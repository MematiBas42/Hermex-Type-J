package io.github.jcartu.hermex.service

import io.github.jcartu.hermex.net.ChatStreamEvent
import kotlin.math.roundToInt

enum class BackgroundRunTerminal { COMPLETE, CANCELLED, FAILED }

data class BackgroundRunState(
    val title: String,
    val status: String = "Running",
    val preview: String = "",
    val tokensPerSecond: Double? = null,
    val terminal: BackgroundRunTerminal? = null,
) {
    fun reduce(event: ChatStreamEvent): BackgroundRunState = when (event) {
        is ChatStreamEvent.Token -> copy(
            status = "Responding",
            preview = (preview + event.text).takeLast(MAX_PREVIEW),
        )
        is ChatStreamEvent.InterimAssistant -> copy(
            status = "Responding",
            preview = (preview + event.event.text.orEmpty()).takeLast(MAX_PREVIEW),
        )
        is ChatStreamEvent.Reasoning -> copy(status = "Thinking")
        is ChatStreamEvent.ToolStarted -> copy(status = event.event.name?.let { "Using $it" } ?: "Using a tool")
        is ChatStreamEvent.ToolCompleted -> copy(
            status = if (event.event.isError == true) "Tool failed" else "Tool complete",
        )
        is ChatStreamEvent.Metering -> copy(tokensPerSecond = event.event.displayableTokensPerSecond)
        is ChatStreamEvent.ApprovalPending -> copy(status = "Approval required")
        is ChatStreamEvent.ClarificationPending -> copy(status = "Clarification required")
        is ChatStreamEvent.Done, ChatStreamEvent.StreamEnd -> copy(status = "Complete", terminal = BackgroundRunTerminal.COMPLETE)
        ChatStreamEvent.Cancelled -> copy(status = "Cancelled", terminal = BackgroundRunTerminal.CANCELLED)
        is ChatStreamEvent.Error -> copy(status = event.message, terminal = BackgroundRunTerminal.FAILED)
        is ChatStreamEvent.TransportError -> copy(status = "Reconnecting")
        is ChatStreamEvent.Title -> copy(title = event.event.title?.takeIf(String::isNotBlank) ?: title)
        is ChatStreamEvent.PendingSteerLeftover, ChatStreamEvent.Heartbeat, ChatStreamEvent.Ignored -> this
    }

    val notificationText: String
        get() = buildString {
            append(status)
            tokensPerSecond?.let { append(" · ${it.roundToInt()} tok/s") }
            preview.trim().takeIf(String::isNotEmpty)?.let { append("\n"); append(it) }
        }

    private companion object { const val MAX_PREVIEW = 240 }
}
