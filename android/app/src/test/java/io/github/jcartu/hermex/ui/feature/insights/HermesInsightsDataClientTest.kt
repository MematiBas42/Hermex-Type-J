package io.github.jcartu.hermex.ui.feature.insights

import io.github.jcartu.hermex.model.ProviderSummary
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.providerQuotaSelection
import io.github.jcartu.hermex.model.hermes.HermesInsightsDailyToken
import io.github.jcartu.hermex.model.hermes.HermesInsightsModelBreakdown
import io.github.jcartu.hermex.model.hermes.HermesInsightsWindow
import io.github.jcartu.hermex.model.hermes.HermesProviderQuota
import io.github.jcartu.hermex.net.hermes.HermesInsightsClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesInsightsDataClientTest {
    @Test
    fun `Hermes window maps every Usage total model and day`() {
        val window = HermesInsightsWindow(
            periodDays = 30,
            totalSessions = 7,
            totalMessages = 19,
            totalInputTokens = 100,
            totalOutputTokens = 25,
            totalTokens = 125,
            totalCost = 1.25,
            totalCacheReadTokens = 40,
            totalCacheHitPercent = 28.0,
            models = listOf(
                HermesInsightsModelBreakdown(
                    model = "gpt-6",
                    provider = "openai-codex",
                    sessions = 7,
                    inputTokens = 100,
                    outputTokens = 25,
                    cacheReadTokens = 40,
                    totalTokens = 125,
                    cost = 1.25,
                    cacheHitPercent = 28.0,
                    sessionShare = 100,
                    tokenShare = 100,
                    costShare = 100,
                ),
            ),
            dailyTokens = listOf(
                HermesInsightsDailyToken("2026-10-22", 100, 25, 40, 7, 1.25),
            ),
        )

        val mapped = window.toInsightsResponse()

        assertEquals(30, mapped.periodDays)
        assertEquals(125, mapped.totalTokens)
        assertEquals(19, mapped.totalMessages)
        assertEquals("openai-codex", mapped.models!!.single().provider)
        assertEquals(40, mapped.dailyTokens!!.single().cacheReadTokens)
        assertTrue(mapped.activityByHour!!.isEmpty())
    }

    @Test
    fun `companion quota maps and card selection keeps active provider first`() = runTest {
        val transport = mockk<HermesInsightsClient>()
        val providers = ProvidersResponse(
            providers = listOf(
                ProviderSummary(id = "anthropic", hasKey = true),
                ProviderSummary(id = "openai-codex", hasKey = true),
                ProviderSummary(id = "openrouter", hasKey = false),
            ),
            activeProvider = "ANTHROPIC",
        )
        coEvery { transport.providers() } returns providers
        coEvery { transport.providerQuota("anthropic", false) } returns HermesProviderQuota(
            ok = true,
            provider = "anthropic",
            displayName = "Anthropic",
            status = "available",
            accountLimits = HermesProviderQuota.AccountLimits(
                plan = "Max",
                available = true,
                windows = listOf(
                    HermesProviderQuota.Window(label = "Weekly", remainingPercent = 42.0),
                ),
            ),
        )
        val client = HermesInsightsDataClient(transport)

        assertEquals(listOf("anthropic", "openai-codex"), providerQuotaSelection(client.providers()))
        val quota = client.providerQuota("anthropic", false)
        assertEquals("Max", quota.accountLimits?.plan)
        assertEquals(42.0, quota.accountLimits?.windows?.single()?.remainingPercent)
    }
}
