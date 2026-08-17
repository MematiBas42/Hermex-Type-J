package io.github.jcartu.hermex.model

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable(with = ContextWindowSnapshotSerializer::class)
data class ContextWindowSnapshot(
    val contextLength: Int? = null,
    val thresholdTokens: Int? = null,
    val lastPromptTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCost: Double? = null,
    val tokensPerSecond: Double? = null,
) {
    val tokensUsed: Int?
        get() = lastPromptTokens ?: inputTokens

    val percentage: Double?
        get() {
            val used = tokensUsed ?: return null
            val total = contextLength ?: return null
            if (total <= 0) return null
            return used.toDouble() / total.toDouble()
        }

    fun replacingTokensUsed(tokens: Int?): ContextWindowSnapshot {
        if (tokens == null) return this
        return copy(lastPromptTokens = tokens)
    }
}

internal object ContextWindowSnapshotSerializer :
    TolerantObjectSerializer<ContextWindowSnapshot>("ContextWindowSnapshot") {

    override fun fromJson(json: Json, obj: JsonObject): ContextWindowSnapshot =
        ContextWindowSnapshot(
            contextLength = obj.lossyInt("context_length", "contextLength"),
            thresholdTokens = obj.lossyInt("threshold_tokens", "thresholdTokens"),
            lastPromptTokens = obj.lossyInt("last_prompt_tokens", "lastPromptTokens"),
            inputTokens = obj.lossyInt("input_tokens", "inputTokens"),
            outputTokens = obj.lossyInt("output_tokens", "outputTokens"),
            estimatedCost = obj.lossyDouble("estimated_cost", "estimatedCost"),
            tokensPerSecond = obj.lossyDouble("tps"),
        )

    override fun toJson(json: Json, value: ContextWindowSnapshot): JsonObject = buildJsonObject {
        value.contextLength?.let { put("context_length", JsonPrimitive(it)) }
        value.thresholdTokens?.let { put("threshold_tokens", JsonPrimitive(it)) }
        value.lastPromptTokens?.let { put("last_prompt_tokens", JsonPrimitive(it)) }
        value.inputTokens?.let { put("input_tokens", JsonPrimitive(it)) }
        value.outputTokens?.let { put("output_tokens", JsonPrimitive(it)) }
        value.estimatedCost?.let { put("estimated_cost", JsonPrimitive(it)) }
        value.tokensPerSecond?.let { put("tps", JsonPrimitive(it)) }
    }
}

object ContextWindowFormatter {
    fun compactIndicator(snapshot: ContextWindowSnapshot): String? {
        val used = snapshot.tokensUsed ?: return null
        val total = snapshot.contextLength ?: return null
        if (total <= 0) return null
        val percentage = ((used.toDouble() / total.toDouble()) * 100).toInt()
        return "$percentage% context"
    }

    fun tokensLabel(snapshot: ContextWindowSnapshot): String {
        val used = snapshot.tokensUsed ?: return "Unavailable"
        val total = snapshot.contextLength ?: return "Unavailable"
        return "${formatTokens(used)} / ${formatTokens(total)}"
    }

    fun inputTokensLabel(snapshot: ContextWindowSnapshot): String {
        val tokens = snapshot.inputTokens ?: return "Unavailable"
        return formatTokens(tokens)
    }

    fun outputTokensLabel(snapshot: ContextWindowSnapshot): String {
        val tokens = snapshot.outputTokens ?: return "Unavailable"
        return formatTokens(tokens)
    }

    fun thresholdLabel(snapshot: ContextWindowSnapshot): String {
        val threshold = snapshot.thresholdTokens ?: return "Unavailable"
        if (threshold <= 0) return "Unavailable"
        return formatTokens(threshold)
    }

    fun costLabel(snapshot: ContextWindowSnapshot): String {
        val cost = snapshot.estimatedCost ?: return "Unavailable"
        return cost.formattedCost()
    }

    fun formatTokens(count: Int): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count.toDouble() / 1_000_000)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count.toDouble() / 1_000)
        else -> count.toString()
    }
}

fun Double.formattedCost(collapsingZeroCents: Boolean = false): String {
    if (collapsingZeroCents && this == 0.0) return "\$0.00"
    return String.format(Locale.US, "\$%.4f", this)
}
