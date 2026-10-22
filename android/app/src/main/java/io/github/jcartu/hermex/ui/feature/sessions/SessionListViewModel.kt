package io.github.jcartu.hermex.ui.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.model.ProjectSummary
import io.github.jcartu.hermex.model.SessionExportFile
import io.github.jcartu.hermex.model.SessionExportFormat
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CacheFallbackPolicy
import io.github.jcartu.hermex.net.HermesApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Port of `SessionListSection` from `SessionListViewModel.swift`. */
data class SessionListSection(
    val kind: Kind,
    val title: String,
    val sessions: List<SessionSummary>,
) {
    enum class Kind { PINNED, TODAY, YESTERDAY, EARLIER }

    val id: String get() = kind.name
}

/** Port of `ScheduledSessionGroups` from `SessionListViewModel.swift`. */
data class ScheduledSessionGroups(
    val ordinary: List<SessionSummary>,
    val scheduled: List<SessionSummary>,
    val totalScheduledCount: Int,
) {
    val scheduledPreview: List<SessionSummary>
        get() = scheduled.take(5)

    val hasAdditionalScheduledSessions: Boolean
        get() = scheduled.size > scheduledPreview.size

    fun showsDisclosure(isSearchActive: Boolean): Boolean =
        totalScheduledCount > 0 && (!isSearchActive || scheduled.isNotEmpty())
}

/**
 * Immutable UI state for the session list. Field-for-field port of the
 * `@Observable` properties on the Swift `SessionListViewModel` (minus the
 * iOS-only export-temp-file and App Intents plumbing).
 */
