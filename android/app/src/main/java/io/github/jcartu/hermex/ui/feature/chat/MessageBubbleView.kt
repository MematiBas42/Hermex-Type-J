package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.ui.theme.SuccessDark
import io.github.jcartu.hermex.ui.theme.SuccessLight
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Port of `MessageBubbleView.swift`: user messages as right-aligned tinted
 * bubbles, assistant turns full-width on the leading edge, `local_notice` /
 * `local_assistant` as bordered status rows. Long-press copies the message
 * content. When [ChatUiState.rtlChatLayoutEnabled] is on the transcript runs
 * under an RTL `LayoutDirection` (provided by [ChatTranscript]), which flips
 * the start/end alignments here automatically.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    state: ChatUiState,
    isStreaming: Boolean,
    liveTokensPerSecond: Double?,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    onListen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        "local_notice" -> LocalStatusRow(
            icon = Icons.Rounded.CheckCircle,
            iconTint = if (isSystemInDarkTheme()) SuccessDark else SuccessLight,
            message = message,
            state = state,
            modifier = modifier,
        )

        "local_assistant" -> LocalStatusRow(
            icon = Icons.Rounded.KeyboardCommandKey,
            iconTint = MaterialTheme.colorScheme.primary,
            message = message,
            state = state,
            modifier = modifier,
        )

        "user" -> UserMessageRow(
            message = message,
            state = state,
            attachmentUrlResolver = attachmentUrlResolver,
            imageLoader = imageLoader,
            modifier = modifier,
        )

        else -> AssistantMessageRow(
            message = message,
            state = state,
            isStreaming = isStreaming,
            liveTokensPerSecond = liveTokensPerSecond,
            onListen = onListen,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageRow(
    message: ChatMessage,
    state: ChatUiState,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val bubbleText = userBubbleText(message, state.hidesAttachmentPaths)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val attachments = message.attachments
        if (!attachments.isNullOrEmpty()) {
            MessageAttachmentsGroup(
                attachments = attachments,
                attachmentUrlResolver = attachmentUrlResolver,
                imageLoader = imageLoader,
            )
        }

        // When the attachment-path line is hidden, an attachment-only message
        // has no bubble text left; skip the empty pill so only the attachment
        // grid shows.
        if (bubbleText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                // Leading gutter so a long bubble never spans the full pane.
                modifier = Modifier.padding(start = 32.dp),
            ) {
                Text(
                    text = bubbleText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { clipboard.setText(AnnotatedString(bubbleText)) },
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantMessageRow(
    message: ChatMessage,
    state: ChatUiState,
    isStreaming: Boolean,
    liveTokensPerSecond: Double?,
    onListen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val content = message.content
    val hasVisibleText = !content.isNullOrBlank()
    val speedText = if (state.showsResponseSpeed) {
        ResponseSpeedFormatter.compactText(if (isStreaming) liveTokensPerSecond else message.turnTps)
    } else {
        null
    }
    val timeText = if (state.showsAssistantTurnTimestamps) {
        AssistantTurnTimestampFormatter.shortTime(message.timestamp)
    } else {
        null
    }
    val showsHeader = showsAssistantTurnHeader(
        role = message.role,
        hasTextContent = hasVisibleText,
        isEnabled = state.showsAssistantTurnTimestamps,
        showsResponseSpeed = state.showsResponseSpeed,
        hasResponseSpeed = speedText != null,
    )
    // Placeholder space mirrors the Swift `messageText` fallback so an empty
    // streaming row keeps its layout slot.
    val messageText = if (content.isNullOrEmpty()) " " else content

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showsHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = "Assistant",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                if (timeText != null) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (speedText != null) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = speedText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        StreamingTextFade(
            text = messageText,
            isStreaming = isStreaming,
            modifier = Modifier.fillMaxWidth(),
        ) {
            MessageMarkdown(
                content = messageText,
                wrapCodeLines = state.wrapsCodeBlockLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (hasVisibleText) clipboard.setText(AnnotatedString(content.orEmpty()))
                        },
                    ),
            )
        }
        if (hasVisibleText && !isStreaming) {
            TextButton(onClick = { onListen(content.orEmpty()) }) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Listen")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalStatusRow(
    icon: ImageVector,
    iconTint: Color,
    message: ChatMessage,
    state: ChatUiState,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val content = message.content
    val messageText = if (content.isNullOrEmpty()) " " else content

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (!content.isNullOrBlank()) clipboard.setText(AnnotatedString(content))
                    },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(iconTint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp),
                )
            }

            MessageMarkdown(
                content = messageText,
                wrapCodeLines = state.wrapsCodeBlockLines,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The user bubble's text with the appended `[Attached files: …]` marker
 * stripped when the user opted to hide paths. Display-only.
 */
private fun userBubbleText(message: ChatMessage, hidesAttachmentPaths: Boolean): String {
    val content = message.content ?: ""
    if (!hidesAttachmentPaths) return content
    return MessageAttachment.contentWithoutAttachedFilesMarker(content)
}

/**
 * Port of `ChatTranscriptDisplaySettings.showsAssistantTurnHeader`: the header
 * is a turn separator, limited to real assistant turns with visible text.
 */
internal fun showsAssistantTurnHeader(
    role: String?,
    hasTextContent: Boolean,
    isEnabled: Boolean,
    showsResponseSpeed: Boolean = false,
    hasResponseSpeed: Boolean = false,
): Boolean =
    (isEnabled || (showsResponseSpeed && hasResponseSpeed)) &&
        role == "assistant" &&
        hasTextContent

/**
 * Port of `AssistantTurnTimestampFormatter`: unix `timestamp` → short,
 * locale-/24h-aware time (`2:14 PM` / `14:14`). Null for missing or
 * non-finite timestamps so the header falls back to glyph-only.
 */
object AssistantTurnTimestampFormatter {
    fun shortTime(
        timestamp: Double?,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String? {
        if (timestamp == null || !timestamp.isFinite()) return null
        val formatter = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        formatter.timeZone = timeZone
        return formatter.format(Date((timestamp * 1_000).toLong()))
    }
}

/** Port of `ResponseSpeedFormatter`: `12.3 t/s`, null for absent/non-positive rates. */
object ResponseSpeedFormatter {
    fun compactText(tokensPerSecond: Double?, locale: Locale = Locale.getDefault()): String? {
        if (tokensPerSecond == null || !tokensPerSecond.isFinite() || tokensPerSecond <= 0) return null
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return "${formatter.format(tokensPerSecond)} t/s"
    }
}
