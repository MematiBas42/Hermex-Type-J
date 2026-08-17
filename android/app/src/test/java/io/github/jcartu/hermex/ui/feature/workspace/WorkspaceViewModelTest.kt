package io.github.jcartu.hermex.ui.feature.workspace

import io.github.jcartu.hermex.model.DirectoryListResponse
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.WorkspaceRoot
import io.github.jcartu.hermex.model.WorkspacesResponse
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    private val api = mockk<HermesApi>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubRoots(
        roots: List<WorkspaceRoot> = listOf(
            WorkspaceRoot(path = "/srv/app", name = "App"),
            WorkspaceRoot(path = "/srv/empty", name = null),
        ),
        last: String? = "/srv/app/",
        sessions: List<SessionSummary> = listOf(
            SessionSummary(sessionId = "old", workspace = "/srv/app", updatedAt = 100.0),
            SessionSummary(sessionId = "new", workspace = "/srv/app/", lastMessageAt = 900.0),
            SessionSummary(sessionId = "noWorkspace", workspace = null, updatedAt = 999.0),
        ),
    ) {
        coEvery { api.workspaces() } returns WorkspacesResponse(workspaces = roots, last = last)
        coEvery { api.sessions(any(), any()) } returns SessionsResponse(sessions = sessions)
    }

    @Test
    fun `loadRoots pairs each root with its most recent session`() {
        stubRoots()
        val viewModel = WorkspaceViewModel(api)

        viewModel.loadRootsIfNeeded()

        val state = viewModel.uiState.value
        assertTrue(state.hasLoadedRoots)
        assertEquals(2, state.roots.size)

        val app = state.roots.first { it.path == "/srv/app" }
        assertEquals("new", app.sessionId) // newest by lastMessageAt/updatedAt/createdAt
        assertEquals(2, app.sessionCount) // trailing-slash workspace matches too
        assertTrue(app.isBrowsable)
        assertEquals("App", app.displayName)

        val empty = state.roots.first { it.path == "/srv/empty" }
        assertNull(empty.sessionId)
        assertFalse(empty.isBrowsable)
        assertEquals("empty", empty.displayName)

        assertEquals("/srv/app", state.lastUsedPath) // trailing slash normalized
    }

    @Test
    fun `loadRootsIfNeeded only fetches once`() {
        stubRoots()
        val viewModel = WorkspaceViewModel(api)

        viewModel.loadRootsIfNeeded()
        viewModel.loadRootsIfNeeded()

        coVerify(exactly = 1) { api.workspaces() }
    }

    @Test
    fun `roots failure surfaces error message`() {
        coEvery { api.workspaces() } throws ApiError.Http(500, null)
        coEvery { api.sessions(any(), any()) } returns SessionsResponse()
        val viewModel = WorkspaceViewModel(api)

        viewModel.loadRoots()

        val state = viewModel.uiState.value
        assertFalse(state.hasLoadedRoots)
        assertEquals(ApiError.Http(500, null).message, state.rootsError)
    }

    @Test
    fun `selectRoot lists the workspace root through the paired session`() {
        stubRoots()
        coEvery { api.directoryList("new", ".") } returns DirectoryListResponse(
            entries = listOf(
                WorkspaceEntry(name = "src", path = "src", type = "dir"),
                WorkspaceEntry(name = "README.md", path = "README.md", type = "file", size = 512),
            ),
            path = ".",
        )
        val viewModel = WorkspaceViewModel(api)
        viewModel.loadRootsIfNeeded()

        viewModel.selectRoot(viewModel.uiState.value.roots.first { it.path == "/srv/app" })

        val state = viewModel.uiState.value
        assertEquals("/srv/app", state.selectedRootPath)
        assertEquals("new", state.browseSessionId)
        assertEquals(".", state.currentPath)
        assertEquals(listOf("src", "README.md"), state.entries.map { it.name })
        assertTrue(state.isAtRoot)
        assertEquals("Root", state.displayPath)
    }

    @Test
    fun `selectRoot ignores roots without a session`() {
        stubRoots()
        val viewModel = WorkspaceViewModel(api)
        viewModel.loadRootsIfNeeded()

        viewModel.selectRoot(viewModel.uiState.value.roots.first { it.path == "/srv/empty" })

        assertNull(viewModel.uiState.value.selectedRootPath)
        coVerify(exactly = 0) { api.directoryList(any(), any()) }
    }

    private fun browseInto(viewModel: WorkspaceViewModel) {
        stubRoots()
        coEvery { api.directoryList("new", ".") } returns DirectoryListResponse(
            entries = listOf(WorkspaceEntry(name = "src", path = "src", type = "dir")),
            path = ".",
        )
        viewModel.loadRootsIfNeeded()
        viewModel.selectRoot(viewModel.uiState.value.roots.first { it.path == "/srv/app" })
    }

    @Test
    fun `opening a directory descends and updates breadcrumbs`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        coEvery { api.directoryList("new", "src/main") } returns DirectoryListResponse(
            entries = emptyList(),
            path = "src/main",
        )

        viewModel.openEntry(WorkspaceEntry(name = "main", path = "src/main", type = "dir"))

        val state = viewModel.uiState.value
        assertEquals("src/main", state.currentPath)
        assertEquals(
            listOf("." to "Root", "src" to "src", "src/main" to "main"),
            state.breadcrumbs.map { it.path to it.title },
        )
        assertEquals("src", state.parentPath)
    }

    @Test
    fun `goUp loads the parent directory`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        coEvery { api.directoryList("new", "src/main") } returns
            DirectoryListResponse(entries = emptyList(), path = "src/main")
        coEvery { api.directoryList("new", "src") } returns
            DirectoryListResponse(entries = emptyList(), path = "src")
        viewModel.openEntry(WorkspaceEntry(name = "main", path = "src/main", type = "dir"))

        viewModel.goUp()

        assertEquals("src", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `listing failure keeps path and surfaces retryable error`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        coEvery { api.directoryList("new", "broken") } throws ApiError.Http(500, null)

        viewModel.load("broken")
        assertEquals(ApiError.Http(500, null).message, viewModel.uiState.value.entriesError)
        assertEquals(".", viewModel.uiState.value.currentPath)

        coEvery { api.directoryList("new", "broken") } returns
            DirectoryListResponse(entries = emptyList(), path = "broken")
        viewModel.retryLastLoad()

        assertNull(viewModel.uiState.value.entriesError)
        assertEquals("broken", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `opening a text file loads it through the file endpoint`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        val file = FileResponse(content = "fun main() {}", path = "src/Main.kt", size = 13, lines = 1)
        coEvery { api.file("new", "src/Main.kt") } returns file

        viewModel.openEntry(WorkspaceEntry(name = "Main.kt", path = "src/Main.kt", type = "file"))

        val preview = viewModel.uiState.value.preview as WorkspaceFilePreview.Text
        assertEquals(file, preview.file)
        assertFalse(preview.isMarkdown)
        assertFalse(viewModel.uiState.value.isLoadingPreview)
    }

    @Test
    fun `markdown files are flagged for rich rendering`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        coEvery { api.file("new", "README.md") } returns FileResponse(content = "# Hi")

        viewModel.openEntry(WorkspaceEntry(name = "README.md", path = "README.md", type = "file"))

        val preview = viewModel.uiState.value.preview as WorkspaceFilePreview.Text
        assertTrue(preview.isMarkdown)
    }

    @Test
    fun `raster images preview via the raw file url`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        coEvery { api.rawFileUrl("new", "logo.png") } returns
            "https://server.test/api/raw?session_id=new&path=logo.png".toHttpUrl()

        viewModel.openEntry(WorkspaceEntry(name = "logo.png", path = "logo.png", type = "file"))

        val preview = viewModel.uiState.value.preview as WorkspaceFilePreview.Image
        assertEquals("https://server.test/api/raw?session_id=new&path=logo.png", preview.url)
        coVerify(exactly = 0) { api.file(any(), any()) }
    }

    @Test
    fun `known binaries short-circuit to an unavailable notice`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)

        viewModel.openEntry(WorkspaceEntry(name = "dump.zip", path = "dump.zip", type = "file"))

        assertTrue(viewModel.uiState.value.preview is WorkspaceFilePreview.Unavailable)
        coVerify(exactly = 0) { api.file(any(), any()) }
        coVerify(exactly = 0) { api.rawFileUrl(any(), any()) }
    }

    @Test
    fun `goBack walks preview then directories then roots`() {
        val viewModel = WorkspaceViewModel(api)
        browseInto(viewModel)
        coEvery { api.directoryList("new", "src") } returns
            DirectoryListResponse(entries = emptyList(), path = "src")
        coEvery { api.file("new", "src/Main.kt") } returns FileResponse(content = "x")
        viewModel.openEntry(WorkspaceEntry(name = "src", path = "src", type = "dir"))
        viewModel.openEntry(WorkspaceEntry(name = "Main.kt", path = "src/Main.kt", type = "file"))

        viewModel.goBack() // closes preview
        assertNull(viewModel.uiState.value.selectedEntry)
        assertEquals("src", viewModel.uiState.value.currentPath)

        viewModel.goBack() // up to root listing
        assertEquals(".", viewModel.uiState.value.currentPath)

        viewModel.goBack() // back to workspace roots
        assertNull(viewModel.uiState.value.selectedRootPath)
        assertFalse(viewModel.uiState.value.canGoBack)
    }

    // MARK: - Pure helpers

    @Test
    fun `breadcrumbs mirror the swift implementation`() {
        assertEquals(listOf(FileBreadcrumb("Root", ".")), breadcrumbsFor("."))
        assertEquals(
            listOf(
                FileBreadcrumb("Root", "."),
                FileBreadcrumb("a", "a"),
                FileBreadcrumb("b", "a/b"),
                FileBreadcrumb("c", "a/b/c"),
            ),
            breadcrumbsFor("a/b/c"),
        )
    }

    @Test
    fun `parent path mirrors the swift implementation`() {
        assertNull(parentPathFor("."))
        assertEquals(".", parentPathFor("src"))
        assertEquals("a/b", parentPathFor("a/b/c"))
    }

    @Test
    fun `path extension ignores dotfiles and directories`() {
        assertEquals("kt", pathExtension("src/Main.kt"))
        assertEquals("gz", pathExtension("logs/archive.tar.gz"))
        assertEquals("", pathExtension("src/.env"))
        assertEquals("", pathExtension("Makefile"))
        assertEquals("", pathExtension("weird."))
    }

    @Test
    fun `preview kinds classify like the ios preview`() {
        assertEquals(FilePreviewKind.IMAGE, previewKindFor("a/logo.WEBP"))
        assertEquals(FilePreviewKind.BINARY, previewKindFor("report.pdf"))
        assertEquals(FilePreviewKind.MARKDOWN, previewKindFor("README.md"))
        assertEquals(FilePreviewKind.TEXT, previewKindFor("main.rs"))
        assertEquals(FilePreviewKind.TEXT, previewKindFor(".gitignore"))
    }

    @Test
    fun `entry filter matches name path and type case-insensitively`() {
        val entries = listOf(
            WorkspaceEntry(name = "Main.kt", path = "src/Main.kt", type = "file"),
            WorkspaceEntry(name = "docs", path = "docs", type = "dir"),
        )
        assertEquals(entries, filterEntries(entries, "  "))
        assertEquals(listOf(entries[0]), filterEntries(entries, "main"))
        assertEquals(listOf(entries[1]), filterEntries(entries, "DIR"))
        assertTrue(filterEntries(entries, "nope").isEmpty())
    }

    @Test
    fun `byte counts format decimally like ByteCountFormatter`() {
        assertEquals("0 bytes", formatByteCount(0))
        assertEquals("1 byte", formatByteCount(1))
        assertEquals("999 bytes", formatByteCount(999))
        assertEquals("1 KB", formatByteCount(1_000))
        assertEquals("1.5 KB", formatByteCount(1_500))
        assertEquals("2 MB", formatByteCount(2_000_000))
        assertEquals("1.2 GB", formatByteCount(1_230_000_000))
    }

    @Test
    fun `line number gutter right-aligns within the widest number`() {
        assertEquals("1", lineNumberGutter(1))
        assertEquals(" 1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n10", lineNumberGutter(10))
    }
}
