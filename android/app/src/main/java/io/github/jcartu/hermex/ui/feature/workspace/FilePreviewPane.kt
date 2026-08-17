package io.github.jcartu.hermex.ui.feature.workspace

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.CodeTextStyle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Port of `Features/Workspace/FilePreviewView.swift`: text files render in a
 * monospace viewer (line numbers, copy, wrap toggle), markdown renders rich,
 * raster images load from the raw-file endpoint, known binaries show a hint.
 */
@Composable
internal fun FilePreviewPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    showBack: Boolean,
) {
    ConstrainedPane(maxWidth = 840.dp, modifier = modifier) {
        val entry = state.selectedEntry
        if (entry == null) {
            EmptyState(
                title = "Select a file",
                message = "Choose a file from the browser to preview it.",
            )
            return@ConstrainedPane
        }

        PreviewHeader(state, viewModel, showBack)
        HorizontalDivider()

        when {
            state.isLoadingPreview -> LoadingState(label = "Loading file...")
            state.preview is WorkspaceFilePreview.Failed ->
                ErrorState(message = state.preview.message)
            state.preview is WorkspaceFilePreview.Unavailable ->
                EmptyState(title = "No Preview", message = "${state.preview.message}\n${entry.path ?: ""}".trim())
            state.preview is WorkspaceFilePreview.Image ->
                ImagePreview(url = state.preview.url, contentDescription = entry.name)
            state.preview is WorkspaceFilePreview.Text ->
                TextPreview(file = state.preview.file, isMarkdown = state.preview.isMarkdown)
        }
    }
}

@Composable
private fun PreviewHeader(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    showBack: Boolean,
) {
    val entry = state.selectedEntry ?: return
    val preview = state.preview
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showBack) {
            IconButton(onClick = { viewModel.closePreview() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to files")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "File",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                previewDisplayPath(state),
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            previewMetadataText(preview)?.let { metadata ->
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!showBack) {
            IconButton(onClick = { viewModel.closePreview() }) {
                Icon(Icons.Filled.Close, contentDescription = "Close preview")
            }
        }
    }
}

/** Text preview path prefers the server-reported path, like the iOS displayPath. */
internal fun previewDisplayPath(state: WorkspaceUiState): String {
    val preview = state.preview
    val path = if (preview is WorkspaceFilePreview.Text) {
        preview.file.path ?: state.selectedEntry?.path
    } else {
        state.selectedEntry?.path
    }
    return path?.trim().takeUnless { it.isNullOrEmpty() }
        ?: state.selectedEntry?.name?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "File"
}

/** "1.2 KB - 42 lines" metadata line, mirroring FilePreviewView.metadataText. */
internal fun previewMetadataText(preview: WorkspaceFilePreview?): String? {
    if (preview !is WorkspaceFilePreview.Text) return null
    val parts = mutableListOf<String>()
    preview.file.size?.let { parts.add(formatByteCount(it.toLong())) }
    preview.file.lines?.let { parts.add(if (it == 1) "1 line" else "$it lines") }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" - ")
}

@Composable
private fun ImagePreview(url: String, contentDescription: String?) {
    val context = LocalContext.current
    val app = context.applicationContext as HermexApplication
    // The raw-file endpoint needs the auth cookie; Coil's default client has no jar.
    val imageLoader = remember(app) {
        ImageLoader.Builder(context).okHttpClient(app.container.okHttpClient).build()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TextPreview(file: FileResponse, isMarkdown: Boolean) {
    val content = file.content ?: ""
    var wrapLines by rememberSaveable(key = "workspacePreviewWrap") { androidx.compose.runtime.mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (!isMarkdown) {
                IconToggleButton(checked = wrapLines, onCheckedChange = { wrapLines = it }) {
                    Icon(
                        Icons.AutoMirrored.Filled.WrapText,
                        contentDescription = if (wrapLines) "Disable line wrap" else "Enable line wrap",
                        tint = if (wrapLines) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("file", content)))
                }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy file contents")
            }
        }
        if (isMarkdown) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Markdown(content, modifier = Modifier.fillMaxWidth())
            }
        } else {
            CodeFileViewer(content = content, wrapLines = wrapLines)
        }
    }
}

/**
 * Monospace file viewer. With wrapping off, a gutter of line numbers sits
 * beside the horizontally scrollable code — both texts share [CodeTextStyle]
 * (same font metrics) so rows stay aligned. Wrapping breaks the one-row-per-
 * line invariant, so the gutter hides while wrap is on.
 */
@Composable
internal fun CodeFileViewer(content: String, wrapLines: Boolean, modifier: Modifier = Modifier) {
    val lines = remember(content) { content.lines() }
    Row(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        if (!wrapLines) {
            val gutter = remember(lines) { lineNumberGutter(lines.size) }
            Text(
                gutter,
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                softWrap = false,
            )
            Spacer(Modifier.width(12.dp))
        }
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                content,
                style = CodeTextStyle,
                softWrap = wrapLines,
                modifier = if (wrapLines) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.horizontalScroll(rememberScrollState())
                },
            )
        }
    }
}

/** "  1\n  2\n…" right-aligned via fixed-width formatting (monospace font). */
internal fun lineNumberGutter(lineCount: Int): String {
    if (lineCount <= 0) return "1"
    val width = lineCount.toString().length
    return (1..lineCount).joinToString("\n") { String.format(Locale.US, "%${width}d", it) }
}
