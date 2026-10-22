package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.sanitizedForStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of `OnboardingViewModel.ConnectionMode` (#900): how this phone reaches
 * the host. It changes only the placeholder, the help and which header rows
 * show: it is not saved, and no request depends on it. The scheme still comes
 * from the address parser.
 */
enum class ConnectionMode(
    val title: String,
    val subtitle: String,
    val help: String,
    /** An example address, never localized. */
    val placeholder: String,
) {
    SAME_WIFI(
        title = "Same Wi-Fi",
        subtitle = "The host is on this network",
        help = "Enter the host’s local address and port. This phone must be on the same Wi-Fi as the host.",
        placeholder = "http://192.168.1.5:9119",
    ),
    PRIVATE_NETWORK(
        title = "Private network",
        subtitle = "Tailscale, NetBird…",
        help = "Enter the host’s name or IP on Tailscale, NetBird or another private network. Keep that app connected on this phone.",
        placeholder = "http://my-mac.tailnet-name.ts.net:9119",
    ),
    CLOUDFLARE_TUNNEL(
        title = "Cloudflare Tunnel",
        subtitle = "Your tunnel’s HTTPS address",
        help = "Enter your tunnel’s HTTPS address. If Cloudflare Access protects it, add your service token below.",
        placeholder = "https://hermes.example.com",
    ),
}

/** Port of `OnboardingViewModel.Entry`: where the form runs, which picks its webui path. */
enum class ConnectFormEntry { ONBOARDING, ADD_SERVER }

/**
 * Port of the connect form's observable state (`OnboardingViewModel.swift`)
 * plus the pager state `OnboardingView.swift` keeps in `@State` (current page,
 * shown prompt, copy-reminder flow) — hoisted so it survives fold/unfold and
 * rotation.
 */
data class OnboardingUiState(
    val entry: ConnectFormEntry = ConnectFormEntry.ONBOARDING,
    val serverUrl: String = "",
    val password: String = "",
    val customHeaders: List<CustomHeader> = emptyList(),
    val connectionMode: ConnectionMode = ConnectionMode.PRIVATE_NETWORK,
    val authStatus: AuthStatusResponse? = null,
    /**
     * What answered at the typed address: `.webui` once the webui path got an
     * answer. Null until then, and again after an edit changes the address or
     * the headers sent. (A Hermes dashboard's detection is Wave 2's.)
     */
    val detectedKind: ServerKind? = null,
    /** Add Server's webui answered that it needs a password, so its field shows. */
    val webuiNeedsPassword: Boolean = false,
    val connectionMessage: String? = null,
    val errorMessage: String? = null,
    val isWorking: Boolean = false,
    /**
     * True while an operation owns the form: views disable the URL/password
     * fields, header editor, and Return-key submission so a side effect that has
     * already run cannot be superseded by mid-flight edits (PR #294 re-gate).
     */
    val isConnectionLocked: Boolean = false,
    /** The server Add Server just added, for the sheet to close on. */
    val addedAccount: ServerAccount? = null,
    val currentPage: Int = 0,
    val shownPrompt: OnboardingSetupPrompt = OnboardingFlowPolicy.initialSetupPrompt,
    val copiedPrompts: Set<OnboardingSetupPrompt> = emptySet(),
    val bypassedPrompts: Set<OnboardingSetupPrompt> = emptySet(),
    val isShowingCopyReminder: Boolean = false,
) {
    /**
     * No auth → no password. Already signed in (trusted-header proxy) → no
     * password either. Passkey/OIDC-only → hide the field; connect() surfaces
     * the specific unsupported message instead. Unknown (null) keeps the "show
     * the field" default.
     */
    val isPasswordRequired: Boolean
        get() {
            if (authStatus?.authEnabled == false) return false
            if (authStatus?.isAlreadySignedIn == true) return false
            return authStatus?.passwordAuthEnabled != false
        }

    /** The webui's password field: onboarding shows it until a webui says it needs none, Add Server once a webui asks for one. */
    val showsPasswordField: Boolean
        get() = if (entry == ConnectFormEntry.ONBOARDING) isPasswordRequired else webuiNeedsPassword

    /** Off without an address. */
    val canSubmit: Boolean
        get() = serverUrl.isNotBlank()

    /** The root Connect will use, for the "Will connect to" line. Null while the text doesn't parse. */
    val addressPreview: String?
        get() = runCatching { AuthManager.normalizedServerUrl(serverUrl) }.getOrNull()
}

/**
 * Port of `Features/Onboarding/OnboardingViewModel.swift`: the connect form's
 * model, shared by onboarding's Connect page and Settings → Add Server (#900).
 * Onboarding's path is `AuthManager.configure`; Add Server's is `addServer`,
 * which leaves the active server alone until the new one succeeds.
 */
class OnboardingViewModel(
    val authManager: AuthManager,
    entry: ConnectFormEntry = ConnectFormEntry.ONBOARDING,
    savedServer: ServerAccount? = null,
    savedHeaders: List<CustomHeader> = emptyList(),
    initialErrorMessage: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            entry = entry,
            serverUrl = savedServer?.urlString.orEmpty(),
            customHeaders = savedHeaders,
            errorMessage = if (savedServer == null) null else initialErrorMessage,
            // A known server means a re-login, not first-run setup: skip the
            // intro pager and land on the connect page with the server filled in.
            currentPage = if (savedServer == null) 0 else OnboardingFlowPolicy.connectPageIndex,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    // Identity + generation of the probe that produced `authStatus` or `detectedKind` (#285).
    private var probedConnectionIdentity: String? = null
    private var operationGeneration = 0
    private var isSyncingAccessRows = false

    // MARK: - Edits

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value) }
        invalidateProbedAuthStatusIfNeeded()
    }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun onCustomHeadersChange(headers: List<CustomHeader>) {
        _uiState.update { it.copy(customHeaders = headers) }
        invalidateProbedAuthStatusIfNeeded()
    }

    /** Cloudflare Tunnel opens Access's two header rows, empty; leaving it removes those rows that are still empty. */
    fun setConnectionMode(mode: ConnectionMode) {
        val old = _uiState.value.connectionMode
        if (old == mode) return
        isSyncingAccessRows = true
        try {
            _uiState.update { state ->
                val headers = when {
                    mode == ConnectionMode.CLOUDFLARE_TUNNEL -> withAccessRows(state.customHeaders)
                    old == ConnectionMode.CLOUDFLARE_TUNNEL -> state.customHeaders.filterNot { header ->
                        header.sanitizedValue.isEmpty() && isAccessHeaderName(header.sanitizedName)
                    }
                    else -> state.customHeaders
                }
                state.copy(connectionMode = mode, customHeaders = headers)
            }
        } finally {
            isSyncingAccessRows = false
        }
    }

    // MARK: - Pager

    fun setShownPrompt(prompt: OnboardingSetupPrompt) = _uiState.update { it.copy(shownPrompt = prompt) }

    fun onPromptCopied(prompt: OnboardingSetupPrompt) =
        _uiState.update { it.copy(copiedPrompts = it.copiedPrompts + prompt) }

    fun dismissCopyReminder() = _uiState.update { it.copy(isShowingCopyReminder = false) }

    fun bypassCopyReminderAndAdvance() {
        _uiState.update {
            it.copy(bypassedPrompts = it.bypassedPrompts + it.shownPrompt, isShowingCopyReminder = false)
        }
        advanceToNextPage()
    }

    /** Bottom-bar primary button on the intro pages. */
    fun handlePrimaryAction() {
        val state = _uiState.value
        if (OnboardingFlowPolicy.shouldShowCopyReminder(
                page = state.currentPage,
                shownPrompt = state.shownPrompt,
                copiedPrompts = state.copiedPrompts,
                bypassedPrompts = state.bypassedPrompts,
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
                shownPrompt = state.shownPrompt,
                copiedPrompts = state.copiedPrompts,
                bypassedPrompts = state.bypassedPrompts,
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

    // MARK: - Connection identity (issue #285)

    /** The header rows the form sends and saves: a name and a value, so Cloudflare Tunnel's empty Access rows never go out (#900). */
    private val sentHeaders: List<CustomHeader>
        get() = _uiState.value.customHeaders.sanitizedForStorage().filter { it.sanitizedValue.isNotEmpty() }

    /**
     * Effective headers exactly as the request path would apply them:
     * applicable rows only, case-insensitive names, array order preserved with
     * last-write-wins for duplicate names. This is the credential set that
     * actually reaches the server, so reordering duplicate names or adding or
     * removing a newline-broken row must change it.
     */
    private fun effectiveHeaderMap(): List<Pair<String, String>> {
        val map = ArrayList<Pair<String, String>>()
        for (header in sentHeaders) {
            if (!header.isApplicable) continue
            val lowered = header.sanitizedName.lowercase()
            val index = map.indexOfFirst { it.first == lowered }
            if (index >= 0) map[index] = lowered to header.sanitizedValue else map += lowered to header.sanitizedValue
        }
        return map
    }

    internal fun currentConnectionIdentity(): String {
        val serverUrl = _uiState.value.serverUrl
        val urlPart = runCatching { AuthManager.normalizedServerUrl(serverUrl).lowercase() }
            .getOrDefault(serverUrl.trim().lowercase())
        // Escape delimiters so distinct header sets cannot collide, then sort canonical keys for stable serialization.
        val headerPart = effectiveHeaderMap()
            .map { (name, value) -> "${escapeForIdentity(name)}:${escapeForIdentity(value)}" }
            .sorted()
            .joinToString("|")
        return "$urlPart|$headerPart"
    }

    private fun invalidateProbedAuthStatusIfNeeded() {
        // A mode switch only opens or drops empty Access rows, which send nothing,
        // so it keeps the probe and any banner.
        if (isSyncingAccessRows) return
        val probed = probedConnectionIdentity
        if (probed == null) {
            // No probe yet, but an inherited banner (re-login error) still has no
            // owner — clear it on any edit so stale copy can't survive.
            if (_uiState.value.errorMessage != null || _uiState.value.connectionMessage != null) {
                _uiState.update { it.copy(errorMessage = null, connectionMessage = null) }
            }
            return
        }
        if (currentConnectionIdentity() != probed) {
            forgetProbe()
            _uiState.update { it.copy(connectionMessage = null, errorMessage = null) }
        }
    }

    /** Drops what the last probe found, which no longer describes the typed address. */
    private fun forgetProbe() {
        probedConnectionIdentity = null
        _uiState.update { it.copy(authStatus = null, detectedKind = null) }
    }

    /** Begins a tracked async operation and returns its generation token used to fence settlement against newer overlapping operations. */
    private fun beginOperation(): Int {
        operationGeneration += 1
        return operationGeneration
    }

    private fun endOperation(token: Int) {
        if (token == operationGeneration) {
            _uiState.update { it.copy(isWorking = false, isConnectionLocked = false) }
        }
    }

    // MARK: - Actions

    fun testConnection() {
        if (_uiState.value.isConnectionLocked) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(errorMessage = null, connectionMessage = null, isWorking = true, isConnectionLocked = true)
            }
            val token = beginOperation()
            val identityAtStart = currentConnectionIdentity()
            try {
                val status = authManager.testConnection(_uiState.value.serverUrl, sentHeaders)
                if (token != operationGeneration || identityAtStart != currentConnectionIdentity()) return@launch
                probedConnectionIdentity = identityAtStart
                val unsupported = AuthManager.unsupportedSignInMessage(status)
                val message = when {
                    unsupported != null -> null
                    status.isAlreadySignedIn -> "Connection ok. Already signed in by this server."
                    status.authEnabled == true -> "Connection ok. Password required."
                    else -> "Connection ok. Password not required."
                }
                _uiState.update {
                    it.copy(
                        authStatus = status,
                        detectedKind = ServerKind.WEBUI,
                        connectionMessage = message,
                        errorMessage = unsupported,
                    )
                }
            } catch (error: ApiError) {
                if (token != operationGeneration || identityAtStart != currentConnectionIdentity()) return@launch
                // A failed re-probe invalidates the prior successful status: the old
                // capability result no longer describes this identity.
                forgetProbe()
                _uiState.update { it.copy(errorMessage = error.message) }
            } finally {
                endOperation(token)
            }
        }
    }

    /** Connect in onboarding, Add in Settings. */
    fun connect() {
        if (_uiState.value.isConnectionLocked) return
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, connectionMessage = null) }

            val state = _uiState.value
            if (state.entry == ConnectFormEntry.ONBOARDING) {
                passwordValidationMessage(state.authStatus, state.password)?.let { message ->
                    _uiState.update { it.copy(errorMessage = message) }
                    return@launch
                }
            }

            // Inputs stay frozen across probe AND configure: AuthManager.configure
            // persists credentials and activates the captured URL/headers as a side
            // effect, so accepting edits mid-operation would let the old server be
            // configured under a form now showing a different one (PR #294 re-gate).
            _uiState.update { it.copy(isWorking = true, isConnectionLocked = true) }
            val token = beginOperation()
            try {
                when (state.entry) {
                    ConnectFormEntry.ONBOARDING -> configureWebui(token)
                    ConnectFormEntry.ADD_SERVER -> addWebuiServer(token)
                }
            } finally {
                endOperation(token)
            }
        }
    }

    /** Onboarding's webui path: probe unless a probe for these inputs already answered, check the password, then `configure`. */
    private suspend fun configureWebui(token: Int) {
        if (_uiState.value.authStatus == null) {
            val identityAtStart = currentConnectionIdentity()
            try {
                val status = authManager.testConnection(_uiState.value.serverUrl, sentHeaders)
                if (token != operationGeneration || identityAtStart != currentConnectionIdentity()) return
                probedConnectionIdentity = identityAtStart
                _uiState.update { it.copy(authStatus = status, detectedKind = ServerKind.WEBUI) }
            } catch (error: ApiError) {
                if (token != operationGeneration || identityAtStart != currentConnectionIdentity()) return
                forgetProbe()
                _uiState.update { it.copy(errorMessage = error.message) }
                return
            }

            val refreshed = _uiState.value
            passwordValidationMessage(refreshed.authStatus, refreshed.password)?.let { message ->
                _uiState.update { it.copy(errorMessage = message) }
                return
            }
        } else if (probedConnectionIdentity == null) {
            probedConnectionIdentity = currentConnectionIdentity()
        }

        // Fence the side effect: capture the identity configure was launched for
        // and refuse to publish its outcome under different inputs.
        val configureIdentity = currentConnectionIdentity()
        authManager.configure(_uiState.value.serverUrl, _uiState.value.password, sentHeaders)
        if (token != operationGeneration || configureIdentity != currentConnectionIdentity()) return
        _uiState.update { it.copy(errorMessage = authManager.lastErrorMessage.value) }
    }

    /** Add Server's webui path: `addServer` reveals the password field when the server needs one, and the next Add sends it. */
    private suspend fun addWebuiServer(token: Int) {
        val identityAtStart = currentConnectionIdentity()
        val outcome = authManager.addServer(_uiState.value.serverUrl, _uiState.value.password, sentHeaders)
        if (token != operationGeneration || identityAtStart != currentConnectionIdentity()) return
        when (outcome) {
            AddServerOutcome.NeedsPassword -> {
                probedConnectionIdentity = identityAtStart
                _uiState.update { it.copy(webuiNeedsPassword = true, detectedKind = ServerKind.WEBUI) }
            }
            AddServerOutcome.Failed -> _uiState.update { it.copy(errorMessage = authManager.lastErrorMessage.value) }
            is AddServerOutcome.Added -> _uiState.update { it.copy(addedAccount = outcome.account) }
        }
    }

    companion object {
        /** Cloudflare Access's service-token headers, which Cloudflare Tunnel opens empty. */
        val accessHeaderNames: List<String> = listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")

        private fun isAccessHeaderName(name: String): Boolean =
            accessHeaderNames.any { it.equals(name, ignoreCase = true) }

        /** `headers` plus an empty row for each Access header it lacks. */
        fun withAccessRows(headers: List<CustomHeader>): List<CustomHeader> =
            headers + accessHeaderNames
                .filter { name -> headers.none { it.sanitizedName.equals(name, ignoreCase = true) } }
                .map { CustomHeader(name = it) }

        private fun escapeForIdentity(raw: String): String =
            raw.replace("%", "%25").replace(":", "%3A").replace("|", "%7C")

        /**
         * Port of `passwordValidationMessage`: a non-empty password is only
         * demanded when the server requires password auth and hasn't already
         * signed this client in; passkey/OIDC-only servers fall through so
         * configure() reports the specific unsupported message (#255, #3).
         */
        fun passwordValidationMessage(authStatus: AuthStatusResponse?, password: String): String? {
            if (authStatus?.authEnabled != true) return null
            if (authStatus.isAlreadySignedIn) return null
            if (authStatus.passwordAuthEnabled == false) return null
            return if (password.trim().isEmpty()) AuthManager.EMPTY_PASSWORD_MESSAGE else null
        }
    }
}
