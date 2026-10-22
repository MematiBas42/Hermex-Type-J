package io.github.jcartu.hermex.ui.feature.hermes.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.BotConnectionRecords
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotConnectionAdvice
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesAddress
import io.github.jcartu.hermex.net.hermes.HermesBrowserSignIn
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesHeaders
import io.github.jcartu.hermex.net.hermes.HermesSignInMethod
import io.github.jcartu.hermex.net.hermes.HermesStatus
import io.github.jcartu.hermex.net.hermes.HermesStatusProbe
import io.github.jcartu.hermex.net.hermes.HermesStatusProbeFailure
import io.github.jcartu.hermex.net.sanitizedForStorage
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Port of `BotConnectionSetup.HostStatus` and its view copy (`Features/Bots/BotConnectionView.swift`). */
sealed interface HermesHostStatus {
    data object Checking : HermesHostStatus
    data class Reachable(val status: HermesStatus) : HermesHostStatus
    data class Unreachable(val failure: Throwable) : HermesHostStatus
}

/** Port of `BotConnectionSetup`, hoisted across rotation and folding. */
data class HermesConnectionFormState(
    val isServerSignIn: Boolean,
    val address: String,
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val headers: List<CustomHeader> = emptyList(),
    val saved: BotConnection? = null,
    val errorMessage: String? = null,
    val isConnecting: Boolean = false,
    val differentHostAddress: HttpUrl? = null,
    val hostStatus: HermesHostStatus? = null,
    val savedRevision: Int = 0,
    val removed: Boolean = false,
) {
    val signInMethod: HermesSignInMethod get() = when {
        !isServerSignIn -> HermesSignInMethod.PASSWORD
        hostStatus is HermesHostStatus.Reachable -> hostStatus.status.signInMethod
        saved?.oauth != null -> HermesSignInMethod.BROWSER
        saved?.hasNoCredentials == true -> HermesSignInMethod.OPEN
        else -> HermesSignInMethod.PASSWORD
    }
    val sentHeaders: List<CustomHeader> get() = headers.sanitizedForStorage().filter { it.sanitizedValue.isNotEmpty() }
    val headerRejection: String? get() = runCatching {
        HermesHeaders.admit(sentHeaders, signInMethod == HermesSignInMethod.BROWSER)
    }.exceptionOrNull()?.message
    val accessPairWarning: String? get() {
        val names = sentHeaders.map { it.sanitizedName.lowercase() }.toSet()
        return if (("cf-access-client-id" in names) != ("cf-access-client-secret" in names))
            "Cloudflare Access needs both CF-Access-Client-Id and CF-Access-Client-Secret." else null
    }
    val canConnect: Boolean get() = !isConnecting && headerRejection == null && address.isNotBlank() &&
        (signInMethod != HermesSignInMethod.PASSWORD || (username.isNotBlank() && password.isNotEmpty()))
    val offersRemoval: Boolean get() = !isServerSignIn && saved != null
    val offersHostReplacement: Boolean get() = !isConnecting && differentHostAddress != null &&
        differentHostAddress == runCatching { HermesAddress.parse(address) }.getOrNull()
    val liveStatus: HermesStatus? get() = (hostStatus as? HermesHostStatus.Reachable)?.status
}

/**
 * Port of `BotConnectionSetup` (`Features/Bots/BotConnectionView.swift`). Unsaved candidates
 * use their own HTTP/gateway connection; the synchronous encrypted record write is the commit
 * point. Cancellation before it saves nothing. Cleanup after it cannot be cancelled by closing
 * the form. The UUID follows the same install or account, never a replacement host.
 */
