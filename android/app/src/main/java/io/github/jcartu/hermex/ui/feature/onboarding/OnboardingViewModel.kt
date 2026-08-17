package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of `Features/Onboarding/OnboardingViewModel.swift` plus the pager state
 * `OnboardingView.swift` keeps in `@State` (current page, copy-reminder flow) —
 * hoisted into the ViewModel so it survives fold/unfold and rotation.
 */
data class OnboardingUiState(
    val serverUrl: String = "",
    val password: String = "",
    val authStatus: AuthStatusResponse? = null,
    val connectionMessage: String? = null,
    val errorMessage: String? = null,
    val isWorking: Boolean = false,
    val currentPage: Int = 0,
    val hasCopiedAgentPrompt: Boolean = false,
    val hasBypassedCopyReminder: Boolean = false,
    val isShowingCopyReminder: Boolean = false,
) {
    /**
     * No auth → no password. Passkey-only (auth on, password auth explicitly
     * off) → hide the password field; connect() surfaces the unsupported
     * message instead. Unknown (nil) keeps the "show the field" default.
     */
    val isPasswordRequired: Boolean
        get() {
            if (authStatus?.authEnabled == false) return false
            return authStatus?.passwordAuthEnabled != false
        }

    val canSubmitConnection: Boolean
        get() = serverUrl.isNotBlank()
}

class OnboardingViewModel(
    val authManager: AuthManager,
    savedServer: ServerConfig? = null,
    initialErrorMessage: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            serverUrl = savedServer?.baseUrl.orEmpty(),
            errorMessage = if (savedServer == null) null else initialErrorMessage,
            // A known server means a re-login, not first-run setup: skip the
            // intro pager and land on the connect page with the server filled in.
            currentPage = if (savedServer == null) 0 else OnboardingFlowPolicy.connectPageIndex,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun onAgentPromptCopied() = _uiState.update { it.copy(hasCopiedAgentPrompt = true) }

    fun dismissCopyReminder() = _uiState.update { it.copy(isShowingCopyReminder = false) }

    fun bypassCopyReminderAndAdvance() {
        _uiState.update { it.copy(hasBypassedCopyReminder = true, isShowingCopyReminder = false) }
        advanceToNextPage()
    }

    /** Bottom-bar primary button on the intro pages. */
    fun handlePrimaryAction() {
        val state = _uiState.value
        if (OnboardingFlowPolicy.shouldShowCopyReminder(
                page = state.currentPage,
                hasCopiedAgentPrompt = state.hasCopiedAgentPrompt,
                hasBypassedCopyReminder = state.hasBypassedCopyReminder,
            )
        ) {
            _uiState.update { it.copy(isShowingCopyReminder = true) }
            return
        }

        if (state.currentPage < OnboardingFlowPolicy.connectPageIndex) {
            advanceToNextPage()
        }
    }

    /** Swipe/settle target from the pager. Returns the page to actually show. */
    fun onPageChangeRequested(newPage: Int): Int {
        val state = _uiState.value
        if (OnboardingFlowPolicy.shouldInterceptForwardNavigationFromAgentPrompt(
                fromPage = state.currentPage,
                toPage = newPage,
                hasCopiedAgentPrompt = state.hasCopiedAgentPrompt,
                hasBypassedCopyReminder = state.hasBypassedCopyReminder,
            )
        ) {
            _uiState.update { it.copy(isShowingCopyReminder = true) }
            return state.currentPage
        }
        _uiState.update { it.copy(currentPage = newPage) }
        return newPage
    }

    fun advanceToNextPage() {
        val page = _uiState.value.currentPage
        if (page >= OnboardingFlowPolicy.connectPageIndex) return
        _uiState.update { it.copy(currentPage = page + 1) }
    }

    fun jumpToConnectPage() =
        _uiState.update { it.copy(currentPage = OnboardingFlowPolicy.connectPageIndex) }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(errorMessage = null, connectionMessage = null, isWorking = true)
            }
            try {
                val status = authManager.testConnection(_uiState.value.serverUrl)
                val (message, error) = if (
                    status.authEnabled == true && status.passwordAuthEnabled == false
                ) {
                    null to AuthManager.PASSKEY_ONLY_MESSAGE
                } else if (status.authEnabled == true) {
                    "Connection ok. Password required." to null
                } else {
                    "Connection ok. Password not required." to null
                }
                _uiState.update {
                    it.copy(
                        authStatus = status,
                        connectionMessage = message,
                        errorMessage = error,
                        isWorking = false,
                    )
                }
            } catch (error: ApiError) {
                _uiState.update { it.copy(errorMessage = error.message, isWorking = false) }
            }
        }
    }

    fun connect() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, connectionMessage = null) }

            val state = _uiState.value
            passwordValidationMessage(state.authStatus, state.password)?.let { message ->
                _uiState.update { it.copy(errorMessage = message) }
                return@launch
            }

            _uiState.update { it.copy(isWorking = true) }

            if (_uiState.value.authStatus == null) {
                try {
                    val status = authManager.testConnection(_uiState.value.serverUrl)
                    _uiState.update { it.copy(authStatus = status) }
                } catch (error: ApiError) {
                    _uiState.update { it.copy(errorMessage = error.message, isWorking = false) }
                    return@launch
                }

                val refreshed = _uiState.value
                passwordValidationMessage(refreshed.authStatus, refreshed.password)?.let { message ->
                    _uiState.update { it.copy(errorMessage = message, isWorking = false) }
                    return@launch
                }
            }

            authManager.configure(_uiState.value.serverUrl, _uiState.value.password)
            _uiState.update {
                it.copy(errorMessage = authManager.lastErrorMessage.value, isWorking = false)
            }
        }
    }

    companion object {
        /**
         * Port of `passwordValidationMessage`: a non-empty password is only
         * demanded when the server requires password auth; passkey-only servers
         * fall through so configure() reports the specific unsupported message.
         */
        fun passwordValidationMessage(authStatus: AuthStatusResponse?, password: String): String? {
            if (authStatus?.authEnabled != true) return null
            if (authStatus.passwordAuthEnabled == false) return null
            return if (password.trim().isEmpty()) AuthManager.EMPTY_PASSWORD_MESSAGE else null
        }
    }
}
