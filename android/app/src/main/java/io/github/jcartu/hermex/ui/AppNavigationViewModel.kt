package io.github.jcartu.hermex.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * App-level navigation state. SavedStateHandle carries the selected destination,
 * session, and new-chat mode across fold/display configuration changes and
 * process recreation — stronger continuity than rememberSaveable inside a shell
 * branch (the cover and inner displays can recreate the Activity).
 */
class AppNavigationViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    var selectedDestination by mutableStateOf(
        runCatching {
            HermexDestination.valueOf(savedStateHandle[DESTINATION_KEY] ?: HermexDestination.CHATS.name)
        }.getOrDefault(HermexDestination.CHATS),
    )
        private set

    var selectedSessionId by mutableStateOf(savedStateHandle.get<String>(SESSION_KEY))
        private set

    var isNewChat by mutableStateOf(savedStateHandle[NEW_CHAT_KEY] ?: false)
        private set

    fun selectDestination(destination: HermexDestination) {
        selectedDestination = destination
        savedStateHandle[DESTINATION_KEY] = destination.name
    }

    fun selectSession(sessionId: String) {
        selectedSessionId = sessionId
        isNewChat = false
        savedStateHandle[SESSION_KEY] = sessionId
        savedStateHandle[NEW_CHAT_KEY] = false
    }

    fun startNewChat() {
        selectedSessionId = null
        isNewChat = true
        savedStateHandle[SESSION_KEY] = null
        savedStateHandle[NEW_CHAT_KEY] = true
    }

    fun showSessionList() {
        selectedSessionId = null
        isNewChat = false
        savedStateHandle[SESSION_KEY] = null
        savedStateHandle[NEW_CHAT_KEY] = false
    }

    private companion object {
        const val DESTINATION_KEY = "ui.destination"
        const val SESSION_KEY = "ui.session_id"
        const val NEW_CHAT_KEY = "ui.new_chat"
    }
}
