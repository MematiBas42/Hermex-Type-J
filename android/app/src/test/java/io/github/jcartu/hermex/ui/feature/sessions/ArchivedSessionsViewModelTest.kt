package io.github.jcartu.hermex.ui.feature.sessions

import io.github.jcartu.hermex.model.SessionMutationResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchivedSessionsViewModelTest {

    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        api = mockk()
    }

    private fun archived(id: String) = SessionSummary(
        sessionId = id,
        title = "Archived $id",
        archived = true,
        messageCount = 2,
    )

    @Test
    fun `load requests archived rows and filters client-side`() = runTest {
        coEvery { api.sessions(includeArchived = true) } returns SessionsResponse(
            sessions = listOf(
                archived("a1"),
                SessionSummary(sessionId = "visible", title = "Visible", messageCount = 2),
            ),
        )

        val vm = ArchivedSessionsViewModel(api)
        vm.load()

        // include_archived=1 is required (issue #17); the merged response keeps
        // visible rows too, so only archived == true rows remain.
        coVerify(exactly = 1) { api.sessions(includeArchived = true) }
        assertEquals(listOf("a1"), vm.uiState.value.sessions.map { it.sessionId })
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `load failure surfaces errorMessage`() = runTest {
        coEvery { api.sessions(includeArchived = true) } throws
            ApiError.Http(statusCode = 500, body = null)

        val vm = ArchivedSessionsViewModel(api)
        vm.load()

        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.sessions.isEmpty())
    }

    @Test
    fun `unarchive removes the row optimistically and keeps it on success`() = runTest {
        coEvery { api.sessions(includeArchived = true) } returns SessionsResponse(
            sessions = listOf(archived("a1"), archived("a2")),
        )
        coEvery { api.archiveSession("a1", false) } returns SessionMutationResponse(ok = true)

        val vm = ArchivedSessionsViewModel(api)
        vm.load()
        assertTrue(vm.unarchive(vm.uiState.value.sessions.first()))

        assertEquals(listOf("a2"), vm.uiState.value.sessions.map { it.sessionId })
        assertTrue(vm.uiState.value.unarchivingSessionIds.isEmpty())
    }

    @Test
    fun `unarchive restores the row at its index when the server rejects`() = runTest {
        coEvery { api.sessions(includeArchived = true) } returns SessionsResponse(
            sessions = listOf(archived("a1"), archived("a2")),
        )
        coEvery { api.archiveSession("a1", false) } returns SessionMutationResponse(
            error = "read-only session",
        )

        val vm = ArchivedSessionsViewModel(api)
        vm.load()
        assertFalse(vm.unarchive(vm.uiState.value.sessions.first()))

        assertEquals(listOf("a1", "a2"), vm.uiState.value.sessions.map { it.sessionId })
        assertEquals("read-only session", vm.uiState.value.actionErrorMessage)
    }

    @Test
    fun `unarchive restores the row when ok is explicitly false`() = runTest {
        coEvery { api.sessions(includeArchived = true) } returns SessionsResponse(
            sessions = listOf(archived("a1")),
        )
        coEvery { api.archiveSession("a1", false) } returns SessionMutationResponse(ok = false)

        val vm = ArchivedSessionsViewModel(api)
        vm.load()
        assertFalse(vm.unarchive(vm.uiState.value.sessions.first()))

        assertEquals(listOf("a1"), vm.uiState.value.sessions.map { it.sessionId })
        assertEquals(
            "The server could not unarchive this session.",
            vm.uiState.value.actionErrorMessage,
        )
    }

    @Test
    fun `unarchive restores the row on transport failure`() = runTest {
        coEvery { api.sessions(includeArchived = true) } returns SessionsResponse(
            sessions = listOf(archived("a1")),
        )
        coEvery { api.archiveSession("a1", false) } throws
            ApiError.Http(statusCode = 400, body = null)

        val vm = ArchivedSessionsViewModel(api)
        vm.load()
        assertFalse(vm.unarchive(vm.uiState.value.sessions.first()))

        assertEquals(listOf("a1"), vm.uiState.value.sessions.map { it.sessionId })
        assertNotNull(vm.uiState.value.actionErrorMessage)
        assertTrue(vm.uiState.value.unarchivingSessionIds.isEmpty())
    }

    @Test
    fun `unarchive without a session id fails locally`() = runTest {
        val vm = ArchivedSessionsViewModel(api)
        assertFalse(vm.unarchive(SessionSummary(title = "No id")))
        assertEquals(
            "The server did not provide a session ID.",
            vm.uiState.value.actionErrorMessage,
        )
    }
}
