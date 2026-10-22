package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.model.TranscriptTurnClassifier
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesDelegationCompletion
import io.github.jcartu.hermex.model.hermes.HermesTranscriptHistory
import io.github.jcartu.hermex.ui.feature.chat.ChatMarkerMessageClassifier
import io.github.jcartu.hermex.ui.feature.chat.ReasoningGroup

/**
 * Port of `HermesCompaction` from
 * `Features/Chat/HermesTranscriptHistory.swift`: the reference card and the
 * message after which it is displayed.
 */
data class HermesCompaction(
    val referenceText: String,
    val anchorMessageID: String?,
)

/**
 * Port of `HermesTranscriptProjection` from
 * `Features/Chat/HermesTranscriptHistory.swift`.
 */
object HermesTranscriptProjection {
    /**
     * Port of `HermesTranscriptProjection.Result` from
     * `Features/Chat/HermesTranscriptHistory.swift`.
     */
    data class Result(
        val messages: List<ChatMessage> = emptyList(),
        val toolCallGroups: List<ToolCallGroup> = emptyList(),
        val reasoningGroups: List<ReasoningGroup> = emptyList(),
        val compaction: HermesCompaction? = null,
    )

    /** Whether [message] starts a Hermes turn. */
    fun isTurnBoundary(message: ChatMessage): Boolean {
        if (message.isSteerMessage) return false
        if (message.role != "delegation_completion") {
            return TranscriptTurnClassifier.isUserTurnBoundary(message)
        }
        val userMessage = ChatMessage(
            role = "user",
            content = message.content,
            timestamp = message.timestamp,
            messageId = message.messageId,
        )
        return !userMessage.isSteerMessage &&
            TranscriptTurnClassifier.isUserTurnBoundary(userMessage)
    }

    /** Projects Hermes REST transcript [rows] into the shared chat display models. */
    fun project(rows: List<BotJSON>, root: String): Result {
        val messages = mutableListOf<ChatMessage>()
        val toolCallGroups = mutableListOf<ToolCallGroup>()
        val reasoningGroups = mutableListOf<ReasoningGroup>()
        var compaction: HermesCompaction? = null
        val tools = mutableListOf<ToolCall>()
        val reasoning = mutableListOf<String>()
        var pendingStart: Int? = null
        val calls = mutableMapOf<String, Pair<String?, Map<String, JSONValue>?>>()

        fun flush(anchor: String?) {
            val start = pendingStart ?: return
            if (tools.isNotEmpty()) {
                toolCallGroups += ToolCallGroup(
                    id = "$root/row-$start/tools",
                    anchorMessageID = anchor,
                    toolCalls = tools.toList(),
                )
            }
            if (reasoning.isNotEmpty()) {
                reasoningGroups += ReasoningGroup(
                    id = "$root/row-$start/reasoning",
                    anchorMessageID = anchor,
                    text = reasoning.joinToString("\n\n"),
                )
            }
            tools.clear()
            reasoning.clear()
            pendingStart = null
        }

        for (row in rows) {
            val rowID = HermesTranscriptHistory.id(row) ?: continue
            val role = row["role"].text ?: continue
            val kind = row["display_kind"].text
            if (row["_compressed_summary"].flag == true) {
                compaction = HermesCompaction(
                    referenceText = summary(row["content"].text.orEmpty()),
                    anchorMessageID = messages.lastOrNull()?.messageId,
                )
                if (row["display_content"].text == null) continue
            }
            if (kind == "hidden") continue

            when (role) {
                "tool" -> {
                    if (pendingStart == null) pendingStart = rowID
                    val call = row["tool_call_id"].text?.let(calls::get)
                    tools += ToolCall(
                        id = "$root/row-$rowID",
                        name = call?.first ?: row["tool_name"].text,
                        preview = row["content"].toolResultPreview,
                        args = call?.second,
                        isCompleted = true,
                        startedAt = row["timestamp"].number ?: 0.0,
                    )
                }

                "assistant", "user" -> {
                    if (role == "assistant") {
                        for (call in row["tool_calls"].list.orEmpty()) {
                            val id = call["id"].text ?: call["call_id"].text ?: continue
                            calls[id] = toolName(call, row["tool_call_labels"][id]) to
                                arguments(call["function"]["arguments"])
                        }
                        val thought = row["display_reasoning"].text
                            ?: row["reasoning"].text
                            ?: row["reasoning_content"].text
                        if (!thought.isNullOrBlank()) {
                            if (pendingStart == null) pendingStart = rowID
                            reasoning += thought
                        }
                    }

                    val text = text(row)
                    if (text.isBlank()) continue
                    if (role != "assistant" && text.trimStart().startsWith("[System:")) continue

                    val id = "$root/row-$rowID"
                    flush(anchor = id)
                    val isDelegationCompletion = kind == HermesDelegationCompletion.DISPLAY_KIND
                    val steer = if (role == "user") ChatMessage.strippedSteerText(text) else null
                    val skill = if (role == "user") skillInvocation(text) else null
                    messages += ChatMessage(
                        role = if (isDelegationCompletion) "delegation_completion" else role,
                        content = skill ?: if (role == "user" && !isDelegationCompletion) {
                            botMentionDisplayText(steer ?: text)
                        } else {
                            text
                        },
                        timestamp = row["timestamp"].number,
                        messageId = id,
                        displayKind = if (steer != null) {
                            ChatMessage.STEER_DISPLAY_KIND
                        } else {
                            kind ?: if (skill == null) null else "skill_invocation"
                        },
                        displayMetadata = row["display_metadata"].argumentDictionary,
                        rowID = rowID,
                        isCompacted = row["active"].integer == 0,
                    )
                }
            }
        }
        flush(anchor = null)

        return Result(
            messages = messages,
            toolCallGroups = toolCallGroups,
            reasoningGroups = reasoningGroups,
            compaction = compaction,
        )
    }

