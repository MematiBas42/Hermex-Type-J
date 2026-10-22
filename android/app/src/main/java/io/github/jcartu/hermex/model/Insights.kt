package io.github.jcartu.hermex.model

import kotlinx.serialization.DeserializationStrategy
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
import java.util.Locale

/**
 * One window's usage: webui's `GET /api/insights`, or a Hermes host's analytics
 * mapped into it (`InsightsResponse(hermesUsage:models:counts:now:)`, #1074).
 * Port of `Features/Insights/InsightsModels.swift`.
 */
@Serializable(with = InsightsResponseSerializer::class)
data class InsightsResponse(
    val periodDays: Int? = null,
    val totalSessions: Int? = null,
    val totalMessages: Int? = null,
    val totalInputTokens: Int? = null,
    val totalOutputTokens: Int? = null,
    val totalTokens: Int? = null,
    val totalCost: Double? = null,
    val totalCacheReadTokens: Int? = null,
    val totalCacheHitPercent: Double? = null,
    val models: List<InsightsModelBreakdown>? = null,
    val dailyTokens: List<InsightsDailyToken>? = null,
    val activityByDay: List<InsightsActivityByDay>? = null,
    val activityByHour: List<InsightsActivityByHour>? = null,
)

internal object InsightsResponseSerializer :
    TolerantObjectSerializer<InsightsResponse>("InsightsResponse") {

    override fun fromJson(json: Json, obj: JsonObject): InsightsResponse =
        InsightsResponse(
            periodDays = obj.lossyInt("period_days", "periodDays"),
            totalSessions = obj.lossyInt("total_sessions", "totalSessions"),
            totalMessages = obj.lossyInt("total_messages", "totalMessages"),
            totalInputTokens = obj.lossyInt("total_input_tokens", "totalInputTokens"),
            totalOutputTokens = obj.lossyInt("total_output_tokens", "totalOutputTokens"),
            totalTokens = obj.lossyInt("total_tokens", "totalTokens"),
            totalCost = obj.lossyCurrencyDouble("total_cost", "totalCost"),
            totalCacheReadTokens = obj.lossyInt("total_cache_read_tokens", "totalCacheReadTokens"),
            totalCacheHitPercent = obj.lossyCurrencyDouble("total_cache_hit_percent", "totalCacheHitPercent"),
            models = obj.decodeLossyList(json, "models", InsightsModelBreakdown.serializer()),
            dailyTokens = obj.decodeLossyList(json, "daily_tokens", InsightsDailyToken.serializer()),
            activityByDay = obj.decodeLossyList(json, "activity_by_day", InsightsActivityByDay.serializer()),
            activityByHour = obj.decodeLossyList(json, "activity_by_hour", InsightsActivityByHour.serializer()),
        )

    override fun toJson(json: Json, value: InsightsResponse): JsonObject = buildJsonObject {
        value.periodDays?.let { put("period_days", JsonPrimitive(it)) }
        value.totalSessions?.let { put("total_sessions", JsonPrimitive(it)) }
        value.totalMessages?.let { put("total_messages", JsonPrimitive(it)) }
        value.totalInputTokens?.let { put("total_input_tokens", JsonPrimitive(it)) }
        value.totalOutputTokens?.let { put("total_output_tokens", JsonPrimitive(it)) }
        value.totalTokens?.let { put("total_tokens", JsonPrimitive(it)) }
        value.totalCost?.let { put("total_cost", JsonPrimitive(it)) }
        value.totalCacheReadTokens?.let { put("total_cache_read_tokens", JsonPrimitive(it)) }
        value.totalCacheHitPercent?.let { put("total_cache_hit_percent", JsonPrimitive(it)) }
        value.models?.let { put("models", json.encodeToJsonElement(ListSerializer(InsightsModelBreakdown.serializer()), it)) }
        value.dailyTokens?.let { put("daily_tokens", json.encodeToJsonElement(ListSerializer(InsightsDailyToken.serializer()), it)) }
        value.activityByDay?.let { put("activity_by_day", json.encodeToJsonElement(ListSerializer(InsightsActivityByDay.serializer()), it)) }
        value.activityByHour?.let { put("activity_by_hour", json.encodeToJsonElement(ListSerializer(InsightsActivityByHour.serializer()), it)) }
    }
}

