package io.github.jcartu.hermex.ui.feature.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.formattedCost
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChartDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * Port of `Features/Insights/InsightsView.swift` over local session metadata.
 * Adaptive: stat cards stack in one column on the cover screen and form a
 * 2/3-column grid on medium/expanded layouts; the whole surface is a single
 * centered column capped at 840dp.
 */
@Composable
fun InsightsRoute() {
    val app = LocalContext.current.applicationContext as HermexApplication
    val viewModel: InsightsViewModel = viewModel(
        factory = viewModelFactory { initializer { InsightsViewModel(app.container.api) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .widthIn(max = 840.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Usage Analytics",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.load() }, enabled = !state.isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh analytics")
                }
            }

            TimeframePicker(
                selected = state.selectedTimeframe,
                onSelect = viewModel::selectTimeframe,
            )

            when {
                state.isLoading && !state.hasLoaded -> LoadingState(label = "Loading analytics...")
                state.errorMessage != null && !state.hasLoaded ->
                    ErrorState(
                        message = state.errorMessage ?: "Something went wrong.",
                        retryLabel = "Try Again",
                        onRetry = { viewModel.load() },
                    )
                state.hasLoaded && state.sessionCount == 0 && state.totalTokens == 0L ->
                    EmptyState(
                        title = "No Data",
                        message = "Session usage data will appear here once you have conversations.",
                    )
                state.hasLoaded -> InsightsContent(state, cardColumns = paneCount.coerceIn(1, 3))
                else -> LoadingState(label = "Loading analytics...")
            }
        }
    }
}

@Composable
private fun TimeframePicker(
    selected: AnalyticsTimeframe,
    onSelect: (AnalyticsTimeframe) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AnalyticsTimeframe.entries) { timeframe ->
            FilterChip(
                selected = timeframe == selected,
                onClick = { onSelect(timeframe) },
                label = { Text(timeframe.title) },
            )
        }
    }
}

@Composable
private fun InsightsContent(state: InsightsUiState, cardColumns: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader(state.selectedTimeframe.title.uppercase(Locale.US))
        StatCardGrid(state, cardColumns)

        SectionHeader("DAILY TOKENS (LAST $DAILY_CHART_DAYS DAYS)")
        DailyTokenChartCard(state.daily)

        if (state.models.isNotEmpty()) {
            SectionHeader("MODELS")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.models.take(10).forEach { ModelBreakdownRow(it) }
                }
            }
        }

        if (state.topSessions.isNotEmpty()) {
            SectionHeader("TOP SESSIONS")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.topSessions.forEach { TopSessionRow(it) }
                }
            }
        }

        Text(
            state.sourceDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatCardGrid(state: InsightsUiState, columns: Int) {
    val cards = listOf(
        StatCard("Sessions", state.sessionCount.toString(), Icons.AutoMirrored.Filled.Chat),
        StatCard("Messages", formatTokenCount(state.totalMessages), Icons.AutoMirrored.Filled.Message),
        StatCard("Input Tokens", formatTokenCount(state.totalInputTokens), Icons.Filled.ArrowCircleDown),
        StatCard("Output Tokens", formatTokenCount(state.totalOutputTokens), Icons.Filled.ArrowCircleUp),
        StatCard("Total Tokens", formatTokenCount(state.totalTokens), Icons.Filled.Functions),
        StatCard("Estimated Cost", state.estimatedCost.formattedCost(collapsingZeroCents = true), Icons.Filled.AttachMoney),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(columns).forEach { rowCards ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowCards.forEach { card ->
                    AnalyticsCard(card, Modifier.weight(1f))
                }
                repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class StatCard(val title: String, val value: String, val icon: ImageVector)

/** Port of AnalyticsCard in InsightsView.swift. */
@Composable
private fun AnalyticsCard(card: StatCard, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                card.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    card.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    card.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DailyTokenChartCard(daily: List<DailyUsage>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            DailyTokenChart(daily)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    daily.firstOrNull()?.date?.format(ChartDateFormatter) ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                val peak = daily.maxOfOrNull { it.totalTokens } ?: 0L
                Text(
                    if (peak > 0) "peak ${formatCompactTokens(peak)} tokens" else "no usage",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Daily-tokens bar chart drawn directly on a Canvas — gold accent bars per
 * the app's visual language, a hairline stub marking zero-usage days.
 */
@Composable
private fun DailyTokenChart(daily: List<DailyUsage>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val stubColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (daily.isEmpty()) return@Canvas
        val max = daily.maxOf { it.totalTokens }.coerceAtLeast(1L)
        val slot = size.width / daily.size
        val barWidth = slot * 0.62f
        val corner = CornerRadius(barWidth / 4f, barWidth / 4f)
        daily.forEachIndexed { index, day ->
            val x = index * slot + (slot - barWidth) / 2f
            if (day.totalTokens > 0) {
                val barHeight = (size.height * day.totalTokens / max.toFloat()).coerceAtLeast(3f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner,
                )
            } else {
                drawRoundRect(
                    color = stubColor,
                    topLeft = Offset(x, size.height - 2f),
                    size = Size(barWidth, 2f),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/** Port of ModelBreakdownRow in InsightsRows.swift, plus a token-share bar. */
@Composable
private fun ModelBreakdownRow(model: ModelUsage) {
    Column {
        Text(
            model.model,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = (model.tokenSharePercent / 100f).coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.height(4.dp))
        val parts = buildList {
            add("${formatTokenCount(model.totalTokens)} tokens")
            add(if (model.sessionCount == 1) "1 session" else "${model.sessionCount} sessions")
            if (model.cost > 0) add(model.cost.formattedCost())
            add("${model.tokenSharePercent}% share")
        }
        Text(
            parts.joinToString("  •  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopSessionRow(session: SessionSummary) {
    Column {
        Text(
            session.title?.takeIf { it.isNotBlank() } ?: "Untitled Session",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val total = (session.inputTokens ?: 0).toLong() + (session.outputTokens ?: 0).toLong()
        val cost = session.estimatedCost
        Text(
            buildString {
                append("${formatTokenCount(total)} tokens")
                if (cost != null && cost > 0) append("  •  ${cost.formattedCost()}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
