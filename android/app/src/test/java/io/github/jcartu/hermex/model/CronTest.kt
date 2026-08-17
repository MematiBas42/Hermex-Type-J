package io.github.jcartu.hermex.model

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun decodesRealisticSnakeCaseJobsStatusAndOutput() {
        val jobsFixture =
            """
            {
              "jobs": [
                {
                  "id": "job-42",
                  "name": "Morning briefing",
                  "prompt": "Summarize overnight activity",
                  "schedule": {
                    "kind": "cron",
                    "expression": "0 9 * * *"
                  },
                  "schedule_display": "Every day at 9:00 AM",
                  "enabled": true,
                  "state": "scheduled",
                  "next_run_at": "2026-08-18T09:00:00Z",
                  "last_run_at": 1786957200,
                  "last_status": "success",
                  "last_error": null,
                  "last_delivery_error": null,
                  "repeat": {
                    "times": 30,
                    "completed": 4
                  },
                  "deliver": "local",
                  "skills": ["weather", "calendar"],
                  "model": "claude-sonnet-4",
                  "provider": "anthropic",
                  "profile": "work",
                  "toast_notifications": true
                }
              ]
            }
            """.trimIndent()

        val jobs = json.decodeFromString(CronJobsResponse.serializer(), jobsFixture)
        val job = jobs.jobs!!.single()
        assertEquals("job-42", job.jobId)
        assertEquals("Morning briefing", job.name)
        assertEquals("Summarize overnight activity", job.prompt)
        assertEquals("cron", job.schedule?.kind)
        assertEquals("0 9 * * *", job.schedule?.expression)
        assertEquals("Every day at 9:00 AM", job.scheduleText)
        assertEquals("0 9 * * *", job.editableScheduleText)
        assertEquals(Instant.parse("2026-08-18T09:00:00Z"), job.nextRunAt?.date)
        assertEquals("success", job.lastStatus)
        assertEquals(30, job.repeatInfo?.times)
        assertEquals(4, job.repeatInfo?.completed)
        assertEquals(listOf("weather", "calendar"), job.skills)
        assertEquals("anthropic", job.provider)
        assertTrue(job.toastNotifications == true)
        assertEquals(CronJobStatus.ACTIVE, job.status)
        assertEquals("Morning briefing", job.displayName)

        val statusFixture =
            """
            {
              "job_id": "job-42",
              "running": {
                "job-42": 3.25,
                "job-77": 0
              },
              "elapsed": "12.5",
              "error": null
            }
            """.trimIndent()

        val status = json.decodeFromString(CronStatusResponse.serializer(), statusFixture)
        assertEquals("job-42", status.jobId)
        assertNull(status.running)
        assertEquals(mapOf("job-42" to 3.25, "job-77" to 0.0), status.runningJobs)
        assertEquals(12.5, status.elapsed!!, 0.0)

        val outputFixture =
            """
            {
              "job_id": "job-42",
              "outputs": [
                {
                  "filename": "2026-08-17T09-00-00.md",
                  "content": "# Morning briefing\nAll systems nominal."
                }
              ]
            }
            """.trimIndent()

        val output = json.decodeFromString(CronOutputResponse.serializer(), outputFixture)
        assertEquals("job-42", output.jobId)
        assertEquals("2026-08-17T09-00-00.md", output.outputs?.single()?.filename)
        assertEquals("# Morning briefing\nAll systems nominal.", output.outputs?.single()?.content)
        assertEquals("2026-08-17T09-00-00.md", output.outputs?.single()?.id)
    }

    @Test
    fun ignoresUnknownExtraFields() {
        val fixture =
            """
            {
              "jobs": [
                {
                  "id": "future-safe",
                  "prompt": "Run safely",
                  "schedule": "@hourly",
                  "enabled": true,
                  "future_job_field": {"nested": true}
                }
              ],
              "future_response_field": [1, 2, 3]
            }
            """.trimIndent()

        val response = json.decodeFromString(CronJobsResponse.serializer(), fixture)
        val job = response.jobs!!.single()
        assertEquals("future-safe", job.jobId)
        assertEquals("@hourly", job.schedule?.expression)
        assertEquals("@hourly", job.schedule?.displayText)
    }

    @Test
    fun coercesLossyScalarsAndFlexibleNumbers() {
        val jobFixture =
            """
            {
              "id": 42,
              "name": false,
              "prompt": 99.5,
              "schedule": {
                "kind": 1,
                "every": 15
              },
              "enabled": "yes",
              "next_run_at": "1750000000.5",
              "toast_notifications": 0
            }
            """.trimIndent()

        val job = json.decodeFromString(CronJob.serializer(), jobFixture)
        assertEquals("42", job.jobId)
        assertEquals("false", job.name)
        assertEquals("99.5", job.prompt)
        assertEquals("1", job.schedule?.kind)
        assertEquals("15", job.schedule?.every)
        assertTrue(job.enabled == true)
        assertFalse(job.toastNotifications ?: true)
        assertEquals(Instant.ofEpochSecond(1_750_000_000, 500_000_000), job.nextRunAt?.date)

        val status = json.decodeFromString(
            CronStatusResponse.serializer(),
            """{"job_id":"job-42","running":true,"elapsed":" 7.75 "}""",
        )
        assertTrue(status.running == true)
        assertNull(status.runningJobs)
        assertEquals(7.75, status.elapsed!!, 0.0)
    }
}
