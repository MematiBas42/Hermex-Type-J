package io.github.jcartu.hermex.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.ui.feature.models.DefaultProfileSelection
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One server's page state (`SettingsServerPage.swift` + `WebuiServerSettingsModel.swift`,
 * #1252): its identity edits, the active webui server's status/version/default
 * model/default profile (loaded from it, each failure isolated), and the ways to
 * switch to it, sign out of it, remove it, or clear its offline cache. Another
 * server's page shows only what this device saved; a Hermes server's page makes
 * no webui request (#899).
 */
data class ServerPageUiState(
    val account: ServerAccount? = null,
    val isActive: Boolean = false,
    val hasOtherServers: Boolean = false,
    val isSignedIn: Boolean = false,
    val displayName: String = "",
    val initials: String = "",
    val colorHex: String = HeaderLogoColor.defaultHex,
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
    val isShowingModelPicker: Boolean = false,
    val isShowingProfilePicker: Boolean = false,
    val isConfirmingSignOut: Boolean = false,
    val isSigningOut: Boolean = false,
    val isConfirmingRemove: Boolean = false,
    val isRemoving: Boolean = false,
    val isConfirmingClearCache: Boolean = false,
    val isClearingCache: Boolean = false,
    val cacheStatusMessage: String? = null,
) {
    val kind: ServerKind get() = account?.kind ?: ServerKind.WEBUI

    /** The active webui server is the one kind whose page loads settings. */
    val loadsFromServer: Boolean get() = isActive && kind == ServerKind.WEBUI

    val hostFallback: String get() = account?.let(ServerIdentity::hostFallback).orEmpty()

    val title: String get() = displayName.ifEmpty { hostFallback }

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

    /** Port of `serverStatusPill`: "Connected" only when the latest load actually succeeded. */
    val serverStatusLabel: String
        get() = when {
            !isActive -> "Inactive"
            isLoadingServerSettings -> "Loading"
            serverSettingsError == null && serverVersion != null -> "Connected"
            else -> serverSettingsError ?: "Unknown"
        }

    val isServerStatusHealthy: Boolean
        get() = isActive && !isLoadingServerSettings && serverSettingsError == null && serverVersion != null

    /**
     * Whether this page's remove button signs out of the active webui server,
     * which also removes it. An active Hermes server is removed under that name
     * instead, because its Sign Out keeps the server and shows its sign-in form (#899).
     */
    val signsOut: Boolean get() = isActive && kind == ServerKind.WEBUI

    val removeButtonTitle: String get() = if (signsOut) "Sign Out of This Server" else "Remove Server"

    val removeAlertTitle: String get() = if (signsOut) "Sign out of this server?" else "Remove this server?"

    val removeFootnote: String
        get() = if (signsOut) "Signs out and removes this server from this device." else "Removes this server from this device."

    val removeAlertMessage: String
        get() = when {
            signsOut && hasOtherServers -> "You'll switch to another configured server. Sign in again to use this one."
            signsOut -> "You'll return to onboarding and need the server URL and password to sign back in."
            isActive && hasOtherServers ->
                "Removes this server, its saved sign-in and its data on this device, then switches to another configured server."
            isActive -> "Removes this server, its saved sign-in and its data on this device, then returns to onboarding."
            else -> "This removes the server and its saved settings on this device. Your active server is unaffected."
        }
}

class ServerPageViewModel(
    private val accountId: String,
    private val api: HermesApi,
    private val authManager: AuthManager,
    private val cacheStore: CacheStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerPageUiState())
    val uiState: StateFlow<ServerPageUiState> = _uiState.asStateFlow()

    private var didSeedIdentity = false
    private var didLoad = false

    init {
        viewModelScope.launch {
            authManager.servers.collect { servers ->
                val account = servers.firstOrNull { it.id == accountId }
                _uiState.update { state ->
                    state.copy(
                        account = account,
                        hasOtherServers = servers.size > 1,
                        displayName = if (didSeedIdentity) state.displayName else account?.displayName.orEmpty(),
                        initials = if (didSeedIdentity) state.initials else account?.initials.orEmpty(),
                        colorHex = if (didSeedIdentity) state.colorHex else account?.headerLogoColorHex ?: HeaderLogoColor.defaultHex,
                    )
                }
                if (account != null) didSeedIdentity = true
                loadIfNeeded()
            }
        }
        viewModelScope.launch {
            authManager.state.collect { state ->
                _uiState.update {
                    it.copy(
                        isActive = authManager.activeServerID == accountId,
                        isSignedIn = state is AuthState.LoggedIn && state.account.id == accountId,
                    )
                }
                loadIfNeeded()
            }
        }
    }

    // MARK: Server-derived info (port of WebuiServerSettingsModel.load)

    private fun loadIfNeeded() {
        if (didLoad || !_uiState.value.loadsFromServer) return
        didLoad = true
        viewModelScope.launch { load() }
    }

    fun reload() {
        if (_uiState.value.isLoadingServerSettings || !_uiState.value.loadsFromServer) return
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
            _uiState.update { it.copy(serverSettingsError = "Unavailable", isLoadingServerSettings = false) }
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

    // MARK: Identity (persisted on every edit; the registry mirrors the active server's live)

    fun setDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value) }
        persistIdentity()
    }

    fun setInitials(value: String) {
        _uiState.update { it.copy(initials = ServerIdentity.normalizedInitials(value)) }
        persistIdentity()
    }

    fun setColorHex(value: String) {
        _uiState.update { it.copy(colorHex = value) }
        persistIdentity()
    }

    private fun persistIdentity() {
        val state = _uiState.value
        val account = state.account ?: return
        authManager.updateServerIdentity(account, state.displayName, state.initials, state.colorHex)
    }

    // MARK: Defaults (pickers from ui/feature/models)

    fun openModelPicker() = _uiState.update { it.copy(isShowingModelPicker = true, defaultModelSaveError = null) }

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
                        it.copy(defaultModelSaveError = "The server did not confirm the change.", isSavingDefaultModel = false)
                    }
                }
            } catch (error: ApiError) {
                _uiState.update { it.copy(defaultModelSaveError = error.message, isSavingDefaultModel = false) }
            }
        }
    }

    /** Mirrors a switch performed by the shared ProfilePickerSheet. */
    fun onDefaultProfileSaved(selection: DefaultProfileSelection) {
        _uiState.update { state ->
            state.copy(
                defaultProfileName = selection.name,
                defaultProfileDisplayName = selection.displayName,
                defaultModel = selection.defaultModel?.takeIf { it.isNotEmpty() } ?: state.defaultModel,
                isShowingProfilePicker = false,
            )
        }
    }

    // MARK: Switch / sign out / remove

    fun switchToThisServer() {
        val account = _uiState.value.account ?: return
        viewModelScope.launch { authManager.switchActiveServer(account) }
    }

    fun requestSignOut() = _uiState.update { it.copy(isConfirmingSignOut = true) }

    fun cancelSignOut() = _uiState.update { it.copy(isConfirmingSignOut = false) }

    /** Sign Out of This Server for an active Hermes server: deletes the saved sign-in; the server stays (#899). */
    fun confirmSignOut() {
        if (_uiState.value.isSigningOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirmingSignOut = false, isSigningOut = true) }
            authManager.signOut()
            _uiState.update { it.copy(isSigningOut = false) }
        }
    }

    fun requestRemove() = _uiState.update { it.copy(isConfirmingRemove = true) }

    fun cancelRemove() = _uiState.update { it.copy(isConfirmingRemove = false) }

    /**
     * Port of `remove()`: purges this server's offline cache before removing
     * it (best-effort; the cache is server-keyed, so a leftover row can never
     * surface as another server's content), then signs out of / removes it.
     */
    fun confirmRemove() {
        val state = _uiState.value
        val account = state.account ?: return
        if (state.isRemoving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirmingRemove = false, isRemoving = true) }
            runCatching { cacheStore.clearCache(account.urlString) }
            if (state.signsOut) authManager.signOut() else authManager.removeServer(account)
            _uiState.update { it.copy(isRemoving = false) }
        }
    }

    // MARK: Offline data

    fun requestClearCache() = _uiState.update { it.copy(isConfirmingClearCache = true) }

    fun cancelClearCache() = _uiState.update { it.copy(isConfirmingClearCache = false) }

    /** Scoped to this server only, so clearing one server's cache never wipes another's offline data (#18). */
    fun confirmClearCache() {
        val account = _uiState.value.account ?: return
        if (_uiState.value.isClearingCache) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirmingClearCache = false, isClearingCache = true) }
            val message = runCatching { cacheStore.clearCache(account.urlString) }
                .map { "This server's offline cache was cleared." }
                .getOrDefault("Could not clear offline cache.")
            _uiState.update { it.copy(isClearingCache = false, cacheStatusMessage = message) }
        }
    }
}
