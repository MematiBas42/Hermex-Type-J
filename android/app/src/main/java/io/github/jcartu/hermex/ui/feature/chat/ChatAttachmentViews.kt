package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Audiotrack
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
import androidx.compose.runtime.produceState
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
import io.github.jcartu.hermex.model.ImagePreviewDownsampler
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.inferredIsAudio
import java.util.Locale

/**
 * Transcript-side attachment rendering — port of the attachment previews in
 * `MessageBubbleView.swift` (audio bars stacked above a 2-column square
 * image/file grid). Audio playback is out of scope for the Android v1: an
 * audio attachment renders as a chip with the audiotrack glyph and its
 * display name instead of the full inline player.
 */
private const val GRID_COLUMNS = 2
private val CELL_SIZE = 118.dp
private val CELL_SPACING = 8.dp

@Composable
fun MessageAttachmentsGroup(
    attachments: List<MessageAttachment>,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
) {
    if (attachments.isEmpty()) return

    val audioItems = attachments.filter { it.inferredIsAudio }
    val gridItems = attachments.filter { !it.inferredIsAudio }
    val contentWidth = CELL_SIZE * GRID_COLUMNS + CELL_SPACING * (GRID_COLUMNS - 1)

    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        audioItems.forEach { attachment ->
            AudioAttachmentChip(
                attachment = attachment,
                modifier = Modifier.widthIn(max = contentWidth),
            )
        }

        gridItems.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_SPACING)) {
                row.forEach { attachment ->
                    if (attachment.inferredIsImage) {
                        ImageAttachmentCell(
                            attachment = attachment,
                            attachmentUrlResolver = attachmentUrlResolver,
                            imageLoader = imageLoader,
                        )
                    } else {
                        FileAttachmentCell(attachment = attachment)
                    }
                }
            }
        }
    }
}

/**
 * Image detection ported from `GridAttachmentCell.inferredIsImage`: explicit
 * server flag first, then MIME type, then file extension for older sessions.
 */
internal val MessageAttachment.inferredIsImage: Boolean
    get() {
        if (isImage == true) return true
        if (mime?.lowercase(Locale.ROOT)?.startsWith("image/") == true) return true
        val ext = fileExtension(name ?: resolvedAttachmentPath ?: "")
        return ext in imageExtensions
    }

/**
 * The server saves uploads to the workspace root. Use the explicit path when
 * available; for older sessions fall back to the filename.
 */
internal val MessageAttachment.resolvedAttachmentPath: String?
    get() {
        path?.takeIf { it.isNotEmpty() }?.let { return it }
        name?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

/** Display name mirroring `GridAttachmentCell.fileDisplayName`. */
internal fun attachmentDisplayName(attachment: MessageAttachment, fallback: String = "File"): String {
    attachment.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    attachment.path?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
        val lastComponent = MessageAttachment.lastPathComponent(path)
        return lastComponent.ifEmpty { path }
    }
    return fallback
}

@Composable
private fun AudioAttachmentChip(attachment: MessageAttachment, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Audiotrack,
                contentDescription = "Audio attachment",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = attachmentDisplayName(attachment, fallback = "Audio"),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ImageAttachmentCell(
    attachment: MessageAttachment,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
) {
    val path = attachment.resolvedAttachmentPath
    val resolvedUrl by produceState<String?>(initialValue = null, path) {
        value = path?.let { attachmentUrlResolver(it) }
    }

    Surface(
        modifier = modifier.size(CELL_SIZE),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        val url = resolvedUrl
        if (url == null) {
            AttachmentImagePlaceholder(showsProgress = path != null)
        } else {
            val context = LocalContext.current
            val request = ImageRequest.Builder(context)
                .data(url)
                // Same preview cap the iOS downsampler applies to attachments.
                .size(ImagePreviewDownsampler.attachmentMaxPixelSize)
                .build()
            SubcomposeAsyncImage(
                model = request,
                imageLoader = imageLoader ?: context.imageLoader,
                contentDescription = "Image attachment ${attachmentDisplayName(attachment, fallback = "image")}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { AttachmentImagePlaceholder(showsProgress = true) },
                error = { AttachmentImagePlaceholder(showsProgress = false) },
            )
        }
    }
}

@Composable
private fun AttachmentImagePlaceholder(showsProgress: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showsProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = "Image unavailable",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun FileAttachmentCell(attachment: MessageAttachment, modifier: Modifier = Modifier) {
    val ext = fileExtension(attachment.name ?: "")
    val displayName = attachmentDisplayName(attachment)
    val sizeLabel = attachment.size?.let { humanReadableSize(it) }
    val badge = buildString {
        append(if (ext.isEmpty()) "FILE" else ext.uppercase(Locale.ROOT).take(5))
        if (sizeLabel != null) {
            append(" · ")
            append(sizeLabel)
        }
    }

    Surface(
        modifier = modifier.size(CELL_SIZE),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = fileIcon(ext),
                contentDescription = "File attachment $displayName",
                tint = fileBadgeColor(ext),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(5.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(5.dp))
            Text(
                text = badge,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = fileBadgeColor(ext),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val imageExtensions =
    setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "tif")

/** `URL(fileURLWithPath:).pathExtension` semantics, lowercased. */
private fun fileExtension(path: String): String {
    val component = path.trimEnd('/').substringAfterLast('/')
    val dot = component.lastIndexOf('.')
    if (dot <= 0) return ""
    return component.substring(dot + 1).lowercase(Locale.ROOT)
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

/** Compact byte-count label for the file cell badge (e.g. "1.2 MB"). */
internal fun humanReadableSize(bytes: Int): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
