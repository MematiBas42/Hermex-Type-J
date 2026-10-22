package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.CronJob
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Blue used for the "Running" badge — mirrors iOS `.blue`. */
internal val RunningBlue = Color(0xFF4A9EFF)

/** Green used for scheduled groups — mirrors iOS `.green`. */
internal val ScheduledGreen = Color(0xFF34C759)

/** Orange used for paused/off badges — mirrors iOS `.orange`. */
internal val PausedOrange = Color(0xFFFF9F0A)

/**
 * One agenda row: a status rail, the job's name, and a single meta line whose
 * content is chosen by the group the row sits in. Model, provider, profile,
 * skills, deliver and the prompt live on Task Detail — a list that repeats
 * them reads as a wall. The one exception is `profile`, set where a list mixes
 * every Profile's Tasks (a Hermes host, #1040), so two Tasks of one name can be
 * told apart.
 * Port of `Features/Tasks/CronJobRowView.swift`.
 */
@Composable
fun CronJobRowView(
    job: CronJob,
    group: TaskAgendaGroup,
    runningElapsed: Double?,
    profile: String? = null,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(3.dp)
                .height(34.dp)
                .background(tint(group), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    job.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (group == TaskAgendaGroup.PAUSED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (profile != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        profile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Text(
                metaText(job, group, runningElapsed, zone),
                style = MaterialTheme.typography.bodySmall,
                color = metaColor(group),
                maxLines = 1,
            )
        }
    }
}

/** The humanised schedule, or the server's own string when the expression is not one we recognise. */
private fun scheduleSentence(job: CronJob): String =
    job.scheduleDescription?.sentence ?: job.scheduleText ?: "Not available"

private fun scheduleRecurrence(job: CronJob): String =
    job.scheduleDescription?.recurrence ?: job.scheduleText ?: "Not available"

/** Today and tomorrow need only a clock time; the group header already says which day. */
private fun nextRunText(date: java.time.Instant, zone: ZoneId): String {
    val zoned = ZonedDateTime.ofInstant(date, zone)
    val today = ZonedDateTime.now(zone).toLocalDate()
    val day = zoned.toLocalDate()
    return if (day == today || day == today.plusDays(1)) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(zoned)
    } else {
        DateTimeFormatter.ofPattern("MMM d, h:mm a").format(zoned)
    }
}

private fun metaText(
    job: CronJob,
    group: TaskAgendaGroup,
    runningElapsed: Double?,
    zone: ZoneId,
): String = when (group) {
    TaskAgendaGroup.NOW -> {
        val elapsed = runningElapsed ?: return "Running"
        "Running · ${elapsedText(elapsed)}"
    }
    TaskAgendaGroup.NEEDS_ATTENTION -> {
        val headline = "Last run failed"
        val reason = job.failureSummary ?: return headline
        "$headline · $reason"
    }
    TaskAgendaGroup.PAUSED -> scheduleSentence(job)
    TaskAgendaGroup.LATER_TODAY,
    TaskAgendaGroup.TOMORROW,
    TaskAgendaGroup.THIS_WEEK,
    TaskAgendaGroup.LATER,
    -> {
        val next = job.nextRunAt?.date ?: return scheduleSentence(job)
        "${nextRunText(next, zone)} · ${scheduleRecurrence(job)}"
    }
}

/** Port of the Swift `elapsedText`: "42s" under a minute, "3m 5s" after. */
fun elapsedText(elapsed: Double): String {
    if (elapsed < 60) return "${Math.round(elapsed)}s"
    val minutes = (elapsed / 60).toInt()
    val seconds = (elapsed % 60).toInt()
    return "${minutes}m ${seconds}s"
}

@Composable
private fun tint(group: TaskAgendaGroup): Color = when (group) {
    TaskAgendaGroup.NOW -> RunningBlue
    TaskAgendaGroup.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
    TaskAgendaGroup.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskAgendaGroup.LATER_TODAY,
    TaskAgendaGroup.TOMORROW,
    TaskAgendaGroup.THIS_WEEK,
    TaskAgendaGroup.LATER,
    -> ScheduledGreen
}

@Composable
private fun metaColor(group: TaskAgendaGroup): Color = when (group) {
    TaskAgendaGroup.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
    TaskAgendaGroup.PAUSED -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusWord(group: TaskAgendaGroup): String = when (group) {
    TaskAgendaGroup.NOW -> "Running"
    TaskAgendaGroup.NEEDS_ATTENTION -> "Needs Attention"
    TaskAgendaGroup.PAUSED -> "Paused"
    TaskAgendaGroup.LATER_TODAY,
    TaskAgendaGroup.TOMORROW,
    TaskAgendaGroup.THIS_WEEK,
    TaskAgendaGroup.LATER,
    -> "Active"
}

@Composable
internal fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
