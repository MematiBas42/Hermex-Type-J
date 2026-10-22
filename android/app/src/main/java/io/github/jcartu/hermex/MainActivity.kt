package io.github.jcartu.hermex

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.core.security.AppLockOverlay
import io.github.jcartu.hermex.ui.components.InAppBrowserHost
import io.github.jcartu.hermex.core.security.DeviceAppLockAuthenticator
import io.github.jcartu.hermex.ui.HermexApp
import io.github.jcartu.hermex.ui.theme.HermexTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var lockAuthenticator: DeviceAppLockAuthenticator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HermexApplication).container
        ingestIncomingIntent(intent)

        // Port of `AppLockSceneDelegate` (#885): the lock prompts through this
        // Activity and follows its lifecycle; while the lock is on the window is
        // secure, so the app switcher and screenshots show no chat.
        val appLock = container.appLock
        lockAuthenticator = DeviceAppLockAuthenticator(this).also { appLock.authenticator = it }
        lifecycleScope.launch {
            appLock.state.map { it.isEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.Settings(),
            )
            val darkTheme = when (settings.theme) {
                SettingsRepository.Theme.SYSTEM -> isSystemInDarkTheme()
                SettingsRepository.Theme.LIGHT -> false
                SettingsRepository.Theme.DARK -> true
            }
            HermexTheme(
                darkTheme = darkTheme,
                palette = settings.palette,
                accentHex = settings.headerLogoColorHex,
            ) {
                Box(Modifier.fillMaxSize()) {
                    HermexApp(container)
                    // Web links open here, above every screen and below the lock.
                    InAppBrowserHost()
                    // The lock is a cover, not a gate: the app stays composed
                    // behind it, so streams and reconnects keep running.
                    val lockState by appLock.state.collectAsStateWithLifecycle()
                    if (lockState.showsLockOverlay) {
                        AppLockOverlay(appLock)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as HermexApplication).container.appLock.sceneDidBecomeActive()
    }

    override fun onPause() {
        (application as HermexApplication).container.appLock.sceneWillResignActive()
        super.onPause()
    }

    override fun onStop() {
        (application as HermexApplication).container.appLock.sceneDidEnterBackground()
        super.onStop()
    }

    override fun onDestroy() {
        val appLock = (application as HermexApplication).container.appLock
        if (appLock.authenticator === lockAuthenticator) appLock.authenticator = null
        lockAuthenticator?.dispose()
        lockAuthenticator = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestIncomingIntent(intent)
    }

    private fun ingestIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val container = (application as HermexApplication).container
        lifecycleScope.launch {
            container.sharedImports.ingest(intent)
            if (intent.action == Intent.ACTION_VIEW) {
                // A run-notification tap carries its destination in typed extras
                // (authority) and as a grammar-valid data URI (fallback); both
                // parse through the one deep-link grammar (#1190, #1192).
                container.deepLinks.submit(
                    io.github.jcartu.hermex.core.navigation.NotificationDeepLink.uriFrom(intent),
                )
            }
        }
    }
}
