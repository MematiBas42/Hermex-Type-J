package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import io.github.jcartu.hermex.ui.components.InAppBrowser
import io.github.jcartu.hermex.ui.theme.CodeTextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/** Markdown renderer with math, diff rows and chat-aware link routing. */
@Composable
fun TranscriptMarkdown(
    content: String,
    wrapCodeLines: Boolean,
    modifier: Modifier = Modifier,
    onOpenWeb: ((String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    hapticsEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val systemUriHandler = LocalUriHandler.current
    val webHandler = onOpenWeb ?: remember(context) {
        { url: String -> InAppBrowser.open(context, url) }
    }
    val uriHandler = remember(webHandler, onOpenWorkspaceFile, systemUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                when {
                    isWebUri(uri) -> webHandler(uri)
                    onOpenWorkspaceFile != null && isWorkspaceFileUri(uri) ->
                        onOpenWorkspaceFile.invoke(workspacePathFromUri(uri))
                    else -> systemUriHandler.openUri(uri)
                }
            }
        }
    }
    val renderedContent = remember(content) { MarkdownMathFormatter.format(content) }
    val tableSegments = remember(renderedContent) { MarkdownTableParser.segments(renderedContent) }
    val components = markdownComponents(
        codeFence = { model ->
            MarkdownCodeFence(
                content = model.content,
                node = model.node,
                style = CodeTextStyle,
            ) { code, language, _ ->
                MessageCodeBlock(code, language, wrapCodeLines, hapticsEnabled)
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(
                content = model.content,
                node = model.node,
                style = CodeTextStyle,
            ) { code, language, _ ->
                MessageCodeBlock(code, language, wrapCodeLines, hapticsEnabled)
            }
        },
    )

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Column(modifier = modifier.fillMaxWidth()) {
            tableSegments.forEach { segment ->
                when (segment) {
                    is MarkdownTableSegment.Markdown -> if (segment.content.isNotEmpty()) {
                        Markdown(
                            content = segment.content,
                            modifier = Modifier.fillMaxWidth(),
                            typography = transcriptMarkdownTypography(),
                            components = components,
                        )
                    }
                    is MarkdownTableSegment.Table -> MarkdownTableView(segment)
                }
            }
        }
    }
}

/**
 * Chat-scale Markdown type: the renderer's M3 defaults put `h1` at
 * `displayLarge`, which dwarfs a chat bubble. iOS renders headings a step or
 * two above body text, so headings here top out at `titleLarge`.
 */
@Composable
private fun transcriptMarkdownTypography() = androidx.compose.material3.MaterialTheme.typography.let { t ->
    val bold = androidx.compose.ui.text.font.FontWeight.SemiBold
    markdownTypography(
        h1 = t.titleLarge.copy(fontWeight = bold),
        h2 = t.titleMedium.copy(fontWeight = bold),
        h3 = t.titleSmall.copy(fontWeight = bold),
        h4 = t.bodyLarge.copy(fontWeight = bold),
        h5 = t.bodyMedium.copy(fontWeight = bold),
        h6 = t.bodyMedium.copy(fontWeight = bold),
        text = t.bodyLarge,
        paragraph = t.bodyLarge,
        code = CodeTextStyle,
        inlineCode = CodeTextStyle,
    )
}

