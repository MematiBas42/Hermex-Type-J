package io.github.jcartu.hermex.ui.feature.insights

import io.github.jcartu.hermex.model.InsightsResponse
import io.github.jcartu.hermex.model.ProviderQuotaResponse
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeInsightsClient(
    var insightsResult: Result<InsightsResponse> = Result.success(InsightsResponse()),
    var sessionsResult: Result<SessionsResponse> = Result.success(SessionsResponse()),
    var providersResult: Result<ProvidersResponse> = Result.success(ProvidersResponse()),
    var quotaResult: Result<ProviderQuotaResponse> = Result.success(ProviderQuotaResponse(status = "available")),
) : InsightsDataClient {

    override val insightsFeatures: InsightsFeatures = InsightsFeatures.webui

    var quotaCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun sessions(): SessionsResponse = sessionsResult.getOrThrow()

    override suspend fun insights(days: Int): InsightsResponse = insightsResult.getOrThrow()

    override suspend fun providers(): ProvidersResponse = providersResult.getOrThrow()

    override suspend fun providerQuota(provider: String, refresh: Boolean): ProviderQuotaResponse {
        quotaCalls += provider to refresh
        return quotaResult.getOrThrow()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun serverInsights() = InsightsResponse(
        periodDays = 7,
        totalSessions = 4,
        totalMessages = 12,
        totalInputTokens = 100,
        totalOutputTokens = 50,
        totalTokens = 150,
        totalCost = 0.75,
        totalCacheReadTokens = 30,
        totalCacheHitPercent = 87.5,
        dailyTokens = listOf(
            io.github.jcartu.hermex.model.InsightsDailyToken(date = "2026-09-01", inputTokens = 10, outputTokens = 5),
            io.github.jcartu.hermex.model.InsightsDailyToken(date = "2026-09-02", inputTokens = 20, outputTokens = 10, cost = 0.1),
        ),
        activityByDay = listOf(io.github.jcartu.hermex.model.InsightsActivityByDay(day = "Monday", sessions = 5)),
        activityByHour = listOf(io.github.jcartu.hermex.model.InsightsActivityByHour(hour = 14, sessions = 3)),
        models = listOf(
            io.github.jcartu.hermex.model.InsightsModelBreakdown(
                model = "muse", cost = 0.4, costShare = 60, tokenShare = 55,
                totalTokens = 90, sessions = 2, cacheHitPercent = 80.0,
            ),
        ),
    )

    @Test
    fun `server insights populate the window aggregates and chart buckets`() = runTest(dispatcher) {
        val client = FakeInsightsClient(insightsResult = Result.success(serverInsights()))
        val vm = InsightsViewModel(client)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(InsightsDataSource.SERVER, state.dataSource)
        assertEquals(150, state.totalTokens)
        assertEquals(4, state.sessionCount)
        assertEquals(0.75, state.estimatedCost, 0.0)
        assertEquals(30, state.totalCacheReadTokens)
        // Cost > 0 opens on the cost metric; buckets span 7 days with padding.
        assertEquals(io.github.jcartu.hermex.model.UsageMetric.COST, state.metric)
        assertEquals(7, state.chartBuckets.size)
        assertTrue(state.chartBuckets.any { it.hasActivity })
        assertEquals("Monday", state.peakDay?.day)
        assertEquals(14, state.peakHour?.hour)
        // Totals: processed, cached input, cache hit rate, input, output, messages, busiest weekday+hour.
        assertEquals("processedTokens", state.totalsCells.first().id)
        assertTrue(state.totalsCells.any { it.id == "cacheHitRate" && it.value == "87.5%" })
        assertTrue(state.totalsCells.any { it.id == "busiestWeekday" })
        assertTrue(state.modelBreakdowns.isNotEmpty())
        assertTrue(state.topSessions.isEmpty())
    }

    @Test
    fun `the today window plots 24 hourly buckets with no metric`() = runTest(dispatcher) {
        val client = FakeInsightsClient(insightsResult = Result.success(serverInsights()))
        val vm = InsightsViewModel(client)
        advanceUntilIdle()

        vm.selectTimeframe(AnalyticsTimeframe.TODAY)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadedTimeframe.isHourly)
        assertFalse(state.showsMetricToggle)
        assertEquals(24, state.chartBuckets.size)
        assertEquals("Sessions", state.heroFigure.label)
        assertTrue(state.hourlyChartNote != null)
    }

    @Test
    fun `a failed insights read falls back to session metadata`() = runTest(dispatcher) {
        val client = FakeInsightsClient(
            insightsResult = Result.failure(RuntimeException("insights down")),
            sessionsResult = Result.success(
                SessionsResponse(
                    sessions = listOf(
                        SessionSummary(
                            sessionId = "s1", inputTokens = 20, outputTokens = 5,
                            messageCount = 6, estimatedCost = 0.05,
                            lastMessageAt = (System.currentTimeMillis() / 1000).toDouble(),
                        ),
                    ),
                ),
            ),
        )
        val vm = InsightsViewModel(client)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(InsightsDataSource.LOCAL_FALLBACK, state.dataSource)
        assertEquals(25, state.totalTokens)
        assertEquals(6, state.totalMessages)
        assertEquals(1, state.topSessions.size)
        assertTrue(state.sourceDescription.contains("fallback"))
    }

    @Test
    fun `quota probes load separately and failures stay silent`() = runTest(dispatcher) {
        val client = FakeInsightsClient(
            providersResult = Result.success(
                ProvidersResponse(
                    providers = listOf(io.github.jcartu.hermex.model.ProviderSummary(id = "openai-codex", hasKey = true)),
                    activeProvider = "openai-codex",
                ),
            ),
            quotaResult = Result.success(
                ProviderQuotaResponse(
                    status = "available",
                    provider = "openai-codex",
                    displayName = "Codex",
                    accountLimits = io.github.jcartu.hermex.model.ProviderAccountLimits(
                        plan = "Plus",
                        available = true,
                        fetchedAt = "2026-09-06T07:55:41Z",
                        windows = listOf(
                            io.github.jcartu.hermex.model.ProviderQuotaWindow(
                                label = "5h window",
                                remainingPercent = 12.0,
                                resetAt = "2026-09-06T07:55:41Z",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val vm = InsightsViewModel(client)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.showsLimits)
        assertEquals(1, state.limitCards.size)
        assertEquals("Codex", state.limitCards.single().title)
        assertEquals("Plus", state.limitCards.single().plan)
        assertEquals("12% left", state.limitCards.single().rows.single().amount)
        assertEquals(listOf("openai-codex" to false), client.quotaCalls)
    }

    @Test
    fun `a superseded timeframe load cannot overwrite a newer one`() = runTest(dispatcher) {
        val client = FakeInsightsClient(insightsResult = Result.success(serverInsights()))
        val vm = InsightsViewModel(client)
        advanceUntilIdle()

        // The second load (30 days) wins even if the first (7 days) answers later.
        vm.selectTimeframe(AnalyticsTimeframe.LAST_30_DAYS)
        advanceUntilIdle()
        assertEquals(AnalyticsTimeframe.LAST_30_DAYS, vm.uiState.value.loadedTimeframe)
        assertFalse(vm.uiState.value.isLoading)
    }
}
