package io.github.jcartu.hermex.ui.feature.git.hermes

import io.github.jcartu.hermex.model.GitBranchMode
import io.github.jcartu.hermex.model.GitBranches
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitInfo
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.net.hermes.HermesGitClient
import io.github.jcartu.hermex.net.hermes.HermesGitRefusal
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceContext
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceUiState
import io.github.jcartu.hermex.ui.feature.git.showsCompanionSyncActions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
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
class HermesGitWorkspaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `dirty Hermes branch switch refuses with exact copy and never offers stash`() = runTest(dispatcher) {
        val client = client(supportsSync = false)
        coEvery { client.checkout(any(), stashingChanges = false) } throws HermesGitRefusal.UncommittedSwitch
        val viewModel = HermesGitWorkspaceViewModel(client)
        viewModel.load()
        advanceUntilIdle()

        viewModel.checkout(GitCheckoutTarget("feature", GitBranchMode.LOCAL), stashFirst = true)
        advanceUntilIdle()

        assertEquals("Commit or discard first.", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.supportsSync)
        assertTrue(viewModel.uiState.value.isHermes)
    }

    @Test
    fun `companion actions stay hidden until capabilities support every sync route`() = runTest(dispatcher) {
        assertFalse(GitWorkspaceUiState(isHermes = true, supportsSync = false).showsCompanionSyncActions)
        assertTrue(GitWorkspaceUiState(isHermes = true, supportsSync = true).showsCompanionSyncActions)
        assertTrue(GitWorkspaceUiState(isHermes = false, supportsSync = false).showsCompanionSyncActions)

        val withoutCapabilities = HermesGitWorkspaceViewModel(client(supportsSync = false))
        withoutCapabilities.load()
        advanceUntilIdle()
        assertFalse(withoutCapabilities.uiState.value.showsCompanionSyncActions)
    }

    private fun client(supportsSync: Boolean): HermesGitClient {
        val client = mockk<HermesGitClient>()
        every { client.context } returns HermesWorkspaceContext(
            server = "http://hermes",
            profile = "default",
            storedKey = "session-1",
            cwd = "/work",
            terminalBackend = "local",
        )
        every { client.writeLock } returns Mutex()
        coEvery { client.info() } returns GitInfo(branch = "main", dirty = 0, isGit = true)
        coEvery { client.status() } returns GitStatus(isGit = true, branch = "main", files = emptyList())
        coEvery { client.branches() } returns GitBranches(isGit = true, current = "main")
        coEvery { client.supportsSync() } returns supportsSync
        return client
    }
}
