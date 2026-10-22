package io.github.jcartu.hermex.model

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    // MARK: - Provider quota tolerant decoding

    @Test
    fun `decodes account limits with missing fields and unknown keys`() {
        val fixture =
            """
            { "ok": true, "provider": "openai-codex", "display_name": "Codex",
              "supported": true, "status": "available",
              "future_field": { "nested": 1 },
              "account_limits": {
                "plan": "Plus", "available": true, "fetched_at": "2026-09-06T02:56:37.848820Z",
                "windows": [
                  { "label": "5h window", "remaining_percent": 12.5, "reset_at": "2026-09-06T07:55:41Z", "detail": "x" },
                  { "label": "daily", "used_percent": "40" }
                ],
                "details": ["line"]
              } }
            """.trimIndent()

        val response = json.decodeFromString(ProviderQuotaResponse.serializer(), fixture)
        assertEquals("available", response.status)
        assertEquals("Codex", response.displayName)
        val limits = response.accountLimits!!
        assertEquals("Plus", limits.plan)
        assertEquals(2, limits.windows?.size)
        assertEquals(12.5, limits.windows!![0].remainingPercent!!, 0.0)
        assertNull(limits.windows!![0].usedPercent)
        assertEquals(40.0, limits.windows!![1].usedPercent!!, 0.0)
        assertNull(limits.windows!![1].remainingPercent)
        assertEquals(
            Instant.parse("2026-09-06T02:56:37.848820Z"),
            providerQuotaDate(limits.fetchedAt),
        )
    }

    @Test
    fun `decodes openrouter credits family`() {
        val fixture =
            """
            { "status": "available", "provider": "openrouter", "display_name": "OpenRouter",
              "quota": { "limit_remaining": 4.32, "usage": 5.68, "limit": 10 } }
            """.trimIndent()

        val response = json.decodeFromString(ProviderQuotaResponse.serializer(), fixture)
        assertEquals(4.32, response.quota!!.limitRemaining!!, 0.0)
        assertNull(response.accountLimits)
    }

    @Test
    fun `unsupported status hides the card and unavailable hides account limits`() {
        val unsupported = json.decodeFromString(
            ProviderQuotaResponse.serializer(),
            """{ "status": "unsupported", "provider": "x" }""",
        )
        assertNull(ProviderLimitCard.build("x", unsupported, Instant.EPOCH))

        val unavailable = json.decodeFromString(
            ProviderQuotaResponse.serializer(),
            """{ "status": "available", "provider": "anthropic",
                 "account_limits": { "available": false, "unavailable_reason": "no probe" } }""",
        )
        assertNull(ProviderLimitCard.build("anthropic", unavailable, Instant.EPOCH))
    }

    @Test
    fun `card building tiers remaining percent and clamps out-of-range`() {
        assertEquals(ProviderLimitTint.CRITICAL, providerLimitTint(10.0))
        assertEquals(ProviderLimitTint.WARNING, providerLimitTint(25.0))
        assertEquals(ProviderLimitTint.NORMAL, providerLimitTint(26.0))
        assertEquals(ProviderLimitTint.NORMAL, providerLimitTint(null))
        assertEquals("2% left", providerLimitPercentLeftText(2.4))
        assertEquals("0% left", providerLimitPercentLeftText(-3.0))
        assertEquals(1.0, providerLimitFraction(150.0), 0.0)
    }

    @Test
    fun `selection keeps active provider first and only keyed candidates`() {
        val response = ProvidersResponse(
            providers = listOf(
                ProviderSummary(id = "openrouter", hasKey = true),
                ProviderSummary(id = "anthropic", hasKey = false),
                ProviderSummary(id = "openai-codex", hasKey = true),
            ),
            activeProvider = "OPENAI-CODEX",
        )
        assertEquals(
            listOf("openai-codex", "openrouter"),
            providerQuotaSelection(response),
        )
    }

    // MARK: - Usage window aggregation

    @Test
    fun `daily buckets fill gaps and pad the leading edge to the window`() {
        val rows = listOf(
            InsightsDailyToken(date = "2026-09-01", inputTokens = 10, outputTokens = 5),
            InsightsDailyToken(date = "2026-09-04", sessions = 2, cost = 0.1),
        )
        val buckets = UsageBuckets.daily(rows, windowDays = 7)
        // 4 real days + 3 leading pad days = 7
        assertEquals(7, buckets.size)
        assertEquals("Sep 1", buckets[3].label)
        assertTrue(buckets[3].processedTokens == 15)
        assertEquals(2, buckets[6].sessions)
        assertTrue(buckets[6].hasActivity)
        assertTrue(!buckets[4].hasActivity)
    }

    @Test
    fun `daily buckets return empty for a shape change`() {
        assertTrue(UsageBuckets.daily(listOf(InsightsDailyToken(date = "not-a-date")), 7).isEmpty())
        assertTrue(UsageBuckets.daily(emptyList(), 7).isEmpty())
    }

    @Test
    fun `hourly buckets span 0-23 zero-filled with bounds enforced`() {
        val rows = listOf(
            InsightsActivityByHour(hour = 9, sessions = 3),
            InsightsActivityByHour(hour = 24, sessions = 99),
            InsightsActivityByHour(hour = -1, sessions = 99),
            InsightsActivityByHour(hour = 17),
        )
        val buckets = UsageBuckets.hourly(rows)
        assertEquals(24, buckets.size)
        assertEquals(0, buckets.first().id)
        assertEquals(23, buckets.last().id)
        assertEquals(3, buckets[9].sessions)
        assertEquals(0, buckets[17].sessions)
    }

    @Test
    fun `hourly buckets return empty when nothing decodes`() {
        assertTrue(UsageBuckets.hourly(listOf(InsightsActivityByHour(hour = 25))).isEmpty())
    }

    @Test
    fun `segments stack tokens and single layers for cost and hours`() {
        val day = UsageBucket(
            id = 0,
            label = "x",
            detail = UsageBucketDetail.Day(input = 4, output = 6, cacheRead = 2, sessions = 1, cost = 0.5),
        )
        assertEquals(
            listOf(
                UsageChartSegment.INPUT to 4.0,
                UsageChartSegment.OUTPUT to 6.0,
                UsageChartSegment.CACHE_READ to 2.0,
            ),
            day.segments(UsageMetric.TOKENS),
        )
        assertEquals(listOf(UsageChartSegment.COST to 0.5), day.segments(UsageMetric.COST))
        val hour = UsageBucket(id = 3, label = "3 AM", detail = UsageBucketDetail.Hour(sessions = 7))
        assertEquals(listOf(UsageChartSegment.SESSIONS to 7.0), hour.segments(UsageMetric.TOKENS))
    }

    @Test
    fun `insights response decodes lossy scalars and unknown fields`() {
        val fixture =
            """
            { "period_days": 30, "total_tokens": "12345", "total_cost": "${'$'}1,234.50",
              "total_cache_hit_percent": "87.5",
              "models": [ { "model": "m", "cost_share": 60, "future": 1 } ],
              "daily_tokens": [ { "date": "2026-09-01", "input_tokens": "10" } ],
              "unknown_block": true }
            """.trimIndent()

        val response = json.decodeFromString(InsightsResponse.serializer(), fixture)
        assertEquals(30, response.periodDays)
        assertEquals(12345, response.totalTokens)
        assertEquals(1234.50, response.totalCost!!, 0.0)
        assertEquals(87.5, response.totalCacheHitPercent!!, 0.0)
        assertEquals("m", response.models?.single()?.model)
        assertEquals(60, response.models?.single()?.costShare)
        assertEquals(10, response.dailyTokens?.single()?.inputTokens)
        assertEquals("87.5%", insightsFormattedPercent(87.5))
    }
}
