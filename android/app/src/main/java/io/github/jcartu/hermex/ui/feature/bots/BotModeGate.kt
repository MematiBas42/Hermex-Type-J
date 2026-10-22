package io.github.jcartu.hermex.ui.feature.bots

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port of `BotModeGate` (`Config/AppTheme.swift`): app-wide opt-in for the Bots row on a
 * webui server. Default off; not per-server, because this hides screens, not user data.
 * Connections/drafts stay when disabled. Hermes servers and connection setup ignore the gate.
 */
class BotModeGate(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(BotLocalDefaults.Preferences.FILE, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(IS_ENABLED_KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(IS_ENABLED_KEY, enabled) }
        _enabled.value = enabled
    }

    companion object {
        const val IS_ENABLED_KEY = "botMode.isEnabled"
    }
}

/** Port of `WebuiServerSettings`: the opt-in row only legacy webui servers need. */
@Composable
fun BotModeSetting(gate: BotModeGate, modifier: Modifier = Modifier) {
    val enabled by gate.enabled.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Bot Mode (beta)", style = MaterialTheme.typography.bodyLarge)
            Text("Show Bots on Web UI servers. Hermes servers always include Bots.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = gate::setEnabled)
    }
}
