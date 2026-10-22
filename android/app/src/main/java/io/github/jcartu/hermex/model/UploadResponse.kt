package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

// Port of `UploadResponse.swift`.
//
// `UploadResponse` itself is a synthesized Swift `Codable` decoded through the shared
// `.convertFromSnakeCase` decoder, so the wire key for `isImage` is `is_image`.
//
// `ImagePreviewDownsampler` (CGImageSource/CGImageDestination based) has no pure-JVM
// equivalent in the allowed dependency set; Android downsamples attachment previews via
// Coil at render time. Only its pixel-size caps are ported here so UI code sizes Coil
// requests exactly like the iOS app does.

/** Response body of the multipart upload endpoint (`APIClient+Upload.swift`). */
@Serializable
data class UploadResponse(
    val filename: String? = null,
    val path: String? = null,
    val size: Int? = null,
    val mime: String? = null,
    @SerialName("is_image") val isImage: Boolean? = null,
    val error: String? = null,
)

/**
 * An attachment staged in the composer, waiting to be uploaded with the next send.
 * (Swift: `Identifiable` via a fresh per-instance `UUID`.)
 */
data class PendingAttachment(
    /** Fresh identity per instance — mirrors Swift `let id = UUID()`. */
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val mime: String,
    val size: Int? = null,
    val isImage: Boolean,
    val thumbnailData: ByteArray? = null,
    /**
     * Name of the durable app-owned copy in `ChatDraftAttachmentStore`, so the
     * attachment can be re-uploaded after the process or the server's
     * per-session upload inbox is gone. Voice notes leave it null.
     */
    val draftFileName: String? = null,
) {
    companion object {
        const val maximumUploadBytes: Int = 20 * 1_024 * 1_024
        const val maximumUploadSizeDescription: String = "20 MB"

        fun uploadTooLargeMessage(filename: String): String =
            "$filename is too large. Attachments must be $maximumUploadSizeDescription or smaller."

        /**
         * Composer draft text with the attachments appended as a reference list, e.g.
         * `"…draft\n\n[Attached files: a.png, notes.txt]"`. Returns the draft unchanged
         * when no attachment carries a non-empty reference. An attachment-only send
         * (blank draft) synthesizes the message the web UI sends (`static/messages.js`),
         * since the server requires non-empty text.
         */
        fun chatMessageText(draft: String, attachments: List<PendingAttachment>): String {
            val references = agentReferences(attachments)
            if (references.isEmpty()) {
                return draft
            }

            if (draft.trim().isEmpty()) {
                return "I've uploaded ${references.size} file(s): ${references.joinToString(", ")}"
            }

            return "$draft\n\n[Attached files: ${references.joinToString(", ")}]"
        }

        private val attachmentOnlyMessagePattern = Regex("""I've uploaded \d+ file\(s\): .+""", RegexOption.DOT_MATCHES_ALL)

        /** True for the text [chatMessageText] makes up for an attachment-only send. */
        fun isAttachmentOnlyMessageText(text: String): Boolean = attachmentOnlyMessagePattern.matches(text)

        /**
         * The steer text with the web UI's attached-files note appended verbatim
         * (`static/commands.js`, `_steerTextWithPendingFiles`), so a steer reads
         * the same from either client.
         */
        fun steerMessageText(draft: String, attachments: List<PendingAttachment>): String {
            val references = agentReferences(attachments)
            if (references.isEmpty()) {
                return draft
            }

            val note = "[Attached files for this steer: ${references.joinToString(", ")}]\n" +
                "Use the file tools/read_file to inspect these documents if needed."
            return "$draft\n\n$note"
        }

        /** Where the agent reads each attachment: its uploaded path, or its name when the upload returned no path. */
        private fun agentReferences(attachments: List<PendingAttachment>): List<String> =
            attachments
                .map { it.chatReference }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }

    /**
     * The string embedded into the chat message for this attachment — the path when
     * known, else the display name. (Upstream branches on `isImage`, but both Swift
     * branches return the same expression.)
     */
    val chatReference: String
        get() = if (path.isEmpty()) name else path

    /** The `attachments[]` payload element sent with the message (keys as the server expects). */
    fun toJSONValue(): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["name"] = JSONValue.string(name)
        fields["path"] = JSONValue.string(path)
        fields["mime"] = JSONValue.string(mime)
        size?.let { fields["size"] = JSONValue.number(it.toDouble()) }
        fields["is_image"] = JSONValue.bool(isImage)
        return JSONValue.objectOf(fields)
    }
}

/**
 * Pixel-size caps for attachment previews (Swift `enum ImagePreviewDownsampler`).
 *
 * The iOS implementation downsamples with ImageIO at these caps; on Android the Coil
 * image pipeline applies the same caps at load time, so only the constants are ported.
 */
object ImagePreviewDownsampler {
    const val attachmentMaxPixelSize: Int = 512
    const val filePreviewMaxPixelSize: Int = 2_048
}
