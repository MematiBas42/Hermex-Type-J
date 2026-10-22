package io.github.jcartu.hermex.ui.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.components.SectionCard
import kotlinx.coroutines.launch

private const val MAX_CONTENT_WIDTH_DP = 840

/**
 * The Usage screen: a window picker, one hero figure with a chart under it, the
 * window totals, and the per-model breakdown. On a webui server every figure
 * comes from `GET /api/insights`, or from local session metadata when that call
 * fails.
 * Port of `Features/Insights/InsightsView.swift` + its cards.
 */
@Composable
fun InsightsRoute(
    client: InsightsDataClient,
    modifier: Modifier = Modifier,
    profile: String? = null,
) {
    val viewModel: InsightsViewModel = viewModel(
        key = "insights:${profile.orEmpty()}",
        factory = viewModelFactory { initializer { InsightsViewModel(client) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch { viewModel.loadLimits(refresh = true) }
        viewModel.load()
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_CONTENT_WIDTH_DP.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (profile == null) "Usage" else "Usage · $profile",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.isRefreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = ::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            if (!state.hasLoadedAnalytics && !state.fallsBackToSessions) {
                // Without a sessions fallback (Hermes) a failed window replaces the
                // figures, so the picker stays above the placeholder.
                Column {
                    WindowPicker(
                        selected = state.selectedTimeframe,
                        timeframes = state.timeframes,
                        onSelect = viewModel::selectTimeframe,
                    )
                    Placeholder(state, onRetry = viewModel::load, modifier = Modifier.weight(1f))
                }
            } else if (state.hasLoadedAnalytics) {
                LoadedContent(
                    state = state,
                    onTimeframeChange = viewModel::selectTimeframe,
                    onMetricChange = viewModel::selectMetric,
                )
            } else {
                Placeholder(state, onRetry = viewModel::load)
            }
        }
    }
}

@Composable
private fun Placeholder(state: UsageUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingState(label = "Loading usage...")
            state.errorMessage != null -> ErrorState(
                message = state.errorMessage,
                retryLabel = "Try Again",
                onRetry = onRetry,
            )
            else -> EmptyState(
                title = "No Data",
                message = "Session usage data will appear here once you have conversations.",
            )
        }
    }
}

@Composable
private fun WindowPicker(
    selected: AnalyticsTimeframe,
    timeframes: List<AnalyticsTimeframe>,
    onSelect: (AnalyticsTimeframe) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        timeframes.forEachIndexed { index, timeframe ->
            SegmentedButton(
                selected = selected == timeframe,
                onClick = { onSelect(timeframe) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = timeframes.size),
            ) {
                Text(timeframe.title, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LoadedContent(
    state: UsageUiState,
    onTimeframeChange: (AnalyticsTimeframe) -> Unit,
    onMetricChange: (io.github.jcartu.hermex.model.UsageMetric) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.showsLimits) {
            ProviderLimitsSection(
                cards = state.limitCards,
                isLoading = state.isLoadingLimits,
            )
        }

        WindowPicker(
            selected = state.selectedTimeframe,
            timeframes = state.timeframes,
            onSelect = onTimeframeChange,
        )

        if (state.dataSource != InsightsDataSource.SERVER) {
            SectionCard(title = "") {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFFFF9F0A),
                        modifier = Modifier.size(16.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(
                        state.sourceDescription,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        UsageChartCard(
            buckets = state.chartBuckets,
            metric = state.metric,
            showsMetricToggle = state.showsMetricToggle,
            windowTotal = state.heroFigure,
            hourlyNote = state.hourlyChartNote,
            chartAccessibilityLabel = state.chartAccessibilityLabel,
            onMetricChange = onMetricChange,
        )

        UsageTotalsGrid(cells = state.totalsCells)

        if (state.modelBreakdowns.isNotEmpty()) {
            UsageModelsCard(
                models = state.modelBreakdowns,
                hasCost = state.estimatedCost > 0,
            )
        }

        if (state.topSessions.isNotEmpty()) {
            UsageTopSessionsCard(sessions = state.topSessions)
        }

        Text(
            state.sourceDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
