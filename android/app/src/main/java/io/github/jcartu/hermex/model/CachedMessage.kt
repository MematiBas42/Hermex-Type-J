package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable

/**
 * Port of `CachedMessage.swift`. iOS stores the tool-call/content-part/
 * attachment lists as JSON-encoded `Data?` blobs inside SwiftData; the Android
 * cache file is already JSON, so the lists are stored directly as typed
 * fields (same names). Like the Swift `apply`, a list is only stored when
 * non-empty. `cachedAt`/`expiresAt` are epoch millis.
 */
@Serializable
data class CachedMessage(
    val cacheKey: String,
    val serverURLString: String,
    val sessionID: String,
    val sortIndex: Int,
    val role: String? = null,
    val content: String? = null,
    val timestamp: Double? = null,
    val messageId: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolUseId: String? = null,
    val toolCallsData: List<JSONValue>? = null,
    val contentPartsData: List<JSONValue>? = null,
    val reasoning: String? = null,
    val attachmentsData: List<MessageAttachment>? = null,
    val turnTps: Double? = null,
    val cachedAt: Long,
    val expiresAt: Long,
) {
    /**
     * Port of the private `ChatMessage.init(cachedMessage:)` extension in
     * `CacheStore.swift`. No blob decoding is needed on Android — the lists
     * are stored typed.
     */
    fun toChatMessage(): ChatMessage = ChatMessage(
        role = role,
        content = content,
        timestamp = timestamp,
        messageId = messageId,
        name = name,
        toolCallId = toolCallId,
        toolUseId = toolUseId,
        toolCalls = toolCallsData,
        contentParts = contentPartsData,
        reasoning = reasoning,
        attachments = attachmentsData,
        turnTps = turnTps,
    )

    companion object {
        /**
         * Port of `CachedMessage.cacheKey(serverURLString:sessionID:message:sortIndex:)`.
         * Swift interpolates `message.timestamp ?? 0` as a `Double`, so the
         * missing-timestamp fallback renders as `"0.0"`; `?: 0.0` with Kotlin's
         * `Double.toString` matches that rendering.
         */
        fun cacheKey(
            serverURLString: String,
            sessionID: String,
            message: ChatMessage,
            sortIndex: Int,
        ): String {
            val messagePart = message.messageId ?: "$sortIndex-${message.timestamp ?: 0.0}"
            return "$serverURLString|session|$sessionID|message|$messagePart"
        }

        /**
         * Port of `init(serverURLString:sessionID:message:sortIndex:cachedAt:)`
         * + `apply(_:sortIndex:cachedAt:)` collapsed into one factory.
         * `expiresAt = cachedAt + CachePolicy.ttl`; empty lists are dropped.
         */
        fun from(
            serverURLString: String,
            sessionID: String,
            message: ChatMessage,
            sortIndex: Int,
            cachedAt: Long,
        ): CachedMessage = CachedMessage(
            cacheKey = cacheKey(serverURLString, sessionID, message, sortIndex),
            serverURLString = serverURLString,
            sessionID = sessionID,
            sortIndex = sortIndex,
            role = message.role,
            content = message.content,
            timestamp = message.timestamp,
            messageId = message.messageId,
            name = message.name,
            toolCallId = message.toolCallId,
            toolUseId = message.toolUseId,
            toolCallsData = message.toolCalls?.takeIf { it.isNotEmpty() },
            contentPartsData = message.contentParts?.takeIf { it.isNotEmpty() },
            reasoning = message.reasoning,
            attachmentsData = message.attachments?.takeIf { it.isNotEmpty() },
            turnTps = message.turnTps,
            cachedAt = cachedAt,
            expiresAt = cachedAt + CachePolicy.ttl,
        )
    }
}
