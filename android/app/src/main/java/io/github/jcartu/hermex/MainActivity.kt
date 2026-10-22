package io.github.jcartu.hermex

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.ui.HermexApp
import io.github.jcartu.hermex.ui.theme.HermexTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HermexApplication).container
        ingestIncomingIntent(intent)
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
                HermexApp(container)
            }
        }
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
                container.deepLinks.submit(intent.data)
            }
        }
    }
}
