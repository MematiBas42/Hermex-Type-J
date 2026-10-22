package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionRefusal
import io.github.jcartu.hermex.net.hermes.HermesREST
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/** Read-only exact-id scan from `MoveToHermesScan.swift` (#1213). */
object MoveToHermesScanner {
    suspend fun scan(
        webui: MoveWebuiClient,
        hermes: MoveHermesClient,
        catalog: HermesMoveProfileCatalog,
        progress: (located: Int, total: Int) -> Unit = { _, _ -> },
    ): MoveScanResult {
        val listing = webui.scanListing()
        val seen = HashSet<String>()
        val chats = listing.sessions.filter { seen.add(it.id) }
        progress(0, chats.size)

        data class Listed(val ids: Set<String>, val failed: Boolean)
        val listed = catalog.profiles.associateWith { profile ->
            val ids = LinkedHashSet<String>()
            var offset = 0
            var failed = false
            while (!failed) {
                val page = try {
                    hermes.sessionScanPage(profile, offset)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failed = true
                    break
                }
                for (row in page.rows) {
                    ids += row.id
                    row.lineageRootID?.let(ids::add)
                }
                offset += HermesREST.SESSION_PAGE_SIZE
                val total = page.total
                if (total != null) {
                    if (offset >= total) break
                } else if (page.count < HermesREST.SESSION_PAGE_SIZE) {
                    break
                } else {
                    // A full page without a total cannot prove there is no next page.
                    failed = true
                }
            }
            Listed(ids, failed)
        }

        val entries = ArrayList<MoveScanEntry>(chats.size)
        chats.forEachIndexed { index, chat ->
            val listedMatches = catalog.profiles.filter { chat.id in listed.getValue(it).ids }.toMutableSet()
            val profilesToRead = if (listedMatches.isEmpty()) {
                catalog.profiles
            } else {
                catalog.profiles.filter { listed.getValue(it).failed }
            }
            var readFailed = false
            for (profile in profilesToRead) {
                try {
                    val row = hermes.sessionRow(chat.id, profile)
                    if (row != null && row["id"].text == chat.id) listedMatches += profile
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    readFailed = true
                }
            }
            val ordered = catalog.profiles.filter { it in listedMatches }
            val location = when {
                ordered.size > 1 -> MoveChatLocation.ambiguous(ordered)
                readFailed -> MoveChatLocation.Unknown
                ordered.size == 1 -> MoveChatLocation.present(ordered.single())
                else -> MoveChatLocation.Missing
            }
            entries += MoveScanEntry(chat, location)
            progress(index + 1, chats.size)
        }
        return MoveScanResult(
            entries = entries,
            onlyProfile = if (listing.allProfiles == true) null else listing.activeProfile,
            runningSessionIds = chats.filter { it.isRunning }.map { it.id },
        )
    }

    /** Exact-id recheck before an idempotent import or a resumed in-flight batch. */
    suspend fun locate(sessionId: String, profiles: List<String>, hermes: MoveHermesClient): MoveChatLocation {
        val found = mutableListOf<String>()
        var failed = false
        for (profile in profiles) {
            try {
                val row = hermes.sessionRow(sessionId, profile)
                if (row != null && row["id"].text == sessionId) found += profile
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failed = true
            }
        }
        return when {
            found.size > 1 -> MoveChatLocation.ambiguous(found)
            failed -> MoveChatLocation.Unknown
            found.size == 1 -> MoveChatLocation.present(found.single())
            else -> MoveChatLocation.Missing
        }
    }
}

/** Webui export → Hermes `/api/sessions/import` conversion from `WebuiSessionTransfer.swift`. */
object MoveImportCodec {
    const val SESSIONS_PER_IMPORT = 500
    const val MESSAGES_PER_IMPORT = 50_000
    const val BYTES_PER_IMPORT = 25 * 1024 * 1024
    const val MESSAGES_PER_SESSION = 10_000
    const val BYTES_PER_SESSION = 5 * 1024 * 1024

    data class Prepared(
        val id: String,
        val encoded: ByteArray,
        val messageCount: Int,
    )

    sealed interface Preparation {
        data class Ready(val prepared: Prepared) : Preparation
        data object Unreadable : Preparation
        data object Oversized : Preparation
    }

