package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Port of `ClarificationPendingResponse` from `Clarification.swift`. */
@Serializable(with = ClarificationPendingResponseSerializer::class)
data class ClarificationPendingResponse(
    val pending: PendingClarification? = null,
    val pendingCount: Int? = null,
) {
    companion object {
        /**
         * Port of `streamPayload(from:decoder:)`: tolerant parse of a raw SSE
         * data payload — wrapped `{pending: …}` shape first, then a bare
         * `PendingClarification` object. Never throws.
         */
        fun streamPayload(json: Json, data: String): ClarificationPendingResponse {
            val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
                ?: return ClarificationPendingResponse(pending = null, pendingCount = null)

            runCatching { json.decodeFromJsonElement(serializer(), element) }
                .getOrNull()
                ?.takeIf { it.pending != null || it.pendingCount != null }
                ?.let { return it }

            runCatching { json.decodeFromJsonElement(PendingClarification.serializer(), element) }
                .getOrNull()
                ?.takeIf { !it.isEmpty }
                ?.let { return ClarificationPendingResponse(pending = it, pendingCount = 1) }

            return ClarificationPendingResponse(pending = null, pendingCount = null)
        }

        /**
         * Port of `containsClarificationMarkers(in:)`: cheap structural sniff
         * used to distinguish clarification payloads on the SSE stream.
         * Returns false for unparseable or non-object payloads.
         */
        fun containsClarificationMarkers(data: String): Boolean {
            val obj = runCatching { Json.parseToJsonElement(data) }.getOrNull() as? JsonObject
                ?: return false

            val candidate = (obj["pending"] as? JsonObject) ?: obj
            return candidate.containsKey("question") ||
                candidate.containsKey("choices_offered") ||
                candidate.containsKey("choicesOffered")
        }
    }
}

internal object ClarificationPendingResponseSerializer :
    TolerantObjectSerializer<ClarificationPendingResponse>("ClarificationPendingResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ClarificationPendingResponse =
        ClarificationPendingResponse(
            pending = obj.presentElement("pending")?.let { element ->
                runCatching { json.decodeFromJsonElement(PendingClarification.serializer(), element) }.getOrNull()
            },
            pendingCount = obj.lossyInt("pendingCount", "pending_count"),
        )

    override fun toJson(json: Json, value: ClarificationPendingResponse): JsonObject = buildJsonObject {
        value.pending?.let { put("pending", json.encodeToJsonElement(PendingClarification.serializer(), it)) }
        value.pendingCount?.let { put("pending_count", JsonPrimitive(it)) }
    }
}

/** Port of `PendingClarification` from `Clarification.swift`. */
@Serializable(with = PendingClarificationSerializer::class)
data class PendingClarification(
    val clarifyId: String? = null,
    val question: String? = null,
    val choicesOffered: List<String>? = null,
    val sessionId: String? = null,
    val kind: String? = null,
    val requestedAt: Double? = null,
    val timeoutSeconds: Int? = null,
    val expiresAt: Double? = null,
) {
    val id: String
        get() {
            if (!clarifyId.isNullOrEmpty()) return clarifyId
            return "${sessionId ?: ""}-${question ?: ""}-${requestedAt ?: 0.0}"
        }

    val displayChoices: List<String>
        get() = choicesOffered
            ?.mapNotNull { choice -> choice.trim().takeIf { it.isNotEmpty() } }
            ?: emptyList()

    val displayQuestion: String
        get() {
            val trimmed = question?.trim() ?: ""
            return trimmed.ifEmpty { "The agent needs more information before continuing." }
        }

    val isEmpty: Boolean
        get() = clarifyId == null &&
            question == null &&
            choicesOffered.isNullOrEmpty() &&
            sessionId == null &&
            kind == null &&
            requestedAt == null &&
            timeoutSeconds == null &&
            expiresAt == null
}

internal object PendingClarificationSerializer :
    TolerantObjectSerializer<PendingClarification>("PendingClarification") {

    override fun fromJson(json: Json, obj: JsonObject): PendingClarification =
        PendingClarification(
            clarifyId = obj.lossyString("clarifyId", "clarify_id"),
            question = obj.lossyString("question"),
            choicesOffered = decodeLossyStringArray(obj, listOf("choicesOffered", "choices_offered")),
            sessionId = obj.lossyString("sessionId", "session_id"),
            kind = obj.lossyString("kind"),
            requestedAt = obj.lossyDouble("requestedAt", "requested_at"),
            timeoutSeconds = obj.lossyInt("timeoutSeconds", "timeout_seconds"),
            expiresAt = obj.lossyDouble("expiresAt", "expires_at"),
        )

    override fun toJson(json: Json, value: PendingClarification): JsonObject = buildJsonObject {
        value.clarifyId?.let { put("clarify_id", JsonPrimitive(it)) }
        value.question?.let { put("question", JsonPrimitive(it)) }
        value.choicesOffered?.let { choices ->
            put("choices_offered", JsonArray(choices.map { JsonPrimitive(it) }))
        }
        value.sessionId?.let { put("session_id", JsonPrimitive(it)) }
        value.kind?.let { put("kind", JsonPrimitive(it)) }
        value.requestedAt?.let { put("requested_at", JsonPrimitive(it)) }
        value.timeoutSeconds?.let { put("timeout_seconds", JsonPrimitive(it)) }
        value.expiresAt?.let { put("expires_at", JsonPrimitive(it)) }
    }
}

/** Port of `ClarificationRespondResponse` from `Clarification.swift`. */
@Serializable(with = ClarificationRespondResponseSerializer::class)
data class ClarificationRespondResponse(
    val ok: Boolean? = null,
    val response: String? = null,
    /** The prompt already expired or was resolved (paired with a 409; issue #25). */
    val stale: Boolean? = null,
    /** Server cleared a stale card whose prompt already resolved (mirrors approvals). */
    val staleCleared: Boolean? = null,
    /** The respond was relayed to a gateway-managed run rather than resolved locally. */
    val relayed: Boolean? = null,
)

internal object ClarificationRespondResponseSerializer :
    TolerantObjectSerializer<ClarificationRespondResponse>("ClarificationRespondResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ClarificationRespondResponse =
        ClarificationRespondResponse(
            ok = obj.lossyBool("ok"),
            response = obj.lossyString("response"),
            stale = obj.lossyBool("stale"),
            staleCleared = obj.lossyBool("staleCleared", "stale_cleared"),
            relayed = obj.lossyBool("relayed"),
        )

    override fun toJson(json: Json, value: ClarificationRespondResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.response?.let { put("response", JsonPrimitive(it)) }
        value.stale?.let { put("stale", JsonPrimitive(it)) }
        value.staleCleared?.let { put("stale_cleared", JsonPrimitive(it)) }
        value.relayed?.let { put("relayed", JsonPrimitive(it)) }
    }
}
