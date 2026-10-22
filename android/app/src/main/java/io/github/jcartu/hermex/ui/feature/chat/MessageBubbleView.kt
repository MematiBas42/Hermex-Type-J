package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Message surface ported from `MessageBubbleView.swift` (1.6–2.0). */
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
    attachmentDataResolver: suspend (path: String) -> ByteArray? = { null },
    workspaceRoot: String? = null,
    onOpenWeb: (String) -> Unit = {},
    onOpenWorkspaceFile: (String) -> Unit = {},
    onAskHermex: (String) -> Unit = {},
    hapticsEnabled: Boolean = true,
) {
    when {
        message.role == "local_notice" -> LocalStatusRow(
            icon = Icons.Rounded.CheckCircle,
            iconTint = SuccessDark,
            message = message,
            state = state,
            onOpenWeb = onOpenWeb,
            onOpenWorkspaceFile = onOpenWorkspaceFile,
            hapticsEnabled = hapticsEnabled,
            modifier = modifier,
        )
        message.role == "local_assistant" -> LocalStatusRow(
            icon = Icons.Rounded.KeyboardCommandKey,
            iconTint = MaterialTheme.colorScheme.primary,
            message = message,
            state = state,
            onOpenWeb = onOpenWeb,
            onOpenWorkspaceFile = onOpenWorkspaceFile,
            hapticsEnabled = hapticsEnabled,
            modifier = modifier,
        )
        message.isSteerMessage -> SteeringHintRow(message.steerText, modifier)
        message.role == "user" -> UserMessageRow(
            message = message,
            hidesAttachmentPaths = state.hidesAttachmentPaths,
            attachmentUrlResolver = attachmentUrlResolver,
            attachmentDataResolver = attachmentDataResolver,
            imageLoader = imageLoader,
            onOpenWorkspaceFile = onOpenWorkspaceFile,
            onOpenWeb = onOpenWeb,
            hapticsEnabled = hapticsEnabled,
            modifier = modifier,
        )
        else -> AssistantMessageRow(
            message = message,
            state = state,
            isStreaming = isStreaming,
            liveTokensPerSecond = liveTokensPerSecond,
            attachmentUrlResolver = attachmentUrlResolver,
            attachmentDataResolver = attachmentDataResolver,
            workspaceRoot = workspaceRoot,
            imageLoader = imageLoader,
            onOpenWeb = onOpenWeb,
            onOpenWorkspaceFile = onOpenWorkspaceFile,
            onAskHermex = onAskHermex,
            onListen = onListen,
            hapticsEnabled = hapticsEnabled,
            modifier = modifier,
        )
    }
}

