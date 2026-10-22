package io.github.jcartu.hermex.ui.feature.kanban

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Hermes Kanban composes on phone, fold/tablet and Galaxy Trifold pane counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KanbanComposeSmokeTest {
    @Test
    fun `Hermes board composes at pane counts 1 to 3`() {
        for (paneCount in 1..3) {
            val composed = mutableStateOf(false)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().runOnUiThread {
                val composeView = ComposeView(controller.get())
                composeView.setContent {
                    CompositionLocalProvider(LocalPaneCount provides paneCount) {
                        DisposableEffect(Unit) {
                            composed.value = true
                            onDispose { }
                        }
                        KanbanRoute(dataSource = FakeKanbanDataSource())
                    }
                }
                controller.get().setContentView(composeView)
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
            assertTrue("Hermes Kanban composed at pane count $paneCount", composed.value)
        }
    }
}
