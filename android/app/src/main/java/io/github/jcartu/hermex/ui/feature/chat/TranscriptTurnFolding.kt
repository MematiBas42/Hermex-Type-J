package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.TranscriptTurnClassifier
import kotlin.math.floor
import kotlin.math.max

/** Presentation-only outcome consumed by [TranscriptTurnFolds]. */
data class FoldRunOutcome(
    val turnKey: String,
    val startedAt: Double,
    val endedAt: Double,
    val ending: Ending,
) {
    enum class Ending { COMPLETED, CANCELLED, FAILED }
    val elapsed: Double get() = max(0.0, endedAt - startedAt)
}

sealed interface TranscriptTurnFoldLabel {
    val title: String

    data class Worked(val elapsed: String?) : TranscriptTurnFoldLabel {
        override val title: String get() = elapsed?.let { "Worked for $it" } ?: "Worked"
    }

    data class Stopped(val elapsed: String?) : TranscriptTurnFoldLabel {
        override val title: String
            get() = elapsed?.let { "You stopped after $it" } ?: "You stopped this response"
    }
}

data class TranscriptTurnFold(
    val turnKey: String,
    val hostRenderID: String,
    val label: TranscriptTurnFoldLabel,
)

data class TranscriptTurnFoldRowState(
    val fold: TranscriptTurnFold?,
    val isExpanded: Boolean,
    val hidesBubble: Boolean,
    val hidesActivity: Boolean,
)

/**
 * Pure folding reducer ported from `Features/Chat/TranscriptTurnFolding.swift`.
 * A settled turn keeps its first and final reply visible while reasoning, tool
 * activity and interim replies fold behind one elapsed-time row.
 */
