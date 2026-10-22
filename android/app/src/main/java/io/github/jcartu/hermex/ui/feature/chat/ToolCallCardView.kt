package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

/** One tool invocation as the compact log row from `ToolCallLogRowView.swift`. */
@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    isLive: Boolean = !toolCall.isCompleted,
    hapticsEnabled: Boolean = true,
) {
    val entry = ToolCallSummaryFormatter.row(toolCall, isLive) ?: return
    var expanded by rememberSaveable(toolCall.presentationID, startExpanded) { mutableStateOf(startExpanded) }
    ToolCallLogRowView(
        entry = ToolCallLogEntry(toolCall, entry),
        isExpanded = expanded,
        onToggle = { expanded = !expanded },
        hapticsEnabled = hapticsEnabled,
        modifier = modifier,
    )
}

@Composable
private fun ToolCallLogRowView(
    entry: ToolCallLogEntry,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val row = entry.row
    val statusColor = if (row.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    TranscriptLogRow(
        summary = row.summary,
        detail = row.detail,
        isFailure = row.isFailure,
        isExpanded = isExpanded,
        accessibilityLabel = row.accessibilityLabel,
        copyText = { ToolCallSummaryFormatter.copyText(entry.toolCall) },
        onToggle = onToggle,
        hapticsEnabled = hapticsEnabled,
        modifier = modifier,
        leading = {
            Icon(
                imageVector = iconFor(row.kind),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(15.dp),
            )
        },
        accessory = {
            row.diffCounts?.let { DiffCountsLabel(it) }
        },
        status = {
            ToolStatusGlyph(row.status, statusColor)
        },
        expandedBody = {
            ToolCallExpandedContent(
                toolCall = entry.toolCall,
                status = row.status,
                hapticsEnabled = hapticsEnabled,
            )
        },
    )
}

/**
 * A turn's calls as a dense log. The newest call remains visible; earlier
 * calls fold behind one `+N previous tool calls` disclosure.
 */
@Composable
fun ToolCallGroupCard(
    group: ToolCallGroup,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    isLive: Boolean = !group.isComplete,
    isReplaying: Boolean = false,
    hapticsEnabled: Boolean = true,
) {
    val entries = remember(group.toolCalls, isLive) {
        ToolCallSummaryFormatter.entries(group.toolCalls, isLive)
    }
    val last = entries.lastOrNull() ?: return
    val previous = entries.dropLast(1)
    var expanded by rememberSaveable(group.id, startExpanded) { mutableStateOf(startExpanded) }
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (previous.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        expanded = !expanded
                    }
                    .padding(horizontal = 2.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.ExpandLess
                    } else {
                        Icons.Rounded.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (expanded) "Show fewer tool calls" else "+${previous.size} previous tool calls",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
            if (expanded) {
                previous.forEach { entry ->
                    var rowExpanded by rememberSaveable(entry.id) { mutableStateOf(startExpanded) }
                    ToolCallLogRowView(
                        entry = entry,
                        isExpanded = rowExpanded,
                        onToggle = { rowExpanded = !rowExpanded },
                        hapticsEnabled = hapticsEnabled,
                    )
                }
            }
        }

        var lastExpanded by rememberSaveable(last.id) { mutableStateOf(startExpanded) }
        FreshToolRow(
            key = last.id,
            animate = isLive && entries.size > 1 && !isReplaying && !reduceMotion,
        ) {
            ToolCallLogRowView(
                entry = last,
                isExpanded = lastExpanded,
                onToggle = { lastExpanded = !lastExpanded },
                hapticsEnabled = hapticsEnabled,
            )
        }
    }
}

@Composable
private fun FreshToolRow(key: String, animate: Boolean, content: @Composable () -> Unit) {
    val alpha = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key, animate) {
        if (animate) alpha.animateTo(1f, tween(220)) else alpha.snapTo(1f)
    }
    Box(Modifier.alpha(alpha.value)) { content() }
}

