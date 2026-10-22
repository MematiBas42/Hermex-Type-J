package io.github.jcartu.hermex.ui.feature.chat.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Expand
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.hermes.HermesBackgroundTask
import io.github.jcartu.hermex.model.hermes.HermesBtw
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.chat.MessageMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Android projection of `ChatActiveRunStatusKind` used by `HermesBtwPanel.swift`. */
data class HermesBtwRunStatus(
    val label: String,
    val accessibilityLabel: String = label,
)

/**
 * Port of `HermesBtwCard` in `Features/Chat/HermesBtwPanel.swift`: the docked side-question
 * card. Its body is bounded so a transcript remains visible above it on every pane width.
 */
@Composable
fun HermesBtwCard(
    btw: HermesBtw,
    maximumExpandedHeight: Dp,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paneCount = LocalPaneCount.current
    val bodyMaximum = (maximumExpandedHeight - 96.dp).coerceIn(44.dp, 320.dp)
    Surface(
        modifier = modifier.widthIn(max = if (paneCount == 1) 560.dp else 640.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
                .semantics {
                    if (btw.state != HermesBtw.State.Waiting) liveRegion = LiveRegionMode.Polite
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HermesBtwHeader(onExpand = onExpand, onClose = onClose)
            Column(
                modifier = Modifier.heightIn(min = 44.dp, max = bodyMaximum).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = btw.question,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(11.dp),
                    )
                }
                HermesBtwAnswer(state = btw.state, isFullScreen = false)
            }
        }
    }
}

/**
 * Port of `HermesBtwBar`: a one-line side-question affordance while a host request occupies
 * the composer slot.
 */
@Composable
fun HermesBtwBar(
    btw: HermesBtw,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .semantics {
                if (btw.state != HermesBtw.State.Waiting) liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onExpand,
                modifier = Modifier.weight(1f),
            ) {
                HermesBtwLabel()
                Text(
                    text = btw.question,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                )
                Icon(Icons.Outlined.Expand, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            HermesBtwCloseButton(onClose)
        }
    }
}

/**
 * Port of `HermesBtwFullScreen`: a readable answer column with explicit Collapse and Close
 * actions and an optional static status pill for the hidden running turn.
 */
@Composable
fun HermesBtwFullScreen(
    btw: HermesBtw,
    sessionTitle: String,
    runStatus: HermesBtwRunStatus?,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Outlined.UnfoldLess, contentDescription = "Collapse side question")
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("BTW", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = sessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close side question")
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .semantics {
                        if (btw.state != HermesBtw.State.Waiting) liveRegion = LiveRegionMode.Polite
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = btw.question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                HermesBtwAnswer(state = btw.state, isFullScreen = true)
            }
        }
        if (runStatus != null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HermesBtwRunPill(runStatus)
            }
        }
    }
}

/**
 * Composable port of `hermesBackgroundCard` / `backgroundResultText` in
 * `Features/Chat/ChatViewModel.swift`: one durable local-assistant card that moves from
 * running to its answer or an unavailable result without becoming user-authored transcript.
 */
