package io.github.jcartu.hermex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.ui.HermexApp
import io.github.jcartu.hermex.ui.theme.HermexTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HermexApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.Settings(),
            )
            val darkTheme = when (settings.theme) {
                SettingsRepository.Theme.SYSTEM -> isSystemInDarkTheme()
                SettingsRepository.Theme.LIGHT -> false
                SettingsRepository.Theme.DARK -> true
            }
            HermexTheme(darkTheme = darkTheme, accentHex = settings.headerLogoColorHex) {
                HermexApp(container)
            }
        }
    }
}
