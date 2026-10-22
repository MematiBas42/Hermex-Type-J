package io.github.jcartu.hermex.ui.feature.workspace.hermes

import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.hermes.HermesWorkspaceFile
import io.github.jcartu.hermex.net.hermes.HermesFolderCompletion
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceContext
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceFilePreview
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
class HermesWorkspaceViewModelTest {
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
    fun `lazy tree and source preview stay workspace relative`() = runTest(dispatcher) {
        val source = FakeSource()
        val viewModel = HermesWorkspaceViewModel(source)

        viewModel.load()
        advanceUntilIdle()
        assertEquals(listOf("src"), viewModel.uiState.value.workspace.tree.rootNodes.map { it.path })

        viewModel.toggleDirectory("src")
        advanceUntilIdle()
        val entry = viewModel.uiState.value.workspace.tree.childrenOf("src")!!.single().entry
        viewModel.openEntry(entry)
        advanceUntilIdle()

        val preview = viewModel.uiState.value.workspace.preview as WorkspaceFilePreview.Text
        assertEquals("src/Main.kt", preview.file.path)
        assertEquals("fun main() = Unit", preview.file.content)
        assertFalse(preview.isMarkdown)
    }

    @Test
    fun `completion keeps host needs-more-typing and move replaces the file scope`() = runTest(dispatcher) {
        val source = FakeSource()
        val viewModel = HermesWorkspaceViewModel(source)
        viewModel.load()
        advanceUntilIdle()

        viewModel.setFolderQuery("/very/broad")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.needsMoreTyping)

        viewModel.moveFolder("/next")
        advanceUntilIdle()
        assertEquals("/next", viewModel.uiState.value.context.cwd)
        assertEquals("/next", viewModel.uiState.value.folderQuery)
        assertTrue(viewModel.uiState.value.workspace.tree.isRootLoaded)
    }

    private class FakeSource : HermesWorkspaceDataSource {
        override var context = HermesWorkspaceContext(
            server = "http://hermes",
            profile = "default",
            storedKey = "session-1",
            cwd = "/work",
            terminalBackend = "local",
        )

        override suspend fun directoryList(path: String): List<WorkspaceEntry> = when (path) {
            "." -> listOf(WorkspaceEntry(name = "src", path = "src", type = "dir", isDirectory = true))
            "src" -> listOf(WorkspaceEntry(name = "Main.kt", path = "src/Main.kt", type = "file"))
            else -> emptyList()
        }

        override suspend fun file(path: String): HermesWorkspaceFile = HermesWorkspaceFile(
            content = "fun main() = Unit",
            path = path,
            name = path.substringAfterLast('/'),
            language = "kotlin",
            size = 17,
            lines = 1,
        )

        override suspend fun previewData(path: String): ByteArray = byteArrayOf(1, 2, 3)

        override suspend fun folderChoices(): List<String> = listOf("/work", "/next")

        override suspend fun completeFolder(word: String): HermesFolderCompletion.Suggestions =
            HermesFolderCompletion.Suggestions(needsMoreTyping = true)

        override suspend fun moveFolder(folder: String): HermesWorkspaceContext {
            context = context.copy(cwd = folder)
            return context
        }
    }
}