@Composable
private fun MessageCodeBlock(
    code: String,
    language: String?,
    wrapCodeLines: Boolean,
    hapticsEnabled: Boolean,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var didCopy by remember(code) { mutableStateOf(false) }
    LaunchedEffect(didCopy) {
        if (!didCopy) return@LaunchedEffect
        delay(1_200)
        didCopy = false
    }
    val normalizedLanguage = language
        ?.trim()
        ?.takeWhile { !it.isWhitespace() }
        ?.lowercase(Locale.ROOT)
    val diff = remember(code, normalizedLanguage) {
        if (normalizedLanguage == "diff" || normalizedLanguage == "patch") MarkdownDiffParser.parse(code) else null
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(displayLanguage(language), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            didCopy = true
                        },
                    ) {
                        Icon(
                            imageVector = if (didCopy) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                            contentDescription = if (didCopy) "Copied code" else "Copy code",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (diff != null) {
                    val body: @Composable () -> Unit = {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            diff.lines.forEach { line -> DiffLine(line) }
                        }
                    }
                    if (wrapCodeLines) {
                        body()
                    } else {
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { body() }
                    }
                } else if (wrapCodeLines) {
                    SelectionContainer {
                        Text(
                            text = code,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = CodeTextStyle,
                            softWrap = true,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                text = code,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = CodeTextStyle,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface MarkdownTableSegment {
    data class Markdown(val content: String) : MarkdownTableSegment
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownTableSegment
}

/** Small GFM table splitter so cell boundaries remain visible in either theme. */
private object MarkdownTableParser {
    fun segments(content: String): List<MarkdownTableSegment> {
        val lines = content.split('\n')
        val result = mutableListOf<MarkdownTableSegment>()
        val markdown = StringBuilder()
        fun flushMarkdown() {
            if (markdown.isNotEmpty()) {
                result += MarkdownTableSegment.Markdown(markdown.toString())
                markdown.clear()
            }
        }
        var index = 0
        var inFence = false
        while (index < lines.size) {
            val marker = fenceMarker(lines[index])
            if (marker != null) {
                inFence = !inFence
                markdown.append(lines[index])
                if (index < lines.lastIndex) markdown.append('\n')
                index += 1
                continue
            }
            if (!inFence && index + 1 < lines.size && looksLikeHeader(lines[index], lines[index + 1])) {
                flushMarkdown()
                val header = splitRow(lines[index])
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && '|' in lines[index] && lines[index].isNotBlank()) {
                    rows += splitRow(lines[index])
                    index += 1
                }
                result += MarkdownTableSegment.Table(header, rows)
                continue
            }
            markdown.append(lines[index])
            if (index < lines.lastIndex) markdown.append('\n')
            index += 1
        }
        flushMarkdown()
        return result.ifEmpty { listOf(MarkdownTableSegment.Markdown(content)) }
    }

    private fun looksLikeHeader(header: String, divider: String): Boolean {
        if ('|' !in header || '|' !in divider) return false
        val cells = splitRow(divider)
        return cells.isNotEmpty() && cells.all { Regex("^:?-{3,}:?$").matches(it.replace(" ", "")) }
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)

    private fun fenceMarker(line: String): Char? {
        val trimmed = line.dropWhile(Char::isWhitespace)
        return when {
            trimmed.startsWith("```") -> '`'
            trimmed.startsWith("~~~") -> '~'
            else -> null
        }
    }
}

@Composable
private fun MarkdownTableView(table: MarkdownTableSegment.Table) {
    val columnCount = maxOf(table.header.size, table.rows.maxOfOrNull(List<String>::size) ?: 0)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
                val rowColor = if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                val borderColor = MaterialTheme.colorScheme.outline
                Row(
                    modifier = Modifier.drawWithContent {
                        drawRect(rowColor)
                        drawContent()
                        val strokeWidth = 0.75.dp.toPx()
                        drawRect(borderColor, style = Stroke(strokeWidth))
                        for (column in 1 until columnCount) {
                            val x = column * 160.dp.toPx()
                            drawLine(borderColor, Offset(x, 0f), Offset(x, size.height), strokeWidth)
                        }
                    },
                ) {
                    repeat(columnCount) { column ->
                        Box(Modifier.width(160.dp)) {
                            val cellStyle = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Markdown(
                                content = row.getOrElse(column) { "" },
                                typography = markdownTypography(
                                    text = cellStyle,
                                    paragraph = cellStyle,
                                    code = CodeTextStyle,
                                    inlineCode = CodeTextStyle,
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isWebUri(value: String): Boolean {
    val scheme = runCatching { value.toUri().scheme?.lowercase(Locale.ROOT) }.getOrNull()
    return scheme == "http" || scheme == "https"
}

private fun isWorkspaceFileUri(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("file:", ignoreCase = true) ||
        trimmed.startsWith('/') || trimmed.startsWith("./") ||
        trimmed.startsWith("../") || trimmed.startsWith("~/")
}

private fun workspacePathFromUri(value: String): String {
    val trimmed = value.trim().removeSurrounding("<", ">")
    if (!trimmed.startsWith("file:", ignoreCase = true)) return trimmed
    val uri = trimmed.toUri()
    return uri.path.orEmpty() + uri.fragment?.let { "#$it" }.orEmpty()
}

private fun displayLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.takeWhile { !it.isWhitespace() }
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: return "Code"
    return when (normalized) {
        "js" -> "JavaScript"
        "ts" -> "TypeScript"
        "py" -> "Python"
        else -> normalized.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
