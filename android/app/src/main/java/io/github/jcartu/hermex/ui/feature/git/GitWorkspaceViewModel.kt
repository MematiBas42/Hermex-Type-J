package io.github.jcartu.hermex.ui.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.GitBranchRef
import io.github.jcartu.hermex.model.GitBranches
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.GitInfo
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GitWorkspaceUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val info: GitInfo? = null,
    val status: GitStatus? = null,
    val branches: GitBranches? = null,
    val selectedFilePath: String? = null,
    val selectedDiffKind: String = "unstaged",
    val diff: GitDiff? = null,
    val selectedPaths: Set<String> = emptySet(),
    val commitMessage: String = "",
    val isLoading: Boolean = false,
    val isLoadingDiff: Boolean = false,
    val isActing: Boolean = false,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
) {
    val selectedSession: SessionSummary? get() = sessions.firstOrNull { it.sessionId == selectedSessionId }
    val files: List<GitFile> get() = status?.files.orEmpty()
    val isGitRepository: Boolean get() = status?.isGit == true || info?.isGit == true
    val selectedFiles: List<GitFile> get() = files.filter { it.path in selectedPaths }
}

class GitWorkspaceViewModel(private val api: HermesApi) : ViewModel() {
    private val _uiState = MutableStateFlow(GitWorkspaceUiState())
    val uiState: StateFlow<GitWorkspaceUiState> = _uiState.asStateFlow()

