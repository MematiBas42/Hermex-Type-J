package io.github.jcartu.hermex.ui.feature.git.hermes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.hermes.HermesGitClient
import io.github.jcartu.hermex.net.hermes.HermesGitRefusal
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceUiState
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffFileModel
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffMapper
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffState
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Direct-Hermes Git reducer over `HermesGitClient` (#1114–#1116/#1236). It reuses
 * the Wave 1 status, commit, branch, and ReviewDiff composables without webui sessions.
 */
class HermesGitWorkspaceViewModel(
    val client: HermesGitClient,
) : ViewModel() {
    private val context = client.context
    private val session = SessionSummary(
        sessionId = context.storedKey,
        title = context.profile,
        workspace = context.cwd,
        profile = context.profile,
    )
    private val mutableUiState = MutableStateFlow(
        GitWorkspaceUiState(
            sessions = listOf(session),
            selectedSessionId = context.storedKey,
            isHermes = true,
            supportsSync = false,
        ),
    )
    val uiState: StateFlow<GitWorkspaceUiState> = mutableUiState.asStateFlow()

    private var reviewJob: Job? = null
    private var reviewGeneration = 0L

    fun load() = viewModelScope.launch { refreshInternal() }

    fun refresh() = viewModelScope.launch { refreshInternal() }

    fun selectSession(@Suppress("UNUSED_PARAMETER") sessionId: String) = Unit

    fun selectFile(file: GitFile) {
        mutableUiState.update { it.copy(selectedFilePath = file.displayPath) }
    }

    fun closeDiff() {
        mutableUiState.update { it.copy(selectedFilePath = null) }
    }

    fun togglePath(path: String) {
        mutableUiState.update {
            it.copy(selectedPaths = if (path in it.selectedPaths) it.selectedPaths - path else it.selectedPaths + path)
        }
    }

    fun setCommitMessage(message: String) {
        mutableUiState.update { it.copy(commitMessage = message) }
    }

    fun dismissMessage() {
        mutableUiState.update { it.copy(errorMessage = null, actionMessage = null) }
    }

    fun stageSelected() = mutate("Changes staged") { files -> client.stage(files) }

    fun unstageSelected() = mutate("Changes unstaged") { files -> client.unstage(files) }

    fun discardSelected(deleteUntracked: Boolean) = mutate("Changes discarded") { files ->
        client.discard(files, deleteUntracked)
    }

    fun generateCommitMessage() = runAction {
        val files = mutableUiState.value.selectedFiles.takeIf(List<GitFile>::isNotEmpty)
        val response = client.suggestMessage(files, avoiding = mutableUiState.value.commitMessage.takeIf(String::isNotBlank))
        val message = response.message?.trim().orEmpty()
        if (message.isEmpty()) throw HermesGitRefusal.NoMessage
        mutableUiState.update {
            it.copy(
                commitMessage = message,
                actionMessage = if (response.truncated == true) "Diff was large; message may be partial." else null,
            )
        }
    }

    fun commit() = runAction {
        val state = mutableUiState.value
        val selected = state.selectedFiles.takeIf(List<GitFile>::isNotEmpty)
        val result = client.writeLock.withLock {
            client.commit(state.commitMessage.trim(), selected)
        }
        val nextStatus = result.response.resolvedStatus ?: client.status()
        mutableUiState.update {
            it.copy(
                status = nextStatus,
                selectedPaths = emptySet(),
                commitMessage = "",
                actionMessage = buildString {
                    append(result.response.shortSHA?.let { sha -> "Commit $sha" } ?: "Committed")
                    if (result.stagingNotRestored) append("; some files couldn’t be staged again")
                },
            )
        }
        loadReview(nextStatus)
    }

    fun fetch() = remote("Fetch complete") { client.fetch() }

    fun pull() = remote("Pull complete") { client.pull() }

    fun push() = remote("Push complete") { client.push() }

    fun checkout(target: GitCheckoutTarget, @Suppress("UNUSED_PARAMETER") stashFirst: Boolean = false) = runAction {
        val response = client.writeLock.withLock { client.checkout(target, stashingChanges = false) }
        val status = response.resolvedStatus ?: client.status()
        mutableUiState.update {
            it.copy(
                status = status,
                branches = client.branches(),
                actionMessage = response.currentBranch?.let { branch -> "Switched to $branch" } ?: "Branch switched",
            )
        }
        loadReview(status)
    }

    private suspend fun refreshInternal() {
        mutableUiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            coroutineScope {
                val info = async { client.info() }
                val status = async { client.status() }
                val branches = async { client.branches() }
                val supportsSync = async { client.supportsSync() }
                val loadedStatus = status.await()
                mutableUiState.update {
                    it.copy(
                        info = info.await(),
                        status = loadedStatus,
                        branches = branches.await(),
                        supportsSync = supportsSync.await(),
                        isLoading = false,
                    )
                }
                loadReview(loadedStatus)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            mutableUiState.update { it.copy(isLoading = false, errorMessage = friendly(error)) }
        }
    }

    private fun mutate(
        success: String,
        operation: suspend (List<GitFile>) -> GitStatus?,
    ) = runAction {
        val files = mutableUiState.value.selectedFiles
        if (files.isEmpty()) return@runAction
        val status = client.writeLock.withLock { operation(files) } ?: client.status()
        mutableUiState.update {
            it.copy(status = status, selectedPaths = emptySet(), actionMessage = success)
        }
        loadReview(status)
    }

    private fun remote(
        success: String,
        operation: suspend () -> io.github.jcartu.hermex.model.GitRemoteActionResponse,
    ) = runAction {
        val response = client.writeLock.withLock { operation() }
        val status = response.status ?: client.status()
        mutableUiState.update { it.copy(status = status, actionMessage = response.message ?: success) }
        loadReview(status)
    }

    private fun runAction(operation: suspend () -> Unit): Job = viewModelScope.launch {
        if (mutableUiState.value.isActing) return@launch
        mutableUiState.update { it.copy(isActing = true, errorMessage = null, actionMessage = null) }
        try {
            operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            mutableUiState.update { it.copy(errorMessage = friendly(error)) }
            if (error is HermesGitRefusal) refreshAfterFailedWrite()
        } finally {
            mutableUiState.update { it.copy(isActing = false) }
        }
    }

    private suspend fun refreshAfterFailedWrite() {
        val status = runCatching { client.status() }.getOrNull() ?: return
        mutableUiState.update { it.copy(status = status) }
        loadReview(status)
    }

    private fun loadReview(status: GitStatus?) {
        reviewJob?.cancel()
        val files = status?.trackedFiles.orEmpty()
        val generation = ++reviewGeneration
        if (files.isEmpty()) {
            mutableUiState.update { it.copy(reviewFiles = emptyList(), isLoadingReview = false) }
            return
        }
        mutableUiState.update {
            it.copy(
                isLoadingReview = true,
                reviewFiles = files.map { file -> ReviewDiffMapper.map(file, ReviewDiffState.Loading) },
            )
        }
        reviewJob = viewModelScope.launch {
            val semaphore = Semaphore(4)
            val loaded = coroutineScope {
                files.map { file ->
                    async {
                        semaphore.withPermit {
                            try {
                                val diff = client.diff(file)
                                if (diff == null) {
                                    ReviewDiffMapper.map(file, ReviewDiffState.Failed("Could Not Load Changes"))
                                } else {
                                    ReviewDiffMapper.map(file, ReviewDiffState.Loaded(diff))
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                ReviewDiffMapper.map(file, ReviewDiffState.Failed(friendly(error)))
                            }
                        }
                    }
                }.awaitAll()
            }
            if (generation != reviewGeneration) return@launch
            mutableUiState.update { it.copy(reviewFiles = loaded, isLoadingReview = false) }
        }
    }

    companion object {
        /** User-safe refusal copy; notably Hermes dirty switches never offer stash. */
        fun friendly(error: Throwable): String = when (error) {
            HermesGitRefusal.UncommittedSwitch -> "Commit or discard first."
            else -> error.message?.takeIf(String::isNotBlank) ?: "Git couldn’t finish this change on your Hermes host."
        }
    }
}
