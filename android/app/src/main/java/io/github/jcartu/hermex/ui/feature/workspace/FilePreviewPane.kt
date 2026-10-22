package io.github.jcartu.hermex.ui.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import io.github.jcartu.hermex.core.media.DocumentPreview
import io.github.jcartu.hermex.core.media.DocumentPreviewOpenResult
import io.github.jcartu.hermex.core.media.DocumentPreviewRoute
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

/** Port of `Features/Workspace/FilePreviewView.swift`. */
@Composable
internal fun FilePreviewPane(
    state: WorkspaceUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean,
) {
    ConstrainedPane(maxWidth = 840.dp, modifier = modifier) {
        val entry = state.selectedEntry
        if (entry == null) {
            EmptyState("Select a file", message = "Choose a file from the tree to preview it.")
            return@ConstrainedPane
        }

        val source = state.preview as? WorkspaceFilePreview.Text
        if (source != null && !source.isMarkdown) {
            SourceViewerScreen(
                content = source.file.content.orEmpty(),
                path = source.file.path ?: entry.path ?: entry.name.orEmpty(),
                language = source.file.language,
                targetLine = state.selectedLine,
                onBack = onClose,
                modifier = Modifier.fillMaxSize(),
            )
            return@ConstrainedPane
        }

        PreviewHeader(state, onClose, showBack)
        HorizontalDivider()
        when {
            state.isLoadingPreview -> LoadingState(label = "Loading file…")
            state.preview is WorkspaceFilePreview.Failed -> ErrorState(state.preview.message)
            state.preview is WorkspaceFilePreview.Unavailable -> EmptyState(
                "No Preview",
                message = "${state.preview.message}\n${entry.path.orEmpty()}".trim(),
            )
            state.preview is WorkspaceFilePreview.Document -> DocumentFilePreview(state.preview)
            state.preview is WorkspaceFilePreview.Text -> MarkdownFilePreview(state.preview)
            else -> EmptyState("No Preview", message = entry.path)
        }
    }
}

@Composable
private fun PreviewHeader(state: WorkspaceUiState, onClose: () -> Unit, showBack: Boolean) {
    val entry = state.selectedEntry ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                if (showBack) "Back to files" else "Close preview",
            )
        }
        FileTypeIcon(entry.path ?: entry.name.orEmpty(), Modifier.padding(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name?.takeIf(String::isNotBlank) ?: "File",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
            previewMetadataText(state.preview)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        (state.preview as? WorkspaceFilePreview.Text)?.let { preview ->
            val context = LocalContext.current
            IconButton(onClick = { copyText(context, preview.file.content.orEmpty(), preview.file.name ?: "file") }) {
                Icon(Icons.Filled.ContentCopy, "Copy file contents")
            }
        }
    }
}

@Composable
private fun DocumentFilePreview(preview: WorkspaceFilePreview.Document) {
    val context = LocalContext.current
    var openError by remember(preview.path) { mutableStateOf<String?>(null) }
    var lightbox by remember(preview.path) { mutableStateOf(false) }

    fun openDocument() {
        when (val result = DocumentPreview.open(context, preview.filename, preview.mimeType, preview.bytes)) {
            is DocumentPreviewOpenResult.Rejected -> openError = result.message
            is DocumentPreviewOpenResult.External,
            is DocumentPreviewOpenResult.InApp,
            -> openError = null
        }
    }

    when (preview.route) {
        DocumentPreviewRoute.IMAGE -> {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    AsyncImage(
                        model = preview.bytes,
                        contentDescription = preview.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { lightbox = true }
                            .padding(16.dp),
                    )
                    Text(
                        "Tap to zoom",
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (lightbox) ImageLightbox(preview.bytes, preview.filename, preview.path) { lightbox = false }
        }
        DocumentPreviewRoute.PDF,
        DocumentPreviewRoute.EXTERNAL,
        -> {
            LaunchedEffect(preview.path) { openDocument() }
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (preview.route == DocumentPreviewRoute.PDF) "PDF preview" else "Open with another app",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = ::openDocument, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Filled.OpenInNew, null)
                    Text(if (preview.route == DocumentPreviewRoute.PDF) "Open PDF" else "Open file", Modifier.padding(start = 8.dp))
                }
                openError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        DocumentPreviewRoute.TEXT -> Unit
    }
}