@Composable
fun HermesBackgroundTaskCard(
    task: HermesBackgroundTask,
    modifier: Modifier = Modifier,
) {
    val prompt = remember(task.prompt) { backgroundPromptSummary(task.prompt) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Background",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when (val state = task.state) {
                HermesBackgroundTask.State.Running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(6.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {}
                        Text(
                            text = "Running in the background…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is HermesBackgroundTask.State.Finished -> {
                    if (state.text.isBlank()) {
                        Text(
                            text = "No answer produced.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        MessageMarkdown(content = state.text, wrapCodeLines = true)
                    }
                }
                HermesBackgroundTask.State.Unavailable -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Result unavailable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Android counterpart to `GoalControlsMenu` plus the automation confirmation in
 * `ChatView.swift`. The two-argument call shape is intentional so the parent can supply it as
 * the composer's optional goal content without introducing another shared UI dependency.
 */
@Composable
fun HermesGoalMenu(
    sideTasks: HermesChatSideTasks,
    onGoalCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var setGoalOpen by remember { mutableStateOf(false) }
    var goalDraft by rememberSaveable { mutableStateOf("") }
    var automationError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val goalStatus = sideTasks.goal?.status?.lowercase()

    Box(modifier = modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            label = { Text("Goal") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = when (goalStatus) {
                        "paused" -> MaterialTheme.colorScheme.onSurfaceVariant
                        "done", "cleared" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            },
            modifier = Modifier.semantics { contentDescription = "Goal controls" },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Set Goal") },
                onClick = {
                    menuOpen = false
                    setGoalOpen = true
                },
            )
            HorizontalDivider()
            listOf(
                "Status" to "status",
                "Pause" to "pause",
                "Resume" to "resume",
                "Mark Done" to "done",
                "Clear" to "clear",
                "Stop" to "stop",
            ).forEach { (title, command) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        menuOpen = false
                        onGoalCommand(command)
                    },
                )
            }
            sideTasks.automations.forEach { control ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${control.kind.label} · ${automationStatus(control.status)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                if (sideTasks.controlsAutomations && control.action != null) {
                    DropdownMenuItem(
                        text = { Text(control.actionTitle) },
                        enabled = sideTasks.canChangeAutomations && !sideTasks.isChangingAutomation,
                        onClick = {
                            menuOpen = false
                            sideTasks.ask(control)
                        },
                    )
                }
            }
        }
    }

    if (setGoalOpen) {
        AlertDialog(
            onDismissRequest = { setGoalOpen = false },
            title = { Text("Set Goal") },
            text = {
                OutlinedTextField(
                    value = goalDraft,
                    onValueChange = { goalDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Describe the goal") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            dismissButton = {
                TextButton(onClick = { setGoalOpen = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val submitted = goalDraft.trim()
                        goalDraft = ""
                        setGoalOpen = false
                        onGoalCommand(submitted)
                    },
                    enabled = goalDraft.isNotBlank(),
                ) { Text("Set") }
            },
        )
    }

    sideTasks.pendingAutomation?.let { control ->
        AlertDialog(
            onDismissRequest = sideTasks::cancelAutomation,
            title = { Text("Change session control?") },
            text = { Text(control.consequence) },
            dismissButton = {
                TextButton(onClick = sideTasks::cancelAutomation) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = !sideTasks.isChangingAutomation,
                    onClick = {
                        coroutineScope.launch {
                            try {
                                sideTasks.confirmAutomation(control)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                automationError = error.message ?: "The session control was not changed."
                            }
                        }
                    },
                ) { Text(control.actionTitle) }
            },
        )
    }

    automationError?.let { message ->
        AlertDialog(
            onDismissRequest = { automationError = null },
            title = { Text("Session control not changed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { automationError = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun HermesBtwHeader(onExpand: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HermesBtwLabel()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Expand, contentDescription = "Expand side question", modifier = Modifier.size(17.dp))
        }
        HermesBtwCloseButton(onClose)
    }
}

@Composable
private fun HermesBtwLabel() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Side question" },
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
            Text(
                text = "BTW",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun HermesBtwCloseButton(onClose: () -> Unit) {
    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Outlined.Close, contentDescription = "Close side question", modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun HermesBtwAnswer(state: HermesBtw.State, isFullScreen: Boolean) {
    when (state) {
        HermesBtw.State.Waiting -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.onSurfaceVariant) {}
                Text(
                    text = "Answering from a snapshot of this chat",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is HermesBtw.State.Answered -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.text.isBlank()) {
                    Text(
                        text = "No answer produced.",
                        style = if (isFullScreen) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MessageMarkdown(content = state.text, wrapCodeLines = true)
                }
                Text(
                    text = "From a snapshot · no tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HermesBtw.State.Unavailable -> {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Answer unavailable. The stream reconnected without this answer. Ask again with /btw."
                },
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(19.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Answer unavailable", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "The stream reconnected without this answer. Ask again with /btw.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HermesBtwRunPill(status: HermesBtwRunStatus) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = status.accessibilityLabel
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.onSurfaceVariant) {}
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun automationStatus(status: String): String = when (status) {
    "active" -> "Active"
    "paused" -> "Paused"
    else -> status
}

private fun backgroundPromptSummary(prompt: String): String {
    if (prompt.codePointCount(0, prompt.length) <= 80) return prompt
    val end = prompt.offsetByCodePoints(0, 80)
    return "${prompt.substring(0, end)}..."
}
