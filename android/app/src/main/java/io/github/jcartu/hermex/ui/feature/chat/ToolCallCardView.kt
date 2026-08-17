package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    startExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable(toolCall.id, startExpanded) { mutableStateOf(startExpanded) }
    val argumentRows = ToolCallDisplayFormatter.argumentRows(toolCall.args)
    val statusDisplay = ToolCallDisplayFormatter.statusDisplay(toolCall)
    val statusColor = if (statusDisplay.status == ToolCallVisualStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(9.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = 9.dp, vertical = if (isExpanded) 8.dp else 7.dp),
            ) {
                ToolCallHeader(
                    toolCall = toolCall,
                    argumentPreview = argumentRows.firstOrNull()?.previewLine(),
                    statusDisplay = statusDisplay,
                    statusColor = statusColor,
                    isExpanded = isExpanded,
                    onToggle = { isExpanded = !isExpanded },
                )
                if (isExpanded) {
                    ToolCallExpandedContent(
                        toolCall = toolCall,
                        displayContent = ToolCallDisplayFormatter.content(toolCall),
                        statusDisplay = statusDisplay,
                        statusColor = statusColor,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallHeader(
    toolCall: ToolCall,
    argumentPreview: String?,
    statusDisplay: ToolCallStatusDisplay,
    statusColor: Color,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = "${toolCall.displayName}, ${statusDisplay.detailText}"
            }
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolStatusIcon(status = statusDisplay.status, color = statusColor)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ToolCallDisplayFormatter.displayName(toolCall),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                statusDisplay.collapsedText?.let { text ->
                    Spacer(modifier = Modifier.width(6.dp))
                    ToolStatusPill(text = text, color = statusColor)
                }
            }
            argumentPreview?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse tool details" else "Expand tool details",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ToolCallExpandedContent(
    toolCall: ToolCall,
    displayContent: ToolCallDisplayContent,
    statusDisplay: ToolCallStatusDisplay,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (displayContent.argumentRows.isNotEmpty()) {
            Text(
                text = "Arguments",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                shape = RoundedCornerShape(7.dp),
            ) {
                Column(modifier = Modifier.padding(7.dp)) {
                    displayContent.argumentRows.forEachIndexed { index, row ->
                        if (index > 0) Spacer(modifier = Modifier.size(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                text = row.key,
                                modifier = Modifier.width(78.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = CodeTextStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.value,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = CodeTextStyle,
                                )
                            }
                        }
                    }
                }
            }
        }

        displayContent.result?.let { result ->
            Text(
                text = result.title,
                modifier = Modifier.padding(top = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                shape = RoundedCornerShape(7.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = result.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(7.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = if (result.isMonospaced) CodeTextStyle else MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        val hasPrimaryContent = displayContent.argumentRows.isNotEmpty() || displayContent.result != null
        if (!hasPrimaryContent || !toolCall.isCompleted || toolCall.isError == true || toolCall.duration != null) {
            Row(
                modifier = Modifier.padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Status",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.width(6.dp))
                SelectionContainer {
                    Text(
                        text = statusDisplay.detailText,
                        color = statusColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun ToolCallGroupCard(
    group: ToolCallGroup,
    startExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable(group.id, startExpanded) { mutableStateOf(startExpanded) }
    val status = when {
        group.hasFailedTool -> ToolCallVisualStatus.FAILED
        group.isComplete -> ToolCallVisualStatus.COMPLETED
        else -> ToolCallVisualStatus.RUNNING
    }
    val statusColor = if (status == ToolCallVisualStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val summary = groupSummary(group)
    val stateText = when (status) {
        ToolCallVisualStatus.FAILED -> "Failed"
        ToolCallVisualStatus.COMPLETED -> "Completed"
        ToolCallVisualStatus.RUNNING -> "Running"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        contentDescription = "${group.activityTitle}, $stateText, $summary"
                    }
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolStatusIcon(status = status, color = statusColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = group.activityTitle,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status != ToolCallVisualStatus.COMPLETED) {
                    ToolStatusPill(text = stateText, color = statusColor)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse activity" else "Expand activity",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    group.toolCalls.forEachIndexed { index, toolCall ->
                        if (index > 0) Spacer(modifier = Modifier.size(6.dp))
                        ToolCallCard(toolCall = toolCall, startExpanded = startExpanded)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(status: ToolCallVisualStatus, color: Color) {
    when (status) {
        ToolCallVisualStatus.FAILED -> Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = "Failed",
            tint = color,
            modifier = Modifier.size(18.dp),
        )

        ToolCallVisualStatus.COMPLETED -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Completed",
            tint = color,
            modifier = Modifier.size(18.dp),
        )

        ToolCallVisualStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = color,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun ToolStatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private fun ToolCallArgumentDisplay.previewLine(): String =
    "$key: ${value.replace('\n', ' ').trim()}"

private fun groupSummary(group: ToolCallGroup): String {
    val uniqueNames = group.toolCalls.map(ToolCall::displayName).distinct()
    if (uniqueNames.isEmpty()) return "No tools"
    val visibleNames = uniqueNames.take(3)
    val remainingCount = uniqueNames.size - visibleNames.size
    val visibleSummary = visibleNames.joinToString(", ")
    return if (remainingCount > 0) "$visibleSummary, +$remainingCount" else visibleSummary
}
