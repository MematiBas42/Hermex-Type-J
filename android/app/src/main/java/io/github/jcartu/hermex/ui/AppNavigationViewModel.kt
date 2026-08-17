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

    var kanbanBoardSlug by mutableStateOf(savedStateHandle.get<String>(KANBAN_BOARD_KEY))
        private set
    var kanbanCardId by mutableStateOf(savedStateHandle.get<String>(KANBAN_CARD_KEY))
        private set
    var pendingMoreRoute by mutableStateOf(savedStateHandle.get<String>(MORE_ROUTE_KEY))
        private set
    var gitSessionId by mutableStateOf(savedStateHandle.get<String>(GIT_SESSION_KEY))
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

    fun openKanban(boardSlug: String?, cardId: String?) {
        selectDestination(HermexDestination.KANBAN)
        kanbanBoardSlug = boardSlug
        kanbanCardId = cardId
        savedStateHandle[KANBAN_BOARD_KEY] = boardSlug
        savedStateHandle[KANBAN_CARD_KEY] = cardId
    }

    fun consumeKanbanTarget() {
        kanbanBoardSlug = null
        kanbanCardId = null
        savedStateHandle[KANBAN_BOARD_KEY] = null
        savedStateHandle[KANBAN_CARD_KEY] = null
    }

    fun openGit(sessionId: String?) {
        selectDestination(HermexDestination.MORE)
        pendingMoreRoute = "git"
        gitSessionId = sessionId
        savedStateHandle[MORE_ROUTE_KEY] = "git"
        savedStateHandle[GIT_SESSION_KEY] = sessionId
    }

    fun consumeGitTarget() {
        pendingMoreRoute = null
        gitSessionId = null
        savedStateHandle[MORE_ROUTE_KEY] = null
        savedStateHandle[GIT_SESSION_KEY] = null
    }

    private companion object {
        const val DESTINATION_KEY = "ui.destination"
        const val SESSION_KEY = "ui.session_id"
        const val NEW_CHAT_KEY = "ui.new_chat"
        const val KANBAN_BOARD_KEY = "ui.kanban_board"
        const val KANBAN_CARD_KEY = "ui.kanban_card"
        const val MORE_ROUTE_KEY = "ui.more_route"
        const val GIT_SESSION_KEY = "ui.git_session"
    }
}
