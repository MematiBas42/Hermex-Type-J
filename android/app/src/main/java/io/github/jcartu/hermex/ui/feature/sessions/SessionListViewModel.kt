package io.github.jcartu.hermex.ui.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.model.ProjectSummary
import io.github.jcartu.hermex.model.SessionExportFile
import io.github.jcartu.hermex.model.SessionExportFormat
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.CacheFallbackPolicy
import io.github.jcartu.hermex.net.HermesApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) {
    fun isMutating(session: SessionSummary): Boolean {
        val sessionId = nonEmpty(session.sessionId) ?: return false
        return sessionId in mutatingSessionIds
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
    ): List<SessionSummary> {
        val query = normalizedSearchQuery(searchText)
        val baseSessions = sessions.filter { automatedVisibility.shows(it) }
        val projectFilteredSessions = baseSessions.filter { session ->
            selectedProjectId == null || session.projectId == selectedProjectId
        }
        val localMatches = projectFilteredSessions.filter { session ->
            query.isEmpty() || searchableText(session).contains(query)
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    private val sessionMutator = SessionMutator(api)
    private var searchJob: Job? = null

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

        try {
            val response = api.sessions()
            val visibleSessions = (response.sessions ?: emptyList())
                .filter { it.archived != true && it.shouldAppearInSessionList }
            _uiState.update {
                it.copy(
                    sessions = visibleSessions,
                    archivedCount = response.archivedCount,
                    isViewingCachedData = false,
                    isLoading = false,
                )
            }
            cacheSessionsQuietly(visibleSessions)
            return true
        } catch (cancellation: CancellationException) {
            _uiState.update { it.copy(isLoading = false) }
            throw cancellation
        } catch (error: Exception) {
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

    /** `loadSessions()` in `SessionListView.swift`: list + projects when online. */
    suspend fun refresh() {
        load()
        if (!_uiState.value.isViewingCachedData) {
            loadProjects()
        }
    }

    fun refreshAsync() {
        viewModelScope.launch { refresh() }
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

            _uiState.update {
                it.copy(
                    remoteContentSearchSessionIds = contentMatchIds(response.sessions ?: emptyList()),
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

    /** Port of `contentMatchIDs(from:)`. */
    private fun contentMatchIds(searchResults: List<SessionSummary>): List<String> {
        val locallyVisibleSessionIds = _uiState.value.sessions.mapNotNullTo(HashSet()) { session ->
            if (session.archived != true) session.sessionId?.takeIf { it.isNotEmpty() } else null
        }
        val seenSessionIds = HashSet<String>()

        return searchResults.mapNotNull { session ->
            val sessionId = session.sessionId ?: return@mapNotNull null
            if (session.matchType?.lowercase() != "content") return@mapNotNull null
            if (sessionId !in locallyVisibleSessionIds) return@mapNotNull null
            if (!seenSessionIds.add(sessionId)) return@mapNotNull null
            sessionId
        }
    }

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
