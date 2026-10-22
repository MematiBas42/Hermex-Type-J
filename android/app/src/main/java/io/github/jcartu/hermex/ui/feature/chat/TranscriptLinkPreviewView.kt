package io.github.jcartu.hermex.ui.feature.chat

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ChatMessage

/** Pure URL extraction ported from `TranscriptLinkPreview.swift`. */
object TranscriptLinkPreviewExtractor {
    private val WEB_URL = Regex("https?://[^\\s<>()]+", RegexOption.IGNORE_CASE)

    fun firstWebUrl(markdown: String): String? {
        var inFence = false
        var fence: Char? = null
        markdown.lineSequence().forEach { line ->
            val marker = fenceMarker(line)
            if (inFence) {
                if (marker == fence) {
                    inFence = false
                    fence = null
                }
                return@forEach
            }
            if (marker != null) {
                inFence = true
                fence = marker
                return@forEach
            }
            if (line.startsWith("    ") || line.startsWith('\t')) return@forEach
            nonCodeSegments(line).forEach { segment ->
                WEB_URL.find(segment)?.value?.trimEnd('.', ',', ';', ':', '!', '?')?.let { return it }
            }
        }
        return null
    }

    private fun nonCodeSegments(line: String): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        var textStart = 0
        while (cursor < line.length) {
            if (line[cursor] != '`') {
                cursor += 1
                continue
            }
            var openEnd = cursor
            while (openEnd < line.length && line[openEnd] == '`') openEnd += 1
            val length = openEnd - cursor
            var close = openEnd
            while (close < line.length) {
                if (line[close] == '`') {
                    var closeEnd = close
                    while (closeEnd < line.length && line[closeEnd] == '`') closeEnd += 1
                    if (closeEnd - close == length) {
                        if (textStart < cursor) result += line.substring(textStart, cursor)
                        cursor = closeEnd
                        textStart = cursor
                        break
                    }
                    close = closeEnd
                } else {
                    close += 1
                }
            }
            if (close >= line.length) cursor = openEnd
        }
        if (textStart < line.length) result += line.substring(textStart)
        return result
    }

    private fun fenceMarker(line: String): Char? {
        val trimmed = line.dropWhile(Char::isWhitespace)
        return when {
            trimmed.startsWith("```") -> '`'
            trimmed.startsWith("~~~") -> '~'
            else -> null
        }
    }
}

object TranscriptLinkPreviewEligibility {
    fun previewUrl(message: ChatMessage, isStreaming: Boolean): String? {
        if (isStreaming || (message.role != "user" && message.role != "assistant")) return null
        return message.content?.let(TranscriptLinkPreviewExtractor::firstWebUrl)
    }
}

/** Compact, redraw-stable web link card. */
@Composable
fun TranscriptLinkPreviewView(
    url: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val host = uri?.host?.takeIf(String::isNotBlank) ?: "Link"
    Surface(
        onClick = { onOpen(url) },
        modifier = modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
