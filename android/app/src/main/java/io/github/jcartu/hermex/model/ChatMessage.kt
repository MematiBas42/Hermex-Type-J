package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.truncate

/**
 * Port of `ChatMessage.swift`. Wire format is snake_case (the Swift APIClient
 * decodes with `.convertFromSnakeCase`); the special keys `_ts` and `_turn_tps`
 * mirror the Swift `underscoredTimestamp`/`turnTps` CodingKeys. camelCase
 * spellings are accepted as fallbacks, matching the Swift plain-decoder paths.
 */
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String? = null,
    val content: String? = null,
    val timestamp: Double? = null,
    val messageId: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolUseId: String? = null,
    val toolCalls: List<JSONValue>? = null,
    val contentParts: List<JSONValue>? = null,
    val reasoning: String? = null,
    val attachments: List<MessageAttachment>? = null,
    val turnTps: Double? = null,
) {
    val id: String
        get() = messageId ?: "${role ?: "unknown"}-${timestamp ?: 0.0}-${content ?: ""}"

    companion object {
        internal fun fromJson(json: kotlinx.serialization.json.Json, obj: JsonObject): ChatMessage {
            val role = obj.lossyString("role")
            val (content, contentParts) = decodeContentTolerantly(obj)
            val decodedAttachments = decodeAttachmentsTolerantly(json, obj)
            return ChatMessage(
                role = role,
                content = content,
                timestamp = obj.lossyDouble("_ts") ?: obj.lossyDouble("timestamp"),
                messageId = obj.lossyString("message_id", "messageId"),
                name = obj.lossyString("name"),
                toolCallId = obj.lossyString("tool_call_id", "toolCallId"),
                toolUseId = obj.lossyString("tool_use_id", "toolUseId"),
                toolCalls = decodeJsonValueList(obj.presentElement("tool_calls", "toolCalls")),
                contentParts = contentParts,
                reasoning = obj.lossyString("reasoning"),
                attachments = attachments(decodedAttachments, enrichedByMarkerIn = content),
                turnTps = obj.lossyDouble("_turn_tps", "_turnTps"),
            )
        }

        private fun attachments(
            decodedAttachments: List<MessageAttachment>?,
            enrichedByMarkerIn: String?,
        ): List<MessageAttachment>? {
            val inferredAttachments = MessageAttachment.inferredFromAttachedFilesMarker(enrichedByMarkerIn)

            if (decodedAttachments.isNullOrEmpty()) {
                return inferredAttachments ?: decodedAttachments
            }

            if (inferredAttachments.isNullOrEmpty()) {
                return decodedAttachments
            }

            val availableInferred = inferredAttachments
                .mapIndexed { offset, element -> IndexedValue(offset, element) }
                .toMutableList()

            return decodedAttachments.mapIndexed { index, attachment ->
                val inferred = if (nonEmptyString(attachment.path) == null) {
                    matchingInferredAttachment(attachment, index, availableInferred)
                } else {
                    null
                }

                if (inferred == null) {
                    attachment
                } else {
                    MessageAttachment(
                        name = nonEmptyString(attachment.name) ?: inferred.name,
                        path = nonEmptyString(inferred.path),
                        mime = attachment.mime ?: inferred.mime,
                        size = attachment.size ?: inferred.size,
                        isImage = attachment.isImage ?: inferred.isImage,
                    )
                }
            }
        }

        private fun matchingInferredAttachment(
            attachment: MessageAttachment,
            index: Int,
            availableInferred: MutableList<IndexedValue<MessageAttachment>>,
        ): MessageAttachment? {
            val key = attachment.identityKey
            if (key != null) {
                val matchedIndex = availableInferred.indexOfFirst { it.value.identityKey == key }
                if (matchedIndex >= 0) {
                    return availableInferred.removeAt(matchedIndex).value
                }
            }

            val matchedIndex = availableInferred.indexOfFirst { it.index == index }
            if (matchedIndex < 0) return null
            return availableInferred.removeAt(matchedIndex).value
        }

        private fun nonEmptyString(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        private fun decodeContentTolerantly(obj: JsonObject): Pair<String?, List<JSONValue>?> {
            lossyString(obj["content"])?.let { return it to null }

            val element = obj.presentElement("content") ?: return null to null
            val value = JSONValue(element)

            value.arrayValue?.let { parts ->
                return textContent(parts) to parts
            }

            return value.compactJSONString to null
        }

        private fun textContent(parts: List<JSONValue>): String? {
            val text = parts
                .mapNotNull { part ->
                    part.string ?: part.objectValue
                        ?.takeIf { it["type"]?.stringValue == "text" }
                        ?.get("text")?.stringValue
                }
                .joinToString(separator = "")
                .trim()

            return text.ifEmpty { null }
        }

        private fun decodeAttachmentsTolerantly(
            json: kotlinx.serialization.json.Json,
            obj: JsonObject,
        ): List<MessageAttachment>? {
            val element = obj.presentElement("attachments") ?: return null

            // Fast path: direct array decode when every attachment is well-shaped.
            runCatching {
                return json.decodeFromJsonElement(ListSerializer(MessageAttachment.serializer()), element)
            }

            // Fallback: decode item by item so one malformed attachment does not
            // throw away the entire message array.
            val values = element as? JsonArray ?: return null
            return values.mapNotNull { value ->
                runCatching {
                    json.decodeFromJsonElement(MessageAttachment.serializer(), value)
                }.getOrNull()
            }
        }

        internal fun decodeJsonValueList(element: JsonElement?): List<JSONValue>? {
            val array = element as? JsonArray ?: return null
            return array.map { JSONValue(it) }
        }
    }
}

internal object ChatMessageSerializer : TolerantObjectSerializer<ChatMessage>("ChatMessage") {
    override fun fromJson(json: Json, obj: JsonObject): ChatMessage = ChatMessage.fromJson(json, obj)

    override fun toJson(json: Json, value: ChatMessage): JsonObject = buildJsonObject {
        value.role?.let { put("role", JsonPrimitive(it)) }
        value.content?.let { put("content", JsonPrimitive(it)) }
        value.timestamp?.let { put("timestamp", JsonPrimitive(it)) }
        value.messageId?.let { put("message_id", JsonPrimitive(it)) }
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
        value.toolUseId?.let { put("tool_use_id", JsonPrimitive(it)) }
        value.toolCalls?.let { calls -> put("tool_calls", JsonArray(calls.map { it.element })) }
        value.contentParts?.let { parts ->
            // Round-trips through the same tolerant content union the
            // decoder reads: an array under `content`.
            if (value.content == null) put("content", JsonArray(parts.map { it.element }))
        }
        value.reasoning?.let { put("reasoning", JsonPrimitive(it)) }
        value.attachments?.let { attachments ->
            put(
                "attachments",
                json.encodeToJsonElement(ListSerializer(MessageAttachment.serializer()), attachments),
            )
        }
        value.turnTps?.let { put("_turn_tps", JsonPrimitive(it)) }
    }
}

/**
 * Port of `TranscriptTurnClassifier` from `ChatMessage.swift`.
 */
object TranscriptTurnClassifier {

    fun anchorID(message: ChatMessage, index: Int, messageOffset: Int? = null): String {
        nonEmpty(message.messageId)?.let { return it }
        return "raw:${maxOf(0, messageOffset ?: 0) + index}"
    }

    fun isUserTurnBoundary(message: ChatMessage): Boolean {
        if (message.role != "user") return false
        return hasVisibleUserContent(message)
    }

    fun isToolResultOnlyMessage(message: ChatMessage): Boolean =
        message.role == "user" && !hasVisibleUserContent(message)

    fun assistantTurnKeysByAnchorID(
        messages: List<ChatMessage>,
        messageOffset: Int? = null,
    ): Map<String, String> {
        val keysByMessageID = mutableMapOf<String, String>()
        var currentTurnKey = "turn:start"

        messages.forEachIndexed { messageIndex, message ->
            if (isUserTurnBoundary(message)) {
                currentTurnKey = "turn:user:${maxOf(0, messageOffset ?: 0) + messageIndex}"
            }

            if (message.role == "assistant") {
                keysByMessageID[anchorID(message, messageIndex, messageOffset)] = currentTurnKey
            }
        }

        return keysByMessageID
    }

    fun assistantTurnKeysByMessageID(messages: List<ChatMessage>): Map<String, String> =
        assistantTurnKeysByAnchorID(messages)

    fun assistantAnchorID(
        rawIndex: Int,
        messages: List<ChatMessage>,
        messageOffset: Int? = null,
    ): String? {
        if (rawIndex !in messages.indices) return null

        if (messages[rawIndex].role == "assistant") {
            return anchorID(messages[rawIndex], rawIndex, messageOffset)
        }

        val lowerBound = previousUserBoundaryIndex(rawIndex, messages)?.plus(1) ?: 0
        if (rawIndex > lowerBound) {
            for (index in rawIndex - 1 downTo lowerBound) {
                if (messages[index].role == "assistant") {
                    return anchorID(messages[index], index, messageOffset)
                }
            }
        }

        val upperBound = nextUserBoundaryIndex(rawIndex, messages) ?: messages.size
        if (rawIndex + 1 < upperBound) {
            for (index in rawIndex + 1 until upperBound) {
                if (messages[index].role == "assistant") {
                    return anchorID(messages[index], index, messageOffset)
                }
            }
        }

        return null
    }

    fun currentTurnAssistantAnchorIDs(
        messages: List<ChatMessage>,
        messageOffset: Int? = null,
    ): List<String> {
        val latestUserIndex = messages.indexOfLast { isUserTurnBoundary(it) }
        val startIndex = if (latestUserIndex >= 0) latestUserIndex + 1 else 0
        if (startIndex >= messages.size) return emptyList()

        return messages.subList(startIndex, messages.size).mapIndexedNotNull { offset, message ->
            if (message.role != "assistant") return@mapIndexedNotNull null
            anchorID(message, startIndex + offset, messageOffset)
        }
    }

    fun currentTurnAssistantMessageIDs(messages: List<ChatMessage>): List<String> =
        currentTurnAssistantAnchorIDs(messages)

    private fun previousUserBoundaryIndex(rawIndex: Int, messages: List<ChatMessage>): Int? {
        if (rawIndex <= 0) return null

        for (index in rawIndex - 1 downTo 0) {
            if (isUserTurnBoundary(messages[index])) return index
        }

        return null
    }

    private fun nextUserBoundaryIndex(rawIndex: Int, messages: List<ChatMessage>): Int? {
        val nextIndex = rawIndex + 1
        if (nextIndex >= messages.size) return null

        for (index in nextIndex until messages.size) {
            if (isUserTurnBoundary(messages[index])) return index
        }

        return null
    }

    private fun hasVisibleUserContent(message: ChatMessage): Boolean {
        if (message.role != "user") return false

        if (message.content?.trim()?.isNotEmpty() == true) return true

        return message.attachments?.isNotEmpty() == true
    }

    private fun nonEmpty(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}

// MARK: - Lossy decoding helpers (port of the KeyedDecodingContainer extension
// in ChatMessage.swift). Result-chained like the Swift `??` chains: a key whose
// value cannot be coerced falls through to the next key.

/** First element present under any of [keys], skipping JSON `null` (mirrors `decodeIfPresent`). */
internal fun JsonObject.presentElement(vararg keys: String): JsonElement? {
    for (key in keys) {
        val element = this[key] ?: continue
        if (element is JsonNull) continue
        return element
    }
    return null
}

internal fun lossyString(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (primitive.isString) return primitive.content
    primitive.longOrNull?.let { return it.toString() }
    primitive.doubleOrNull?.let { return it.toString() }
    primitive.booleanOrNull?.let { return if (it) "true" else "false" }
    return null
}

internal fun lossyDouble(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (!primitive.isString) {
        if (primitive.booleanOrNull != null) return null
        return primitive.doubleOrNull
    }
    return primitive.content.trim().toDoubleOrNull()
}

internal fun lossyInt(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null

    fun intExactly(value: Double): Int? {
        if (!value.isFinite()) return null
        val truncated = truncate(value)
        // Same out-of-range guard as Swift's `Int(exactly:)` (#62): huge server
        // values decode to null instead of crashing/overflowing.
        if (truncated < Int.MIN_VALUE.toDouble() || truncated > Int.MAX_VALUE.toDouble()) return null
        return truncated.toInt()
    }

    if (!primitive.isString) {
        if (primitive.booleanOrNull != null) return null
        primitive.longOrNull?.let { long ->
            return if (long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) long.toInt() else null
        }
        return primitive.doubleOrNull?.let(::intExactly)
    }

    val trimmed = primitive.content.trim()
    trimmed.toIntOrNull()?.let { return it }
    return trimmed.toDoubleOrNull()?.let(::intExactly)
}

internal fun lossyBool(element: JsonElement?): Boolean? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null

    if (!primitive.isString) {
        primitive.booleanOrNull?.let { return it }
        return when (primitive.longOrNull) {
            0L -> false
            1L -> true
            else -> null
        }
    }

    return when (primitive.content.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}

internal fun JsonObject.lossyString(vararg keys: String): String? {
    for (key in keys) lossyString(this[key])?.let { return it }
    return null
}

internal fun JsonObject.lossyDouble(vararg keys: String): Double? {
    for (key in keys) lossyDouble(this[key])?.let { return it }
    return null
}

internal fun JsonObject.lossyInt(vararg keys: String): Int? {
    for (key in keys) lossyInt(this[key])?.let { return it }
    return null
}

internal fun JsonObject.lossyBool(vararg keys: String): Boolean? {
    for (key in keys) lossyBool(this[key])?.let { return it }
    return null
}
