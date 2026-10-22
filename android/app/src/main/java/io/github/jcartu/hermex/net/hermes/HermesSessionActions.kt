package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionRefusal
import io.github.jcartu.hermex.model.hermes.HermesSessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Port of `HermesSessionDuplication` (`Networking/Hermes/HermesSessionActions.swift`):
 * Duplicate on a Hermes row (#1051): an independent copy with every row the session's export
 * holds, which `session.branch_stored` would drop. The export (#1048) is imported under a new
 * id with no parent, then titled.
 *
 * The import is all-or-nothing: a refused one leaves nothing behind. It skips an id the
 * Profile already has, and fails the whole import on a title in use, so the copy goes in
 * untitled and is titled after, "<title> (copy)", then "(copy 2)" and on while the host has
 * the title. A copy the host won't title stays untitled.
 */
object HermesSessionDuplication {
    /** The host's import limits per session: 10,000 messages, and 5 MB of JSON. A whole request past 25 MB is 413. */
    const val MESSAGE_LIMIT = 10_000
    const val BYTE_LIMIT = 5 * 1024 * 1024

    /** How many titles a copy tries before it stays untitled. */
    const val TITLE_ATTEMPTS = 10

    class TooLarge : Exception() {
        override val message: String get() = "This session is too large to duplicate."
    }

    /** The copy's key and the title the host kept, null when it stayed untitled. */
    data class Outcome(val key: String, val title: String?)

    /**
     * Copies the session [key] of [profile], titled after [title]. Throws [TooLarge] past the
     * host's limits, the host's refusal as [HermesSessionRefusal], and [BotFailure.Unsupported]
     * when the host imported no copy.
     */
    suspend fun duplicate(key: String, profile: String, title: String, wire: BotClient): Outcome {
        val export = wire.exportSession(key, profile)
        val id = newID(Date())
        // An export can be megabytes, so it is read and the copy written off the actor.
        val body = withContext(Dispatchers.Default) { importBody(export, id, profile) }
        val result = try {
            wire.importSessions(body)
        } catch (error: BotFailure.Rejected) {
            if (error.code == 413) throw TooLarge()
            throw error
        }
        if (result["imported_ids"].list?.any { it.text == id } != true) throw BotFailure.Unsupported
        for (attempt in 1..TITLE_ATTEMPTS) {
            val candidate = title(title, attempt)
            try {
                val kept = wire.updateSession(HermesSessionChange.Title(candidate), id, profile)
                return Outcome(id, kept?.takeIf { it.isNotEmpty() } ?: candidate)
            } catch (error: HermesSessionRefusal) {
                continue
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                break
            }
        }
        return Outcome(id, null)
    }

    /** The import of [export]'s copy as [id] into [profile], refused as [TooLarge] past the host's limits. */
    private fun importBody(export: ByteArray, id: String, profile: String): ByteArray {
        val copy = copy(BotJSON.parse(export), id) ?: throw BotFailure.Unsupported
        if ((copy["messages"].list?.size ?: 0) > MESSAGE_LIMIT) throw TooLarge()
        val body = BotJSON.objectOf("sessions" to BotJSON.array(listOf(copy)), "profile" to BotJSON.string(profile)).encoded.toByteArray()
        if (body.size > BYTE_LIMIT) throw TooLarge()
        return body
    }

    /**
     * [export] as a new session [id]: without its parent, compression lineage and derived
     * timings, its messages without their row ids, untitled, and neither archived nor pinned.
     * Null for an export that is not a session with its messages.
     */
    fun copy(export: BotJSON, id: String): BotJSON? {
        val fields = export.element as? JsonObject ?: return null
        val messages = fields["messages"] as? JsonArray ?: return null
        val kept = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((key, value) in fields) {
            if (key.startsWith("_lineage_") || key == "parent_session_id" || key == "timings") continue
            kept[key] = value
        }
        kept["id"] = JsonPrimitive(id)
        kept["title"] = JsonNull
        kept["archived"] = JsonPrimitive(false)
        kept["pinned"] = JsonPrimitive(false)
        kept["messages"] = JsonArray(
            messages.map { message -> JsonObject((message as? JsonObject).orEmpty().filterKeys { it != "id" }) },
        )
        return BotJSON(JsonObject(kept))
    }

    /** A session id in the host's shape (`hermes_state_ids.py`), `YYYYMMDD_HHMMSS_<6 hex>`, at [date] in this device's time zone. */
    fun newID(date: Date): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return formatter.format(date) + "_" + UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
    }

    /** The copy's title for one attempt, with [title] cut so the whole fits the host's 100 characters. */
    fun title(title: String, attempt: Int): String {
        val suffix = if (attempt == 1) " (copy)" else " (copy $attempt)"
        val room = 100 - suffix.codePointCount(0, suffix.length)
        val base = if (title.codePointCount(0, title.length) <= room) title else title.substring(0, title.offsetByCodePoints(0, room))
        return base.trim(' ') + suffix
    }
}

/**
 * Port of `HermesBranchParent`: a Hermes session's parent, for its "Forked from" row and Fork
 * From Here (#1051). A branch's own row names its parent (`parent_session_id`) and stores
 * `{"_branched_from": <parent>}` as its `model_config`.
 */
object HermesBranchParent {
    /** The parent's row when [key] of [profile] is a branch of a session the host still has; null otherwise. */
    suspend fun row(key: String, profile: String, wire: BotClient): HermesSessionRow? {
        val own = wire.sessionRow(key, profile) ?: return null
        val parent = own["parent_session_id"].text?.takeIf { it.isNotEmpty() } ?: return null
        if (own.modelConfigText("_branched_from") != parent) return null
        val row = wire.sessionRow(parent, profile) ?: return null
        val grandparent = row["parent_session_id"].text
        return HermesSessionRow(
            id = parent, title = row["title"].text, profile = profile,
            parentSessionID = grandparent?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Whether a session's own row [own] holds its whole history as the host counts a branch:
     * it has no parent, or it is a branch, whose rows are a copy.
     */
    fun standsAlone(own: BotJSON): Boolean =
        (own["parent_session_id"].text ?: "").isEmpty() || !(own.modelConfigText("_branched_from") ?: "").isEmpty()
}

/**
 * `modelConfigText(_:)` — [key] in a stored session row's `model_config`, which the host
 * stores as a JSON string and may send as an object.
 */
fun BotJSON.modelConfigText(key: String): String? {
    val config = this["model_config"]
    val obj = config.text?.let { BotJSON.parseOrNull(it) } ?: config
    return obj[key].text
}

/**
 * Port of `HermesSessionDeletion`: deletes a Hermes session through `session.delete`, never
 * REST `DELETE`, so a runtime that holds the session blocks it instead of losing that work.
 * This phone's own runtimes on the session are closed first when idle, and a busy one refuses
 * the delete before anything is sent. A 4023 after that is a runtime this phone did not attach.
 */
object HermesSessionDeletion {
    enum class Outcome {
        DELETED,

        /** A reply runs, or waits on an answer, in a runtime this phone attached. */
        BUSY_HERE,

        /** The host refused (4023): a runtime this phone did not attach holds the session. */
        OPEN_ELSEWHERE,
        ;

        /** What the list and the Archived screen say when the host kept the session. */
        val message: String?
            get() = when (this) {
                DELETED -> null
                BUSY_HERE -> "This session is still replying here. Stop the reply first, then delete it."
                OPEN_ELSEWHERE -> "This session is open in another app, so nothing was deleted. Close it there, then try again."
            }
    }

    suspend fun delete(key: String, profile: String, wire: BotClient): Outcome {
        // An older host without the live list still refuses a held session with 4023.
        val live = try {
            wire.call(HermesCall.SessionActiveList)["sessions"].list ?: emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyList()
        }
        val attached = wire.attachedRuntimes
        val own = live.filter { it["session_key"].text == key && (it["id"].text ?: "") in attached }
        if (own.any { HermesSessionRowStates.from(listOf(it)).isNotEmpty() }) return Outcome.BUSY_HERE
        for (runtime in own.mapNotNull { it["id"].text }) {
            wire.call(HermesCall.SessionClose(runtime))
        }
        try {
            wire.call(HermesCall.SessionDelete(profile, key))
        } catch (error: BotFailure.Rejected) {
            if (error.code == 4023) return Outcome.OPEN_ELSEWHERE
            throw error
        }
        return Outcome.DELETED
    }
}
