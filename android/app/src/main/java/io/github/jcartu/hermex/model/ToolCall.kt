package io.github.jcartu.hermex.model

import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val toolCallArgumentsSerializer = MapSerializer(String.serializer(), JSONValue.serializer())

/** A live or completed tool invocation shown in the chat transcript. */
data class ToolCall(
    val id: String = "live-tool-${uuidString()}",
    /** Stable disclosure identity when a generated id is replaced by the server id. */
    val presentationID: String = id,
    var name: String?,
    var preview: String?,
    var args: Map<String, JSONValue>?,
    var duration: Double? = null,
    var isError: Boolean? = null,
    var isCompleted: Boolean = false,
    val startedAt: Double = currentEpochSeconds(),
) {
    val displayName: String
        get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Tool"
}

@Serializable(with = PersistedToolCallSerializer::class)
data class PersistedToolCall(
    val name: String? = null,
    val snippet: String? = null,
    val tid: String? = null,
    val assistantMsgIdx: Int? = null,
    val args: Map<String, JSONValue>? = null,
    /** Server verdict when persisted transcripts include it; null on older rows. */
    val isError: Boolean? = null,
    val duration: Double? = null,
) {
    fun toolCall(fallbackIndex: Int): ToolCall {
        val id = tid?.trim()?.takeIf { it.isNotEmpty() }
            ?: "persisted-tool-$fallbackIndex"

        return ToolCall(
            id = id,
            name = name,
            preview = snippet,
            args = args,
            duration = duration,
            isError = isError,
            isCompleted = true,
        )
    }
}

internal object PersistedToolCallSerializer :
    TolerantObjectSerializer<PersistedToolCall>("PersistedToolCall") {

    override fun fromJson(json: Json, obj: JsonObject): PersistedToolCall =
        PersistedToolCall(
            name = obj.lossyString("name"),
            snippet = obj.lossyString("snippet"),
            tid = obj.lossyString("tid"),
            assistantMsgIdx = obj.lossyInt("assistant_msg_idx", "assistantMsgIdx"),
            isError = obj.lossyBool("is_error", "isError"),
            duration = obj.lossyDouble("duration"),
            args = obj.presentElement("args")?.let { element ->
                runCatching {
                    json.decodeFromJsonElement(toolCallArgumentsSerializer, element)
                }.getOrNull()
            },
        )

    override fun toJson(json: Json, value: PersistedToolCall): JsonObject = buildJsonObject {
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.snippet?.let { put("snippet", JsonPrimitive(it)) }
        value.tid?.let { put("tid", JsonPrimitive(it)) }
        value.assistantMsgIdx?.let { put("assistant_msg_idx", JsonPrimitive(it)) }
        value.args?.let {
            put("args", json.encodeToJsonElement(toolCallArgumentsSerializer, it))
        }
        value.isError?.let { put("is_error", JsonPrimitive(it)) }
        value.duration?.let { put("duration", JsonPrimitive(it)) }
    }
}

