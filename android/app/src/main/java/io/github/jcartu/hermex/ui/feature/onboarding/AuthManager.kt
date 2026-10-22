package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.core.prefs.CustomHeaderValidationException
import io.github.jcartu.hermex.core.prefs.EncryptedCustomHeaderRepository
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.ServerCredentialStore
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.model.HealthResponse
import io.github.jcartu.hermex.model.LoginResponse
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.ServerRegistry
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.sanitizedForStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Port of Swift `AuthAPIClient` (`Auth/AuthManager.swift`): the four calls auth
 * needs. [AuthManager] builds one per probed server, bound to that server's URL
 * and headers, so probing a candidate never touches the active server's client
 * (Swift's `probeClientFactory`, #17).
 */
interface AuthApiClient {
    suspend fun health(): HealthResponse
    suspend fun authStatus(): AuthStatusResponse
    suspend fun login(password: String): LoginResponse
    suspend fun logout(): LoginResponse
}

/** `extension APIClient: AuthAPIClient {}` — a [HermesApi] as the auth client. */
class HermesAuthClient(private val api: HermesApi) : AuthApiClient {
    override suspend fun health(): HealthResponse = api.health()
    override suspend fun authStatus(): AuthStatusResponse = api.authStatus()
    override suspend fun login(password: String): LoginResponse = api.login(password)
    override suspend fun logout(): LoginResponse = api.logout()
}

/**
 * The session cookies as auth needs them — port of the `HTTPCookieStorage`
 * calls in `AuthManager.swift`: clear one server's cookies (by host, #16) or,
 * as the fallback when nothing is active, the whole jar.
 */
interface SessionCookieStore {
    fun clear(serverUrl: String)
    fun clearAll()
}

/**
 * Port of `AuthManager.State`. [LoggedOut] and [LoggedIn] carry the active
 * server's registry entry (Swift carries its URL), so sign-out and session
 * expiry are scoped to that server (#16) and the shell keys the signed-in
 * subtree on `account.id` (`docs/agents/multi-server-state-isolation.md`).
 */
sealed interface AuthState {
    /** No server is active — first run, or after the last server was removed. */
    data object Unconfigured : AuthState

    /** The server is known but its session is not (or no longer) valid. */
    data class LoggedOut(val account: ServerAccount) : AuthState

    /** Signed in (or optimistically restored, like a relaunch on iOS). */
    data class LoggedIn(val account: ServerAccount) : AuthState
}

/** The server the state refers to, if any — mirrors Swift `State.server`. */
val AuthState.account: ServerAccount?
    get() = when (this) {
        is AuthState.Unconfigured -> null
        is AuthState.LoggedOut -> account
        is AuthState.LoggedIn -> account
    }

/** Port of `AuthManager.AddServerOutcome` (#17). */
sealed interface AddServerOutcome {
    data class Added(val account: ServerAccount) : AddServerOutcome

    /** Not an error — the UI reveals the password field and retries. */
    data object NeedsPassword : AddServerOutcome

    data object Failed : AddServerOutcome
}

/**
 * Port of `Auth/AuthManager.swift`: the connect / add / switch / sign-out /
 * remove / expiry state machine over the multi-server [ServerRegistry] (#15,
 * #16, #17, #899).
 *
 * Android wiring, where it differs from the Swift object graph:
 * - The registry (which servers exist, which is active) is the source of truth.
 *   [serverConfig] is kept as a **projection of the active server** (its URL and
 *   webui password): `HermesApi` reads its base URL from it per request, and every
 *   per-server consumer (offline cache keys, drafts, header activation, session
 *   list prefs) keys on `serverConfig.current().baseUrl`, which therefore always
 *   names the active server. Switching rewrites the projection; it is never a
 *   second list of servers.
 * - Per-server webui passwords live in [credentials] (encrypted, keyed by the
 *   account id) — Swift's scoped Keychain entries — so server A's password never
 *   resolves for server B.
 * - Per-server custom headers live in [customHeaders] (encrypted per origin);
 *   entering a server activates its headers in the process-wide store before any
 *   request can be built, the hydrate-before-client ordering of #255/#16.
 * - A probed server gets its own [AuthApiClient] from [clientFactory], bound to
 *   that URL and the headers being tried, so a typo can never bounce the active
 *   server out of its session.
 * - A Hermes server (#899) makes no webui request anywhere here: its sign-in
 *   record is Wave 2's; until one exists it enters [AuthState.LoggedOut] and its
 *   sign-in form shows.
 */
class AuthManager(
    private val clientFactory: (baseUrl: String, headers: List<CustomHeader>) -> AuthApiClient,
    private val serverConfig: ServerConfigRepository,
    private val registry: ServerRegistry,
    private val credentials: ServerCredentialStore,
    private val customHeaders: EncryptedCustomHeaderRepository,
    private val cookies: SessionCookieStore,
    private val scope: CoroutineScope,
    private val logoutTimeoutMillis: Long = 5_000,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Unconfigured)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    /**
     * Observable snapshot of every configured server, mirrored from the registry
     * after each mutation routed through this manager so the Settings list
     * updates reactively (#17).
     */
    private val _servers = MutableStateFlow(registry.servers)
    val servers: StateFlow<List<ServerAccount>> = _servers.asStateFlow()

    /** The active server's id (its normalized URL string), or null when unconfigured. */
    val activeServerID: String? get() = _state.value.account?.id

    /** The active server's registry entry, or null when unconfigured. */
    val activeServer: ServerAccount? get() = activeServerID?.let { id -> _servers.value.firstOrNull { it.id == id } }

    /** What `serverUrl` is. A URL missing from the registry reads as webui, the only kind before #899. */
    fun kind(of: String): ServerKind = _servers.value.firstOrNull { it.id == of }?.kind ?: ServerKind.WEBUI

    /** The headers saved for [account], prefilling the connect and Settings editors. */
    fun savedCustomHeaders(account: ServerAccount): List<CustomHeader> = customHeaders.snapshotFor(account.urlString)

    /** Re-reads the registry into the observable [servers] snapshot. */
    fun refreshServers() {
        _servers.value = registry.servers
    }

    /**
     * Port of `restoreSavedServer()`. A registry with an active server enters it
     * (optimistically `.loggedIn` for webui; a stale cookie is demoted by the
     * first request's 401 via [handleApiError]). Otherwise the legacy single
     * server (`server.base_url` + its encrypted password) is migrated into the
     * registry as its first, active entry (#15) — lossless and idempotent: an
     * already-registered id is only re-activated, its identity is seeded on
     * first insert only, and its password is copied only when the per-server
     * entry is still empty.
     */
    suspend fun restoreSavedServer() {
        registry.activeServer?.let { active ->
            refreshServers()
            enterActiveServer(active)
            return
        }

        val legacy = serverConfig.current()
        if (legacy.baseUrl.isBlank()) {
            refreshServers()
            _state.value = AuthState.Unconfigured
            return
        }
        val id = runCatching { normalizedServerUrl(legacy.baseUrl) }.getOrDefault(legacy.baseUrl.trim())
        val account = registry.activate(id)
        if (legacy.password.isNotEmpty() && credentials.password(id).isEmpty()) {
            credentials.save(id, legacy.password)
        }
        refreshServers()
        enterActiveServer(account)
    }

    /**
     * Probes [serverUrlString] without persisting anything: normalize → GET
     * /health (must report `ok`) → GET /api/auth/status, through a client bound
     * to [headers] so the calls already traverse the proxy (#255). Throws [ApiError].
     */
    suspend fun testConnection(
        serverUrlString: String,
        headers: List<CustomHeader> = emptyList(),
    ): AuthStatusResponse {
        val serverUrl = normalizedServerUrl(serverUrlString)
        return probe(clientFactory(serverUrl, headers.sanitizedForStorage()))
    }

    /**
     * The onboarding connect flow, port of `configure(...)`: normalize → health →
     * auth status → (password login when the server requires it and hasn't
     * already signed this client in, #3) → persist the password and the headers
     * that reached the server under the server's own scope → activate →
     * [AuthState.LoggedIn]. Failures set [lastErrorMessage] and change nothing.
     */
    suspend fun configure(
        serverUrlString: String,
        password: String,
        headers: List<CustomHeader> = emptyList(),
    ) {
        _lastErrorMessage.value = null

        try {
            val serverUrl = normalizedServerUrl(serverUrlString)
            // A webui server and a Hermes server never share a URL (#899).
            if (kind(of = serverUrl) == ServerKind.HERMES) {
                _lastErrorMessage.value = ALREADY_CONFIGURED_MESSAGE
                return
            }
            val sentHeaders = headers.sanitizedForStorage()
            val client = clientFactory(serverUrl, sentHeaders)
            val authStatus = probe(client)

            unsupportedSignInMessage(authStatus)?.let { message ->
                _lastErrorMessage.value = message
                return
            }

            // `logged_in` means the server already authenticated this client —
            // trusted-header mode does it at the proxy — so there is nothing to
            // log in with and the server is saved as signed in (#3).
            if (authStatus.authEnabled == true && !authStatus.isAlreadySignedIn) {
                if (password.isEmpty()) {
                    _lastErrorMessage.value = EMPTY_PASSWORD_MESSAGE
                    return
                }
                val loginResponse = client.login(password)
                if (loginResponse.ok != true) {
                    _state.value = AuthState.LoggedOut(transientAccount(serverUrl))
                    _lastErrorMessage.value = ApiError.Unauthorized.message
                    return
                }
            }

            // Persist only on success: the password and the headers that reached
            // this server, each under its own scoped entry (#16).
            credentials.save(serverUrl, password)
            customHeaders.replaceFor(serverUrl, sentHeaders)
            val account = registry.activate(serverUrl)
            refreshServers()
            enterActiveServer(account)
        } catch (error: ApiError) {
            _lastErrorMessage.value = error.message
        } catch (error: CustomHeaderValidationException) {
            _lastErrorMessage.value = error.message
        }
    }

    /**
     * Adds (and switches to) another server from the in-app add-server flow,
     * port of `addServer(...)` (#17). Unlike [configure] this NEVER touches the
     * active server until the add fully succeeds: the new server is probed
     * through a client bound to its own headers, so a typo or an unreachable
     * server can't bounce the user out of a working session, and the active
     * server's in-flight requests never pick up the new server's headers.
     * Rejects a URL that's already configured (no duplicate normalized URLs).
     */
    suspend fun addServer(
        serverUrlString: String,
        password: String,
        headers: List<CustomHeader> = emptyList(),
    ): AddServerOutcome {
        _lastErrorMessage.value = null

        val serverUrl = try {
            normalizedServerUrl(serverUrlString)
        } catch (error: ApiError) {
            _lastErrorMessage.value = error.message
            return AddServerOutcome.Failed
        }

        if (registry.servers.any { it.id == serverUrl }) {
            _lastErrorMessage.value = ALREADY_CONFIGURED_MESSAGE
            return AddServerOutcome.Failed
        }

        val newHeaders = headers.sanitizedForStorage()
        val client = clientFactory(serverUrl, newHeaders)

        try {
            val authStatus = probe(client)

            unsupportedSignInMessage(authStatus)?.let { message ->
                _lastErrorMessage.value = message
                return AddServerOutcome.Failed
            }

            if (authStatus.authEnabled == true && !authStatus.isAlreadySignedIn) {
                if (password.isEmpty()) return AddServerOutcome.NeedsPassword

                val loginResponse = client.login(password)
                if (loginResponse.ok != true) {
                    _lastErrorMessage.value = ApiError.Unauthorized.message
                    return AddServerOutcome.Failed
                }
            }

            // Commit only now that the add succeeded: the new server becomes
            // active, its password and headers persist under its own scope (#16);
            // the previous active server's entries were never disturbed.
            credentials.save(serverUrl, password)
            customHeaders.replaceFor(serverUrl, newHeaders)
            val account = registry.activate(serverUrl)
            refreshServers()
            enterActiveServer(account)
            return AddServerOutcome.Added(account)
        } catch (error: ApiError) {
            _lastErrorMessage.value = error.message
            return AddServerOutcome.Failed
        } catch (error: CustomHeaderValidationException) {
            _lastErrorMessage.value = error.message
            return AddServerOutcome.Failed
        }
    }

    /**
     * Signs out of the **active** server, port of `signOut()`: best-effort
     * server-side logout bounded by [logoutTimeoutMillis] (an unreachable or
     * slow server never blocks local sign-out — #249), then drops it locally and
     * auto-switches to the next remaining server — returning to onboarding only
     * when none remain (#17). A Hermes server stays configured and shows its
     * sign-in form (#899).
     */
    suspend fun signOut() {
        val active = _state.value.account
        if (active == null) {
            // Defensive: nothing is active. Safe full reset to onboarding.
            clearLocalAuth()
            _state.value = AuthState.Unconfigured
            return
        }

        if (active.kind == ServerKind.HERMES) {
            _lastErrorMessage.value = null
            _state.value = AuthState.LoggedOut(active)
            return
        }

        if (_state.value is AuthState.LoggedIn) {
            attemptBestEffortServerLogout(active)
        }
        advanceAfterRemoving(active)
    }

    /**
     * Removes a configured server, port of `removeServer(_:)`. When it's the
     * active one this behaves like [signOut] (best-effort server logout +
     * auto-switch / onboarding). A non-active server is just dropped locally —
     * its registry row, scoped headers, password and cookies — leaving the
     * active server's auth untouched (#17).
     */
    suspend fun removeServer(account: ServerAccount) {
        val isActive = _state.value.account?.id == account.id
        if (isActive) {
            if (_state.value is AuthState.LoggedIn && account.kind == ServerKind.WEBUI) {
                attemptBestEffortServerLogout(account)
            }
            advanceAfterRemoving(account)
        } else {
            clearLocalArtifacts(account)
            registry.remove(account.id)
            refreshServers()
        }
    }

    /**
     * Switches the active server to an already-registered one (the Settings
     * switcher, port of `switchActiveServer(to:)`). Mirrors the cold-launch path:
     * set it active and enter it — a webui server optimistically `.loggedIn` with
     * its scoped headers, a stale cookie demoted by the first request's 401 —
     * so no extra round-trip here.
     */
    suspend fun switchActiveServer(account: ServerAccount) {
        if (account.id == _state.value.account?.id) return
        registry.setActive(account.id) ?: return
        refreshServers()
        _lastErrorMessage.value = null
        enterActiveServer(account)
    }

    /**
     * Updates a server's per-server identity, port of `updateServerIdentity`.
     * When [account] is the active server the registry mirrors the new identity
     * into the global identity defaults, so the avatar / accent update live (#17).
     */
    fun updateServerIdentity(
        account: ServerAccount,
        displayName: String,
        initials: String,
        headerLogoColorHex: String,
    ) {
        registry.update(
            account.copy(displayName = displayName, initials = initials, headerLogoColorHex = headerLogoColorHex),
        )
        refreshServers()
        val updated = _servers.value.firstOrNull { it.id == account.id } ?: return
        when (val current = _state.value) {
            is AuthState.LoggedIn -> if (current.account.id == updated.id) _state.value = AuthState.LoggedIn(updated)
            is AuthState.LoggedOut -> if (current.account.id == updated.id) _state.value = AuthState.LoggedOut(updated)
            AuthState.Unconfigured -> Unit
        }
    }

    /**
     * Port of `handleAPIError`: a 401 demotes the active webui server to
     * [AuthState.LoggedOut], clearing only its cookies and keeping its saved
     * URL, password and headers so re-login is a one-field affair (#16, #255).
     * A webui 401 is never about a Hermes server's own sign-in (#899). Any other
     * error is ignored here.
     */
    fun handleApiError(error: Throwable) {
        if (error !is ApiError.Unauthorized) return
        val active = _state.value.account
        if (active != null && active.kind == ServerKind.HERMES) return

        _lastErrorMessage.value = SESSION_EXPIRED_MESSAGE
        when (val current = _state.value) {
            is AuthState.LoggedIn -> {
                cookies.clear(current.account.urlString)
                _state.value = AuthState.LoggedOut(current.account)
            }
            is AuthState.LoggedOut -> {
                cookies.clear(current.account.urlString)
            }
            is AuthState.Unconfigured -> {
                // Defensive: a 401 with nothing configured. Safe full reset.
                scope.launch { clearLocalAuth() }
            }
        }
    }

    // MARK: - Internals

    /**
     * Enters [account], already active in the registry, port of
     * `enterActiveServer`: a webui server's headers are activated before the
     * projection changes (so the first request carries them) and it enters
     * `.loggedIn` optimistically; a Hermes server sends no webui headers and,
     * with no saved sign-in record yet, shows its sign-in form.
     */
    private suspend fun enterActiveServer(account: ServerAccount) {
        if (account.kind == ServerKind.HERMES) {
            customHeaders.activate("")
            serverConfig.save(account.urlString, "")
            _state.value = AuthState.LoggedOut(account)
            return
        }
        customHeaders.activate(account.urlString)
        serverConfig.save(account.urlString, credentials.password(account.id))
        _state.value = AuthState.LoggedIn(account)
    }

    /**
     * Drops the active server locally + from the registry, then auto-switches to
     * the next remaining server, or returns to onboarding when none remain. The
     * shared core of [signOut] and active-server [removeServer] (#17).
     */
    private suspend fun advanceAfterRemoving(account: ServerAccount) {
        clearLocalArtifacts(account)
        val nextActive = registry.remove(account.id)
        refreshServers()

        if (nextActive != null) {
            _lastErrorMessage.value = null
            enterActiveServer(nextActive)
        } else {
            serverConfig.clear()
            customHeaders.activate("")
            _state.value = AuthState.Unconfigured
        }
    }

    /**
     * Deletes one server's local auth artifacts — its scoped custom headers, its
     * password and (webui only) its cookies — without touching the registry.
     */
    private suspend fun clearLocalArtifacts(account: ServerAccount) {
        runCatching { customHeaders.clearFor(account.urlString) }
        credentials.remove(account.id)
        if (account.kind == ServerKind.WEBUI) cookies.clear(account.urlString)
    }

    /**
     * Port of `clearLocalAuth(for: nil)`: the fallback when there's no active
     * server to scope to (a 401 while unconfigured) — clears the projection,
     * the whole cookie jar and the registry's active selection.
     */
    private suspend fun clearLocalAuth() {
        serverConfig.clear()
        cookies.clearAll()
        registry.forgetActiveServer()
        refreshServers()
        customHeaders.activate("")
    }

    /**
     * Tells the server to end the session, but never lets an unreachable or
     * slow server block local sign-out: best-effort and bounded by
     * [logoutTimeoutMillis]. Runs while the session cookie still exists (#249).
     */
    private suspend fun attemptBestEffortServerLogout(account: ServerAccount) {
        val client = clientFactory(account.urlString, customHeaders.snapshotFor(account.urlString))
        withTimeoutOrNull(logoutTimeoutMillis) {
            runCatching { client.logout() }
        }
    }

    private suspend fun probe(client: AuthApiClient): AuthStatusResponse {
        val health = client.health()
        if (health.status != "ok") {
            throw ApiError.Http(statusCode = 200, body = "Unexpected health status.")
        }
        return client.authStatus()
    }

    /** A not-yet-registered server as a [AuthState.LoggedOut] payload (a rejected first login). */
    private fun transientAccount(serverUrl: String) = ServerAccount(id = serverUrl, urlString = serverUrl)

    companion object {
        /** Shown when a server has auth on but explicitly reports password auth off (#255). */
        const val PASSKEY_ONLY_MESSAGE =
            "This server signs in with passkeys, which Hermex doesn't support yet."

        /** Single sign-on: Hermex cannot run the OIDC redirect flow (#3). */
        const val OIDC_ONLY_MESSAGE =
            "This server signs in with single sign-on, which Hermex doesn't support yet."

        /** Trusted-header mode where the proxy did *not* authenticate this request (#3). */
        const val TRUSTED_AUTH_NOT_SIGNED_IN_MESSAGE =
            "This server signs in through an identity proxy, which didn't authorize this request. " +
                "Open the server in a browser, or check the custom headers."

        const val EMPTY_PASSWORD_MESSAGE = "Enter the server password."

        const val SESSION_EXPIRED_MESSAGE = "Your session expired. Sign in again."

        const val ALREADY_CONFIGURED_MESSAGE = "This server is already configured."

        /**
         * Port of `unsupportedSignInMessage(for:)`: why the app can't complete
         * sign-in on its own, or null when it can — either there's no auth, the
         * server already signed this client in, or a password login is available.
         * Only an explicit `password_auth_enabled == false` counts; a missing
         * value means an older server that doesn't report it (#255, #3).
         */
        fun unsupportedSignInMessage(status: AuthStatusResponse): String? {
            if (status.authEnabled != true || status.isAlreadySignedIn) return null
            if (status.passwordAuthEnabled != false) return null
            if (status.oidcEnabled == true) return OIDC_ONLY_MESSAGE
            if (status.trustedAuthEnabled == true) return TRUSTED_AUTH_NOT_SIGNED_IN_MESSAGE
            return PASSKEY_ONLY_MESSAGE
        }

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
