package io.github.jcartu.hermex.ui.feature.hermes.home

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.ui.adaptive.FirstPaneSplitRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.sessions.SessionRow
import io.github.jcartu.hermex.ui.feature.sessions.SessionsHeader
import io.github.jcartu.hermex.ui.theme.HermexTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Same no-ui-test-artifact semantics harness as SessionsComposeTest; deterministic data, real home layout/bar/rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HermesHomeComposeTest {
    @Test fun `both home sides retain header and floating switch at every pane count`() {
        for (paneCount in 1..3) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val tab = mutableStateOf(HermesHomeTab.SESSIONS)
            controller.get().setContent {
                HermexTheme {
                    CompositionLocalProvider(LocalPaneCount provides paneCount) {
                        Box(Modifier.requiredSize((360 * paneCount).dp, 720.dp)) {
                            val list: @Composable (Modifier) -> Unit = { modifier -> FixtureList(tab.value, modifier) { tab.value = it } }
                            HermesHomeSurface(tab.value, hasDetail = false, onBack = {},
                                bots = { if (paneCount == 1) list(Modifier.fillMaxSize()) else FirstPaneSplitRow(firstPane = { _, modifier -> list(modifier) }, remaining = { Text("Bot detail", it) }) },
                                sessions = list, detail = { Text("Select a Chat", it) })
                        }
                    }
                }
            }
            try {
                for (side in HermesHomeTab.entries) {
                    tab.value = side
                    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                    val nodes = controller.get().window.decorView.semanticsNodes()
                    val text = nodes.flatMap { node -> node.config.flatMap { entry -> when (entry.key) {
                        SemanticsProperties.Text -> (entry.value as? List<*>)?.filterIsInstance<AnnotatedString>()?.map { it.text }.orEmpty()
                        SemanticsProperties.ContentDescription -> (entry.value as? List<*>)?.filterIsInstance<String>().orEmpty()
                        else -> emptyList()
                    } } }
                    assertTrue("$side/$paneCount header: $text", "HERMEX" in text)
                    assertTrue("$side/$paneCount Bots switch: $text", "Bots" in text)
                    assertTrue("$side/$paneCount Sessions switch: $text", "Sessions" in text)
                    assertTrue("$side/$paneCount side data: $text", text.any { it.contains(if (side == HermesHomeTab.SESSIONS) "Saved session" else "Research bot") })
                    val bar = nodes.first { node -> node.config.any { it.key == SemanticsProperties.ContentDescription && (it.value as? List<*>)?.contains("Hermes home switch") == true } }
                    assertTrue("bar floats below rows", bar.boundsInRoot.top > 100f)
                    assertTrue("bar is within first pane", bar.boundsInRoot.width <= 370f * controller.get().resources.displayMetrics.density)
                }
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Composable private fun FixtureList(tab: HermesHomeTab, modifier: Modifier, select: (HermesHomeTab) -> Unit) {
        Box(modifier) {
            Column {
                SessionsHeader(Color.Cyan, "JC", Color.Cyan, Color.Black, false, "", {}, {}, {}, {}, {})
                SessionRow(SessionSummary(sessionId = "one", title = if (tab == HermesHomeTab.SESSIONS) "Saved session" else "Research bot", messageCount = 4),
                    showsMessageCount = true, showsWorkspace = true, isViewingCachedData = false)
            }
            HermesHomeBar(tab, select, filter = { TextButton(onClick = {}) { Text("Filter") } },
                newChat = { TextButton(onClick = {}) { Text("New") } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
    private fun View.semanticsNodes(): List<SemanticsNode> {
        fun find(view: View): View? = when {
            view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" -> view
            view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        val view = find(this) ?: return emptyList()
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
        val out = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { out += node; node.children.forEach(::visit) }
        visit(owner.rootSemanticsNode)
        return out
    }
}
