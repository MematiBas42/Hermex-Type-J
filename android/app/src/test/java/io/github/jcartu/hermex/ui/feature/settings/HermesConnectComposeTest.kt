package io.github.jcartu.hermex.ui.feature.settings

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.net.hermes.HermesSignInMethod
import io.github.jcartu.hermex.net.hermes.HermesStatus
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesConnectionFormContent
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesConnectionFormState
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesHostStatus
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingConnectPage
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingUiState
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingWelcomePage
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingAccent
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingError
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingSuccess
import io.github.jcartu.hermex.ui.theme.HermexTheme
import io.github.jcartu.hermex.ui.theme.Palette
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HermesConnectComposeTest {
    private fun render(panes: Int, palette: Palette = Palette.TOKYO_NIGHT, dark: Boolean = true, content: @Composable () -> Unit): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContent {
                HermexTheme(darkTheme = dark, palette = palette) {
                    CompositionLocalProvider(LocalPaneCount provides panes) {
                        Box(Modifier.requiredSize((400 * panes).dp, 1100.dp)) { content() }
                    }
                }
            }
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            return activity.dump()
        } finally { controller.pause().stop().destroy() }
    }
    private fun Activity.dump(): List<String> {
        fun find(view: View): View? = when {
            view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        val view = find(window.decorView) ?: error("Compose did not mount")
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
        val text = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.forEach { entry ->
                if (entry.key == SemanticsProperties.Text) {
                    @Suppress("UNCHECKED_CAST")
                    (entry.value as? List<AnnotatedString>)?.forEach { text += it.text }
                }
            }
            node.children.forEach(::walk)
        }
        walk(owner.rootSemanticsNode)
        return text
    }

    @Test fun `connect form renders all three methods at every pane count`() {
        for (panes in 1..3) for (method in HermesSignInMethod.entries) {
            val text = render(panes) {
                OnboardingConnectPage(OnboardingUiState(serverUrl = "https://hermes.example", detectedKind = ServerKind.HERMES, signInMethod = method),
                    {}, {}, {}, {}, {})
            }
            assertTrue(text.any { it == "Connect" })
            assertEquals(method == HermesSignInMethod.PASSWORD, "Username" in text)
            assertEquals(method == HermesSignInMethod.PASSWORD, "Password" in text)
            if (method == HermesSignInMethod.OPEN) assertTrue(HermesSignInMethod.OPEN_WARNING in text)
            if (method == HermesSignInMethod.BROWSER) assertTrue(text.any { "browser" in it })
        }
    }

    @Test fun `own Hermes connection renders method status and no remove at every pane count`() {
        for (panes in 1..3) for (method in HermesSignInMethod.entries) {
            val text = render(panes) {
                HermesConnectionFormContent(HermesConnectionFormState(true, "https://hermes.example", hostStatus = HermesHostStatus.Reachable(
                    HermesStatus(authRequired = method != HermesSignInMethod.OPEN, signInMethod = method, version = "0.22.2", installID = "install", gatewayState = "running"))),
                    false, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
            assertFalse(text.any { "Remove Hermes connection" in it })
            assertTrue(text.any { it == if (method == HermesSignInMethod.BROWSER) "Sign In with Browser" else "Connect" })
            assertTrue("0.22.2" in text)
        }
    }

    @Test fun `server settings page renders Hermes status companion and update at every pane count`() {
        val account = ServerAccount("https://hermes.example", "https://hermes.example", kind = ServerKind.HERMES)
        val saved = BotConnection(name = "Host", address = account.id.toHttpUrl(), username = "", password = "")
        for (panes in 1..3) {
            val text = render(panes) {
                Column {
                    ServerIdentityEditor("Host", "H", "#7AA2F7", "Host", {}, {}, {})
                    HermesServerSettingsContent(account, HermesServerSettingsState(saved, true, true, companionInstalled = false),
                        HermesUpdateUiState(), {}, {}, {}, {}, {})
                }
            }
            assertTrue("Hermes connection" in text)
            assertTrue("hermex-push companion" in text)
            assertTrue("not installed" in text)
            assertTrue(HermesSignInMethod.OPEN_WARNING in text)
            assertTrue("UPDATE HERMES" in text)
        }
    }

    @Test fun `onboarding semantic colors follow both palettes in light and dark`() {
        for (palette in Palette.entries) for (dark in listOf(false, true)) {
            val text = render(1, palette, dark) {
                assertEquals(MaterialTheme.colorScheme.primary, OnboardingAccent)
                assertEquals(MaterialTheme.colorScheme.tertiary, OnboardingSuccess)
                assertEquals(MaterialTheme.colorScheme.error, OnboardingError)
                OnboardingWelcomePage()
            }
            assertTrue(text.any { "Control your Hermes agent" in it })
        }
    }
}
