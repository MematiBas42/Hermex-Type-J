package io.github.jcartu.hermex.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

// MARK: - Requests

data class KanbanBoardRequest(
    val board: String,
    val tenant: String? = null,
    val assignee: String? = null,
    val includeArchived: Boolean = false,
    val onlyMine: Boolean = false,
    val since: Int? = null,
)

data class KanbanCreateBoardRequest(
    val slug: String,
    val name: String,
    val description: String,
    val icon: String,
    val color: String,
)

data class KanbanEditBoardRequest(
    val slug: String,
    val name: String,
    val description: String,
    val icon: String,
    val color: String,
)

data class KanbanCreateCardRequest(
    val board: String,
    val title: String,
    val body: String? = null,
    val status: String,
    val priority: Int? = null,
    val assignee: String? = null,
    val tenant: String? = null,
    val workspaceKind: String = "workspace",
    val workspacePath: String? = null,
    val skills: List<String>? = null,
    val maxRuntimeSeconds: Int? = null,
    val prerequisiteId: String? = null,
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

data class KanbanEditCardRequest(
    val cardId: String,
    val board: String,
    val title: String,
    val body: String,
    val tenant: String? = null,
    val priority: Int,
    val assignee: String? = null,
    val status: String? = null,
)

data class KanbanCardStatusRequest(val cardId: String, val board: String, val status: String)
data class KanbanCardActionRequest(val cardId: String, val board: String, val reason: String? = null)
data class KanbanDependencyMutationRequest(val board: String, val prerequisiteId: String, val dependentId: String)
data class KanbanCardDetailRequest(val cardId: String, val board: String)
data class KanbanWorkerLogRequest(val cardId: String, val board: String, val tailBytes: Int = 65_536)
data class KanbanAddCommentRequest(val cardId: String, val board: String, val body: String)
data class KanbanEventsRequest(val board: String, val since: Int, val limit: Int = 200)
data class KanbanDispatchRequest(val board: String, val dryRun: Boolean, val maximum: Int = 8)

enum class KanbanBulkActionType { CHANGE_STATUS, ASSIGN_PROFILE, SET_PRIORITY, ARCHIVE }

data class KanbanBulkActionRequest(
    val board: String,
    val cardIds: List<String>,
    val action: KanbanBulkActionType,
    val value: String? = null,
)

// MARK: - Status + board shell

@Serializable(with = KanbanStatusSerializer::class)
data class KanbanStatus(val rawValue: String) {
    val normalized: String get() = rawValue.trim().lowercase()
    val isSupported: Boolean get() = normalized in SUPPORTED

    companion object {
        val SUPPORTED = setOf("triage", "todo", "blocked", "ready", "running", "done", "archived")
    }
}

object KanbanStatusSerializer : KSerializer<KanbanStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("KanbanStatus", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): KanbanStatus = KanbanStatus(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: KanbanStatus) = encoder.encodeString(value.rawValue)
}

enum class KanbanStaleness { NONE, WARNING, CRITICAL }

@Serializable(with = KanbanConfigurationSerializer::class)
data class KanbanConfiguration(
    val columns: List<String>? = null,
    val assignees: List<String>? = null,
    val defaultTenant: String? = null,
    val laneByProfile: Boolean? = null,
    val includeArchivedByDefault: Boolean? = null,
    val renderMarkdown: Boolean? = null,
    val readOnly: Boolean? = null,
)