class TranscriptTurnFolds private constructor(
    val folds: List<TranscriptTurnFold>,
    private val membershipByRenderID: Map<String, Membership>,
) {
    private data class Membership(
        val foldIndex: Int,
        val isHost: Boolean,
        val foldsBubble: Boolean,
        val foldsActivity: Boolean,
    )

    fun rowState(renderID: String, expandedTurnKeys: Set<String>): TranscriptTurnFoldRowState? {
        val membership = membershipByRenderID[renderID] ?: return null
        val fold = folds[membership.foldIndex]
        val expanded = fold.turnKey in expandedTurnKeys
        return TranscriptTurnFoldRowState(
            fold = fold.takeIf { membership.isHost },
            isExpanded = expanded,
            hidesBubble = membership.foldsBubble && !expanded,
            hidesActivity = membership.foldsActivity && !expanded,
        )
    }

    companion object {
        val None = TranscriptTurnFolds(emptyList(), emptyMap())

        fun derive(
            transcriptMessages: List<TranscriptMessage>,
            messages: List<ChatMessage>,
            messageOffset: Int?,
            activityAnchorIDs: Set<String>,
            rendersBubble: (ChatMessage) -> Boolean,
            isStreamActive: Boolean,
            streamingAssistantMessageID: String?,
            latestRunOutcome: FoldRunOutcome?,
            durationForMessage: (ChatMessage) -> Double? = { null },
        ): TranscriptTurnFolds {
            val offset = max(0, messageOffset ?: 0)
            val turnKeyByAnchor = TranscriptTurnClassifier.assistantTurnKeysByAnchorID(messages, messageOffset)
            val startTimestampByTurn = buildMap {
                messages.forEachIndexed { index, message ->
                    if (TranscriptTurnClassifier.isUserTurnBoundary(message)) {
                        put(userTurnKey(offset + index), message.timestamp)
                    }
                }
            }
            val unsettledTurnKey = if (isStreamActive) latestTurnKey(messages, offset) else null
            val streamingTurnKey = streamingAssistantMessageID?.let(turnKeyByAnchor::get)

            data class Row(
                val renderID: String,
                val message: ChatMessage,
                val hasActivity: Boolean,
                val hasBubble: Boolean,
            )

            val turnOrder = mutableListOf<String>()
            val rowsByTurn = linkedMapOf<String, MutableList<Row>>()
            transcriptMessages.forEach { transcriptMessage ->
                if (transcriptMessage.message.role != "assistant") return@forEach
                val turnKey = turnKeyByAnchor[transcriptMessage.anchorID] ?: return@forEach
                val rows = rowsByTurn.getOrPut(turnKey) {
                    turnOrder += turnKey
                    mutableListOf()
                }
                rows += Row(
                    renderID = transcriptMessage.renderID,
                    message = transcriptMessage.message,
                    hasActivity = transcriptMessage.anchorID in activityAnchorIDs,
                    hasBubble = rendersBubble(transcriptMessage.message),
                )
            }

            val folds = mutableListOf<TranscriptTurnFold>()
            val membership = mutableMapOf<String, Membership>()
            turnOrder.forEach { turnKey ->
                if (turnKey == unsettledTurnKey || turnKey == streamingTurnKey) return@forEach
                val rows = rowsByTurn[turnKey].orEmpty()
                val bubbleRows = rows.filter(Row::hasBubble)
                val firstBubbleID = bubbleRows.firstOrNull()?.renderID
                val lastBubbleID = bubbleRows.lastOrNull()?.renderID
                val rowMemberships = rows.map { row ->
                    Triple(
                        row.renderID,
                        row.hasBubble && row.renderID != firstBubbleID && row.renderID != lastBubbleID,
                        row.hasActivity,
                    )
                }
                val host = rowMemberships.firstOrNull { it.second || it.third } ?: return@forEach
                val outcome = latestRunOutcome?.takeIf { it.turnKey == turnKey }
                val elapsed = elapsedSeconds(
                    rows = rows.map(Row::message),
                    startTimestamp = startTimestampByTurn[turnKey],
                    outcome = outcome,
                    durationForMessage = durationForMessage,
                )?.let(ChatWorkingElapsedFormatter::label)
                val label = if (outcome?.ending == FoldRunOutcome.Ending.CANCELLED) {
                    TranscriptTurnFoldLabel.Stopped(elapsed)
                } else {
                    TranscriptTurnFoldLabel.Worked(elapsed)
                }
                val foldIndex = folds.size
                folds += TranscriptTurnFold(turnKey, host.first, label)
                rowMemberships.forEach { (renderID, foldsBubble, foldsActivity) ->
                    membership[renderID] = Membership(
                        foldIndex = foldIndex,
                        isHost = renderID == host.first,
                        foldsBubble = foldsBubble,
                        foldsActivity = foldsActivity,
                    )
                }
            }
            return TranscriptTurnFolds(folds, membership)
        }

        fun terminalReplyRenderIDs(
            transcriptMessages: List<TranscriptMessage>,
            messages: List<ChatMessage>,
            messageOffset: Int?,
            rendersBubble: (ChatMessage) -> Boolean,
            isStreamActive: Boolean,
            streamingAssistantMessageID: String?,
        ): Set<String> {
            val turnKeyByAnchor = TranscriptTurnClassifier.assistantTurnKeysByAnchorID(messages, messageOffset)
            val unsettled = if (isStreamActive) latestTurnKey(messages, max(0, messageOffset ?: 0)) else null
            val streaming = streamingAssistantMessageID?.let(turnKeyByAnchor::get)
            val lastByTurn = linkedMapOf<String, String>()
            transcriptMessages.forEach { row ->
                if (row.message.role != "assistant" || !rendersBubble(row.message)) return@forEach
                val turnKey = turnKeyByAnchor[row.anchorID] ?: return@forEach
                if (turnKey != unsettled && turnKey != streaming) lastByTurn[turnKey] = row.renderID
            }
            return lastByTurn.values.toSet()
        }

        private fun elapsedSeconds(
            rows: List<ChatMessage>,
            startTimestamp: Double?,
            outcome: FoldRunOutcome?,
            durationForMessage: (ChatMessage) -> Double?,
        ): Double? {
            rows.asReversed().firstNotNullOfOrNull(durationForMessage)
                ?.takeIf { it.isFinite() && it >= 0 }
                ?.let { return it }
            outcome?.let { return it.elapsed }
            val start = startTimestamp?.takeIf(Double::isFinite) ?: return null
            val end = rows.mapNotNull(ChatMessage::timestamp)
                .filter(Double::isFinite)
                .maxOrNull()
                ?: return null
            return (end - start).takeIf { it >= 0 }
        }

        private fun latestTurnKey(messages: List<ChatMessage>, offset: Int): String? {
            val index = messages.indexOfLast(TranscriptTurnClassifier::isUserTurnBoundary)
            return if (index >= 0) userTurnKey(offset + index) else null
        }

        private fun userTurnKey(absoluteIndex: Int): String = "turn:user:$absoluteIndex"
    }
}

/** Compact elapsed labels shared by the active and settled rows. */
object ChatWorkingElapsedFormatter {
    fun label(startedAt: Double, now: Double): String = label(now - startedAt)

    fun label(seconds: Double): String {
        val whole = if (seconds.isFinite()) max(0, floor(seconds).toInt()) else 0
        val hours = whole / 3_600
        val minutes = (whole % 3_600) / 60
        val remainder = whole % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${remainder}s"
            minutes > 0 -> "${minutes}m ${remainder}s"
            else -> "${remainder}s"
        }
    }
}
