package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Port of `ChatActiveRunStatusView.swift` + `ChatActiveRunStatusPolicy`
 * (AppTheme.swift), extended with the inline stop control the Android layout
 * places in this row. Visible while a response runs or the stream is being
 * recovered; the label follows the run phase.
 */
@Composable
fun ChatActiveRunStatusRow(
    state: ChatUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    waitingForInput: Boolean = false,
) {
    val visible = state.isStreaming ||
        state.recoveryState != ActiveStreamRecoveryState.IDLE ||
        state.isStartingChat ||
        state.isCancellingStream
    if (!visible) return

    val runningToolName = state.liveToolCalls.lastOrNull { !it.isCompleted }?.displayName
    val label = when {
        state.isCancellingStream -> "Stopping response"
        state.isStartingChat -> "Starting response"
        state.recoveryState == ActiveStreamRecoveryState.CHECKING -> "Checking stream"
        state.recoveryState == ActiveStreamRecoveryState.RECONNECTING -> "Reconnecting stream"
        waitingForInput -> "Waiting for you"
        runningToolName != null -> "Running $runningToolName"
        state.liveReasoningText.isNotBlank() && state.streamingAssistantMessageId == null -> "Thinking…"
        else -> "Hermes is working"
    }
    val speedText = if (state.showsResponseSpeed) {
        ResponseSpeedFormatter.compactText(state.liveTokensPerSecond)
    } else {
        null
    }
    val reduceMotion = rememberReduceMotion()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reduceMotion) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (speedText != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = speedText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(4.dp))

            IconButton(
                onClick = onStop,
                enabled = !state.isCancellingStream,
                modifier = Modifier.size(32.dp),
            ) {
                if (state.isCancellingStream) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "Stop response",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
