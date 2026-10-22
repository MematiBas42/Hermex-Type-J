package io.github.jcartu.hermex.ui.feature.chat.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.hermes.HermesDelegatedTail
import io.github.jcartu.hermex.model.hermes.HermesDelegatedWorker
import io.github.jcartu.hermex.model.hermes.HermesDelegationCompletion
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.chat.MessageMarkdown
import io.github.jcartu.hermex.ui.theme.SuccessDark
import io.github.jcartu.hermex.ui.theme.SuccessLight
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToLong

/** Immutable projection of `HermesDelegatedWorkView` in `Features/Chat/HermesDelegatedWorkView.swift`. */
data class HermesDelegatedWorkViewState(
    val availability: HermesDelegatedWork.Availability,
    val workers: List<HermesDelegatedWorker>,
    val omittedWorkerCount: Int,
    val activeCount: Int,
    val isRefreshing: Boolean,
    val loadingTail: HermesDelegatedWorker.Identity?,
    val tail: HermesDelegatedTail?,
    val interruptingWorker: HermesDelegatedWorker.Identity?,
    val interruptedWorker: HermesDelegatedWorker.Identity?,
    val errorMessage: String?,
) {
    companion object {
        /** Takes the plain-value snapshot the parent publishes through its own StateFlow. */
        fun from(work: HermesDelegatedWork): HermesDelegatedWorkViewState = HermesDelegatedWorkViewState(
            availability = work.availability,
            workers = work.workers,
            omittedWorkerCount = work.omittedWorkerCount,
            activeCount = work.activeCount,
            isRefreshing = work.isRefreshing,
            loadingTail = work.loadingTail,
            tail = work.tail,
            interruptingWorker = work.interruptingWorker,
            interruptedWorker = work.interruptedWorker,
            errorMessage = work.errorMessage,
        )
    }
}

/**
 * Port of `HermesDelegatedWorkButton` in `Features/Chat/HermesDelegatedWorkView.swift`.
 */
@Composable
fun HermesDelegatedWorkButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Delegated work, $count active workers. Shows worker status, recent output, and interrupt controls."
        },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.Group, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = min(count, 99).toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Port of `HermesDelegatedWorkView`. All controller operations are explicit callbacks; the
 * composable owns only the temporary confirmation action captured by a worker tap.
 */
