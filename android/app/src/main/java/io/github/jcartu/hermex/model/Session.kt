package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** Port of `SessionsResponse` from `Session.swift`. */
@Serializable
data class SessionsResponse(
    val sessions: List<SessionSummary>? = null,
    @SerialName("cli_count") val cliCount: Int? = null,
    /**
     * Total archived sessions in the active profile (`archived_count`), present
     * on every response regardless of `include_archived` (issue #17). Optional
     * so older servers that omit it decode fine.
     */
    @SerialName("archived_count") val archivedCount: Int? = null,
    @SerialName("server_time") val serverTime: Double? = null,
    @SerialName("server_tz") val serverTz: String? = null,
)

/** Port of `SessionSearchResponse` from `Session.swift`. */
@Serializable
data class SessionSearchResponse(
    val sessions: List<SessionSummary>? = null,
    val query: String? = null,
    val count: Int? = null,
)

/** Port of `SessionResponse` from `Session.swift`. */
@Serializable
data class SessionResponse(
    val session: SessionDetail? = null,
)

/** Port of `SessionMutationResponse` from `Session.swift`. */
@Serializable
data class SessionMutationResponse(
    val ok: Boolean? = null,
    val session: SessionSummary? = null,
    val error: String? = null,
)

/** Port of `ProjectsResponse` from `Session.swift` — a malformed `projects` array decodes to null. */
@Serializable(with = ProjectsResponseSerializer::class)
data class ProjectsResponse(
    val projects: List<ProjectSummary>? = null,
)

internal object ProjectsResponseSerializer : TolerantObjectSerializer<ProjectsResponse>("ProjectsResponse") {
    override fun fromJson(json: Json, obj: JsonObject): ProjectsResponse =
        ProjectsResponse(
            projects = obj.presentElement("projects")?.let { element ->
                runCatching {
                    json.decodeFromJsonElement(ListSerializer(ProjectSummary.serializer()), element)
                }.getOrNull()
            },
        )

    override fun toJson(json: Json, value: ProjectsResponse): JsonObject = buildJsonObject {
        value.projects?.let {
            put("projects", json.encodeToJsonElement(ListSerializer(ProjectSummary.serializer()), it))
        }
    }
}

/** Port of `ProjectMutationResponse` from `Session.swift`. */
@Serializable(with = ProjectMutationResponseSerializer::class)
data class ProjectMutationResponse(
    val ok: Boolean? = null,
    val project: ProjectSummary? = null,
    val error: String? = null,
)

internal object ProjectMutationResponseSerializer :
    TolerantObjectSerializer<ProjectMutationResponse>("ProjectMutationResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ProjectMutationResponse =
        ProjectMutationResponse(
            ok = obj.lossyBool("ok"),
            project = obj.presentElement("project")?.let { element ->
                runCatching { json.decodeFromJsonElement(ProjectSummary.serializer(), element) }.getOrNull()
            },
            error = obj.lossyString("error"),
        )

    override fun toJson(json: Json, value: ProjectMutationResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.project?.let { put("project", json.encodeToJsonElement(ProjectSummary.serializer(), it)) }
        value.error?.let { put("error", JsonPrimitive(it)) }
    }
}

/** Port of `ProjectSummary` from `Session.swift`. */
@Serializable(with = ProjectSummarySerializer::class)
data class ProjectSummary(
    val projectId: String? = null,
    val name: String? = null,
    val color: String? = null,
    val createdAt: Double? = null,
) {
    val id: String
        get() = projectId ?: name ?: UUID.randomUUID().toString()
}

internal object ProjectSummarySerializer : TolerantObjectSerializer<ProjectSummary>("ProjectSummary") {
    override fun fromJson(json: Json, obj: JsonObject): ProjectSummary =
        ProjectSummary(
            projectId = obj.lossyString("project_id", "projectId"),
            name = obj.lossyString("name"),
            color = obj.lossyString("color"),
            createdAt = obj.lossyDouble("created_at", "createdAt"),
        )

    override fun toJson(json: Json, value: ProjectSummary): JsonObject = buildJsonObject {
        value.projectId?.let { put("project_id", JsonPrimitive(it)) }
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.color?.let { put("color", JsonPrimitive(it)) }
        value.createdAt?.let { put("created_at", JsonPrimitive(it)) }
    }
}

