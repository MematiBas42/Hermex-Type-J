package io.github.jcartu.hermex.ui.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.GitBranches
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.GitInfo
import io.github.jcartu.hermex.model.GitRemoteActionResponse
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** State for status/actions plus the upstream all-files review surface. */
data class GitWorkspaceUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val info: GitInfo? = null,
    val status: GitStatus? = null,
    val branches: GitBranches? = null,
    val selectedFilePath: String? = null,
    val selectedDiffKind: String = "unstaged",
    val diff: GitDiff? = null,
    val reviewFiles: List<ReviewDiffFileModel> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val commitMessage: String = "",
    val isLoading: Boolean = false,
    val isLoadingDiff: Boolean = false,
    val isLoadingReview: Boolean = false,
    val isActing: Boolean = false,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
) {
    val selectedSession: SessionSummary? get() = sessions.firstOrNull { it.sessionId == selectedSessionId }
    val files: List<GitFile> get() = status?.trackedFiles.orEmpty()
    val isGitRepository: Boolean get() = status?.isGit == true || info?.isGit == true
    val selectedFiles: List<GitFile> get() = files.filter { it.displayPath in selectedPaths }
}

/**
 * Port of `GitWorkspaceViewModel.swift` and `GitDiffView.ReviewDiffLoader`.
 * Existing write actions remain serialized by [isActing]; every status refresh
 * starts an all-files diff load with at most four requests in flight.
 */
class GitWorkspaceViewModel(private val api: HermesApi) : ViewModel() {
    private val mutableUiState = MutableStateFlow(GitWorkspaceUiState())
    val uiState: StateFlow<GitWorkspaceUiState> = mutableUiState.asStateFlow()
    private var reviewJob: Job? = null
    private var reviewGeneration = 0L

    fun load() = viewModelScope.launch {
        mutableUiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val sessions = api.sessions().sessions.orEmpty()
                .filter { !it.workspace.isNullOrBlank() && !it.sessionId.isNullOrBlank() && it.archived != true }
                .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: 0.0 }
            val selected = mutableUiState.value.selectedSessionId.takeIf { id -> sessions.any { it.sessionId == id } }
                ?: sessions.firstOrNull()?.sessionId
            mutableUiState.update { it.copy(sessions = sessions, selectedSessionId = selected, isLoading = false) }
            selected?.let { refreshInternal(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load workspaces.") }
        }
    }

    fun selectSession(sessionId: String) = viewModelScope.launch {
        reviewJob?.cancel()
        mutableUiState.update {
            it.copy(
                selectedSessionId = sessionId,
                info = null,
                status = null,
                branches = null,
                selectedFilePath = null,
                diff = null,
                reviewFiles = emptyList(),
                selectedPaths = emptySet(),
                errorMessage = null,
            )
        }
        refreshInternal(sessionId)
    }

    fun refresh() = viewModelScope.launch {
        mutableUiState.value.selectedSessionId?.let { refreshInternal(it) } ?: load()
    }

    private suspend fun refreshInternal(sessionId: String) {
        mutableUiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val info = api.gitInfo(sessionId).git
            val status = api.gitStatus(sessionId).git
            val branches = runCatching { api.gitBranches(sessionId).branches }.getOrNull()
            val ids = status?.trackedFiles.orEmpty().map(GitFile::displayPath).toSet()
            mutableUiState.update { state ->
                state.copy(
                    info = info,
                    status = status,
                    branches = branches,
                    isLoading = false,
                    selectedPaths = state.selectedPaths.intersect(ids),
                    selectedFilePath = state.selectedFilePath.takeIf(ids::contains),
                    diff = state.diff.takeIf { state.selectedFilePath in ids },
                )
            }
            startReview(sessionId, status?.trackedFiles.orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load Git status.") }
        }
    }

