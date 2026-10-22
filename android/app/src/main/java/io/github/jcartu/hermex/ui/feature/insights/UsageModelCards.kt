package io.github.jcartu.hermex.ui.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.InsightsModelBreakdown
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.insightsFormattedPercent
import io.github.jcartu.hermex.model.usageFormattedCost
import io.github.jcartu.hermex.model.usageFormattedTokens
import io.github.jcartu.hermex.ui.components.SectionCard

/**
 * Every model the server reported for the window, in the order it returned them
 * (webui: cost descending; a Hermes host: tokens descending). There is no
 * provider dot: webui's insights handler has no provider dimension to draw one
 * from.
 * Port of `Features/Insights/UsageModelRows.swift`.
 */
@Composable
fun UsageModelsCard(
    models: List<InsightsModelBreakdown>,
    hasCost: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "By model", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            models.forEach { model ->
                UsageModelRow(model = model, hasCost = hasCost)
            }
        }
    }
}

@Composable
private fun UsageModelRow(model: InsightsModelBreakdown, hasCost: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                model.model ?: "Unknown Model",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                secondaryLine(model, hasCost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val cost = model.cost
        if (cost != null && cost > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                usageFormattedCost(cost),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun secondaryLine(model: InsightsModelBreakdown, hasCost: Boolean): String {
    val parts = mutableListOf<String>()
    model.provider?.let(parts::add)

    if (hasCost) {
        model.costShare?.let { parts += "$it% of cost" }
    } else {
        model.tokenShare?.let { parts += "$it% of tokens" }
    }

    parts += "${usageFormattedTokens(model.totalTokens ?: 0)} tokens"
    parts += "${model.sessions ?: 0} sessions"

    model.cacheHitPercent?.let { parts += "${insightsFormattedPercent(it)} cache" }

    return parts.joinToString(" · ")
}

/**
 * The heaviest sessions, by tokens. Only reachable on the local-metadata
 * fallback, where the server never told us about models.
 */
@Composable
fun UsageTopSessionsCard(
    sessions: List<SessionSummary>,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Top Sessions", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            sessions.take(10).forEach { session ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        session.title ?: "Untitled Session",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        topSessionDetail(session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun topSessionDetail(session: SessionSummary): String {
    val total = (session.inputTokens ?: 0) + (session.outputTokens ?: 0)
    val tokens = "${usageFormattedTokens(total)} tokens"

    val cost = session.estimatedCost
    return if (cost != null && cost > 0) {
        "$tokens · ${usageFormattedCost(cost)}"
    } else {
        tokens
    }
}

/**
 * The two-up grid of window totals under the chart. It reflows to one column at
 * accessibility text sizes, where two cells no longer fit side by side.
 * Port of `Features/Insights/UsageTotalsGrid.swift`.
 */
@Composable
fun UsageTotalsGrid(
    cells: List<UsageTotalsCell>,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Totals", modifier = modifier) {
        // Two-up grid built from chunked rows: a lazy grid cannot live inside
        // the screen's scrollable column (unbounded height), and the cell
        // count is small enough that laziness buys nothing.
        cells.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { cell ->
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            cell.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            cell.value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        cell.detail?.let { detail ->
                            Text(
                                detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.padding(vertical = 8.dp))
        }
    }
}