@Serializable(with = InsightsModelBreakdownSerializer::class)
data class InsightsModelBreakdown(
    val model: String? = null,
    /**
     * The billing provider, which only a Hermes host reports; one model can be
     * billed through two, and each is its own row.
     */
    val provider: String? = null,
    val sessions: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val totalTokens: Int? = null,
    val cost: Double? = null,
    val cacheHitPercent: Double? = null,
    val sessionShare: Int? = null,
    val tokenShare: Int? = null,
    val costShare: Int? = null,
) {
    val displayShare: Int?
        get() {
            val shares = listOf(costShare, tokenShare, sessionShare).filterNotNull()
            return shares.firstOrNull { it > 0 } ?: shares.firstOrNull()
        }
}

internal object InsightsModelBreakdownSerializer :
    TolerantObjectSerializer<InsightsModelBreakdown>("InsightsModelBreakdown") {

    override fun fromJson(json: Json, obj: JsonObject): InsightsModelBreakdown =
        InsightsModelBreakdown(
            model = obj.lossyString("model"),
            provider = obj.lossyString("provider"),
            sessions = obj.lossyInt("sessions"),
            inputTokens = obj.lossyInt("input_tokens", "inputTokens"),
            outputTokens = obj.lossyInt("output_tokens", "outputTokens"),
            cacheReadTokens = obj.lossyInt("cache_read_tokens", "cacheReadTokens"),
            totalTokens = obj.lossyInt("total_tokens", "totalTokens"),
            cost = obj.lossyCurrencyDouble("cost"),
            cacheHitPercent = obj.lossyCurrencyDouble("cache_hit_percent", "cacheHitPercent"),
            sessionShare = obj.lossyInt("session_share", "sessionShare"),
            tokenShare = obj.lossyInt("token_share", "tokenShare"),
            costShare = obj.lossyInt("cost_share", "costShare"),
        )

    override fun toJson(json: Json, value: InsightsModelBreakdown): JsonObject = buildJsonObject {
        value.model?.let { put("model", JsonPrimitive(it)) }
        value.provider?.let { put("provider", JsonPrimitive(it)) }
        value.sessions?.let { put("sessions", JsonPrimitive(it)) }
        value.inputTokens?.let { put("input_tokens", JsonPrimitive(it)) }
        value.outputTokens?.let { put("output_tokens", JsonPrimitive(it)) }
        value.cacheReadTokens?.let { put("cache_read_tokens", JsonPrimitive(it)) }
        value.totalTokens?.let { put("total_tokens", JsonPrimitive(it)) }
        value.cost?.let { put("cost", JsonPrimitive(it)) }
        value.cacheHitPercent?.let { put("cache_hit_percent", JsonPrimitive(it)) }
        value.sessionShare?.let { put("session_share", JsonPrimitive(it)) }
        value.tokenShare?.let { put("token_share", JsonPrimitive(it)) }
        value.costShare?.let { put("cost_share", JsonPrimitive(it)) }
    }
}

@Serializable(with = InsightsDailyTokenSerializer::class)
data class InsightsDailyToken(
    val date: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val sessions: Int? = null,
    val cost: Double? = null,
)

internal object InsightsDailyTokenSerializer :
    TolerantObjectSerializer<InsightsDailyToken>("InsightsDailyToken") {

    override fun fromJson(json: Json, obj: JsonObject): InsightsDailyToken =
        InsightsDailyToken(
            date = obj.lossyString("date"),
            inputTokens = obj.lossyInt("input_tokens", "inputTokens"),
            outputTokens = obj.lossyInt("output_tokens", "outputTokens"),
            cacheReadTokens = obj.lossyInt("cache_read_tokens", "cacheReadTokens"),
            sessions = obj.lossyInt("sessions"),
            cost = obj.lossyCurrencyDouble("cost"),
        )

    override fun toJson(json: Json, value: InsightsDailyToken): JsonObject = buildJsonObject {
        value.date?.let { put("date", JsonPrimitive(it)) }
        value.inputTokens?.let { put("input_tokens", JsonPrimitive(it)) }
        value.outputTokens?.let { put("output_tokens", JsonPrimitive(it)) }
        value.cacheReadTokens?.let { put("cache_read_tokens", JsonPrimitive(it)) }
        value.sessions?.let { put("sessions", JsonPrimitive(it)) }
        value.cost?.let { put("cost", JsonPrimitive(it)) }
    }
}