internal object KanbanConfigurationSerializer : TolerantObjectSerializer<KanbanConfiguration>("KanbanConfiguration") {
    override fun fromJson(json: Json, obj: JsonObject): KanbanConfiguration {
        val columns = json.lossyStringList(obj["columns"])
        val assignees = (obj["assignees"] as? JsonArray)?.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> lossyString(item)
                is JsonObject -> item.lossyString("name")
                else -> null
            }
        }
        return KanbanConfiguration(
            columns = columns,
            assignees = assignees,
            defaultTenant = obj.lossyString("defaultTenant", "default_tenant"),
            laneByProfile = obj.lossyBool("laneByProfile", "lane_by_profile"),
            includeArchivedByDefault = obj.lossyBool("includeArchivedByDefault", "include_archived_by_default"),
            renderMarkdown = obj.lossyBool("renderMarkdown", "render_markdown"),
            readOnly = obj.lossyBool("readOnly", "read_only"),
        )
    }

    override fun toJson(json: Json, value: KanbanConfiguration): JsonObject = buildJsonObject {
        value.columns?.let { put("columns", json.encodeToJsonElement(it)) }
        value.assignees?.let { put("assignees", json.encodeToJsonElement(it)) }
        value.defaultTenant?.let { put("defaultTenant", JsonPrimitive(it)) }
        value.laneByProfile?.let { put("laneByProfile", JsonPrimitive(it)) }
        value.includeArchivedByDefault?.let { put("includeArchivedByDefault", JsonPrimitive(it)) }
        value.renderMarkdown?.let { put("renderMarkdown", JsonPrimitive(it)) }
        value.readOnly?.let { put("readOnly", JsonPrimitive(it)) }
    }
}

@Serializable(with = KanbanBoardSerializer::class)
data class KanbanBoard(
    val slug: String? = null,
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val isCurrent: Boolean? = null,
    val total: Int? = null,
    val counts: Map<String, Int>? = null,
    val readOnly: Boolean? = null,
) {
    val stableId: String get() = slug?.trim().takeUnless { it.isNullOrEmpty() } ?: name.orEmpty()
}

internal object KanbanBoardSerializer : TolerantObjectSerializer<KanbanBoard>("KanbanBoard") {
    override fun fromJson(json: Json, obj: JsonObject): KanbanBoard = KanbanBoard(
        slug = obj.lossyString("slug"), name = obj.lossyString("name"),
        description = obj.lossyString("description"), icon = obj.lossyString("icon"),
        color = obj.lossyString("color"), isCurrent = obj.lossyBool("isCurrent", "is_current"),
        total = obj.lossyInt("total"), counts = json.lossyIntMap(obj["counts"]),
        readOnly = obj.lossyBool("readOnly", "read_only"),
    )

    override fun toJson(json: Json, value: KanbanBoard): JsonObject = buildJsonObject {
        value.slug?.let { put("slug", JsonPrimitive(it)) }; value.name?.let { put("name", JsonPrimitive(it)) }
        value.description?.let { put("description", JsonPrimitive(it)) }; value.icon?.let { put("icon", JsonPrimitive(it)) }
        value.color?.let { put("color", JsonPrimitive(it)) }; value.isCurrent?.let { put("isCurrent", JsonPrimitive(it)) }
        value.total?.let { put("total", JsonPrimitive(it)) }; value.counts?.let { put("counts", json.encodeToJsonElement(it)) }
        value.readOnly?.let { put("readOnly", JsonPrimitive(it)) }
    }
}

