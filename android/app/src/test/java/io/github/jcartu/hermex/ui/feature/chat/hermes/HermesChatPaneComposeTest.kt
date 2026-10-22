package io.github.jcartu.hermex.ui.feature.chat.hermes

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.chat.ChatComposerActions
import io.github.jcartu.hermex.ui.feature.chat.ChatUiState
import io.github.jcartu.hermex.ui.feature.chat.ReasoningGroup
import io.github.jcartu.hermex.ui.feature.chat.TranscriptMessage
import io.github.jcartu.hermex.ui.theme.HermexTheme
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real ChatTranscript/ChatComposer rendering through the same adaptive pane used by the network screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HermesChatPaneComposeTest {
    @Test fun `mixed saved history and composer remain visible at one two and three panes`() {
        for (panes in 1..3) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
            val activity = controller.get()
            val messages = listOf(
                ChatMessage(role = "user", content = "Inspect this note", timestamp = 100.0, messageId = "stored/row-1", rowID = 1,
                    attachments = listOf(MessageAttachment(name = "notes.txt", path = "/host/notes.txt", isImage = false))),
                ChatMessage(role = "assistant", content = "Reply fixture", timestamp = 101.0, messageId = "stored/row-3", rowID = 3),
            )
            val state = ChatUiState(
                sessionId = "stored", draft = "fixture draft", selectedProfileTitle = "Fixture Profile",
                messages = messages,
                transcriptMessages = messages.mapIndexed { index, row -> TranscriptMessage(index, "row-$index", row.id, row) },
                completedReasoningGroups = listOf(ReasoningGroup("thought", "stored/row-3", "I inspected the note.")),
                completedToolCallGroups = listOf(ToolCallGroup("tools", "stored/row-3", listOf(ToolCall(id = "tool", name = "read_file", preview = "Notes loaded", isCompleted = true, startedAt = 100.0)))),
                thinkingCardsStartExpanded = true, toolCardsStartExpanded = true, hidesAttachmentPaths = true,
            )
            activity.setContent {
                HermexTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalPaneCount provides panes) {
                        Box(Modifier.requiredSize((if (panes == 1) 430 else if (panes == 2) 780 else 1120).dp, 1000.dp)) {
                            HermesChatPane(state, mockk<ChatComposerActions>(relaxed = true))
                        }
                    }
                }
            }
            controller.start().resume().visible()
            shadowOf(Looper.getMainLooper()).idle()
            val view = findComposeView(activity.window.decorView)
            assertTrue("pane $panes did not compose", view != null)
            val owner = view!!.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
            val text = mutableListOf<String>()
            fun visit(node: SemanticsNode) {
                for (entry in node.config) {
                    if (entry.key == SemanticsProperties.Text) {
                        @Suppress("UNCHECKED_CAST")
                        (entry.value as? List<AnnotatedString>)?.forEach { text += it.text }
                    } else if (entry.key == SemanticsProperties.EditableText) (entry.value as? AnnotatedString)?.let { text += it.text }
                }
                node.children.forEach(::visit)
            }
            visit(owner.rootSemanticsNode)
            val dump = text.joinToString("\n")
            assertTrue("pane $panes lost composer: $dump", "fixture draft" in dump)
            assertTrue("pane $panes lost transcript: $dump", "Reply fixture" in dump)
            assertTrue("pane $panes lost profile chip: $dump", "Fixture Profile" in dump)
            assertFalse(activity.isFinishing)
            controller.pause().stop().destroy()
        }
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findComposeView(view.getChildAt(index))?.let { return it }
        return null
    }
}
