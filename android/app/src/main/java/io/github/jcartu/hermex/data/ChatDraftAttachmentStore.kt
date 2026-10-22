package io.github.jcartu.hermex.data

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Port of `Persistence/ChatDraftAttachmentStore.swift`: durable, app-owned
 * copies of staged composer attachments. A draft record references one of
 * these files by name so the attachment can be re-uploaded after the app's
 * process or the server-side per-session upload inbox is gone (the server
 * scopes uploads per session and deletes them with the session).
 *
 * All methods are blocking file I/O; callers run them on `Dispatchers.IO`.
 */
interface ChatDraftAttachmentStoring {
    /** Writes a copy of the attachment bytes, returning the generated file name the draft record stores. */
    fun save(data: ByteArray, suggestedFilename: String): String

    /** Size of every retained copy by file name. */
    fun retainedFileBytes(): Map<String, Long>

    fun data(fileName: String): ByteArray

    /** Where the copy lives, for readers that need a file rather than its bytes; the file itself may be gone. */
    fun file(fileName: String): File

    fun delete(fileName: String)

    /**
     * Deletes files not referenced by any draft and older than [maxAgeMillis].
     * The age grace keeps copies whose owning upload/record write is still in
     * flight from being collected.
     */
    fun sweep(keepingReferenced: Set<String>, maxAgeMillis: Long, now: Long = System.currentTimeMillis())
}

/**
 * Port of `ChatDraftAttachmentReadFailure`: why a draft attachment's durable
 * copy could not be read, and therefore whether its record is worth keeping.
 * A copy that is merely unreadable right now must not be reported as
 * permanently lost and dropped from the draft.
 */
enum class ChatDraftAttachmentReadFailure {
    /** The copy is gone, or its recorded name can never resolve: the record is dropped. */
    UNRECOVERABLE,

    /** The copy may still be there; this read just did not succeed: the record is kept. */
    TRANSIENT;

    companion object {
        fun classify(error: Throwable): ChatDraftAttachmentReadFailure = when (error) {
            is FileNotFoundException, is NoSuchFileException, is InvalidDraftFileNameException -> UNRECOVERABLE
            else -> TRANSIENT
        }
    }
}

/** A stored file name that is not a plain name (`fileURL(for:)`'s `fileReadInvalidFileName`). */
class InvalidDraftFileNameException(fileName: String) : IOException("Invalid draft attachment file name: $fileName")

class ChatDraftAttachmentStore(baseDirectory: File) : ChatDraftAttachmentStoring {
    private val directory = File(File(baseDirectory, "ChatDrafts"), "Attachments")

    override fun save(data: ByteArray, suggestedFilename: String): String {
        directory.mkdirs()
        val fileName = "${UUID.randomUUID().toString().lowercase()}-${normalizedFilename(suggestedFilename)}"
        val target = File(directory, fileName)
        val temp = File(directory, "$fileName.tmp")
        temp.writeBytes(data)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return fileName
    }

    override fun retainedFileBytes(): Map<String, Long> {
        if (!directory.isDirectory) return emptyMap()
        val files = directory.listFiles() ?: throw IOException("Could not list draft attachments")
        val bytes = LinkedHashMap<String, Long>()
        for (file in files) {
            // Regular files only: never follow a symlink or count a directory.
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) continue
            bytes[file.name] = file.length()
        }
        return bytes
    }

    override fun data(fileName: String): ByteArray = file(fileName).readBytes()

    override fun file(fileName: String): File {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." || trimmed != normalizedFilename(trimmed)) {
            throw InvalidDraftFileNameException(fileName)
        }
        return File(directory, trimmed)
    }

    override fun delete(fileName: String) {
        val target = runCatching { file(fileName) }.getOrNull() ?: return
        // Already gone is fine — deletion is idempotent; other failures are logged by callers' retries.
        runCatching { Files.deleteIfExists(target.toPath()) }
    }

    override fun sweep(keepingReferenced: Set<String>, maxAgeMillis: Long, now: Long) {
        val files = directory.listFiles() ?: return
        val cutoff = now - maxAgeMillis
        for (file in files) {
            if (file.name in keepingReferenced) continue
            val created = runCatching {
                Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                    .creationTime().toMillis()
            }.getOrDefault(0L)
            if (created >= cutoff) continue
            runCatching { Files.deleteIfExists(file.toPath()) }
        }
    }

    companion object {
        /**
         * Resolves a suggested name to a plain file name: the last path
         * component, trimmed, `attachment` when empty.
         */
        fun normalizedFilename(filename: String): String {
            val lastComponent = filename.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')
            val trimmed = lastComponent.trim()
            return trimmed.ifEmpty { "attachment" }
        }
    }
}