    /**
     * Port of `HermesChatTurnCoordinator.displayed` from
     * `Features/Chat/HermesChatTurnCoordinator.swift`: hide transport-only
     * attachment references, the host context footer, and a valid Bot mention
     * annotation from a displayed user row.
     */
    fun displayed(message: ChatMessage): ChatMessage {
        if (message.role != "user") return message
        val content = message.content ?: return message
        val shown = MessageAttachment.hermesReferences(content)
        val text = botMentionDisplayText(shown.text)
        if (text == content && shown.attachments.isEmpty()) return message
        return message.copy(
            content = text,
            attachments = if (shown.attachments.isEmpty()) {
                message.attachments
            } else {
                message.attachments.orEmpty() + shown.attachments
            },
        )
    }

    /**
     * Port of `HermesTranscriptProjection.skillInvocation` and Hermes
     * `describe_skill_invocation`: returns the user-authored slash-command line
     * from a stored expanded skill prompt.
     */
    fun skillInvocation(text: String): String? {
        val prefix = "[IMPORTANT: The user has invoked the "
        if (!text.startsWith(prefix)) return null
        val quoted = text.substring(prefix.length)
        val name = if (quoted.startsWith('"')) {
            val close = quoted.indexOf('"', startIndex = 1)
            if (close >= 0) quoted.substring(1, close).trim() else ""
        } else {
            ""
        }

        fun cut(after: String, before: String, last: Boolean = false): String {
            val marker = if (last) text.lastIndexOf(after) else text.indexOf(after)
            if (marker < 0) return ""
            val start = marker + after.length
            val stop = text.indexOf(before, startIndex = start).let { if (it < 0) text.length else it }
            return text.substring(start, stop)
        }

        val instruction = when {
            " skill bundle," in text -> cut(
                after = "\nUser instruction: ",
                before = "\n\n[Loaded as part of the ",
            )

            "The full skill content is loaded below.]" in text -> cut(
                after = "The user has provided the following instruction alongside the skill invocation: ",
                before = "\n\n[Runtime note:",
                last = true,
            )

            else -> ""
        }
        val words = normalizedWords(instruction)
        val label = if (name.startsWith('/')) name else "/$name"
        return when {
            words.isNotEmpty() -> if (name.isEmpty()) words else "$label $words"
            name.isNotEmpty() -> label
            else -> null
        }
    }

