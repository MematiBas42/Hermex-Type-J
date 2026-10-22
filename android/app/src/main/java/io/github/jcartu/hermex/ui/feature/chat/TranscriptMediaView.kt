package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import io.github.jcartu.hermex.core.media.DocumentPreview
import io.github.jcartu.hermex.core.media.DocumentPreviewOpenResult
import kotlinx.coroutines.launch

/** Inline image/audio/file rendering ported from `TranscriptMediaView.swift`. */
@Composable
fun TranscriptMediaContentView(
    segments: List<TranscriptMediaSegment>,
    wrapCodeLines: Boolean,
    imageLoader: ImageLoader?,
    loadMediaUrl: suspend (TranscriptMediaReference) -> String?,
    loadMediaData: suspend (TranscriptMediaReference) -> ByteArray?,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
    onAskHermex: (String) -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is TranscriptMediaSegment.Text -> if (segment.markdown.isNotEmpty()) {
                    TranscriptMarkdown(
                        content = segment.markdown,
                        wrapCodeLines = wrapCodeLines,
                        onOpenWeb = onOpenWeb,
                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                        hapticsEnabled = hapticsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is TranscriptMediaSegment.Media -> TranscriptMediaTile(
                    reference = segment.reference,
                    imageLoader = imageLoader,
                    loadMediaUrl = loadMediaUrl,
                    loadMediaData = loadMediaData,
                    onOpenWeb = onOpenWeb,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                )
            }
        }
    }
}

@Composable
private fun TranscriptMediaTile(
    reference: TranscriptMediaReference,
    imageLoader: ImageLoader?,
    loadMediaUrl: suspend (TranscriptMediaReference) -> String?,
    loadMediaData: suspend (TranscriptMediaReference) -> ByteArray?,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
) {
    when (reference.mediaKind) {
        TranscriptMediaKind.IMAGE -> TranscriptImageTile(reference, imageLoader, loadMediaUrl)
        TranscriptMediaKind.AUDIO -> InlineAudioPlayerView(
            title = reference.displayName,
            load = { loadMediaData(reference) },
            modifier = Modifier.widthIn(max = 340.dp),
        )
        TranscriptMediaKind.VIDEO -> TranscriptFileTile(reference, Icons.Rounded.Movie) {
            openReference(reference, onOpenWeb, onOpenWorkspaceFile)
        }
        TranscriptMediaKind.DOCUMENT -> TranscriptDocumentTile(
            reference = reference,
            loadMediaData = loadMediaData,
            onFallback = { openReference(reference, onOpenWeb, onOpenWorkspaceFile) },
        )
        TranscriptMediaKind.UNSUPPORTED -> TranscriptFileTile(reference, Icons.Rounded.Description) {
            openReference(reference, onOpenWeb, onOpenWorkspaceFile)
        }
    }
}

@Composable
private fun TranscriptDocumentTile(
    reference: TranscriptMediaReference,
    loadMediaData: suspend (TranscriptMediaReference) -> ByteArray?,
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(reference.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TranscriptFileTile(reference, Icons.Rounded.Description) {
            scope.launch {
                val bytes = loadMediaData(reference)
                if (bytes == null) {
                    onFallback()
                } else {
                    val result = DocumentPreview.open(
                        context = context,
                        filename = reference.displayName,
                        mimeType = null,
                        bytes = bytes,
                    )
                    error = (result as? DocumentPreviewOpenResult.Rejected)?.message
                }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TranscriptImageTile(
    reference: TranscriptMediaReference,
    imageLoader: ImageLoader?,
    loadMediaUrl: suspend (TranscriptMediaReference) -> String?,
) {
    val context = LocalContext.current
    val model by produceState<Any?>(initialValue = null, reference.id) {
        value = loadMediaUrl(reference)
    }
    var lightbox by remember(reference.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .height(190.dp)
            .clickable(enabled = model != null) { lightbox = true },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val imageModel = model
        if (imageModel == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        } else {
            SubcomposeAsyncImage(
                model = imageModel,
                imageLoader = imageLoader ?: context.imageLoader,
                contentDescription = reference.accessibilityName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
                loading = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Photo, "Image unavailable", modifier = Modifier.size(32.dp))
                    }
                },
            )
        }
    }
    if (lightbox && model != null) {
        ImageLightboxView(
            model = model!!,
            title = reference.accessibilityName,
            imageLoader = imageLoader,
            onDismiss = { lightbox = false },
        )
    }
}

@Composable
private fun TranscriptFileTile(
    reference: TranscriptMediaReference,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(max = 340.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    text = reference.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = reference.extension.uppercase().ifBlank { "FILE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openReference(
    reference: TranscriptMediaReference,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
) {
    when (val source = reference.source) {
        is TranscriptMediaSource.RemoteUrl -> onOpenWeb(source.url)
        is TranscriptMediaSource.LocalPath -> onOpenWorkspaceFile(reference.workspacePath ?: source.path)
    }
}
