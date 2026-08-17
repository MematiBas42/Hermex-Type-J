package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jcartu.hermex.model.ContextWindowFormatter
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import kotlin.math.min

/**
 * Port of `ContextWindowIndicatorView.swift`: a compact usage ring with the
 * percentage in its center; tapping opens the detail popover (tokens, input,
 * output, threshold, cost). Past the session's compaction threshold the
 * progress arc takes the warning (error) tint.
 */
@Composable
fun ContextWindowIndicator(snapshot: ContextWindowSnapshot, modifier: Modifier = Modifier) {
    val percentage = snapshot.percentage
    val percentageLabel = percentage?.let { "${(it * 100).toInt()}" } ?: "–"
    val isInteractive = percentage != null
    var showDetails by remember { mutableStateOf(false) }

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val isPastThreshold = snapshot.thresholdTokens?.let { threshold ->
        threshold > 0 && (snapshot.tokensUsed ?: 0) >= threshold
    } ?: false
    val progressColor = if (isPastThreshold) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    }
    val labelColor = if (isInteractive) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val accessibilityText = ContextWindowFormatter.compactIndicator(snapshot)
        ?: "Context usage loading"

    Box(modifier) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(enabled = isInteractive) { showDetails = true }
                .semantics { contentDescription = accessibilityText },
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(30.dp)) {
                    val strokeWidth = 3.dp.toPx()
                    val diameter = min(size.width, size.height) - strokeWidth
                    val topLeft = Offset(
                        (size.width - diameter) / 2f,
                        (size.height - diameter) / 2f,
                    )
                    val arcSize = Size(diameter, diameter)

                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth),
                    )

                    if (percentage != null) {
                        drawArc(
                            color = progressColor,
                            startAngle = -90f,
                            sweepAngle = (percentage.coerceIn(0.0, 1.0) * 360f).toFloat(),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                    }
                }

                Text(
                    text = percentageLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor,
                )
            }
        }

        DropdownMenu(
            expanded = showDetails,
            onDismissRequest = { showDetails = false },
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = ContextWindowFormatter.tokensLabel(snapshot),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(10.dp))
                HorizontalDivider()
                Spacer(Modifier.size(10.dp))
                ContextWindowInfoRow("Input", ContextWindowFormatter.inputTokensLabel(snapshot))
                ContextWindowInfoRow("Output", ContextWindowFormatter.outputTokensLabel(snapshot))
                ContextWindowInfoRow("Threshold", ContextWindowFormatter.thresholdLabel(snapshot))
                ContextWindowInfoRow("Cost", ContextWindowFormatter.costLabel(snapshot))
            }
        }
    }
}

@Composable
private fun ContextWindowInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
