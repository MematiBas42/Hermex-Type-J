package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.CronRecentCompletion
import java.time.Duration
import java.time.Instant

/**
 * One row of the Tasks list's "Ran Recently" group: an ok/failed dot, the
 * job's name, and how long ago it finished. The list decides whether the row
 * links to Task Detail; this view only draws it.
 * Port of `Features/Tasks/TaskRecentRunRowView.swift`.
 */
@Composable
fun TaskRecentRunRowView(
    completion: CronRecentCompletion,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(8.dp)
                .background(
                    if (completion.didFail) MaterialTheme.colorScheme.error else ScheduledGreen,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            completion.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            relativeTime(completion.completedAt, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * "5m ago" style relative time, re-evaluated whenever the row is redrawn, which
 * a refresh forces by replacing the feed. Port of the Swift `.formatted(.relative)`.
 */
internal fun relativeTime(completedAt: Instant, now: Instant): String {
    val seconds = maxOf(0L, Duration.between(completedAt, now).seconds)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}

internal fun recentRunStatusWord(completion: CronRecentCompletion): String =
    if (completion.didFail) "Failed" else "Completed"
