package io.github.jcartu.hermex.ui.feature.workspace

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.model.GitTotals
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceContext
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceScreen
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceUiState
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffScreen
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffMapper
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffState
import io.github.jcartu.hermex.ui.feature.workspace.hermes.HermesWorkspaceScreen
import io.github.jcartu.hermex.ui.feature.workspace.hermes.HermesWorkspaceUiState
import io.github.jcartu.hermex.ui.theme.HermexTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceComposeRenderTest {
    @Test
    fun `tree renders at one two and three panes`() {
        val tree = LazyFileTree()
            .reduce(FileTreeAction.LoadStarted(".", 1))
            .reduce(FileTreeAction.Loaded(".", 1, listOf(WorkspaceEntry("src", "src", type = "dir"))))
        val state = WorkspaceUiState(
            hasLoadedRoots = true,
            roots = listOf(WorkspaceRootItem("/work", "Work", "session", 1)),
            selectedRootPath = "/work",
            browseSessionId = "session",
            tree = tree,
        )
        assertEveryPaneRenders { WorkspaceTreeRenderSurface(state) }
    }

    @Test
    fun `source viewer renders at one two and three panes`() {
        assertEveryPaneRenders { _ ->
            SourceViewerScreen(
                content = "fun main() {\n    println(\"hello\")\n}",
                path = "src/Main.kt",
            )
        }
    }

    @Test
    fun `review diff renders at one two and three panes`() {
        val file = GitFile(id = "Main.kt", path = "Main.kt", status = "M", additions = 1, deletions = 1)
        val review = ReviewDiffMapper.map(
            file,
            ReviewDiffState.Loaded(GitDiff(diff = "@@ -1,1 +1,1 @@\n-old\n+new")),
        )
        assertEveryPaneRenders {
            ReviewDiffScreen(files = listOf(review), initialPath = "Main.kt", showFileIcons = false)
        }
    }

    @Test
    fun `Hermes Files tree and preview render at one two and three panes`() {
        val entry = WorkspaceEntry(name = "Main.kt", path = "src/Main.kt", type = "file")
        val tree = LazyFileTree()
            .reduce(FileTreeAction.LoadStarted(".", 1))
            .reduce(FileTreeAction.Loaded(".", 1, listOf(WorkspaceEntry("src", "src", type = "dir"))))
            .reduce(FileTreeAction.ToggleDirectory("src"))
            .reduce(FileTreeAction.LoadStarted("src", 2))
            .reduce(FileTreeAction.Loaded("src", 2, listOf(entry)))
        val context = HermesWorkspaceContext("http://hermes", "default", "s1", "/work", "local")
        val workspace = WorkspaceUiState(
            hasLoadedRoots = true,
            roots = listOf(WorkspaceRootItem("hermes", "Working folder", "s1", 1)),
            selectedRootPath = "hermes",
            browseSessionId = "s1",
            selectedEntry = entry,
            preview = WorkspaceFilePreview.Text(
                file = FileResponse(content = "fun main() = Unit", path = "src/Main.kt", name = "Main.kt"),
                isMarkdown = false,
            ),
            tree = tree,
        )
        val state = HermesWorkspaceUiState(
            workspace = workspace,
            context = context,
            folderChoices = listOf("/work", "/work/next"),
        )
        assertEveryPaneRenders { pane ->
            HermesWorkspaceScreen(state = state, paneCount = pane, showFileIcons = false)
        }
    }

    @Test
    fun `Hermes Git review renders at one two and three panes`() {
        val file = GitFile(id = "Main.kt", path = "Main.kt", status = "M", additions = 1, deletions = 1)
        val review = ReviewDiffMapper.map(
            file,
            ReviewDiffState.Loaded(GitDiff(diff = "@@ -1,1 +1,1 @@\n-old\n+new")),
        )
        val state = GitWorkspaceUiState(
            sessions = listOf(SessionSummary(sessionId = "s1", title = "default", workspace = "/work")),
            selectedSessionId = "s1",
            info = io.github.jcartu.hermex.model.GitInfo(branch = "main", isGit = true),
            status = GitStatus(
                isGit = true,
                branch = "main",
                totals = GitTotals(changed = 1),
                files = listOf(file),
            ),
            selectedFilePath = "Main.kt",
            reviewFiles = listOf(review),
            isHermes = true,
            supportsSync = false,
        )
        assertEveryPaneRenders { pane ->
            GitWorkspaceScreen(
                state = state,
                paneCount = pane,
                showsCommitControls = false,
                showFileIcons = false,
            )
        }
    }

    private fun assertEveryPaneRenders(content: @androidx.compose.runtime.Composable (Int) -> Unit) {
        for (pane in 1..3) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setContent {
                HermexTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalPaneCount provides pane) { content(pane) }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            val decor = activity.window.decorView as ViewGroup
            assertTrue("pane $pane should attach Compose content", decor.childCount > 0)
            controller.destroy()
        }
    }
}
