package io.github.jcartu.hermex.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable(with = GoalSubmissionResponseSerializer::class)
data class GoalSubmissionResponse(
    val ok: Boolean? = null,
    val action: String? = null,
    val message: String? = null,
    val goal: SubmittedGoal? = null,
    val kickoffPrompt: String? = null,
    val decision: GoalDecision? = null,
) {
    val displayMessage: String?
        get() = message?.trim()?.takeIf { it.isNotEmpty() }

    val kickoffPromptText: String?
        get() = kickoffPrompt?.trim()?.takeIf { it.isNotEmpty() }
}

internal object GoalSubmissionResponseSerializer :
    TolerantObjectSerializer<GoalSubmissionResponse>("GoalSubmissionResponse") {

    override fun fromJson(json: Json, obj: JsonObject): GoalSubmissionResponse =
        GoalSubmissionResponse(
            ok = obj.lossyBool("ok"),
            action = obj.lossyString("action"),
            message = obj.lossyString("message"),
            goal = decodeGoalValue(json, obj, SubmittedGoal.serializer(), "goal"),
            kickoffPrompt = obj.lossyString("kickoff_prompt", "kickoffPrompt"),
            decision = decodeGoalValue(json, obj, GoalDecision.serializer(), "decision"),
        )

    override fun toJson(json: Json, value: GoalSubmissionResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.action?.let { put("action", JsonPrimitive(it)) }
        value.message?.let { put("message", JsonPrimitive(it)) }
        value.goal?.let { put("goal", json.encodeToJsonElement(SubmittedGoal.serializer(), it)) }
        value.kickoffPrompt?.let { put("kickoff_prompt", JsonPrimitive(it)) }
        value.decision?.let { put("decision", json.encodeToJsonElement(GoalDecision.serializer(), it)) }
    }
}

@Serializable(with = SubmittedGoalSerializer::class)
data class SubmittedGoal(
    val goal: String? = null,
    val status: String? = null,
    val turnsUsed: Int? = null,
    val maxTurns: Int? = null,
    val lastVerdict: String? = null,
    val lastReason: String? = null,
    val pausedReason: String? = null,
)

internal object SubmittedGoalSerializer : TolerantObjectSerializer<SubmittedGoal>("SubmittedGoal") {
    override fun fromJson(json: Json, obj: JsonObject): SubmittedGoal =
        SubmittedGoal(
            goal = obj.lossyString("goal"),
            status = obj.lossyString("status"),
            turnsUsed = obj.lossyInt("turns_used", "turnsUsed"),
            maxTurns = obj.lossyInt("max_turns", "maxTurns"),
            lastVerdict = obj.lossyString("last_verdict", "lastVerdict"),
            lastReason = obj.lossyString("last_reason", "lastReason"),
            pausedReason = obj.lossyString("paused_reason", "pausedReason"),
        )

    override fun toJson(json: Json, value: SubmittedGoal): JsonObject = buildJsonObject {
        value.goal?.let { put("goal", JsonPrimitive(it)) }
        value.status?.let { put("status", JsonPrimitive(it)) }
        value.turnsUsed?.let { put("turns_used", JsonPrimitive(it)) }
        value.maxTurns?.let { put("max_turns", JsonPrimitive(it)) }
        value.lastVerdict?.let { put("last_verdict", JsonPrimitive(it)) }
        value.lastReason?.let { put("last_reason", JsonPrimitive(it)) }
        value.pausedReason?.let { put("paused_reason", JsonPrimitive(it)) }
    }
}

@Serializable(with = GoalDecisionSerializer::class)
data class GoalDecision(
    val status: String? = null,
    val shouldContinue: Boolean? = null,
    val continuationPrompt: String? = null,
    val verdict: String? = null,
    val reason: String? = null,
    val message: String? = null,
    val messageKey: String? = null,
    val messageArgs: List<JSONValue>? = null,
)

internal object GoalDecisionSerializer : TolerantObjectSerializer<GoalDecision>("GoalDecision") {
    override fun fromJson(json: Json, obj: JsonObject): GoalDecision =
        GoalDecision(
            status = obj.lossyString("status"),
            shouldContinue = obj.lossyBool("should_continue", "shouldContinue"),
            continuationPrompt = obj.lossyString("continuation_prompt", "continuationPrompt"),
            verdict = obj.lossyString("verdict"),
            reason = obj.lossyString("reason"),
            message = obj.lossyString("message"),
            messageKey = obj.lossyString("message_key", "messageKey"),
            messageArgs = decodeGoalValue(
                json,
                obj,
                goalMessageArgsSerializer,
                "message_args",
                "messageArgs",
            ),
        )

    override fun toJson(json: Json, value: GoalDecision): JsonObject = buildJsonObject {
        value.status?.let { put("status", JsonPrimitive(it)) }
        value.shouldContinue?.let { put("should_continue", JsonPrimitive(it)) }
        value.continuationPrompt?.let { put("continuation_prompt", JsonPrimitive(it)) }
        value.verdict?.let { put("verdict", JsonPrimitive(it)) }
        value.reason?.let { put("reason", JsonPrimitive(it)) }
        value.message?.let { put("message", JsonPrimitive(it)) }
        value.messageKey?.let { put("message_key", JsonPrimitive(it)) }
        value.messageArgs?.let { put("message_args", json.encodeToJsonElement(goalMessageArgsSerializer, it)) }
    }
}

private val goalMessageArgsSerializer = ListSerializer(JSONValue.serializer())

private fun <T> decodeGoalValue(
    json: Json,
    obj: JsonObject,
    serializer: DeserializationStrategy<T>,
    vararg keys: String,
): T? {
    for (key in keys) {
        val element: JsonElement = obj[key] ?: continue
        if (element is JsonNull) continue
        runCatching { json.decodeFromJsonElement(serializer, element) }
            .getOrNull()
            ?.let { return it }
    }
    return null
}
