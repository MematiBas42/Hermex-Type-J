package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.core.prefs.BotConnectionRecords
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
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotConnectionAdvice
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.hermes.HermesAddress
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesConnections
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
 * - A Hermes server (#899) makes no webui request anywhere here. Its sign-in is
 *   the [BotConnection] record saved under its own id in [botConnections]
 *   (`bot_connection::<server id>`, the iOS Keychain's shape): with a record it
 *   enters [AuthState.LoggedIn] and its screens sign in through
 *   [hermesConnections]; without one, or after the host refuses the saved
 *   sign-in ([hermesSignInRejected]), it enters [AuthState.LoggedOut] and its own
 *   sign-in form shows. [discardHermesData] drops what this device kept for the
 *   server's Hermes chats (offline rows, drafts) on sign-out and removal.
 */
class AuthManager(
    private val clientFactory: (baseUrl: String, headers: List<CustomHeader>) -> AuthApiClient,
    private val serverConfig: ServerConfigRepository,
    private val registry: ServerRegistry,
    private val credentials: ServerCredentialStore,
    private val customHeaders: EncryptedCustomHeaderRepository,
    private val cookies: SessionCookieStore,
    private val scope: CoroutineScope,
    private val botConnections: BotConnectionRecords,
    private val hermesConnections: HermesConnections = HermesConnections(),
    private val discardHermesData: suspend (server: String, connectionID: String?) -> Unit = { _, _ -> },
    private val logoutTimeoutMillis: Long = 5_000,
) {

    /** Port of the `addHermesServer` refusal: a webui server and a Hermes server never share a URL. */
    class ServerAlreadyConfigured : Exception(ALREADY_CONFIGURED_MESSAGE)

    /** The record write, a sign-in's commit point, failed. */
    class SignInNotSaved(cause: Throwable?) : Exception(COULD_NOT_SAVE_SIGN_IN_MESSAGE, cause)

    /** [hermesSignIn] on a server with no saved sign-in record. */
    class HermesSignInUnavailable : Exception("This Hermes server is signed out. Sign in from its form first.")

    /**
     * A Hermes sign-in a webui server keeps for its Bots, which the connect form offers to copy
     * into a new Hermes server at the same address (#900). Port of `AuthManager.SavedHermesSignIn`.
     */
    data class SavedHermesSignIn(
        /** The webui server that keeps it, named as Settings → Servers names it. */
        val serverName: String,
        val connection: BotConnection,
    )

    private val _state = MutableStateFlow<AuthState>(AuthState.Unconfigured)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Port of `state.didSet`: leaving a server retires its shared Hermes connection at once,
     * so a sign-in finishing afterwards stores nothing and resends nothing.
     */
    private fun setState(next: AuthState) {
        val previous = _state.value.account?.id
        _state.value = next
        if (previous != null && previous != next.account?.id) hermesConnections.retire(previous)
    }

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
            setState(AuthState.Unconfigured)
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
                    setState(AuthState.LoggedOut(transientAccount(serverUrl)))
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
     * when none remain (#17). A Hermes server stays configured: its record and
     * its Hermes data on this device are deleted and its sign-in form shows,
     * with its address (#899).
     */
    suspend fun signOut() {
        val active = _state.value.account
        if (active == null) {
            // Defensive: nothing is active. Safe full reset to onboarding.
            clearLocalAuth()
            setState(AuthState.Unconfigured)
            return
        }

        if (active.kind == ServerKind.HERMES) {
            discardHermesServerData(active.id)
            removeBotConnection(active.id)
            _lastErrorMessage.value = null
            setState(AuthState.LoggedOut(active))
            return
        }

        if (_state.value is AuthState.LoggedIn) {
            attemptBestEffortServerLogout(active)
        }
        discardHermesServerData(active.id)
        advanceAfterRemoving(active)
    }

    /**
     * Removes a configured server, port of `removeServer(_:)`. When it's the
     * active one this behaves like [signOut] (best-effort server logout +
     * auto-switch / onboarding). A non-active server is just dropped locally —
     * its registry row, scoped headers, password, Hermes sign-in record and
     * cookies — leaving the active server's auth untouched (#17). Either way the
     * server's Hermes data on this device (offline rows, drafts) goes first.
     */
    suspend fun removeServer(account: ServerAccount) {
        discardHermesServerData(account.id)
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

    // MARK: - Hermes servers (#899)

    /**
     * Port of `addHermesServer(_:activating:)`: [connection] is a sign-in the connect form
     * or Move to Hermes has already verified, and becomes the server's own record, saved
     * under its address, which is also the server's id. Refuses an address already in the
     * registry with [ServerAlreadyConfigured], as [addServer] does, so a webui server and a
     * Hermes server never share a URL ([replaceWebuiServer] swaps one for the other). With
     * [activate] the server becomes active and enters [AuthState.LoggedIn]; a sign-in opened
     * from Move to Hermes passes `false` and the server is only added (#1266). Returns the
     * registry entry.
     */
    suspend fun addHermesServer(connection: BotConnection, activate: Boolean = true): ServerAccount {
        _lastErrorMessage.value = null
        val server = hermesServerID(connection.address)
        if (registry.servers.any { it.id == server }) {
            _lastErrorMessage.value = ALREADY_CONFIGURED_MESSAGE
            throw ServerAlreadyConfigured()
        }
        try {
            botConnections.save(connection, server)
        } catch (error: Exception) {
            _lastErrorMessage.value = COULD_NOT_SAVE_SIGN_IN_MESSAGE
            throw SignInNotSaved(error)
        }
        if (!activate) {
            val account = registry.add(server, ServerKind.HERMES, connection.hermesVersion)
            refreshServers()
            return account
        }
        val account = registry.activate(server, ServerKind.HERMES, connection.hermesVersion)
        refreshServers()
        // The synchronous record save committed this operation; finishing its projection is not cancellable.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { enterActiveServer(account) }
        return account
    }

    /**
     * Port of `replaceWebuiServer(with:)` (#1027): replaces the webui server saved at
     * [connection]'s address with a Hermes server, for a host that moved from hermes-webui to
     * the dashboard. The webui server goes as [removeServer] takes it, and the Hermes server
     * keeps its name, initials and color. Throws [ServerAlreadyConfigured] unless a webui
     * server is saved there.
     */
    suspend fun replaceWebuiServer(connection: BotConnection): ServerAccount {
        _lastErrorMessage.value = null
        val server = hermesServerID(connection.address)
        val webui = _servers.value.firstOrNull { it.id == server }?.takeIf { it.kind == ServerKind.WEBUI }
        if (webui == null) {
            _lastErrorMessage.value = ALREADY_CONFIGURED_MESSAGE
            throw ServerAlreadyConfigured()
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            removeServer(webui)
            val added = addHermesServer(connection)
            updateServerIdentity(added, webui.displayName, webui.initials, webui.headerLogoColorHex)
            _servers.value.firstOrNull { it.id == added.id } ?: added
        }
    }

    /**
     * Port of `savedHermesSignIns(at:)`: the sign-ins configured webui servers keep for
     * exactly [address], each saved address parsed as the typed one was. Never matched by
     * `install_id`: it comes from the public `/api/status`, so any host could report
     * another's and be offered its saved password.
     */
    fun savedHermesSignIns(address: HttpUrl): List<SavedHermesSignIn> =
        _servers.value.mapNotNull { account ->
            if (account.kind != ServerKind.WEBUI) return@mapNotNull null
            val saved = runCatching { botConnections.load(account.id) }.getOrNull() ?: return@mapNotNull null
            val savedAddress = runCatching { HermesAddress.parse(saved.address.toString()) }.getOrNull()
            if (savedAddress != address) return@mapNotNull null
            val name = account.displayName.ifEmpty { account.urlString.toHttpUrlOrNull()?.host ?: account.urlString }
            SavedHermesSignIn(serverName = name, connection = saved)
        }

    /** The sign-in record saved for [account], or null when it is signed out. */
    fun hermesSignInRecord(account: ServerAccount): BotConnection? =
        runCatching { botConnections.load(account.id) }.getOrNull()

    /**
     * The shared, signed-in connection of a Hermes server: its saved record on
     * [hermesConnections] after one `signIn()`. Throws [HermesSignInUnavailable] when the
     * server has no record, and the sign-in's [BotFailure] when the host refuses it (which
     * also signs the active server out through [hermesSignInRejected]).
     */
    suspend fun hermesSignIn(account: ServerAccount): HermesConnection {
        val saved = hermesSignInRecord(account) ?: throw HermesSignInUnavailable()
        val connection = hermesConnections.connection(saved, account.id)
        connection.signIn()
        return connection
    }

    /**
     * Port of `hermesSignInRejected(server:failure:)`: the host refused the active Hermes
     * server's saved username or password at the login step, or its browser sign-in's refresh
     * token ([failure], from `HermesConnections.onSignInRejected`), including the one silent
     * re-sign-in a signed-in 401 starts. Shows that server's sign-in form, with [failure]'s
     * advice, where no Hermes screen exists to send the refused credential again. Any other
     * server, and a webui server's own Hermes connection, stays as it is.
     */
    fun hermesSignInRejected(server: String, failure: BotFailure = BotFailure.Rejected(401)) {
        val current = _state.value as? AuthState.LoggedIn ?: return
        if (current.account.id != server || kind(of = server) != ServerKind.HERMES) return
        val address = HermesAddress.url(server) ?: return
        _lastErrorMessage.value = BotConnectionAdvice.message(failure, address)
        setState(AuthState.LoggedOut(current.account))
    }

    /**
     * Port of `hermesSignInSaved(server:)`: the Hermes connection form saved [server]'s
     * sign-in. Records the release the host reported, and signs the server back in when it is
     * the active Hermes server waiting on its sign-in form. Token refreshes never call this.
     * A no-op for a webui server.
     */
    fun hermesSignInSaved(server: String) {
        val account = _servers.value.firstOrNull { it.id == server }?.takeIf { it.kind == ServerKind.HERMES } ?: return
        val saved = runCatching { botConnections.load(server) }.getOrNull() ?: return
        var current = account
        if (account.serverVersion != saved.hermesVersion) {
            registry.update(account.copy(serverVersion = saved.hermesVersion))
            refreshServers()
            current = _servers.value.firstOrNull { it.id == server } ?: account
        }
        val state = _state.value
        if (state is AuthState.LoggedOut && state.account.id == server) {
            _lastErrorMessage.value = null
            setState(AuthState.LoggedIn(current))
        }
    }

    /**
     * Port of `hermesServerUpdated(server:to:)` (#1075): an update from Settings installed
     * [version] on the Hermes server [server]. Records it on the server's saved sign-in and
     * its registry entry, where a sign-in would have, and changes nothing else: a server
     * waiting on its sign-in form stays there.
     */
    fun hermesServerUpdated(server: String, version: String) {
        runCatching { botConnections.load(server) }.getOrNull()?.let { saved ->
            if (saved.hermesVersion != version) runCatching { botConnections.save(saved.copy(hermesVersion = version), server) }
        }
        val account = _servers.value.firstOrNull { it.id == server }?.takeIf { it.kind == ServerKind.HERMES } ?: return
        if (account.serverVersion == version) return
        registry.update(account.copy(serverVersion = version))
        refreshServers()
        val state = _state.value
        when {
            state is AuthState.LoggedIn && state.account.id == server -> _servers.value.firstOrNull { it.id == server }?.let { setState(AuthState.LoggedIn(it)) }
            state is AuthState.LoggedOut && state.account.id == server -> _servers.value.firstOrNull { it.id == server }?.let { setState(AuthState.LoggedOut(it)) }
        }
    }

    /** A Hermes server's id: its address as `HermesAddress.parse` normalizes it, else the address as given. */
    private fun hermesServerID(address: HttpUrl): String =
        HermesAddress.serverID(runCatching { HermesAddress.parse(address.toString()) }.getOrDefault(address))

    /** Drops the Hermes data this device kept for [server] (offline rows, drafts), keyed by its current record. */
    private suspend fun discardHermesServerData(server: String) {
        val connectionID = runCatching { botConnections.load(server) }.getOrNull()?.id
        runCatching { discardHermesData(server, connectionID) }
    }

    /** Port of `removeBotConnection(for:)`: deletes [server]'s sign-in record, which also retires its shared connection. */
    private fun removeBotConnection(server: String) {
        runCatching { botConnections.remove(server) }
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
            is AuthState.LoggedIn -> if (current.account.id == updated.id) setState(AuthState.LoggedIn(updated))
            is AuthState.LoggedOut -> if (current.account.id == updated.id) setState(AuthState.LoggedOut(updated))
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
                setState(AuthState.LoggedOut(current.account))
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
     * `.loggedIn` optimistically; a Hermes server sends no webui headers and is
     * signed in only while its own record exists; without one its sign-in form
     * shows (#899).
     */
    private suspend fun enterActiveServer(account: ServerAccount) {
        if (account.kind == ServerKind.HERMES) {
            customHeaders.activate("")
            serverConfig.save(account.urlString, "")
            val saved = runCatching { botConnections.load(account.id) }.getOrNull()
            setState(if (saved == null) AuthState.LoggedOut(account) else AuthState.LoggedIn(account))
            return
        }
        customHeaders.activate(account.urlString)
        serverConfig.save(account.urlString, credentials.password(account.id))
        setState(AuthState.LoggedIn(account))
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
            setState(AuthState.Unconfigured)
        }
    }

    /**
     * Deletes one server's local auth artifacts — its scoped custom headers, its
     * password, its Hermes sign-in record (which retires its shared connection)
     * and (webui only) its cookies — without touching the registry. A Hermes
     * server's sign-in never uses the shared cookie jar, so the cookies of a
     * webui server on the same host stay.
     */
    private suspend fun clearLocalArtifacts(account: ServerAccount) {
        runCatching { customHeaders.clearFor(account.urlString) }
        credentials.remove(account.id)
        removeBotConnection(account.id)
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

        /** The record write that is a Hermes sign-in's commit point failed. */
        const val COULD_NOT_SAVE_SIGN_IN_MESSAGE = "Could not save sign-in details on this device."

        /** A Hermes dashboard answered at a saved webui server's address (#1027); the form offers Replace. */
        const val WEBUI_SAVED_AT_ADDRESS_MESSAGE = "A webui server is saved at this address."

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
