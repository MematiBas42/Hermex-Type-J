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

        /** Parsed display text and chips for a Hermes-host transcript row (#1012). */
        data class HermesReferenceResult(
            val text: String,
            val attachments: List<MessageAttachment>,
        )

        /**
         * Port of `MessageAttachment.hermesReferences(in:)`: strips the exact
         * image/file reference blocks Hermex sends to Hermes and turns them
         * into attachment chips. Ordinary user text is returned unchanged.
         */
        fun hermesReferences(content: String): HermesReferenceResult {
            if (
                "@file:" !in content &&
                "[The user attached an image: " !in content &&
                " Context ---" !in content &&
                "--- Context Warnings ---" !in content
            ) return HermesReferenceResult(content, emptyList())

            val footer = HERMES_CONTEXT_FOOTER.find(content)
            val body = footer?.let { content.substring(0, it.range.first) } ?: content
            val kept = mutableListOf<String>()
            val attachments = mutableListOf<MessageAttachment>()
            body.split("\n\n").forEach { block ->
                val line = block.trim()
                val image = HERMES_IMAGE_REFERENCE.matchEntire(line)
                val file = HERMES_FILE_REFERENCE.matchEntire(line)
                when {
                    image != null -> attachments += MessageAttachment(
                        name = hermesImageName(image.groupValues[1]),
                        path = image.groupValues[2],
                        isImage = true,
                    )
                    file != null -> {
                        val path = file.groupValues.drop(1).firstOrNull(String::isNotEmpty)
                        if (path != null) {
                            attachments += MessageAttachment(
                                name = hermesFileName(path),
                                path = path,
                                isImage = false,
                            )
                        }
                    }
                    else -> kept += block
                }
            }
            if (footer == null && attachments.isEmpty()) {
                return HermesReferenceResult(content, emptyList())
            }
            return HermesReferenceResult(kept.joinToString("\n\n").trimEnd(), attachments)
        }

        /** Display-safe title/preview with Hermes attachment references removed. */
        fun hermesTitle(value: String?): String? {
            val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if ('[' !in text && '@' !in text) return text
            val parsed = hermesReferences(text)
            if (parsed.text != text || parsed.attachments.isNotEmpty()) {
                val typed = parsed.text.replace(Regex("\\s+"), " ").trim()
                return typed.ifEmpty { parsed.attachments.firstOrNull()?.name }
            }
            val names = mutableListOf<String>()
            var stripped = HERMES_IMAGE_INLINE.replace(text) { match ->
                names += hermesImageName(match.groupValues[1])
                ""
            }
            stripped = HERMES_FILE_INLINE.replace(stripped) { match ->
                val path = match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
                if (path.isNotEmpty()) names += hermesFileName(path)
                ""
            }
            val typed = stripped.replace(Regex("\\s+"), " ").trim()
            return typed.ifEmpty { names.firstOrNull() ?: text }
        }

        private fun hermesImageName(value: String): String =
            value.replace(Regex("^dashboard_\\d{8}_\\d{6}_[0-9a-f]{8}_"), "")

        private fun hermesFileName(value: String): String =
            lastPathComponent(value).replace(
                Regex("^[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}-"),
                "",
            )

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

        private val HERMES_IMAGE_REFERENCE = Regex(
            """\[The user attached an image: ([^\n]*)]\n\[Examine it with the vision_analyze tool using image_url: (/[^\n]*)]""",
        )
        private val HERMES_FILE_REFERENCE =
            Regex("""@file:(?:`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|(\S+))""")
        private val HERMES_CONTEXT_FOOTER =
            Regex("""(?m)(?:^|\n)--- (?:Context Warnings|Attached Context) ---[ \t]*(?:\n|$)""")
        private val HERMES_IMAGE_INLINE =
            Regex("""\[The user attached an image: ([^]]*)]""")
        private val HERMES_FILE_INLINE =
            Regex("""@file:(?:`([^`]+)`|"([^"]+)"|'([^']+)'|(\S+))""")
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
