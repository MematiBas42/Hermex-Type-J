package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.CronRunHistoryItem
import io.github.jcartu.hermex.model.usageFormattedCost
import io.github.jcartu.hermex.model.usageFormattedTokens
import io.github.jcartu.hermex.ui.components.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Task Detail's run history: every past run the server still holds, newest
 * first, a page at a time. A Hermes host's newest 100 runs are its one page.
 *
 * Rows carry only what the server actually reports for every run — when it
 * ran, and on webui how big its output was. Model, duration, tokens and cost
 * come from `usage`, which is empty for most webui runs, so they are appended
 * when present and take no space when not.
 * Port of `Features/Tasks/TaskRunHistorySection.swift`.
 */
@Composable
fun TaskRunHistorySection(
    runs: List<CronRunHistoryItem>,
    total: Int?,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    remainingCount: Int,
    errorMessage: String?,
    outcome: (CronRunHistoryItem) -> RunOutcome?,
    selectRun: (CronRunHistoryItem) -> Unit,
    retry: () -> Unit,
    loadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = sectionTitle(total), modifier = modifier) {
        Column {
            if (errorMessage != null) {
                InlineError(errorMessage, retry = retry, runsEmpty = runs.isEmpty())
            }

            if (runs.isEmpty()) {
                HistoryEmptyState(isLoading = isLoading, hasError = errorMessage != null)
            } else {
                runs.forEachIndexed { index, run ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectRun(run) }
                            .padding(vertical = 10.dp),
                    ) {
                        TaskRunHistoryRow(run = run, outcome = outcome(run))
                    }
                }

                if (canLoadMore) {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoadingMore, onClick = loadMore)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (remainingCount > 0) "Load $remainingCount more" else "Load more",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun sectionTitle(total: Int?): String =
    if (total == null) "Run History" else "Run History · $total"

@Composable
private fun HistoryEmptyState(isLoading: Boolean, hasError: Boolean) {
    when {
        isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Loading runs...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        !hasError -> Text(
            "This task has not produced any output yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/** History failing is a section-local problem: it never takes the screen down with it. */
@Composable
private fun InlineError(message: String, retry: () -> Unit, runsEmpty: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (runsEmpty) 0.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = retry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("Try Again", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * One run. The date is always there; everything after it is a decoration the
 * server may or may not have reported.
 */
@Composable
private fun TaskRunHistoryRow(
    run: CronRunHistoryItem,
    outcome: RunOutcome?,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(7.dp)
                .background(dotColor(outcome), CircleShape),
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                titleText(run),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            detailText(run, outcome)?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun titleText(run: CronRunHistoryItem): String {
    val date = run.date ?: return run.filename
    val zoned = ZonedDateTime.ofInstant(date, ZoneId.systemDefault())
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(zoned)
}

/**
 * The status plus `metaParts`, joined rather than laid out in columns so a run
 * with empty `usage` leaves no gap where its decorations would be. A completed
 * run's green dot already says so.
 */
private fun detailText(run: CronRunHistoryItem, outcome: RunOutcome?): String? {
    val parts = (if (outcome == RunOutcome.Completed) emptyList() else statusParts(outcome)) + metaParts(run)
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ")
}

/** Size first, because webui reports it for every run, then whatever `usage` happened to carry. */
private fun metaParts(run: CronRunHistoryItem): List<String> {
    val parts = mutableListOf<String>()

    val size = run.size
    if (size != null && size >= 0) {
        parts += byteCountText(size)
    }

    val duration = run.usage.durationSeconds
    if (duration != null && duration.isFinite() && duration > 0) {
        parts += durationText(duration)
    }

    val model = run.usage.model
    if (!model.isNullOrEmpty()) {
        parts += model
    }

    val tokens = run.usage.totalTokens
    if (tokens != null && tokens > 0) {
        parts += "${usageFormattedTokens(tokens)} tokens"
    }

    val cost = run.usage.estimatedCostUsd
    if (cost != null && cost.isFinite() && cost > 0) {
        parts += usageFormattedCost(cost)
    }

    return parts
}

/** The outcome in words, a failure with its reason. */
private fun statusParts(outcome: RunOutcome?): List<String> = when (outcome) {
    RunOutcome.Running -> listOf("Running")
    RunOutcome.Completed -> listOf("Completed")
    is RunOutcome.Failed -> listOf("Failed") + listOfNotNull(outcome.reason)
    null -> emptyList()
}

@Composable
private fun dotColor(outcome: RunOutcome?): Color = when (outcome) {
    RunOutcome.Running -> RunningBlue
    RunOutcome.Completed -> ScheduledGreen
    is RunOutcome.Failed -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.outline
}

/** Port of `size.formatted(.byteCount(style: .file))` — "12 KB", "1.2 MB". */
internal fun byteCountText(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${if (kb >= 100) kb.toInt().toString() else String.format(Locale.ROOT, "%.1f", kb).trimEnd('0').trimEnd('.')} KB"
    val mb = kb / 1024.0
    return "${String.format(Locale.ROOT, "%.1f", mb).trimEnd('0').trimEnd('.')} MB"
}

/** Port of `Duration.seconds(duration).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .narrow))`. */
internal fun durationText(duration: Double): String {
    val totalSeconds = duration.toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
