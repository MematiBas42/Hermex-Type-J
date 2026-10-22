package io.github.jcartu.hermex.ui.feature.sessions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.PendingApproval
import io.github.jcartu.hermex.model.PendingClarification
import io.github.jcartu.hermex.model.SessionMutationResponse
import io.github.jcartu.hermex.model.SessionSearchResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 1.6.0–1.8.0 session-list upgrades against `SessionListViewModel`:
 * multi-word any-order search with match excerpts, unread-reply marks,
 * undo-archive, and the active-row attention probes.
 */
class SessionListUpgradesViewModelTest {

    private val serverUrl = "https://hermes.example.com:8080"

    private lateinit var directory: java.io.File
    private lateinit var api: HermesApi
    private lateinit var unreadStore: SessionUnreadStore
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("session-list-upgrades-test").toFile()
        api = mockk()
        storeScope = CoroutineScope(Job())
        unreadStore = SessionUnreadStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                java.io.File(directory, "unseen.preferences_pb")
            },
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun viewModel(withUnreadStore: Boolean = true): SessionListViewModel =
        SessionListViewModel(
            api = api,
            cacheStore = null,
            serverUrlProvider = { serverUrl },
            unreadStore = if (withUnreadStore) unreadStore else null,
        )

    private fun session(
        id: String,
        title: String = "Session $id",
        workspace: String? = null,
        model: String? = null,
        projectId: String? = null,
        lastMessageAt: Double? = 1_700_000_000.0,
        isStreaming: Boolean? = null,
        activeStreamId: String? = null,
        archived: Boolean? = null,
    ) = SessionSummary(
        sessionId = id,
        title = title,
        workspace = workspace,
        model = model,
        projectId = projectId,
        lastMessageAt = lastMessageAt,
        isStreaming = isStreaming,
        activeStreamId = activeStreamId,
        archived = archived,
    )

    // MARK: - Multi-word search (1.8.0 "multiple words in any order")

    @Test
    fun `search matches multiple words in any order across fields`() = runTest {
        val hit = session("s1", title = "Deploy pipeline", workspace = "/home/u/hermes-agent")
        val missingWord = session("s2", title = "Deploy notes")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(hit, missingWord))

        val vm = viewModel()
        vm.load()

        // "hermes deploy": both words must appear somewhere, any field, any order.
        val matches = vm.uiState.value.visibleSessions("hermes deploy", selectedProjectId = null)
        assertEquals(listOf("s1"), matches.map { it.sessionId })
    }

    @Test
    fun `search with a word only some rows have narrows the list`() = runTest {
        val a = session("s1", title = "Budget review", model = "gpt-6 astra")
        val b = session("s2", title = "Budget forecast", model = "glm-5.3")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(a, b))

        val vm = viewModel()
        vm.load()

        val matches = vm.uiState.value.visibleSessions("budget astra", selectedProjectId = null)
        assertEquals(listOf("s1"), matches.map { it.sessionId })
    }

    @Test
    fun `search excerpt rides along for content matches and clears with the search`() = runTest {
        val row = session("s1", title = "Quarterly planning")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))
        coEvery { api.searchSessions(any(), any(), any()) } returns SessionSearchResponse(
            sessions = listOf(
                SessionSummary(
                    sessionId = "s1",
                    matchType = "content",
                    matchPreview = "the budget for\\nQ3 is tight",
                ),
            ),
        )

        val vm = viewModel()
        vm.load()
        vm.searchSessions("budget", debounceMillis = 0)

        // The row matched locally on nothing ("budget" is not in the title), so
        // it arrives as a remote content match carrying its excerpt.
        val visible = vm.uiState.value.visibleSessions("budget", selectedProjectId = null)
        assertEquals(listOf("s1"), visible.map { it.sessionId })

        val excerpt = vm.uiState.value.searchExcerptFor(row, searchText = "budget")
        assertEquals("the budget for Q3 is tight", excerpt?.text)

        // A screen asking with another query inherits nothing.
        assertNull(vm.uiState.value.searchExcerptFor(row, searchText = "other"))

        vm.clearSearchResults()
        assertNull(vm.uiState.value.searchExcerptFor(row, searchText = "budget"))
    }

    // MARK: - Unread replies (1.7.0)

    @Test
    fun `a settled row is unread only past the device seen mark`() = runTest {
        val row = session("s1", lastMessageAt = 1_700_000_000.0)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))

        val vm = viewModel()
        vm.load()

        // First load seeds the mark for rows never seen on this device.
        assertFalse(vm.uiState.value.isUnread(row))

        // The server's timestamp moving past the mark lights the dot.
        val updated = session("s1", lastMessageAt = 1_700_000_100.0)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(updated))
        vm.load()
        assertTrue(vm.uiState.value.isUnread(updated))

        // A streaming row never shows the dot.
        val streaming = session("s1", lastMessageAt = 1_700_000_200.0, isStreaming = true)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(streaming))
        vm.load()
        assertFalse(vm.uiState.value.isUnread(streaming))
    }

    @Test
    fun `toggleUnread marks unread below the timestamp and read at it`() = runTest {
        val row = session("s1", lastMessageAt = 1_700_000_000.0)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))

        val vm = viewModel()
        vm.load()
        assertFalse(vm.uiState.value.isUnread(row))

        vm.toggleUnread(row)
        assertTrue(vm.uiState.value.isUnread(row))

        vm.toggleUnread(row)
        assertFalse(vm.uiState.value.isUnread(row))

        // Marks survive a relaunch through the store.
        val vm2 = viewModel()
        vm2.load()
        assertTrue(vm2.uiState.value.isUnread(row.copy(lastMessageAt = 1_700_000_050.0)))
        assertFalse(vm2.uiState.value.isUnread(row))
    }

    @Test
    fun `opening a chat marks it seen and streaming rows are not toggleable`() = runTest {
        val row = session("s1", lastMessageAt = 1_700_000_000.0)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))

        val vm = viewModel()
        vm.load()
        vm.toggleUnread(row)
        assertTrue(vm.uiState.value.isUnread(row))

        vm.beginViewing(row)
        assertFalse(vm.uiState.value.isUnread(row))

        val streaming = session("s1", lastMessageAt = 1_700_000_300.0, activeStreamId = "st-1")
        assertFalse(vm.uiState.value.canToggleUnread(streaming))
    }

    // MARK: - Undo archive (1.8.0 #865)

    @Test
    fun `unarchive restores the session via archiveSession false`() = runTest {
        val row = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))
        coEvery { api.archiveSession(any(), any()) } returns SessionMutationResponse(ok = true)

        val vm = viewModel()
        vm.load()

        assertTrue(vm.archive(row))
        coVerify { api.archiveSession("s1", true) }

        // The toast's Undo: restores, then reloads so the row returns.
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))
        assertTrue(vm.unarchive(row))
        coVerify { api.archiveSession("s1", false) }
        assertEquals(listOf("s1"), vm.uiState.value.sessions.map { it.sessionId })
    }

    @Test
    fun `a second unarchive while one is in flight sends nothing`() = runTest {
        val row = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))
        coEvery { api.archiveSession(any(), any()) } coAnswers {
            delay(10_000)
            SessionMutationResponse(ok = true)
        }

        val vm = viewModel()
        vm.load()

        val first = async { vm.unarchive(row) }
        delay(1)
        assertFalse(vm.unarchive(row))
        first.cancel()
        coVerify(exactly = 1) { api.archiveSession("s1", false) }
    }

    // MARK: - Active-row attention probes (1.6.0)

    @Test
    fun `attention probes resolve approval over input over working per streaming row`() = runTest {
        val approvalRow = session("s1", isStreaming = true, activeStreamId = "a")
        val inputRow = session("s2", isStreaming = true, activeStreamId = "b")
        val workingRow = session("s3", isStreaming = true, activeStreamId = "c")
        coEvery { api.sessions() } returns SessionsResponse(
            sessions = listOf(approvalRow, inputRow, workingRow),
        )
        coEvery { api.chatStreamStatus(any()) } returns
            io.github.jcartu.hermex.model.ChatStreamStatusResponse(active = true)
        coEvery { api.approvalPending("s1") } returns
            ApprovalPendingResponse(pending = PendingApproval.of(approvalId = "ap-1"))
        coEvery { api.approvalPending("s2") } returns ApprovalPendingResponse(pending = null)
        coEvery { api.approvalPending("s3") } returns ApprovalPendingResponse(pending = null)
        coEvery { api.clarifyPending("s1") } returns
            ClarificationPendingResponse(pending = PendingClarification(clarifyId = "cl-1"))
        coEvery { api.clarifyPending("s2") } returns
            ClarificationPendingResponse(pending = PendingClarification(clarifyId = "cl-2"))
        coEvery { api.clarifyPending("s3") } returns ClarificationPendingResponse(pending = null)

        val vm = viewModel()
        vm.load()
        val result = vm.refreshActiveSessionStatesIfNeeded(listOf("a", "b", "c"))

        assertEquals(ActiveSessionStateRefreshResult.UNCHANGED, result)
        val states = vm.uiState.value.attentionStatesBySessionId
        // Approval masks the clarification on the same row.
        assertEquals(SessionRowAttentionState.APPROVAL, states["s1"])
        assertEquals(SessionRowAttentionState.INPUT, states["s2"])
        assertEquals(SessionRowAttentionState.WORKING, states["s3"])
    }

    @Test
    fun `a failed probe keeps the previous state instead of falling back to working`() = runTest {
        val row = session("s1", isStreaming = true, activeStreamId = "a")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))
        coEvery { api.chatStreamStatus(any()) } returns
            io.github.jcartu.hermex.model.ChatStreamStatusResponse(active = true)
        coEvery { api.approvalPending("s1") } returns
            ApprovalPendingResponse(pending = PendingApproval.of(approvalId = "ap-1"))
        coEvery { api.clarifyPending("s1") } returns ClarificationPendingResponse(pending = null)

        val vm = viewModel()
        vm.load()
        vm.refreshActiveSessionStatesIfNeeded(listOf("a"))
        assertEquals(
            SessionRowAttentionState.APPROVAL,
            vm.uiState.value.attentionStatesBySessionId["s1"],
        )

        coEvery { api.approvalPending("s1") } throws ApiError.Network(java.net.SocketTimeoutException())
        vm.refreshActiveSessionStatesIfNeeded(listOf("a"))
        assertEquals(
            SessionRowAttentionState.APPROVAL,
            vm.uiState.value.attentionStatesBySessionId["s1"],
        )
    }

    @Test
    fun `a settled stream reloads the tick instead of probing`() = runTest {
        val row = session("s1", isStreaming = true, activeStreamId = "a")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(row))
        coEvery { api.chatStreamStatus("a") } returns
            io.github.jcartu.hermex.model.ChatStreamStatusResponse(active = false)
        coEvery { api.approvalPending("s1") } returns ApprovalPendingResponse(pending = null)
        coEvery { api.clarifyPending("s1") } returns ClarificationPendingResponse(pending = null)

        val vm = viewModel()
        vm.load()
        // The stream ended: the tick reloads the list (which drops the stream
        // state) instead of probing.
        val result = vm.refreshActiveSessionStatesIfNeeded(listOf("a"))
        assertEquals(ActiveSessionStateRefreshResult.RELOADED, result)
        assertTrue(vm.uiState.value.attentionStatesBySessionId.isEmpty())
        coVerify(exactly = 0) { api.approvalPending(any()) }
    }

    // MARK: - Idle ticker (1.8.0)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun `idle ticker emits immediately then once per wall minute until cancelled`() = runTest {
        val ticks = mutableListOf<Long>()
        var fakeNow = 1_700_000_000L
        // The fake wall clock steps with the fake epoch, staying minute-aligned:
        // every tick waits exactly 60 virtual seconds.
        var fakeNowMillis = 120_000L
        val job = launch {
            SessionListIdleTicker.run(
                nowEpochSeconds = { fakeNow },
                nowMillis = { fakeNowMillis },
                emit = ticks::add,
            )
        }

        // Immediate first tick once the launch runs.
        testScheduler.runCurrent()
        assertEquals(listOf(fakeNow), ticks)

        // 60_001 covers the next tick whether or not the endpoint is inclusive.
        fakeNow += 60
        fakeNowMillis += 60_000L
        advanceTimeBy(60_001)
        assertEquals(listOf(1_700_000_000L, 1_700_000_060L), ticks)

        job.cancel()
        advanceTimeBy(300_000)
        assertEquals(2, ticks.size)
    }

    @Test
    fun `minute boundary math`() {
        assertEquals(60_000L, SessionListIdleTicker.millisUntilNextMinute(0))
        assertEquals(59_000L, SessionListIdleTicker.millisUntilNextMinute(1_000))
        assertEquals(1_000L, SessionListIdleTicker.millisUntilNextMinute(59_000))
    }
}
