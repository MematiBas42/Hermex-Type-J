package io.github.jcartu.hermex.ui.feature.bots

/**
 * Port of `Features/Bots/BotMessageActions.swift`: room messages expose Copy only. The value
 * copied is the original Markdown source, never rendered/plain-text reconstruction.
 */
data class BotRoomMessageAction(
    val title: String,
    val enabled: Boolean,
    val perform: () -> Unit,
)

fun botRoomMessageActions(copyText: String?, copy: (String) -> Unit): List<BotRoomMessageAction> {
    val source = copyText?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(BotRoomMessageAction(title = "Copy", enabled = true) { copy(source) })
}
