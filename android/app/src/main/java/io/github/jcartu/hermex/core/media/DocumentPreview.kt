package io.github.jcartu.hermex.core.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** In-app/external routing equivalent to iOS `BinaryFilePreview` + Quick Look. */
enum class DocumentPreviewRoute { PDF, IMAGE, TEXT, EXTERNAL }

/** A staged in-app preview consumed by [DocumentPreviewHost]. */
data class DocumentPreviewRequest(
    val id: String,
    val file: File,
    val displayName: String,
    val mimeType: String,
    val route: DocumentPreviewRoute,
    val sizeBytes: Long,
)

/** Observable result returned to Files, chat links, and attachment surfaces. */
sealed interface DocumentPreviewOpenResult {
    data class InApp(val request: DocumentPreviewRequest) : DocumentPreviewOpenResult
    data class External(val uri: Uri) : DocumentPreviewOpenResult
    data class Rejected(val message: String) : DocumentPreviewOpenResult
}

/**
 * Android document-preview entry point. PDFs, images, and text/code are staged
 * into the app's private cache and shown by [DocumentPreviewHost]. Other file
 * types are handed to an installed viewer through an own-authority FileProvider.
 * No preview read may exceed [MAX_BYTES].
 */
object DocumentPreview {
    const val MAX_BYTES: Long = 25L * 1024L * 1024L
    const val TOO_LARGE_MESSAGE = "This file is too large to preview on this device (25 MB maximum)."

    private val mutableRequest = MutableStateFlow<DocumentPreviewRequest?>(null)
    val request: StateFlow<DocumentPreviewRequest?> = mutableRequest.asStateFlow()

    /** MIME/extension routing used before any bytes are staged. */
    fun route(filename: String, mimeType: String? = null): DocumentPreviewRoute {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || extension == "pdf" -> DocumentPreviewRoute.PDF
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> DocumentPreviewRoute.IMAGE
            mime.startsWith("text/") || mime in TEXT_MIME_TYPES || extension in TEXT_EXTENSIONS ->
                DocumentPreviewRoute.TEXT
            else -> DocumentPreviewRoute.EXTERNAL
        }
    }

    /** Pure cap check used by local metadata and network/content-resolver reads. */
    fun sizeRejection(sizeBytes: Long?): String? =
        if (sizeBytes != null && sizeBytes > MAX_BYTES) TOO_LARGE_MESSAGE else null

    /**
     * Opens already-loaded bytes. This is the shared call used by workspace
     * files and downloaded chat attachments.
     */
    fun open(
        context: Context,
        filename: String,
        mimeType: String? = null,
        bytes: ByteArray,
    ): DocumentPreviewOpenResult {
        sizeRejection(bytes.size.toLong())?.let { return DocumentPreviewOpenResult.Rejected(it) }
        val resolvedName = safeFilename(filename)
        val resolvedMime = resolvedMimeType(resolvedName, mimeType)
        val route = route(resolvedName, resolvedMime)
        val staged = runCatching { stage(context, resolvedName, bytes) }.getOrElse {
            return DocumentPreviewOpenResult.Rejected("This file could not be opened. It may be missing or unavailable.")
        }
        return if (route == DocumentPreviewRoute.EXTERNAL) {
            openExternal(context, staged, resolvedMime)
        } else {
            val request = DocumentPreviewRequest(
                id = UUID.randomUUID().toString(),
                file = staged,
                displayName = resolvedName,
                mimeType = resolvedMime,
                route = route,
                sizeBytes = bytes.size.toLong(),
            )
            mutableRequest.value = request
            DocumentPreviewOpenResult.InApp(request)
        }
    }

    /**
     * Reads and opens a document-picker/content URI without buffering past the
     * 25 MB cap. [declaredSize] prevents an oversized read when metadata exists.
     */
    suspend fun open(
        context: Context,
        uri: Uri,
        filename: String,
        mimeType: String? = context.contentResolver.getType(uri),
        declaredSize: Long? = null,
    ): DocumentPreviewOpenResult = withContext(Dispatchers.IO) {
        sizeRejection(declaredSize)?.let { return@withContext DocumentPreviewOpenResult.Rejected(it) }
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use(::readCapped)
                ?: return@withContext DocumentPreviewOpenResult.Rejected(
                    "This file could not be opened. It may be missing or unavailable.",
                )
        } catch (_: DocumentPreviewTooLargeException) {
            return@withContext DocumentPreviewOpenResult.Rejected(TOO_LARGE_MESSAGE)
        } catch (_: Exception) {
            return@withContext DocumentPreviewOpenResult.Rejected(
                "This file could not be opened. It may be missing or unavailable.",
            )
        }
        open(context, filename, mimeType, bytes)
    }

    /** Removes the active in-app surface without deleting its cache file mid-render. */
    fun dismiss(id: String? = mutableRequest.value?.id) {
        if (id != null && mutableRequest.value?.id == id) mutableRequest.value = null
    }

    /** Reads a stream up to exactly [MAX_BYTES], then refuses the first extra byte. */
    fun readCapped(input: InputStream, limitBytes: Long = MAX_BYTES): ByteArray {
        require(limitBytes >= 0) { "limitBytes must be non-negative" }
        val output = ByteArrayOutputStream(minOf(limitBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limitBytes) throw DocumentPreviewTooLargeException(limitBytes)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun stage(context: Context, filename: String, bytes: ByteArray): File {
        val directory = File(context.cacheDir, "document_previews/${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Could not create preview directory" }
        return File(directory, filename).also { it.writeBytes(bytes) }
    }

    private fun openExternal(context: Context, file: File, mimeType: String): DocumentPreviewOpenResult {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            DocumentPreviewOpenResult.External(uri)
        } catch (_: ActivityNotFoundException) {
            DocumentPreviewOpenResult.Rejected("No installed app can open this file type.")
        } catch (_: SecurityException) {
            DocumentPreviewOpenResult.Rejected("Android could not grant another app access to this file.")
        }
    }

    private fun safeFilename(filename: String): String {
        val candidate = filename.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[\\u0000-\\u001f]"), "_")
        return candidate.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "Hermes File"
    }

    private fun resolvedMimeType(filename: String, supplied: String?): String {
        supplied?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty)?.let { return it.lowercase() }
        val extension = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private val IMAGE_EXTENSIONS = setOf("avif", "bmp", "gif", "heic", "heif", "ico", "jpeg", "jpg", "png", "webp")
    private val TEXT_MIME_TYPES = setOf(
        "application/json", "application/ld+json", "application/javascript", "application/xml",
        "application/x-httpd-php", "application/x-sh", "application/yaml",
    )
    private val TEXT_EXTENSIONS = setOf(
        "bash", "c", "cc", "cfg", "conf", "cpp", "css", "csv", "cxx", "env", "go", "graphql",
        "h", "hpp", "htm", "html", "ini", "java", "js", "json", "jsonc", "jsx", "kt", "kts",
        "less", "log", "md", "mdown", "mkd", "markdown", "mjs", "mts", "plist", "properties",
        "py", "rb", "rs", "sass", "scss", "sh", "sql", "svg", "swift", "toml", "ts", "tsx",
        "txt", "xml", "yaml", "yml", "zig", "zsh",
    )
}

/** Internal sentinel thrown before a capped read allocates beyond its budget. */
class DocumentPreviewTooLargeException(val limitBytes: Long) : Exception(DocumentPreview.TOO_LARGE_MESSAGE)
