package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.WarningAmber
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
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobStatus
import io.github.jcartu.hermex.ui.components.SectionCard
import java.time.Instant

/**
 * Task Detail's first card: what this task is, when it runs next, and the two
 * controls an operator reaches for. When the last run failed the card takes an
 * error tint and carries the reason, because that is the only thing on the
 * screen worth reading first.
 * Port of `Features/Tasks/TaskDetailHeaderCard.swift`.
 */
@Composable
fun TaskDetailHeaderCard(
    job: CronJob,
    runningElapsed: Double?,
    isBusy: Boolean,
    canSeeFullOutput: Boolean,
    showsRunNow: Boolean = true,
    runNowState: RunNowState = RunNowState.IDLE,
    runNow: () -> Unit,
    togglePauseResume: () -> Unit,
    seeFullOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Title row: name + schedule sentence + status badge.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        job.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                    )
                    Text(
                        scheduleSentence(job),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(text = statusText(job, runningElapsed), color = statusColor(job, runningElapsed))
            }

            nextRunBlock(job, runningElapsed)

            job.failureSummary?.let { failure -> failureBlock(failure, canSeeFullOutput, seeFullOutput) }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        actionFooter(
            showsRunNow = showsRunNow,
            runNowState = runNowState,
            pauseResumeTitle = job.pauseResumeTitle,
            isBusy = isBusy,
            runNow = runNow,
            togglePauseResume = togglePauseResume,
        )
    }
}

/**
 * The countdown reads big; the absolute time sits under it for the reader who
 * wants the actual clock. Both are computed on render rather than ticked by a
 * timer.
 */
@Composable
private fun nextRunBlock(job: CronJob, runningElapsed: Double?) {
    val running = runningElapsed
    val next = job.nextRunAt
    val last = job.lastRunAt
    when {
        running != null -> Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                elapsedText(running),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = RunningBlue,
            )
            Text(
                "Running now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        next != null -> Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                CronScheduleHumanizer.countdown(next.date),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Next run · ${next.formatted}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        last != null -> Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Not scheduled",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Last run · ${last.formatted}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun failureBlock(
    failure: String,
    canSeeFullOutput: Boolean,
    seeFullOutput: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                failure,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
        if (canSeeFullOutput) {
            TextButton(onClick = seeFullOutput, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("See full output", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The card's two controls, as its bottom edge rather than as objects sitting
 * on it. Neither action is the primary one, so they carry equal weight.
 */
@Composable
private fun actionFooter(
    showsRunNow: Boolean,
    runNowState: RunNowState,
    pauseResumeTitle: String,
    isBusy: Boolean,
    runNow: () -> Unit,
    togglePauseResume: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showsRunNow) {
            FooterButton(
                title = "Run now",
                icon = {
                    if (runNowState == RunNowState.REQUESTED) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                enabled = runNowState == RunNowState.IDLE && !isBusy,
                action = runNow,
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider(
                Modifier
                    .width(1.dp)
                    .height(28.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        FooterButton(
            title = pauseResumeTitle,
            icon = {
                Icon(
                    if (pauseResumeTitle == "Resume") Icons.Filled.PlayCircle else Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            enabled = !isBusy,
            action = togglePauseResume,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FooterButton(
    title: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        Modifier
            .then(modifier)
            .clickable(enabled = enabled, onClick = action)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun scheduleSentence(job: CronJob): String =
    job.scheduleDescription?.sentence ?: job.scheduleText ?: "Not available"

private fun statusText(job: CronJob, runningElapsed: Double?): String =
    if (runningElapsed == null) job.status.label else "Running"

@Composable
private fun statusColor(job: CronJob, runningElapsed: Double?): Color {
    if (runningElapsed != null) return RunningBlue
    return when (job.status) {
        CronJobStatus.ACTIVE -> ScheduledGreen
        CronJobStatus.PAUSED, CronJobStatus.OFF -> PausedOrange
        CronJobStatus.ERROR -> MaterialTheme.colorScheme.error
        CronJobStatus.NEEDS_ATTENTION -> Color(0xFFFFD60A)
    }
}