@Composable
fun HermesDelegatedWorkView(
    state: HermesDelegatedWorkViewState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onLoadTail: (HermesDelegatedWorker) -> Unit,
    onHideTail: () -> Unit,
    onPrepareInterrupt: (HermesDelegatedWorker) -> HermesDelegatedWork.InterruptAction?,
    onInterrupt: (HermesDelegatedWork.InterruptAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingInterrupt by remember { mutableStateOf<HermesDelegatedWork.InterruptAction?>(null) }
    val paneCount = LocalPaneCount.current
    val contentMaximum = if (paneCount == 1) 640.dp else 840.dp

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.widthIn(max = contentMaximum).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Done") }
            Text(
                text = "Delegated work",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh delegated work")
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.widthIn(max = contentMaximum).fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { error ->
                item(key = "error") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (state.workers.isEmpty()) {
                item(key = "empty") {
                    DelegatedWorkEmptyState(state)
                }
            } else {
                item(key = "heading") {
                    Text(
                        text = "Active workers",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(
                    items = state.workers,
                    key = { worker ->
                        "${worker.identity.subagentID}:${worker.identity.startedAt}:${worker.identity.delegationID}"
                    },
                ) { worker ->
                    DelegatedWorkerRow(
                        worker = worker,
                        state = state,
                        onLoadTail = onLoadTail,
                        onHideTail = onHideTail,
                        onRequestInterrupt = {
                            pendingInterrupt = onPrepareInterrupt(worker)
                        },
                    )
                }
                item(key = "footer") {
                    Text(
                        text = if (state.omittedWorkerCount > 0) {
                            "Showing the first ${HermesDelegatedWork.MAXIMUM_WORKERS} workers. ${state.omittedWorkerCount} more are active."
                        } else {
                            "Tails load only when requested and are limited to 16 KB."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    pendingInterrupt?.let { action ->
        val worker = state.workers.firstOrNull { it.identity == action.worker }
        AlertDialog(
            onDismissRequest = { pendingInterrupt = null },
            title = {
                Text(worker?.let { "Interrupt “${it.goal}”?" } ?: "Interrupt this worker?")
            },
            text = {
                Text("This stops this delegated worker now. Hermex cannot resume it. The parent and sibling workers keep running.")
            },
            dismissButton = {
                TextButton(onClick = { pendingInterrupt = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingInterrupt = null
                        onInterrupt(action)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Interrupt worker") }
            },
        )
    }
}

@Composable
private fun DelegatedWorkEmptyState(state: HermesDelegatedWorkViewState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            Icons.Outlined.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Text("No active workers", style = MaterialTheme.typography.titleMedium)
        Text(
            text = when {
                state.isRefreshing -> "Refreshing delegated work…"
                state.availability == HermesDelegatedWork.Availability.UNSUPPORTED ->
                    "This Hermes host does not support delegated-work inspection."
                else -> "Workers appear here while the agent has delegated work in progress."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DelegatedWorkerRow(
    worker: HermesDelegatedWorker,
    state: HermesDelegatedWorkViewState,
    onLoadTail: (HermesDelegatedWorker) -> Unit,
    onHideTail: () -> Unit,
    onRequestInterrupt: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = (min(worker.depth, 4) * 16).dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = worker.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = worker.status.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                worker.model?.let {
                    WorkerMetadata(Icons.Outlined.Computer, it)
                }
                worker.lastTool?.let {
                    WorkerMetadata(Icons.Outlined.Build, it)
                } ?: worker.toolCount?.let {
                    WorkerMetadata(Icons.Outlined.Build, "$it tools")
                }
            }

            when {
                state.loadingTail == worker.identity -> {
                    Text(
                        text = "Loading tail…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.tail?.worker == worker.identity -> DelegatedTailView(state.tail)
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tailShown = state.tail?.worker == worker.identity
                val controlsDisabled = state.loadingTail != null || state.interruptingWorker != null
                if (maxWidth < 350.dp) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TailButton(tailShown, controlsDisabled, onHideTail) { onLoadTail(worker) }
                        InterruptControl(worker, state, onRequestInterrupt, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TailButton(tailShown, controlsDisabled, onHideTail) { onLoadTail(worker) }
                        Spacer(Modifier.weight(1f))
                        InterruptControl(worker, state, onRequestInterrupt)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerMetadata(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TailButton(
    shown: Boolean,
    disabled: Boolean,
    onHideTail: () -> Unit,
    onLoadTail: () -> Unit,
) {
    OutlinedButton(onClick = if (shown) onHideTail else onLoadTail, enabled = !disabled) {
        Icon(
            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(if (shown) "Hide tail" else "Show tail", modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun InterruptControl(
    worker: HermesDelegatedWorker,
    state: HermesDelegatedWorkViewState,
    onRequestInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.interruptedWorker == worker.identity) {
        Row(
            modifier = modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text = "Interrupt sent",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        OutlinedButton(
            onClick = onRequestInterrupt,
            enabled = worker.canInterrupt && state.interruptingWorker == null,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Interrupt")
        }
    }
}

@Composable
private fun DelegatedTailView(tail: HermesDelegatedTail) {
    if (!tail.available) {
        Text(
            text = "Live output is no longer available for this worker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectionContainer {
                Text(
                    text = tail.text.ifEmpty { "No output yet." },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (tail.truncated) {
                Text(
                    text = "Showing the latest 16 KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Port of `HermesDelegationCompletionCard`: a typed, durable transcript card. Opening the
 * report is delegated to the parent so sheets remain pane-aware.
 */
@Composable
fun HermesDelegationCompletionCard(
    completion: HermesDelegationCompletion,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = delegationTitle(completion)
    val summary = delegationSummary(completion)
    val iconColor = if (completion.hasFailures) Color(0xFFFF9800) else delegatedSuccessColor()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title. $summary. Opens the complete delegated work report." }
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = iconColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (completion.hasFailures) Icons.Filled.Warning else Icons.Outlined.Check,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).size(17.dp),
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                completion.delegationID?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } ?: Spacer(Modifier.weight(1f))
                Text(
                    text = "View results",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Port of `HermesDelegationResultsSheet`, expressed as pane content with explicit dismiss and
 * copy callbacks rather than owning a platform sheet.
 */
@Composable
fun HermesDelegationResultsSheet(
    completion: HermesDelegationCompletion,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Done") }
            Text(
                text = "Delegated work",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onCopy(completion.report) }, enabled = completion.report.isNotEmpty()) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (completion.hasFailures) Icons.Filled.Warning else Icons.Outlined.Check,
                    contentDescription = null,
                    tint = if (completion.hasFailures) Color(0xFFFF9800) else delegatedSuccessColor(),
                )
                Text(delegationSummary(completion), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            completion.delegationID?.let {
                SelectionContainer {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            if (completion.report.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(34.dp))
                    Text("No report", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Hermes recorded this completion without a result body.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                SelectionContainer {
                    MessageMarkdown(content = completion.report, wrapCodeLines = true)
                }
            }
        }
    }
}

private fun delegationTitle(completion: HermesDelegationCompletion): String = when {
    completion.taskCount == 1 && completion.hasFailures -> "1 worker finished"
    completion.taskCount == 1 -> "1 worker completed"
    completion.hasFailures -> "${completion.taskCount} workers finished"
    else -> "${completion.taskCount} workers completed"
}

private fun delegationSummary(completion: HermesDelegationCompletion): String {
    val parts = buildList {
        if (completion.completedCount > 0) {
            add(if (completion.completedCount == 1) "1 succeeded" else "${completion.completedCount} succeeded")
        }
        if (completion.failedCount > 0) {
            add(if (completion.failedCount == 1) "1 failed" else "${completion.failedCount} failed")
        }
        completion.durationSeconds?.let { add(formatDelegationDuration(it)) }
    }
    return parts.joinToString(" · ")
}

private fun formatDelegationDuration(seconds: Double): String {
    var remaining = seconds.coerceAtLeast(0.0).roundToLong()
    val hours = remaining / 3_600
    remaining %= 3_600
    val minutes = remaining / 60
    val secs = remaining % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (secs > 0 || isEmpty()) add("${secs}s")
    }.take(2).joinToString(" ")
}

@Composable
private fun delegatedSuccessColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SuccessDark else SuccessLight
