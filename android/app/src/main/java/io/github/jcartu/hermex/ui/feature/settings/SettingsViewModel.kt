package io.github.jcartu.hermex.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.ui.feature.models.DefaultProfileSelection
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Settings screen. Local preference values stream in from
 * [SettingsRepository]/[VisibilitySettings]; server-derived values follow the
 * load flow of `SettingsView.loadServerSettings()` (version → default model →
 * default profile, each failure isolated). The model/profile pickers come from
 * `ui/feature/models` (ModelPickerSheet / ProfilePickerSheet); this ViewModel
 * only persists the default-model choice and mirrors the results.
 */
data class SettingsUiState(
    val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
    val visibility: VisibilitySettings.State = VisibilitySettings.State(),
    val serverBaseUrl: String = "",
    val isLoadingServerSettings: Boolean = false,
    val serverVersion: String? = null,
    val serverSettingsError: String? = null,
    val isLoadingDefaultModel: Boolean = false,
    val defaultModel: String? = null,
    val isSavingDefaultModel: Boolean = false,
    val defaultModelSaveError: String? = null,
    val isLoadingDefaultProfile: Boolean = false,
    val defaultProfileName: String? = null,
    val defaultProfileDisplayName: String? = null,
    val isConfirmingSignOut: Boolean = false,
    val isSigningOut: Boolean = false,
    val isShowingProviders: Boolean = false,
    val isShowingModelPicker: Boolean = false,
    val isShowingProfilePicker: Boolean = false,
) {
    /** Port of `defaultModelLabel`. */
    val defaultModelLabel: String
        get() = when {
            isSavingDefaultModel -> "Saving"
            isLoadingDefaultModel -> "Loading"
            defaultModel.isNullOrEmpty() -> "Not set"
            else -> defaultModel
        }

    /** Port of `defaultProfileLabel`. */
    val defaultProfileLabel: String
        get() = when {
            isLoadingDefaultProfile -> "Loading"
            !defaultProfileDisplayName.isNullOrEmpty() -> defaultProfileDisplayName
            defaultProfileName.isNullOrEmpty() -> "Not set"
            defaultProfileName == "default" -> "Default"
            else -> defaultProfileName
        }

    /**
     * Port of `serverStatusPill`: "Connected" only when the latest load
     * actually succeeded — a stale version must not mask a now-failed load.
     */
    val serverStatusLabel: String
        get() = when {
            isLoadingServerSettings -> "Loading"
            serverSettingsError == null && serverVersion != null -> "Connected"
            else -> serverSettingsError ?: "Unknown"
        }

    val isServerStatusHealthy: Boolean
        get() = !isLoadingServerSettings && serverSettingsError == null && serverVersion != null
}

