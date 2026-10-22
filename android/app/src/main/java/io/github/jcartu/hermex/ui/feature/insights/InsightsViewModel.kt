package io.github.jcartu.hermex.ui.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.InsightsActivityByDay
import io.github.jcartu.hermex.model.InsightsActivityByHour
import io.github.jcartu.hermex.model.InsightsDailyToken
import io.github.jcartu.hermex.model.InsightsModelBreakdown
import io.github.jcartu.hermex.model.InsightsResponse
import io.github.jcartu.hermex.model.ProviderLimitCard
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.UsageBucket
import io.github.jcartu.hermex.model.UsageBuckets
import io.github.jcartu.hermex.model.UsageHeroFigure
import io.github.jcartu.hermex.model.UsageMetric
import io.github.jcartu.hermex.model.insightsFormattedPercent
import io.github.jcartu.hermex.model.providerQuotaSelection
import io.github.jcartu.hermex.model.usageFormattedTokens
import io.github.jcartu.hermex.model.usageHourLabel
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the figures come from. Port of `InsightsDataSource`. */
enum class InsightsDataSource {
    SERVER,
    LOCAL_FALLBACK,
    LOCAL,
}

/** One cell in the Usage totals grid. Port of `UsageTotalsCell`. */
data class UsageTotalsCell(
    val id: String,
    val label: String,
    val value: String,
    val detail: String?,
)

/**
 * State for the Usage screen. Port of `Features/Insights/InsightsViewModel.swift`
 * (`InsightsViewModel`, an `@Observable` with the same computed fields).
 */
