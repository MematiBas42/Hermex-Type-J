package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Auth lifecycle of the single configured server. Port of `Auth/AuthManager.swift`
 * reduced to the Android v1 single-server model (the iOS multi-server registry,
 * per-server custom-header scoping, and App Intents cache are out of scope).
 */
sealed interface AuthState {
    /** No server saved — first run or after a full sign-out. */
    data object Unconfigured : AuthState

    /** A server is known but the session is not (or no longer) valid. */
    data class LoggedOut(val saved: ServerConfig) : AuthState

    /** Signed in (or optimistically restored from a saved config). */
    data class LoggedIn(val server: ServerConfig) : AuthState
}

/** The server the state refers to, if any — mirrors Swift `State.server`. */
val AuthState.configuredServer: ServerConfig?
    get() = when (this) {
        is AuthState.Unconfigured -> null
        is AuthState.LoggedOut -> saved
        is AuthState.LoggedIn -> server
    }

/**
 * Port of the Swift `AuthManager` connect/sign-out/expiry state machine.
 *
 * Differences forced by the Android networking design: [HermesApi] reads its
 * base URL from the persisted [ServerConfigRepository] per request (there is no
 * per-URL client factory), so probing a candidate server stages its config,
 * runs the probe, and restores the previous config unless the connect fully
 * succeeded. The net effect matches Swift's "persist only on success".
 *
 * [sessionResetter] is wired by the integrator to
 * `PersistentCookieJar.clearAll()` + `ServerConfigRepository.clear()` (a full
 * local sign-out). It is intentionally NOT invoked on session expiry (401):
 * like iOS, expiry keeps the saved server so re-login is a one-field affair.
 */
