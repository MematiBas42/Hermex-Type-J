package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A turn's reasoning as the same compact row as tool activity. Port of
 * `Features/Chat/ReasoningBlockView.swift`.
 */
@Composable
fun ReasoningBlock(
    text: String,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    isStreaming: Boolean = false,
    hapticsEnabled: Boolean = true,
) {
    val displayText = remember(text) { text.trim().takeIf(String::isNotEmpty) } ?: return
    var userToggled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: startExpanded
    val summary = remember(displayText) { ReasoningSummaryFormatter.summary(displayText) }

    TranscriptLogRow(
        summary = "Thinking",
        detail = summary,
        isFailure = false,
        isExpanded = expanded,
        accessibilityLabel = "Thinking, $summary",
        copyText = { displayText },
        onToggle = { userToggled = !expanded },
        hapticsEnabled = hapticsEnabled,
        modifier = modifier,
        leading = {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        expandedBody = {
            SelectionContainer {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** Port of `ReasoningSummaryFormatter.swift`. */
object ReasoningSummaryFormatter {
    private const val LIMIT = 80

    fun summary(value: String): String {
        val oneLine = value.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
        return if (oneLine.length <= LIMIT) oneLine else oneLine.take(LIMIT - 1) + "…"
    }
}
