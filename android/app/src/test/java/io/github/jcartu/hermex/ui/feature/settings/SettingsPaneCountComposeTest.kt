package io.github.jcartu.hermex.ui.feature.settings

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormStyle
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectionMode
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectionModePicker
import io.github.jcartu.hermex.ui.feature.onboarding.CustomHeadersEditor
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingViewModel
import io.github.jcartu.hermex.ui.theme.HermexTheme
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Composes the Settings index, a server page (webui and Hermes), the app pages
 * and the connect form at pane counts 1, 2 and 3 (`LocalPaneCount`), the fork's
 * adaptive contract: a layout that throws at any count fails here. The build
 * excludes Android resources from unit tests, so Material3 surfaces that read
 * string resources (dialogs) stay out of these compositions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HermexApplication::class)
class SettingsPaneCountComposeTest {

    private fun compose(paneCount: Int, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            HermexTheme(darkTheme = true) {
                CompositionLocalProvider(LocalPaneCount provides paneCount) { content() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity.window.decorView)
        activity.finish()
    }

    @Test
    fun `settings index and pages compose at every pane count`() {
        for (paneCount in 1..3) {
            compose(paneCount) {
                val container = (LocalContext.current.applicationContext as HermexApplication).container
                val viewModel = SettingsViewModel(container.settings, container.authManager, container.appLock)
                val webui = ServerAccount(id = "https://webui.example.com", urlString = "https://webui.example.com", displayName = "Web")
                val hermes = ServerAccount(
                    id = "https://hermes.example.com:9119",
                    urlString = "https://hermes.example.com:9119",
                    kind = ServerKind.HERMES,
                    serverVersion = "0.22.2",
                )
                val base = SettingsUiState(servers = listOf(webui, hermes), activeServerId = webui.id)
                SettingsScreen(container, viewModel, base, onOpenArchived = {})
                SettingsScreen(container, viewModel, base.copy(page = SettingsPage.Server(webui.id)), onOpenArchived = {})
                SettingsScreen(container, viewModel, base.copy(page = SettingsPage.Server(hermes.id)), onOpenArchived = {})
                SettingsScreen(container, viewModel, base.copy(page = SettingsPage.Chat), onOpenArchived = {})
                SettingsScreen(container, viewModel, base.copy(page = SettingsPage.Appearance), onOpenArchived = {})
                SettingsScreen(container, viewModel, base.copy(page = SettingsPage.General), onOpenArchived = {})
                SettingsScreen(container, viewModel, base.copy(page = SettingsPage.About), onOpenArchived = {})
            }
        }
    }

    @Test
    fun `connect form components compose at every pane count`() {
        for (paneCount in 1..3) {
            compose(paneCount) {
                val container = (LocalContext.current.applicationContext as HermexApplication).container
                val form = OnboardingViewModel(container.authManager)
                form.setConnectionMode(ConnectionMode.CLOUDFLARE_TUNNEL)
                ConnectionModePicker(form.uiState.value.connectionMode, form::setConnectionMode, ConnectFormStyle.ONBOARDING)
                CustomHeadersEditor(form.uiState.value.customHeaders, form::onCustomHeadersChange, ConnectFormStyle.SETTINGS)
                ServerIdentityEditor("", "", HeaderLogoColor.defaultHex, "host", {}, {}, {})
            }
        }
    }
}