@Serializable
data class KanbanBoardsResponse(
    val boards: List<KanbanBoard>? = null,
    val current: String? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

@Serializable
data class KanbanBoardMutationEnvelope(
    val board: KanbanBoard? = null,
    val current: String? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

// MARK: - Cards

@Serializable
data class KanbanLinkCounts(val parents: Int? = null, val children: Int? = null)

@Serializable(with = KanbanCardSerializer::class)
data class KanbanCard(
    val cardId: String? = null,
    val title: String? = null,
    val status: KanbanStatus? = null,
    val assignee: String? = null,
    val body: String? = null,
    val tenant: String? = null,
    val priority: Int? = null,
    val commentCount: Int? = null,
    val linkCounts: KanbanLinkCounts? = null,
    val ageSeconds: Double? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val workspaceKind: String? = null,
    val workspacePath: String? = null,
    val skills: List<String>? = null,
    val maxRuntimeSeconds: Int? = null,
    val currentRunId: String? = null,
    val claimLock: String? = null,
    val claimExpires: String? = null,
    val workerId: String? = null,
) {
    val stableId: String get() = cardId?.trim().takeUnless { it.isNullOrEmpty() } ?: "card-${title.orEmpty()}-${createdAt.orEmpty()}"
    val staleness: KanbanStaleness
        get() = when (status?.normalized) {
            "running" -> when { (ageSeconds ?: 0.0) >= 3_600 -> KanbanStaleness.CRITICAL; (ageSeconds ?: 0.0) >= 600 -> KanbanStaleness.WARNING; else -> KanbanStaleness.NONE }
            "ready" -> if ((ageSeconds ?: 0.0) >= 3_600) KanbanStaleness.WARNING else KanbanStaleness.NONE
            "blocked" -> when { (ageSeconds ?: 0.0) >= 86_400 -> KanbanStaleness.CRITICAL; (ageSeconds ?: 0.0) >= 3_600 -> KanbanStaleness.WARNING; else -> KanbanStaleness.NONE }
            else -> KanbanStaleness.NONE
        }

    fun replacingStatus(next: String): KanbanCard = copy(
        status = KanbanStatus(next),
        currentRunId = currentRunId.takeIf { next == "running" },
        claimLock = claimLock.takeIf { next == "running" },
        claimExpires = claimExpires.takeIf { next == "running" },
        workerId = workerId.takeIf { next == "running" },
    )
}

internal object KanbanCardSerializer : TolerantObjectSerializer<KanbanCard>("KanbanCard") {
    override fun fromJson(json: Json, obj: JsonObject): KanbanCard = KanbanCard(
        cardId = obj.lossyString("id", "cardId", "card_id"), title = obj.lossyString("title"),
        status = obj.lossyString("status")?.let(::KanbanStatus), assignee = obj.lossyString("assignee"),
        body = obj.lossyString("body"), tenant = obj.lossyString("tenant"), priority = obj.lossyInt("priority"),
        commentCount = obj.lossyInt("commentCount", "comment_count"),
        linkCounts = json.decodeOrNull(obj["linkCounts"] ?: obj["link_counts"]),
        ageSeconds = obj.lossyDouble("ageSeconds", "age_seconds"), createdAt = obj.lossyString("createdAt", "created_at"),
        updatedAt = obj.lossyString("updatedAt", "updated_at"), workspaceKind = obj.lossyString("workspaceKind", "workspace_kind"),
        workspacePath = obj.lossyString("workspacePath", "workspace_path"), skills = json.lossyStringList(obj["skills"]),
        maxRuntimeSeconds = obj.lossyInt("maxRuntimeSeconds", "max_runtime_seconds"),
        currentRunId = obj.lossyString("currentRunId", "current_run_id"), claimLock = obj.lossyString("claimLock", "claim_lock"),
        claimExpires = obj.lossyString("claimExpires", "claim_expires"), workerId = obj.lossyString("workerPid", "worker_id", "workerId"),
    )

    override fun toJson(json: Json, value: KanbanCard): JsonObject = buildJsonObject {
        value.cardId?.let { put("id", JsonPrimitive(it)) }; value.title?.let { put("title", JsonPrimitive(it)) }
        value.status?.let { put("status", JsonPrimitive(it.rawValue)) }; value.assignee?.let { put("assignee", JsonPrimitive(it)) }
        value.body?.let { put("body", JsonPrimitive(it)) }; value.tenant?.let { put("tenant", JsonPrimitive(it)) }
        value.priority?.let { put("priority", JsonPrimitive(it)) }; value.commentCount?.let { put("commentCount", JsonPrimitive(it)) }
        value.linkCounts?.let { put("linkCounts", json.encodeToJsonElement(it)) }; value.ageSeconds?.let { put("ageSeconds", JsonPrimitive(it)) }
        value.createdAt?.let { put("createdAt", JsonPrimitive(it)) }; value.updatedAt?.let { put("updatedAt", JsonPrimitive(it)) }
        value.workspaceKind?.let { put("workspaceKind", JsonPrimitive(it)) }; value.workspacePath?.let { put("workspacePath", JsonPrimitive(it)) }
        value.skills?.let { put("skills", json.encodeToJsonElement(it)) }; value.maxRuntimeSeconds?.let { put("maxRuntimeSeconds", JsonPrimitive(it)) }
        value.currentRunId?.let { put("currentRunId", JsonPrimitive(it)) }; value.workerId?.let { put("workerPid", JsonPrimitive(it)) }
    }
}

@Serializable
data class KanbanColumn(val name: String? = null, @SerialName("tasks") val cards: List<KanbanCard>? = null)

@Serializable
data class KanbanAppliedFilters(
    val tenant: String? = null,
    val assignee: String? = null,
    @SerialName("include_archived") val includeArchived: Boolean? = null,
    @SerialName("only_mine") val onlyMine: Boolean? = null,
    val profile: String? = null,
)

@Serializable
data class KanbanBoardSnapshot(
    val columns: List<KanbanColumn>? = null,
    val tenants: List<String>? = null,
    val assignees: List<String>? = null,
    val filters: KanbanAppliedFilters? = null,
    val changed: Boolean? = null,
    @SerialName("latest_event_id") val latestEventId: Int? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

// MARK: - Detail

@Serializable
data class KanbanComment(
    @SerialName("id") val commentId: String? = null,
    @SerialName("task_id") val cardId: String? = null,
    val author: String? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val stableId: String get() = commentId ?: listOfNotNull(cardId, author, createdAt, body).joinToString("|")
}

@Serializable
data class KanbanDetailEventPayload(
    val status: String? = null,
    val reason: String? = null,
    val summary: String? = null,
    val fields: List<String>? = null,
)

@Serializable
data class KanbanDetailEvent(
    @SerialName("id") val eventId: String? = null,
    @SerialName("task_id") val cardId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val kind: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val payload: KanbanDetailEventPayload? = null,
) {
    val stableId: String get() = eventId ?: listOfNotNull(cardId, runId, kind, createdAt).joinToString("|")
}

@Serializable
data class KanbanDependencyLinks(
    @SerialName("parents") val prerequisites: List<String>? = null,
    @SerialName("children") val dependents: List<String>? = null,
)

@Serializable
data class KanbanDispatchRun(
    @SerialName("id") val runId: String? = null,
    val status: String? = null,
    val outcome: String? = null,
    val summary: String? = null,
    val error: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("worker_pid") val workerId: String? = null,
    @SerialName("log_tail") val logTail: String? = null,
) {
    val stableId: String get() = runId ?: listOfNotNull(status, outcome, startedAt, finishedAt).joinToString("|")
}

@Serializable
data class KanbanCardDetailEnvelope(
    @SerialName("task") val card: KanbanCard? = null,
    val comments: List<KanbanComment>? = null,
    val events: List<KanbanDetailEvent>? = null,
    val links: KanbanDependencyLinks? = null,
    val runs: List<KanbanDispatchRun>? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

@Serializable
data class KanbanCardMutationEnvelope(
    @SerialName("task") val card: KanbanCard? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

@Serializable
data class KanbanDependencyMutationEnvelope(
    val ok: Boolean? = null,
    val changed: Boolean? = null,
    @SerialName("parent_id") val prerequisiteId: String? = null,
    @SerialName("child_id") val dependentId: String? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

@Serializable
data class KanbanWorkerLog(
    @SerialName("task_id") val cardId: String? = null,
    val exists: Boolean? = null,
    @SerialName("size_bytes") val sizeBytes: Int? = null,
    val content: String? = null,
    val truncated: Boolean? = null,
)

@Serializable
data class KanbanAddCommentResponse(
    val ok: Boolean? = null,
    @SerialName("comment_id") val commentId: String? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

// MARK: - Operational responses

@Serializable
data class KanbanEvent(
    @SerialName("id") val eventId: Int? = null,
    @SerialName("task_id") val cardId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val kind: String? = null,
    @SerialName("created_at") val createdAt: Int? = null,
)

@Serializable
data class KanbanEventsEnvelope(
    val events: List<KanbanEvent>? = null,
    val cursor: Int? = null,
    @SerialName("latest_event_id") val latestEventId: Int? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

@Serializable
data class KanbanStats(
    val total: Int? = null,
    @SerialName("by_status") val byStatus: Map<String, Int>? = null,
    @SerialName("by_assignee") val byAssignee: Map<String, Int>? = null,
)

@Serializable
data class KanbanAssigneeHistory(val assignees: List<String>? = null)

@Serializable
data class KanbanDispatchResult(
    val spawned: JsonElement? = null,
    val promoted: JsonElement? = null,
    val reclaimed: JsonElement? = null,
    @SerialName("skipped_unassigned") val skippedUnassigned: JsonElement? = null,
    @SerialName("skipped_nonspawnable") val skippedNonspawnable: JsonElement? = null,
    @SerialName("auto_blocked") val autoBlocked: JsonElement? = null,
    @SerialName("timed_out") val timedOut: JsonElement? = null,
    val crashed: JsonElement? = null,
) {
    fun count(value: JsonElement?): Int? = when (value) {
        is JsonArray -> value.size
        is JsonPrimitive -> lossyInt(value)
        else -> null
    }
    val spawnedCount get() = count(spawned)
    val promotedCount get() = count(promoted)
    val reclaimedCount get() = count(reclaimed)
    val skippedCount get() = listOfNotNull(count(skippedUnassigned), count(skippedNonspawnable)).sum()
    val failureCount get() = listOfNotNull(count(autoBlocked), count(timedOut), count(crashed)).sum()
    val hasKnownCategory: Boolean
        get() = listOf(
            spawnedCount, promotedCount, reclaimedCount,
            count(skippedUnassigned), count(skippedNonspawnable),
            count(autoBlocked), count(timedOut), count(crashed),
        ).any { it != null }
}

@Serializable
data class KanbanBulkActionResult(@SerialName("id") val cardId: String? = null, val ok: Boolean? = null, val error: String? = null)

@Serializable
data class KanbanBulkActionEnvelope(
    val results: List<KanbanBulkActionResult>? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
)

object KanbanValidators {
    fun requireCard(envelope: KanbanCardMutationEnvelope, expectedId: String? = null): KanbanCard {
        val card = envelope.card ?: throw KanbanContractException("Server response omitted the card")
        val id = card.cardId?.trim().orEmpty()
        if (id.isEmpty() || card.status?.rawValue?.trim().isNullOrEmpty() || (expectedId != null && id != expectedId.trim())) {
            throw KanbanContractException("Server response omitted card identity or status")
        }
        return card
    }

    fun requireDetail(envelope: KanbanCardDetailEnvelope, requestedId: String) {
        requireCard(KanbanCardMutationEnvelope(envelope.card, envelope.readOnly), requestedId)
    }

    fun requireDependency(envelope: KanbanDependencyMutationEnvelope, request: KanbanDependencyMutationRequest) {
        if (envelope.ok != true || envelope.prerequisiteId?.trim() != request.prerequisiteId.trim() || envelope.dependentId?.trim() != request.dependentId.trim()) {
            throw KanbanContractException("Server response omitted dependency identity")
        }
    }
}

class KanbanContractException(message: String) : Exception(message)

private inline fun <reified T> Json.decodeOrNull(element: JsonElement?): T? =
    element?.let { runCatching { decodeFromJsonElement<T>(it) }.getOrNull() }

private fun Json.lossyStringList(element: JsonElement?): List<String>? =
    (element as? JsonArray)?.mapNotNull(::lossyString)

private fun Json.lossyIntMap(element: JsonElement?): Map<String, Int>? =
    (element as? JsonObject)?.mapNotNull { (key, value) -> lossyInt(value)?.let { key to it } }?.toMap()
