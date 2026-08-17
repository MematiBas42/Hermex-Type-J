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
) {
    companion object {
        const val maximumUploadBytes: Int = 20 * 1_024 * 1_024
        const val maximumUploadSizeDescription: String = "20 MB"

        fun uploadTooLargeMessage(filename: String): String =
            "$filename is too large. Attachments must be $maximumUploadSizeDescription or smaller."

        /**
         * Composer draft text with the attachments appended as a reference list, e.g.
         * `"…draft\n\n[Attached files: a.png, notes.txt]"`. Returns the draft unchanged
         * when no attachment carries a non-empty reference.
         */
        fun chatMessageText(draft: String, attachments: List<PendingAttachment>): String {
            val references = attachments
                .map { it.chatReference }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (references.isEmpty()) {
                return draft
            }

            return "$draft\n\n[Attached files: ${references.joinToString(", ")}]"
        }
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