@Composable
private fun UserMessageRow(
    message: ChatMessage,
    hidesAttachmentPaths: Boolean,
    attachmentUrlResolver: suspend (String) -> String?,
    attachmentDataResolver: suspend (String) -> ByteArray?,
    imageLoader: ImageLoader?,
    onOpenWorkspaceFile: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier,
) {
    val text = remember(message.content, hidesAttachmentPaths) {
        val content = message.content.orEmpty()
        if (hidesAttachmentPaths) MessageAttachment.contentWithoutAttachedFilesMarker(content) else content
    }
    val link = remember(message.content) { TranscriptLinkPreviewEligibility.previewUrl(message, false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.attachments.orEmpty().takeIf(List<MessageAttachment>::isNotEmpty)?.let { attachments ->
            MessageAttachmentsGroup(
                attachments = attachments,
                attachmentUrlResolver = attachmentUrlResolver,
                attachmentDataResolver = attachmentDataResolver,
                imageLoader = imageLoader,
                onOpenWorkspaceFile = onOpenWorkspaceFile,
            )
        }
        if (text.isNotBlank()) {
            UserBubble(text, hapticsEnabled)
        }
        link?.let { TranscriptLinkPreviewView(it, onOpenWeb) }
    }
}

@Composable
private fun UserBubble(text: String, hapticsEnabled: Boolean) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var canFold by remember(text) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(start = 32.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { result -> if (!expanded && result.hasVisualOverflow) canFold = true },
                    )
                }
                if (canFold && !expanded) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceVariant),
                                ),
                            ),
                    )
                }
            }
            if (canFold) {
                TextButton(
                    onClick = {
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        expanded = !expanded
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(if (expanded) "Show less" else "Show more", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SteeringHintRow(text: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Steer", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            ) {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageRow(
    message: ChatMessage,
    state: ChatUiState,
    isStreaming: Boolean,
    liveTokensPerSecond: Double?,
    attachmentUrlResolver: suspend (String) -> String?,
    attachmentDataResolver: suspend (String) -> ByteArray?,
    workspaceRoot: String?,
    imageLoader: ImageLoader?,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
    onAskHermex: (String) -> Unit,
    onListen: (String) -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier,
) {
    val content = message.content.orEmpty()
    val displayed = rememberWordBoundaryText(content.ifEmpty { " " }, isStreaming, message.messageId ?: message.id)
    val speed = if (state.showsResponseSpeed) {
        ResponseSpeedFormatter.compactText(if (isStreaming) liveTokensPerSecond else message.turnTps)
    } else null
    val segments = remember(displayed, workspaceRoot, isStreaming) {
        TranscriptMediaParser.segments(displayed, workspaceRoot)
    }
    val hasMedia = segments.any { it is TranscriptMediaSegment.Media }
    val link = remember(message.content, isStreaming) { TranscriptLinkPreviewEligibility.previewUrl(message, isStreaming) }

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (speed != null && content.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.AutoAwesome, "Assistant", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                Text(speed, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val body: @Composable () -> Unit = {
            if (hasMedia) {
                TranscriptMediaContentView(
                    segments = segments,
                    wrapCodeLines = state.wrapsCodeBlockLines,
                    imageLoader = imageLoader,
                    loadMediaUrl = { reference ->
                        when (val source = reference.source) {
                            is TranscriptMediaSource.RemoteUrl -> source.url
                            is TranscriptMediaSource.LocalPath -> attachmentUrlResolver(source.path)
                        }
                    },
                    loadMediaData = { reference ->
                        when (val source = reference.source) {
                            is TranscriptMediaSource.RemoteUrl -> attachmentDataResolver(source.url)
                            is TranscriptMediaSource.LocalPath -> attachmentDataResolver(source.path)
                        }
                    },
                    onOpenWeb = onOpenWeb,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    onAskHermex = onAskHermex,
                    hapticsEnabled = hapticsEnabled,
                )
            } else {
                TranscriptMarkdown(
                    content = displayed,
                    wrapCodeLines = state.wrapsCodeBlockLines,
                    onOpenWeb = onOpenWeb,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    hapticsEnabled = hapticsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (isStreaming) body() else ResponseSelectionContainer(onAskHermex, content = body)
        link?.let { TranscriptLinkPreviewView(it, onOpenWeb) }
        if (content.isNotBlank() && !isStreaming) {
            TextButton(onClick = { onListen(content) }) {
                Icon(Icons.Rounded.VolumeUp, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Listen")
            }
        }
    }
}

@Composable
private fun LocalStatusRow(
    icon: ImageVector,
    iconTint: Color,
    message: ChatMessage,
    state: ChatUiState,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).background(iconTint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
            }
            TranscriptMarkdown(
                content = message.content?.ifEmpty { " " } ?: " ",
                wrapCodeLines = state.wrapsCodeBlockLines,
                onOpenWeb = onOpenWeb,
                onOpenWorkspaceFile = onOpenWorkspaceFile,
                hapticsEnabled = hapticsEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal fun showsAssistantTurnHeader(
    role: String?,
    hasTextContent: Boolean,
    isEnabled: Boolean,
    showsResponseSpeed: Boolean = false,
    hasResponseSpeed: Boolean = false,
): Boolean =
    (isEnabled || (showsResponseSpeed && hasResponseSpeed)) && role == "assistant" && hasTextContent

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