    private fun normalizedWords(value: String): String {
        if (value.isEmpty()) return ""
        val normalized = StringBuilder(value.length)
        var separated = true
        for (character in value) {
            if (character.isWhitespace()) {
                separated = true
            } else {
                if (separated && normalized.isNotEmpty()) normalized.append(' ')
                normalized.append(character)
                separated = false
            }
        }
        return normalized.toString()
    }

    /**
     * Port of `HermesTranscriptProjection.summary` from
     * `Features/Chat/HermesTranscriptHistory.swift`.
     */
    fun summary(content: String): String {
        var text = content
        val priorContextEnd = "[END OF PRIOR CONTEXT — COMPACTION SUMMARY BELOW]"
        val merged = text.indexOf(priorContextEnd)
        if (merged >= 0) text = text.substring(merged + priorContextEnd.length)
        text = text.trimStart()
        if (ChatMarkerMessageClassifier.isContextCompactionText(text)) {
            val firstLineEnd = text.indexOf('\n')
            text = if (firstLineEnd >= 0) text.substring(firstLineEnd) else ""
        }
        val end = text.indexOf("--- END OF CONTEXT SUMMARY")
        if (end >= 0) text = text.substring(0, end)
        return text.trim()
    }

    /** A row's host-projected visible text and public commentary. */
    private fun text(row: BotJSON): String {
        val body = row["display_content"].text ?: row["content"].list
            ?.mapNotNull { it.text ?: it["text"].text }
            ?.joinToString(separator = "")
            ?: row["content"].text.orEmpty()
        val commentary = row["display_commentary"].list.orEmpty()
            .mapNotNull(BotJSON::text)
            .filterNot(String::isBlank)
        val joined = commentary.joinToString("\n\n")
        if (commentary.isEmpty() || joined.trim() == body.trim()) return body
        return buildList {
            add(joined)
            if (body.isNotEmpty()) add(body)
        }.joinToString("\n\n")
    }

    /** A declared call's display name, preferring bridge-provided labels. */
    private fun toolName(call: BotJSON, labels: BotJSON): String? {
        val named = labels.list.orEmpty()
            .mapNotNull { it["text"].text ?: it["name"].text }
            .filter(String::isNotEmpty)
        return named.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
            ?: call["function"]["name"].text
    }

    /** Provider tool arguments, which can be a JSON string or an object. */
    private fun arguments(value: BotJSON): Map<String, JSONValue>? =
        value.text?.let(BotJSON::parseOrNull)?.argumentDictionary ?: value.argumentDictionary

    /**
     * Port of `BotMentions.displayText` from `Features/Bots/BotMentions.swift`.
     * It intentionally recognizes only the exact generated suffix.
     */
    private fun botMentionDisplayText(text: String): String {
        val start = text.lastIndexOf(BOT_MENTION_NOTE_PREFIX)
        if (start < 0 || !text.endsWith(BOT_MENTION_NOTE_SUFFIX)) return text
        val linesStart = start + BOT_MENTION_NOTE_PREFIX.length
        val linesEnd = text.length - BOT_MENTION_NOTE_SUFFIX.length
        if (linesStart > linesEnd) return text
        val lines = text.substring(linesStart, linesEnd)
        if (!BOT_MENTION_NOTE_LINES.matches(lines)) return text
        return text.substring(0, start)
    }

    private const val BOT_MENTION_NOTE_PREFIX =
        "\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: "
    private const val BOT_MENTION_NOTE_SUFFIX =
        ". If they want one of these agents contacted, compose your own message and send it with your message_agent tool " +
            "(agents on other connected machines are reachable too — the Desktop relays it); never forward the user’s text " +
            "verbatim. If this session has no message_agent tool, agent messaging is unavailable here — say so.]"
    private val BOT_MENTION_NOTE_LINES = Regex(
        """\A@[a-zA-Z0-9][a-zA-Z0-9_-]* = agent profile "[^"\r\n]+"(?: \("[\s\S]*?"\))?""" +
            """(?:; @[a-zA-Z0-9][a-zA-Z0-9_-]* = agent profile "[^"\r\n]+"(?: \("[\s\S]*?"\))?)*\z""",
    )
}
