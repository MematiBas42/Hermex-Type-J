package io.github.jcartu.hermex.ui.feature.insights

import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val api = mockk<HermesApi>()
    private val zone = ZoneOffset.UTC

    /** Fixed "now": 2026-08-17T12:00:00Z. */
    private val now: Instant = Instant.parse("2026-08-17T12:00:00Z")
    private val nowMillis = now.toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun epochSeconds(daysAgo: Long): Double =
        now.minusSeconds(daysAgo * 86_400).epochSecond.toDouble()

    private fun session(
        id: String,
        daysAgo: Long,
        model: String? = "gpt-fast",
        input: Int = 0,
        output: Int = 0,
        messages: Int = 0,
        cost: Double? = null,
        title: String? = null,
    ) = SessionSummary(
        sessionId = id,
        title = title,
        model = model,
        messageCount = messages,
        lastMessageAt = epochSeconds(daysAgo),
        inputTokens = input,
        outputTokens = output,
        estimatedCost = cost,
    )

    private val fixture = listOf(
        session("today", daysAgo = 0, model = "gpt-fast", input = 100, output = 50, messages = 4, cost = 0.5),
        session("thisWeek", daysAgo = 5, model = "gpt-smart", input = 300, output = 150, messages = 10, cost = 1.5),
        session("thisMonth", daysAgo = 20, model = "gpt-fast", input = 600, output = 300, messages = 6, cost = 2.0),
        session("ancient", daysAgo = 40, model = "gpt-old", input = 9_000, output = 1_000, messages = 99, cost = 9.0),
    )

    private fun makeViewModel(sessions: List<SessionSummary> = fixture): InsightsViewModel {
        coEvery { api.sessions(any(), any()) } returns SessionsResponse(sessions = sessions)
        return InsightsViewModel(api, nowMillis = { nowMillis }, zone = zone)
    }

    @Test
    fun `default timeframe aggregates the last 30 days`() {
        val viewModel = makeViewModel()

        viewModel.loadIfNeeded()

        val state = viewModel.uiState.value
        assertTrue(state.hasLoaded)
        assertEquals(AnalyticsTimeframe.LAST_30_DAYS, state.selectedTimeframe)
        assertEquals(3, state.sessionCount) // "ancient" excluded
        assertEquals(1_000L, state.totalInputTokens)
        assertEquals(500L, state.totalOutputTokens)
        assertEquals(1_500L, state.totalTokens)
        assertEquals(20L, state.totalMessages)
        assertEquals(4.0, state.estimatedCost, 1e-9)
    }

    @Test
    fun `timeframe switch re-derives locally without refetching`() {
        val viewModel = makeViewModel()
        viewModel.loadIfNeeded()

        viewModel.selectTimeframe(AnalyticsTimeframe.TODAY)
        assertEquals(1, viewModel.uiState.value.sessionCount)
        assertEquals(150L, viewModel.uiState.value.totalTokens)

        viewModel.selectTimeframe(AnalyticsTimeframe.ALL_TIME)
        assertEquals(4, viewModel.uiState.value.sessionCount)
        assertEquals(11_500L, viewModel.uiState.value.totalTokens)

        coVerify(exactly = 1) { api.sessions(any(), any()) }
    }

    @Test
    fun `per-model breakdown groups sums and sorts by tokens`() {
        val viewModel = makeViewModel()
        viewModel.loadIfNeeded()

        val models = viewModel.uiState.value.models
        assertEquals(listOf("gpt-fast", "gpt-smart"), models.map { it.model })

        val fast = models[0]
        assertEquals(2, fast.sessionCount)
        assertEquals(700L, fast.inputTokens)
        assertEquals(350L, fast.outputTokens)
        assertEquals(1_050L, fast.totalTokens)
        assertEquals(2.5, fast.cost, 1e-9)
        assertEquals(70, fast.tokenSharePercent)
        assertEquals(30, models[1].tokenSharePercent)
    }

    @Test
    fun `top sessions sort by total tokens descending and cap at ten`() {
        val many = (1..12).map { index ->
            session("s$index", daysAgo = 1, input = index * 10, output = 0)
        }
        val viewModel = makeViewModel(many)
        viewModel.loadIfNeeded()

        val top = viewModel.uiState.value.topSessions
        assertEquals(10, top.size)
        assertEquals("s12", top.first().sessionId)
        assertEquals("s3", top.last().sessionId)
    }

    @Test
    fun `daily usage buckets are zero-filled oldest first`() {
        val viewModel = makeViewModel()
        viewModel.loadIfNeeded()

        val daily = viewModel.uiState.value.daily
        assertEquals(DAILY_CHART_DAYS, daily.size)
        assertEquals(LocalDate.of(2026, 8, 4), daily.first().date)
        assertEquals(LocalDate.of(2026, 8, 17), daily.last().date)
        assertEquals(150L, daily.last().totalTokens) // "today"
        assertEquals(1, daily.last().sessionCount)
        assertEquals(450L, daily[daily.size - 6].totalTokens) // "thisWeek", 5 days ago
        assertEquals(0L, daily[1].totalTokens) // untouched day stays zero
    }

    @Test
    fun `failure surfaces an error message`() {
        coEvery { api.sessions(any(), any()) } throws ApiError.Http(503, null)
        val viewModel = InsightsViewModel(api, nowMillis = { nowMillis }, zone = zone)

        viewModel.load()

        val state = viewModel.uiState.value
        assertFalse(state.hasLoaded)
        assertEquals(ApiError.Http(503, null).message, state.errorMessage)
    }

    // MARK: - Pure aggregation

    @Test
    fun `timeframes filter on the analytics timestamp with fallbacks`() {
        val byUpdated = SessionSummary(sessionId = "u", updatedAt = epochSeconds(0))
        val byCreated = SessionSummary(sessionId = "c", createdAt = epochSeconds(0))
        val timeless = SessionSummary(sessionId = "t")

        assertTrue(AnalyticsTimeframe.TODAY.contains(byUpdated, nowMillis, zone))
        assertTrue(AnalyticsTimeframe.TODAY.contains(byCreated, nowMillis, zone))
        assertFalse(AnalyticsTimeframe.TODAY.contains(timeless, nowMillis, zone))
        assertFalse(AnalyticsTimeframe.LAST_7_DAYS.contains(timeless, nowMillis, zone))
        assertTrue(AnalyticsTimeframe.ALL_TIME.contains(timeless, nowMillis, zone))
    }

    @Test
    fun `seven day window is inclusive at the boundary and excludes the future`() {
        val boundary = session("b", daysAgo = 7)
        val past = session("p", daysAgo = 8)
        val future = session("f", daysAgo = -1)

        assertTrue(AnalyticsTimeframe.LAST_7_DAYS.contains(boundary, nowMillis, zone))
        assertFalse(AnalyticsTimeframe.LAST_7_DAYS.contains(past, nowMillis, zone))
        assertFalse(AnalyticsTimeframe.LAST_7_DAYS.contains(future, nowMillis, zone))
    }

    @Test
    fun `model breakdown with zero tokens reports zero share`() {
        val models = modelUsageBreakdown(
            listOf(SessionSummary(sessionId = "a", model = "m", inputTokens = 0, outputTokens = 0)),
        )
        assertEquals(1, models.size)
        assertEquals(0, models[0].tokenSharePercent)
    }

    @Test
    fun `sessions without a model group under unknown`() {
        val models = modelUsageBreakdown(
            listOf(
                SessionSummary(sessionId = "a", model = null, inputTokens = 10),
                SessionSummary(sessionId = "b", model = " ", inputTokens = 5),
            ),
        )
        assertEquals(1, models.size)
        assertEquals("Unknown Model", models[0].model)
        assertEquals(15L, models[0].totalTokens)
        assertEquals(100, models[0].tokenSharePercent)
    }

    @Test
    fun `daily usage ignores sessions outside the window`() {
        val daily = dailyUsage(
            listOf(session("old", daysAgo = 30, input = 500), session("in", daysAgo = 2, input = 40)),
            days = 7,
            nowMillis = nowMillis,
            zone = zone,
        )
        assertEquals(7, daily.size)
        assertEquals(40L, daily.sumOf { it.totalTokens })
    }

    @Test
    fun `token formatting matches the ios rows`() {
        assertEquals("12,345", formatTokenCount(12_345))
        assertEquals("0", formatTokenCount(0))
        assertEquals("999", formatCompactTokens(999))
        assertEquals("45.0K", formatCompactTokens(45_000))
        assertEquals("1.2M", formatCompactTokens(1_230_000))
    }
}