data class SessionListUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingProjects: Boolean = false,
    val isCreatingProject: Boolean = false,
    val isDeletingProject: Boolean = false,
    val isRenamingSession: Boolean = false,
    val isRenamingProject: Boolean = false,
    val isMovingSession: Boolean = false,
    val isViewingCachedData: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val cacheErrorMessage: String? = null,
    val searchErrorMessage: String? = null,
    val isSearchingRemoteSessions: Boolean = false,
    val mutatingSessionIds: Set<String> = emptySet(),
    /**
     * Total archived sessions reported by the last successful list load
     * (`archived_count`, issue #17). Null until a load succeeds or when an
     * older server omits the field — the Archived entry stays hidden then.
     */
    val archivedCount: Int? = null,
    val remoteContentSearchSessionIds: List<String> = emptyList(),
    /** The normalized query the current [remoteContentSearchSessionIds] belong to. */
    val activeRemoteSearchQuery: String? = null,
    /** True when the last list load failed with a connectivity-class error. */
    val loadFailedOffline: Boolean = false,
    /**
     * Attention state per streaming session, refreshed on the same tick that
     * checks stream liveness (port of `attentionStatesBySessionID`, 1.6.0).
     * Only sessions with an active stream ever have an entry, and the map is
     * reassigned only when a value actually changes so rows do not invalidate
     * on every poll tick.
     */
    val attentionStatesBySessionId: Map<String, SessionRowAttentionState> = emptyMap(),
    /**
     * `match_preview` per content-matched session from the last search, so a
     * row can show why it matched (port of `remoteContentSearchExcerpts`,
     * 1.6.0). Empty against servers that omit the field.
     */
    val remoteContentSearchExcerpts: Map<String, String> = emptyMap(),
    /**
     * Device-local last-seen `lastMessageAt` per session id (port of
     * `seenMessageTimes`, 1.7.0 unread replies). Persisted per server.
     */
    val seenMessageTimes: Map<String, Double> = emptyMap(),
    /** Active-profile picker surface (port of the Swift `activeProfile*` properties). */
    val activeProfileName: String? = null,
    val activeProfileDisplayName: String? = null,
    val activeProfileModel: String? = null,
    val activeProfileProvider: String? = null,
    val profileOptions: List<ProfileSummary> = emptyList(),
    val isSingleProfileMode: Boolean = false,
    val isLoadingActiveProfile: Boolean = false,
    val isSwitchingActiveProfile: Boolean = false,
    val switchingActiveProfileName: String? = null,
    val activeProfileErrorMessage: String? = null,
) {
    fun isMutating(session: SessionSummary): Boolean {
        val sessionId = nonEmpty(session.sessionId) ?: return false
        return sessionId in mutatingSessionIds
    }

    /** Port of `SessionListViewModel.attentionState(for:)`. */
    fun attentionStateFor(session: SessionSummary): SessionRowAttentionState? {
        val sessionId = nonEmpty(session.sessionId) ?: return null
        return attentionStatesBySessionId[sessionId]
    }

    /**
     * Port of `SessionListViewModel.isUnread(_:)`: a settled row is unread only
     * when its server timestamp moved past the last timestamp this device
     * showed. No phone clock enters the comparison.
     */
    fun isUnread(session: SessionSummary): Boolean {
        val sessionId = nonEmpty(session.sessionId) ?: return false
        val timestamp = messageTime(session) ?: return false
        val seen = seenMessageTimes[sessionId] ?: return false
        if (isActiveStreaming(session) || session.hasPendingUserMessage == true) return false
        return timestamp > seen
    }

    /** Port of `SessionListViewModel.canToggleUnread(_:)`. */
    fun canToggleUnread(session: SessionSummary): Boolean =
        nonEmpty(session.sessionId) != null &&
            messageTime(session) != null &&
            !isActiveStreaming(session) &&
            session.hasPendingUserMessage != true

    /**
     * Port of `searchExcerpt(for:searchText:)`. `searchText` is the query of
     * the *screen* asking, not the view model's: screens with their own search
     * field (Scheduled sessions) share this view model, and they must not
     * inherit the sidebar's last excerpts.
     */
    fun searchExcerptFor(session: SessionSummary, searchText: String): SessionSearchExcerpt? {
        val query = normalizedSearchQuery(searchText)
        if (query.isEmpty() || activeRemoteSearchQuery != query) return null
        val sessionId = session.sessionId ?: return null
        val text = remoteContentSearchExcerpts[sessionId] ?: return null
        return SessionSearchExcerpt.of(text = text, query = query)
    }

    /** Port of `SessionListViewModel.sections`. */
    fun sections(
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): List<SessionListSection> = sessionListSections(sessions, zoneId, nowEpochSeconds)

    /** Port of `visibleSessions(searchText:selectedProjectID:automatedVisibility:)`. */
    fun visibleSessions(
        searchText: String,
        selectedProjectId: String?,
        automatedVisibility: io.github.jcartu.hermex.model.AutomatedSessionVisibility =
            io.github.jcartu.hermex.model.AutomatedSessionVisibility.showAll,
    ): List<SessionSummary> =
        visibleSessionsAmong(sessions, searchText, selectedProjectId, automatedVisibility)

    /**
     * Port of `visibleActiveSessions(searchText:selectedProjectID:automatedVisibility:)`:
     * the visible rows that are still streaming, for the list's active-row
     * monitor. Filters to streaming rows first (usually zero to two), so a body
     * pass does not filter and sort every session to find them.
     */
    fun visibleActiveSessions(
        searchText: String,
        selectedProjectId: String?,
        automatedVisibility: io.github.jcartu.hermex.model.AutomatedSessionVisibility =
            io.github.jcartu.hermex.model.AutomatedSessionVisibility.showAll,
    ): List<SessionSummary> {
        val activeSessions = sessions.filter(::isActiveStreaming)
        if (activeSessions.isEmpty()) return emptyList()
        return visibleSessionsAmong(activeSessions, searchText, selectedProjectId, automatedVisibility)
    }

    /**
     * Port of the private `visibleSessions(among:searchText:selectedProjectID:automatedVisibility:)`.
     * Visibility is decided per row, so running this over a subset of
     * `sessions` yields exactly the visible rows of that subset.
     */
    private fun visibleSessionsAmong(
        candidates: List<SessionSummary>,
        searchText: String,
        selectedProjectId: String?,
        automatedVisibility: io.github.jcartu.hermex.model.AutomatedSessionVisibility,
    ): List<SessionSummary> {
        val query = normalizedSearchQuery(searchText)
        // Every word must appear somewhere in the row, in any order and field (1.8.0).
        val searchTerms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val baseSessions = candidates.filter { automatedVisibility.shows(it) }
        val projectFilteredSessions = baseSessions.filter { session ->
            selectedProjectId == null || session.projectId == selectedProjectId
        }
        val localMatches = projectFilteredSessions.filter { session ->
            if (searchTerms.isEmpty()) {
                true
            } else {
                val text = searchableText(session)
                searchTerms.all { text.contains(it) }
            }
        }
        val sortedLocalMatches = sortedSessions(localMatches)

        if (query.isEmpty() || activeRemoteSearchQuery != query) {
            return sortedLocalMatches
        }

        val localMatchIds = sortedLocalMatches.mapNotNull { it.sessionId }.toHashSet()
        val sessionsById = HashMap<String, SessionSummary>()
        for (session in projectFilteredSessions) {
            val sessionId = session.sessionId?.takeIf { it.isNotEmpty() } ?: continue
            sessionsById.putIfAbsent(sessionId, session)
        }
        val remoteMatches = remoteContentSearchSessionIds.mapNotNull { sessionId ->
            if (sessionId in localMatchIds) null else sessionsById[sessionId]
        }

        return sortedLocalMatches + sortedSessions(remoteMatches)
    }

    /** Port of `scheduledSessionGroups(searchText:selectedProjectID:automatedVisibility:)`. */
    fun scheduledSessionGroups(
        searchText: String,
        selectedProjectId: String?,
        automatedVisibility: io.github.jcartu.hermex.model.AutomatedSessionVisibility =
            io.github.jcartu.hermex.model.AutomatedSessionVisibility.showAll,
    ): ScheduledSessionGroups {
        val candidates = visibleSessions(searchText, selectedProjectId, automatedVisibility)

        return ScheduledSessionGroups(
            ordinary = candidates.filter { !it.isCronSession },
            scheduled = candidates.filter { it.isCronSession && it.archived != true },
            totalScheduledCount = if (automatedVisibility.showsCron) {
                sessions.count { it.isCronSession && it.archived != true }
            } else {
                0
            },
        )
    }

    companion object {
        internal fun nonEmpty(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        internal fun normalizedSearchQuery(value: String): String =
            value.trim().lowercase()

        /** Port of `timestamp(for:)`: last message, else updated, else created, else 0. */
        internal fun timestamp(session: SessionSummary): Double =
            session.lastMessageAt ?: session.updatedAt ?: session.createdAt ?: 0.0

        /** Port of `messageTime(for:)`: `lastMessageAt` only, finite and positive. */
        internal fun messageTime(session: SessionSummary): Double? {
            val timestamp = session.lastMessageAt ?: return null
            return if (timestamp.isFinite() && timestamp > 0.0) timestamp else null
        }

        /** Port of `sortedSessions(_:)`: pinned first, then newest first. */
        internal fun sortedSessions(sessions: List<SessionSummary>): List<SessionSummary> =
            sessions.sortedWith(
                compareByDescending<SessionSummary> { it.pinned == true }
                    .thenByDescending { timestamp(it) },
            )

        /** Port of `searchableText(for:)`. */
        internal fun searchableText(session: SessionSummary): String =
            listOfNotNull(
                session.title,
                session.workspace,
                session.model,
                session.modelProvider,
                session.profile,
                session.sourceLabel,
            ).joinToString(separator = " ") { it.lowercase() }

        /** Port of `SessionListViewModel.activeStreamIDs(in:)`. */
        fun activeStreamIds(sessions: List<SessionSummary>): List<String> =
            sessions.mapNotNull { nonEmpty(it.activeStreamId) }.distinct().sorted()
    }
}

/** Port of the Swift `sections` computed property; pure and unit-testable. */
fun sessionListSections(
    sessions: List<SessionSummary>,
    zoneId: ZoneId,
    nowEpochSeconds: Long,
): List<SessionListSection> {
    val sorted = sessions.sortedByDescending { SessionListUiState.timestamp(it) }
    val (pinned, unpinned) = sorted.partition { it.pinned == true }

    val today = Instant.ofEpochSecond(nowEpochSeconds).atZone(zoneId).toLocalDate()
    val yesterday = today.minusDays(1)

    fun localDate(session: SessionSummary): LocalDate? {
        val timestamp = SessionListUiState.timestamp(session)
        if (timestamp <= 0.0) return null
        return Instant.ofEpochSecond(timestamp.toLong()).atZone(zoneId).toLocalDate()
    }

    val todaySessions = unpinned.filter { localDate(it) == today }
    val yesterdaySessions = unpinned.filter { localDate(it) == yesterday }
    val earlierSessions = unpinned.filter {
        val date = localDate(it) ?: return@filter true
        date != today && date != yesterday
    }

    return listOf(
        SessionListSection(SessionListSection.Kind.PINNED, "Pinned", pinned),
        SessionListSection(SessionListSection.Kind.TODAY, "Today", todaySessions),
        SessionListSection(SessionListSection.Kind.YESTERDAY, "Yesterday", yesterdaySessions),
        SessionListSection(SessionListSection.Kind.EARLIER, "Earlier", earlierSessions),
    ).filter { it.sessions.isNotEmpty() }
}

/**
 * Port of the Swift `ActiveSessionStateRefreshResult` — the outcome of one
 * active-row monitor tick.
 */
enum class ActiveSessionStateRefreshResult { UNCHANGED, RELOADED, FAILED }

/**
 * Port of `SessionListViewModel.swift` (minus the iOS-only profile switcher,
 * App Intents, and share-sheet plumbing). Suspend functions mirror the Swift
 * async methods one-to-one and return the same success values; `*Async`
 * wrappers launch them in [viewModelScope] for fire-and-forget UI events.
 */
class SessionListViewModel(
    private val api: HermesApi,
    private val cacheStore: CacheStore?,
    private val serverUrlProvider: suspend () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val unreadStore: SessionUnreadStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    private val sessionMutator = SessionMutator(api)
    private var searchJob: Job? = null

    /** The session the user is inside right now (`viewingSessionID`). */
    private var viewingSessionId: String? = null

    /** Sessions a chat opened from this list has just closed (`returnedFromSessionIDs`). */
    private val returnedFromSessionIds = LinkedHashSet<String>()

    /** Guards the one-shot unread-mark read from [unreadStore]. */
    private var seenMarksLoaded = false

    /** Bumped by each active-profile apply, so only the newest one applies. */
    private var activeProfileGeneration = 0

    /** The last list-load failure, for the monitor's `loadFailureRefreshResult`. */
    private var lastLoadError: Exception? = null

    // MARK: - Loading

    /** Port of `load(modelContext:animation:)`. */
    suspend fun load(): Boolean {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                cacheErrorMessage = null,
                loadFailedOffline = false,
            )
        }
        ensureSeenMarksLoaded()

        try {
            val response = api.sessions()
            val allSessions = response.sessions ?: emptyList()
            val visibleSessions = allSessions
                .filter { it.archived != true && it.shouldAppearInSessionList }
            reconcileUnread(visibleSessions, allSessions = allSessions)
            _uiState.update {
                it.copy(
                    sessions = visibleSessions,
                    archivedCount = response.archivedCount,
                    isViewingCachedData = false,
                    isLoading = false,
                )
            }
            pruneAttentionStates()
            lastLoadError = null
            cacheSessionsQuietly(visibleSessions)
            return true
        } catch (cancellation: CancellationException) {
            _uiState.update { it.copy(isLoading = false) }
            throw cancellation
        } catch (error: Exception) {
            lastLoadError = error
            val useCache = CacheFallbackPolicy.shouldUseCache(error)
            _uiState.update { it.copy(loadFailedOffline = useCache) }
            if (useCache && cacheStore != null) {
                try {
                    val serverUrl = serverUrlProvider()
                    val cachedSessions = withContext(ioDispatcher) {
                        cacheStore.cachedSessions(serverUrl)
                    }.filter { it.shouldAppearInSessionList }
                    if (cachedSessions.isNotEmpty()) {
                        _uiState.update {
                            it.copy(
                                sessions = cachedSessions,
                                isViewingCachedData = true,
                                errorMessage = null,
                                isLoading = false,
                            )
                        }
                        // Cached rows carry no live server state, so nothing can
                        // still be waiting on the user here.
                        clearAttentionStates()
                    } else {
                        _uiState.update {
                            it.copy(
                                isViewingCachedData = false,
                                errorMessage = error.userMessage,
                                isLoading = false,
                            )
                        }
                    }
                } catch (cacheError: Exception) {
                    if (cacheError is CancellationException) throw cacheError
                    _uiState.update {
                        it.copy(
                            cacheErrorMessage = cacheError.userMessage,
                            isViewingCachedData = false,
                            errorMessage = error.userMessage,
                            isLoading = false,
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isViewingCachedData = false,
                        errorMessage = error.userMessage,
                        isLoading = false,
                    )
                }
            }
            return false
        }
    }

    /** Port of `loadProjects()`. */
    suspend fun loadProjects() {
        _uiState.update { it.copy(isLoadingProjects = true, actionErrorMessage = null) }
        try {
            val response = api.projects()
            _uiState.update {
                it.copy(projects = response.projects ?: emptyList(), isLoadingProjects = false)
            }
        } catch (cancellation: CancellationException) {
            _uiState.update { it.copy(isLoadingProjects = false) }
            throw cancellation
        } catch (error: Exception) {
            _uiState.update {
                it.copy(actionErrorMessage = error.userMessage, isLoadingProjects = false)
            }
        }
    }

    /**
     * `loadSessions()` in `SessionListView.swift`: list first (restore needs
     * only the session rows, so it runs as soon as they arrive), then projects
     * and the active profile side by side when online
     * (`SessionListInitialLoad.loadProjectsAndActiveProfile`).
     */
    suspend fun refresh() {
        load()
        if (!_uiState.value.isViewingCachedData) {
            coroutineScope {
                launch { loadProjects() }
                launch { loadActiveProfile() }
            }
        }
    }

    fun refreshAsync() {
        viewModelScope.launch { refresh() }
    }

    // MARK: - Unread replies (1.7.0)

    /**
     * Port of `beginViewing(_:)`. Every chat entry point selects a
     * destination, so this one stamp covers rows and deep links alike.
     */
    suspend fun beginViewing(session: SessionSummary) {
        viewingSessionId = SessionListUiState.nonEmpty(session.sessionId)
        markSeen(session)
    }

    /**
     * Port of `noteReturn(from:)`: the next list load after a chat closes
     * stamps its freshest server timestamp if it succeeds, including a reply
     * completed during that visit.
     */
    fun noteReturn(session: SessionSummary) {
        val sessionId = SessionListUiState.nonEmpty(session.sessionId) ?: return
        if (viewingSessionId == sessionId) viewingSessionId = null
        returnedFromSessionIds.add(sessionId)
    }

    /** Port of `toggleUnread(_:)`. */
    suspend fun toggleUnread(session: SessionSummary) {
        if (!_uiState.value.canToggleUnread(session)) return
        val sessionId = SessionListUiState.nonEmpty(session.sessionId) ?: return
        val timestamp = SessionListUiState.messageTime(session) ?: return
        val unread = _uiState.value.isUnread(session)
        // Unread marks sit strictly below the server timestamp; marking read
        // stamps the timestamp itself (`nextDown` on the unread arm).
        val mark = if (unread) timestamp else Math.nextDown(timestamp)
        updateSeenMessageTimes { it + (sessionId to mark) }
        persistSeen()
    }

    /** Port of the private `markSeen(_:)`. */
    private suspend fun markSeen(session: SessionSummary) {
        val sessionId = SessionListUiState.nonEmpty(session.sessionId) ?: return
        val timestamp = SessionListUiState.messageTime(session) ?: return
        val seen = _uiState.value.seenMessageTimes[sessionId] ?: 0.0
        if (seen >= timestamp) return
        updateSeenMessageTimes { it + (sessionId to timestamp) }
        persistSeen()
    }

    /**
     * Port of `reconcileUnread(_:allSessions:returnedFromIDs:)`: drops marks
     * for sessions the server no longer lists, seeds marks for rows never seen
     * on this device (so an install does not light up every existing chat),
     * and stamps rows the user is inside or just returned from.
     */
    private suspend fun reconcileUnread(
        visibleSessions: List<SessionSummary>,
        allSessions: List<SessionSummary>,
    ) {
        val returnedFromIds = returnedFromSessionIds.toSet()
        returnedFromSessionIds.clear()
        val presentIds = allSessions.mapNotNullTo(HashSet()) {
            SessionListUiState.nonEmpty(it.sessionId)
        }
        val current = _uiState.value.seenMessageTimes
        val updated = current.filterKeys { it in presentIds }.toMutableMap()
        for (session in visibleSessions) {
            val sessionId = SessionListUiState.nonEmpty(session.sessionId) ?: continue
            val timestamp = SessionListUiState.messageTime(session) ?: continue
            if (updated[sessionId] == null ||
                sessionId in returnedFromIds ||
                viewingSessionId == sessionId
            ) {
                updated[sessionId] = maxOf(updated[sessionId] ?: timestamp, timestamp)
            }
        }
        if (updated == current) return
        _uiState.update { it.copy(seenMessageTimes = updated) }
        persistSeen()
    }

    private fun updateSeenMessageTimes(transform: (Map<String, Double>) -> Map<String, Double>) {
        _uiState.update { it.copy(seenMessageTimes = transform(it.seenMessageTimes)) }
    }

    /** One-shot read of the per-server seen marks (`SessionUnreadStore.load`). */
    private suspend fun ensureSeenMarksLoaded() {
        if (seenMarksLoaded) return
        seenMarksLoaded = true
        val store = unreadStore ?: return
        val serverUrl = serverUrlProvider()
        val seen = withContext(ioDispatcher) { store.load(serverUrl) }
        if (seen.isEmpty()) return
        _uiState.update { state ->
            // A mark written before the first load landed (the user opened a
            // chat from a restored list) wins over the stored value.
            state.copy(seenMessageTimes = seen + state.seenMessageTimes)
        }
    }

    private suspend fun persistSeen() {
        val store = unreadStore ?: return
        val serverUrl = serverUrlProvider()
        val seen = _uiState.value.seenMessageTimes
        withContext(ioDispatcher) { store.save(seen, serverUrl) }
    }

    // MARK: - Active-row monitor (1.6.0 row states, 1.8.0 no-pulse fix)

    /**
     * Port of `refreshActiveSessionStatesIfNeeded(streamIDs:modelContext:)`.
     * One monitor tick: checks the active rows' streams and reloads when one
     * ended, then refreshes attention probes.
     */
    suspend fun refreshActiveSessionStatesIfNeeded(
        rawStreamIds: List<String>,
    ): ActiveSessionStateRefreshResult {
        if (_uiState.value.isViewingCachedData || _uiState.value.isLoading) {
            return ActiveSessionStateRefreshResult.UNCHANGED
        }

        val streamIds = rawStreamIds
            .mapNotNull { SessionListUiState.nonEmpty(it) }
            .distinct()
            .sorted()
        if (streamIds.isEmpty()) {
            return if (load()) {
                ActiveSessionStateRefreshResult.RELOADED
            } else {
                loadFailureRefreshResult
            }
        }

        for (streamId in streamIds) {
            try {
                val response = api.chatStreamStatus(streamId)
                if (response.active == false) {
                    return if (load()) {
                        ActiveSessionStateRefreshResult.RELOADED
                    } else {
                        loadFailureRefreshResult
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (error is ApiError.Unauthorized) return ActiveSessionStateRefreshResult.FAILED
                continue
            }
        }

        return refreshAttentionStates()
    }

    private val loadFailureRefreshResult: ActiveSessionStateRefreshResult
        get() = if (lastLoadError == null) {
            ActiveSessionStateRefreshResult.UNCHANGED
        } else {
            ActiveSessionStateRefreshResult.FAILED
        }

    /**
     * Port of `refreshAttentionStates()`: one approval probe and one
     * clarification probe per streaming row, on the tick the caller already
     * runs. Sessions without an active stream are never probed, and there is
     * no separate polling loop or timer. A row's two probes go out together,
     * so N streaming rows cost about N round trips per tick instead of 2N.
     */
    private suspend fun refreshAttentionStates(): ActiveSessionStateRefreshResult {
        val streamingSessions = _uiState.value.sessions.filter(::isActiveStreaming)
        if (streamingSessions.isEmpty()) {
            clearAttentionStates()
            return ActiveSessionStateRefreshResult.UNCHANGED
        }

        val refreshed = HashMap<String, SessionRowAttentionState>()
        for (session in streamingSessions) {
            val sessionId = SessionListUiState.nonEmpty(session.sessionId) ?: continue
            val previous = _uiState.value.attentionStatesBySessionId[sessionId]

            // A failed probe is not evidence that nothing is pending, so it
            // keeps what the last successful tick knew rather than letting the
            // row fall back to "Working". A previous `.approval` masks any
            // clarification, so it carries no clarify knowledge, and a clarify
            // probe that fails behind it resolves to nothing pending.
            var probeError: Exception? = null
            val hasPendingApproval = try {
                api.approvalPending(sessionId).pending?.let { !it.isEmpty } == true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                probeError = error
                previous == SessionRowAttentionState.APPROVAL
            }
            val hasPendingClarification = try {
                api.clarifyPending(sessionId).pending?.let { !it.isEmpty } == true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (probeError == null) probeError = error
                previous == SessionRowAttentionState.INPUT
            }

            val failure = probeError
            if (failure is ApiError.Unauthorized) return ActiveSessionStateRefreshResult.FAILED

            SessionRowAttentionState.resolve(
                session = session,
                hasPendingApproval = hasPendingApproval,
                hasPendingClarification = hasPendingClarification,
            )?.let { refreshed[sessionId] = it }
        }

        if (refreshed != _uiState.value.attentionStatesBySessionId) {
            _uiState.update { it.copy(attentionStatesBySessionId = refreshed) }
        }
        return ActiveSessionStateRefreshResult.UNCHANGED
    }

    private fun clearAttentionStates() {
        if (_uiState.value.attentionStatesBySessionId.isEmpty()) return
        _uiState.update { it.copy(attentionStatesBySessionId = emptyMap()) }
    }

    /**
     * Port of `pruneAttentionStates()`: attention state only means something
     * for a row the server still reports as streaming, so a reload that ends a
     * stream drops that row's entry.
     */
    private fun pruneAttentionStates() {
        val current = _uiState.value.attentionStatesBySessionId
        if (current.isEmpty()) return
        val streamingSessionIds = _uiState.value.sessions.mapNotNullTo(HashSet()) { session ->
            if (!isActiveStreaming(session)) return@mapNotNullTo null
            SessionListUiState.nonEmpty(session.sessionId)
        }
        val pruned = current.filterKeys { it in streamingSessionIds }
        if (pruned != current) {
            _uiState.update { it.copy(attentionStatesBySessionId = pruned) }
        }
    }

    // MARK: - Active profile

    /** Port of `loadActiveProfile()`. */
    suspend fun loadActiveProfile() {
        if (_uiState.value.isLoadingActiveProfile) return

        _uiState.update { it.copy(isLoadingActiveProfile = true, activeProfileErrorMessage = null) }
        val generation = ++activeProfileGeneration
        try {
            val response = api.profiles()
            if (generation != activeProfileGeneration) return
            applyActiveProfile(response)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (generation != activeProfileGeneration) return
            _uiState.update { it.copy(activeProfileErrorMessage = error.userMessage) }
        } finally {
            _uiState.update { it.copy(isLoadingActiveProfile = false) }
        }
    }

    /** Port of `switchActiveProfile(_:)`. */
    suspend fun switchActiveProfile(profile: ProfileSummary): Boolean {
        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(activeProfileErrorMessage = "Reconnect to the server to change profiles.")
            }
            return false
        }

        val profileName = SessionListUiState.nonEmpty(profile.name)
        if (profileName == null) {
            _uiState.update {
                it.copy(activeProfileErrorMessage = "The server did not provide a profile name.")
            }
            return false
        }

        if (profileName == _uiState.value.activeProfileName) return true

        _uiState.update {
            it.copy(
                isSwitchingActiveProfile = true,
                switchingActiveProfileName = profileName,
                activeProfileErrorMessage = null,
            )
        }
        try {
            val response = api.switchProfile(profileName)
            val serverError = SessionListUiState.nonEmpty(response.error)
            if (serverError != null) {
                _uiState.update { it.copy(activeProfileErrorMessage = serverError) }
                return false
            }

            val resolvedName = SessionListUiState.nonEmpty(response.active) ?: profileName
            // The switch response has no `single_profile_mode` field; carry the
            // last known value forward so the switcher visibility doesn't flap.
            val profileResponse = ProfilesResponse(
                profiles = response.profiles ?: _uiState.value.profileOptions,
                active = resolvedName,
                singleProfileMode = _uiState.value.isSingleProfileMode,
            )
            applyActiveProfile(
                profileResponse,
                fallbackProfile = profile,
                fallbackDefaultModel = response.defaultModel,
            )
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(activeProfileErrorMessage = error.userMessage) }
            return false
        } finally {
            _uiState.update {
                it.copy(isSwitchingActiveProfile = false, switchingActiveProfileName = null)
            }
        }
    }

    /** Port of `applyActiveProfile(_:fallbackProfile:fallbackDefaultModel:)`. */
    private fun applyActiveProfile(
        response: ProfilesResponse,
        fallbackProfile: ProfileSummary? = null,
        fallbackDefaultModel: String? = null,
    ) {
        activeProfileGeneration += 1

        // Tolerant: only a present field moves the flag, so an older server
        // (or the carried-forward switch-response value) keeps today's behavior.
        response.singleProfileMode?.let { mode ->
            _uiState.update { it.copy(isSingleProfileMode = mode) }
        }
        response.profiles?.let { options ->
            _uiState.update { it.copy(profileOptions = options) }
        }

        val profileName = response.effectiveDefaultProfileName
        val profile = response.profile(matching = profileName) ?: fallbackProfile
        _uiState.update {
            it.copy(
                activeProfileName = profileName,
                activeProfileDisplayName = response.displayName(profileName) ?: profile?.displayName,
                activeProfileModel = SessionListUiState.nonEmpty(profile?.model)
                    ?: SessionListUiState.nonEmpty(fallbackDefaultModel),
                activeProfileProvider = SessionListUiState.nonEmpty(profile?.provider),
            )
        }
    }

    // MARK: - Remote content search

    /** Port of `searchSessions(query:content:depth:debounceNanoseconds:)`. */
    suspend fun searchSessions(
        rawQuery: String,
        content: Boolean = true,
        depth: Int = 5,
        debounceMillis: Long = 350,
    ) {
        val query = SessionListUiState.normalizedSearchQuery(rawQuery)
        _uiState.update {
            it.copy(
                activeRemoteSearchQuery = query,
                remoteContentSearchSessionIds = emptyList(),
                remoteContentSearchExcerpts = emptyMap(),
                searchErrorMessage = null,
            )
        }

        if (query.isEmpty() || _uiState.value.isViewingCachedData) {
            _uiState.update { it.copy(isSearchingRemoteSessions = false) }
            return
        }

        try {
            if (debounceMillis > 0) delay(debounceMillis)
            if (_uiState.value.activeRemoteSearchQuery != query) return

            _uiState.update { it.copy(isSearchingRemoteSessions = true) }
            val response = api.searchSessions(query = query, content = content, depth = depth)
            if (_uiState.value.activeRemoteSearchQuery != query) return

            val matches = contentMatches(response.sessions ?: emptyList())
            _uiState.update {
                it.copy(
                    remoteContentSearchSessionIds = matches.sessionIds,
                    remoteContentSearchExcerpts = matches.excerpts,
                    isSearchingRemoteSessions = false,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (_uiState.value.activeRemoteSearchQuery != query) return
            _uiState.update {
                it.copy(
                    isSearchingRemoteSessions = false,
                    remoteContentSearchSessionIds = emptyList(),
                    remoteContentSearchExcerpts = emptyMap(),
                    searchErrorMessage = error.userMessage,
                )
            }
        }
    }

    /** Debounced fire-and-forget wrapper; supersedes any in-flight search like `.task(id:)`. */
    fun searchSessionsAsync(rawQuery: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { searchSessions(rawQuery) }
    }

    /** Port of `clearSearchResults()`. */
    fun clearSearchResults() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                activeRemoteSearchQuery = null,
                remoteContentSearchSessionIds = emptyList(),
                remoteContentSearchExcerpts = emptyMap(),
                searchErrorMessage = null,
                isSearchingRemoteSessions = false,
            )
        }
    }

    // MARK: - Session mutations

    /** Port of `setPinned(_:for:modelContext:animation:)`. */
    suspend fun setPinned(pinned: Boolean, session: SessionSummary): Boolean {
        val sessionId = requireSessionId(session) ?: return false
        if (!beginSessionMutation(sessionId)) return false
        try {
            return mutate { sessionMutator.setPinned(pinned, sessionId) }
        } finally {
            endSessionMutation(sessionId)
        }
    }

    /** Port of `archive(_:modelContext:animation:)`. */
    suspend fun archive(session: SessionSummary): Boolean {
        val sessionId = requireSessionId(session) ?: return false
        if (!beginSessionMutation(sessionId)) return false
        try {
            return mutate { sessionMutator.archive(sessionId) }
        } finally {
            endSessionMutation(sessionId)
        }
    }

    /**
     * Port of `unarchive(_:modelContext:animation:)` (1.8.0 #865): undoes an
     * archive from the list's confirmation toast — restores the session, then
     * reloads so its row returns to its old place (the server keeps
     * `updated_at`). A second call while one is in flight for the same session
     * sends nothing.
     */
    suspend fun unarchive(session: SessionSummary): Boolean {
        val sessionId = requireSessionId(session) ?: return false
        if (!beginSessionMutation(sessionId)) return false
        try {
            return mutate { sessionMutator.unarchive(sessionId) }
        } finally {
            endSessionMutation(sessionId)
        }
    }

    /** Port of `delete(_:modelContext:animation:)`. */
    suspend fun delete(session: SessionSummary): Boolean {
        val sessionId = requireSessionId(session) ?: return false
        if (!beginSessionMutation(sessionId)) return false
        try {
            return mutate { sessionMutator.delete(sessionId) }
        } finally {
            endSessionMutation(sessionId)
        }
    }

    /** Port of `rename(_:to:modelContext:)`. */
    suspend fun rename(session: SessionSummary, rawTitle: String): Boolean {
        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(actionErrorMessage = "Reconnect to the server to rename a session.")
            }
            return false
        }

        val sessionId = requireSessionId(session) ?: return false
        val title = SessionListUiState.nonEmpty(rawTitle)
        if (title == null) {
            _uiState.update { it.copy(actionErrorMessage = "Enter a session title.") }
            return false
        }

        _uiState.update { it.copy(isRenamingSession = true, actionErrorMessage = null) }
        try {
            val response = sessionMutator.rename(sessionId, title)
            val serverError = SessionListUiState.nonEmpty(response.error)
            if (serverError != null) {
                _uiState.update {
                    it.copy(actionErrorMessage = serverError, isRenamingSession = false)
                }
                return false
            }

            val resolvedTitle = SessionListUiState.nonEmpty(response.session?.title) ?: title
            var updatedSession: SessionSummary? = null
            _uiState.update { state ->
                val baseSession = state.sessions.firstOrNull { it.sessionId == sessionId } ?: session
                val renamed = baseSession.replacingTitle(resolvedTitle)
                updatedSession = renamed
                val sessions = state.sessions.map { existing ->
                    if (existing.sessionId == sessionId) renamed else existing
                }
                state.copy(sessions = sessions, isRenamingSession = false)
            }
            updatedSession?.let { cacheSessionQuietly(it) }
            return true
        } catch (cancellation: CancellationException) {
            _uiState.update { it.copy(isRenamingSession = false) }
            throw cancellation
        } catch (error: Exception) {
            _uiState.update {
                it.copy(actionErrorMessage = error.userMessage, isRenamingSession = false)
            }
            return false
        }
    }

    /** Port of `duplicate(_:modelContext:)` — the "branch" list action. */
    suspend fun duplicate(session: SessionSummary, title: String? = null): SessionSummary? {
        val sessionId = requireSessionId(session) ?: return null
        if (!beginSessionMutation(sessionId)) return null
        _uiState.update { it.copy(actionErrorMessage = null) }
        try {
            val result = sessionMutator.duplicate(
                sessionId = sessionId,
                title = SessionListUiState.nonEmpty(title) ?: duplicateTitle(session),
            )

            val duplicatedSession = result.session
            if (duplicatedSession == null) {
                _uiState.update { it.copy(actionErrorMessage = result.errorMessage) }
                return null
            }

            load()
            var didInsert = false
            _uiState.update { state ->
                if (state.sessions.any { it.sessionId == duplicatedSession.sessionId }) {
                    state
                } else {
                    didInsert = true
                    state.copy(sessions = listOf(duplicatedSession) + state.sessions)
                }
            }
            if (didInsert) cacheSessionsQuietly(_uiState.value.sessions)
            return duplicatedSession
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return null
        } finally {
            endSessionMutation(sessionId)
        }
    }

    /** Port of `move(_:to:modelContext:)`. */
    suspend fun move(session: SessionSummary, projectId: String?): Boolean {
        val sessionId = requireSessionId(session) ?: return false
        if (!beginSessionMutation(sessionId)) return false
        _uiState.update { it.copy(isMovingSession = true) }
        try {
            return mutate { sessionMutator.move(sessionId, projectId) }
        } finally {
            _uiState.update { it.copy(isMovingSession = false) }
            endSessionMutation(sessionId)
        }
    }

    /** Port of `export(_:format:)`, minus the iOS temp-file/share-sheet plumbing. */
    suspend fun export(session: SessionSummary, format: SessionExportFormat): SessionExportFile? {
        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(actionErrorMessage = "Reconnect to the server to export a session.")
            }
            return null
        }

        val sessionId = requireSessionId(session) ?: return null
        // Reuses the per-session mutation gate: it disables the row's other
        // actions while the download runs and blocks a double-tap from firing
        // two exports.
        if (!beginSessionMutation(sessionId)) return null
        _uiState.update { it.copy(actionErrorMessage = null) }
        try {
            return api.exportSession(id = sessionId, format = format, fallbackTitle = session.title)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return null
        } finally {
            endSessionMutation(sessionId)
        }
    }

    // MARK: - Projects

    /** Port of `createProject(named:color:moving:modelContext:)`. */
    suspend fun createProject(rawName: String, color: String, moving: SessionSummary): Boolean {
        _uiState.update { it.copy(actionErrorMessage = null) }

        val sessionId = moving.sessionId
        if (sessionId == null) {
            _uiState.update {
                it.copy(actionErrorMessage = "The server did not provide a session ID.")
            }
            return false
        }

        val name = rawName.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(actionErrorMessage = "Enter a project name.") }
            return false
        }

        _uiState.update { it.copy(isCreatingProject = true, isMovingSession = true) }
        try {
            val createResponse = api.createProject(name = name, color = color)
            val project = createResponse.project
            if (project == null) {
                _uiState.update {
                    it.copy(
                        actionErrorMessage = createResponse.error
                            ?: "The server did not return the new project.",
                    )
                }
                return false
            }

            val projectId = project.projectId
            if (projectId.isNullOrEmpty()) {
                _uiState.update {
                    it.copy(
                        actionErrorMessage = createResponse.error
                            ?: "The server did not return the new project ID.",
                    )
                }
                return false
            }

            upsertProject(project)
            sessionMutator.move(sessionId, projectId)
            load()
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return false
        } finally {
            _uiState.update { it.copy(isCreatingProject = false, isMovingSession = false) }
        }
    }

    /** Port of `createEmptyProject(named:color:modelContext:)`. */
    suspend fun createEmptyProject(rawName: String, color: String): Boolean {
        _uiState.update { it.copy(actionErrorMessage = null) }

        val name = rawName.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(actionErrorMessage = "Enter a project name.") }
            return false
        }

        _uiState.update { it.copy(isCreatingProject = true) }
        try {
            val createResponse = api.createProject(name = name, color = color)
            val project = createResponse.project
            if (project == null) {
                _uiState.update {
                    it.copy(
                        actionErrorMessage = createResponse.error
                            ?: "The server did not return the new project.",
                    )
                }
                return false
            }

            if (project.projectId.isNullOrEmpty()) {
                _uiState.update {
                    it.copy(
                        actionErrorMessage = createResponse.error
                            ?: "The server did not return the new project ID.",
                    )
                }
                return false
            }

            upsertProject(project)
            load()
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return false
        } finally {
            _uiState.update { it.copy(isCreatingProject = false) }
        }
    }

    /** Port of `delete(_:modelContext:)` for projects. */
    suspend fun deleteProject(project: ProjectSummary): Boolean {
        val projectId = project.projectId
        if (projectId.isNullOrEmpty()) {
            _uiState.update {
                it.copy(actionErrorMessage = "The server did not provide a project ID.")
            }
            return false
        }

        _uiState.update { it.copy(isDeletingProject = true, actionErrorMessage = null) }
        try {
            api.deleteProject(id = projectId)
            _uiState.update { state ->
                state.copy(projects = state.projects.filter { it.projectId != projectId })
            }
            load()
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return false
        } finally {
            _uiState.update { it.copy(isDeletingProject = false) }
        }
    }

    /** Port of `rename(_:named:color:)` for projects. */
    suspend fun renameProject(project: ProjectSummary, rawName: String, color: String?): Boolean {
        _uiState.update { it.copy(actionErrorMessage = null) }

        val projectId = project.projectId
        if (projectId.isNullOrEmpty()) {
            _uiState.update {
                it.copy(actionErrorMessage = "The server did not provide a project ID.")
            }
            return false
        }

        val name = rawName.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(actionErrorMessage = "Enter a project name.") }
            return false
        }

        _uiState.update { it.copy(isRenamingProject = true) }
        try {
            val response = api.renameProject(id = projectId, name = name, color = color)
            val renamedProject = response.project
            if (renamedProject == null) {
                _uiState.update {
                    it.copy(
                        actionErrorMessage = response.error
                            ?: "The server did not return the renamed project.",
                    )
                }
                return false
            }

            if (renamedProject.projectId.isNullOrEmpty()) {
                _uiState.update {
                    it.copy(
                        actionErrorMessage = response.error
                            ?: "The server did not return the renamed project ID.",
                    )
                }
                return false
            }

            upsertProject(renamedProject)
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return false
        } finally {
            _uiState.update { it.copy(isRenamingProject = false) }
        }
    }

    /** Port of `clearActionError()`. */
    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    /** Clears the active-profile error line (paired with `clearActionError`). */
    fun clearActiveProfileError() {
        _uiState.update { it.copy(activeProfileErrorMessage = null) }
    }

    // MARK: - Private helpers

    /**
     * Port of the private `mutate(modelContext:animation:_:)`: run the
     * operation, then reload the list; the reload is what rolls the UI back
     * when the server rejected the mutation.
     */
    private suspend fun mutate(operation: suspend () -> Unit): Boolean {
        _uiState.update { it.copy(actionErrorMessage = null) }
        return try {
            operation()
            load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            false
        }
    }

    private fun requireSessionId(session: SessionSummary): String? {
        val sessionId = SessionListUiState.nonEmpty(session.sessionId)
        if (sessionId == null) {
            _uiState.update {
                it.copy(actionErrorMessage = "The server did not provide a session ID.")
            }
        }
        return sessionId
    }

    private fun beginSessionMutation(sessionId: String): Boolean {
        var inserted = false
        _uiState.update { state ->
            if (sessionId in state.mutatingSessionIds) {
                state
            } else {
                inserted = true
                state.copy(mutatingSessionIds = state.mutatingSessionIds + sessionId)
            }
        }
        return inserted
    }

    private fun endSessionMutation(sessionId: String) {
        _uiState.update { it.copy(mutatingSessionIds = it.mutatingSessionIds - sessionId) }
    }

    private fun upsertProject(project: ProjectSummary) {
        val projectId = project.projectId?.takeIf { it.isNotEmpty() } ?: return
        _uiState.update { state ->
            val projects = if (state.projects.any { it.projectId == projectId }) {
                state.projects.map { if (it.projectId == projectId) project else it }
            } else {
                state.projects + project
            }
            state.copy(projects = projects)
        }
    }

    /**
     * Port of `contentMatches(from:)`: content-match rows narrowed to sessions
     * the list can actually show, in server order, plus each row's excerpt when
     * the server sent one (`match_preview`).
     */
    private fun contentMatches(
        searchResults: List<SessionSummary>,
    ): ContentMatches {
        val locallyVisibleSessionIds = _uiState.value.sessions.mapNotNullTo(HashSet()) { session ->
            if (session.archived != true) session.sessionId?.takeIf { it.isNotEmpty() } else null
        }
        val seenSessionIds = HashSet<String>()
        val sessionIds = mutableListOf<String>()
        val excerpts = mutableMapOf<String, String>()

        for (session in searchResults) {
            if (session.matchType?.lowercase() != "content") continue
            val sessionId = session.sessionId ?: continue
            if (sessionId !in locallyVisibleSessionIds) continue
            if (!seenSessionIds.add(sessionId)) continue

            sessionIds.add(sessionId)
            val preview = session.matchPreview?.trim()
            if (!preview.isNullOrEmpty()) {
                excerpts[sessionId] = preview
            }
        }

        return ContentMatches(sessionIds = sessionIds, excerpts = excerpts)
    }

    /** Result of [contentMatches]: ordered ids plus per-id excerpt. */
    private data class ContentMatches(
        val sessionIds: List<String>,
        val excerpts: Map<String, String>,
    )

    private fun duplicateTitle(session: SessionSummary): String {
        val baseTitle = SessionListUiState.nonEmpty(session.title) ?: "Untitled Session"
        return "$baseTitle (copy)"
    }

    private suspend fun cacheSessionsQuietly(sessions: List<SessionSummary>) {
        val store = cacheStore ?: return
        try {
            val serverUrl = serverUrlProvider()
            withContext(ioDispatcher) { store.cacheSessions(sessions, serverUrl) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(cacheErrorMessage = error.userMessage) }
        }
    }

    private suspend fun cacheSessionQuietly(session: SessionSummary) {
        val store = cacheStore ?: return
        try {
            val serverUrl = serverUrlProvider()
            withContext(ioDispatcher) { store.cacheSession(session, serverUrl) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(cacheErrorMessage = error.userMessage) }
        }
    }
}

/** Mirrors `error.localizedDescription`: ApiError carries its own user-facing text. */
internal val Throwable.userMessage: String
    get() = message ?: toString()
