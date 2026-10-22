package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The segments above the Tasks list. Selection is view state: it is never
 * persisted and never travels between servers.
 * Port of `Features/Tasks/TaskAgenda.swift`.
 */
enum class TaskFilter {
    ALL,
    LIVE,
    PAUSED,
    ERRORS;

    /** The segment label, count included — a segmented control can only show one string per segment. */
    fun title(count: Int): String = when (this) {
        ALL -> "All $count"
        LIVE -> "Live $count"
        PAUSED -> "Paused $count"
        ERRORS -> "Errors $count"
    }
}

/**
 * Agenda buckets, declared in the order they appear on screen: what is
 * happening now, then what happens next, then what is broken, then what is
 * dormant.
 */
enum class TaskAgendaGroup {
    NOW,
    LATER_TODAY,
    TOMORROW,
    THIS_WEEK,
    LATER,
    NEEDS_ATTENTION,
    PAUSED;

    val title: String
        get() = when (this) {
            NOW -> "Now"
            LATER_TODAY -> "Later Today"
            TOMORROW -> "Tomorrow"
            THIS_WEEK -> "This Week"
            LATER -> "Later"
            NEEDS_ATTENTION -> "Needs Attention"
            PAUSED -> "Paused"
        }
}

/** One rendered group. Empty groups are never built, so a section always has rows. */
data class TaskAgendaSection(
    val group: TaskAgendaGroup,
    val jobs: List<CronJob>,
)

object TaskAgenda {
    /**
     * The single group a job belongs to.
     *
     * Precedence is what keeps the buckets disjoint: a running job is always
     * "Now", a failed last run is always "Needs Attention" whatever the job's
     * enabled state, and everything that is neither running nor scheduled
     * falls to "Paused".
     */
    fun group(
        job: CronJob,
        isRunning: Boolean,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TaskAgendaGroup {
        if (isRunning) return TaskAgendaGroup.NOW
        if (job.hasFailedRun) return TaskAgendaGroup.NEEDS_ATTENTION
        if (!job.isLive) return TaskAgendaGroup.PAUSED
        val next = job.nextRunAt?.date ?: return TaskAgendaGroup.LATER

        val startOfToday: Instant = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val startOfTomorrow = startOfToday + Duration.ofDays(1)
        val startOfDayAfterTomorrow = startOfToday + Duration.ofDays(2)
        val weekHorizon = startOfToday + Duration.ofDays(7)

        // An overdue next run — the server's schedule lagging behind the clock
        // — still reads as today's business rather than sinking to "Later".
        if (next < startOfTomorrow) return TaskAgendaGroup.LATER_TODAY
        if (next < startOfDayAfterTomorrow) return TaskAgendaGroup.TOMORROW
        if (next < weekHorizon) return TaskAgendaGroup.THIS_WEEK
        return TaskAgendaGroup.LATER
    }

    /** Whether a job survives the selected filter. */
    fun matches(job: CronJob, filter: TaskFilter, isRunning: Boolean): Boolean =
        when (filter) {
            TaskFilter.ALL -> true
            TaskFilter.LIVE -> isRunning || job.isLive
            TaskFilter.PAUSED -> !isRunning && !job.isLive
            TaskFilter.ERRORS -> job.hasFailedRun
        }

    fun count(
        filter: TaskFilter,
        jobs: List<CronJob>,
        runningJobIds: Set<String>,
    ): Int = jobs.count { job -> matches(job, filter, isRunning(job, runningJobIds)) }

    /**
     * The filtered, grouped, sorted agenda. Groups keep their declared order;
     * within a group jobs sort by next run, soonest first, then by name.
     */
    fun sections(
        jobs: List<CronJob>,
        runningJobIds: Set<String>,
        filter: TaskFilter,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TaskAgendaSection> {
        val grouped: MutableMap<TaskAgendaGroup, MutableList<CronJob>> = linkedMapOf()

        for (job in jobs) {
            val running = isRunning(job, runningJobIds)
            if (!matches(job, filter, isRunning = running)) continue
            val group = group(job, isRunning = running, now = now, zone = zone)
            grouped.getOrPut(group) { mutableListOf() }.add(job)
        }

        return TaskAgendaGroup.entries.mapNotNull { group ->
            val groupJobs = grouped[group]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TaskAgendaSection(group = group, jobs = groupJobs.sortedWith(::precedes))
        }
    }

    fun isRunning(job: CronJob, runningJobIds: Set<String>): Boolean {
        val jobId = job.jobId ?: return false
        return jobId in runningJobIds
    }

    private fun precedes(left: CronJob, right: CronJob): Int {
        val leftDate = left.nextRunAt?.date
        val rightDate = right.nextRunAt?.date
        return when {
            leftDate != null && rightDate != null && leftDate != rightDate -> leftDate.compareTo(rightDate)
            leftDate != null && rightDate == null -> -1
            leftDate == null && rightDate != null -> 1
            else -> left.displayName.compareTo(right.displayName, ignoreCase = true)
        }
    }
}

// MARK: - CronJob agenda extensions (port of the `extension CronJob` in TaskAgenda.swift)

/** Live means the server will fire this job again. `enabled` and `state` are the same fact surfaced twice. */
val CronJob.isLive: Boolean
    get() = enabled != false && state != "paused"

/** True when the last run, or its delivery, failed. */
val CronJob.hasFailedRun: Boolean
    get() {
        if (lastStatus == "error") return true
        if (!lastError.isNullOrEmpty()) return true
        if (!lastDeliveryError.isNullOrEmpty()) return true
        return false
    }

/**
 * One readable line explaining the failure, safe for a list row: escapes
 * stripped, first meaningful line only. The full text lives on Task Detail.
 */
val CronJob.failureSummary: String?
    get() {
        for (candidate in listOf(lastError, lastDeliveryError)) {
            if (candidate.isNullOrEmpty()) continue
            candidate.firstLineSummary()?.let { return it }
        }
        return null
    }

/**
 * The schedule in English, or `null` when the expression is not one the
 * humaniser recognises.
 */
val CronJob.scheduleDescription: CronScheduleDescription?
    get() = CronScheduleHumanizer.describe(editableScheduleText ?: scheduleText)

/**
 * Pause/resume control state, shared by the detail screen and its header card.
 * Resume is a circled triangle, never `play.fill`: beside "Run now" the same
 * solid triangle twice would say the two buttons do the same thing.
 */
val CronJob.shouldResume: Boolean
    get() = status == CronJobStatus.PAUSED || status == CronJobStatus.OFF

val CronJob.pauseResumeTitle: String
    get() = if (shouldResume) "Resume" else "Pause"
