package io.github.jcartu.hermex.ui.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.PendingAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android counterpart of `CustomAttachmentPicker.swift` + `CameraPickerView.swift`.
 * Photos use the system privacy-preserving [ActivityResultContracts.PickVisualMedia]
 * picker, documents use the Storage Access Framework, and camera capture keeps the
 * fork's existing no-storage-permission preview contract.
 */
@Composable
internal fun CustomAttachmentPicker(
    visible: Boolean,
    onDismiss: () -> Unit,
    onUrisPicked: (List<Uri>) -> Unit,
    onCameraCaptured: (Bitmap) -> Unit,
    onError: (String) -> Unit,
) {
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onUrisPicked(listOf(uri))
    }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onUrisPicked(uris)
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) onCameraCaptured(bitmap)
    }

    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Attachment") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AttachmentSourceRow(
                    icon = Icons.Filled.PhotoLibrary,
                    title = "Photo Library",
                    description = "Choose a photo without granting library access",
                    onClick = {
                        onDismiss()
                        runCatching {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }.onFailure { onError("No photo picker is available.") }
                    },
                )
                AttachmentSourceRow(
                    icon = Icons.Filled.CameraAlt,
                    title = "Camera",
                    description = "Take a new photo",
                    onClick = {
                        onDismiss()
                        runCatching { cameraPicker.launch(null) }
                            .onFailure { onError("Camera is not available.") }
                    },
                )
                AttachmentSourceRow(
                    icon = Icons.Filled.Description,
                    title = "Files",
                    description = "Choose one or more documents",
                    onClick = {
                        onDismiss()
                        runCatching { documentPicker.launch(arrayOf("*/*")) }
                            .onFailure { onError("No file picker is available.") }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AttachmentSourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 16.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal sealed interface PreparedAttachmentResult {
    data class Success(
        val filename: String,
        val bytes: ByteArray,
        val previewData: ByteArray?,
    ) : PreparedAttachmentResult

    data class TooLarge(val filename: String) : PreparedAttachmentResult
    data class Failure(val message: String) : PreparedAttachmentResult
}

private data class PickedAttachmentMetadata(
    val filename: String,
    val size: Long?,
    val mime: String,
)

private class AttachmentTooLargeException : IOException()

/** Reads one selected URI and renders/caches its staged-document thumbnail off the UI thread. */
internal suspend fun preparePickedAttachment(
    context: Context,
    uri: Uri,
    serverCacheNamespace: String,
): PreparedAttachmentResult = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val metadata = pickedAttachmentMetadata(context, uri)
    if (metadata.size != null && metadata.size > PendingAttachment.maximumUploadBytes) {
        return@withContext PreparedAttachmentResult.TooLarge(metadata.filename)
    }

    val bytes = try {
        resolver.openInputStream(uri)?.use { stream ->
            stream.readBounded(PendingAttachment.maximumUploadBytes)
        }
    } catch (_: AttachmentTooLargeException) {
        return@withContext PreparedAttachmentResult.TooLarge(metadata.filename)
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    }

    if (bytes == null) {
        PreparedAttachmentResult.Failure("Couldn't read ${metadata.filename}.")
    } else {
        val contentHash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        val cacheKey = "${metadata.filename}|${metadata.mime}|$contentHash"
        val preview = ComposerThumbnailCache.get(serverCacheNamespace, cacheKey)
            ?: try {
                renderAttachmentPreview(context, uri, metadata, bytes)
            } catch (_: Exception) {
                null
            }?.also { rendered ->
                ComposerThumbnailCache.put(serverCacheNamespace, cacheKey, rendered)
            }
        PreparedAttachmentResult.Success(
            filename = metadata.filename,
            bytes = bytes,
            previewData = preview,
        )
    }
}

internal suspend fun prepareCameraAttachment(bitmap: Bitmap): PreparedAttachmentResult = withContext(Dispatchers.IO) {
    val bytes = ByteArrayOutputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) null else output.toByteArray()
    } ?: return@withContext PreparedAttachmentResult.Failure("Couldn't prepare the camera image.")

    if (bytes.size > PendingAttachment.maximumUploadBytes) {
        PreparedAttachmentResult.TooLarge("Camera image")
    } else {
        val preview = downsampleImage(bytes)
        PreparedAttachmentResult.Success(
            filename = "camera-${System.currentTimeMillis()}.jpg",
            bytes = bytes,
            previewData = preview,
        )
    }
}

