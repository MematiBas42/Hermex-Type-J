package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.model.*
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.CacheFallbackPolicy
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.sessions.*

/** The BotTransport subset read by the Sessions list and link lookup (SessionListViewModel.swift). */
interface HermesSessionWire {
    var onEvent: ((BotJSON) -> Unit)?
    var onDisconnect: ((Throwable) -> Unit)?
    suspend fun connect()
    suspend fun close()
    suspend fun call(call: HermesCall): BotJSON
    suspend fun currentProfile(): String
    suspend fun page(profile: String, offset: Int, archived: Boolean, scheduled: Boolean = false): HermesSessionPage
    suspend fun search(query: String, profile: String, scheduled: Boolean = false): List<HermesSessionSearchResult>
    suspend fun row(key: String, profile: String): BotJSON?
    suspend fun update(change: HermesSessionChange, key: String, profile: String): String?
    suspend fun delete(key: String, profile: String): HermesSessionDeletion.Outcome
    suspend fun duplicate(key: String, profile: String, title: String): HermesSessionDuplication.Outcome
    suspend fun export(key: String, profile: String): ByteArray
}

/** One consumer of the shared Hermes connection. Transport callbacks are deliberately not moved off HermesActor here. */
class BotClientSessionWire(private val client: BotClient) : HermesSessionWire {
    override var onEvent: ((BotJSON) -> Unit)?
        get() = client.onEvent
        set(value) { client.onEvent = value }
    override var onDisconnect: ((Throwable) -> Unit)?
        get() = client.onDisconnect
        set(value) { client.onDisconnect = value }
    override suspend fun connect() = client.connect()
    override suspend fun close() = client.close()
    override suspend fun call(call: HermesCall) = client.call(call)
    override suspend fun currentProfile() = client.currentProfile()
    override suspend fun page(profile: String, offset: Int, archived: Boolean, scheduled: Boolean) =
        if (scheduled) client.scheduledSessionPage(profile, offset, archived) else client.sessionPage(profile, offset, archived)
    override suspend fun search(query: String, profile: String, scheduled: Boolean) =
        if (scheduled) client.searchScheduledSessions(query, profile, HermesREST.SESSION_SEARCH_LIMIT)
        else client.searchSessions(query, profile, HermesREST.SESSION_SEARCH_LIMIT)
    override suspend fun row(key: String, profile: String) = client.sessionRow(key, profile)
    override suspend fun update(change: HermesSessionChange, key: String, profile: String) = client.updateSession(change, key, profile)
    override suspend fun delete(key: String, profile: String) = HermesSessionDeletion.delete(key, profile, client)
    override suspend fun duplicate(key: String, profile: String, title: String) = HermesSessionDuplication.duplicate(key, profile, title, client)
    override suspend fun export(key: String, profile: String) = client.exportSession(key, profile)
}

/** Port of HermesSessionRow.summary(in:project:), without changing the webui model's wire/persistence shape. */
fun HermesSessionRow.summary(listedProfile: String, project: String? = null): SessionSummary = SessionSummary(
    sessionId = id,
    title = if (isBotChat) "Bot Chat · ${profile?.takeIf { it.isNotEmpty() } ?: listedProfile}" else MessageAttachment.hermesTitle(title),
    workspace = cwd, model = model, messageCount = messageCount, createdAt = startedAt, lastMessageAt = lastActive,
    pinned = pinned, archived = archived, projectId = project, profile = profile?.takeIf { it.isNotEmpty() } ?: listedProfile,
    sessionSource = source, parentSessionId = parentSessionID,
)

/** The identity includes the Profile: identical stored keys in two Profile stores must never share actions or marks. */
data class HermesListedSession(val profile: String, val row: HermesSessionRow) {
    val id: String get() = "$profile|${row.identity}"
    val key: String get() = "$profile|${row.id}"
    val displayTitle: String get() = row.summary(profile).title?.takeIf { it.isNotBlank() }
        ?: MessageAttachment.hermesTitle(row.preview) ?: "Untitled Session"
    fun summary(project: String? = null) = row.summary(profile, project)
}

/** Direct-Hermes implementation of the list seam; all mutations retain the row's own Profile. */
class HermesSessionListDataSource(private val wire: () -> HermesSessionWire) : SessionListDataSource {
    override suspend fun page(profile: String?, offset: Int, archived: Boolean): SessionListPage {
        val name = requireNotNull(profile)
        val page = wire().page(name, offset, archived)
        return SessionListPage(page.rows.map { it.summary(name) }, hermesPage = page)
    }
    override suspend fun setPinned(pinned: Boolean, session: SessionSummary) {
        wire().update(HermesSessionChange.Pinned(pinned), key(session), profile(session))
    }
    override suspend fun setArchived(archived: Boolean, session: SessionSummary) {
        wire().update(HermesSessionChange.Archived(archived), key(session), profile(session))
    }
    override suspend fun delete(session: SessionSummary) = wire().delete(key(session), profile(session)).message
    override suspend fun rename(session: SessionSummary, title: String): SessionMutationResponse {
        val kept = wire().update(HermesSessionChange.Title(title), key(session), profile(session))?.takeIf { it.isNotBlank() } ?: title
        return SessionMutationResponse(ok = true, session = session.copy(title = kept))
    }
    override suspend fun duplicate(session: SessionSummary, title: String): SessionDuplicateResult {
        val copy = wire().duplicate(key(session), profile(session), title)
        return SessionDuplicateResult(HermesSessionRow(copy.key, title = copy.title).summary(profile(session)), null)
    }
    override suspend fun export(session: SessionSummary, format: SessionExportFormat): SessionExportFile {
        require(format == SessionExportFormat.json) { "Hermes exports sessions as JSON only." }
        return SessionExportFile(wire().export(key(session), profile(session)), SessionExportFile.filename(null, session.title, key(session), format))
    }
    private fun key(row: SessionSummary) = requireNotNull(row.sessionId?.takeIf { it.isNotEmpty() })
    private fun profile(row: SessionSummary) = requireNotNull(row.profile?.takeIf { it.isNotEmpty() })
}

/** Port of Networking/CacheFallbackPolicy.swift's Hermes arm (#1054), including tunnel 520–530 responses. */
fun hermesUsesOfflineCache(error: Throwable): Boolean = when (error) {
    BotFailure.Transport -> true
    is BotFailure.Network -> CacheFallbackPolicy.shouldUseCache(error.underlying)
    is BotFailure.Rejected -> error.code == 408 || error.code in 502..504 || error.code in 520..530
    else -> CacheFallbackPolicy.shouldUseCache(error)
}