class AuthManager(
    private val api: HermesApi,
    private val serverConfig: ServerConfigRepository,
    private val sessionResetter: () -> Unit,
    private val logoutTimeoutMillis: Long = 5_000,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Unconfigured)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    /**
     * Mirrors Swift `restoreSavedServer()`: a saved server enters `.loggedIn`
     * optimistically; a stale cookie is demoted to [AuthState.LoggedOut] by the
     * first request's 401 via [handleApiError], exactly like a relaunch on iOS.
     */
    suspend fun restoreSavedServer() {
        val saved = serverConfig.current()
        _state.value = if (saved.baseUrl.isBlank()) {
            AuthState.Unconfigured
        } else {
            AuthState.LoggedIn(saved)
        }
    }

    /**
     * Probes [serverUrlString] without persisting anything: normalize → GET
     * /health (must report `ok`) → GET /api/auth/status. Throws [ApiError].
     */
    suspend fun testConnection(serverUrlString: String): AuthStatusResponse {
        val serverUrl = normalizedServerUrl(serverUrlString)
        val previous = serverConfig.current()
        return try {
            serverConfig.save(serverUrl, previous.password)
            probe()
        } finally {
            restoreConfig(previous)
        }
    }

    /**
     * The onboarding connect flow, port of Swift `configure(...)`:
     * normalize URL → health → auth status → (password login when the server
     * requires it) → persist + [AuthState.LoggedIn]. Failures set
     * [lastErrorMessage] and leave the previously stored config untouched.
     */
    suspend fun configure(serverUrlString: String, password: String) {
        _lastErrorMessage.value = null
        val previous = serverConfig.current()

        try {
            val serverUrl = normalizedServerUrl(serverUrlString)
            serverConfig.save(serverUrl, password)
            val authStatus = probe()

            // Passkey-only: auth is on but the server explicitly reports password
            // auth off. Only an explicit false counts — a missing field means an
            // older server that doesn't report it, so we must fall through to the
            // password path and never block a working password user (#255).
            if (authStatus.authEnabled == true && authStatus.passwordAuthEnabled == false) {
                _lastErrorMessage.value = PASSKEY_ONLY_MESSAGE
                restoreConfig(previous)
                return
            }

            if (authStatus.authEnabled == true) {
                if (password.isEmpty()) {
                    _lastErrorMessage.value = EMPTY_PASSWORD_MESSAGE
                    restoreConfig(previous)
                    return
                }

                val loginResponse = api.login(password)
                if (loginResponse.ok != true) {
                    restoreConfig(previous)
                    _state.value = AuthState.LoggedOut(ServerConfig(serverUrl, ""))
                    _lastErrorMessage.value = ApiError.Unauthorized.message
                    return
                }
            }

            // Persist only on success (already staged above; the save is what
            // makes it stick because no rollback happens on this path).
            _state.value = AuthState.LoggedIn(ServerConfig(serverUrl, password))
        } catch (error: ApiError) {
            restoreConfig(previous)
            _lastErrorMessage.value = error.message
        }
    }

    /**
     * Signs out: best-effort server-side logout bounded by [logoutTimeoutMillis]
     * (an unreachable or slow server never blocks local sign-out — issue #249),
     * then clears local auth via [sessionResetter] and returns to onboarding.
     */
    suspend fun signOut() {
        if (_state.value is AuthState.LoggedIn) {
            withTimeoutOrNull(logoutTimeoutMillis) {
                runCatching { api.logout() }
            }
        }
        sessionResetter()
        _lastErrorMessage.value = null
        _state.value = AuthState.Unconfigured
    }

    /**
     * Port of Swift `handleAPIError`: a 401 anywhere demotes the state to
     * [AuthState.LoggedOut] for the active server, keeping the saved config so
     * re-login is one field. Any other error is ignored here.
     */
    fun handleApiError(error: Throwable) {
        if (error !is ApiError.Unauthorized) return

        _lastErrorMessage.value = SESSION_EXPIRED_MESSAGE
        when (val current = _state.value) {
            is AuthState.LoggedIn -> _state.value = AuthState.LoggedOut(current.server)
            is AuthState.LoggedOut -> _state.value = AuthState.LoggedOut(current.saved)
            is AuthState.Unconfigured -> {
                // Defensive: a 401 with nothing configured. Safe full reset.
                sessionResetter()
            }
        }
    }

    private suspend fun probe(): AuthStatusResponse {
        val health = api.health()
        if (health.status != "ok") {
            throw ApiError.Http(statusCode = 200, body = "Unexpected health status.")
        }
        return api.authStatus()
    }

    private suspend fun restoreConfig(previous: ServerConfig) {
        if (previous.baseUrl.isBlank() && previous.password.isBlank()) {
            serverConfig.clear()
        } else {
            serverConfig.save(previous.baseUrl, previous.password)
        }
    }

    companion object {
        /** Shown when a server has auth on but explicitly reports password auth off (#255). */
        const val PASSKEY_ONLY_MESSAGE =
            "This server signs in with passkeys, which Hermex doesn't support yet."

        const val EMPTY_PASSWORD_MESSAGE = "Enter the server password."

        const val SESSION_EXPIRED_MESSAGE = "Your session expired. Sign in again."

        /**
         * Port of Swift `normalizedServerURL(from:)`: trims, defaults the scheme
         * (plain HTTP only for localhost/127.0.0.1 and Tailscale CGNAT
         * 100.64.0.0/10 hosts), strips path/query/fragment, and drops the
         * `www.` prefix from `www.webui.*` hosts. Throws [ApiError.InvalidServerUrl].
         */
        fun normalizedServerUrl(rawValue: String): String {
            val trimmed = rawValue.trim()
            if (trimmed.isEmpty()) throw ApiError.InvalidServerUrl

            val valueWithScheme =
                if ("://" in trimmed) trimmed else "${defaultScheme(trimmed)}://$trimmed"
            // HttpUrl only parses http/https, matching the Swift scheme guard.
            val url = valueWithScheme.toHttpUrlOrNull() ?: throw ApiError.InvalidServerUrl

            val host = normalizedHost(url.host)
            val portPart =
                if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
            return "${url.scheme}://$host$portPart"
        }

        private fun normalizedHost(host: String): String =
            if (host.lowercase().startsWith("www.webui.")) host.substring(4) else host

        private fun defaultScheme(rawValue: String): String {
            val host = "http://$rawValue".toHttpUrlOrNull()?.host?.lowercase()
                ?: return "https"
            return if (shouldDefaultToPlainHttp(host)) "http" else "https"
        }

        private fun shouldDefaultToPlainHttp(host: String): Boolean {
            if (host == "localhost" || host == "127.0.0.1") return true

            val parts = host.split('.')
            val octets = parts.mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || octets.size != 4 || octets.any { it !in 0..255 }) {
                return false
            }
            return octets[0] == 100 && octets[1] in 64..127
        }
    }
}