data class ToolCallGroup(
    val id: String = uuidString(),
    val anchorMessageID: String?,
    val toolCalls: List<ToolCall>,
) {
    val activityTitle: String
        get() = if (toolCalls.size == 1) {
            "Activity: 1 tool"
        } else {
            "Activity: ${toolCalls.size} tools"
        }

    val isComplete: Boolean
        get() = toolCalls.all { it.isCompleted }

    val hasFailedTool: Boolean
        get() = toolCalls.any { it.isError == true }

    companion object {
        fun live(anchorMessageID: String?, toolCalls: List<ToolCall>): ToolCallGroup =
            ToolCallGroup(
                id = "live-tools-${anchorMessageID ?: "unanchored"}",
                anchorMessageID = anchorMessageID,
                toolCalls = toolCalls,
            )

        fun groups(
            persistedToolCalls: List<PersistedToolCall>,
            messages: List<ChatMessage>,
            messageOffset: Int?,
        ): List<ToolCallGroup> {
            val derivedGroups = groupsFromMessageMetadata(messages, messageOffset)
            if (persistedToolCalls.isEmpty()) {
                return coalescingByAssistantTurn(derivedGroups, messages, messageOffset)
            }

            return coalescingByAssistantTurn(
                merging(
                    primaryGroups = groupsFromPersistedToolCalls(
                        persistedToolCalls,
                        messages,
                        messageOffset,
                    ),
                    fallbackGroups = derivedGroups,
                ),
                messages,
                messageOffset,
            )
        }

        private fun groupsFromPersistedToolCalls(
            persistedToolCalls: List<PersistedToolCall>,
            messages: List<ChatMessage>,
            messageOffset: Int?,
        ): List<ToolCallGroup> {
            val offset = messageOffset ?: 0
            val groups = mutableListOf<ToolCallGroup>()
            val groupIndexesByAnchor = mutableMapOf<String, Int>()

            persistedToolCalls.forEachIndexed { toolIndex, persistedToolCall ->
                val assistantMsgIdx = persistedToolCall.assistantMsgIdx
                    ?: return@forEachIndexed
                val loadedMessageIndex = assistantMsgIdx - offset
                if (loadedMessageIndex !in messages.indices) return@forEachIndexed

                val anchorMessageID = TranscriptTurnClassifier.assistantAnchorID(
                    rawIndex = loadedMessageIndex,
                    messages = messages,
                    messageOffset = messageOffset,
                ) ?: return@forEachIndexed
                val toolCall = persistedToolCall.toolCall(fallbackIndex = toolIndex)

                val groupIndex = groupIndexesByAnchor[anchorMessageID]
                if (groupIndex != null) {
                    val existingGroup = groups[groupIndex]
                    groups[groupIndex] = existingGroup.copy(
                        toolCalls = existingGroup.toolCalls + toolCall,
                    )
                } else {
                    groupIndexesByAnchor[anchorMessageID] = groups.size
                    groups += ToolCallGroup(
                        id = "persisted-tools-$anchorMessageID",
                        anchorMessageID = anchorMessageID,
                        toolCalls = listOf(toolCall),
                    )
                }
            }

            return groups
        }

        private fun groupsFromMessageMetadata(
            messages: List<ChatMessage>,
            messageOffset: Int?,
        ): List<ToolCallGroup> {
            val resultsByToolID = toolResultSnippetsByID(messages)
            val groups = mutableListOf<ToolCallGroup>()
            var currentAnchorMessageID: String? = null
            val currentToolCalls = mutableListOf<ToolCall>()

            fun flushCurrentGroup() {
                if (currentToolCalls.isEmpty()) {
                    currentAnchorMessageID = null
                    return
                }

                groups += ToolCallGroup(
                    id = "persisted-tools-${currentAnchorMessageID ?: "unanchored-${groups.size}"}",
                    anchorMessageID = currentAnchorMessageID,
                    toolCalls = uniqueToolCalls(currentToolCalls),
                )
                currentAnchorMessageID = null
                currentToolCalls.clear()
            }

            messages.forEachIndexed { messageIndex, message ->
                if (TranscriptTurnClassifier.isUserTurnBoundary(message)) {
                    flushCurrentGroup()
                    return@forEachIndexed
                }

                if (message.role != "assistant") return@forEachIndexed

                val toolCalls = openAIToolCalls(
                    message = message,
                    messageIndex = messageIndex,
                    resultsByToolID = resultsByToolID,
                ) + anthropicToolCalls(
                    message = message,
                    messageIndex = messageIndex,
                    resultsByToolID = resultsByToolID,
                )

                if (toolCalls.isEmpty()) return@forEachIndexed

                if (currentAnchorMessageID == null) {
                    currentAnchorMessageID = TranscriptTurnClassifier.anchorID(
                        message = message,
                        index = messageIndex,
                        messageOffset = messageOffset,
                    )
                }
                currentToolCalls += toolCalls
            }

            flushCurrentGroup()
            return groups
        }

        private fun toolResultSnippetsByID(messages: List<ChatMessage>): Map<String, String> {
            val results = mutableMapOf<String, String>()

            for (message in messages) {
                if (message.role == "tool") {
                    val toolCallID = nonEmpty(message.toolCallId) ?: nonEmpty(message.toolUseId)
                    val content = nonEmpty(message.content)
                    if (toolCallID != null && content != null) {
                        results[toolCallID] = content
                    }
                }

                for (part in message.contentParts.orEmpty()) {
                    val toolResult = toolResult(part) ?: continue
                    results[toolResult.first] = toolResult.second
                }
            }

            return results
        }

        private fun openAIToolCalls(
            message: ChatMessage,
            messageIndex: Int,
            resultsByToolID: Map<String, String>,
        ): List<ToolCall> = message.toolCalls.orEmpty().mapIndexedNotNull { toolIndex, value ->
            toolCall(
                value = value,
                messageIndex = messageIndex,
                toolIndex = toolIndex,
                resultsByToolID = resultsByToolID,
            )
        }

        private fun toolCall(
            value: JSONValue,
            messageIndex: Int,
            toolIndex: Int,
            resultsByToolID: Map<String, String>,
        ): ToolCall? {
            val obj = value.objectValue ?: return null
            val function = obj["function"]?.objectValue
            val name = nonEmpty(function?.get("name")?.stringValue)
                ?: nonEmpty(obj["name"]?.stringValue)
                ?: "tool"
            val toolID = nonEmpty(obj["id"]?.stringValue)
                ?: nonEmpty(obj["call_id"]?.stringValue)
                ?: nonEmpty(obj["tool_call_id"]?.stringValue)
                ?: "message-tool-$messageIndex-$toolIndex"
            val argumentValue = function?.get("arguments")
                ?: obj["arguments"]
                ?: obj["args"]
                ?: obj["input"]
            val preview = nonEmpty(resultsByToolID[toolID])
                ?: nonEmpty(obj["snippet"]?.stringValue)
                ?: nonEmpty(obj["preview"]?.stringValue)

            return ToolCall(
                id = toolID,
                name = name,
                preview = preview,
                args = arguments(argumentValue),
                isCompleted = true,
            )
        }

        private fun anthropicToolCalls(
            message: ChatMessage,
            messageIndex: Int,
            resultsByToolID: Map<String, String>,
        ): List<ToolCall> = message.contentParts.orEmpty().mapIndexedNotNull { toolIndex, value ->
            val obj = value.objectValue ?: return@mapIndexedNotNull null
            if (obj["type"]?.stringValue != "tool_use") return@mapIndexedNotNull null

            val name = nonEmpty(obj["name"]?.stringValue) ?: "tool"
            val toolID = nonEmpty(obj["id"]?.stringValue)
                ?: "message-tool-$messageIndex-$toolIndex"
            val argumentValue = obj["input"] ?: obj["arguments"] ?: obj["args"]

            ToolCall(
                id = toolID,
                name = name,
                preview = nonEmpty(resultsByToolID[toolID])
                    ?: nonEmpty(obj["snippet"]?.stringValue)
                    ?: nonEmpty(obj["preview"]?.stringValue),
                args = arguments(argumentValue),
                isCompleted = true,
            )
        }

        private fun toolResult(value: JSONValue): Pair<String, String>? {
            val obj = value.objectValue ?: return null
            if (obj["type"]?.stringValue != "tool_result") return null

            val id = nonEmpty(obj["tool_use_id"]?.stringValue)
                ?: nonEmpty(obj["tool_call_id"]?.stringValue)
                ?: nonEmpty(obj["id"]?.stringValue)
                ?: return null
            val content = resultContent(obj["content"]) ?: return null
            return id to content
        }

        private fun resultContent(value: JSONValue?): String? {
            value ?: return null
            value.string?.let { return nonEmpty(it) }

            value.arrayValue?.let { values ->
                val text = values.mapNotNull { item ->
                    item.string ?: item.objectValue?.let { obj ->
                        obj["text"]?.stringValue ?: obj["content"]?.stringValue
                    }
                }.joinToString(separator = "")
                return nonEmpty(text)
            }

            return nonEmpty(value.compactJSONString)
        }

        private fun uniqueToolCalls(toolCalls: List<ToolCall>): List<ToolCall> {
            val stableIDIndexes = mutableMapOf<String, Int>()
            val fingerprintIndexes = mutableMapOf<String, Int>()
            val unique = mutableListOf<ToolCall>()
            val isGeneratedByIndex = mutableListOf<Boolean>()

            for (toolCall in toolCalls) {
                val isGenerated = isGeneratedToolID(toolCall.id)
                val fingerprint = toolCallFingerprint(toolCall)
                val stableIDIndex = if (isGenerated) null else stableIDIndexes[toolCall.id]
                val fingerprintIndex = fingerprintIndexes[fingerprint]?.takeIf { index ->
                    isGenerated || isGeneratedByIndex[index]
                }
                val existingIndex = stableIDIndex ?: fingerprintIndex

                if (existingIndex != null) {
                    unique[existingIndex] = mergingToolCall(unique[existingIndex], toolCall)
                    isGeneratedByIndex[existingIndex] = isGeneratedToolID(unique[existingIndex].id)
                    if (!isGenerated) {
                        stableIDIndexes[toolCall.id] = existingIndex
                    }
                    fingerprintIndexes[fingerprint] = existingIndex
                } else {
                    if (!isGenerated) {
                        stableIDIndexes[toolCall.id] = unique.size
                    }
                    if (fingerprintIndexes[fingerprint] == null || isGenerated) {
                        fingerprintIndexes[fingerprint] = unique.size
                    }
                    isGeneratedByIndex += isGenerated
                    unique += toolCall
                }
            }

            return unique
        }

        fun merging(
            primaryGroups: List<ToolCallGroup>,
            fallbackGroups: List<ToolCallGroup>,
        ): List<ToolCallGroup> {
            val merged = primaryGroups.toMutableList()
            val groupIndexesByAnchor = mutableMapOf<String, Int>()
            primaryGroups.forEachIndexed { index, group ->
                group.anchorMessageID?.let { groupIndexesByAnchor[it] = index }
            }

            for (fallbackGroup in fallbackGroups) {
                val anchorMessageID = fallbackGroup.anchorMessageID
                val groupIndex = anchorMessageID?.let { groupIndexesByAnchor[it] }
                if (anchorMessageID == null || groupIndex == null) {
                    if (anchorMessageID != null) {
                        groupIndexesByAnchor[anchorMessageID] = merged.size
                    }
                    merged += fallbackGroup
                    continue
                }

                val existingGroup = merged[groupIndex]
                merged[groupIndex] = existingGroup.copy(
                    toolCalls = mergingToolCalls(
                        primaryToolCalls = existingGroup.toolCalls,
                        fallbackToolCalls = fallbackGroup.toolCalls,
                    ),
                )
            }

            return merged
        }

        fun coalescingByAssistantTurn(
            groups: List<ToolCallGroup>,
            messages: List<ChatMessage>,
            messageOffset: Int? = null,
        ): List<ToolCallGroup> {
            if (groups.size <= 1) {
                return groups.map { group ->
                    group.copy(toolCalls = uniqueToolCalls(group.toolCalls))
                }
            }

            val messageIndexesByID = mutableMapOf<String, Int>()
            messages.forEachIndexed { index, message ->
                messageIndexesByID[
                    TranscriptTurnClassifier.anchorID(message, index, messageOffset)
                ] = index
            }
            val turnKeysByAssistantMessageID =
                TranscriptTurnClassifier.assistantTurnKeysByAnchorID(messages, messageOffset)
            val mergedGroups = mutableListOf<TurnGroupBuilder>()
            val builderIndexesByTurnKey = mutableMapOf<String, Int>()

            groups.forEachIndexed { groupOrder, group ->
                val anchorIndex = group.anchorMessageID?.let { messageIndexesByID[it] }
                    ?: Int.MAX_VALUE - groupOrder
                val turnKey = group.anchorMessageID?.let { turnKeysByAssistantMessageID[it] }
                    ?: "group:${group.id}"
                val builderIndex = builderIndexesByTurnKey[turnKey]

                if (builderIndex != null) {
                    mergedGroups[builderIndex].append(group, anchorIndex)
                } else {
                    builderIndexesByTurnKey[turnKey] = mergedGroups.size
                    mergedGroups += TurnGroupBuilder(turnKey, group, anchorIndex)
                }
            }

            return mergedGroups
                .sortedWith(compareBy<TurnGroupBuilder> { it.anchorIndex }.thenBy { it.turnKey })
                .map { builder ->
                    ToolCallGroup(
                        id = builder.id,
                        anchorMessageID = builder.anchorMessageID,
                        toolCalls = uniqueToolCalls(builder.toolCalls),
                    )
                }
        }

        private fun arguments(value: JSONValue?): Map<String, JSONValue>? {
            value ?: return null

            value.objectValue?.let { obj ->
                return obj.takeIf { it.isNotEmpty() }
            }

            val string = value.string ?: return null
            val obj = runCatching { Json.parseToJsonElement(string) }.getOrNull() as? JsonObject
                ?: return null
            if (obj.isEmpty()) return null
            return obj.mapValues { JSONValue(it.value) }
        }

        private fun nonEmpty(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        private fun toolCallFingerprint(toolCall: ToolCall): String =
            listOf(
                "fallback",
                toolCall.displayName,
                argumentsKey(toolCall.args),
            ).joinToString(separator = ":")

        private fun isGeneratedToolID(id: String): Boolean =
            id.startsWith("live-tool-") ||
                id.startsWith("message-tool-") ||
                id.startsWith("persisted-tool-")

        private fun mergingToolCalls(
            primaryToolCalls: List<ToolCall>,
            fallbackToolCalls: List<ToolCall>,
        ): List<ToolCall> {
            val mergedToolCalls = uniqueToolCalls(primaryToolCalls).toMutableList()
            val fallbackNameOrdinals = mutableMapOf<String, Int>()

            for (fallbackToolCall in fallbackToolCalls) {
                val nameKey = toolCallNameKey(fallbackToolCall)
                val nameOrdinal = (fallbackNameOrdinals[nameKey] ?: 0) + 1
                fallbackNameOrdinals[nameKey] = nameOrdinal

                val existingIndex = matchingToolCallIndex(
                    fallbackToolCall = fallbackToolCall,
                    nameOrdinal = nameOrdinal,
                    toolCalls = mergedToolCalls,
                )
                if (existingIndex != null) {
                    mergedToolCalls[existingIndex] = mergingToolCall(
                        mergedToolCalls[existingIndex],
                        fallbackToolCall,
                        presentationID = fallbackToolCall.presentationID,
                    )
                } else {
                    mergedToolCalls += fallbackToolCall
                }
            }

            return uniqueToolCalls(mergedToolCalls)
        }

        private fun matchingToolCallIndex(
            fallbackToolCall: ToolCall,
            nameOrdinal: Int,
            toolCalls: List<ToolCall>,
        ): Int? {
            val fallbackIsGenerated = isGeneratedToolID(fallbackToolCall.id)

            // Stable transcript IDs are authoritative; generated live IDs are only
            // reconciliation hints while the completed transcript catches up.
            if (!fallbackIsGenerated) {
                val stableIDIndex = toolCalls.indexOfFirst { toolCall ->
                    !isGeneratedToolID(toolCall.id) && toolCall.id == fallbackToolCall.id
                }
                if (stableIDIndex >= 0) return stableIDIndex
            }

            // Fingerprints preserve the saved-transcript dedupe rule when one side
            // has a generated fallback ID and both sides still carry matching args.
            val fallbackFingerprint = toolCallFingerprint(fallbackToolCall)
            val fingerprintIndex = toolCalls.indexOfFirst { toolCall ->
                (fallbackIsGenerated || isGeneratedToolID(toolCall.id)) &&
                    toolCallFingerprint(toolCall) == fallbackFingerprint
            }
            if (fingerprintIndex >= 0) return fingerprintIndex

            return matchingToolCallNameOrdinalIndex(
                fallbackToolCall = fallbackToolCall,
                fallbackIsGenerated = fallbackIsGenerated,
                nameOrdinal = nameOrdinal,
                toolCalls = toolCalls,
            )
        }

        private fun matchingToolCallNameOrdinalIndex(
            fallbackToolCall: ToolCall,
            fallbackIsGenerated: Boolean,
            nameOrdinal: Int,
            toolCalls: List<ToolCall>,
        ): Int? {
            val fallbackNameKey = toolCallNameKey(fallbackToolCall)
            var currentOrdinal = 0

            for ((index, toolCall) in toolCalls.withIndex()) {
                if (toolCallNameKey(toolCall) != fallbackNameKey) continue
                // Name order is a last resort for live fallback events whose args
                // can be missing/truncated; never collapse two stable transcript IDs.
                if (!fallbackIsGenerated && !isGeneratedToolID(toolCall.id)) continue

                currentOrdinal += 1
                if (currentOrdinal == nameOrdinal) return index
            }

            return null
        }

        private fun toolCallNameKey(toolCall: ToolCall): String =
            toolCall.displayName.trim()

        private fun argumentsKey(args: Map<String, JSONValue>?): String {
            if (args.isNullOrEmpty()) return ""
            return JSONValue.objectOf(args.toSortedMap()).compactJSONString
        }

        private fun mergingToolCall(
            existing: ToolCall,
            fallback: ToolCall,
            presentationID: String? = null,
        ): ToolCall {
            val id = if (isGeneratedToolID(existing.id) && !isGeneratedToolID(fallback.id)) {
                fallback.id
            } else {
                existing.id
            }

            return ToolCall(
                id = id,
                presentationID = presentationID ?: existing.presentationID,
                name = existing.name ?: fallback.name,
                preview = existing.preview ?: fallback.preview,
                args = existing.args ?: fallback.args,
                duration = existing.duration ?: fallback.duration,
                isError = mergedErrorState(existing.isError, fallback.isError),
                isCompleted = existing.isCompleted || fallback.isCompleted,
                startedAt = minOf(existing.startedAt, fallback.startedAt),
            )
        }

        private fun mergedErrorState(existing: Boolean?, fallback: Boolean?): Boolean? {
            if (existing == true || fallback == true) return true
            return existing ?: fallback
        }
    }
}

data class ToolCallGroupAnchorLookup private constructor(
    private val groupsByAnchor: Map<String?, List<ToolCallGroup>>,
) {
    constructor(groups: List<ToolCallGroup> = emptyList()) : this(
        groupsByAnchor = groups.groupBy { it.anchorMessageID },
    )

    fun groups(anchorMessageID: String?): List<ToolCallGroup> =
        groupsByAnchor[anchorMessageID].orEmpty()
}

private class TurnGroupBuilder(
    val turnKey: String,
    group: ToolCallGroup,
    anchorIndex: Int,
) {
    var id: String = group.id
        private set
    var anchorMessageID: String? = group.anchorMessageID
        private set
    var anchorIndex: Int = anchorIndex
        private set
    var toolCalls: List<ToolCall> = group.toolCalls
        private set

    fun append(group: ToolCallGroup, anchorIndex: Int) {
        if (anchorIndex < this.anchorIndex) {
            id = group.id
            anchorMessageID = group.anchorMessageID
            this.anchorIndex = anchorIndex
        }
        toolCalls += group.toolCalls
    }
}

private fun uuidString(): String =
    UUID.randomUUID().toString().uppercase(Locale.ROOT)

private fun currentEpochSeconds(): Double =
    System.currentTimeMillis() / 1_000.0