@Composable
private fun ToolCallExpandedContent(
    toolCall: ToolCall,
    status: ToolCallLogStatus,
    hapticsEnabled: Boolean,
) {
    val content = remember(toolCall) { ToolCallDisplayFormatter.openedContent(toolCall) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        content.diff?.let { diff -> ToolDiffSection(diff, hapticsEnabled) }

        if (content.argumentRows.isNotEmpty()) {
            Text(
                text = "Arguments",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    content.argumentRows.forEach { argument ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                text = argument.key,
                                style = CodeTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(78.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(argument.value, style = CodeTextStyle)
                            }
                        }
                    }
                }
            }
        }

        content.result?.let { result ->
            Text(
                text = result.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = result.text,
                        style = if (result.isMonospaced) CodeTextStyle else MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    )
                }
            }
        }

        val hasPrimary = content.diff != null || content.argumentRows.isNotEmpty() || content.result != null
        if (!hasPrimary || !toolCall.isCompleted || toolCall.isError == true || toolCall.duration != null) {
            val statusText = when (status) {
                ToolCallLogStatus.SUCCESS -> toolCall.duration?.let { "Completed in %.1fs".format(it) } ?: "Completed"
                ToolCallLogStatus.FAILURE -> "Failed"
                ToolCallLogStatus.RUNNING -> "Running"
                ToolCallLogStatus.INTERRUPTED -> "Interrupted"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == ToolCallLogStatus.FAILURE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolDiffSection(diff: ToolCallDiff, hapticsEnabled: Boolean) {
    var showAll by rememberSaveable(diff.document.hashCode()) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            val horizontal = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontal)
                    .padding(vertical = 5.dp),
            ) {
                diff.document.visibleLines(showAll).forEach { line -> DiffLine(line) }
            }
            if (diff.document.isCollapsible) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = if (showAll) {
                        "Show first ${MarkdownDiffDocument.COLLAPSED_LINE_LIMIT} lines"
                    } else {
                        "Show all ${diff.document.lines.size} lines"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            showAll = !showAll
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
    }
    if (diff.isTruncated) {
        Text(
            text = "Partial diff. The server sent only the first part of this edit.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DiffLine(line: MarkdownDiffLine, modifier: Modifier = Modifier) {
    val background = when (line.kind) {
        MarkdownDiffLine.Kind.ADDED -> Color(0xFF2E7D32).copy(alpha = 0.18f)
        MarkdownDiffLine.Kind.REMOVED -> Color(0xFFC62828).copy(alpha = 0.18f)
        MarkdownDiffLine.Kind.HUNK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val foreground = when (line.kind) {
        MarkdownDiffLine.Kind.ADDED -> Color(0xFF5EDB70)
        MarkdownDiffLine.Kind.REMOVED -> Color(0xFFFF6B6B)
        MarkdownDiffLine.Kind.HUNK, MarkdownDiffLine.Kind.FILE_HEADER -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = line.text.ifEmpty { " " },
        style = CodeTextStyle,
        color = foreground,
        softWrap = false,
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

@Composable
private fun DiffCountsLabel(counts: ToolCallDiff.Counts) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("+${counts.additions}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF43A047))
        counts.deletions?.let {
            Text("−$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ToolStatusGlyph(status: ToolCallLogStatus, color: Color) {
    when (status) {
        ToolCallLogStatus.SUCCESS -> Icon(Icons.Rounded.Check, "Completed", tint = color, modifier = Modifier.size(13.dp))
        ToolCallLogStatus.FAILURE -> Icon(Icons.Rounded.ErrorOutline, "Failed", tint = color, modifier = Modifier.size(14.dp))
        ToolCallLogStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp, color = color)
        ToolCallLogStatus.INTERRUPTED -> Text("−", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun iconFor(kind: ToolCallKind): ImageVector = when (kind) {
    ToolCallKind.SHELL -> Icons.Rounded.Terminal
    ToolCallKind.READ -> Icons.Rounded.Description
    ToolCallKind.WRITE -> Icons.Rounded.EditNote
    ToolCallKind.SEARCH -> Icons.Rounded.Search
    ToolCallKind.WEB -> Icons.Rounded.Language
    ToolCallKind.LIST -> Icons.Rounded.List
    ToolCallKind.SKILL -> Icons.Rounded.MenuBook
    ToolCallKind.MEMORY -> Icons.Rounded.Psychology
    ToolCallKind.DELEGATE -> Icons.Rounded.AccountTree
    ToolCallKind.UNKNOWN -> Icons.Rounded.Build
}
