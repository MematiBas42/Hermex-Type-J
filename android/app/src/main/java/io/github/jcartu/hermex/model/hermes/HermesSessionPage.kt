package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.TolerantObjectSerializer
import io.github.jcartu.hermex.model.lossyBool
import io.github.jcartu.hermex.model.lossyDouble
import io.github.jcartu.hermex.model.lossyInt
import io.github.jcartu.hermex.model.lossyString
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Port of `HermesSessionPage` (`Networking/Hermes/HermesSessionPage.swift`): one
 * `GET /api/sessions` page (#1046). Ordinary paging ignores [total]; the source-scoped cron
 * list uses it only for its count badge. A row that can't be read is skipped, yet still counts
 * in [count], so it never ends paging early. A page without its `sessions` list is a failed
 * read, not an empty Profile.
 */
@Serializable(with = HermesSessionPageSerializer::class)
data class HermesSessionPage(
    val rows: List<HermesSessionRow>,
    /** How many rows the host sent, readable or not. */
    val count: Int = rows.size,
    /** Source-scoped total, used only by the scheduled section badge, never to end paging. */
    val total: Int? = null,
)

internal object HermesSessionPageSerializer : TolerantObjectSerializer<HermesSessionPage>("HermesSessionPage") {
    override fun fromJson(json: Json, obj: JsonObject): HermesSessionPage {
        val slots = obj["sessions"] as? JsonArray ?: throw SerializationException("A sessions page without its list")
        val rows = slots.mapNotNull { element -> (element as? JsonObject)?.let { HermesSessionRow.fromJsonOrNull(it) } }
        return HermesSessionPage(rows = rows, count = slots.size, total = lossyInt(obj["total"]))
    }

    override fun toJson(json: Json, value: HermesSessionPage): JsonObject = buildJsonObject {
        put("sessions", JsonArray(value.rows.map { json.encodeToJsonElement(HermesSessionRowSerializer, it) }))
        value.total?.let { put("total", it) }
    }
}

/**
 * Port of `HermesSessionRow`: one session row of a Hermes Profile's list. Every field but
 * [id] is optional, and unknown ones are ignored. A row's identity is its `_lineage_root_id`,
 * which only a legacy compression chain carries, else its [id]; it is opened and changed by
 * [id], that chain's tip.
 */
@Serializable(with = HermesSessionRowSerializer::class)
data class HermesSessionRow(
    val id: String,
    val source: String? = null,
    /** Changed in place by a rename this phone made (#1048), as are [pinned] and [archived]. */
    val title: String? = null,
    /** The first prompt, flattened onto one line and cut at 60 characters with `...`. */
    val preview: String? = null,
    val lastActive: Double? = null,
    val startedAt: Double? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    /** The host's read mark. A session no client has marked reads as read. */
    val unread: Boolean? = null,
    /** Hidden from the list, as a bot's Bot Chat is; only the Archived screen shows such a row. */
    val hidden: Boolean? = null,
    val model: String? = null,
    val cwd: String? = null,
    val messageCount: Int? = null,
    val profile: String? = null,
    val parentSessionID: String? = null,
    val lineageRootID: String? = null,
) {
    val identity: String get() = lineageRootID ?: id

    /** A bot's canonical Bot Chat (#1048): hidden, under its exact title. */
    val isBotChat: Boolean get() = hidden == true && title == HermesCall.BOT_CHAT_TITLE

    /**
     * The Bot Chat root this row's chat caches under (#1144): an [isBotChat] row's identity, or
     * the root of a legacy compression chain the host lists under its tip's "Bot Chat (continued)"
     * title. Null for any other row.
     */
    val botChatRoot: String?
        get() {
            if (hidden != true) return null
            if (title == HermesCall.BOT_CHAT_TITLE) return identity
            return if (title == HermesCall.BOT_CHAT_TITLE + " (continued)") lineageRootID else null
        }

    /**
     * A group room's session (#1127): hidden, titled `Group: <room id>`, or Desktop's older
     * `Group: <name> · …`. No screen lists or opens one (#1146).
     */
    val isRoomSession: Boolean get() = hidden != false && title?.startsWith("Group: ") == true

    companion object {
        /** `init(from:)` — null for a row without an id. */
        fun fromJsonOrNull(row: JsonObject): HermesSessionRow? {
            val id = lossyString(row["id"])?.takeIf { it.isNotEmpty() } ?: return null
            // `Date(timeIntervalSince1970:)` takes NaN and infinity and traps later, in formatting.
            fun time(key: String): Double? = lossyDouble(row[key])?.takeIf { it.isFinite() }
            val root = lossyString(row["_lineage_root_id"])
            return HermesSessionRow(
                id = id,
                title = lossyString(row["title"]),
                preview = lossyString(row["preview"]),
                lastActive = time("last_active"),
                startedAt = time("started_at"),
                pinned = lossyBool(row["pinned"]),
                archived = lossyBool(row["archived"]),
                unread = lossyBool(row["unread"]),
                hidden = lossyBool(row["hidden"]),
                model = lossyString(row["model"]),
                cwd = lossyString(row["cwd"]),
                messageCount = lossyInt(row["message_count"]),
                profile = lossyString(row["profile"]),
                parentSessionID = lossyString(row["parent_session_id"]),
                lineageRootID = root?.takeIf { it.isNotEmpty() },
                source = lossyString(row["source"]),
            )
        }
    }
}

internal object HermesSessionRowSerializer : TolerantObjectSerializer<HermesSessionRow>("HermesSessionRow") {
    override fun fromJson(json: Json, obj: JsonObject): HermesSessionRow =
        HermesSessionRow.fromJsonOrNull(obj) ?: throw SerializationException("A session row without an id")

    override fun toJson(json: Json, value: HermesSessionRow): JsonObject = buildJsonObject {
        put("id", value.id)
        value.source?.let { put("source", it) }
        value.title?.let { put("title", it) }
        value.preview?.let { put("preview", it) }
        value.lastActive?.let { put("last_active", it) }
        value.startedAt?.let { put("started_at", it) }
        value.pinned?.let { put("pinned", it) }
        value.archived?.let { put("archived", it) }
        value.unread?.let { put("unread", it) }
        value.hidden?.let { put("hidden", it) }
        value.model?.let { put("model", it) }
        value.cwd?.let { put("cwd", it) }
        value.messageCount?.let { put("message_count", it) }
        value.profile?.let { put("profile", it) }
        value.parentSessionID?.let { put("parent_session_id", it) }
        value.lineageRootID?.let { put("_lineage_root_id", it) }
    }
}

/**
 * Port of `HermesSessionSearch`: one `GET /api/sessions/search` reply (#1053), id matches
 * first, then message-content matches, one per compression lineage, archived and hidden
 * sessions included, except a room's. A result that can't be read is skipped; a reply
 * without `results` is a failed read.
 */
@Serializable(with = HermesSessionSearchSerializer::class)
data class HermesSessionSearch(val results: List<HermesSessionSearchResult>)

internal object HermesSessionSearchSerializer : TolerantObjectSerializer<HermesSessionSearch>("HermesSessionSearch") {
    override fun fromJson(json: Json, obj: JsonObject): HermesSessionSearch {
        val slots = obj["results"] as? JsonArray ?: throw SerializationException("A search reply without results")
        return HermesSessionSearch(
            slots.mapNotNull { element -> (element as? JsonObject)?.let { HermesSessionSearchResult.fromJsonOrNull(it) } }
                .filter { !it.row.isRoomSession },
        )
    }

    override fun toJson(json: Json, value: HermesSessionSearch): JsonObject = buildJsonObject {
        put(
            "results",
            JsonArray(
                value.results.map { result ->
                    buildJsonObject {
                        put("session_id", result.row.id)
                        result.row.title?.let { put("title", it) }
                        result.row.preview?.let { put("preview", it) }
                        result.row.lastActive?.let { put("last_active", it) }
                        result.row.startedAt?.let { put("started_at", it) }
                        result.row.archived?.let { put("archived", it) }
                        result.row.model?.let { put("model", it) }
                        result.row.messageCount?.let { put("message_count", it) }
                        result.row.profile?.let { put("profile", it) }
                        result.row.parentSessionID?.let { put("parent_session_id", it) }
                        result.row.lineageRootID?.let { put("lineage_root", it) }
                        result.row.source?.let { put("source", it) }
                        result.role?.let { put("role", it) }
                        result.snippet?.let { put("snippet", it) }
                    }
                },
            ),
        )
    }
}

/** Port of `HermesSessionSearchResult`: the session as a list row, and the message text it matched on. */
data class HermesSessionSearchResult(
    /** Opened by `session_id`, the lineage's tip, and merged with the list's rows by `lineage_root`. */
    val row: HermesSessionRow,
    /** A content match's FTS `snippet()`; null for an id match, whose snippet is only its preview. */
    val snippet: String? = null,
    /** Who wrote a content match's message (`user`, `assistant`); null for an id match. */
    val role: String? = null,
) {
    companion object {
        fun fromJsonOrNull(result: JsonObject): HermesSessionSearchResult? {
            fun nonEmpty(key: String): String? = lossyString(result[key])?.takeIf { it.isNotEmpty() }
            val id = nonEmpty("session_id") ?: nonEmpty("id") ?: return null
            fun time(key: String): Double? = lossyDouble(result[key])?.takeIf { it.isFinite() }
            val title = lossyString(result["title"])
            val row = HermesSessionRow(
                id = id, title = title, preview = lossyString(result["preview"]),
                // A content match without the session's row has no `last_active`.
                lastActive = time("last_active") ?: time("session_started"),
                startedAt = time("started_at") ?: time("session_started"),
                archived = lossyBool(result["archived"]),
                // Without `hidden`, the title is the tell: a bot's canonical Bot Chat is the
                // session under that exact title, and the host refuses renaming it.
                hidden = if (title == HermesCall.BOT_CHAT_TITLE) true else null,
                model = lossyString(result["model"]), messageCount = lossyInt(result["message_count"]),
                profile = nonEmpty("profile"), parentSessionID = nonEmpty("parent_session_id"),
                lineageRootID = nonEmpty("lineage_root"), source = nonEmpty("source"),
            )
            // An id match has no `role`; its snippet repeats the preview or says "Session ID: …".
            val role = nonEmpty("role")
            return HermesSessionSearchResult(row = row, snippet = if (role == null) null else nonEmpty("snippet"), role = role)
        }
    }
}

/**
 * Port of `ProjectSummary.Hermes` folded into one row (`Networking/Hermes/HermesProjects.swift`):
 * one lane of a Hermes Profile's project tree (#1052).
 */
data class HermesProject(
    val projectId: String,
    val name: String,
    val color: String? = null,
    /** The project's primary folder on the host; null for a lane without one. */
    val folder: String? = null,
    /** An automatic per-repository lane the host groups the rest into. */
    val isAutomatic: Boolean = false,
    val sessionCount: Int = 0,
    /** How many of the list's session ids this lane claims. */
    val claimedCount: Int = 0,
)

/**
 * Port of `HermesSessionWriteIdentity`'s consumer-visible refusals (`HermesSessionActions.swift`):
 * a session change a Hermes host refused with its reason (#1048): a PATCH's 400 `{detail}`.
 */
class HermesSessionRefusal(
    override val message: String,
    /** Every session an import refusal named; empty for any other refusal. */
    val entries: List<Entry> = emptyList(),
) : Exception() {
    /** One session an import's 400 named (#1213). */
    data class Entry(val index: Int, val sessionID: String?, val reason: String)
}

/**
 * Port of `HermesSessionFenceRefusal`: a session write the host's write fence refused with
 * 409 (#1300). Nothing was written, and the write is never resent.
 */
enum class HermesSessionFenceRefusal {
    /** The session moved past the revision the write read, or its runtime changed under it. */
    CHANGED,

    /** A turn holds the session. */
    BUSY,

    /** The host asks for a write identity this build doesn't send. */
    UPDATE_REQUIRED,
    ;

    val message: String
        get() = when (this) {
            CHANGED -> "This session changed on the server. Try again."
            BUSY -> "This session is busy. Try again when it finishes."
            UPDATE_REQUIRED -> "Update Hermex to change sessions on this server."
        }

    /** The refusal as a throwable, so a write can raise it. */
    fun asException(): HermesSessionFenceRefused = HermesSessionFenceRefused(this)

    companion object {
        /** `init?(detail:)` — the refusal a 409's `detail` names; null for any other reason. */
        fun fromDetail(detail: String?): HermesSessionFenceRefusal? = when {
            detail == null -> null
            detail == "revision_conflict" || detail == "stale_generation" || detail == "admission_conflict" -> CHANGED
            detail == "session_busy" -> BUSY
            detail == "mutation_identity_required" -> UPDATE_REQUIRED
            detail.startsWith("client_upgrade_required") -> UPDATE_REQUIRED
            else -> null
        }
    }
}

/** [HermesSessionFenceRefusal] thrown from a session write. */
class HermesSessionFenceRefused(val refusal: HermesSessionFenceRefusal) : Exception() {
    override val message: String get() = refusal.message
}