@Composable
private fun MarkdownFilePreview(preview: WorkspaceFilePreview.Text) {
    val content = preview.file.content.orEmpty()
    val chunks = preview.markdownChunks ?: listOf(MarkdownPreviewChunk(0, content, 0))
    var lightbox by remember { mutableStateOf<MarkdownImageBlock?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(chunks, key = MarkdownPreviewChunk::id) { chunk ->
            Column(Modifier.fillMaxWidth().padding(top = chunk.topSpacingDp.dp)) {
                markdownBlocks(chunk.text, preview.markdownImages).forEach { block ->
                    when (block) {
                        is MarkdownTextBlock -> if (block.text.isNotBlank()) {
                            Markdown(block.text, modifier = Modifier.fillMaxWidth())
                        }
                        is MarkdownImageBlock -> {
                            if (block.bytes != null) {
                                AsyncImage(
                                    model = block.bytes,
                                    contentDescription = block.alt.ifBlank { preview.file.name },
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { lightbox = block }
                                        .padding(vertical = 8.dp),
                                )
                            } else {
                                Text(
                                    block.alt.ifBlank { block.destination },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    lightbox?.let { image ->
        image.bytes?.let { bytes ->
            ImageLightbox(bytes, image.alt.ifBlank { preview.file.name ?: "Image" }, image.destination) {
                lightbox = null
            }
        }
    }
}

private sealed interface MarkdownBlock
private data class MarkdownTextBlock(val text: String) : MarkdownBlock
private data class MarkdownImageBlock(
    val alt: String,
    val destination: String,
    val bytes: ByteArray?,
) : MarkdownBlock

/** Splits a Markdown chunk around image syntax so loaded images stay inline. */
private fun markdownBlocks(content: String, images: Map<String, ByteArray>): List<MarkdownBlock> {
    val pattern = Regex("!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+[\"'][^)]*[\"'])?\\)")
    val blocks = ArrayList<MarkdownBlock>()
    var cursor = 0
    for (match in pattern.findAll(content)) {
        if (match.range.first > cursor) blocks += MarkdownTextBlock(content.substring(cursor, match.range.first))
        val destination = match.groupValues[2].removeSurrounding("<", ">")
        blocks += MarkdownImageBlock(match.groupValues[1], destination, images[destination])
        cursor = match.range.last + 1
    }
    if (cursor < content.length) blocks += MarkdownTextBlock(content.substring(cursor))
    if (blocks.isEmpty()) blocks += MarkdownTextBlock(content)
    return blocks
}

/** Text preview path prefers the server-reported path. */
internal fun previewDisplayPath(state: WorkspaceUiState): String {
    val preview = state.preview
    return when (preview) {
        is WorkspaceFilePreview.Text -> preview.file.path ?: state.selectedEntry?.path
        is WorkspaceFilePreview.Document -> preview.path
        else -> state.selectedEntry?.path
    }?.trim()?.takeIf(String::isNotEmpty)
        ?: state.selectedEntry?.name?.trim()?.takeIf(String::isNotEmpty)
        ?: "File"
}

/** `1.2 KB · 42 lines` metadata matching upstream. */
internal fun previewMetadataText(preview: WorkspaceFilePreview?): String? {
    val parts = when (preview) {
        is WorkspaceFilePreview.Text -> buildList {
            preview.file.size?.let { add(formatByteCount(it.toLong())) }
            preview.file.lines?.let { add(if (it == 1) "1 line" else "$it lines") }
        }
        is WorkspaceFilePreview.Document -> listOf(formatByteCount(preview.bytes.size.toLong()))
        else -> emptyList()
    }
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}

private fun copyText(context: Context, text: String, label: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Kept for callers/tests that need the upstream right-aligned gutter string. */
internal fun lineNumberGutter(lineCount: Int): String {
    val count = lineCount.coerceAtLeast(1)
    val width = count.toString().length
    return (1..count).joinToString("\n") { it.toString().padStart(width) }
}
