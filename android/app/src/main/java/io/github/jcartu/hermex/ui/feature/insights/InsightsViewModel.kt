package io.github.jcartu.hermex.ui.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** How many trailing days the daily-tokens bar chart covers (iOS shows 14). */
const val DAILY_CHART_DAYS = 14

/**
 * Port of `Features/Insights/InsightsViewModel.swift`, local data source:
 * aggregates are computed from `GET /api/sessions` metadata (the Swift
 * `.localFallback` path). Timeframe switches re-derive locally — the full
 * session list is already in memory — and pull-to-refresh refetches.
 */
class InsightsViewModel(
    private val api: HermesApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private var sessions: List<SessionSummary> = emptyList()
    private var loadRevision = 0

    fun loadIfNeeded() {
        if (_uiState.value.hasLoaded || _uiState.value.isLoading) return
        load()
    }

    fun load() {
        val revision = ++loadRevision
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response = api.sessions()
                if (revision != loadRevision) return@launch
                sessions = response.sessions.orEmpty()
                _uiState.update { state ->
                    derive(sessions, state.selectedTimeframe).copy(
                        isLoading = false,
                        hasLoaded = true,
                        selectedTimeframe = state.selectedTimeframe,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != loadRevision) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message?.takeIf { m -> m.isNotBlank() } ?: "Something went wrong.",
                    )
                }
            }
        }
    }

    fun selectTimeframe(timeframe: AnalyticsTimeframe) {
        if (timeframe == _uiState.value.selectedTimeframe) return
        _uiState.update { state ->
            derive(sessions, timeframe).copy(
                isLoading = state.isLoading,
                hasLoaded = state.hasLoaded,
                errorMessage = state.errorMessage,
                selectedTimeframe = timeframe,
            )
        }
    }

    private fun derive(sessions: List<SessionSummary>, timeframe: AnalyticsTimeframe): InsightsUiState {
        val now = nowMillis()
        val filtered = sessions.filter { timeframe.contains(it, now, zone) }
        val analytics = SessionUsageAnalytics(filtered)
        return InsightsUiState(
            sessionCount = analytics.sessionCount,
            totalMessages = analytics.totalMessages,
            totalInputTokens = analytics.totalInputTokens,
            totalOutputTokens = analytics.totalOutputTokens,
            totalTokens = analytics.totalTokens,
            estimatedCost = analytics.estimatedCost,
            models = modelUsageBreakdown(filtered),
            daily = dailyUsage(filtered, DAILY_CHART_DAYS, now, zone),
            topSessions = analytics.topSessions.take(10),
        )
    }
}

data class InsightsUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val selectedTimeframe: AnalyticsTimeframe = AnalyticsTimeframe.LAST_30_DAYS,
    val sessionCount: Int = 0,
    val totalMessages: Long = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalTokens: Long = 0,
    val estimatedCost: Double = 0.0,
    val models: List<ModelUsage> = emptyList(),
    val daily: List<DailyUsage> = emptyList(),
    val topSessions: List<SessionSummary> = emptyList(),
) {
    /** Port of InsightsViewModel.sourceDescription for the local data source. */
    val sourceDescription: String
        get() = "Source: local session metadata."
}