@Serializable(with = InsightsActivityByDaySerializer::class)
data class InsightsActivityByDay(
    val day: String? = null,
    val sessions: Int? = null,
)

internal object InsightsActivityByDaySerializer :
    TolerantObjectSerializer<InsightsActivityByDay>("InsightsActivityByDay") {

    override fun fromJson(json: Json, obj: JsonObject): InsightsActivityByDay =
        InsightsActivityByDay(
            day = obj.lossyString("day"),
            sessions = obj.lossyInt("sessions"),
        )

    override fun toJson(json: Json, value: InsightsActivityByDay): JsonObject = buildJsonObject {
        value.day?.let { put("day", JsonPrimitive(it)) }
        value.sessions?.let { put("sessions", JsonPrimitive(it)) }
    }
}

@Serializable(with = InsightsActivityByHourSerializer::class)
data class InsightsActivityByHour(
    val hour: Int? = null,
    val sessions: Int? = null,
)

internal object InsightsActivityByHourSerializer :
    TolerantObjectSerializer<InsightsActivityByHour>("InsightsActivityByHour") {

    override fun fromJson(json: Json, obj: JsonObject): InsightsActivityByHour =
        InsightsActivityByHour(
            hour = obj.lossyInt("hour"),
            sessions = obj.lossyInt("sessions"),
        )

    override fun toJson(json: Json, value: InsightsActivityByHour): JsonObject = buildJsonObject {
        value.hour?.let { put("hour", JsonPrimitive(it)) }
        value.sessions?.let { put("sessions", JsonPrimitive(it)) }
    }
}

/**
 * Formats a server-reported 0–100 percentage (e.g. `cache_hit_percent`) with a
 * localized percent symbol and at most one fraction digit ("87.5%", "12%").
 * Port of `insightsFormattedPercent(_:locale:)`.
 */
fun insightsFormattedPercent(value: Double, locale: Locale = Locale.getDefault()): String =
    formatPercent(value / 100.0, locale)

private fun formatPercent(fraction: Double, locale: Locale): String {
    val percentage = fraction * 100
    val digits = if (percentage == kotlin.math.round(percentage)) 0 else 1
    val rounded = String.format(locale, "%.${digits}f", percentage)
    return "$rounded%"
}

/** Swift's `decodeLossyCurrencyDoubleIfPresent`: number, or "$1,234.56"-style string. */
internal fun JsonObject.lossyCurrencyDouble(vararg keys: String): Double? {
    for (key in keys) {
        val value = lossyCurrencyDouble(this[key]) ?: continue
        return value
    }
    return null
}

internal fun lossyCurrencyDouble(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (!primitive.isString) {
        if (primitive.booleanOrNull != null) return null
        return primitive.doubleOrNull
    }
    val normalized = primitive.content
        .trim()
        .replace("$", "")
        .replace(",", "")
    if (normalized.isEmpty()) return null
    return normalized.toDoubleOrNull()
}

/** Tolerantly decodes a list field: a malformed entry drops out, a missing field is null. */
internal fun <T> JsonObject.decodeLossyList(
    json: Json,
    key: String,
    serializer: DeserializationStrategy<T>,
): List<T>? {
    val element = presentElement(key) ?: return null
    val array = element as? JsonArray ?: return null
    val list = array.mapNotNull { item ->
        runCatching { json.decodeFromJsonElement(serializer, item) }.getOrNull()
    }
    return list
}
