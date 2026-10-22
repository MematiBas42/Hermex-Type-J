package io.github.jcartu.hermex.ui.feature.workspace

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffScreen
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffMapper
import io.github.jcartu.hermex.ui.feature.git.ReviewDiffState
import io.github.jcartu.hermex.ui.theme.HermexTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
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
