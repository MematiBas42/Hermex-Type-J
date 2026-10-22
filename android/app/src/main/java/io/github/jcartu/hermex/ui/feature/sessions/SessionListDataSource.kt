package io.github.jcartu.hermex.ui.feature.sessions

import io.github.jcartu.hermex.model.*
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.HermesApi

/** The list's typed read: webui's whole list, or one Hermes page (HermesSessionListSource.swift). */
data class SessionListPage(
    val sessions: List<SessionSummary>,
    val archivedCount: Int? = null,
    val hermesPage: HermesSessionPage? = null,
)

/**
 * Port of the webui/Hermes source boundary in Features/SessionList/SessionListViewModel.swift.
 * The two ViewModels keep their own live/read state; row reads and actions use this seam.
 * A Hermes implementation requires the row's Profile and never reaches the webui API.
 */
interface SessionListDataSource {
    suspend fun page(profile: String? = null, offset: Int = 0, archived: Boolean = false): SessionListPage
    suspend fun setPinned(pinned: Boolean, session: SessionSummary)
    suspend fun setArchived(archived: Boolean, session: SessionSummary)
    /** Null means deleted; otherwise the host kept the row and this is the reason. */
    suspend fun delete(session: SessionSummary): String?
    suspend fun rename(session: SessionSummary, title: String): SessionMutationResponse
    suspend fun duplicate(session: SessionSummary, title: String): SessionDuplicateResult
    suspend fun export(session: SessionSummary, format: SessionExportFormat): SessionExportFile
}

/** Today's webui implementation, extracted unchanged from SessionListViewModel and SessionMutator. */
class WebuiSessionListDataSource(private val api: HermesApi) : SessionListDataSource {
    private val mutator = SessionMutator(api)
    override suspend fun page(profile: String?, offset: Int, archived: Boolean): SessionListPage {
        val response = api.sessions(includeArchived = archived)
        return SessionListPage(response.sessions.orEmpty(), response.archivedCount)
    }
    override suspend fun setPinned(pinned: Boolean, session: SessionSummary) = mutator.setPinned(pinned, key(session))
    override suspend fun setArchived(archived: Boolean, session: SessionSummary) {
        if (archived) mutator.archive(key(session)) else mutator.unarchive(key(session))
    }
    override suspend fun delete(session: SessionSummary): String? {
        mutator.delete(key(session))
        return null
    }
    override suspend fun rename(session: SessionSummary, title: String) = mutator.rename(key(session), title)
    override suspend fun duplicate(session: SessionSummary, title: String) = mutator.duplicate(key(session), title)
    override suspend fun export(session: SessionSummary, format: SessionExportFormat) =
        api.exportSession(key(session), format, session.title)
    private fun key(session: SessionSummary) = requireNotNull(session.sessionId?.takeIf { it.isNotEmpty() })
}
