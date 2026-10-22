package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hardware-keyboard chat mapping (1.8.0), ported from `HermexCommands`:
 * Ctrl+1…9 position, Ctrl+Tab / Ctrl+Shift+Tab / Ctrl+Shift+] / Ctrl+Shift+[ /
 * Ctrl+↓ / Ctrl+↑ adjacent, Ctrl+N new chat, Ctrl+F search. Also covers
 * `ChatShortcutNavigation`'s position/adjacency rules and wrap-around.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SessionKeyboardShortcutsTest {

    private class Recorder(canCreate: Boolean = true) {
        val calls = mutableListOf<String>()
        val actions = SessionListShortcutActions(
            canCreateNewChat = canCreate,
            createNewChat = { calls += "new" },
            searchSessions = { calls += "search" },
            openChatAtPosition = { calls += "position:$it" },
            openAdjacentChat = { calls += "adjacent:$it" },
        )
    }

    private fun keyEvent(
        keyCode: Int,
        ctrl: Boolean = true,
        shift: Boolean = false,
        action: Int = android.view.KeyEvent.ACTION_DOWN,
    ): KeyEvent {
        val meta = (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or
            (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0)
        val native = android.view.KeyEvent(0, 0, action, keyCode, 0, meta)
        return KeyEvent(native)
    }

    @Test
    fun `ctrl+digit opens the chat at that position`() {
        val recorder = Recorder()
        assertEquals(
            true,
            handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_3), recorder.actions),
        )
        assertEquals(listOf("position:3"), recorder.calls)
    }

    @Test
    fun `ctrl+tab and ctrl+shift+tab step chats`() {
        val recorder = Recorder()
        handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_TAB), recorder.actions)
        handleSessionListShortcutKey(
            keyEvent(android.view.KeyEvent.KEYCODE_TAB, shift = true),
            recorder.actions,
        )
        assertEquals(listOf("adjacent:1", "adjacent:-1"), recorder.calls)
    }

    @Test
    fun `ctrl+shift+brackets and ctrl+arrows step chats`() {
        val recorder = Recorder()
        handleSessionListShortcutKey(
            keyEvent(android.view.KeyEvent.KEYCODE_RIGHT_BRACKET, shift = true),
            recorder.actions,
        )
        handleSessionListShortcutKey(
            keyEvent(android.view.KeyEvent.KEYCODE_LEFT_BRACKET, shift = true),
            recorder.actions,
        )
        handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN), recorder.actions)
        handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP), recorder.actions)
        assertEquals(listOf("adjacent:1", "adjacent:-1", "adjacent:1", "adjacent:-1"), recorder.calls)
    }

    @Test
    fun `ctrl+n honors the new-chat gate and ctrl+f opens search`() {
        val recorder = Recorder()
        handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_N), recorder.actions)
        handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_F), recorder.actions)
        assertEquals(listOf("new", "search"), recorder.calls)

        // Disabled while offline: the key is consumed but nothing fires,
        // mirroring `.disabled(actions?.canCreateNewChat != true)`.
        val offline = Recorder(canCreate = false)
        assertEquals(
            true,
            handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_N), offline.actions),
        )
        assertEquals(emptyList<String>(), offline.calls)
    }

    @Test
    fun `unmodified keys and key-up are ignored`() {
        val recorder = Recorder()
        assertEquals(
            false,
            handleSessionListShortcutKey(
                keyEvent(android.view.KeyEvent.KEYCODE_3, ctrl = false),
                recorder.actions,
            ),
        )
        assertEquals(
            false,
            handleSessionListShortcutKey(
                keyEvent(android.view.KeyEvent.KEYCODE_3, action = android.view.KeyEvent.ACTION_UP),
                recorder.actions,
            ),
        )
        assertEquals(false, handleSessionListShortcutKey(keyEvent(android.view.KeyEvent.KEYCODE_3), null))
        assertEquals(emptyList<String>(), recorder.calls)
    }

    // MARK: - ChatShortcutNavigation

    private fun chat(id: String) = io.github.jcartu.hermex.model.SessionSummary(
        sessionId = id,
        title = "Chat $id",
    )

    @Test
    fun `chat at position is one-based and bounded`() {
        val chats = listOf(chat("a"), chat("b"))
        assertEquals("a", ChatShortcutNavigation.chatAtPosition(1, chats)?.sessionId)
        assertEquals("b", ChatShortcutNavigation.chatAtPosition(2, chats)?.sessionId)
        assertNull(ChatShortcutNavigation.chatAtPosition(3, chats))
        assertNull(ChatShortcutNavigation.chatAtPosition(0, chats))
    }

    @Test
    fun `adjacent chat wraps at the ends`() {
        val chats = listOf(chat("a"), chat("b"), chat("c"))
        assertEquals(
            "c",
            ChatShortcutNavigation.adjacentChat(offset = -1, selectedSessionId = "a", chats)?.sessionId,
        )
        assertEquals(
            "a",
            ChatShortcutNavigation.adjacentChat(offset = 1, selectedSessionId = "c", chats)?.sessionId,
        )
        assertEquals(
            "b",
            ChatShortcutNavigation.adjacentChat(offset = 1, selectedSessionId = "a", chats)?.sessionId,
        )
    }

    @Test
    fun `adjacent without a selection starts at the first or last chat`() {
        val chats = listOf(chat("a"), chat("b"))
        assertEquals(
            "a",
            ChatShortcutNavigation.adjacentChat(offset = 1, selectedSessionId = null, chats)?.sessionId,
        )
        assertEquals(
            "b",
            ChatShortcutNavigation.adjacentChat(offset = -1, selectedSessionId = null, chats)?.sessionId,
        )
        assertNull(ChatShortcutNavigation.adjacentChat(offset = 1, selectedSessionId = null, emptyList()))
    }
}
