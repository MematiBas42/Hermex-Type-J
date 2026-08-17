package io.github.jcartu.hermex.ui.feature.sessions

import androidx.lifecycle.ViewModel
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI state for the archived-sessions screen (`ArchivedSessionsViewModel.swift`). */
data class ArchivedSessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val unarchivingSessionIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
) {
    val isUnarchiving: Boolean get() = unarchivingSessionIds.isNotEmpty()

    fun isUnarchiving(session: SessionSummary): Boolean {
        val sessionId = session.sessionId ?: return false
        return sessionId in unarchivingSessionIds
    }
}

/** Port of `Features/Settings/ArchivedSessionsViewModel.swift`. */
class ArchivedSessionsViewModel(private val api: HermesApi) : ViewModel() {

    private val _uiState = MutableStateFlow(ArchivedSessionsUiState())
    val uiState: StateFlow<ArchivedSessionsUiState> = _uiState.asStateFlow()

    /** Port of `load()`: `include_archived=1` is required (issue #17); filter client-side. */
    suspend fun load() {
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, actionErrorMessage = null)
        }
        try {
            val response = api.sessions(includeArchived = true)
            _uiState.update {
                it.copy(
                    sessions = (response.sessions ?: emptyList()).filter { s -> s.archived == true },
                    isLoading = false,
                )
            }
        } catch (cancellation: CancellationException) {
            // A cancelled load (pull-to-refresh superseding the launched task, or
            // the view disappearing) is not a failure — don't flash an error state.
            _uiState.update { it.copy(isLoading = false) }
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.userMessage, isLoading = false) }
        }
    }

    /**
     * Port of `unarchive(_:)`: optimistic removal with rollback. The row is
     * removed before the request; a server rejection or transport failure
     * restores it at its original index.
     */
    suspend fun unarchive(session: SessionSummary): Boolean {
        val sessionId = SessionListUiState.nonEmpty(session.sessionId)
        if (sessionId == null) {
            _uiState.update {
                it.copy(actionErrorMessage = "The server did not provide a session ID.")
            }
            return false
        }
        if (sessionId in _uiState.value.unarchivingSessionIds) return false

        val removed = removeSession(sessionId) ?: return false

        _uiState.update {
            it.copy(
                unarchivingSessionIds = it.unarchivingSessionIds + sessionId,
                actionErrorMessage = null,
            )
        }
        try {
            val response = api.archiveSession(id = sessionId, archived = false)
            // Rejections (subagent / read-only CLI sessions) arrive as HTTP 400 and
            // throw; a 200 body with an `error` field is surfaced too so the server's
            // own message is always shown (issue #17). An explicit `ok: false`
            // without an `error` string is still a failure — only a missing `ok` is
            // treated as success, per tolerant decoding.
            val serverError = SessionListUiState.nonEmpty(response.error)
            if (serverError != null) {
                restore(removed)
                _uiState.update { it.copy(actionErrorMessage = serverError) }
                return false
            }
            if (response.ok == false) {
                restore(removed)
                _uiState.update {
                    it.copy(actionErrorMessage = "The server could not unarchive this session.")
                }
                return false
            }
            return true
        } catch (cancellation: CancellationException) {
            restore(removed)
            throw cancellation
        } catch (error: Exception) {
            restore(removed)
            _uiState.update { it.copy(actionErrorMessage = error.userMessage) }
            return false
        } finally {
            _uiState.update { it.copy(unarchivingSessionIds = it.unarchivingSessionIds - sessionId) }
        }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    private fun removeSession(sessionId: String): Pair<Int, SessionSummary>? {
        var removed: Pair<Int, SessionSummary>? = null
        _uiState.update { state ->
            val index = state.sessions.indexOfFirst { it.sessionId == sessionId }
            if (index < 0) {
                state
            } else {
                removed = index to state.sessions[index]
                state.copy(sessions = state.sessions.filterIndexed { i, _ -> i != index })
            }
        }
        return removed
    }

    private fun restore(removed: Pair<Int, SessionSummary>) {
        val (index, session) = removed
        if (session.sessionId == null) return
        _uiState.update { state ->
            if (state.sessions.any { it.sessionId == session.sessionId }) {
                state
            } else {
                val sessions = state.sessions.toMutableList()
                sessions.add(minOf(index, sessions.size), session)
                state.copy(sessions = sessions)
            }
        }
    }
}
