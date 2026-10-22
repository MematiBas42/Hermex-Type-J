package io.github.jcartu.hermex.ui.feature.chat.hermes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import io.github.jcartu.hermex.model.hermes.BotAttachmentFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Port of `BotAttachmentUpload.prepare` from
 * `Networking/Bots/BotAttachmentUpload.swift`. Images are orientation-corrected,
 * bounded to 4096 pixels on their longest edge, and encoded as PNG when they
 * carry alpha or JPEG otherwise. Supported non-images retain their exact bytes.
 */
object HermesAttachmentPreparer {
    const val MAXIMUM_FILE_BYTES: Long = 25L * 1024L * 1024L
    const val MAXIMUM_TOTAL_BYTES: Long = 50L * 1024L * 1024L
    const val MAXIMUM_ATTACHMENTS: Int = 8
    const val MAXIMUM_IMAGE_DIMENSION: Int = 4096
    private const val THUMBNAIL_IMAGE_DIMENSION = 512
    private const val TEXT_THUMBNAIL_WIDTH = 480
    private const val TEXT_THUMBNAIL_HEIGHT = 320
    private const val PDF_THUMBNAIL_WIDTH = 360
    private const val PDF_THUMBNAIL_MAX_HEIGHT = 480
    private const val TEXT_EXCERPT_BYTES = 24 * 1024

    /**
     * Port of the tuple returned by `BotAttachmentUpload.prepare` in
     * `Networking/Bots/BotAttachmentUpload.swift`.
     */
    data class Prepared(
        val data: ByteArray,
        val name: String,
        val mime: String,
        val image: Boolean,
    )

    /**
     * Produces the bytes and metadata Hermes receives. Bitmap work is kept off
     * the main dispatcher, matching the Swift caller's detached preparation.
     */
    @Throws(BotAttachmentFailure::class)
    suspend fun prepare(data: ByteArray, filename: String): Prepared = withContext(Dispatchers.Default) {
        admit(existingCount = 0, existingBytes = 0, nextBytes = data.size.toLong())
        val name = filename.trimEnd('/').substringAfterLast('/')
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension.isEmpty()) throw BotAttachmentFailure.Type
        val mime = preferredMime(extension)

