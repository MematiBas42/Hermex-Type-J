package io.github.jcartu.hermex.ui.feature.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Read-only explanation of `HermesChatPolicy.botChat` (`Features/Chat/HermesSlashCommands.swift`).
 * This sheet does not decide which actions run: the shared Hermes chat policy owns admission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatRulesSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("About this bot’s chat", style = MaterialTheme.typography.titleLarge)
            Text("A bot keeps one chat under its own Profile and name. New chat, clear, switching chats, renaming, forking, editing and rewinding are not available here.")
            Text("Run /compress to free up context. /btw, /background, /goal and /yolo work as they do in a Hermes session. Retry under a failed turn is still available when Hermes offers it.")
            Text("@mentions identify other bots on this Hermes connection. They do not send a message themselves: the agent decides whether to use its message_agent tool. If that tool is unavailable, the agent cannot contact another bot from here.")
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}
