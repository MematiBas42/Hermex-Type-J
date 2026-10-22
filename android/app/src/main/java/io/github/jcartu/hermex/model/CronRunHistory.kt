package io.github.jcartu.hermex.model

import java.time.Instant
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * One page of a task's run history, from `GET /api/crons/history`.
 *
 * `total` counts every run the server holds, not the rows in this page. The
 * two differ on purpose: upstream slices `all_files[offset:offset + limit]`
 * and then drops any entry whose `stat()` throws, so a page can return fewer
 * rows than it consumed. Callers page by the `limit` they asked for and stop
 * at `total`; advancing by `runs.count` silently re-reads rows it already has.
 * Port of `Models/CronRunHistory.swift`.
 */
@Serializable(with = CronRunHistoryResponseSerializer::class)
data class CronRunHistoryResponse(
    val jobId: String? = null,
    val runs: List<CronRunHistoryItem> = emptyList(),
    val total: Int? = null,
    val offset: Int? = null,
)

internal object CronRunHistoryResponseSerializer :
    TolerantObjectSerializer<CronRunHistoryResponse>("CronRunHistoryResponse") {

    override fun fromJson(json: Json, obj: JsonObject): CronRunHistoryResponse {
        // Lossy array: one malformed entry drops out of the page instead of
        // failing the whole request (Swift `LossyCronRun`).
        val runs = obj.presentElement("runs")?.let { element ->
            (element as? JsonArray)?.mapNotNull { runElement ->
                runCatching {
                    json.decodeFromJsonElement(CronRunHistoryItem.serializer(), runElement)
                }.getOrNull()
            }.orEmpty()
        }.orEmpty()

        return CronRunHistoryResponse(
            jobId = obj.lossyString("job_id", "jobId"),
            runs = runs,
            total = obj.lossyInt("total"),
            offset = obj.lossyInt("offset"),
        )
    }

    override fun toJson(json: Json, value: CronRunHistoryResponse): JsonObject = buildJsonObject {
        value.jobId?.let { put("job_id", JsonPrimitive(it)) }
        put(
            "runs",
            buildJsonArray {
                value.runs.forEach { add(json.encodeToJsonElement(CronRunHistoryItem.serializer(), it)) }
            },
        )
        value.total?.let { put("total", JsonPrimitive(it)) }
        value.offset?.let { put("offset", JsonPrimitive(it)) }
    }
}

/**
 * One run: enough to render a row, and the `filename` handle the run-detail
 * endpoint needs. A Hermes host's run (#1042) is its session, mapped onto the
 * same row in `HermesCronClient`. Port of `Models/CronRunHistory.swift`.
 */
@Serializable(with = CronRunHistoryItemSerializer::class)
data class CronRunHistoryItem(
    /**
     * Unique within a job's output directory, which is what makes it a safe
     * list identity as well as the detail request's key. On a Hermes host,
     * the run's session id (`cron_<job>_<time>`).
     */
    val filename: String,
    /** Bytes on disk. `null` when the server sent something unusable rather than a number. */
    val size: Int? = null,
    /** The file's mtime, which is when the run finished. */
    val modified: Instant? = null,
    /** When a Hermes host's run started; null on webui. */
    val startedAt: Instant? = null,
    /** A Hermes host's run still in progress. A webui run is listed only once it has finished. */
    val isRunning: Boolean = false,
    /** Parsed out of the run's markdown front matter, and frequently empty. */
    val usage: CronRunUsage = CronRunUsage(),
) {
    val id: String
        get() = filename

    /** When the row says the run happened: a Hermes run's start, else the webui file's finish. */
    val date: Instant?
        get() = startedAt ?: modified
}

internal object CronRunHistoryItemSerializer :
    TolerantObjectSerializer<CronRunHistoryItem>("CronRunHistoryItem") {

    override fun fromJson(json: Json, obj: JsonObject): CronRunHistoryItem {
        // A run with no filename cannot be opened, so `CronRunHistoryResponse`
        // drops it rather than rendering a dead row (Swift throws here).
        val filename = obj.lossyString("filename")
            ?: throw SerializationException("Cron run is missing a filename")
        if (filename.isEmpty()) throw SerializationException("Cron run is missing a filename")

        return CronRunHistoryItem(
            filename = filename,
            size = obj.lossyInt("size"),
            // `Date(timeIntervalSince1970:)` accepts NaN and infinity and then
            // traps deep inside date formatting, so a non-finite mtime becomes
            // no date (Swift flatMap guard).
            modified = obj.lossyDouble("modified")
                ?.takeIf { it.isFinite() }
                ?.let(::instantFromEpochSeconds),
            usage = decodeCronValue(json, obj, CronRunUsage.serializer(), "usage") ?: CronRunUsage(),
        )
    }

    override fun toJson(json: Json, value: CronRunHistoryItem): JsonObject = buildJsonObject {
        put("filename", JsonPrimitive(value.filename))
        value.size?.let { put("size", JsonPrimitive(it)) }
        value.modified?.let { put("modified", JsonPrimitive(epochSeconds(it))) }
        put("usage", json.encodeToJsonElement(CronRunUsage.serializer(), value.usage))
    }
}