class SettingsViewModel(
    private val api: HermesApi,
    private val settingsRepository: SettingsRepository,
    private val visibilitySettings: VisibilitySettings,
    private val serverConfig: ServerConfigRepository,
    val authManager: AuthManager,
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
            visibilitySettings.state.collect { visibility ->
                _uiState.update { it.copy(visibility = visibility) }
            }
        }
        viewModelScope.launch {
            serverConfig.config.collect { config ->
                _uiState.update { it.copy(serverBaseUrl = config.baseUrl) }
            }
        }
        viewModelScope.launch {
            // Restore first so a 401 during the initial load demotes the
            // correct (restored) auth state instead of racing it.
            authManager.restoreSavedServer()
            load()
        }
    }

    // MARK: Server-derived info (port of loadServerSettings)

    fun loadServerSettings() {
        if (_uiState.value.isLoadingServerSettings) return
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _uiState.update {
            it.copy(
                isLoadingServerSettings = true,
                isLoadingDefaultModel = true,
                isLoadingDefaultProfile = true,
                serverSettingsError = null,
            )
        }

        try {
            val settings = api.settings()
            _uiState.update {
                it.copy(
                    serverVersion = settings.webuiVersion,
                    serverSettingsError = if (settings.webuiVersion == null) "Unknown" else null,
                    isLoadingServerSettings = false,
                )
            }
        } catch (error: ApiError) {
            authManager.handleApiError(error)
            _uiState.update {
                it.copy(serverSettingsError = "Unavailable", isLoadingServerSettings = false)
            }
        }

        // Non-fatal: default model is optional info.
        val defaultModel = runCatching { api.models().defaultModel }.getOrNull()
        _uiState.update { it.copy(defaultModel = defaultModel, isLoadingDefaultModel = false) }

        // Non-fatal: default profile is optional info.
        val profiles = runCatching { api.profiles() }.getOrNull()
        val profileName = profiles?.effectiveDefaultProfileName
        _uiState.update {
            it.copy(
                defaultProfileName = profileName,
                defaultProfileDisplayName = profiles?.displayName(profileName),
                isLoadingDefaultProfile = false,
            )
        }
    }

    // MARK: Local preferences

    fun setTheme(theme: SettingsRepository.Theme) =
        launchWrite { settingsRepository.setTheme(theme) }

    fun setHeaderLogoColor(hex: String) =
        launchWrite { settingsRepository.setHeaderLogoColor(hex) }

    fun setHapticsEnabled(enabled: Boolean) =
        launchWrite { settingsRepository.setHapticsEnabled(enabled) }

    fun setShowsThinkingAndToolCards(enabled: Boolean) =
        launchWrite { settingsRepository.setShowsThinkingAndToolCards(enabled) }

    fun setThinkingCardsStartExpanded(enabled: Boolean) =
        launchWrite { settingsRepository.setThinkingCardsStartExpanded(enabled) }

    fun setToolCardsStartExpanded(enabled: Boolean) =
        launchWrite { settingsRepository.setToolCardsStartExpanded(enabled) }

    fun setHidesAttachmentPaths(enabled: Boolean) =
        launchWrite { settingsRepository.setHidesAttachmentPaths(enabled) }

    fun setShowsAssistantTurnTimestamps(enabled: Boolean) =
        launchWrite { settingsRepository.setShowsAssistantTurnTimestamps(enabled) }

    fun setShowsResponseSpeed(enabled: Boolean) =
        launchWrite { settingsRepository.setShowsResponseSpeed(enabled) }

    fun setWrapsCodeBlockLines(enabled: Boolean) =
        launchWrite { settingsRepository.setWrapsCodeBlockLines(enabled) }

    fun setRtlChatLayoutEnabled(enabled: Boolean) =
        launchWrite { settingsRepository.setRtlChatLayoutEnabled(enabled) }

    fun setSectionVisible(section: VisibilitySettings.Section, visible: Boolean) =
        launchWrite { visibilitySettings.setVisible(section, visible) }

    // MARK: Pickers & detail navigation (survives fold/unfold and pane-count changes)

    fun openProviders() = _uiState.update { it.copy(isShowingProviders = true) }

    fun closeProviders() = _uiState.update { it.copy(isShowingProviders = false) }

    fun openModelPicker() =
        _uiState.update { it.copy(isShowingModelPicker = true, defaultModelSaveError = null) }

    fun dismissModelPicker() = _uiState.update { it.copy(isShowingModelPicker = false) }

    fun openProfilePicker() = _uiState.update { it.copy(isShowingProfilePicker = true) }

    fun dismissProfilePicker() = _uiState.update { it.copy(isShowingProfilePicker = false) }

    /**
     * Persists a model chosen in the shared ModelPickerSheet as the server's
     * default model, port of `DefaultModelPickerView.save`: only an `ok: true`
     * response commits the label; anything else surfaces an inline error.
     */
    fun saveDefaultModel(model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingDefaultModel = true, defaultModelSaveError = null) }
            try {
                val response = api.saveDefaultModel(trimmed)
                _uiState.update {
                    if (response.ok == true) {
                        it.copy(defaultModel = trimmed, isSavingDefaultModel = false)
                    } else {
                        it.copy(
                            defaultModelSaveError = "The server did not confirm the change.",
                            isSavingDefaultModel = false,
                        )
                    }
                }
            } catch (error: ApiError) {
                _uiState.update {
                    it.copy(defaultModelSaveError = error.message, isSavingDefaultModel = false)
                }
            }
        }
    }

    /** Mirrors a switch performed by the shared ProfilePickerSheet. */
    fun onDefaultProfileSaved(selection: DefaultProfileSelection) {
        _uiState.update { state ->
            state.copy(
                defaultProfileName = selection.name,
                defaultProfileDisplayName = selection.displayName,
                defaultModel = selection.defaultModel?.takeIf { it.isNotEmpty() }
                    ?: state.defaultModel,
                isShowingProfilePicker = false,
            )
        }
    }

    // MARK: Sign out

    fun requestSignOut() = _uiState.update { it.copy(isConfirmingSignOut = true) }

    fun cancelSignOut() = _uiState.update { it.copy(isConfirmingSignOut = false) }

    fun confirmSignOut() {
        if (_uiState.value.isSigningOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirmingSignOut = false, isSigningOut = true) }
            // Best-effort server logout + local reset; the app shell observes the
            // cleared ServerConfig and returns to onboarding.
            authManager.signOut()
            _uiState.update { it.copy(isSigningOut = false) }
        }
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
