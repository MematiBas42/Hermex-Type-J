package io.github.jcartu.hermex.model

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * `GET /api/crons/recent`: one entry per job that has ever run, built from
 * each job's `last_run_at` and `last_status`. Upstream emits them in job
 * order, not by time, so the Tasks list sorts `completions` itself.
 * Port of `Models/CronRecentCompletion.swift`.
 */
@Serializable(with = CronRecentCompletionsResponseSerializer::class)
data class CronRecentCompletionsResponse(
    val completions: List<CronRecentCompletion>? = null,
)

internal object CronRecentCompletionsResponseSerializer :
    TolerantObjectSerializer<CronRecentCompletionsResponse>("CronRecentCompletionsResponse") {

    override fun fromJson(json: Json, obj: JsonObject): CronRecentCompletionsResponse {
        // Lossy array: a single malformed completion is skipped instead of
        // failing the whole feed (Swift `LossyCronRecentCompletion`).
        val completions = obj.presentElement("completions")?.let { element ->
            (element as? JsonArray)?.mapNotNull { runElement ->
                runCatching {
                    json.decodeFromJsonElement(CronRecentCompletion.serializer(), runElement)
                }.getOrNull()
            }.orEmpty()
        }
        return CronRecentCompletionsResponse(completions = completions)
    }

    override fun toJson(json: Json, value: CronRecentCompletionsResponse): JsonObject = buildJsonObject {
        value.completions?.let {
            put(
                "completions",
                buildJsonArray {
                    it.forEach { completion ->
                        add(json.encodeToJsonElement(CronRecentCompletion.serializer(), completion))
                    }
                },
            )
        }
    }
}

/**
 * One job's latest finished run, as the recent-runs group shows it.
 * Port of `Models/CronRecentCompletion.swift`.
 */
@Serializable(with = CronRecentCompletionSerializer::class)
data class CronRecentCompletion(
    val jobId: String? = null,
    val name: String? = null,
    val status: String? = null,
    val completedAt: Instant,
    val sessionId: String? = null,
    val messageCount: Int? = null,
) {
    /**
     * One completion per job on the wire, so the job is the identity; a
     * nameless, idless entry falls back to its timestamp.
     */
    val id: String
        get() = jobId ?: name ?: completedAt.epochSecond.toString()

    val didFail: Boolean
        get() = status == "error"

    val displayName: String
        get() = name?.takeIf { it.isNotEmpty() } ?: "Untitled Task"

    companion object {
        /** Newest first, which is the only order the group is shown in. */
        fun newestFirst(completions: List<CronRecentCompletion>): List<CronRecentCompletion> =
            completions.sortedByDescending { it.completedAt }
    }
}

internal object CronRecentCompletionSerializer :
    TolerantObjectSerializer<CronRecentCompletion>("CronRecentCompletion") {

    override fun fromJson(json: Json, obj: JsonObject): CronRecentCompletion {
        // Without a time the row has nothing to sort or show, so it is dropped
        // by the lossy response decoder rather than rendered (Swift throws here).
        val completedAt = obj.presentElement("completed_at", "completedAt")?.let { element ->
            runCatching { json.decodeFromJsonElement(CronDateValue.serializer(), element) }
                .getOrNull()
                ?.date
        } ?: throw kotlinx.serialization.SerializationException(
            "Recent completion has no usable completed_at",
        )

        return CronRecentCompletion(
            completedAt = completedAt,
            jobId = obj.lossyString("job_id", "jobId"),
            name = obj.lossyString("name"),
            status = obj.lossyString("status"),
            sessionId = obj.lossyString("session_id", "sessionId"),
            messageCount = obj.lossyInt("message_count", "messageCount"),
        )
    }

    override fun toJson(json: Json, value: CronRecentCompletion): JsonObject = buildJsonObject {
        value.jobId?.let { put("job_id", JsonPrimitive(it)) }
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.status?.let { put("status", JsonPrimitive(it)) }
        put("completed_at", json.encodeToJsonElement(CronDateValue.serializer(), CronDateValue(value.completedAt)))
        value.sessionId?.let { put("session_id", JsonPrimitive(it)) }
        value.messageCount?.let { put("message_count", JsonPrimitive(it)) }
    }
}