/**
 * The optional token, cost and timing metadata upstream scrapes out of a run's
 * markdown front matter.
 *
 * Every field is a decoration. The server returns `{}` for most runs, so no
 * layout may reserve space for these — a row shows what is there and nothing
 * where there is not. Port of `Models/CronRunHistory.swift`.
 */
@Serializable(with = CronRunUsageSerializer::class)
data class CronRunUsage(
    val model: String? = null,
    val provider: String? = null,
    val estimatedCostUsd: Double? = null,
    val durationSeconds: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

internal object CronRunUsageSerializer : TolerantObjectSerializer<CronRunUsage>("CronRunUsage") {
    override fun fromJson(json: Json, obj: JsonObject): CronRunUsage =
        CronRunUsage(
            model = obj.lossyString("model"),
            provider = obj.lossyString("provider"),
            estimatedCostUsd = obj.lossyDouble("estimated_cost_usd", "estimatedCostUsd"),
            durationSeconds = obj.lossyDouble("duration_seconds", "durationSeconds"),
            inputTokens = obj.lossyInt("input_tokens", "inputTokens"),
            outputTokens = obj.lossyInt("output_tokens", "outputTokens"),
            totalTokens = obj.lossyInt("total_tokens", "totalTokens"),
        )

    override fun toJson(json: Json, value: CronRunUsage): JsonObject = buildJsonObject {
        value.model?.let { put("model", JsonPrimitive(it)) }
        value.provider?.let { put("provider", JsonPrimitive(it)) }
        value.estimatedCostUsd?.let { put("estimated_cost_usd", JsonPrimitive(it)) }
        value.durationSeconds?.let { put("duration_seconds", JsonPrimitive(it)) }
        value.inputTokens?.let { put("input_tokens", JsonPrimitive(it)) }
        value.outputTokens?.let { put("output_tokens", JsonPrimitive(it)) }
        value.totalTokens?.let { put("total_tokens", JsonPrimitive(it)) }
    }
}

/**
 * One run's full text, from `GET /api/crons/run` — the read that shares its
 * path with the POST that triggers a run. Port of `Models/CronRunHistory.swift`.
 */
@Serializable(with = CronRunDetailResponseSerializer::class)
data class CronRunDetailResponse(
    val jobId: String? = null,
    val filename: String? = null,
    val content: String? = null,
    val snippet: String? = null,
    val usage: CronRunUsage = CronRunUsage(),
)

internal object CronRunDetailResponseSerializer :
    TolerantObjectSerializer<CronRunDetailResponse>("CronRunDetailResponse") {

    override fun fromJson(json: Json, obj: JsonObject): CronRunDetailResponse =
        CronRunDetailResponse(
            jobId = obj.lossyString("job_id", "jobId"),
            filename = obj.lossyString("filename"),
            content = obj.presentElement("content")?.let { lossyString(it) },
            snippet = obj.presentElement("snippet")?.let { lossyString(it) },
            usage = decodeCronValue(json, obj, CronRunUsage.serializer(), "usage") ?: CronRunUsage(),
        )

    override fun toJson(json: Json, value: CronRunDetailResponse): JsonObject = buildJsonObject {
        value.jobId?.let { put("job_id", JsonPrimitive(it)) }
        value.filename?.let { put("filename", JsonPrimitive(it)) }
        value.content?.let { put("content", JsonPrimitive(it)) }
        value.snippet?.let { put("snippet", JsonPrimitive(it)) }
        put("usage", json.encodeToJsonElement(CronRunUsage.serializer(), value.usage))
    }
}

private val cronRunStringListSerializer = ListSerializer(String.serializer())

private fun <T> decodeCronValue(
    json: Json,
    obj: JsonObject,
    serializer: DeserializationStrategy<T>,
    vararg keys: String,
): T? {
    for (key in keys) {
        val element: JsonElement = obj[key] ?: continue
        if (element is kotlinx.serialization.json.JsonNull) continue
        runCatching { json.decodeFromJsonElement(serializer, element) }
            .getOrNull()
            ?.let { return it }
    }
    return null
}

private fun instantFromEpochSeconds(timestamp: Double): Instant? {
    if (!timestamp.isFinite()) return null
    return runCatching {
        val wholeSeconds = timestamp.toLong()
        val nanos = ((timestamp - wholeSeconds) * 1_000_000_000.0).toLong()
        Instant.ofEpochSecond(wholeSeconds, nanos)
    }.getOrNull()
}

private fun epochSeconds(instant: Instant): Double =
    instant.epochSecond + instant.nano / 1_000_000_000.0
