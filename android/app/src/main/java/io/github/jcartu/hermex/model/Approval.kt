package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Port of `ApprovalChoice` from `Approval.swift`. */
@Serializable
enum class ApprovalChoice {
    @SerialName("once")
    ONCE,

    @SerialName("session")
    SESSION,

    @SerialName("always")
    ALWAYS,

    @SerialName("deny")
    DENY;

    val rawValue: String
        get() = when (this) {
            ONCE -> "once"
            SESSION -> "session"
            ALWAYS -> "always"
            DENY -> "deny"
        }

    companion object {
        fun fromRawValue(value: String): ApprovalChoice? =
            entries.firstOrNull { it.rawValue == value }
    }
}

/** Port of `ApprovalPendingResponse` from `Approval.swift`. */
@Serializable(with = ApprovalPendingResponseSerializer::class)
data class ApprovalPendingResponse(
    val pending: PendingApproval? = null,
    val pendingCount: Int? = null,
) {
    companion object {
        /**
         * Port of `streamPayload(from:decoder:)`: tolerant parse of a raw SSE
         * data payload — wrapped `{pending: …}` shape first, then a bare
         * `PendingApproval` object. Never throws.
         */
        fun streamPayload(json: Json, data: String): ApprovalPendingResponse {
            val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
                ?: return ApprovalPendingResponse(pending = null, pendingCount = null)

            runCatching { json.decodeFromJsonElement(serializer(), element) }
                .getOrNull()
                ?.takeIf { it.pending != null || it.pendingCount != null }
                ?.let { return it }

            runCatching { json.decodeFromJsonElement(PendingApproval.serializer(), element) }
                .getOrNull()
                ?.takeIf { !it.isEmpty }
                ?.let { return ApprovalPendingResponse(pending = it, pendingCount = 1) }

            return ApprovalPendingResponse(pending = null, pendingCount = null)
        }
    }
}

internal object ApprovalPendingResponseSerializer :
    TolerantObjectSerializer<ApprovalPendingResponse>("ApprovalPendingResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ApprovalPendingResponse =
        ApprovalPendingResponse(
            pending = obj.presentElement("pending")?.let { element ->
                runCatching { json.decodeFromJsonElement(PendingApproval.serializer(), element) }.getOrNull()
            },
            pendingCount = obj.lossyInt("pendingCount", "pending_count"),
        )

    override fun toJson(json: Json, value: ApprovalPendingResponse): JsonObject = buildJsonObject {
        value.pending?.let { put("pending", json.encodeToJsonElement(PendingApproval.serializer(), it)) }
        value.pendingCount?.let { put("pending_count", JsonPrimitive(it)) }
    }
}

/** Port of `PendingApproval` from `Approval.swift`. */
@Serializable(with = PendingApprovalSerializer::class)
data class PendingApproval(
    val approvalId: String? = null,
    val command: String? = null,
    val description: String? = null,
    val patternKey: String? = null,
    val patternKeys: List<String>? = null,
) {
    val id: String
        get() {
            if (!approvalId.isNullOrEmpty()) return approvalId
            return "${command ?: ""}-${description ?: ""}-${displayPatternKeys.joinToString(",")}"
        }

    val displayPatternKeys: List<String>
        get() {
            val keys = patternKeys?.filter { it.trim().isNotEmpty() } ?: emptyList()
            if (keys.isNotEmpty()) return keys

            val single = patternKey?.trim()
            if (single.isNullOrEmpty()) return emptyList()

            return listOf(single)
        }

    val isEmpty: Boolean
        get() = approvalId == null &&
            command == null &&
            description == null &&
            patternKey == null &&
            patternKeys.isNullOrEmpty()

    companion object {
        /**
         * Mirrors the Swift memberwise initializer, which normalizes
         * `approvalId` (trims; empty becomes null).
         */
        fun of(
            approvalId: String? = null,
            command: String? = null,
            description: String? = null,
            patternKey: String? = null,
            patternKeys: List<String>? = null,
        ): PendingApproval = PendingApproval(
            approvalId = normalizedApprovalId(approvalId),
            command = command,
            description = description,
            patternKey = patternKey,
            patternKeys = patternKeys,
        )

        internal fun normalizedApprovalId(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }
    }
}

internal object PendingApprovalSerializer : TolerantObjectSerializer<PendingApproval>("PendingApproval") {

    override fun fromJson(json: Json, obj: JsonObject): PendingApproval =
        PendingApproval(
            approvalId = decodeApprovalId(obj),
            command = obj.lossyString("command"),
            description = obj.lossyString("description"),
            patternKey = obj.lossyString("patternKey", "pattern_key"),
            patternKeys = decodeLossyStringArray(obj, listOf("patternKeys", "pattern_keys")),
        )

