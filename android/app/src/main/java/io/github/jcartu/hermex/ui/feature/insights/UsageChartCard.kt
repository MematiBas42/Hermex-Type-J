package io.github.jcartu.hermex.ui.feature.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.UsageBucket
import io.github.jcartu.hermex.model.UsageChartSegment
import io.github.jcartu.hermex.model.UsageHeroFigure
import io.github.jcartu.hermex.model.UsageMetric
import io.github.jcartu.hermex.model.segments
import io.github.jcartu.hermex.model.usageChartLegendSegments
import io.github.jcartu.hermex.ui.components.SectionCard

private const val CHART_HEIGHT_DP = 180

private fun segmentColor(segment: UsageChartSegment): Color = when (segment) {
    UsageChartSegment.INPUT -> Color(0xFF4A9EFF)
    UsageChartSegment.OUTPUT -> Color(0xFFFF9F0A)
    UsageChartSegment.CACHE_READ -> Color(0xFF30B0C7)
    UsageChartSegment.COST -> Color(0xFF5856D6)
    UsageChartSegment.SESSIONS -> Color(0xFFAF52DE)
}

/**
 * The Usage screen's headline card: the window figure, the metric toggle, the
 * bar chart, and the edge labels beneath it.
 *
 * Dragging across the chart replaces the hero with the touched bucket's own
 * figures and restores the window total on release, which is what lets the old
 * "Recent Daily Tokens" list go away without losing per-day detail.
 * Port of `Features/Insights/UsageChartCard.swift`.
 */
@Composable
fun UsageChartCard(
    buckets: List<UsageBucket>,
    metric: UsageMetric,
    showsMetricToggle: Boolean,
    windowTotal: UsageHeroFigure,
    hourlyNote: String?,
    chartAccessibilityLabel: String,
    onMetricChange: (UsageMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedBucketId by remember(buckets) { mutableStateOf<Int?>(null) }

    val hero = selectedBucketId
        ?.let { id -> buckets.firstOrNull { it.id == id } }
        ?.let { UsageHeroFigure.selection(it, metric) }
        ?: windowTotal

    SectionCard(title = "", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header: hero figure + metric toggle.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        hero.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        hero.value,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        hero.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (showsMetricToggle) {
                    SingleChoiceSegmentedButtonRow {
                        UsageMetric.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = metric == option,
                                onClick = { onMetricChange(option) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = UsageMetric.entries.size,
                                ),
                            ) {
                                Text(option.title, maxLines = 1)
                            }
                        }
                    }
                }
            }

            UsageBarChart(
                buckets = buckets,
                metric = metric,
                selectedBucketId = selectedBucketId,
                onSelectBucket = { selectedBucketId = it },
            )

            chartFooter(buckets, metric)

            if (hourlyNote != null) {
                Text(
                    hourlyNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The stacked bar chart, drawn directly on a [Canvas]. Bars stack the selected
 * metric's segments bottom-to-top; a press or drag selects the touched bucket.
 */
@Composable
private fun UsageBarChart(
    buckets: List<UsageBucket>,
    metric: UsageMetric,
    selectedBucketId: Int?,
    onSelectBucket: (Int?) -> Unit,
) {
    val hasActivity = buckets.any { it.hasActivity }

    if (!hasActivity) {
        Text(
            "No activity in this window.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT_DP.dp),
        )
        return
    }

    // The tallest stacked total, so every bar is normalized to it.
    val maxTotal = buckets
        .maxOfOrNull { bucket -> bucket.segments(metric).sumOf { layer -> layer.second } }
        ?.takeIf { it > 0 }
        ?: 1.0

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT_DP.dp)
            .pointerInput(buckets, metric) {
                val bucketCount = buckets.size
                detectTapGestures(
                    onPress = { offset ->
                        val index = ((offset.x / size.width) * bucketCount).toInt().coerceIn(0, bucketCount - 1)
                        onSelectBucket(buckets[index].id)
                        tryAwaitRelease()
                        onSelectBucket(null)
                    },
                )
            },
    ) {
        val barSlot = size.width / buckets.size
        val barWidth = barSlot * 0.6f

        buckets.forEachIndexed { index, bucket ->
            val layers = bucket.segments(metric)
            val total = layers.sumOf { layer -> layer.second }
            val barHeight = if (maxTotal > 0) (total / maxTotal * size.height).toFloat() else 0f
            val startX = index * barSlot + (barSlot - barWidth) / 2

            var yCursor = size.height
            layers.forEach { (segment, value) ->
                val layerHeight = (if (total > 0) value / total * barHeight else 0.0).toFloat()
                if (layerHeight > 0f) {
                    drawRect(
                        color = segmentColor(segment).copy(
                            alpha = if (selectedBucketId == null || selectedBucketId == bucket.id) 1f else 0.3f,
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(startX, yCursor - layerHeight),
                        size = androidx.compose.ui.geometry.Size(barWidth, layerHeight),
                    )
                    yCursor -= layerHeight
                }
            }
        }
    }
}

@Composable
private fun chartFooter(buckets: List<UsageBucket>, metric: UsageMetric) {
    val legendSegments = usageChartLegendSegments(buckets, metric)

    Row(verticalAlignment = Alignment.Top) {
        Text(
            buckets.firstOrNull()?.label.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        if (legendSegments.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                legendSegments.forEach { segment ->
                    Row(
                        Modifier.padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(7.dp)
                                .background(segmentColor(segment), androidx.compose.foundation.shape.CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            segment.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Text(
            buckets.lastOrNull()?.label.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
