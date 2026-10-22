package io.github.jcartu.hermex.ui.feature.bots

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance

/**
 * Generic setup request from `BotConnectionSetup.prompt` in
 * `Features/Bots/BotConnectionView.swift`. It deliberately contains no saved address,
 * username, password, token, or other device state: copying it sends nothing.
 */
const val BOT_CONNECTION_SETUP_PROMPT: String = """Help me connect the Hermex Android app to my existing Hermes dashboard for Bots and notifications. Hermex may already connect to hermes-webui, but this is a separate direct dashboard connection.

Inspect this machine's Hermes installation and current dashboard/Desktop setup first. Check the installed version's supported setup instructions and whether its dashboard supports username/password sign-in and Bot chats. Reuse the backend and Profile storage already used by my bots; do not start a second backend against the same storage.

Find the dashboard address reachable from my phone, including scheme and port, and the sign-in username. A localhost address only works on this machine, so ask whether I use LAN, Tailscale/VPN, or an existing HTTPS tunnel when needed. Do not assume the WebUI address or login is the dashboard's.

Tell me whether I should use my existing dashboard password. Do not print secrets, invent credentials, or claim to recover a hashed password. If setup or a password reset is needed, explain the exact changes and ask me before changing credentials, starting or restarting services, installing anything, or changing network exposure. Help me set up the supported connection only after I approve.

Finish with the Hermes address and username to enter in Hermex, where to obtain or set the password, and any remaining steps. Do not claim the phone can connect until reachability and authenticated dashboard access have been checked."""

/**
 * Disconnected Bot inbox welcome, ported from `BotConnectionWelcomeView` in
 * `Features/Bots/BotConnectionView.swift` and the setup-help contract in `docs/agents/bots.md`.
 * The same neutral drawn faces as the editor are interactive only while this surface is
 * uncovered. The face implementation itself also honours lifecycle and Reduce Motion.
 */
@Composable
fun BotConnectionWelcome(
    onConnection: () -> Unit,
    modifier: Modifier = Modifier,
    isCovered: Boolean = false,
) {
    val context = LocalContext.current
    var copied by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        val faceSize = (maxWidth / 4).coerceIn(56.dp, 92.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 408.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Top,
            ) {
                WelcomeFace(
                    name = "welcome-circle",
                    shape = BotAvatarShape.CIRCLE,
                    color = "#f97316",
                    size = faceSize,
                    isCovered = isCovered,
                )
                WelcomeFace(
                    name = "welcome-triangle",
                    shape = BotAvatarShape.TRIANGLE,
                    color = "#22c55e",
                    size = faceSize,
                    isCovered = isCovered,
                    modifier = Modifier.padding(top = 22.dp),
                )
                WelcomeFace(
                    name = "welcome-squircle",
                    shape = BotAvatarShape.SQUIRCLE,
                    color = "#8b5cf6",
                    size = faceSize,
                    isCovered = isCovered,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Your bots, together.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Bots live in your Hermes dashboard. WebUI uses a separate connection, so sign in once here to bring them to Hermex.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = onConnection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect", modifier = Modifier.padding(vertical = 6.dp))
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Need your connection details? Copy a prompt for your Hermes agent. It checks the existing setup and asks before changing anything. Copying sends nothing and includes no saved connection details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hermes setup prompt", BOT_CONNECTION_SETUP_PROMPT))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (copied) "Copied" else "Copy setup prompt")
                }
            }
        }
    }
}

@Composable
private fun WelcomeFace(
    name: String,
    shape: BotAvatarShape,
    color: String,
    size: Dp,
    isCovered: Boolean,
    modifier: Modifier = Modifier,
) {
    val appearance = BotProfileAppearance(
        title = name,
        shape = shape.rawValue,
        color = color,
        custom = true,
        expression = BotAvatarExpression.NEUTRAL.rawValue,
    )
    if (isCovered) {
        BotAvatarMarkView(
            name = name,
            appearance = appearance,
            size = size,
            pose = BotFacePose.Rest,
            modifier = modifier,
        )
    } else {
        BotInteractiveFaceView(
            name = name,
            appearance = appearance,
            size = size,
            modifier = modifier,
        )
    }
}
