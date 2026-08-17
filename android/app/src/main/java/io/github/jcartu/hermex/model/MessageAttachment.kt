package io.github.jcartu.hermex.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Port of `MessageAttachment.swift`. Tolerant decoding: upstream may store bare
 * filenames (legacy) or objects with unexpected field names / types. Never
 * crashes the parent [ChatMessage] decode because of one malformed attachment.
 */
@Serializable(with = MessageAttachmentSerializer::class)
data class MessageAttachment(
    val name: String? = null,
    val path: String? = null,
    val mime: String? = null,
    val size: Int? = null,
    val isImage: Boolean? = null,
) {
    /**
     * Stable identity for matching the *same* attachment across two
     * representations — the lowercased basename of the first non-empty
     * `name`/`path`. See the Swift doc comment for why raw values are not
     * compared (server returns `path` inconsistently on reload; #330).
     */
    val identityKey: String?
        get() {
            val raw = listOf(name, path)
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return null
            val lastComponent = lastPathComponent(raw)
            val value = (lastComponent.ifEmpty { raw }).lowercase()
            return value.ifEmpty { null }
        }

    companion object {
        fun inferredFromAttachedFilesMarker(content: String?): List<MessageAttachment>? {
            if (content == null) return null
            val marker = attachedFilesMarker(content) ?: return null

            val inferredDirectory = marker.references
                .firstOrNull { it.contains("/") }
                ?.let { parentPath(it) }

            val attachments = marker.references.map { reference ->
                MessageAttachment(
                    name = displayName(reference),
                    path = inferredPath(reference, inferredDirectory),
                    mime = null,
                    size = null,
                    isImage = isImageReference(reference),
                )
            }

            return attachments.ifEmpty { null }
        }

        /**
         * Returns the message text with the trailing `[Attached files: …]`
         * marker (and the blank-line separator it was appended after) removed,
         * for the display layer to render. Reuses the same parser as
         * attachment inference so the two can never disagree.
         */
        fun contentWithoutAttachedFilesMarker(content: String): String {
            val marker = attachedFilesMarker(content) ?: return content

            // The parser rejects any non-whitespace after the closing bracket,
            // so the marker is always a suffix; everything before it is the
            // user's typed message. Drop the trailing separator whitespace too.
            return content.substring(0, marker.startIndex).trimEnd()
        }

        private data class Marker(
            val startIndex: Int,
            val references: List<String>,
        )

        private fun attachedFilesMarker(content: String): Marker? {
            val markerStart = content.lastIndexOf("[Attached files:")
            if (markerStart < 0) return null

            val afterMarkerIndex = markerStart + "[Attached files:".length
            val closeBracket = content.indexOf(']', startIndex = afterMarkerIndex)
            if (closeBracket < 0) return null

            val afterBracket = content.substring(closeBracket + 1)
            if (afterBracket.trim().isNotEmpty()) return null

            val references = content.substring(afterMarkerIndex, closeBracket)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            return Marker(startIndex = markerStart, references = references)
        }

        private fun displayName(reference: String): String {
            val lastComponent = lastPathComponent(reference)
            return lastComponent.ifEmpty { reference }
        }

        private fun inferredPath(reference: String, fallbackDirectory: String?): String? {
            if (reference.contains("/")) return reference

            if (!isImageReference(reference) || fallbackDirectory.isNullOrEmpty()) return null

            return "${fallbackDirectory.trimEnd('/')}/$reference"
        }

        private val imageExtensions =
            setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "tif")

        private fun isImageReference(reference: String): Boolean {
            val ext = lastPathComponent(reference).substringAfterLast('.', missingDelimiterValue = "")
            return ext.lowercase() in imageExtensions
        }

        internal fun lastPathComponent(path: String): String =
            path.trimEnd('/').substringAfterLast('/')

        internal fun parentPath(path: String): String =
            path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
    }
}

object MessageAttachmentSerializer : KSerializer<MessageAttachment> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MessageAttachment")

    override fun deserialize(decoder: Decoder): MessageAttachment {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("MessageAttachment supports JSON decoding only")

        val element = input.decodeJsonElement()

        // Some old server data stores attachments as bare strings.
        if (element is JsonPrimitive && element !is JsonNull && element.isString) {
            return MessageAttachment(name = element.content)
        }

        val obj = element as? JsonObject
            ?: throw SerializationException("Expected JSON object or string for MessageAttachment")

        return MessageAttachment(
            name = obj.lossyString("name") ?: obj.lossyString("filename"),
            path = obj.lossyString("path"),
            mime = obj.lossyString("mime"),
            size = obj.lossyInt("size"),
            // Snake wire key first; camelCase matches the Swift cache
            // round-trip, which encodes the CodingKey raw value verbatim.
            isImage = obj.lossyBool("is_image", "isImage"),
        )
    }

    override fun serialize(encoder: Encoder, value: MessageAttachment) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("MessageAttachment supports JSON encoding only")
        output.encodeJsonElement(
            buildJsonObject {
                value.name?.let { put("name", JsonPrimitive(it)) }
                value.path?.let { put("path", JsonPrimitive(it)) }
                value.mime?.let { put("mime", JsonPrimitive(it)) }
                value.size?.let { put("size", JsonPrimitive(it)) }
                value.isImage?.let { put("is_image", JsonPrimitive(it)) }
            },
        )
    }
}
