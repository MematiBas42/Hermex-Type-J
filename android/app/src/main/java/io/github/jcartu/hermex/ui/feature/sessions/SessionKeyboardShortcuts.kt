package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The session list's hardware-keyboard commands, ported from `HermexCommands`
 * (`HermesMobileApp.swift`) + `HermexSceneActions`
 * (`Features/SessionList/SessionListView.swift`) — 1.8.0 "switch chats with
 * iPad keyboard shortcuts".
 *
 * Key mapping (iPad → Android hardware keyboard):
 * - ⌘N → Ctrl+N: New Chat
 * - ⌘F → Ctrl+F: Search Sessions
 * - ⌃Tab / ⌃⇧Tab → Ctrl+Tab / Ctrl+Shift+Tab: Next / Previous Chat
 * - ⌘⇧] / ⌘⇧[ → Ctrl+Shift+] / Ctrl+Shift+[: Next / Previous Chat
 * - ⌘1…⌘9 → Ctrl+1…Ctrl+9: Go to Chat N
 * - Ctrl+↓ / Ctrl+↑: Next / Previous Chat (Android arrow-key equivalents)
 */
data class SessionListShortcutActions(
    val canCreateNewChat: Boolean,
    val createNewChat: () -> Unit,
    val searchSessions: () -> Unit,
    /** Opens the chat at a 1-based position in the visible list. */
    val openChatAtPosition: (Int) -> Unit,
    /** Opens the chat `offset` rows from the selected one, wrapping at the ends. */
    val openAdjacentChat: (Int) -> Unit,
)

/**
 * The pane's shortcut surface, provided by the sessions list and read by the
 * shell's key handler — the Compose analogue of SwiftUI's
 * `focusedSceneValue(\.hermexSceneActions)`.
 */
val LocalSessionListShortcutActions = compositionLocalOf<SessionListShortcutActions?> { null }

/**
 * Handles one hardware key event against the session shortcuts. Returns true
 * when the event was consumed. Port of the `HermexCommands` command body:
 * out-of-range positions do nothing rather than disable (counting rows here
 * would filter and sort every session on each update).
 */
fun handleSessionListShortcutKey(
    event: KeyEvent,
    actions: SessionListShortcutActions?,
): Boolean {
    if (actions == null) return false
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed) return false

    return when {
        // ⌃Tab / ⌃⇧Tab and ⌘⇧] / ⌘⇧[ — Next / Previous Chat.
        event.key == Key.Tab && event.isShiftPressed -> {
            actions.openAdjacentChat(-1)
            true
        }
        event.key == Key.Tab -> {
            actions.openAdjacentChat(1)
            true
        }
        event.isShiftPressed && event.key == Key.RightBracket -> {
            actions.openAdjacentChat(1)
            true
        }
        event.isShiftPressed && event.key == Key.LeftBracket -> {
            actions.openAdjacentChat(-1)
            true
        }
        // Android arrow-key equivalents (this fork's hardware-keyboard mapping).
        event.key == Key.DirectionDown -> {
            actions.openAdjacentChat(1)
            true
        }
        event.key == Key.DirectionUp -> {
            actions.openAdjacentChat(-1)
            true
        }
        event.key == Key.N -> {
            // ⌘N: disabled (consumed but inert) while new chat is unavailable,
            // mirroring `.disabled(actions?.canCreateNewChat != true)`.
            if (actions.canCreateNewChat) actions.createNewChat()
            true
        }
        event.key == Key.F -> {
            actions.searchSessions()
            true
        }
        else -> {
            val position = event.key.chatPositionOrNull()
            if (position != null) {
                actions.openChatAtPosition(position)
                true
            } else {
                false
            }
        }
    }
}

/** ⌘1…⌘9 → position; null for every other key. */
private fun Key.chatPositionOrNull(): Int? = when (this) {
    Key.One -> 1
    Key.Two -> 2
    Key.Three -> 3
    Key.Four -> 4
    Key.Five -> 5
    Key.Six -> 6
    Key.Seven -> 7
    Key.Eight -> 8
    Key.Nine -> 9
    else -> null
}