/** Port of `SessionBranchResponse` from `Session.swift`. */
@Serializable
data class SessionBranchResponse(
    @SerialName("session_id") val sessionId: String? = null,
    val title: String? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    val error: String? = null,
)

/** Port of `SessionCompressResponse` from `Session.swift`. */
@Serializable
data class SessionCompressResponse(
    val ok: Boolean? = null,
    val session: SessionDetail? = null,
    val summary: SessionCompressionSummary? = null,
    @SerialName("focus_topic") val focusTopic: String? = null,
    val error: String? = null,
)

/** Port of `SessionCompressionSummary` from `Session.swift`. */
@Serializable
data class SessionCompressionSummary(
    val headline: String? = null,
    @SerialName("token_line") val tokenLine: String? = null,
    val note: String? = null,
    @SerialName("reference_message") val referenceMessage: String? = null,
) {
    val compressedTokenEstimate: Int?
        get() {
            val line = tokenLine?.takeIf { it.isNotEmpty() } ?: return null

            val trailingTokenText = line
                .split("\u2192").last()
                .split("->").last()

            val digits = trailingTokenText.filter { it.isDigit() }
            if (digits.isEmpty()) return null
            return digits.toIntOrNull()
        }
}

/** Port of `SessionUndoResponse` from `Session.swift`. */
@Serializable
data class SessionUndoResponse(
    val ok: Boolean? = null,
    @SerialName("removed_count") val removedCount: Int? = null,
    @SerialName("removed_preview") val removedPreview: String? = null,
    val error: String? = null,
)

/** Port of `SessionRetryResponse` from `Session.swift`. */
@Serializable
data class SessionRetryResponse(
    val ok: Boolean? = null,
    @SerialName("last_user_text") val lastUserText: String? = null,
    @SerialName("removed_count") val removedCount: Int? = null,
    val error: String? = null,
)

/** Port of `SessionStatusResponse` from `Session.swift`. */
@Serializable
data class SessionStatusResponse(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("active_stream_id") val activeStreamId: String? = null,
    @SerialName("is_streaming") val isStreaming: Boolean? = null,
    @SerialName("pending_user_message") val pendingUserMessage: String? = null,
    val error: String? = null,
)

