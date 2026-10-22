package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronDateValue
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAgendaTest {
    private val zone = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-09-02T12:00:00Z") // a Wednesday

    private fun job(
        id: String,
        name: String = id,
        enabled: Boolean? = true,
        state: String? = null,
        nextRunAt: Instant? = null,
        lastStatus: String? = "ok",
        lastError: String? = null,
        lastDeliveryError: String? = null,
        scheduleKind: String? = "cron",
    ) = CronJob(
        jobId = id,
        name = name,
        enabled = enabled,
        state = state,
        nextRunAt = nextRunAt?.let { CronDateValue(it) },
        lastStatus = lastStatus,
        lastError = lastError,
        lastDeliveryError = lastDeliveryError,
        schedule = io.github.jcartu.hermex.model.CronSchedule(kind = scheduleKind),
    )

    @Test
    fun `groups are disjoint by precedence - running, failed, paused, then time`() {
        val running = job("r", nextRunAt = now.plusSeconds(3_600))
        val failed = job("f", lastStatus = "error", lastError = "boom")
        val paused = job("p", enabled = false)
        val laterToday = job("t", nextRunAt = now.plusSeconds(7_200))
        val tomorrow = job("tm", nextRunAt = now.plusSeconds(90_000))
        val later = job("l")

        val runningIds = setOf("r")
        assertEquals(
            TaskAgendaGroup.NOW,
            TaskAgenda.group(running, isRunning = true, now = now, zone = zone),
        )
        assertEquals(
            TaskAgendaGroup.NEEDS_ATTENTION,
            TaskAgenda.group(failed, isRunning = false, now = now, zone = zone),
        )
        assertEquals(
            TaskAgendaGroup.PAUSED,
            TaskAgenda.group(paused, isRunning = false, now = now, zone = zone),
        )
        assertEquals(
            TaskAgendaGroup.LATER_TODAY,
            TaskAgenda.group(laterToday, isRunning = false, now = now, zone = zone),
        )
        assertEquals(
            TaskAgendaGroup.TOMORROW,
            TaskAgenda.group(tomorrow, isRunning = false, now = now, zone = zone),
        )
        assertEquals(
            TaskAgendaGroup.LATER,
            TaskAgenda.group(later, isRunning = false, now = now, zone = zone),
        )
        assertTrue(runningIds.contains("r"))
    }

    @Test
    fun `sections filter and keep group declaration order with soonest first`() {
        val jobs = listOf(
            job("a", "Alpha", nextRunAt = now.plusSeconds(200_000)),
            job("b", "Beta", nextRunAt = now.plusSeconds(50_000)),
            job("l", "Lima"),
            job("p", enabled = false),
            job("e", lastStatus = "error"),
        )
        val running = setOf<String>()

        val sections = TaskAgenda.sections(jobs, running, TaskFilter.ALL, now, zone)
        assertEquals(
            listOf(
                TaskAgendaGroup.TOMORROW,
                TaskAgendaGroup.THIS_WEEK,
                TaskAgendaGroup.LATER,
                TaskAgendaGroup.NEEDS_ATTENTION,
                TaskAgendaGroup.PAUSED,
            ),
            sections.map { it.group },
        )
        // Within the "Tomorrow" group, the soonest run first.
        assertEquals(listOf("Beta"), sections.first().jobs.map { it.name })

        val errors = TaskAgenda.sections(jobs, running, TaskFilter.ERRORS, now, zone)
        assertEquals(listOf(TaskAgendaGroup.NEEDS_ATTENTION), errors.map { it.group })

        val paused = TaskAgenda.sections(jobs, running, TaskFilter.PAUSED, now, zone)
        assertEquals(listOf(TaskAgendaGroup.PAUSED), paused.map { it.group })

        assertEquals(4, TaskAgenda.count(TaskFilter.LIVE, jobs, running))
        assertEquals(1, TaskAgenda.count(TaskFilter.PAUSED, jobs, running))
        assertEquals(5, TaskAgenda.count(TaskFilter.ALL, jobs, running))
    }

    @Test
    fun `overdue next run still reads as later today`() {
        val overdue = job("o", nextRunAt = now.minusSeconds(3_600))
        assertEquals(
            TaskAgendaGroup.LATER_TODAY,
            TaskAgenda.group(overdue, isRunning = false, now = now, zone = zone),
        )
    }

    @Test
    fun `delivery failure counts as a failed run`() {
        val job = job("d", lastDeliveryError = "send failed")
        assertTrue(job.hasFailedRun)
        assertEquals("send failed", job.failureSummary)
    }
}