private fun pickedAttachmentMetadata(context: Context, uri: Uri): PickedAttachmentMetadata {
    var filename: String? = null
    var size: Long? = null
    try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) filename = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    val value = cursor.getLong(sizeIndex)
                    if (value >= 0L) size = value
                }
            }
        }
    } catch (_: RuntimeException) {
        // Providers may reject metadata while still allowing the stream read.
    }
    return PickedAttachmentMetadata(
        filename = filename?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "attachment",
        size = size,
        mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty(),
    )
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE * 4))
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) throw AttachmentTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun renderAttachmentPreview(
    context: Context,
    uri: Uri,
    metadata: PickedAttachmentMetadata,
    bytes: ByteArray,
): ByteArray? = when {
    metadata.mime.startsWith("image/") -> downsampleImage(bytes)
    metadata.mime.equals("application/pdf", ignoreCase = true) ||
        metadata.filename.endsWith(".pdf", ignoreCase = true) -> renderPdfFirstPage(context, uri)
    metadata.isTextDocument() -> renderTextExcerpt(bytes)
    else -> null
}

/** `PdfRenderer` first-page thumbnail, the Android equivalent of Quick Look's PDF thumbnail. */
private fun renderPdfFirstPage(context: Context, uri: Uri): ByteArray? {
    val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
    val renderer = runCatching { PdfRenderer(descriptor) }.getOrElse {
        runCatching { descriptor.close() }
        return null
    }
    return renderer.use { pdf ->
        if (pdf.pageCount <= 0) return@use null
        pdf.openPage(0).use { page ->
            val targetWidth = 360
            val targetHeight = max(1, (targetWidth * page.height.toFloat() / max(1, page.width)).roundToInt())
                .coerceAtMost(480)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            val scale = minOf(
                targetWidth / page.width.toFloat(),
                targetHeight / page.height.toFloat(),
            )
            val transform = Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    (targetWidth - page.width * scale) / 2f,
                    (targetHeight - page.height * scale) / 2f,
                )
            }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.toPngBytes()
        }
    }
}

/** Renders a bounded UTF-8 text excerpt so source documents do not collapse to a generic icon. */
private fun renderTextExcerpt(bytes: ByteArray): ByteArray? {
    val excerpt = bytes.decodeToString(endIndex = minOf(bytes.size, 24 * 1_024))
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    if (excerpt.isEmpty()) return null

    val width = 480
    val height = 320
    val padding = 24f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 43, 54)
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }
    val lineHeight = paint.fontSpacing
    val maxLines = ((height - padding * 2) / lineHeight).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(245, 247, 250))

    val renderedLines = ArrayList<String>(maxLines)
    for (sourceLine in excerpt.lineSequence()) {
        if (renderedLines.size >= maxLines) break
        var remaining = sourceLine.ifEmpty { " " }
        while (remaining.isNotEmpty() && renderedLines.size < maxLines) {
            var count = paint.breakText(remaining, true, width - padding * 2, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val breakAt = remaining.substring(0, count).lastIndexOf(' ')
                if (breakAt > 0) count = breakAt
            }
            renderedLines += remaining.substring(0, count)
            remaining = remaining.substring(count).trimStart()
        }
    }
    renderedLines.forEachIndexed { index, line ->
        canvas.drawText(line, padding, padding - paint.fontMetrics.top + index * lineHeight, paint)
    }
    return bitmap.toPngBytes()
}

private fun downsampleImage(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    return bitmap.toPngBytes()
}

private fun Bitmap.toPngBytes(): ByteArray? = ByteArrayOutputStream().use { output ->
    if (!compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
}

private fun PickedAttachmentMetadata.isTextDocument(): Boolean {
    if (mime.startsWith("text/")) return true
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "swift", "js", "ts", "tsx", "jsx",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "zsh", "fish", "json",
        "jsonl", "yaml", "yml", "toml", "xml", "html", "css", "scss", "sql", "csv", "log",
    )
}

/** Small process cache partitioned by server identity; values are rendered thumbnails only. */
private object ComposerThumbnailCache {
    private const val MAX_BYTES = 16 * 1_024 * 1_024
    private val entries = LinkedHashMap<String, ByteArray>(32, 0.75f, true)
    private var sizeBytes = 0

    @Synchronized
    fun get(serverNamespace: String, key: String): ByteArray? = entries[scoped(serverNamespace, key)]

    @Synchronized
    fun put(serverNamespace: String, key: String, bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) return
        val scopedKey = scoped(serverNamespace, key)
        entries.put(scopedKey, bytes)?.let { previous -> sizeBytes -= previous.size }
        sizeBytes += bytes.size
        val iterator = entries.entries.iterator()
        while (sizeBytes > MAX_BYTES && iterator.hasNext()) {
            sizeBytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    private fun scoped(serverNamespace: String, key: String): String =
        "${serverNamespace.ifBlank { "default" }}\u0000$key"
}