/** Port of `SessionSummary` from `Session.swift` (synthesized snake_case decoding). */
@Serializable
data class SessionSummary(
    @SerialName("session_id") val sessionId: String? = null,
    val title: String? = null,
    val workspace: String? = null,
    val model: String? = null,
    @SerialName("model_provider") val modelProvider: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("last_message_at") val lastMessageAt: Double? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    @SerialName("project_id") val projectId: String? = null,
    val profile: String? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("estimated_cost") val estimatedCost: Double? = null,
    @SerialName("active_stream_id") val activeStreamId: String? = null,
    @SerialName("is_streaming") val isStreaming: Boolean? = null,
    @SerialName("is_cli_session") val isCliSession: Boolean? = null,
    @SerialName("user_message_count") val userMessageCount: Int? = null,
    @SerialName("has_pending_user_message") val hasPendingUserMessage: Boolean? = null,
    @SerialName("pending_started_at") val pendingStartedAt: Double? = null,
    @SerialName("worktree_path") val worktreePath: String? = null,
    @SerialName("source_tag") val sourceTag: String? = null,
    @SerialName("raw_source") val rawSource: String? = null,
    @SerialName("session_source") val sessionSource: String? = null,
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    @SerialName("relationship_type") val relationshipType: String? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
    @SerialName("is_read_only") val isReadOnly: Boolean? = null,
    @SerialName("match_type") val matchType: String? = null,
) {
    /** Port of `init(from detail:)`. */
    constructor(detail: SessionDetail) : this(
        sessionId = detail.sessionId,
        title = detail.title,
        workspace = detail.workspace,
        model = detail.model,
        modelProvider = detail.modelProvider,
        messageCount = detail.messageCount ?: detail.messages?.size,
        createdAt = detail.createdAt,
        updatedAt = detail.updatedAt,
        lastMessageAt = detail.lastMessageAt,
        pinned = detail.pinned,
        archived = detail.archived,
        projectId = detail.projectId,
        profile = detail.profile,
        inputTokens = detail.inputTokens,
        outputTokens = detail.outputTokens,
        estimatedCost = detail.estimatedCost,
        activeStreamId = detail.activeStreamId,
        isStreaming = null,
        isCliSession = detail.isCliSession,
        userMessageCount = null,
        hasPendingUserMessage = if (
            nonEmpty(detail.pendingUserMessage) != null || detail.pendingAttachments?.isNotEmpty() == true
        ) true else null,
        pendingStartedAt = detail.pendingStartedAt,
        worktreePath = detail.worktreePath,
        sourceTag = detail.sourceTag,
        rawSource = detail.rawSource,
        sessionSource = detail.sessionSource,
        sourceLabel = detail.sourceLabel,
        parentSessionId = detail.parentSessionId,
        relationshipType = detail.relationshipType,
        readOnly = detail.readOnly,
        isReadOnly = detail.isReadOnly,
        matchType = null,
    )

    val id: String
        get() {
            if (!sessionId.isNullOrEmpty()) return sessionId

            val titlePart = title?.trim() ?: "untitled"
            val timestamp = createdAt ?: updatedAt ?: lastMessageAt ?: 0.0
            return "session-$titlePart-$timestamp"
        }

    /**
     * Mirrors all stored fields so local title patches preserve session-list
     * metadata (data-class `copy` keeps every other stored property).
     */
    fun replacingTitle(title: String): SessionSummary = copy(title = title)

    /**
     * Delegated children are identified only by an explicit source marker.
     * Parent linkage is shared by ordinary forks and compression continuations,
     * so it must never classify a row as a subagent on its own.
     */
    val isDelegatedSubagentSession: Boolean
        get() = listOf(sourceTag, rawSource, sessionSource, sourceLabel)
            .mapNotNull(::normalizedSourceMarker)
            .contains("subagent")

    /**
     * Claude Code imports are classified only by explicit upstream source
     * metadata. Titles, models, and read-only/CLI flags are intentionally not
     * descriptive enough to identify this source.
     */
    val isClaudeCodeSession: Boolean
        get() = listOf(sourceTag, rawSource)
            .mapNotNull(::normalizedSourceMarker)
            .contains("claude_code")

    /**
     * Delegated children are runner-owned and view-only. Upstream has also
     * emitted both read-only spellings across row sources, so either explicit
     * true value preserves that safety for other imported sessions.
     */
    val isSessionReadOnly: Boolean
        get() = isDelegatedSubagentSession || readOnly == true || isReadOnly == true

    val shouldAppearInSessionList: Boolean
        get() = !isEmptySidebarPlaceholder

    /**
     * Mirrors hermes-webui's visible-sidebar safety net for just-created
     * placeholders: hide only the known empty Untitled shape, while keeping
     * rows with content, pending work, streaming state, or explicit
     * user/server state. Sort timestamps such as [lastMessageAt] are
     * intentionally ignored here — `compact()` sets them from `updated_at`
     * even for zero-message sessions.
     */
    val isEmptySidebarPlaceholder: Boolean
        get() {
            if (!hasPlaceholderTitle) return false
            if (hasSidebarState) return false
            if (hasMessageActivity) return false

            return (messageCount ?: 0) == 0 && (userMessageCount ?: 0) == 0
        }

    /**
     * True when this row originates from a scheduled cron job.
     *
     * Mirrors hermes-webui's `is_cron_session` (`api/models.py`): a `cron`
     * source marker (`session_source` / `source_tag` / `source_label`) or a
     * `cron_`-prefixed session id. Tolerant — a row with no cron markers is
     * treated as a normal session, so unknown/missing fields never hide it.
     */
    val isCronSession: Boolean
        get() {
            val normalizedId = sessionId?.trim()?.lowercase()
            if (normalizedId != null && normalizedId.startsWith("cron_")) return true

            return listOf(sessionSource, sourceTag, rawSource, sourceLabel)
                .mapNotNull(::normalizedSourceMarker)
                .contains("cron")
        }

    private val hasPlaceholderTitle: Boolean
        get() {
            val normalizedTitle = nonEmpty(title)?.lowercase() ?: return true
            return normalizedTitle == "untitled" || normalizedTitle == "untitled session"
        }

    private val hasSidebarState: Boolean
        get() = pinned == true ||
            isStreaming == true ||
            nonEmpty(activeStreamId) != null ||
            hasPendingUserMessage == true ||
            pendingStartedAt != null ||
            nonEmpty(worktreePath) != null

    private val hasMessageActivity: Boolean
        get() {
            if ((messageCount ?: 0) > 0) return true
            if ((userMessageCount ?: 0) > 0) return true
            return false
        }

    companion object {
        internal fun nonEmpty(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        private fun normalizedSourceMarker(value: String?): String? =
            nonEmpty(value)?.lowercase()
    }
}

/**
 * Port of `AutomatedSessionVisibility` from `Session.swift`: which
 * non-standard session kinds the session list should show. Cron jobs, CLI
 * imports, Claude Code imports, and delegated subagents are controlled
 * independently. A row with unknown/missing source data remains visible as a
 * normal session.
 */
data class AutomatedSessionVisibility(
    var showsCron: Boolean,
    var showsCli: Boolean,
    var showsClaudeCode: Boolean = true,
    var showsSubagents: Boolean = false,
) {
    /**
     * Whether `session` should remain visible under these toggles.
     *
     * `isCliSession` is server-computed (`is_cli_session_row`, re-stamped onto
     * every row by `_normalize_sidebar_source_flags` in `api/routes.py`); cron
     * detection is client-side ([SessionSummary.isCronSession]).
     */
    fun shows(session: SessionSummary): Boolean {
        if (session.isDelegatedSubagentSession && !showsSubagents) return false
        if (session.isCronSession && !showsCron) return false
        if (session.isCliSession == true && !showsCli) return false
        if (session.isClaudeCodeSession && !showsClaudeCode) return false
        return true
    }

    companion object {
        /** Show every kind, primarily for explicit opt-in and tests. */
        val showAll = AutomatedSessionVisibility(
            showsCron = true,
            showsCli = true,
            showsClaudeCode = true,
            showsSubagents = true,
        )
    }
}

/** Port of `SessionDetail` from `Session.swift` (fully lossy custom decoding). */
@Serializable(with = SessionDetailSerializer::class)
data class SessionDetail(
    val sessionId: String? = null,
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
    val pendingUserMessage: String? = null,
    val pendingAttachments: List<JSONValue>? = null,
    val pendingStartedAt: Double? = null,
    val worktreePath: String? = null,
    val contextLength: Int? = null,
    val thresholdTokens: Int? = null,
    val lastPromptTokens: Int? = null,
    val isCliSession: Boolean? = null,
    val sourceTag: String? = null,
    val rawSource: String? = null,
    val sessionSource: String? = null,
    val sourceLabel: String? = null,
    val parentSessionId: String? = null,
    val relationshipType: String? = null,
    val readOnly: Boolean? = null,
    val isReadOnly: Boolean? = null,
    val messages: List<ChatMessage>? = null,
    val toolCalls: List<PersistedToolCall>? = null,
    val messagesTruncated: Boolean? = null,
    val messagesOffset: Int? = null,
    val compressionAnchorVisibleIdx: Int? = null,
    val compressionAnchorMessageKey: CompressionAnchorMessageKey? = null,
    val compressionAnchorSummary: String? = null,
) {
    val id: String
        get() {
            if (!sessionId.isNullOrEmpty()) return sessionId

            val titlePart = title?.trim() ?: "untitled"
            val timestamp = createdAt ?: updatedAt ?: lastMessageAt ?: 0.0
            return "session-$titlePart-$timestamp"
        }
}

internal object SessionDetailSerializer : TolerantObjectSerializer<SessionDetail>("SessionDetail") {

    override fun fromJson(json: Json, obj: JsonObject): SessionDetail =
        SessionDetail(
            sessionId = obj.lossyString("session_id", "sessionId"),
            title = obj.lossyString("title"),
            workspace = obj.lossyString("workspace"),
            model = obj.lossyString("model"),
            modelProvider = obj.lossyString("model_provider", "modelProvider"),
            messageCount = obj.lossyInt("message_count", "messageCount"),
            createdAt = obj.lossyDouble("created_at", "createdAt"),
            updatedAt = obj.lossyDouble("updated_at", "updatedAt"),
            lastMessageAt = obj.lossyDouble("last_message_at", "lastMessageAt"),
            pinned = obj.lossyBool("pinned"),
            archived = obj.lossyBool("archived"),
            projectId = obj.lossyString("project_id", "projectId"),
            profile = obj.lossyString("profile"),
            inputTokens = obj.lossyInt("input_tokens", "inputTokens"),
            outputTokens = obj.lossyInt("output_tokens", "outputTokens"),
            estimatedCost = obj.lossyDouble("estimated_cost", "estimatedCost"),
            activeStreamId = obj.lossyString("active_stream_id", "activeStreamId"),
            pendingUserMessage = obj.lossyString("pending_user_message", "pendingUserMessage"),
            pendingAttachments = ChatMessage.decodeJsonValueList(
                obj.presentElement("pending_attachments", "pendingAttachments"),
            ),
            pendingStartedAt = obj.lossyDouble("pending_started_at", "pendingStartedAt"),
            worktreePath = obj.lossyString("worktree_path", "worktreePath"),
            contextLength = obj.lossyInt("context_length", "contextLength"),
            thresholdTokens = obj.lossyInt("threshold_tokens", "thresholdTokens"),
            lastPromptTokens = obj.lossyInt("last_prompt_tokens", "lastPromptTokens"),
            isCliSession = obj.lossyBool("is_cli_session", "isCliSession"),
            sourceTag = obj.lossyString("source_tag", "sourceTag"),
            rawSource = obj.lossyString("raw_source", "rawSource"),
            sessionSource = obj.lossyString("session_source", "sessionSource"),
            sourceLabel = obj.lossyString("source_label", "sourceLabel"),
            parentSessionId = obj.lossyString("parent_session_id", "parentSessionId"),
            relationshipType = obj.lossyString("relationship_type", "relationshipType"),
            readOnly = obj.lossyBool("read_only", "readOnly"),
            isReadOnly = obj.lossyBool("is_read_only", "isReadOnly"),
            messages = decodeListTolerantly(json, obj.presentElement("messages"), ChatMessage.serializer()),
            toolCalls = decodeListTolerantly(json, obj.presentElement("tool_calls", "toolCalls"), PersistedToolCall.serializer()),
            messagesTruncated = obj.lossyBool(
                "_messages_truncated",
                "_messagesTruncated",
                "messages_truncated",
                "messagesTruncated",
            ),
            messagesOffset = obj.lossyInt(
                "_messages_offset",
                "_messagesOffset",
                "messages_offset",
                "messagesOffset",
            ),
            compressionAnchorVisibleIdx = obj.lossyInt(
                "compression_anchor_visible_idx",
                "compressionAnchorVisibleIdx",
            ),
            compressionAnchorMessageKey = obj.presentElement(
                "compression_anchor_message_key",
                "compressionAnchorMessageKey",
            )?.let { element ->
                runCatching {
                    json.decodeFromJsonElement(CompressionAnchorMessageKey.serializer(), element)
                }.getOrNull()
            },
            compressionAnchorSummary = obj.lossyString(
                "compression_anchor_summary",
                "compressionAnchorSummary",
            ),
        )

    /**
     * Port of `decodeMessagesTolerantly` / `decodeToolCallsTolerantly`: direct
     * array decode first; on failure decode element by element so one
     * malformed entry does not throw away the whole array.
     */
    private fun <T> decodeListTolerantly(
        json: Json,
        element: JsonElement?,
        itemSerializer: kotlinx.serialization.KSerializer<T>,
    ): List<T>? {
        if (element == null) return null

        runCatching {
            return json.decodeFromJsonElement(ListSerializer(itemSerializer), element)
        }

        val values = element as? JsonArray ?: return null
        return values.mapNotNull { value ->
            runCatching { json.decodeFromJsonElement(itemSerializer, value) }.getOrNull()
        }
    }

    override fun toJson(json: Json, value: SessionDetail): JsonObject = buildJsonObject {
        value.sessionId?.let { put("session_id", JsonPrimitive(it)) }
        value.title?.let { put("title", JsonPrimitive(it)) }
        value.workspace?.let { put("workspace", JsonPrimitive(it)) }
        value.model?.let { put("model", JsonPrimitive(it)) }
        value.modelProvider?.let { put("model_provider", JsonPrimitive(it)) }
        value.messageCount?.let { put("message_count", JsonPrimitive(it)) }
        value.createdAt?.let { put("created_at", JsonPrimitive(it)) }
        value.updatedAt?.let { put("updated_at", JsonPrimitive(it)) }
        value.lastMessageAt?.let { put("last_message_at", JsonPrimitive(it)) }
        value.pinned?.let { put("pinned", JsonPrimitive(it)) }
        value.archived?.let { put("archived", JsonPrimitive(it)) }
        value.projectId?.let { put("project_id", JsonPrimitive(it)) }
        value.profile?.let { put("profile", JsonPrimitive(it)) }
        value.inputTokens?.let { put("input_tokens", JsonPrimitive(it)) }
        value.outputTokens?.let { put("output_tokens", JsonPrimitive(it)) }
        value.estimatedCost?.let { put("estimated_cost", JsonPrimitive(it)) }
        value.activeStreamId?.let { put("active_stream_id", JsonPrimitive(it)) }
        value.pendingUserMessage?.let { put("pending_user_message", JsonPrimitive(it)) }
        value.pendingAttachments?.let { attachments ->
            put("pending_attachments", JsonArray(attachments.map { it.element }))
        }
        value.pendingStartedAt?.let { put("pending_started_at", JsonPrimitive(it)) }
        value.worktreePath?.let { put("worktree_path", JsonPrimitive(it)) }
        value.contextLength?.let { put("context_length", JsonPrimitive(it)) }
        value.thresholdTokens?.let { put("threshold_tokens", JsonPrimitive(it)) }
        value.lastPromptTokens?.let { put("last_prompt_tokens", JsonPrimitive(it)) }
        value.isCliSession?.let { put("is_cli_session", JsonPrimitive(it)) }
        value.sourceTag?.let { put("source_tag", JsonPrimitive(it)) }
        value.rawSource?.let { put("raw_source", JsonPrimitive(it)) }
        value.sessionSource?.let { put("session_source", JsonPrimitive(it)) }
        value.sourceLabel?.let { put("source_label", JsonPrimitive(it)) }
        value.parentSessionId?.let { put("parent_session_id", JsonPrimitive(it)) }
        value.relationshipType?.let { put("relationship_type", JsonPrimitive(it)) }
        value.readOnly?.let { put("read_only", JsonPrimitive(it)) }
        value.isReadOnly?.let { put("is_read_only", JsonPrimitive(it)) }
        value.messages?.let {
            put("messages", json.encodeToJsonElement(ListSerializer(ChatMessage.serializer()), it))
        }
        value.toolCalls?.let {
            put("tool_calls", json.encodeToJsonElement(ListSerializer(PersistedToolCall.serializer()), it))
        }
        value.messagesTruncated?.let { put("_messages_truncated", JsonPrimitive(it)) }
        value.messagesOffset?.let { put("_messages_offset", JsonPrimitive(it)) }
        value.compressionAnchorVisibleIdx?.let { put("compression_anchor_visible_idx", JsonPrimitive(it)) }
        value.compressionAnchorMessageKey?.let {
            put(
                "compression_anchor_message_key",
                json.encodeToJsonElement(CompressionAnchorMessageKey.serializer(), it),
            )
        }
        value.compressionAnchorSummary?.let { put("compression_anchor_summary", JsonPrimitive(it)) }
    }
}

/**
 * Port of `CompressionAnchorMessageKey` from `Session.swift`: anchor key the
 * server builds in `_anchor_message_key` (`api/routes.py`) — role, optional
 * timestamp, first 160 chars of whitespace-normalized text, and attachment
 * count of the last visible message after compaction.
 */
@Serializable(with = CompressionAnchorMessageKeySerializer::class)
data class CompressionAnchorMessageKey(
    val role: String? = null,
    val ts: Double? = null,
    val text: String? = null,
    val attachments: Int? = null,
)

internal object CompressionAnchorMessageKeySerializer :
    TolerantObjectSerializer<CompressionAnchorMessageKey>("CompressionAnchorMessageKey") {

    override fun fromJson(json: Json, obj: JsonObject): CompressionAnchorMessageKey =
        CompressionAnchorMessageKey(
            role = obj.lossyString("role"),
            ts = obj.lossyDouble("ts"),
            text = obj.lossyString("text"),
            attachments = obj.lossyInt("attachments"),
        )

    override fun toJson(json: Json, value: CompressionAnchorMessageKey): JsonObject = buildJsonObject {
        value.role?.let { put("role", JsonPrimitive(it)) }
        value.ts?.let { put("ts", JsonPrimitive(it)) }
        value.text?.let { put("text", JsonPrimitive(it)) }
        value.attachments?.let { put("attachments", JsonPrimitive(it)) }
    }
}