    fun prepare(export: ByteArray, sessionId: String): Preparation {
        val source = BotJSON.parseOrNull(export) ?: return Preparation.Unreadable
        if (source["session_id"].text != sessionId) return Preparation.Unreadable
        val messages = source["messages"].list ?: return Preparation.Unreadable
        val row = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(sessionId),
            "source" to JsonPrimitive("webui"),
            "archived" to JsonPrimitive(source["archived"].flag ?: false),
        )
        source["created_at"].number?.let { row["started_at"] = JsonPrimitive(it) }
        source["model"].text?.let { row["model"] = JsonPrimitive(it) }
        for (key in listOf("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens")) {
            source[key].integer?.let { row[key] = JsonPrimitive(it) }
        }
        source["estimated_cost"].number?.let { row["estimated_cost_usd"] = JsonPrimitive(it) }
        val importedMessages = messages.mapNotNull(::message)
        row["messages"] = JsonArray(importedMessages)
        val encoded = JsonObject(row).toString().toByteArray(Charsets.UTF_8)
        if (importedMessages.size > MESSAGES_PER_SESSION || encoded.size > BYTES_PER_SESSION) {
            return Preparation.Oversized
        }
        return Preparation.Ready(Prepared(sessionId, encoded, importedMessages.size))
    }

    private fun message(source: BotJSON): JsonObject? {
        val fields = source.fields ?: return null
        val role = fields["role"]?.text ?: return null
        if (role !in setOf("user", "assistant", "tool")) return null
        val result = linkedMapOf<String, JsonElement>(
            "role" to JsonPrimitive(role),
            "content" to (fields["content"]?.element ?: JsonNull),
        )
        (fields["_ts"] ?: fields["timestamp"])?.number?.let { result["timestamp"] = JsonPrimitive(it) }
        fields["tool_calls"]?.takeUnless { it.isNull }?.let { result["tool_calls"] = it.element }
        fields["tool_call_id"]?.text?.let { result["tool_call_id"] = JsonPrimitive(it) }
        if (role == "tool") {
            (fields["tool_name"] ?: fields["name"])?.text?.let { result["tool_name"] = JsonPrimitive(it) }
        }
        if (role == "assistant") {
            fields["reasoning"]?.text?.let { result["reasoning"] = JsonPrimitive(it) }
            fields["reasoning_content"]?.text?.let { result["reasoning_content"] = JsonPrimitive(it) }
        }
        return JsonObject(result)
    }

    fun fits(batch: List<Prepared>, destinationProfile: String): Boolean {
        if (batch.size > SESSIONS_PER_IMPORT) return false
        if (batch.sumOf { it.messageCount } > MESSAGES_PER_IMPORT) return false
        // The transport splices request_id + expected_revision into this object. Reserve 128
        // bytes, comfortably above the UUID and two member names, without encoding the payload twice.
        val overhead = 160 + destinationProfile.toByteArray(Charsets.UTF_8).size + maxOf(0, batch.size - 1)
        return batch.sumOf { it.encoded.size } + overhead <= BYTES_PER_IMPORT
    }

    fun body(batch: List<Prepared>, destinationProfile: String): ByteArray {
        val output = ByteArrayOutputStream(batch.sumOf { it.encoded.size } + 64)
        output.write("{\"sessions\":[".toByteArray(Charsets.UTF_8))
        batch.forEachIndexed { index, prepared ->
            if (index > 0) output.write(','.code)
            output.write(prepared.encoded)
        }
        output.write("],\"profile\":".toByteArray(Charsets.UTF_8))
        output.write(JsonPrimitive(destinationProfile).toString().toByteArray(Charsets.UTF_8))
        output.write('}'.code)
        return output.toByteArray()
    }

    fun batches(prepared: List<Prepared>, destinationProfile: String): List<List<Prepared>> {
        val result = mutableListOf<List<Prepared>>()
        var current = mutableListOf<Prepared>()
        for (item in prepared) {
            if (current.isNotEmpty() && !fits(current + item, destinationProfile)) {
                result += current
                current = mutableListOf()
            }
            current += item
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}

data class MoveImportOutcome(
    val state: MoveCopyState,
    val profile: String,
    val reason: String? = null,
)

/** Import batching/refusal reducer from `WebuiSessionTransfer.Attempt` (#1213). */
object MoveImportBatcher {
    private val sessionLimitReasons = setOf(
        "messages exceeds the per-session import limit",
        "session exceeds the import size limit",
    )
    private val totalLimitReasons = setOf(
        "import exceeds the total size limit",
        "messages exceeds the total import limit",
    )

    suspend fun run(
        prepared: List<MoveImportCodec.Prepared>,
        destinationProfile: String,
        hermes: MoveHermesClient,
        willDispatch: suspend (List<String>) -> Unit,
    ): Map<String, MoveImportOutcome> {
        val outcomes = linkedMapOf<String, MoveImportOutcome>()
        for (batch in MoveImportCodec.batches(prepared, destinationProfile)) {
            send(batch, destinationProfile, hermes, willDispatch, outcomes)
        }
        return outcomes
    }

    private suspend fun send(
        batch: List<MoveImportCodec.Prepared>,
        destination: String,
        hermes: MoveHermesClient,
        willDispatch: suspend (List<String>) -> Unit,
        outcomes: MutableMap<String, MoveImportOutcome>,
    ) {
        if (batch.isEmpty()) return
        willDispatch(batch.map { it.id })
        try {
            val result = hermes.importSessions(MoveImportCodec.body(batch, destination))
            val imported = result["imported_ids"].list.orEmpty().mapNotNull { it.text }.toSet()
            val skipped = result["skipped_ids"].list.orEmpty().mapNotNull { it.text }.toSet()
            for (item in batch) {
                outcomes[item.id] = when (item.id) {
                    in imported -> MoveImportOutcome(MoveCopyState.IMPORTED, destination)
                    in skipped -> MoveImportOutcome(MoveCopyState.ALREADY_PRESENT, destination)
                    else -> MoveImportOutcome(MoveCopyState.DISPATCHING, destination)
                }
            }
        } catch (refusal: HermesSessionRefusal) {
            resendAfterRefusal(batch, destination, hermes, willDispatch, outcomes, refusal)
        } catch (failure: BotFailure.Rejected) {
            when {
                failure.code == 413 -> split(batch, destination, hermes, willDispatch, outcomes)
                failure.code in 400..499 && failure.code !in setOf(408, 409, 429) -> batch.forEach {
                    outcomes[it.id] = MoveImportOutcome(MoveCopyState.REJECTED, destination, failure.message)
                }
                else -> throw failure
            }
        }
    }

    private suspend fun resendAfterRefusal(
        batch: List<MoveImportCodec.Prepared>,
        destination: String,
        hermes: MoveHermesClient,
        willDispatch: suspend (List<String>) -> Unit,
        outcomes: MutableMap<String, MoveImportOutcome>,
        refusal: HermesSessionRefusal,
    ) {
        val remaining = mutableListOf<MoveImportCodec.Prepared>()
        var splitForTotals = false
        for ((index, prepared) in batch.withIndex()) {
            val entry = refusal.entries.firstOrNull { it.sessionID == prepared.id }
                ?: refusal.entries.firstOrNull { it.sessionID == null && it.index == index }
            val reason = entry?.reason
            when {
                reason == null -> remaining += prepared
                reason in totalLimitReasons -> {
                    splitForTotals = true
                    remaining += prepared
                }
                reason in sessionLimitReasons -> outcomes[prepared.id] =
                    MoveImportOutcome(MoveCopyState.OVERSIZED, destination, reason)
                else -> outcomes[prepared.id] = MoveImportOutcome(MoveCopyState.REJECTED, destination, reason)
            }
        }
        when {
            splitForTotals -> split(remaining, destination, hermes, willDispatch, outcomes)
            remaining.size == batch.size -> batch.forEach {
                outcomes[it.id] = MoveImportOutcome(MoveCopyState.REJECTED, destination, refusal.message)
            }
            else -> send(remaining, destination, hermes, willDispatch, outcomes)
        }
    }

    private suspend fun split(
        batch: List<MoveImportCodec.Prepared>,
        destination: String,
        hermes: MoveHermesClient,
        willDispatch: suspend (List<String>) -> Unit,
        outcomes: MutableMap<String, MoveImportOutcome>,
    ) {
        if (batch.size <= 1) {
            batch.forEach { outcomes[it.id] = MoveImportOutcome(MoveCopyState.OVERSIZED, destination) }
            return
        }
        val half = batch.size / 2
        send(batch.subList(0, half), destination, hermes, willDispatch, outcomes)
        send(batch.subList(half, batch.size), destination, hermes, willDispatch, outcomes)
    }
}