    fun load() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val sessions = api.sessions().sessions.orEmpty()
                .filter { !it.workspace.isNullOrBlank() && !it.sessionId.isNullOrBlank() && it.archived != true }
                .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: 0.0 }
            val selected = _uiState.value.selectedSessionId.takeIf { id -> sessions.any { it.sessionId == id } }
                ?: sessions.firstOrNull()?.sessionId
            _uiState.update { it.copy(sessions = sessions, selectedSessionId = selected, isLoading = false) }
            selected?.let { refreshInternal(it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load workspaces.") }
        }
    }

    fun selectSession(sessionId: String) = viewModelScope.launch {
        _uiState.update {
            it.copy(
                selectedSessionId = sessionId, info = null, status = null, branches = null,
                selectedFilePath = null, diff = null, selectedPaths = emptySet(), errorMessage = null,
            )
        }
        refreshInternal(sessionId)
    }

    fun refresh() = viewModelScope.launch {
        _uiState.value.selectedSessionId?.let { refreshInternal(it) } ?: load()
    }

    private suspend fun refreshInternal(sessionId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val info = api.gitInfo(sessionId).git
            val status = api.gitStatus(sessionId).git
            val branches = runCatching { api.gitBranches(sessionId).branches }.getOrNull()
            val ids = status?.files.orEmpty().mapNotNull { it.path }.toSet()
            _uiState.update {
                it.copy(
                    info = info, status = status, branches = branches, isLoading = false,
                    selectedPaths = it.selectedPaths.intersect(ids),
                    selectedFilePath = it.selectedFilePath.takeIf(ids::contains),
                    diff = it.diff.takeIf { _ -> it.selectedFilePath in ids },
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load Git status.") }
        }
    }

    fun selectFile(file: GitFile, kind: String? = null) = viewModelScope.launch {
        val session = _uiState.value.selectedSessionId ?: return@launch
        val path = file.path ?: return@launch
        val resolvedKind = kind ?: if (file.staged == true && file.unstaged != true) "staged" else "unstaged"
        _uiState.update { it.copy(selectedFilePath = path, selectedDiffKind = resolvedKind, isLoadingDiff = true, diff = null) }
        try {
            val diff = api.gitDiff(session, path, resolvedKind).diff
            _uiState.update { it.copy(diff = diff, isLoadingDiff = false) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isLoadingDiff = false, errorMessage = error.message ?: "Could not load diff.") }
        }
    }

    fun closeDiff() = _uiState.update {
        it.copy(selectedFilePath = null, diff = null, isLoadingDiff = false)
    }

    fun togglePath(path: String) = _uiState.update { state ->
        state.copy(selectedPaths = state.selectedPaths.toMutableSet().apply { if (!add(path)) remove(path) })
    }

    fun clearSelection() = _uiState.update { it.copy(selectedPaths = emptySet()) }
    fun setCommitMessage(message: String) = _uiState.update { it.copy(commitMessage = message) }
    fun dismissMessage() = _uiState.update { it.copy(errorMessage = null, actionMessage = null) }

    fun fetch() = remote("Fetched") { api.gitFetch(it) }
    fun pull() = remote("Pulled latest changes") { api.gitPull(it) }
    fun push() = remote("Pushed changes") { api.gitPush(it) }

    private fun remote(success: String, operation: suspend (String) -> io.github.jcartu.hermex.model.GitRemoteActionResponse) = act(success) { session ->
        val response = operation(session)
        if (response.ok == false) error(response.message ?: "Git operation failed")
        response.status?.let(::applyStatus) ?: refreshInternal(session)
    }

    fun checkout(target: GitCheckoutTarget, stashFirst: Boolean) = act("Checked out ${target.displayName}") { session ->
        val response = if (stashFirst) api.gitStashCheckout(session, target) else api.gitCheckout(session, target)
        if (response.ok == false) error(response.message ?: "Checkout failed")
        response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
    }

    fun stageSelected() = selectedMutation("Staged selected files") { session, paths -> api.gitStage(session, paths) }
    fun unstageSelected() = selectedMutation("Unstaged selected files") { session, paths -> api.gitUnstage(session, paths) }

    fun discardSelected(deleteUntracked: Boolean) = selectedMutation("Discarded selected changes") { session, paths ->
        api.gitDiscard(session, paths, deleteUntracked)
    }

    private fun selectedMutation(
        success: String,
        operation: suspend (String, List<String>) -> io.github.jcartu.hermex.model.GitMutationResponse,
    ) = act(success) { session ->
        val paths = _uiState.value.selectedPaths.toList()
        require(paths.isNotEmpty()) { "Select at least one file" }
        val response = operation(session, paths)
        if (response.ok == false) error("Git operation was rejected")
        response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
        clearSelection()
    }

    fun generateCommitMessage() = act("Commit message generated") { session ->
        val selected = _uiState.value.selectedPaths.toList()
        val response = if (selected.isEmpty()) api.gitCommitMessage(session)
        else api.gitCommitMessageSelected(session, selected)
        val message = response.message?.trim().orEmpty()
        require(message.isNotEmpty()) { "Server returned an empty commit message" }
        _uiState.update { it.copy(commitMessage = message) }
    }

    fun commit() = act("Committed changes") { session ->
        val message = _uiState.value.commitMessage.trim()
        require(message.isNotEmpty()) { "Enter a commit message" }
        val selected = _uiState.value.selectedPaths.toList()
        val response = if (selected.isEmpty()) api.gitCommit(session, message)
        else api.gitCommitSelected(session, message, selected)
        if (response.ok == false || response.commit.isNullOrBlank()) error("Commit was not confirmed")
        response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
        _uiState.update { it.copy(commitMessage = "", selectedPaths = emptySet(), actionMessage = "Committed ${response.shortSHA ?: "changes"}") }
    }

    private fun act(success: String, block: suspend (String) -> Unit): Job = viewModelScope.launch {
        val session = _uiState.value.selectedSessionId ?: run {
            _uiState.update { it.copy(errorMessage = "Choose a workspace session first.") }; return@launch
        }
        _uiState.update { it.copy(isActing = true, errorMessage = null, actionMessage = null) }
        try {
            block(session)
            _uiState.update { it.copy(isActing = false, actionMessage = it.actionMessage ?: success) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isActing = false, errorMessage = error.message ?: "Git operation failed.") }
        }
    }

    private fun applyStatus(status: GitStatus) {
        val ids = status.files.orEmpty().mapNotNull { it.path }.toSet()
        _uiState.update {
            it.copy(
                status = status,
                selectedPaths = it.selectedPaths.intersect(ids),
                selectedFilePath = it.selectedFilePath.takeIf(ids::contains),
                diff = it.diff.takeIf { _ -> it.selectedFilePath in ids },
            )
        }
    }
}
