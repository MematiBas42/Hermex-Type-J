package io.github.jcartu.hermex.ui.feature.skills

import io.github.jcartu.hermex.model.SkillDetailResponse
import io.github.jcartu.hermex.net.ApiError
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load fetches content and linked files`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.skillContent("git") } returns SkillDetailResponse(
            name = "git",
            content = "# Git skill",
            linkedFiles = listOf("usage.md", "advanced.md"),
        )

        val viewModel = SkillDetailViewModel(api, "git")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("# Git skill", state.content)
        assertEquals(listOf("usage.md", "advanced.md"), state.linkedFiles)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `load failure surfaces error message`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.skillContent("git") } throws ApiError.Http(500, null)

        val viewModel = SkillDetailViewModel(api, "git")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.content)
    }

    @Test
    fun `blank skill name never hits the network`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        val viewModel = SkillDetailViewModel(api, "")
        advanceUntilIdle()

        assertEquals(SkillDetailUiState(), viewModel.uiState.value)
        coVerify(exactly = 0) { api.skillContent(any(), any()) }
    }

    @Test
    fun `open file loads the linked file content`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.skillContent("git") } returns SkillDetailResponse(
            name = "git",
            content = "# Git",
            linkedFiles = listOf("usage.md"),
        )
        coEvery { api.skillContent("git", "usage.md") } returns SkillDetailResponse(
            name = "git",
            content = "## Usage details",
        )

        val viewModel = SkillDetailViewModel(api, "git")
        advanceUntilIdle()

        viewModel.openFile("usage.md")
        assertEquals("usage.md", viewModel.uiState.value.selectedFile)
        assertTrue(viewModel.uiState.value.isLoadingFile)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals("## Usage details", state.fileContent)
        assertFalse(state.isLoadingFile)
        coVerify { api.skillContent("git", "usage.md") }
    }

    @Test
    fun `open file failure shows an inline message`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.skillContent("git") } returns SkillDetailResponse(name = "git", content = "x")
        coEvery { api.skillContent("git", "gone.md") } throws ApiError.Http(404, null)

        val viewModel = SkillDetailViewModel(api, "git")
        advanceUntilIdle()

        viewModel.openFile("gone.md")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("gone.md", state.selectedFile)
        assertTrue(state.fileContent!!.startsWith("Could not load file:"))
        assertFalse(state.isLoadingFile)
    }

    @Test
    fun `close file clears the viewer`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.skillContent("git") } returns SkillDetailResponse(name = "git", content = "x")
        coEvery { api.skillContent("git", "usage.md") } returns
            SkillDetailResponse(name = "git", content = "y")

        val viewModel = SkillDetailViewModel(api, "git")
        advanceUntilIdle()
        viewModel.openFile("usage.md")
        advanceUntilIdle()

        viewModel.closeFile()
        val state = viewModel.uiState.value
        assertNull(state.selectedFile)
        assertNull(state.fileContent)
        assertFalse(state.isLoadingFile)
    }

    @Test
    fun `decodes skill detail fixture with unknown fields`() = runTest(dispatcher) {
        val fixture = """
            {
              "name": "git",
              "content": "# Git\nUse branches.",
              "linked_files": ["b.md", "a.md"],
              "unknown": {"deep": true}
            }
        """.trimIndent()
        val response = json.decodeFromString<SkillDetailResponse>(fixture)
        // The tolerant decoder sorts linked files.
        assertEquals(listOf("a.md", "b.md"), response.linkedFiles)

        val api = mockk<HermesApi>()
        coEvery { api.skillContent("git") } returns response

        val viewModel = SkillDetailViewModel(api, "git")
        advanceUntilIdle()

        assertEquals("# Git\nUse branches.", viewModel.uiState.value.content)
        assertEquals(listOf("a.md", "b.md"), viewModel.uiState.value.linkedFiles)
    }
}
