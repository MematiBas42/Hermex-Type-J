package io.github.jcartu.hermex.ui.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ProviderLimitCard
import io.github.jcartu.hermex.model.ProviderLimitRow
import io.github.jcartu.hermex.model.ProviderLimitTint
import io.github.jcartu.hermex.model.providerLimitResetText
import io.github.jcartu.hermex.model.providerLimitTint
import io.github.jcartu.hermex.model.providerLimitUpdatedText
import io.github.jcartu.hermex.ui.components.SectionCard
import java.time.Instant

/**
 * The "Limits" group at the top of the Usage screen (#415): one card per
 * provider that reported a usable quota, or a placeholder card while the first
 * probes are still running. The caller omits the group entirely when there are
 * no cards and nothing is loading, which is how every failed or unsupported
 * probe disappears.
 * Port of `Features/Insights/ProviderLimitsCard.swift`.
 */
@Composable
fun ProviderLimitsSection(
    cards: List<ProviderLimitCard>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.then(modifier).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "LIMITS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                ProviderLimitsCardView(card = card)
            }
        } else if (isLoading) {
            ProviderLimitsPlaceholderCard()
        }
    }
}

/**
 * Holds the Limits slot open while the first quota probes are in flight, so the
 * real cards replace a reserved space instead of appearing above the analytics
 * and pushing the screen down a second after it settles (#415).
 */
@Composable
private fun ProviderLimitsPlaceholderCard() {
    SectionCard(title = "") {
        Column {
            Text(
                "Provider",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            ProviderLimitRowView(
                row = ProviderLimitRow(
                    id = "placeholder",
                    label = "Session",
                    amount = "100% left",
                    fraction = 0.0,
                    remainingPercent = 100.0,
                    resetAt = null,
                    note = null,
                ),
                now = Instant.now(),
            )
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            ProviderLimitRowView(
                row = ProviderLimitRow(
                    id = "placeholder-2",
                    label = "Session",
                    amount = "100% left",
                    fraction = 0.0,
                    remainingPercent = 100.0,
                    resetAt = null,
                    note = null,
                ),
                now = Instant.now(),
            )
        }
    }
}

/**
 * One provider's card: a header, a row per limit window, and a footer saying
 * how old the numbers are. Every relative time is computed while the body runs
 * — nothing here ticks, animates, or repaints on its own.
 */
@Composable
private fun ProviderLimitsCardView(card: ProviderLimitCard) {
    // One now for the whole card so its rows and footer agree.
    val now = Instant.now()

    SectionCard(title = "") {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                card.plan?.let { plan ->
                    Text(
                        plan,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                CircleShape,
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            card.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                }
                ProviderLimitRowView(row = row, now = now)
            }

            providerLimitUpdatedText(card.updatedAt, now)?.let { updated ->
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text(
                    updated,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One limit row: the label, the bold remaining amount, the bar, and either a
 * reset countdown or a static usage note.
 */
@Composable
private fun ProviderLimitRowView(row: ProviderLimitRow, now: Instant) {
    val resetText = providerLimitResetText(row.resetAt, now)
    val tint = providerLimitTint(row.remainingPercent)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            row.amount?.let { amount ->
                Text(
                    amount,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tintColor(tint),
                )
            }
        }

        row.fraction?.let { fraction ->
            ProviderLimitBar(fraction = fraction, tint = tint)
        }

        if (resetText != null) {
            Text(
                resetText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            row.note?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The remaining-balance bar. Deliberately unanimated: it is redrawn only when
 * a load replaces the card, so there is nothing for Reduce Motion to suppress.
 */
@Composable
private fun ProviderLimitBar(fraction: Double, tint: ProviderLimitTint) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .height(8.dp)
                .background(tintColor(tint), RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun tintColor(tint: ProviderLimitTint): Color = when (tint) {
    ProviderLimitTint.NORMAL -> MaterialTheme.colorScheme.primary
    ProviderLimitTint.WARNING -> Color(0xFFFF9F0A)
    ProviderLimitTint.CRITICAL -> MaterialTheme.colorScheme.error
}
