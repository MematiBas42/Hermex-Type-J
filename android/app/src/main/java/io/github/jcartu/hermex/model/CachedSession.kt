package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable

/**
 * Port of `CachePolicy` from `CachedSession.swift`. The Swift `ttl` is a
 * `TimeInterval` in seconds; Android timestamps are epoch millis, so the same
 * 7-day window is expressed in milliseconds here.
 */
object CachePolicy {
    /** 7 days (Swift: `7 * 24 * 60 * 60` seconds), in milliseconds. */
    const val ttl: Long = 7L * 24 * 60 * 60 * 1000
    const val maxMessages: Int = 5_000
}

/**
 * Port of `CachedSession.swift`. iOS persists this via SwiftData; on Android
 * it is a plain `@Serializable` record stored in the file-backed
 * [io.github.jcartu.hermex.data.CacheStore]. Field list mirrors the Swift
 * `@Model` exactly; `cachedAt`/`expiresAt` are epoch millis.
 */
@Serializable
data class CachedSession(
    val cacheKey: String,
    val serverURLString: String,
    val sessionID: String,
    val title: String? = null,
    val workspace: String? = null,
    val model: String? = null,
    val modelProvider: String? = null,
    val messageCount: Int? = null,
    val createdAt: Double? = null,
    val updatedAt: Double? = null,
    val lastMessageAt: Double? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val projectId: String? = null,
    val profile: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCost: Double? = null,
    val activeStreamId: String? = null,
    val isStreaming: Boolean? = null,
    val isCliSession: Boolean? = null,
    val userMessageCount: Int? = null,
    val hasPendingUserMessage: Boolean? = null,
    val pendingStartedAt: Double? = null,
    val worktreePath: String? = null,
    val sourceTag: String? = null,
    val rawSource: String? = null,
    val sessionSource: String? = null,
    val sourceLabel: String? = null,
    val parentSessionId: String? = null,
    val relationshipType: String? = null,
    val readOnly: Boolean? = null,
    val isReadOnly: Boolean? = null,
    val cachedAt: Long,
    val expiresAt: Long,
) {
    /**
     * Port of the private `SessionSummary.init(cachedSession:)` extension in
     * `CacheStore.swift`. `isStreaming` is carried through; `matchType` is
     * always `nil` for cached rows.
     */
    fun toSessionSummary(): SessionSummary = SessionSummary(
        sessionId = sessionID,
        title = title,
        workspace = workspace,
        model = model,
        modelProvider = modelProvider,
        messageCount = messageCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        pinned = pinned,
        archived = archived,
        projectId = projectId,
        profile = profile,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        estimatedCost = estimatedCost,
        activeStreamId = activeStreamId,
        isStreaming = isStreaming,
        isCliSession = isCliSession,
        userMessageCount = userMessageCount,
        hasPendingUserMessage = hasPendingUserMessage,
        pendingStartedAt = pendingStartedAt,
        worktreePath = worktreePath,
        sourceTag = sourceTag,
        rawSource = rawSource,
        sessionSource = sessionSource,
        sourceLabel = sourceLabel,
        parentSessionId = parentSessionId,
        relationshipType = relationshipType,
        readOnly = readOnly,
        isReadOnly = isReadOnly,
        matchType = null,
    )

    companion object {
        /** Port of `CachedSession.cacheKey(serverURLString:sessionID:)`. */
        fun cacheKey(serverURLString: String, sessionID: String): String =
            "$serverURLString|session|$sessionID"

        /**
         * Port of `init(serverURLString:session:cachedAt:)` + `apply(_:cachedAt:)`
         * (the record is immutable here, so init and apply collapse into one
         * factory). `expiresAt = cachedAt + CachePolicy.ttl`.
         */
        fun from(serverURLString: String, session: SessionSummary, cachedAt: Long): CachedSession {
            val sessionID = session.sessionId ?: session.id
            return CachedSession(
                cacheKey = cacheKey(serverURLString, sessionID),
                serverURLString = serverURLString,
                sessionID = sessionID,
                title = session.title,
                workspace = session.workspace,
                model = session.model,
                modelProvider = session.modelProvider,
                messageCount = session.messageCount,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                lastMessageAt = session.lastMessageAt,
                pinned = session.pinned,
                archived = session.archived,
                projectId = session.projectId,
                profile = session.profile,
                inputTokens = session.inputTokens,
                outputTokens = session.outputTokens,
                estimatedCost = session.estimatedCost,
                activeStreamId = session.activeStreamId,
                isStreaming = session.isStreaming,
                isCliSession = session.isCliSession,
                userMessageCount = session.userMessageCount,
                hasPendingUserMessage = session.hasPendingUserMessage,
                pendingStartedAt = session.pendingStartedAt,
                worktreePath = session.worktreePath,
                sourceTag = session.sourceTag,
                rawSource = session.rawSource,
                sessionSource = session.sessionSource,
                sourceLabel = session.sourceLabel,
                parentSessionId = session.parentSessionId,
                relationshipType = session.relationshipType,
                readOnly = session.readOnly,
                isReadOnly = session.isReadOnly,
                cachedAt = cachedAt,
                expiresAt = cachedAt + CachePolicy.ttl,
            )
        }
    }
}
