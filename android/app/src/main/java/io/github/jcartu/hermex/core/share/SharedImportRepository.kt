package io.github.jcartu.hermex.core.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
private data class SharedImportPayload(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
    val attachments: List<SharedAttachmentPayload>,
    val warnings: List<String> = emptyList(),
)

@Serializable
private data class SharedAttachmentPayload(
    val filename: String,
    val storedFilename: String,
    val mimeType: String?,
    val size: Long,
)

data class SharedImportAttachment(
    val filename: String,
    val mimeType: String?,
    val size: Long,
    val file: File,
)

data class SharedImport(
    val id: String,
    val text: String,
    val attachments: List<SharedImportAttachment>,
    val warnings: List<String>,
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/**
 * Durable ACTION_SEND / ACTION_SEND_MULTIPLE inbox. Content URIs are copied
 * immediately while the sender's temporary grants are valid. Metadata and
 * app-private files survive onboarding and process death until Chat confirms
 * every attachment staged successfully.
 */
class SharedImportRepository(
    context: Context,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val root = File(context.filesDir, "shared-import")
    private val metadata = File(root, "pending.json")
    private val attachmentsDirectory = File(root, "attachments")
    private val _pending = MutableStateFlow<SharedImport?>(null)
    val pending: StateFlow<SharedImport?> = _pending.asStateFlow()

    suspend fun refresh() = withContext(ioDispatcher) {
        _pending.value = loadFromDisk()
    }

    suspend fun ingest(intent: Intent): SharedImport? = withContext(ioDispatcher) {
        if (intent.action !in SUPPORTED_ACTIONS) return@withContext null
        val text = sharedText(intent)
        val uris = sharedUris(intent).distinctBy(Uri::toString).take(MAX_ATTACHMENT_COUNT)
        val warnings = mutableListOf<String>()
        val id = UUID.randomUUID().toString()

        root.mkdirs()
        attachmentsDirectory.deleteRecursively()
        attachmentsDirectory.mkdirs()

        val payloads = uris.mapNotNull { uri ->
            val source = metadata(uri)
            if (source.size != null && source.size > MAX_ATTACHMENT_BYTES) {
                warnings += "${source.filename} is larger than 20 MB."
                return@mapNotNull null
            }
            copyUri(uri, source, warnings)
        }
        val payload = SharedImportPayload(id, text, System.currentTimeMillis(), payloads, warnings)
        if (payload.text.isBlank() && payload.attachments.isEmpty()) {
            clearDisk()
            _pending.value = null
            return@withContext null
        }
        val temp = File(root, "pending.json.tmp")
        temp.writeText(json.encodeToString(SharedImportPayload.serializer(), payload))
        if (!temp.renameTo(metadata)) {
            temp.copyTo(metadata, overwrite = true); temp.delete()
        }
        loadFromDisk().also { _pending.value = it }
    }

    suspend fun acknowledge(id: String) = withContext(ioDispatcher) {
        if (_pending.value?.id != id) return@withContext
        clearDisk()
        _pending.value = null
    }

    private fun loadFromDisk(): SharedImport? {
        if (!metadata.isFile) return null
        val payload = runCatching {
            json.decodeFromString(SharedImportPayload.serializer(), metadata.readText())
        }.getOrElse {
            clearDisk(); return null
        }
        val files = payload.attachments.mapNotNull { item ->
            val file = File(attachmentsDirectory, item.storedFilename)
            file.takeIf { it.isFile && it.length() in 1..MAX_ATTACHMENT_BYTES }?.let {
                SharedImportAttachment(item.filename, item.mimeType, item.size, it)
            }
        }
        return SharedImport(payload.id, payload.text.trim(), files, payload.warnings).takeUnless(SharedImport::isEmpty)
    }

    private fun copyUri(
        uri: Uri,
        source: SourceMetadata,
        warnings: MutableList<String>,
    ): SharedAttachmentPayload? {
        val ext = source.filename.substringAfterLast('.', "").takeIf { it.length in 1..12 }
        val stored = UUID.randomUUID().toString() + (ext?.let { ".$it" } ?: "")
        val destination = File(attachmentsDirectory, stored)
        return try {
            val input = resolver.openInputStream(uri) ?: error("The shared file could not be opened.")
            var total = 0L
            input.use { stream ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ATTACHMENT_BYTES) error("The shared file is larger than 20 MB.")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (total == 0L) error("The shared file is empty.")
            SharedAttachmentPayload(source.filename, stored, source.mimeType, total)
        } catch (error: Exception) {
            destination.delete()
            warnings += "${source.filename}: ${error.message ?: "could not be imported"}"
            null
        }
    }

    private fun sharedText(intent: Intent): String {
        val values = linkedSetOf<String>()
        listOf(Intent.EXTRA_TEXT, Intent.EXTRA_PROCESS_TEXT, Intent.EXTRA_SUBJECT).forEach { key ->
            intent.getCharSequenceExtra(key)?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
        }
        return values.joinToString("\n\n")
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val values = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(values::add) }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            values += parcelableUriList(intent, Intent.EXTRA_STREAM)
        } else {
            parcelableUri(intent, Intent.EXTRA_STREAM)?.let(values::add)
        }
        return values
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java)
        else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun parcelableUriList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        else intent.getParcelableArrayListExtra<Uri>(key).orEmpty()

    private fun metadata(uri: Uri): SourceMetadata {
        var name: String? = null
        var size: Long? = null
        val cursor: Cursor? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return SourceMetadata(
            filename = sanitizeFilename(name ?: fallback),
            mimeType = resolver.getType(uri),
            size = size,
        )
    }

    private fun sanitizeFilename(raw: String): String {
        val name = raw.replace('\\', '/').substringAfterLast('/').trim()
            .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
            .take(180)
        return name.ifEmpty { "shared-file" }
    }

    private fun clearDisk() {
        metadata.delete()
        File(root, "pending.json.tmp").delete()
        attachmentsDirectory.deleteRecursively()
    }

    private data class SourceMetadata(val filename: String, val mimeType: String?, val size: Long?)

    companion object {
        const val MAX_ATTACHMENT_COUNT = 10
        const val MAX_ATTACHMENT_BYTES = 20L * 1_024L * 1_024L
        private val SUPPORTED_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)
    }
}
