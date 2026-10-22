package io.github.jcartu.hermex.ui.feature.bots

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.AppNavigationViewModel
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState

/**
 * Port of the webui `BotModeGate` utility destination (#496): when enabled, the existing list
 * gets one Bots row. Hermes homes do not use this wrapper. External session/share/shortcut
 * navigation pops the inbox; turning the gate off never deletes its connection or drafts.
 */
@Composable
fun WebuiBotsHost(
    container: AppContainer,
    navigation: AppNavigationViewModel,
    content: @Composable (botsEntry: (@Composable () -> Unit)?) -> Unit,
) {
    val auth by container.authManager.state.collectAsStateWithLifecycle()
    val account = (auth as? AuthState.LoggedIn)?.account
    val enabled by container.botModeGate.enabled.collectAsStateWithLifecycle()
    val destination by BotLinkMailbox.pending.collectAsStateWithLifecycle()
    val incoming by container.deepLinks.pending.collectAsStateWithLifecycle()
    val share by container.sharedImports.pending.collectAsStateWithLifecycle()
    var showingBots by rememberSaveable(account?.id) { mutableStateOf(false) }
    LaunchedEffect(enabled, destination, account?.id) {
        if (!enabled) {
            showingBots = false
            if (destination?.server == account?.id) BotLinkMailbox.consume()
        }
    }
    LaunchedEffect(navigation.selectedSessionId, navigation.isNewChat, incoming?.first, share?.id) {
        if (incoming != null || share != null || navigation.selectedSessionId != null || navigation.isNewChat) showingBots = false
    }
    LaunchedEffect(destination) {
        if (enabled && destination?.server == account?.id) showingBots = true
    }
    if (showingBots && enabled && account?.kind == ServerKind.WEBUI) {
        BackHandler { showingBots = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showingBots = false }) { Text("Back to Sessions") }
            BotsInbox(account, modifier = Modifier.weight(1f),
                pendingDestination = destination?.takeIf { it.server == account.id },
                onDestinationHandled = BotLinkMailbox::consume, bottomContentPadding = 24.dp)
        }
    } else {
        val entry: (@Composable () -> Unit)? = if (enabled && account?.kind == ServerKind.WEBUI) {
            { BotsEntryRow { showingBots = true } }
        } else null
        content(entry)
    }
}

/** The session list's native utility row; tapping it does not start a bot turn. */
@Composable
private fun BotsEntryRow(onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Bots", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Filled.ChevronRight, contentDescription = "Open Bots")
    }
}
