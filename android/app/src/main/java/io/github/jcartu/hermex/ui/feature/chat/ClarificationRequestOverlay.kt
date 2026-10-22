package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Clarification surface ported from `ClarificationRequestCard.swift`. Unlike
 * approvals it is not modal: ChatPane pins it directly above the composer so
 * the question remains reachable above the IME (#1.6/#1.7).
 */
@Composable
fun ClarificationRequestOverlay(
    prompt: ClarificationPromptState,
    isResponding: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(prompt.id) { mutableStateOf("") }
    val response = draft.trim()
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 5.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClarificationHeader(prompt)
            Text(
                text = prompt.pending.displayQuestion,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(11.dp))
                    .padding(11.dp),
            )
            prompt.pending.displayChoices.forEach { choice ->
                OutlinedButton(
                    onClick = { onSubmit(choice) },
                    enabled = !isResponding,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                ) {
                    Text(choice, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a response") },
                    enabled = !isResponding,
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(13.dp),
                )
                IconButton(
                    onClick = { if (response.isNotEmpty()) onSubmit(response) },
                    enabled = !isResponding && response.isNotEmpty(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (isResponding) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.ArrowUpward, "Submit clarification")
                    }
                }
            }
            errorMessage?.trim()?.takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ClarificationHeader(prompt: ClarificationPromptState) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.HelpOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Clarification required", style = MaterialTheme.typography.titleMedium)
            if (prompt.pendingCount > 1) {
                Text(
                    "1 of ${prompt.pendingCount} pending",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ClarificationExpirationBadge(prompt)
    }
}

@Composable
private fun ClarificationExpirationBadge(prompt: ClarificationPromptState) {
    val expiresAt = prompt.pending.expiresAt
    val timeout = prompt.pending.timeoutSeconds
    if (expiresAt == null && timeout == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(prompt.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remaining = expiresAt?.let { max(0.0, it - now / 1_000.0) }
    val fraction = if (remaining != null && timeout != null && timeout > 0) {
        min(1.0, remaining / timeout).toFloat()
    } else {
        1f
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = when {
                remaining == null -> "Timeout ${durationText(timeout?.toDouble() ?: 0.0)}"
                remaining <= 0 -> "Expired"
                else -> "${durationText(remaining)} left"
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.width(68.dp).height(4.dp),
        )
    }
}

private fun durationText(seconds: Double): String {
    val value = max(0, ceil(seconds).toInt())
    val minutes = value / 60
    val remainder = value % 60
    return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
}
