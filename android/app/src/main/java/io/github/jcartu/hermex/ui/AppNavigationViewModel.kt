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

    /**
     * Pending new-chat route (#875): set by `startNewChat(projectId, profileName)`
     * from the sessions list so the created chat joins the filtered project and
     * uses the list's selected profile; null for deep-link/share new chats.
     * Consumed by ChatPane on creation.
     */
    var newChatProjectId by mutableStateOf(savedStateHandle.get<String>(NEW_CHAT_PROJECT_KEY))
        private set
    var newChatProfileName by mutableStateOf(savedStateHandle.get<String>(NEW_CHAT_PROFILE_KEY))
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
        newChatProjectId = null
        newChatProfileName = null
        savedStateHandle[SESSION_KEY] = sessionId
        savedStateHandle[NEW_CHAT_KEY] = false
        savedStateHandle[NEW_CHAT_PROJECT_KEY] = null
        savedStateHandle[NEW_CHAT_PROFILE_KEY] = null
    }

    fun startNewChat(projectId: String? = null, profileName: String? = null) {
        selectedSessionId = null
        isNewChat = true
        newChatProjectId = projectId
        newChatProfileName = profileName
        savedStateHandle[SESSION_KEY] = null
        savedStateHandle[NEW_CHAT_KEY] = true
        savedStateHandle[NEW_CHAT_PROJECT_KEY] = projectId
        savedStateHandle[NEW_CHAT_PROFILE_KEY] = profileName
    }

    fun showSessionList() {
        selectedSessionId = null
        isNewChat = false
        newChatProjectId = null
        newChatProfileName = null
        savedStateHandle[SESSION_KEY] = null
        savedStateHandle[NEW_CHAT_KEY] = false
        savedStateHandle[NEW_CHAT_PROJECT_KEY] = null
        savedStateHandle[NEW_CHAT_PROFILE_KEY] = null
    }

    /** Routes to More → Settings (the session-list header avatar tap, #283). */
    fun openSettings() {
        selectDestination(HermexDestination.MORE)
        pendingMoreRoute = "settings"
        savedStateHandle[MORE_ROUTE_KEY] = "settings"
    }

    /** Consumes the "settings" More-route after MoreRoute applied it. */
    fun consumeMoreRoute() {
        pendingMoreRoute = null
        savedStateHandle[MORE_ROUTE_KEY] = null
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
        const val NEW_CHAT_PROJECT_KEY = "ui.new_chat_project_id"
        const val NEW_CHAT_PROFILE_KEY = "ui.new_chat_profile"
        const val KANBAN_BOARD_KEY = "ui.kanban_board"
        const val KANBAN_CARD_KEY = "ui.kanban_card"
        const val MORE_ROUTE_KEY = "ui.more_route"
        const val GIT_SESSION_KEY = "ui.git_session"
    }
}
