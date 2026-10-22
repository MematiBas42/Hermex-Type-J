package io.github.jcartu.hermex.ui.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

/**
 * Port of `Workspace/SourceViewer/SourceFileSurface.swift`: a lazy source
 * surface with a line gutter, lightweight syntax colour, target-line reveal,
 * line wrapping, Copy, and Android share.
 */
@Composable
fun SourceViewerScreen(
    content: String,
    path: String,
    modifier: Modifier = Modifier,
    language: String? = null,
    targetLine: Int? = null,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var wrapsLines by rememberSaveable(path) { androidx.compose.runtime.mutableStateOf(false) }
    val document = remember(content, path, language) { SourceTokenizer.tokenize(content, path, language) }
    val listState = rememberLazyListState()
    val horizontalState = androidx.compose.foundation.rememberScrollState()
    val selectedIndex = targetLine?.minus(1)?.takeIf { it in document.lines.indices }

    LaunchedEffect(selectedIndex, document.lines.size) {
        selectedIndex?.let { listState.scrollToItem(it, scrollOffset = -72) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Source viewer for $path" },
    ) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                onBack?.let { back ->
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        path.substringAfterLast('/').ifEmpty { "Source" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                IconToggleButton(checked = wrapsLines, onCheckedChange = { wrapsLines = it }) {
                    Icon(
                        Icons.AutoMirrored.Filled.WrapText,
                        contentDescription = if (wrapsLines) "Disable line wrap" else "Enable line wrap",
                        tint = if (wrapsLines) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { copySource(context, content, path) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy file contents")
                }
                IconButton(onClick = { shareSource(context, content, path) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share file contents")
                }
            }
        }
        HorizontalDivider()
        if (document.language == null) {
            Text(
                "Plain text",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val digits = document.lines.size.coerceAtLeast(1).toString().length
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(document.lines, key = SourceLine::number) { line ->
                SourceViewerRow(
                    line = line,
                    digits = digits,
                    wrapsLines = wrapsLines,
                    selected = line.number - 1 == selectedIndex,
                    horizontalState = horizontalState,
                )
            }
        }
    }
}

/** One lazily composed source line shared by Files and document previews. */
@Composable
private fun SourceViewerRow(
    line: SourceLine,
    digits: Int,
    wrapsLines: Boolean,
    selected: Boolean,
    horizontalState: androidx.compose.foundation.ScrollState,
) {
    val colors = SourceTokenColors(
        keyword = MaterialTheme.colorScheme.primary,
        string = Color(0xFF9ECE6A),
        comment = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        number = Color(0xFFFF9E64),
        markup = Color(0xFFBB9AF7),
    )
    val styled = remember(line, colors) { highlightedLine(line, colors) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color.Transparent)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            line.number.toString().padStart(digits),
            modifier = Modifier
                .widthIn(min = (digits * 9 + 20).dp)
                .padding(start = 8.dp, end = 10.dp),
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            softWrap = false,
        )
        Text(
            styled,
            modifier = if (wrapsLines) {
                Modifier.weight(1f).padding(end = 12.dp)
            } else {
                Modifier.horizontalScroll(horizontalState).padding(end = 24.dp)
            },
            style = CodeTextStyle,
            softWrap = wrapsLines,
        )
    }
}

private data class SourceTokenColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val markup: Color,
)

private fun highlightedLine(line: SourceLine, colors: SourceTokenColors): AnnotatedString = buildAnnotatedString {
    append(line.text.ifEmpty { " " })
    for (token in line.tokens) {
        val start = token.start.coerceIn(0, length)
        val end = token.endExclusive.coerceIn(start, length)
        if (start == end) continue
        val color = when (token.kind) {
            SourceTokenKind.KEYWORD -> colors.keyword
            SourceTokenKind.STRING -> colors.string
            SourceTokenKind.COMMENT -> colors.comment
            SourceTokenKind.NUMBER -> colors.number
            SourceTokenKind.MARKUP -> colors.markup
        }
        addStyle(SpanStyle(color = color), start, end)
    }
}

private fun copySource(context: Context, content: String, path: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(path.substringAfterLast('/'), content))
}

private fun shareSource(context: Context, content: String, path: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, path.substringAfterLast('/'))
        .putExtra(Intent.EXTRA_TEXT, content)
    context.startActivity(Intent.createChooser(intent, "Share source").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
