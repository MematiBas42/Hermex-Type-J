package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import io.github.jcartu.hermex.ui.theme.CodeTextStyle
import java.util.Locale

/** Renders message markdown after math-source preprocessing. */
@Composable
fun MessageMarkdown(
    content: String,
    wrapCodeLines: Boolean,
    modifier: Modifier = Modifier,
) {
    val renderedContent = remember(content) { MarkdownMathFormatter.format(content) }
    val components = markdownComponents(
        codeFence = { model ->
            MarkdownCodeFence(
                content = model.content,
                node = model.node,
                style = CodeTextStyle,
            ) { code, language, _ ->
                MessageCodeBlock(code = code, language = language, wrapCodeLines = wrapCodeLines)
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(
                content = model.content,
                node = model.node,
                style = CodeTextStyle,
            ) { code, language, _ ->
                MessageCodeBlock(code = code, language = language, wrapCodeLines = wrapCodeLines)
            }
        },
    )

    // The renderer's default annotator dispatches links through LocalUriHandler.
    Markdown(
        content = renderedContent,
        modifier = modifier.fillMaxWidth(),
        typography = markdownTypography(
            code = CodeTextStyle,
            inlineCode = CodeTextStyle,
        ),
        components = components,
    )
}

@Composable
private fun MessageCodeBlock(
    code: String,
    language: String?,
    wrapCodeLines: Boolean,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var didCopy by remember(code) { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayLanguage(language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            if (wrapCodeLines) {
                SelectionContainer {
                    Text(
                        text = code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = CodeTextStyle,
                        softWrap = true,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
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

private fun displayLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.takeWhile { !it.isWhitespace() }
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
        ?: return "Code"

    return when (normalized) {
        "js" -> "JavaScript"
        "ts" -> "TypeScript"
        "py" -> "Python"
        else -> if (normalized.uppercase(Locale.ROOT) == normalized) {
            normalized
        } else {
            normalized.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
