package io.github.jcartu.hermex.model

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CronRecentCompletionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `decodes completions and drops entries without a usable time`() {
        val fixture =
            """
            { "completions": [
                { "job_id": "j1", "name": "Backup", "status": "ok", "completed_at": 1755000000,
                  "session_id": "s1", "message_count": 12, "extra": true },
                { "job_id": "j2", "status": "error", "completed_at": "unparseable" },
                { "name": "NoTime" }
            ] }
            """.trimIndent()

        val response = json.decodeFromString(CronRecentCompletionsResponse.serializer(), fixture)
        assertEquals(1, response.completions?.size)
        val completion = response.completions!!.single()
        assertEquals("j1", completion.jobId)
        assertEquals("Backup", completion.displayName)
        assertEquals(false, completion.didFail)
        assertEquals(Instant.ofEpochSecond(1_755_000_000), completion.completedAt)
        assertEquals("s1", completion.sessionId)
        assertEquals(12, completion.messageCount)
    }

    @Test
    fun `newestFirst sorts and failed status reads as failure`() {
        val older = CronRecentCompletion(
            jobId = "a",
            name = "A",
            status = "ok",
            completedAt = Instant.ofEpochSecond(100),
        )
        val newer = CronRecentCompletion(
            jobId = "b",
            name = "B",
            status = "error",
            completedAt = Instant.ofEpochSecond(200),
        )
        val sorted = CronRecentCompletion.newestFirst(listOf(older, newer))
        assertEquals(listOf("b", "a"), sorted.map { it.jobId })
        assertTrue(newer.didFail)
    }
}