data class UsageUiState(
    val selectedTimeframe: AnalyticsTimeframe = AnalyticsTimeframe.LAST_30_DAYS,
    val loadedTimeframe: AnalyticsTimeframe = AnalyticsTimeframe.LAST_30_DAYS,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val fallbackReason: String? = null,
    val dataSource: InsightsDataSource = InsightsDataSource.LOCAL,
    val sessions: List<SessionSummary> = emptyList(),
    val serverInsights: InsightsResponse? = null,
    val limitCards: List<ProviderLimitCard> = emptyList(),
    val isLoadingLimits: Boolean = false,
    val chosenMetric: UsageMetric? = null,
    val timeframes: List<AnalyticsTimeframe> = AnalyticsTimeframe.entries,
    val approximatesMessages: Boolean = false,
    val fallsBackToSessions: Boolean = true,
) {
    /** Whether the Limits group has anything to occupy its slot: real cards, or a first-load placeholder. */
    val showsLimits: Boolean
        get() = limitCards.isNotEmpty() || isLoadingLimits
    val hasLoadedAnalytics: Boolean
        get() = serverInsights != null || dataSource == InsightsDataSource.LOCAL_FALLBACK

    /** A re-fetch of something already on screen. */
    val isRefreshing: Boolean
        get() = isLoading && hasLoadedAnalytics

    // Aggregates: server first, then the local fallback.
    val filteredSessions: List<SessionSummary>
        get() = sessions.filter { loadedTimeframe.contains(it) }

    private val analytics: SessionUsageAnalytics
        get() = SessionUsageAnalytics(filteredSessions)

    val totalInputTokens: Int
        get() = serverInsights?.totalInputTokens ?: analytics.totalInputTokens

    val totalOutputTokens: Int
        get() = serverInsights?.totalOutputTokens ?: analytics.totalOutputTokens

    val totalTokens: Int
        get() = serverInsights?.totalTokens ?: analytics.totalTokens

    val totalMessages: Int
        get() = serverInsights?.totalMessages ?: analytics.totalMessages

    val estimatedCost: Double
        get() = serverInsights?.totalCost ?: analytics.estimatedCost

    val totalCacheReadTokens: Int?
        get() = serverInsights?.totalCacheReadTokens

    val totalCacheHitPercent: Double?
        get() = serverInsights?.totalCacheHitPercent

    val sessionCount: Int
        get() = serverInsights?.totalSessions ?: analytics.sessionCount

    val periodDays: Int
        get() = serverInsights?.periodDays ?: selectedTimeframe.serverDays

    val modelBreakdowns: List<InsightsModelBreakdown>
        get() = serverInsights?.models ?: emptyList()

    val activityByDay: List<InsightsActivityByDay>
        get() = serverInsights?.activityByDay ?: emptyList()

    val activityByHour: List<InsightsActivityByHour>
        get() = serverInsights?.activityByHour ?: emptyList()

    val peakDay: InsightsActivityByDay?
        get() = activityByDay.maxByOrNull { it.sessions ?: 0 }

    val peakHour: InsightsActivityByHour?
        get() = activityByHour.maxByOrNull { it.sessions ?: 0 }

    /** The hero and chart metric. A window the server prices at zero opens on tokens. */
    val metric: UsageMetric
        get() = chosenMetric ?: if (estimatedCost > 0) UsageMetric.COST else UsageMetric.TOKENS

    /** Hidden on the hourly window, which has only one figure to show. */
    val showsMetricToggle: Boolean
        get() = !loadedTimeframe.isHourly

    /** The bars for the loaded window: one per hour on the 24-hour window, one per calendar day otherwise. */
    val chartBuckets: List<UsageBucket>
        get() {
        val insights = serverInsights ?: return emptyList()
        if (loadedTimeframe.isHourly) {
            return UsageBuckets.hourly(insights.activityByHour ?: emptyList())
        }
        return UsageBuckets.daily(insights.dailyTokens ?: emptyList(), windowDays = periodDays)
    }

    /** Daily rows regardless of window, so "per active day" means the same thing on every screen. */
    private val dailyBuckets: List<UsageBucket>
        get() {
        val insights = serverInsights ?: return emptyList()
        return UsageBuckets.daily(insights.dailyTokens ?: emptyList(), windowDays = periodDays)
    }

    val activeDayCount: Int
        get() = dailyBuckets.count { it.hasActivity }

    /** The window total, shown whenever the user is not dragging the chart. */
    val heroFigure: UsageHeroFigure
        get() {
        if (loadedTimeframe.isHourly) {
            return UsageHeroFigure(
                label = "Sessions",
                value = sessionCount.toString(),
                caption = "Started or updated today.",
            )
        }
        return when (metric) {
            UsageMetric.COST -> UsageHeroFigure(
                label = "Estimated cost",
                value = io.github.jcartu.hermex.model.usageFormattedCost(estimatedCost),
                caption = "Estimated by the server from session metadata.",
            )
            UsageMetric.TOKENS -> UsageHeroFigure(
                // "Processed", not "total": this is the server's `total_tokens`,
                // which excludes cache reads.
                label = "Processed tokens",
                value = usageFormattedTokens(totalTokens),
                caption = "Across $sessionCount sessions.",
            )
        }
    }

    val chartAccessibilityLabel: String
        get() {
        if (loadedTimeframe.isHourly) return "Sessions per hour, ${loadedTimeframe.title}"
        return when (metric) {
            UsageMetric.COST -> "Estimated cost per day, ${loadedTimeframe.title}"
            UsageMetric.TOKENS -> "Tokens per day, ${loadedTimeframe.title}"
        }
    }

    val hourlyChartNote: String? =
        if (loadedTimeframe.isHourly) {
            "Sessions per hour — the server does not report hourly tokens or cost."
        } else {
            null
        }

    val totalsCells: List<UsageTotalsCell>
        get() {
        val cells = mutableListOf<UsageTotalsCell>()

        cells += UsageTotalsCell(
            id = "processedTokens",
            label = "Processed tokens",
            value = usageFormattedTokens(totalTokens),
            detail = perActiveDayDetail(),
        )

        totalCacheReadTokens?.let { cacheReadTokens ->
            cells += UsageTotalsCell(
                id = "cachedInput",
                label = "Cached input",
                value = usageFormattedTokens(cacheReadTokens),
                detail = cachedInputDetail(cacheReadTokens),
            )
        }

        totalCacheHitPercent?.let { cacheHitPercent ->
            cells += UsageTotalsCell(
                id = "cacheHitRate",
                label = "Cache hit rate",
                value = insightsFormattedPercent(cacheHitPercent),
                detail = "reported by the server",
            )
        }

        cells += UsageTotalsCell(
            id = "input",
            label = "Input",
            value = usageFormattedTokens(totalInputTokens),
            detail = "uncached",
        )

        cells += UsageTotalsCell(
            id = "output",
            label = "Output",
            value = usageFormattedTokens(totalOutputTokens),
            detail = null,
        )

        cells += UsageTotalsCell(
            id = "messages",
            label = "Messages",
            value = (if (approximatesMessages) "≈" else "") + usageFormattedTokens(totalMessages),
            detail = "across $sessionCount sessions",
        )

        val day = peakDay
        if (day != null && !day.day.isNullOrEmpty()) {
            cells += UsageTotalsCell(
                id = "busiestWeekday",
                label = "Busiest weekday",
                value = day.day!!,
                detail = "${day.sessions ?: 0} sessions",
            )
        }

        val hour = peakHour
        if (hour != null && hour.hour != null) {
            cells += UsageTotalsCell(
                id = "busiestHour",
                label = "Busiest hour",
                value = usageHourLabel(hour.hour!!),
                detail = "${hour.sessions ?: 0} sessions",
            )
        }

        return cells
    }

    val topSessions: List<SessionSummary>
        get() {
        if (dataSource == InsightsDataSource.SERVER) return emptyList()
        return analytics.topSessions
    }

    val sourceDescription: String
        get() {
        return when (dataSource) {
            InsightsDataSource.SERVER -> "Source: server insights from the last $periodDays days."
            InsightsDataSource.LOCAL_FALLBACK -> {
                val reason = fallbackReason
                if (!reason.isNullOrEmpty()) {
                    "Source: local session metadata fallback. Server insights failed: $reason"
                } else {
                    "Source: local session metadata fallback."
                }
            }
            InsightsDataSource.LOCAL -> "Source: local session metadata."
        }
    }

    private fun perActiveDayDetail(): String? {
        val activeDays = activeDayCount
        if (activeDays <= 0) return null
        return "${usageFormattedTokens(totalTokens / activeDays)} per active day"
    }

    /** Cached reads as a share of everything the model was shown. */
    private fun cachedInputDetail(cacheReadTokens: Int): String? {
        val observedInput = totalInputTokens + cacheReadTokens
        if (observedInput <= 0) return null
        val share = cacheReadTokens.toDouble() / observedInput * 100
        return "${insightsFormattedPercent(share)} of observed input"
    }
}

