package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import io.github.jcartu.hermex.core.media.DocumentPreview
import io.github.jcartu.hermex.core.media.DocumentPreviewOpenResult
import io.github.jcartu.hermex.model.ImagePreviewDownsampler
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.inferredIsAudio
import java.util.Locale
import kotlinx.coroutines.launch

private const val GRID_COLUMNS = 2
private val CELL_SIZE = 118.dp
private val CELL_SPACING = 8.dp

/** Transcript attachment grid + inline audio, port of `MessageBubbleView.swift`. */
@Composable
fun MessageAttachmentsGroup(
    attachments: List<MessageAttachment>,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    attachmentDataResolver: suspend (path: String) -> ByteArray? = { null },
    onOpenWorkspaceFile: (String) -> Unit = {},
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewError by remember { mutableStateOf<String?>(null) }
    val audioItems = attachments.filter { it.inferredIsAudio }
    val gridItems = attachments.filterNot { it.inferredIsAudio }
    val contentWidth = CELL_SIZE * GRID_COLUMNS + CELL_SPACING

    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        audioItems.forEach { attachment ->
            val path = attachment.resolvedAttachmentPath
            InlineAudioPlayerView(
                title = attachmentDisplayName(attachment, "Audio"),
                load = { path?.let { attachmentDataResolver(it) } },
                modifier = Modifier.widthIn(max = contentWidth),
            )
        }
        gridItems.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_SPACING)) {
                row.forEach { attachment ->
                    if (attachment.inferredIsImage) {
                        ImageAttachmentCell(attachment, attachmentUrlResolver, imageLoader)
                    } else {
                        FileAttachmentCell(attachment) {
                            val path = attachment.resolvedAttachmentPath ?: return@FileAttachmentCell
                            scope.launch {
                                val bytes = attachmentDataResolver(path)
                                if (bytes == null) {
                                    onOpenWorkspaceFile(path)
                                } else {
                                    val result = DocumentPreview.open(
                                        context = context,
                                        filename = attachmentDisplayName(attachment),
                                        mimeType = attachment.mime,
                                        bytes = bytes,
                                    )
                                    previewError = (result as? DocumentPreviewOpenResult.Rejected)?.message
                                }
                            }
                        }
                    }
                }
            }
        }
        previewError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.widthIn(max = contentWidth),
            )
        }
    }
}

internal val MessageAttachment.inferredIsImage: Boolean
    get() {
        if (isImage == true) return true
        if (mime?.lowercase(Locale.ROOT)?.startsWith("image/") == true) return true
        return fileExtension(name ?: resolvedAttachmentPath.orEmpty()) in IMAGE_EXTENSIONS
    }

internal val MessageAttachment.resolvedAttachmentPath: String?
    get() = path?.takeIf(String::isNotEmpty) ?: name?.takeIf(String::isNotEmpty)

internal fun attachmentDisplayName(attachment: MessageAttachment, fallback: String = "File"): String {
    attachment.name?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    attachment.path?.trim()?.takeIf(String::isNotEmpty)?.let { path ->
        return MessageAttachment.lastPathComponent(path).ifEmpty { path }
    }
    return fallback
}

@Composable
private fun ImageAttachmentCell(
    attachment: MessageAttachment,
    attachmentUrlResolver: suspend (String) -> String?,
    imageLoader: ImageLoader?,
) {
    val path = attachment.resolvedAttachmentPath
    val resolvedUrl by produceState<String?>(null, path) {
        value = path?.let { attachmentUrlResolver(it) }
    }
    var lightbox by remember(path) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.size(CELL_SIZE).clickable(enabled = resolvedUrl != null) { lightbox = true },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val url = resolvedUrl
        if (url == null) {
            AttachmentImagePlaceholder(path != null)
        } else {
            val context = LocalContext.current
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(ImagePreviewDownsampler.attachmentMaxPixelSize)
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                imageLoader = imageLoader ?: context.imageLoader,
                contentDescription = "Image attachment ${attachmentDisplayName(attachment, "image")}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { AttachmentImagePlaceholder(true) },
                error = { AttachmentImagePlaceholder(false) },
            )
        }
    }
    if (lightbox && resolvedUrl != null) {
        ImageLightboxView(
            model = resolvedUrl!!,
            title = attachmentDisplayName(attachment, "Image"),
            imageLoader = imageLoader,
            onDismiss = { lightbox = false },
        )
    }
}

@Composable
private fun AttachmentImagePlaceholder(showsProgress: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showsProgress) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.BrokenImage, "Image unavailable", modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun FileAttachmentCell(attachment: MessageAttachment, onClick: () -> Unit) {
    val ext = fileExtension(attachment.name ?: attachment.path.orEmpty())
    val displayName = attachmentDisplayName(attachment)
    val sizeLabel = attachment.size?.let(::humanReadableSize)
    val badge = buildString {
        append(ext.uppercase(Locale.ROOT).take(5).ifEmpty { "FILE" })
        sizeLabel?.let { append(" · "); append(it) }
    }
    Surface(
        modifier = Modifier.size(CELL_SIZE).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxSize().padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(fileIcon(ext), "File attachment $displayName", tint = fileBadgeColor(ext), modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(5.dp))
            Text(
                displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(5.dp))
            Text(
                badge,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = fileBadgeColor(ext),
                maxLines = 1,
            )
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "tif")

private fun fileExtension(path: String): String {
    val component = path.trimEnd('/').substringAfterLast('/')
    val dot = component.lastIndexOf('.')
    return if (dot <= 0) "" else component.substring(dot + 1).lowercase(Locale.ROOT)
}

private fun fileIcon(ext: String): ImageVector = when (ext) {
    "csv", "tsv", "xls", "xlsx" -> Icons.Rounded.TableChart
    "json", "md", "txt", "log", "xml", "yaml", "yml" -> Icons.Rounded.Description
    "pdf" -> Icons.Rounded.PictureAsPdf
    "zip", "tar", "gz", "tgz" -> Icons.Rounded.Archive
    else -> Icons.Rounded.InsertDriveFile
}

@Composable
private fun fileBadgeColor(ext: String): Color = when (ext) {
    "csv", "tsv", "xls", "xlsx" -> Color(0xFF34C759)
    "pdf" -> MaterialTheme.colorScheme.error
    "json", "md", "txt", "log", "xml", "yaml", "yml" -> Color(0xFF409CFF)
    else -> MaterialTheme.colorScheme.primary
}

internal fun humanReadableSize(bytes: Int): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
