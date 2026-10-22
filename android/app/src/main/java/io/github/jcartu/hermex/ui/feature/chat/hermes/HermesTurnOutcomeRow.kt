package io.github.jcartu.hermex.ui.feature.chat.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.net.hermes.HermesTurnOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import okhttp3.HttpUrl

/**
 * Port of `Features/Chat/HermesTurnOutcomeRow.swift`: the host-retained failure and the
 * completion-only warning shown under their turn. It is intentionally static and every action
 * is supplied explicitly by the parent pane.
 */
@Composable
fun HermesTurnOutcomeRow(
    failure: HermesTurnOutcome?,
    notice: HermesTurnOutcome?,
    offersRetry: Boolean,
    mayRetry: Boolean,
    onRetry: () -> Unit,
    onOpenBilling: (HttpUrl) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failure == null && notice?.warning == null) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failure != null) {
            OutcomeBlock(
                icon = Icons.Filled.Error,
                tint = MaterialTheme.colorScheme.error,
                title = hermesTurnOutcomeTitle(failure),
                detail = failure.error,
                resetsAt = failure.surface?.resetsAt,
            ) {
                val billingURL = notice?.billingURL
                if (offersRetry || billingURL != null) {
                    OutcomeActions(
                        offersRetry = offersRetry,
                        mayRetry = mayRetry,
                        billingURL = billingURL,
                        onRetry = onRetry,
                        onOpenBilling = onOpenBilling,
                    )
                }
            }
        }
        notice?.warning?.let { warning ->
            OutcomeBlock(
                icon = Icons.Filled.Warning,
                tint = Color(0xFFFF9800),
                title = "Reply shown but not saved",
                detail = warning,
                resetsAt = null,
                buttons = {},
            )
        }
    }
}

/**
 * Port of `HermesTurnOutcomeRow.title(for:)`; the host sentence wins, then actionable code
 * and layer copy, while raw provider details remain in the selectable detail field.
 */
fun hermesTurnOutcomeTitle(failure: HermesTurnOutcome): String {
    failure.surface?.message?.let { return it }
    if (failure.surface?.layer == "endpoint") {
        return "Your custom model endpoint did not answer"
    }
    when (failure.surface?.code ?: failure.failureReason) {
        "rate_limit", "upstream_rate_limit" -> return "The model provider is rate-limiting requests"
        "context_overflow" -> return "The conversation is too long for this model"
        "payload_too_large" -> return "The request was too large for this model"
        "model_not_found" -> return "The model provider does not know this model"
        "content_policy_blocked" -> return "The model provider refused this request (content policy)"
        "timeout" -> return "The model provider did not answer in time"
        "overloaded" -> return "The model provider is overloaded"
        "server_error" -> return "The model provider had an internal error"
    }
    return when (failure.surface?.layer) {
        "auth" -> "The model provider rejected the API key"
        "billing" -> "The model provider reports no credit left"
        "streaming" -> "The connection to the model provider dropped mid-reply"
        "disk" -> "The disk is full, so Hermes could not save the turn"
        "gateway" -> "Hermes hit an internal error while running this turn"
        "provider" -> "The model provider returned an error"
        else -> "The turn failed"
    }
}

@Composable
private fun OutcomeBlock(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String?,
    resetsAt: Double?,
    buttons: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.padding(top = 1.dp).size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (detail != null) {
                        SelectionContainer {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val resetLine = resetsAt?.takeIf {
                        it.isFinite() && it > System.currentTimeMillis() / 1_000.0
                    }?.let(::hermesTurnOutcomeResetLine)
                    if (resetLine != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Outlined.AccessTime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = resetLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            buttons()
        }
    }
}

@Composable
private fun OutcomeActions(
    offersRetry: Boolean,
    mayRetry: Boolean,
    billingURL: HttpUrl?,
    onRetry: () -> Unit,
    onOpenBilling: (HttpUrl) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (offersRetry) {
                    OutcomeRetryButton(mayRetry, onRetry, Modifier.fillMaxWidth())
                }
                if (billingURL != null) {
                    OutcomeBillingButton(billingURL, onOpenBilling, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (offersRetry) {
                    OutcomeRetryButton(mayRetry, onRetry, Modifier.weight(1f))
                }
                if (billingURL != null) {
                    OutcomeBillingButton(billingURL, onOpenBilling, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OutcomeRetryButton(
    mayRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = onRetry, enabled = mayRetry, modifier = modifier) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
        Text("Retry", modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun OutcomeBillingButton(
    billingURL: HttpUrl,
    onOpenBilling: (HttpUrl) -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = { onOpenBilling(billingURL) }, modifier = modifier) {
        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
        Text("Open billing page", modifier = Modifier.padding(start = 7.dp))
    }
}

/** `Limit resets at 14:05`, including the localized date when it is not today. */
fun hermesTurnOutcomeResetLine(epochSeconds: Double): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli((epochSeconds * 1_000.0).toLong()).atZone(zone)
    val formatter = if (dateTime.toLocalDate() == LocalDate.now(zone)) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
    return "Limit resets at ${formatter.format(dateTime)}"
}
