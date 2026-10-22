package io.github.jcartu.hermex.ui.feature.git

import io.github.jcartu.hermex.model.GitBranches
import io.github.jcartu.hermex.model.GitBranchesResponse
import io.github.jcartu.hermex.model.GitCommitMessageResponse
import io.github.jcartu.hermex.model.GitCommitResponse
import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitDiffResponse
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.GitInfo
import io.github.jcartu.hermex.model.GitInfoResponse
import io.github.jcartu.hermex.model.GitMutationResponse
import io.github.jcartu.hermex.model.GitRemoteActionResponse
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.model.GitStatusResponse
import io.github.jcartu.hermex.model.GitTotals
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitWorkspaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApi

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        coEvery { api.sessions(any(), any()) } returns SessionsResponse(
            listOf(
                SessionSummary(sessionId = "s1", title = "Type-J", workspace = "/workspace/type-j", lastMessageAt = 20.0),
                SessionSummary(sessionId = "no-workspace", title = "Skip"),
            ),
        )
        coEvery { api.gitInfo("s1") } returns GitInfoResponse(GitInfo("main", dirty = 1, isGit = true))
        coEvery { api.gitStatus("s1") } returns GitStatusResponse(status())
        coEvery { api.gitBranches("s1") } returns GitBranchesResponse(GitBranches(isGit = true, current = "main"))
    }
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `load filters non workspace sessions and resolves git status`() = runTest(dispatcher) {
        val vm = GitWorkspaceViewModel(api)
        vm.load(); advanceUntilIdle()
        assertEquals(listOf("s1"), vm.uiState.value.sessions.map { it.sessionId })
        assertEquals("main", vm.uiState.value.status?.branch)
        assertTrue(vm.uiState.value.isGitRepository)
    }

    @Test fun `select file loads preferred staged diff`() = runTest(dispatcher) {
        coEvery { api.gitDiff("s1", "A.kt", "staged") } returns GitDiffResponse(GitDiff("A.kt", "staged", diff = "+new"))
        val vm = loaded()
        val file = vm.uiState.value.files.first()
        vm.selectFile(file); advanceUntilIdle()
        assertEquals("staged", vm.uiState.value.selectedDiffKind)
        assertEquals("+new", vm.uiState.value.diff?.diff)
    }

    @Test fun `stage selected uses explicit paths and adopts refreshed status`() = runTest(dispatcher) {
        val updated = status(files = listOf(file("B.kt", staged = true)))
        coEvery { api.gitStage("s1", listOf("A.kt")) } returns GitMutationResponse(true, updated)
        val vm = loaded()
        vm.togglePath("A.kt"); vm.stageSelected(); advanceUntilIdle()
        coVerify { api.gitStage("s1", listOf("A.kt")) }
        assertEquals("B.kt", vm.uiState.value.files.single().path)
        assertTrue(vm.uiState.value.selectedPaths.isEmpty())
    }

    @Test fun `generated message feeds server confirmed selected commit`() = runTest(dispatcher) {
        coEvery { api.gitCommitMessageSelected("s1", listOf("A.kt")) } returns GitCommitMessageResponse(true, "feat: Type-J")
        coEvery { api.gitCommitSelected("s1", "feat: Type-J", listOf("A.kt")) } returns GitCommitResponse(true, "abc123", listOf("A.kt"), status())
        val vm = loaded()
        vm.togglePath("A.kt"); vm.generateCommitMessage(); advanceUntilIdle()
        assertEquals("feat: Type-J", vm.uiState.value.commitMessage)
        vm.commit(); advanceUntilIdle()
        assertEquals("Committed abc123", vm.uiState.value.actionMessage)
        assertEquals("", vm.uiState.value.commitMessage)
    }

    @Test fun `remote rejection remains an error and never claims success`() = runTest(dispatcher) {
        coEvery { api.gitPush("s1") } returns GitRemoteActionResponse(false, "protected branch")
        val vm = loaded()
        vm.push(); advanceUntilIdle()
        assertEquals("protected branch", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.actionMessage?.contains("Pushed") == true)
    }

    @Test fun `discard forwards explicit delete untracked choice`() = runTest(dispatcher) {
        coEvery { api.gitUnstage("s1", listOf("A.kt")) } returns GitMutationResponse(true, status())
        coEvery { api.gitDiscard("s1", listOf("A.kt"), true) } returns GitMutationResponse(true, status(files = emptyList()))
        val vm = loaded()
        vm.togglePath("A.kt"); vm.discardSelected(true); advanceUntilIdle()
        coVerify { api.gitDiscard("s1", listOf("A.kt"), true) }
        coVerify { api.gitUnstage("s1", listOf("A.kt")) }
        assertTrue(vm.uiState.value.files.isEmpty())
    }

    private fun loaded(): GitWorkspaceViewModel {
        val vm = GitWorkspaceViewModel(api); vm.load(); dispatcher.scheduler.advanceUntilIdle(); return vm
    }

    private fun status(files: List<GitFile> = listOf(file("A.kt", staged = true))) = GitStatus(
        isGit = true, branch = "main", totals = GitTotals(changed = files.size, staged = files.count { it.staged == true }), files = files,
    )

    private fun file(path: String, staged: Boolean) = GitFile(
        id = path, path = path, status = "M", staged = staged, unstaged = !staged, additions = 2, deletions = 1,
    )
}