class HermesConnectionViewModel(
    val account: ServerAccount,
    private val store: BotConnectionRecords,
    private val auth: AuthManager,
    private val httpClient: OkHttpClient,
    error: String? = null,
    private val probe: suspend (HttpUrl, HermesHeaders) -> Result<HermesStatus> = { address, headers -> HermesStatusProbe(httpClient).check(address, headers) },
    private val verifyProfiles: suspend (HermesConnection) -> Unit = { http ->
        val wire = BotClient(http)
        try {
            wire.connect()
            if (wire.call(HermesCall.ProfilesList(includeSessions = true))["profiles"].list == null) throw BotFailure.Unsupported
        } finally { withContext(NonCancellable) { wire.close() } }
    },
    private val discard: suspend (BotConnection) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(HermesConnectionFormState(account.kind == ServerKind.HERMES,
        if (account.kind == ServerKind.HERMES) account.urlString else "", errorMessage = error))
    val state: StateFlow<HermesConnectionFormState> = _state.asStateFlow()
    private var attempt = 0
    private var statusAttempt = 0
    private var operation: Job? = null
    private var checking: Job? = null
    private var http: HermesConnection? = null

    init {
        try {
            val saved = store.load(account.id)
            _state.update { it.copy(saved = saved, address = if (it.isServerSignIn) account.urlString else saved?.address?.toString().orEmpty(),
                name = saved?.name.orEmpty(), username = saved?.username.orEmpty(), password = saved?.password.orEmpty(), headers = saved?.headers.orEmpty()) }
        } catch (_: Exception) { _state.update { it.copy(errorMessage = "Could not read saved sign-in details.") } }
        checkStatus()
    }

    fun setAddress(value: String) { if (!_state.value.isConnecting && !_state.value.isServerSignIn) _state.update { it.copy(address = value) } }
    fun setName(value: String) { if (!_state.value.isConnecting) _state.update { it.copy(name = value) } }
    fun setUsername(value: String) { if (!_state.value.isConnecting) _state.update { it.copy(username = value) } }
    fun setPassword(value: String) { if (!_state.value.isConnecting) _state.update { it.copy(password = value) } }
    fun setHeaders(value: List<CustomHeader>) { if (!_state.value.isConnecting) _state.update { it.copy(headers = value) } }

    fun checkStatus() {
        val current = _state.value
        val address = current.saved?.address ?: if (current.isServerSignIn) HermesAddress.url(account.urlString) else null
        if (address == null) return
        checking?.cancel()
        val token = ++statusAttempt
        checking = viewModelScope.launch {
            _state.update { it.copy(hostStatus = HermesHostStatus.Checking) }
            val headers = current.saved?.let(HermesHeaders::saved) ?: runCatching { HermesHeaders.admit(current.sentHeaders) }.getOrDefault(HermesHeaders.NONE)
            val result = probe(address, headers)
            if (token != statusAttempt) return@launch
            _state.update { it.copy(hostStatus = result.fold(
                onSuccess = { status -> if (status.authRequired == null) HermesHostStatus.Unreachable(HermesStatusProbeFailure.NotHermes) else HermesHostStatus.Reachable(status) },
                onFailure = { failure -> HermesHostStatus.Unreachable(failure) })) }
        }
    }

    fun connect(browser: HermesBrowserSignIn.Presenter, replacingHost: Boolean = false) {
        if (!_state.value.canConnect) return
        val previous = _state.value
        val token = ++attempt
        _state.update { it.copy(isConnecting = true, errorMessage = null, differentHostAddress = null) }
        operation = viewModelScope.launch {
            var address: HttpUrl? = null
            var candidate: HermesConnection? = null
            try {
                address = HermesAddress.parse(if (previous.isServerSignIn) account.urlString else previous.address)
                val browserMethod = previous.signInMethod == HermesSignInMethod.BROWSER
                val open = previous.signInMethod == HermesSignInMethod.OPEN
                val headers = HermesHeaders.admit(previous.sentHeaders, browserMethod).values.takeIf { it.isNotEmpty() }
                val expected = if (replacingHost || previous.saved?.address != address) null else previous.saved?.installID
                candidate = HermesConnection(BotConnection(name = previous.name.ifEmpty { address.host }, address = address,
                    username = if (open || browserMethod) "" else previous.username.trim(), password = if (open || browserMethod) "" else previous.password,
                    installID = expected, headers = headers), httpClient)
                http = candidate
                if (browserMethod) HermesBrowserSignIn.signIn(candidate, browser) else {
                    candidate.signIn()
                    verifyProfiles(candidate)
                }
                currentCoroutineContext().ensureActive()
                if (token != attempt) return@launch
                val signedIn = candidate.connection
                val old = previous.saved
                val sameInstall = candidate.serverInstallID != null && candidate.serverInstallID == old?.installID
                val sameAccount = old != null && old.address == address && if (browserMethod)
                    old.oauth?.provider == signedIn.oauth?.provider && old.oauth?.userID == signedIn.oauth?.userID
                    else old.username == signedIn.username
                val kept = if (!replacingHost && (sameInstall || sameAccount)) old else null
                val record = signedIn.copy(id = kept?.id ?: signedIn.id, hermesVersion = candidate.serverVersion,
                    installID = candidate.serverInstallID ?: kept?.installID)
                store.save(record, account.id)
                // After this synchronous commit, leaving the sheet cannot undo success or its cleanup.
                withContext(NonCancellable) {
                    _state.update { it.copy(saved = record) }
                    if (old != null && old.id != record.id) runCatching { discard(old) }
                    auth.hermesSignInSaved(account.id)
                    _state.update { it.copy(savedRevision = it.savedRevision + 1) }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (token == attempt) _state.update { it.copy(
                    errorMessage = address?.let { target -> BotConnectionAdvice.message(error, target) } ?: error.message,
                    differentHostAddress = if (error === BotFailure.DifferentHost) address else null) }
            } finally {
                candidate?.retire()
                if (http === candidate) http = null
                if (token == attempt) _state.update { it.copy(isConnecting = false) }
            }
        }
    }

    fun remove() {
        val previous = _state.value
        if (!previous.offersRemoval || previous.isConnecting) return
        operation = viewModelScope.launch {
            try {
                store.remove(account.id)
                withContext(NonCancellable) {
                    previous.saved?.let { discard(it) }
                    _state.update { it.copy(saved = null, removed = true) }
                }
            } catch (_: Exception) { _state.update { it.copy(errorMessage = "Could not remove saved sign-in details.") } }
        }
    }

    fun cancel() {
        attempt++
        statusAttempt++
        operation?.cancel()
        checking?.cancel()
        http?.retire()
        http = null
        _state.update { it.copy(isConnecting = false) }
    }
    override fun onCleared() { cancel(); super.onCleared() }
}
