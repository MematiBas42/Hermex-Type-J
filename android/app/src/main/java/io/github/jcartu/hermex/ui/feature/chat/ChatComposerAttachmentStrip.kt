package io.github.jcartu.hermex.ui.feature.chat

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.AttachmentAudioDetection
import io.github.jcartu.hermex.model.PendingAttachment

/** Horizontal staged-attachment strip above the composer input. */
@Composable
internal fun ChatComposerAttachmentStrip(
    attachments: List<PendingAttachment>,
    isUploading: Boolean,
    errorMessage: String?,
    onRemove: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && !isUploading && errorMessage == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (errorMessage != null) {
            AttachmentErrorRow(
                message = errorMessage,
                onDismiss = onDismissError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
        }

        if (attachments.isNotEmpty() || isUploading) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(attachments, key = PendingAttachment::id) { attachment ->
                    PendingAttachmentCard(
                        attachment = attachment,
                        onRemove = { onRemove(attachment.id) },
                    )
                }

                if (isUploading) {
                    item(key = "attachment-upload-progress") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Uploading attachment...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentCard(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val sizeText = remember(attachment.size, attachment.mime) {
        attachment.size
            ?.let { Formatter.formatShortFileSize(context, it.toLong()) }
            ?: attachment.mime.ifBlank { "Unknown size" }
    }
    val isAudio = remember(attachment.id, attachment.isImage, attachment.mime, attachment.name, attachment.path) {
        AttachmentAudioDetection.isAudio(
            isImage = attachment.isImage,
            mime = attachment.mime,
            name = attachment.name,
            path = attachment.path,
        )
    }

    Card(
        modifier = Modifier.widthIn(min = 190.dp, max = 268.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AttachmentPreview(attachment = attachment, isAudio = isAudio)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sizeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove attachment ${attachment.name}",
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    attachment: PendingAttachment,
    isAudio: Boolean,
) {
    val thumbnail = remember(attachment.id, attachment.thumbnailData) {
        attachment.thumbnailData?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            thumbnail != null -> Image(
                bitmap = thumbnail,
                contentDescription = "Image attachment ${attachment.name}",
                modifier = Modifier.size(58.dp),
                contentScale = ContentScale.Crop,
            )

            attachment.isImage -> AttachmentTypeIcon(
                icon = Icons.Filled.Image,
                contentDescription = "Image attachment ${attachment.name}",
            )

            isAudio -> AttachmentTypeIcon(
                icon = Icons.Filled.AudioFile,
                contentDescription = "Audio attachment ${attachment.name}",
            )

            else -> AttachmentTypeIcon(
                icon = Icons.Filled.Description,
                contentDescription = "File attachment ${attachment.name}",
            )
        }
    }
}

@Composable
private fun AttachmentTypeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    Surface(
        modifier = Modifier.size(58.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun AttachmentErrorRow(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss attachment error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
