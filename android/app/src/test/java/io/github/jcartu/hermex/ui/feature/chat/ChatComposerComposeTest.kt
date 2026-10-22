package io.github.jcartu.hermex.ui.feature.chat

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jcartu.hermex.model.PendingAttachment
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ChatComposerComposeTest {

    @Test
    fun `composer composes chips and attachments at every pane count`() {
        for (paneCount in 1..3) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
            val activity = controller.get()
            activity.setContent {
                HermexTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalPaneCount provides paneCount) {
                        ChatComposer(
                            state = ChatUiState(
                                sessionId = "session-$paneCount",
                                draft = "/code-review check @src/Main.kt ",
                                pendingAttachments = listOf(
                                    PendingAttachment(
                                        name = "notes.txt",
                                        path = "/uploads/notes.txt",
                                        mime = "text/plain",
                                        size = 128,
                                        isImage = false,
                                    ),
                                ),
                            ),
                            actions = noOpComposerActions(),
                            draftTokens = listOf(
                                ComposerToken.SkillChip("Code Review"),
                                ComposerToken.Text(" check "),
                                ComposerToken.PathChip("src/Main.kt"),
                                ComposerToken.Text(" "),
                            ),
                        )
                    }
                }
            }
            controller.start().resume().visible()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("pane $paneCount activity finished", activity.isFinishing)
            controller.pause().stop().destroy()
        }
    }

    private fun noOpComposerActions(): ChatComposerActions = Proxy.newProxyInstance(
        ChatComposerActions::class.java.classLoader,
        arrayOf(ChatComposerActions::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    } as ChatComposerActions
}