/**
 * Port of `Features/Insights/InsightsViewModel.swift`: loads one window from
 * `/api/insights`, falling back to `/api/sessions` metadata where the server
 * backs that, and probes provider quota cards separately so a cold probe never
 * holds up the chart.
 */
class InsightsViewModel(private val client: InsightsDataClient) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    /** Same generation guard as the Swift: a superseded load must never overwrite a newer one's state. */
    private var activeLoadId = 0
    private var limitsGeneration = 0
    private var sessions: List<SessionSummary> = emptyList()
    private var serverInsights: InsightsResponse? = null
    private var dataSource = InsightsDataSource.LOCAL
    private var fallbackReason: String? = null
    private var loadedTimeframe = AnalyticsTimeframe.LAST_30_DAYS
    private var isLoading = false
    private var errorMessage: String? = null

    init {
        load()
        loadLimits()
    }

    fun selectTimeframe(timeframe: AnalyticsTimeframe) {
        if (_uiState.value.selectedTimeframe == timeframe) return
        _uiState.value = _uiState.value.copy(selectedTimeframe = timeframe)
        load()
    }

    fun selectMetric(metric: UsageMetric) {
        _uiState.value = _uiState.value.copy(chosenMetric = metric)
    }

    fun load() {
        val loadId = ++activeLoadId
        val timeframe = _uiState.value.selectedTimeframe
        isLoading = true
        errorMessage = null
        fallbackReason = null
        publish()
        val hadLoadedAnalytics = _uiState.value.hasLoadedAnalytics

        viewModelScope.launch {
            try {
                val response = client.insights(timeframe.serverDays)
                if (loadId != activeLoadId) return@launch

                serverInsights = response
                sessions = emptyList()
                loadedTimeframe = timeframe
                dataSource = InsightsDataSource.SERVER
                fallbackReason = null
                isLoading = false
                publish()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (loadId != activeLoadId) return@launch
                if (!_uiState.value.fallsBackToSessions) {
                    // Nothing to rebuild the window from: the failure replaces it.
                    serverInsights = null
                    dataSource = InsightsDataSource.LOCAL
                    loadedTimeframe = timeframe
                    fallbackReason = null
                    isLoading = false
                    publish(error.message ?: "Could not load usage.")
                    return@launch
                }
                fallbackReason = error.message ?: "Server insights failed."

                try {
                    val response = client.sessions()
                    if (loadId != activeLoadId) return@launch

                    serverInsights = null
                    sessions = response.sessions ?: emptyList()
                    loadedTimeframe = timeframe
                    dataSource = InsightsDataSource.LOCAL_FALLBACK
                    isLoading = false
                    publish()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (fallbackError: Exception) {
                    if (loadId != activeLoadId) return@launch
                    isLoading = false
                    if (hadLoadedAnalytics) {
                        fallbackReason = fallbackError.message ?: "Server insights failed."
                        publish()
                    } else {
                        fallbackReason = null
                        dataSource = InsightsDataSource.LOCAL
                        publish(fallbackError.message ?: "Could not load usage.")
                    }
                }
            }
        }
    }

    /**
     * Loads the quota cards. Deliberately separate from [load]: a cold
     * account-limits probe can take several seconds upstream, and the chart
     * must never wait on it. Every failure here is silent.
     *
     * `refresh` bypasses the server's 45 s probe cache and belongs only to the
     * toolbar button and pull-to-refresh.
     */
    fun loadLimits(refresh: Boolean = false) {
        val generation = ++limitsGeneration
        viewModelScope.launch {
            try {
                val selection: List<String>
                try {
                    selection = providerQuotaSelection(client.providers())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@launch
                }

                if (generation != limitsGeneration) return@launch
                if (selection.isEmpty()) {
                    _uiState.value = _uiState.value.copy(limitCards = emptyList())
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isLoadingLimits = true)

                val fetchedAt = Instant.now()
                val cards = coroutineScope {
                    selection.map { provider ->
                        async {
                            try {
                                val response = client.providerQuota(provider, refresh)
                                ProviderLimitCard.build(provider, response, fetchedAt)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()

                if (generation != limitsGeneration) return@launch
                _uiState.value = _uiState.value.copy(
                    limitCards = cards,
                    isLoadingLimits = false,
                )
            } finally {
                if (generation == limitsGeneration) {
                    _uiState.value = _uiState.value.copy(isLoadingLimits = false)
                }
            }
        }
    }

    private fun publish(errorMessageOverride: String? = null) {
        if (errorMessageOverride != null) errorMessage = errorMessageOverride
        _uiState.value = UsageUiState(
            selectedTimeframe = _uiState.value.selectedTimeframe,
            loadedTimeframe = loadedTimeframe,
            isLoading = isLoading,
            errorMessage = errorMessage,
            fallbackReason = fallbackReason,
            dataSource = dataSource,
            sessions = sessions,
            serverInsights = serverInsights,
            limitCards = _uiState.value.limitCards,
            isLoadingLimits = _uiState.value.isLoadingLimits,
            chosenMetric = _uiState.value.chosenMetric,
            timeframes = client.insightsFeatures.timeframes,
            approximatesMessages = client.insightsFeatures.approximatesMessages,
            fallsBackToSessions = client.insightsFeatures.fallsBackToSessions,
        )
    }
}