    override fun toJson(json: Json, value: PendingApproval): JsonObject = buildJsonObject {
        value.approvalId?.let { put("approval_id", JsonPrimitive(it)) }
        value.command?.let { put("command", JsonPrimitive(it)) }
        value.description?.let { put("description", JsonPrimitive(it)) }
        value.patternKey?.let { put("pattern_key", JsonPrimitive(it)) }
        value.patternKeys?.let { keys -> put("pattern_keys", JsonArray(keys.map { JsonPrimitive(it) })) }
    }

    private fun decodeApprovalId(obj: JsonObject): String? {
        for (key in listOf("approvalId", "approval_id", "id")) {
            PendingApproval.normalizedApprovalId(lossyString(obj[key]))?.let { return it }
        }
        return null
    }
}

/**
 * Port of the tolerant string-array union both approval and clarification
 * shapes use: strict `[String]` first, then any array coerced element-wise
 * (Swift's private `lossyString` extension, numbers rendered as `"\(value)"`),
 * then a lone scalar wrapped into a single-element list.
 */
internal fun decodeLossyStringArray(obj: JsonObject, keys: List<String>): List<String>? {
    for (key in keys) {
        val element = obj[key] ?: continue
        if (element is JsonNull) continue

        if (element is JsonArray) {
            if (element.all { it is JsonPrimitive && it !is JsonNull && it.isString }) {
                return element.map { (it as JsonPrimitive).content }
            }
            return element.mapNotNull { interpolatedLossyString(JSONValue(it)) }
        }

        lossyString(element)?.let { return listOf(it) }
    }

    return null
}

/**
 * The private `JSONValue.lossyString` extensions in `Approval.swift` /
 * `Clarification.swift`: numbers use Swift string interpolation (`3.0`,
 * `3.5`), which matches Kotlin's `Double.toString`.
 */
internal fun interpolatedLossyString(value: JSONValue): String? {
    value.string?.let { return it }
    value.number?.let { return it.toString() }
    value.boolean?.let { return if (it) "true" else "false" }
    return null
}

/** Port of `ApprovalRespondResponse` from `Approval.swift`. */
@Serializable(with = ApprovalRespondResponseSerializer::class)
data class ApprovalRespondResponse(
    val ok: Boolean? = null,
    val choice: ApprovalChoice? = null,
    /** Server cleared a stale card whose approval already resolved (benign 200; issue #25). */
    val staleCleared: Boolean? = null,
    /** The respond was relayed to a gateway-managed run rather than resolved locally. */
    val relayed: Boolean? = null,
    /** The prompt already expired (paired with a 409 on the docs' respond contract). */
    val stale: Boolean? = null,
)

internal object ApprovalRespondResponseSerializer :
    TolerantObjectSerializer<ApprovalRespondResponse>("ApprovalRespondResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ApprovalRespondResponse =
        ApprovalRespondResponse(
            ok = obj.lossyBool("ok"),
            choice = lossyString(obj["choice"])?.let { ApprovalChoice.fromRawValue(it) },
            staleCleared = obj.lossyBool("staleCleared", "stale_cleared"),
            relayed = obj.lossyBool("relayed"),
            stale = obj.lossyBool("stale"),
        )

    override fun toJson(json: Json, value: ApprovalRespondResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.choice?.let { put("choice", JsonPrimitive(it.rawValue)) }
        value.staleCleared?.let { put("stale_cleared", JsonPrimitive(it)) }
        value.relayed?.let { put("relayed", JsonPrimitive(it)) }
        value.stale?.let { put("stale", JsonPrimitive(it)) }
    }
}

/** Port of `SessionYoloResponse` from `Approval.swift`. */
@Serializable(with = SessionYoloResponseSerializer::class)
data class SessionYoloResponse(
    val ok: Boolean? = null,
    val yoloEnabled: Boolean? = null,
)

internal object SessionYoloResponseSerializer :
    TolerantObjectSerializer<SessionYoloResponse>("SessionYoloResponse") {

    override fun fromJson(json: Json, obj: JsonObject): SessionYoloResponse =
        SessionYoloResponse(
            ok = obj.lossyBool("ok"),
            yoloEnabled = obj.lossyBool("yoloEnabled", "yolo_enabled"),
        )

    override fun toJson(json: Json, value: SessionYoloResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.yoloEnabled?.let { put("yolo_enabled", JsonPrimitive(it)) }
    }
}
