package io.github.jcartu.hermex.model

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronRunHistoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `decodes a page and drops a malformed run without failing`() {
        val fixture =
            """
            {
              "job_id": "job-42",
              "total": 60,
              "offset": 50,
              "runs": [
                { "filename": "2026-08-17T09-00-00.md", "size": 4096, "modified": 1755421200,
                  "usage": { "model": "muse", "provider": "gcp", "estimated_cost_usd": 0.004,
                             "duration_seconds": 12.5, "input_tokens": 10, "output_tokens": 30, "total_tokens": 40 },
                  "future_run_field": true },
                { "filename": null, "size": 5 },
                "not-an-object",
                { "size": 999 }
              ]
            }
            """.trimIndent()

        val response = json.decodeFromString(CronRunHistoryResponse.serializer(), fixture)
        assertEquals("job-42", response.jobId)
        assertEquals(60, response.total)
        assertEquals(50, response.offset)
        assertEquals(1, response.runs.size)
        val run = response.runs.single()
        assertEquals("2026-08-17T09-00-00.md", run.filename)
        assertEquals(4096, run.size)
        assertEquals(Instant.ofEpochSecond(1_755_421_200), run.modified)
        assertEquals("muse", run.usage.model)
        assertEquals(0.004, run.usage.estimatedCostUsd!!, 0.0)
        assertEquals(40, run.usage.totalTokens)
    }

    @Test
    fun `non-finite mtime and missing usage decode to null`() {
        val fixture =
            """
            { "runs": [
                { "filename": "a.md", "modified": null },
                { "filename": "b.md", "modified": "not-a-number" }
            ] }
            """.trimIndent()

        val response = json.decodeFromString(CronRunHistoryResponse.serializer(), fixture)
        assertEquals(2, response.runs.size)
        assertNull(response.runs[0].modified)
        assertNull(response.runs[0].usage.model)
        assertNull(response.runs[1].modified)
        assertTrue(response.runs[1].usage.totalTokens == null)
    }

    @Test
    fun `run detail falls back to snippet and keeps usage`() {
        val fixture =
            """
            { "job_id": "j", "filename": "a.md", "snippet": "tail of output",
              "usage": { "total_tokens": "88" } }
            """.trimIndent()

        val detail = json.decodeFromString(CronRunDetailResponse.serializer(), fixture)
        assertEquals("j", detail.jobId)
        assertEquals("a.md", detail.filename)
        assertNull(detail.content)
        assertEquals("tail of output", detail.snippet)
        assertEquals(88, detail.usage.totalTokens)
    }
}