    /** Scroll target + compatibility per-file diff; the review itself already contains every file. */
    fun selectFile(file: GitFile, kind: String? = null) = viewModelScope.launch {
        val session = mutableUiState.value.selectedSessionId ?: return@launch
        val path = file.displayPath.takeIf(String::isNotBlank) ?: return@launch
        val resolvedKind = kind ?: file.preferredDiffKind
        mutableUiState.update {
            it.copy(selectedFilePath = path, selectedDiffKind = resolvedKind, isLoadingDiff = true, diff = null)
        }
        try {
            val diff = api.gitDiff(session, path, resolvedKind).diff
            mutableUiState.update { it.copy(diff = diff, isLoadingDiff = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUiState.update { it.copy(isLoadingDiff = false, errorMessage = error.message ?: "Could not load diff.") }
        }
    }

    fun closeDiff() = mutableUiState.update { it.copy(selectedFilePath = null, diff = null, isLoadingDiff = false) }

    fun togglePath(path: String) = mutableUiState.update { state ->
        state.copy(selectedPaths = state.selectedPaths.toMutableSet().apply { if (!add(path)) remove(path) })
    }

    fun clearSelection() = mutableUiState.update { it.copy(selectedPaths = emptySet()) }
    fun setCommitMessage(message: String) = mutableUiState.update { it.copy(commitMessage = message) }
    fun dismissMessage() = mutableUiState.update { it.copy(errorMessage = null, actionMessage = null) }

    fun fetch() = remote("Fetched") { api.gitFetch(it) }
    fun pull() = remote("Pulled latest changes") { api.gitPull(it) }
    fun push() = remote("Pushed changes") { api.gitPush(it) }

    private fun remote(
        success: String,
        operation: suspend (String) -> GitRemoteActionResponse,
    ) = act(success) { session ->
        val response = operation(session)
        if (response.ok == false) error(response.message ?: "Git operation failed")
        response.status?.let(::applyStatus) ?: refreshInternal(session)
    }

    fun checkout(target: GitCheckoutTarget, stashFirst: Boolean) =
        act("Checked out ${target.displayName}") { session ->
            val response = if (stashFirst) api.gitStashCheckout(session, target) else api.gitCheckout(session, target)
            if (response.ok == false) error(response.message ?: "Checkout failed")
            response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
        }

    fun stageSelected() = selectedMutation("Staged selected files") { session, paths -> api.gitStage(session, paths) }
    fun unstageSelected() = selectedMutation("Unstaged selected files") { session, paths -> api.gitUnstage(session, paths) }

    /** Staged files are first removed from the index, matching `WebUIGitClient.discard`. */
    fun discardSelected(deleteUntracked: Boolean) = act("Discarded selected changes") { session ->
        val files = mutableUiState.value.selectedFiles
        require(files.isNotEmpty()) { "Select at least one file" }
        val staged = files.filter { it.staged == true }.map(GitFile::displayPath)
        if (staged.isNotEmpty()) {
            val unstaged = api.gitUnstage(session, staged)
            if (unstaged.ok == false) error("Git operation was rejected")
        }
        val paths = files.map(GitFile::displayPath)
        val response = api.gitDiscard(session, paths, deleteUntracked)
        if (response.ok == false) error("Git operation was rejected")
        response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
        clearSelection()
    }

    private fun selectedMutation(
        success: String,
        operation: suspend (String, List<String>) -> io.github.jcartu.hermex.model.GitMutationResponse,
    ) = act(success) { session ->
        val paths = mutableUiState.value.selectedPaths.toList()
        require(paths.isNotEmpty()) { "Select at least one file" }
        val response = operation(session, paths)
        if (response.ok == false) error("Git operation was rejected")
        response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
        clearSelection()
    }

    fun generateCommitMessage() = act("Commit message generated") { session ->
        val selected = mutableUiState.value.selectedPaths.toList()
        val response = if (selected.isEmpty()) api.gitCommitMessage(session)
        else api.gitCommitMessageSelected(session, selected)
        val message = response.message?.trim().orEmpty()
        require(message.isNotEmpty()) { "Server returned an empty commit message" }
        mutableUiState.update { it.copy(commitMessage = message) }
    }

    fun commit() = act("Committed changes") { session ->
        val message = mutableUiState.value.commitMessage.trim()
        require(message.isNotEmpty()) { "Enter a commit message" }
        val selected = mutableUiState.value.selectedPaths.toList()
        val response = if (selected.isEmpty()) api.gitCommit(session, message)
        else api.gitCommitSelected(session, message, selected)
        if (response.ok == false || response.commit.isNullOrBlank()) error("Commit was not confirmed")
        response.resolvedStatus?.let(::applyStatus) ?: refreshInternal(session)
        mutableUiState.update {
            it.copy(
                commitMessage = "",
                selectedPaths = emptySet(),
                actionMessage = "Committed ${response.shortSHA ?: "changes"}",
            )
        }
    }

    private fun act(success: String, block: suspend (String) -> Unit): Job = viewModelScope.launch {
        val session = mutableUiState.value.selectedSessionId ?: run {
            mutableUiState.update { it.copy(errorMessage = "Choose a workspace session first.") }
            return@launch
        }
        if (mutableUiState.value.isActing) return@launch
        mutableUiState.update { it.copy(isActing = true, errorMessage = null, actionMessage = null) }
        try {
            block(session)
            mutableUiState.update { it.copy(isActing = false, actionMessage = it.actionMessage ?: success) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUiState.update { it.copy(isActing = false, errorMessage = error.message ?: "Git operation failed.") }
        }
    }

    private fun applyStatus(status: GitStatus) {
        val ids = status.trackedFiles.map(GitFile::displayPath).toSet()
        mutableUiState.update { state ->
            state.copy(
                status = status,
                selectedPaths = state.selectedPaths.intersect(ids),
                selectedFilePath = state.selectedFilePath.takeIf(ids::contains),
                diff = state.diff.takeIf { state.selectedFilePath in ids },
            )
        }
        mutableUiState.value.selectedSessionId?.let { startReview(it, status.trackedFiles) }
    }

    private fun startReview(sessionId: String, files: List<GitFile>) {
        reviewJob?.cancel()
        val generation = ++reviewGeneration
        var models = files.associate { file ->
            file.id to ReviewDiffMapper.map(file, ReviewDiffState.Loading)
        }
        mutableUiState.update {
            it.copy(reviewFiles = files.mapNotNull { file -> models[file.id] }, isLoadingReview = files.isNotEmpty())
        }
        if (files.isEmpty()) return

        reviewJob = viewModelScope.launch {
            val semaphore = Semaphore(MAX_CONCURRENT_DIFFS)
            coroutineScope {
                files.map { file ->
                    async {
                        val state = semaphore.withPermit {
                            try {
                                val diff = api.gitDiff(sessionId, file.displayPath, file.preferredDiffKind).diff
                                if (diff == null) ReviewDiffState.Failed("Could Not Load Changes")
                                else ReviewDiffState.Loaded(diff)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                ReviewDiffState.Failed(error.message ?: "Could Not Load Changes")
                            }
                        }
                        if (generation != reviewGeneration) return@async
                        models = models + (file.id to ReviewDiffMapper.map(file, state))
                        mutableUiState.update { current ->
                            current.copy(reviewFiles = files.mapNotNull { models[it.id] })
                        }
                    }
                }.awaitAll()
            }
            if (generation == reviewGeneration) mutableUiState.update { it.copy(isLoadingReview = false) }
        }
    }

    private companion object {
        const val MAX_CONCURRENT_DIFFS = 4
    }
}
