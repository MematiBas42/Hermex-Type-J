package io.github.jcartu.hermex.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.core.prefs.ThinkingAndToolsMode
import io.github.jcartu.hermex.core.security.AppLock
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import io.github.jcartu.hermex.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The pages Settings' index opens (`SettingsView.swift`, #1252): one per
 * configured server, then the Hermex pages that apply on this device to every
 * server, then About.
 */
sealed interface SettingsPage {
    data class Server(val id: String) : SettingsPage
    data object Appearance : SettingsPage
    data object Composer : SettingsPage
    data object Chat : SettingsPage
    data object Notifications : SettingsPage
    data object General : SettingsPage
    data object About : SettingsPage
}

/** A server page's pushed detail: Providers or the Connection Headers editor. */
enum class SettingsDetail { PROVIDERS, CONNECTION_HEADERS }

/**
 * State for the Settings index and its app pages. Local preference values
 * stream in from [SettingsRepository]; the server list mirrors
 * [AuthManager.servers]. Navigation (page, detail, Add Server) lives here so it
 * survives fold/unfold and pane-count changes.
 */
data class SettingsUiState(
    val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
    val servers: List<ServerAccount> = emptyList(),
    val activeServerId: String? = null,
    val appLock: AppLock.State = AppLock.State(),
    val page: SettingsPage? = null,
    val detail: SettingsDetail? = null,
    val isAddingServer: Boolean = false,
) {
    val activeServer: ServerAccount? get() = servers.firstOrNull { it.id == activeServerId }
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    val authManager: AuthManager,
    private val appLock: AppLock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            authManager.servers.collect { servers ->
                _uiState.update { state ->
                    // A page for a server that no longer exists closes itself.
                    val page = state.page
                    val stillOpen = page !is SettingsPage.Server || servers.any { it.id == page.id }
                    state.copy(page = if (stillOpen) page else null, detail = if (stillOpen) state.detail else null, servers = servers)
                }
            }
        }
        viewModelScope.launch {
            authManager.state.collect { _ ->
                _uiState.update { it.copy(activeServerId = authManager.activeServerID) }
            }
        }
        viewModelScope.launch {
            appLock.state.collect { lock -> _uiState.update { it.copy(appLock = lock) } }
        }
    }

    // MARK: Navigation (survives fold/unfold and pane-count changes)

    fun openPage(page: SettingsPage) = _uiState.update { it.copy(page = page, detail = null) }

    fun closePage() = _uiState.update { it.copy(page = null, detail = null) }

    fun openDetail(detail: SettingsDetail) = _uiState.update { it.copy(detail = detail) }

    fun closeDetail() = _uiState.update { it.copy(detail = null) }

    fun openAddServer() = _uiState.update { it.copy(isAddingServer = true) }

    fun dismissAddServer() = _uiState.update { it.copy(isAddingServer = false) }

    // MARK: Appearance

    fun setPalette(palette: Palette) = launchWrite { settingsRepository.setPalette(palette) }

    fun setTheme(theme: SettingsRepository.Theme) = launchWrite { settingsRepository.setTheme(theme) }

    /**
     * The accent is the active server's Header Logo Color (per server, mirrored
     * into the global key by the registry, #17); without an active server the
     * global key is written directly.
     */
    fun setHeaderLogoColor(hex: String) {
        val active = authManager.activeServer
        if (active != null) {
            authManager.updateServerIdentity(active, active.displayName, active.initials, hex)
        } else {
            launchWrite { settingsRepository.setHeaderLogoColor(hex) }
        }
    }

    // MARK: Composer

    fun setDismissKeyboardAfterSend(enabled: Boolean) = launchWrite { settingsRepository.setDismissKeyboardAfterSend(enabled) }

    fun setShowsFilesButton(visible: Boolean) = launchWrite { settingsRepository.setShowsFilesButton(visible) }

    fun setShowsGitActions(visible: Boolean) = launchWrite { settingsRepository.setShowsGitActions(visible) }

    // MARK: Chat

    fun setThinkingAndToolsMode(mode: ThinkingAndToolsMode) = launchWrite { settingsRepository.setThinkingAndToolsMode(mode) }

    fun setFoldsSettledTurns(enabled: Boolean) = launchWrite { settingsRepository.setFoldsSettledTurns(enabled) }

    fun setReadCompletedResponseFromStart(enabled: Boolean) =
        launchWrite { settingsRepository.setReadCompletedResponseFromStart(enabled) }

    fun setHidesAttachmentPaths(enabled: Boolean) = launchWrite { settingsRepository.setHidesAttachmentPaths(enabled) }

    fun setShowsAssistantTurnTimestamps(enabled: Boolean) =
        launchWrite { settingsRepository.setShowsAssistantTurnTimestamps(enabled) }

    fun setShowsResponseSpeed(enabled: Boolean) = launchWrite { settingsRepository.setShowsResponseSpeed(enabled) }

    fun setWrapsCodeBlockLines(enabled: Boolean) = launchWrite { settingsRepository.setWrapsCodeBlockLines(enabled) }

    fun setRtlChatLayoutEnabled(enabled: Boolean) = launchWrite { settingsRepository.setRtlChatLayoutEnabled(enabled) }

    // MARK: Notifications

    fun setNotifyReplies(enabled: Boolean) = launchWrite { settingsRepository.setNotifyReplies(enabled) }

    fun setNotifyApprovals(enabled: Boolean) = launchWrite { settingsRepository.setNotifyApprovals(enabled) }

    fun setNotifyQuestions(enabled: Boolean) = launchWrite { settingsRepository.setNotifyQuestions(enabled) }

    fun setNotifyFailedRuns(enabled: Boolean) = launchWrite { settingsRepository.setNotifyFailedRuns(enabled) }

    // MARK: General

    fun setHapticsEnabled(enabled: Boolean) = launchWrite { settingsRepository.setHapticsEnabled(enabled) }

    /** The app lock toggle: `AppLock` authenticates before it changes, so the switch moves only once the device says yes. */
    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { appLock.setEnabled(enabled) }
    }

    fun refreshAppLockCapability() = appLock.refreshCapability()

    // MARK: Sessions list (server page, Main Page card)

    fun setShowsSessionRowStates(enabled: Boolean) = launchWrite { settingsRepository.setShowsSessionRowStates(enabled) }

    fun setShowsTasksEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsTasksEntry(visible) }

    fun setShowsKanbanEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsKanbanEntry(visible) }

    fun setShowsSkillsEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsSkillsEntry(visible) }

    fun setShowsMemoryEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsMemoryEntry(visible) }

    fun setShowsInsightsEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsInsightsEntry(visible) }

    fun setShowsActiveProfileEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsActiveProfileEntry(visible) }

    fun setShowsProjectsEntry(visible: Boolean) = launchWrite { settingsRepository.setShowsProjectsEntry(visible) }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