        if (isImage(extension, mime)) {
            prepareImage(data = data, name = name)
        } else {
            if (!isAllowedDocument(extension, mime)) throw BotAttachmentFailure.Type
            Prepared(
                data = data,
                name = name,
                mime = mime ?: "application/octet-stream",
                image = false,
            )
        }
    }

    /**
     * Regenerates the bounded image, PDF, or text preview used by the Android
     * composer. This is a renderer only: restored bytes are never passed back
     * through `BotAttachmentUpload.prepare`.
     */
    suspend fun thumbnail(data: ByteArray, filename: String): ByteArray? {
        if (data.isEmpty() || data.size.toLong() > MAXIMUM_FILE_BYTES) return null
        val name = filename.trimEnd('/').substringAfterLast('/')
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension.isEmpty()) return null
        val mime = preferredMime(extension)
        return when {
            isImage(extension, mime) -> withContext(Dispatchers.Default) {
                nullableRender { renderImageThumbnail(data) }
            }
            extension == "pdf" || mime == "application/pdf" -> withContext(Dispatchers.IO) {
                nullableRender { renderPdfThumbnail(data) }
            }
            extension in TEXT_EXTENSIONS || mime?.startsWith("text/") == true -> {
                withContext(Dispatchers.Default) {
                    nullableRender { renderTextThumbnail(data) }
                }
            }
            else -> null
        }
    }

    /**
     * Applies the attachment-count, per-file, and aggregate-byte limits from
     * `BotAttachmentUpload.swift` and `ChatAttachmentCoordinator.swift`.
     */
    @Throws(BotAttachmentFailure::class)
    fun admit(existingCount: Int, existingBytes: Long, nextBytes: Long) {
        val invalid = existingCount < 0 ||
            existingCount >= MAXIMUM_ATTACHMENTS ||
            existingBytes < 0L ||
            nextBytes <= 0L ||
            nextBytes > MAXIMUM_FILE_BYTES ||
            existingBytes > MAXIMUM_TOTAL_BYTES - nextBytes
        if (invalid) throw BotAttachmentFailure.Limit
    }

    private fun prepareImage(data: ByteArray, name: String): Prepared {
        val bitmap = decodeImage(data, MAXIMUM_IMAGE_DIMENSION) ?: throw BotAttachmentFailure.Unreadable
        try {
            val hasAlpha = bitmap.hasAlpha()
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(format, if (hasAlpha) 100 else 90, output)) {
                throw BotAttachmentFailure.Unreadable
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) throw BotAttachmentFailure.Unreadable
            if (bytes.size.toLong() > MAXIMUM_FILE_BYTES) throw BotAttachmentFailure.Limit
            val base = name.substringBeforeLast('.', missingDelimiterValue = name)
            return Prepared(
                data = bytes,
                name = base + if (hasAlpha) ".png" else ".jpg",
                mime = if (hasAlpha) "image/png" else "image/jpeg",
                image = true,
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** ImageDecoder applies encoded EXIF orientation on API 28 and newer. */
    private fun decodeImage(data: ByteArray, maximumDimension: Int): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            try {
                decodeWithImageDecoder(data, maximumDimension)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        } else {
            null
        }
        return decoded ?: decodeWithBitmapFactory(data, maximumDimension)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(data: ByteArray, maximumDimension: Int): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maximumDimension) {
                decoder.setTargetSize(
                    boundedDimension(info.size.width, longest, maximumDimension),
                    boundedDimension(info.size.height, longest, maximumDimension),
                )
            }
        }
        return scaleToBound(decoded, maximumDimension)
    }

    /** BitmapFactory fallback for API 26–27 and codecs ImageDecoder declines. */
    private fun decodeWithBitmapFactory(data: ByteArray, maximumDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maximumDimension) {
            sampleSize *= 2
        }
        var bitmap = BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        val matrix = exifMatrix(data)
        if (matrix != null && !matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap) {
                bitmap.recycle()
                bitmap = transformed
            }
        }
        return scaleToBound(bitmap, maximumDimension)
    }

    private fun scaleToBound(bitmap: Bitmap, maximumDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maximumDimension) return bitmap
        val scaled = bitmap.scale(
            width = boundedDimension(bitmap.width, longest, maximumDimension),
            height = boundedDimension(bitmap.height, longest, maximumDimension),
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun boundedDimension(dimension: Int, longest: Int, maximumDimension: Int): Int {
        require(dimension > 0 && longest > 0 && maximumDimension > 0) { "Invalid image dimensions" }
        val rounded = dimension.toLong() * maximumDimension + longest / 2L
        return (rounded / longest).toInt().coerceAtLeast(1)
    }

    private inline fun nullableRender(render: () -> ByteArray?): ByteArray? = try {
        render()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private fun renderImageThumbnail(data: ByteArray): ByteArray? {
        val bitmap = decodeImage(data, THUMBNAIL_IMAGE_DIMENSION) ?: return null
        return try {
            bitmap.pngBytes()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun renderPdfThumbnail(data: ByteArray): ByteArray? {
        val temporary = File.createTempFile("hermes-thumbnail-", ".pdf")
        return try {
            FileOutputStream(temporary).use { output -> output.write(data) }
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) {
                        null
                    } else {
                        renderer.openPage(0).use { page ->
                            val targetHeight = max(
                                1,
                                (PDF_THUMBNAIL_WIDTH * page.height.toFloat() / max(1, page.width)).roundToInt(),
                            ).coerceAtMost(PDF_THUMBNAIL_MAX_HEIGHT)
                            val bitmap = createBitmap(PDF_THUMBNAIL_WIDTH, targetHeight)
                            try {
                                Canvas(bitmap).drawColor(Color.WHITE)
                                val scale = minOf(
                                    PDF_THUMBNAIL_WIDTH / page.width.toFloat(),
                                    targetHeight / page.height.toFloat(),
                                )
                                val transform = Matrix().apply {
                                    setScale(scale, scale)
                                    postTranslate(
                                        (PDF_THUMBNAIL_WIDTH - page.width * scale) / 2f,
                                        (targetHeight - page.height * scale) / 2f,
                                    )
                                }
                                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmap.pngBytes()
                            } finally {
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            if (!temporary.delete()) {
                try {
                    FileOutputStream(temporary).use { }
                    temporary.delete()
                } catch (_: Exception) {
                    // Best effort after every renderer descriptor was closed.
                }
            }
        }
    }

    private fun renderTextThumbnail(data: ByteArray): ByteArray? {
        val excerpt = data.decodeToString(endIndex = minOf(data.size, TEXT_EXCERPT_BYTES))
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (excerpt.isEmpty()) return null

        val padding = 24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 43, 54)
            textSize = 20f
            typeface = Typeface.MONOSPACE
        }
        val lineHeight = paint.fontSpacing
        val maxLines = ((TEXT_THUMBNAIL_HEIGHT - padding * 2) / lineHeight).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(TEXT_THUMBNAIL_WIDTH, TEXT_THUMBNAIL_HEIGHT)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(245, 247, 250))
            val renderedLines = ArrayList<String>(maxLines)
            for (sourceLine in excerpt.lineSequence()) {
                if (renderedLines.size >= maxLines) break
                var remaining = sourceLine.ifEmpty { " " }
                while (remaining.isNotEmpty() && renderedLines.size < maxLines) {
                    var count = paint.breakText(
                        remaining,
                        true,
                        TEXT_THUMBNAIL_WIDTH - padding * 2,
                        null,
                    ).coerceAtLeast(1)
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
            return bitmap.pngBytes()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun Bitmap.pngBytes(): ByteArray? = ByteArrayOutputStream().use { output ->
        if (compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
    }

    /** AndroidX ExifInterface avoids the security bugs in older platform parsers. */
    private fun exifMatrix(data: ByteArray): Matrix? {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
            else -> null
        }
    }

    private fun isImage(extension: String, mime: String?): Boolean =
        mime?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS

    private fun isAllowedDocument(extension: String, mime: String?): Boolean =
        mime?.startsWith("text/") == true ||
            mime?.startsWith("audio/") == true ||
            mime == "application/pdf" ||
            extension in TEXT_EXTENSIONS ||
            extension in EXTRA_DOCUMENT_EXTENSIONS

    private fun preferredMime(extension: String): String? =
        PREFERRED_MIME_TYPES[extension]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (extension in TEXT_EXTENSIONS) "text/plain" else null

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "wbmp", "tif", "tiff", "avif",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "rtf", "kt", "kts", "java", "swift", "js", "ts", "tsx", "jsx",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "zsh", "fish", "json",
        "jsonl", "yaml", "yml", "toml", "xml", "html", "css", "scss", "sql", "csv", "log",
    )

    private val EXTRA_DOCUMENT_EXTENSIONS = setOf(
        "json", "yaml", "yml", "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip",
    )

    private val PREFERRED_MIME_TYPES = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "csv" to "text/csv",
        "xml" to "text/xml",
        "json" to "application/json",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "pdf" to "application/pdf",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "flac" to "audio/flac",
        "amr" to "audio/amr",
        "3gp" to "audio/3gpp",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip",
    )
}
