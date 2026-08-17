package io.github.jcartu.hermex.ui.feature.memory

import io.github.jcartu.hermex.model.MemoryResponse
import io.github.jcartu.hermex.model.MemorySection
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {

    private val api = mockk<HermesApi>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load maps every section and the project context`() {
        coEvery { api.memory() } returns MemoryResponse(
            memory = "- remembers things",
            user = "Josh",
            soul = "helpful",
            memoryMtime = 100.0,
            userMtime = 200.0,
            soulMtime = 300.0,
            projectContext = "# Project",
            projectContextName = "AGENTS.md",
            projectContextWorkspace = "/srv/app",
            projectContextMtime = 400.0,
            projectContextShadowed = true,
        )
        val viewModel = MemoryViewModel(api)

        viewModel.load()

        val state = viewModel.uiState.value
        assertTrue(state.hasLoaded)
        assertFalse(state.isLoading)
        assertEquals("- remembers things", state.content(MemorySection.MEMORY))
        assertEquals("Josh", state.content(MemorySection.USER))
        assertEquals("helpful", state.content(MemorySection.SOUL))
        assertEquals(100.0, state.modifiedAt(MemorySection.MEMORY))
        assertEquals(200.0, state.modifiedAt(MemorySection.USER))
        assertEquals(300.0, state.modifiedAt(MemorySection.SOUL))
        assertTrue(state.showsProjectContext)
        assertEquals("AGENTS.md — /srv/app", state.projectContextDetail)
        assertTrue(state.isProjectContextShadowed)
    }

    @Test
    fun `missing sections render as empty content`() {
        coEvery { api.memory() } returns MemoryResponse()
        val viewModel = MemoryViewModel(api)

        viewModel.load()

        val state = viewModel.uiState.value
        assertTrue(state.hasLoaded)
        assertEquals("", state.content(MemorySection.MEMORY))
        assertNull(state.modifiedAt(MemorySection.MEMORY))
        assertFalse(state.showsProjectContext)
        assertNull(state.projectContextDetail)
        assertFalse(state.isProjectContextShadowed)
    }

    @Test
    fun `blank project context hides the section`() {
        coEvery { api.memory() } returns MemoryResponse(projectContext = "   \n")
        val viewModel = MemoryViewModel(api)

        viewModel.load()

        assertFalse(viewModel.uiState.value.showsProjectContext)
    }

    @Test
    fun `project context detail joins only non-blank parts`() {
        coEvery { api.memory() } returns MemoryResponse(
            projectContext = "text",
            projectContextName = "  ",
            projectContextWorkspace = "/srv/app",
        )
        val viewModel = MemoryViewModel(api)

        viewModel.load()

        assertEquals("/srv/app", viewModel.uiState.value.projectContextDetail)
    }

    @Test
    fun `failure surfaces an error and keeps hasLoaded false`() {
        coEvery { api.memory() } throws ApiError.Unauthorized
        val viewModel = MemoryViewModel(api)

        viewModel.load()

        val state = viewModel.uiState.value
        assertFalse(state.hasLoaded)
        assertFalse(state.isLoading)
        assertEquals(ApiError.Unauthorized.message, state.errorMessage)
    }

    @Test
    fun `loadIfNeeded fetches once and refresh clears a stale error`() {
        coEvery { api.memory() } throws ApiError.Http(500, null)
        val viewModel = MemoryViewModel(api)
        viewModel.loadIfNeeded()
        assertEquals(ApiError.Http(500, null).message, viewModel.uiState.value.errorMessage)

        coEvery { api.memory() } returns MemoryResponse(memory = "ok")
        viewModel.loadIfNeeded() // error state is not "loaded" — retries
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.hasLoaded)

        viewModel.loadIfNeeded() // now a no-op
        coVerify(exactly = 2) { api.memory() }
    }
}
