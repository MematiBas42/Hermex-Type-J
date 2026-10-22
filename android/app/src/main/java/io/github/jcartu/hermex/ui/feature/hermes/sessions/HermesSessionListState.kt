package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.hermes.HermesProject
import io.github.jcartu.hermex.ui.feature.sessions.SessionRowAttentionState
import io.github.jcartu.hermex.ui.feature.sessions.SessionSearchExcerpt

/** Port of SessionListViewModel.swift's Hermes state; Bots never shares its read marks/live states. */
data class HermesSessionListState(
    val sessions: List<HermesListedSession> = emptyList(),
    val profile: String? = null,
    val profiles: List<String> = emptyList(),
    val showsAllProfiles: Boolean = false,
    val archived: Boolean = false,
    val projects: List<HermesProject> = emptyList(),
    val projectOwners: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val pageOffset: Int = 0,
    val isLoadingMoreScheduled: Boolean = false,
    val hasMoreScheduled: Boolean = false,
    val scheduledTotal: Int = 0,
    val isConnected: Boolean = false,
    val isLive: Boolean = false,
    val isViewingCachedData: Boolean = false,
    val isCachedCopyProvisional: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val renameErrorMessage: String? = null,
    val projectErrorMessage: String? = null,
    val searchErrorMessage: String? = null,
    val cacheErrorMessage: String? = null,
    val isLoadingProjects: Boolean = false,
    val isSavingProject: Boolean = false,
    val isRenamingSession: Boolean = false,
    val mutating: Set<String> = emptySet(),
    val attentionStates: Map<String, SessionRowAttentionState> = emptyMap(),
    val unreadMarks: Map<String, Boolean> = emptyMap(),
    val query: String = "",
    val searchResults: List<HermesListedSession> = emptyList(),
    val searchSnippets: Map<String, String> = emptyMap(),
    val isSearching: Boolean = false,
) {
    val showsOfflineCache: Boolean get() = isViewingCachedData && !isCachedCopyProvisional
    fun isUnread(session: HermesListedSession): Boolean = !isViewingCachedData && !session.row.isBotChat &&
        (unreadMarks[session.key] ?: (session.row.unread == true))
    fun canMutate(session: HermesListedSession): Boolean = isLive && !isViewingCachedData && !session.row.isBotChat &&
        !session.summary().isSessionReadOnly && session.key !in mutating
    fun attention(session: HermesListedSession): SessionRowAttentionState? = if (isViewingCachedData) null else attentionStates[session.key]
    fun excerpt(session: HermesListedSession): SessionSearchExcerpt? = searchSnippets[session.id]?.takeIf { query.isNotEmpty() }
        ?.let { SessionSearchExcerpt.hermesSnippet(it, query) }

    /** Host hits follow local matches in host order; a loaded row's metadata wins by Profile + lineage. */
    fun visibleSessions(selectedProjectId: String? = null): List<HermesListedSession> {
        val terms = query.trim().lowercase().split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }
        fun inLane(row: HermesListedSession) = selectedProjectId == null || projectOwners[row.row.id] == selectedProjectId
        val local = sessions.filter { session ->
            (archived || session.row.hidden != true) && !session.row.isRoomSession && inLane(session) &&
                (terms.isEmpty() || searchableText(session).let { text -> terms.all(text::contains) })
        }.sortedWith(compareByDescending<HermesListedSession> { it.row.pinned == true }
            .thenByDescending { it.row.lastActive ?: it.row.startedAt ?: 0.0 }.thenBy { it.id })
        if (query.isBlank() || isViewingCachedData) return local
        val seen = local.mapTo(HashSet()) { it.id }
        val loaded = sessions.associateBy { it.id }
        return local + searchResults.mapNotNull { match ->
            val row = loaded[match.id] ?: match
            row.takeIf { !it.row.isRoomSession && inLane(it) && seen.add(it.id) }
        }
    }
    private fun searchableText(session: HermesListedSession) = listOfNotNull(
        MessageAttachment.hermesTitle(session.row.title), MessageAttachment.hermesTitle(session.row.preview),
        session.row.cwd, session.row.model, session.profile, session.row.source,
    ).joinToString(" ").lowercase()
}
