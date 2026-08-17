package io.github.jcartu.hermex.ui.feature.sessions

import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.model.AutomatedSessionVisibility
import io.github.jcartu.hermex.model.ProjectMutationResponse
import io.github.jcartu.hermex.model.ProjectSummary
import io.github.jcartu.hermex.model.ProjectsResponse
import io.github.jcartu.hermex.model.SessionBranchResponse
import io.github.jcartu.hermex.model.SessionDetail
import io.github.jcartu.hermex.model.SessionMutationResponse
import io.github.jcartu.hermex.model.SessionResponse
import io.github.jcartu.hermex.model.SessionSearchResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionListViewModelTest {

    private val serverUrl = "https://hermes.example.com:8080"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private lateinit var directory: File
    private lateinit var cacheStore: CacheStore
    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("session-list-vm-test").toFile()
        cacheStore = CacheStore(directory, json)
        api = mockk()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun viewModel(store: CacheStore? = cacheStore): SessionListViewModel =
        SessionListViewModel(
            api = api,
            cacheStore = store,
            serverUrlProvider = { serverUrl },
        )

    private fun session(
        id: String,
        title: String = "Session $id",
        pinned: Boolean? = null,
        archived: Boolean? = null,
        projectId: String? = null,
        lastMessageAt: Double? = 1_700_000_000.0,
        messageCount: Int? = 3,
        sessionSource: String? = null,
        readOnly: Boolean? = null,
    ) = SessionSummary(
        sessionId = id,
        title = title,
        pinned = pinned,
        archived = archived,
        projectId = projectId,
        lastMessageAt = lastMessageAt,
        messageCount = messageCount,
        sessionSource = sessionSource,
        readOnly = readOnly,
    )

    // MARK: - load

    @Test
    fun `load keeps visible sessions, drops archived and placeholders, and caches`() = runTest {
        val visible = session("s1")
        val archived = session("s2", archived = true)
        // Empty Untitled placeholder: no title, no messages, no state.
        val placeholder = SessionSummary(sessionId = "s3", title = "Untitled", messageCount = 0)
        coEvery { api.sessions() } returns SessionsResponse(
            sessions = listOf(visible, archived, placeholder),
            archivedCount = 4,
        )

        val vm = viewModel()
        assertTrue(vm.load())

        val state = vm.uiState.value
        assertEquals(listOf(visible), state.sessions)
        assertEquals(4, state.archivedCount)
        assertFalse(state.isViewingCachedData)
        assertNull(state.errorMessage)
        // The successful load wrote through to the offline cache.
        assertEquals(listOf("s1"), cacheStore.cachedSessions(serverUrl).map { it.sessionId })
    }

    @Test
    fun `load falls back to cache on connectivity failure`() = runTest {
        cacheStore.cacheSessions(listOf(session("cached-1")), serverUrl)
        coEvery { api.sessions() } throws ApiError.Network(UnknownHostException("offline"))

        val vm = viewModel()
        assertFalse(vm.load())

        val state = vm.uiState.value
        assertTrue(state.isViewingCachedData)
        assertEquals(listOf("cached-1"), state.sessions.map { it.sessionId })
        assertNull(state.errorMessage)
        assertTrue(state.loadFailedOffline)
    }

    @Test
    fun `load with connectivity failure and empty cache surfaces the error`() = runTest {
        coEvery { api.sessions() } throws ApiError.Network(UnknownHostException("offline"))

        val vm = viewModel()
        assertFalse(vm.load())

        val state = vm.uiState.value
        assertFalse(state.isViewingCachedData)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `load does not use cache for non-connectivity failures`() = runTest {
        cacheStore.cacheSessions(listOf(session("cached-1")), serverUrl)
        coEvery { api.sessions() } throws ApiError.Http(statusCode = 400, body = null)

        val vm = viewModel()
        assertFalse(vm.load())

        val state = vm.uiState.value
        assertFalse(state.isViewingCachedData)
        assertTrue(state.sessions.isEmpty())
        assertNotNull(state.errorMessage)
        assertFalse(state.loadFailedOffline)
    }

    // MARK: - filtering / grouping

    @Test
    fun `visibleSessions filters by query, project, and automated visibility`() = runTest {
        val ordinary = session("s1", title = "Fix the parser", projectId = "p1")
        val otherProject = session("s2", title = "Fix the lexer", projectId = "p2")
        val cron = session("cron_1", title = "Fix nightly job", sessionSource = "cron")
        coEvery { api.sessions() } returns SessionsResponse(
            sessions = listOf(ordinary, otherProject, cron),
        )

        val vm = viewModel()
        vm.load()
        val state = vm.uiState.value

        // Local text search matches titles case-insensitively.
        assertEquals(
            listOf("s1", "s2"),
            state.visibleSessions("fix the", null, AutomatedSessionVisibility.showAll)
                .map { it.sessionId },
        )

        // Project filter.
        assertEquals(
            listOf("s1"),
            state.visibleSessions("", "p1", AutomatedSessionVisibility.showAll)
                .map { it.sessionId },
        )

        // Cron hidden when showsCron is false.
        val noCron = state.visibleSessions(
            "",
            null,
            AutomatedSessionVisibility(showsCron = false, showsCli = true),
        )
        assertTrue(noCron.none { it.sessionId == "cron_1" })
    }

    @Test
    fun `visibleSessions appends remote content matches after local matches`() = runTest {
        val local = session("s1", title = "budget report")
        val remoteOnly = session("s2", title = "untitled meeting")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(local, remoteOnly))
        coEvery { api.searchSessions(any(), any(), any()) } returns SessionSearchResponse(
            sessions = listOf(
                SessionSummary(sessionId = "s2", matchType = "content"),
                SessionSummary(sessionId = "s1", matchType = "content"),
                SessionSummary(sessionId = "missing", matchType = "content"),
            ),
        )

        val vm = viewModel()
        vm.load()
        vm.searchSessions("budget", debounceMillis = 0)

        val state = vm.uiState.value
        assertEquals(listOf("s2"), state.remoteContentSearchSessionIds - setOf("s1"))
        // Local title match first, then the remote content-only match.
        assertEquals(
            listOf("s1", "s2"),
            state.visibleSessions("budget", null).map { it.sessionId },
        )
    }

    @Test
    fun `searchSessions failure clears matches and sets searchErrorMessage`() = runTest {
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(session("s1")))
        coEvery { api.searchSessions(any(), any(), any()) } throws
            ApiError.Http(statusCode = 500, body = null)

        val vm = viewModel()
        vm.load()
        vm.searchSessions("query", debounceMillis = 0)

        val state = vm.uiState.value
        assertTrue(state.remoteContentSearchSessionIds.isEmpty())
        assertNotNull(state.searchErrorMessage)
        assertFalse(state.isSearchingRemoteSessions)
    }

    @Test
    fun `searchSessions is skipped while viewing cached data`() = runTest {
        cacheStore.cacheSessions(listOf(session("cached-1")), serverUrl)
        coEvery { api.sessions() } throws ApiError.Network(UnknownHostException("offline"))

        val vm = viewModel()
        vm.load()
        vm.searchSessions("query", debounceMillis = 0)

        coVerify(exactly = 0) { api.searchSessions(any(), any(), any()) }
        assertFalse(vm.uiState.value.isSearchingRemoteSessions)
    }

    @Test
    fun `sessionListSections groups pinned today yesterday earlier with counts`() {
        val zone = ZoneOffset.UTC
        val now = 1_755_432_000L // 2026-08-17T12:00:00Z
        val pinnedSession = session("pin", pinned = true, lastMessageAt = (now - 3 * 86_400).toDouble())
        val todaySession = session("today", lastMessageAt = (now - 3_600).toDouble())
        val yesterdaySession = session("yesterday", lastMessageAt = (now - 86_400).toDouble())
        val earlierSession = session("earlier", lastMessageAt = (now - 5 * 86_400).toDouble())
        val undatedSession = session("undated", lastMessageAt = null, messageCount = 1)

        val sections = sessionListSections(
            sessions = listOf(earlierSession, todaySession, pinnedSession, yesterdaySession, undatedSession),
            zoneId = zone,
            nowEpochSeconds = now,
        )

        assertEquals(
            listOf(
                SessionListSection.Kind.PINNED,
                SessionListSection.Kind.TODAY,
                SessionListSection.Kind.YESTERDAY,
                SessionListSection.Kind.EARLIER,
            ),
            sections.map { it.kind },
        )
        assertEquals(listOf("pin"), sections[0].sessions.map { it.sessionId })
        assertEquals(listOf("today"), sections[1].sessions.map { it.sessionId })
        assertEquals(listOf("yesterday"), sections[2].sessions.map { it.sessionId })
        // Sessions with no usable timestamp land in Earlier, like the Swift guard.
        assertEquals(
            setOf("earlier", "undated"),
            sections[3].sessions.map { it.sessionId }.toSet(),
        )
    }

    @Test
    fun `scheduledSessionGroups separates cron sessions and counts them`() = runTest {
        val ordinary = session("s1")
        val cron = session("cron_1", sessionSource = "cron")
        val archivedCron = session("cron_2", sessionSource = "cron", archived = true)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(ordinary, cron, archivedCron))

        val vm = viewModel()
        vm.load()
        val groups = vm.uiState.value.scheduledSessionGroups("", null)

        assertEquals(listOf("s1"), groups.ordinary.map { it.sessionId })
        assertEquals(listOf("cron_1"), groups.scheduled.map { it.sessionId })
        assertEquals(1, groups.totalScheduledCount)
        assertTrue(groups.showsDisclosure(isSearchActive = false))

        val hiddenCron = vm.uiState.value.scheduledSessionGroups(
            "",
            null,
            AutomatedSessionVisibility(showsCron = false, showsCli = true),
        )
        assertEquals(0, hiddenCron.totalScheduledCount)
        assertFalse(hiddenCron.showsDisclosure(isSearchActive = false))
    }

    // MARK: - mutations

    @Test
    fun `setPinned pins then reloads`() = runTest {
        val target = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target)) andThen
            SessionsResponse(sessions = listOf(target.copy(pinned = true)))
        coEvery { api.pinSession("s1", true) } returns SessionMutationResponse(ok = true)

        val vm = viewModel()
        vm.load()
        assertTrue(vm.setPinned(true, target))

        coVerify(exactly = 1) { api.pinSession("s1", true) }
        assertEquals(true, vm.uiState.value.sessions.single().pinned)
        assertTrue(vm.uiState.value.mutatingSessionIds.isEmpty())
    }

    @Test
    fun `setPinned failure keeps sessions and surfaces action error`() = runTest {
        val target = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.pinSession("s1", true) } throws ApiError.Http(statusCode = 500, body = null)

        val vm = viewModel()
        vm.load()
        assertFalse(vm.setPinned(true, target))

        val state = vm.uiState.value
        // The optimistic flow rolled back: row unchanged, error surfaced, gate released.
        assertEquals(listOf(target), state.sessions)
        assertNotNull(state.actionErrorMessage)
        assertTrue(state.mutatingSessionIds.isEmpty())
    }

    @Test
    fun `delete removes the session through reload`() = runTest {
        val target = session("s1")
        val other = session("s2")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target, other)) andThen
            SessionsResponse(sessions = listOf(other))
        coEvery { api.deleteSession("s1") } returns SessionMutationResponse(ok = true)

        val vm = viewModel()
        vm.load()
        assertTrue(vm.delete(target))

        assertEquals(listOf("s2"), vm.uiState.value.sessions.map { it.sessionId })
    }

    @Test
    fun `rename updates the row in place with the server-resolved title`() = runTest {
        val target = session("s1", title = "Old title")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.renameSession("s1", "New title") } returns SessionMutationResponse(
            ok = true,
            session = target.copy(title = "Server title"),
        )

        val vm = viewModel()
        vm.load()
        assertTrue(vm.rename(target, "New title"))

        assertEquals("Server title", vm.uiState.value.sessions.single().title)
        assertFalse(vm.uiState.value.isRenamingSession)
    }

    @Test
    fun `rename with server error body fails with that message`() = runTest {
        val target = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.renameSession("s1", "New title") } returns SessionMutationResponse(
            error = "title rejected",
        )

        val vm = viewModel()
        vm.load()
        assertFalse(vm.rename(target, "New title"))
        assertEquals("title rejected", vm.uiState.value.actionErrorMessage)
    }

    @Test
    fun `rename is blocked while viewing cached data`() = runTest {
        cacheStore.cacheSessions(listOf(session("cached-1")), serverUrl)
        coEvery { api.sessions() } throws ApiError.Network(UnknownHostException("offline"))

        val vm = viewModel()
        vm.load()
        assertFalse(vm.rename(session("cached-1"), "New title"))
        assertEquals(
            "Reconnect to the server to rename a session.",
            vm.uiState.value.actionErrorMessage,
        )
        coVerify(exactly = 0) { api.renameSession(any(), any()) }
    }

    @Test
    fun `duplicate branches with full history and inserts the new session`() = runTest {
        val target = session("s1", title = "Original")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.branchSession("s1", null, "Original (copy)") } returns
            SessionBranchResponse(sessionId = "s1-copy")
        coEvery { api.session("s1-copy", false, null) } returns SessionResponse(
            session = SessionDetail(sessionId = "s1-copy", title = "Original (copy)", messageCount = 3),
        )

        val vm = viewModel()
        vm.load()
        val duplicated = vm.duplicate(target)

        assertEquals("s1-copy", duplicated?.sessionId)
        // keep_count omitted: the list-level branch keeps the full history.
        coVerify(exactly = 1) { api.branchSession("s1", null, "Original (copy)") }
        assertEquals(
            listOf("s1-copy", "s1"),
            vm.uiState.value.sessions.map { it.sessionId },
        )
    }

    @Test
    fun `duplicate without a returned session id surfaces the server error`() = runTest {
        val target = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.branchSession("s1", null, any()) } returns
            SessionBranchResponse(error = "branch refused")

        val vm = viewModel()
        vm.load()
        assertNull(vm.duplicate(target))
        assertEquals("branch refused", vm.uiState.value.actionErrorMessage)
    }

    @Test
    fun `move maps the busy 503 to the streaming message`() = runTest {
        val target = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.moveSession("s1", "p1") } throws ApiError.Http(
            statusCode = 503,
            body = """{"error": "session is busy"}""",
        )

        val vm = viewModel()
        vm.load()
        assertFalse(vm.move(target, "p1"))

        assertEquals(
            "This session is still responding, so it can't be moved yet. " +
                "Try again when it finishes.",
            vm.uiState.value.actionErrorMessage,
        )
        assertFalse(vm.uiState.value.isMovingSession)
    }

    @Test
    fun `move succeeds and reloads`() = runTest {
        val target = session("s1", projectId = null)
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target)) andThen
            SessionsResponse(sessions = listOf(target.copy(projectId = "p1")))
        coEvery { api.moveSession("s1", "p1") } returns SessionMutationResponse(ok = true)

        val vm = viewModel()
        vm.load()
        assertTrue(vm.move(target, "p1"))
        assertEquals("p1", vm.uiState.value.sessions.single().projectId)
    }

    // MARK: - projects

    @Test
    fun `loadProjects stores the project list`() = runTest {
        coEvery { api.projects() } returns ProjectsResponse(
            projects = listOf(ProjectSummary(projectId = "p1", name = "Work")),
        )

        val vm = viewModel()
        vm.loadProjects()
        assertEquals(listOf("p1"), vm.uiState.value.projects.map { it.projectId })
    }

    @Test
    fun `createProject creates, moves the session, and reloads`() = runTest {
        val target = session("s1")
        coEvery { api.sessions() } returns SessionsResponse(sessions = listOf(target))
        coEvery { api.createProject("Work", "#7cb9ff") } returns ProjectMutationResponse(
            project = ProjectSummary(projectId = "p1", name = "Work"),
        )
        coEvery { api.moveSession("s1", "p1") } returns SessionMutationResponse(ok = true)

        val vm = viewModel()
        vm.load()
        assertTrue(vm.createProject("Work", "#7cb9ff", target))

        coVerify(exactly = 1) { api.moveSession("s1", "p1") }
        assertEquals(listOf("p1"), vm.uiState.value.projects.map { it.projectId })
        assertFalse(vm.uiState.value.isCreatingProject)
        assertFalse(vm.uiState.value.isMovingSession)
    }

    @Test
    fun `createProject with blank name fails locally`() = runTest {
        val vm = viewModel()
        assertFalse(vm.createProject("   ", "#7cb9ff", session("s1")))
        assertEquals("Enter a project name.", vm.uiState.value.actionErrorMessage)
        coVerify(exactly = 0) { api.createProject(any(), any()) }
    }
}
