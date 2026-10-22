package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of `HermesConnection` (`Networking/Hermes/HermesConnection.swift`): one direct-Hermes
 * connection: one ephemeral cookie jar, one sign-in, the path every Bot HTTP request and
 * gateway upgrade is sent through, and the one gateway socket its Bot screens share
 * ([gateway]). [HermesConnections] gives every consumer of a server's saved connection the
 * same instance, so they sign in once and share one socket. Setup and dev auto-login probe
 * unsaved credentials on their own.
 *
 * Sign-in is single-flight: it reads the public `/api/status`, refuses a release older than
 * [HermesCompatibility.MINIMUM_VERSION] and checks the install identity before the password
 * goes out, then verifies the identity the host returns. A password connection signs in with
 * its username and password, and the host's session cookie authenticates what follows. A
 * browser sign-in's connection (`BotConnection.oauth`, #708) trades its refresh token for an
 * access token instead, held in memory only and sent as `Authorization: Bearer`; the host
 * rotates the refresh token each time, and the new one is saved ([onRefreshTokenRotated])
 * before the access token is used. A dashboard with no sign-in (`HermesSignInMethod.OPEN`,
 * #1314) is signed in to with the session token its page carries, sent as
 * `X-Hermes-Session-Token`; a host restart replaces it, which the 401 below recovers from.
 * Cancelling one waiting consumer never cancels it. A signed-in request the host answers with
 * 401 signs in again once, sharing that sign-in with any other consumer's 401, and is resent.
 * Read-only HTTP requests can recover stale pooled connections. Mutation requests and
 * WebSocket tickets never replay on transport failure. Failures leave sign-in unchanged;
 * after [retire], every call and late reply throws [BotFailure.Stale] and the gateway ends.
 *
 * Every method runs on [HermesActor.dispatcher]; the network suspends there rather than
 * blocking it.
 */
class HermesConnection(
    connection: BotConnection,
    httpClient: OkHttpClient = OkHttpClient(),
    gatewayOptions: HermesGateway.Options = HermesGateway.Options(),
) {
    enum class Deadline {
        /** 15 seconds per request and 30 overall, like the gateway's own calls. */
        STANDARD,

        /**
         * 120 and 180: installing a plugin clones a repository on the host, a restart takes
         * the gateway down and back up, a Task's Run Now answers once the run has finished
         * (#1041), Listen's speech can install the host's TTS engine (#1072), and a session
         * export or import carries every message (#1048, #1051).
         */
        PROVISIONING,
    }

    /** One signed-in reply, whatever its status, for routes whose refusals carry the host's reason. */
    class Reply(val body: ByteArray, val status: Int) {
        operator fun component1(): ByteArray = body
        operator fun component2(): Int = status

        /** The body as JSON, or null when it isn't. */
        val json: BotJSON? get() = BotJSON.parseOrNull(body)
    }

    @Volatile
    var connection: BotConnection = connection
        private set

    /** `version` from the last sign-in's `/api/status`, read before the auth gate; null when omitted. */
    @Volatile
    var serverVersion: String? = null
        private set

    /** `install_id` from the same read; null when omitted. */
    @Volatile
    var serverInstallID: String? = null
        private set

    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val attached = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Gateway methods the host answered -32601 (method not found) on this connection, so a
     * control one chat found missing stays off in every chat on it. A new connection starts empty.
     */
    val unavailableMethods: Set<String> get() = unavailable.toSet()

    /**
     * Every runtime a `session.resume` on this connection reached, or a `session.branch` made
     * (#1051), and no `session.close` here ended (#1048). It lasts as long as this connection,
     * past any one socket, as the host's runtimes do.
     */
    val attachedRuntimes: Set<String> get() = attached.toSet()

    private val headers: HermesHeaders = HermesHeaders.saved(connection)
    private val address: HttpUrl = connection.address

    /** The standard-deadline client. The gateway socket opens on it, with its cookies. */
    val client: OkHttpClient

    /** Shares [client]'s cookie jar; only its deadlines differ. */
    val provisioningClient: OkHttpClient

    /** [client] without read or call timeouts, for the sockets, whose silence the gateway polices. */
    val socketClient: OkHttpClient

    private val readClient: OkHttpClient
    private val provisioningReadClient: OkHttpClient

    /** The gateway socket every Bot screen on this connection shares. */
    val gateway: HermesGateway by lazy { HermesGateway(this, gatewayOptions) }

    private val scope = CoroutineScope(SupervisorJob() + HermesActor.dispatcher)

    /** Lives past retirement: a refresh in flight finishes so its rotated token is saved. */
    private val refreshScope = CoroutineScope(SupervisorJob() + HermesActor.dispatcher)

    private var isSignedIn = false

    /** What every signed-in request carries besides the cookie jar. */
    private sealed class Credential {
        /** A browser sign-in's access token from the last refresh, sent as `Authorization: Bearer`. */
        data class AccessToken(val token: String) : Credential()

        /** An open dashboard's session token from its page, sent as `X-Hermes-Session-Token`. */
        data class PageToken(val token: String) : Credential()
    }

    private var credential: Credential? = null

    /** Counts sign-ins, so a 401 can tell whether another consumer has already recovered. */
    private var epoch = 0
    private var signInTask: Deferred<Unit>? = null
    private val platformNoteChecks = HashMap<String, Deferred<Boolean>>()

    @Volatile
    var isRetired: Boolean = false
        private set

    /**
     * Called with `Rejected(401)` when the login step answers 401, with [BotFailure.SignInChanged]
     * when the saved sign-in no longer matches the host's, and with [BotFailure.SignInExpired]
     * when the host refuses a browser sign-in's refresh token, before the failure reaches any
     * consumer, including the re-sign-in a signed-in 401 starts. [HermesConnections] sets it.
     */
    @Volatile
    var onLoginRejected: ((BotFailure) -> Unit)? = null

    /**
     * Called with the refresh token the host rotated the sent one into, before the new access
     * token is used, even when this connection was retired meanwhile. [HermesConnections] saves it.
     */
    @Volatile
    var onRefreshTokenRotated: ((String) -> Unit)? = null

    /**
     * Asked before each refresh for a refresh token saved since this connection last saw one,
     * to send instead of its own; null keeps its own. A read that throws fails the sign-in
     * before any token is spent.
     */
    @Volatile
    var savedRefreshToken: (suspend () -> String?)? = null

    /** The refresh in flight, which retiring this connection never cancels. */
    @Volatile
    var refreshInFlight: Deferred<String>? = null
        private set

    /** Numbers connections in this process (`c1`, `c2`, …) for the log, never naming a server. */
    val serial: Int = connectionCount.incrementAndGet()

    init {
        val jar = HermesCookieJar()
        client = httpClient.newBuilder()
            .cookieJar(jar)
            // A proxy can retain a closed idle socket. Start writes on a fresh connection
            // rather than retrying an operation whose outcome could already be committed.
            .connectionPool(okhttp3.ConnectionPool(0, 5, TimeUnit.MINUTES))
            // A dropped reply may follow a committed mutation or consumed refresh token.
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(headers.redirectGuard(address))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        provisioningClient = client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
        readClient = client.newBuilder().connectionPool(httpClient.connectionPool)
            .retryOnConnectionFailure(true).build()
        provisioningReadClient = provisioningClient.newBuilder().connectionPool(httpClient.connectionPool)
            .retryOnConnectionFailure(true).build()
        socketClient = client.newBuilder()
            // OkHttp omits network interceptors for WebSocket calls; never follow upgrades.
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** The gateway's report that the host answered [method] with -32601. */
    fun noteUnavailable(method: String) {
        unavailable += method
    }

    /** The gateway's report that a `session.resume` reached [runtime]. */
    fun noteAttached(runtime: String) {
        attached += runtime
    }

    /** The gateway's report that a `session.close` here ended [runtime]. */
    fun noteClosed(runtime: String) {
        attached -= runtime
    }

    /**
     * Keeps [HermesREST.PLATFORM_NOTE] as [profile]'s `platform_hints.hermex.append` (#1278).
     * Reads the config once per Profile on this connection and writes only a missing or changed
     * note; a call while that check runs waits for it. A failure only lets the next call try again.
     */
    suspend fun keepPlatformNote(profile: String) = withContext(HermesActor.dispatcher) {
        if (profile.isEmpty()) return@withContext
        val check = platformNoteChecks[profile] ?: scope.async { writePlatformNoteIfChanged(profile) }
        platformNoteChecks[profile] = check
        val kept = try {
            check.await()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        }
        if (!kept && platformNoteChecks[profile] === check) platformNoteChecks.remove(profile)
    }

    private suspend fun writePlatformNoteIfChanged(profile: String): Boolean = try {
        val config = BotJSON.parse(data(HermesREST.Config(profile)))
        if (config["platform_hints"][HermesCall.SOURCE]["append"].text != HermesREST.PLATFORM_NOTE) {
            data(HermesREST.SetPlatformNote(profile))
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        false
    }

    /**
     * Signs in unless this connection already is. Concurrent callers share one attempt, which
     * runs on the client for the [deadline] of the caller that started it.
     */
    suspend fun signIn(deadline: Deadline = Deadline.STANDARD) = withContext(HermesActor.dispatcher) {
        checkCurrent()
        if (isSignedIn) return@withContext
        val attempt = signInTask ?: beginSignIn(clientFor(deadline))
        try {
            attempt.await()
        } catch (error: Throwable) {
            checkCurrent()
            throw error
        }
        checkCurrent()
    }

    private fun beginSignIn(client: OkHttpClient): Deferred<Unit> {
        lateinit var attempt: Deferred<Unit>
        attempt = scope.async(start = CoroutineStart.LAZY) {
            // The step a failure is logged at.
            var step = "status"
            try {
                val status = publicStatus(client)
                checkCurrent()
                serverVersion = status["version"].text
                serverInstallID = BotConnection.installID(status)
                serverVersion?.let { if (!HermesCompatibility.isSupported(it)) throw BotFailure.Outdated(it) }
                connection.requireSameInstall(serverInstallID)
                val method = HermesSignInMethod.from(status) ?: throw BotFailure.Unsupported
                val account = connection.oauth
                when {
                    method == HermesSignInMethod.OPEN -> {
                        if (!connection.hasNoCredentials) throw BotFailure.SignInChanged
                        step = "page"
                        val page = send(prepared(HermesREST.DashboardPage.request(address)), client, 200..200)
                        checkCurrent()
                        val token = HermesREST.pageToken(page) ?: throw BotFailure.Unsupported
                        credential = Credential.PageToken(token)
                        // Any gated read proves the token. At the 0.21.5 pin `/api/auth/me` answers
                        // 401 in this mode, since only the password gate sets a session.
                        step = "token"
                        decoded(HermesREST.ProfilesActive, client)
                        checkCurrent()
                    }
                    account != null -> {
                        if (credential == null) {
                            step = "refresh"
                            refresh(client)
                        }
                        step = "identity"
                        val identity = decoded(HermesREST.Identity, client)
                        checkCurrent()
                        val current = connection.oauth ?: account
                        if (identity["provider"].text != current.provider || identity["user_id"].text != current.userID) {
                            throw BotFailure.WrongIdentity
                        }
                    }
                    else -> {
                        step = "login"
                        if (connection.hasNoCredentials) throw BotFailure.SignInChanged
                        // A browser-only host signs in from its form (`HermesBrowserSignIn`), never here.
                        if (status["auth_providers"].list?.any { it.text == "basic" } != true) throw BotFailure.BrowserSignIn
                        decoded(HermesREST.Login(connection.username, connection.password), client)
                        checkCurrent()
                        step = "identity"
                        val identity = decoded(HermesREST.Identity, client)
                        checkCurrent()
                        if (identity["provider"].text != "basic") throw BotFailure.WrongIdentity
                    }
                }
                // Trust on first use, in memory only: a later sign-in here must reach the same install.
                if (connection.installID == null) connection = connection.copy(installID = serverInstallID)
                isSignedIn = true
                epoch += 1
                HermesConnectionLog.notice("c$serial: signed in, release ${serverVersion ?: "unreported"}")
            } catch (error: Throwable) {
                credential = null
                // A retired connection has already logged that it was retired.
                if (!isRetired && error !is CancellationException) {
                    HermesConnectionLog.error("c$serial: sign-in failed at $step: ${HermesConnectionLog.reason(error)}")
                    when {
                        step == "login" && error == BotFailure.Rejected(401) -> onLoginRejected?.invoke(BotFailure.Rejected(401))
                        error === BotFailure.SignInChanged -> onLoginRejected?.invoke(BotFailure.SignInChanged)
                        step == "refresh" && error === BotFailure.SignInExpired -> onLoginRejected?.invoke(BotFailure.SignInExpired)
                    }
                }
                throw error
            } finally {
                if (signInTask === attempt) signInTask = null
            }
        }
        signInTask = attempt
        attempt.start()
        return attempt
    }

    /**
     * Finishes a browser sign-in on a connection not yet saved (#708): trades the loopback
     * [code] and its PKCE [verifier] for tokens at `/auth/native/token`, makes this a browser
     * sign-in's connection ([connection]`.oauth`, with the host's user id as its username and
     * no password), and signs in with the access token, which checks the release, the install
     * and the identity. The caller saves [connection]. A refused code (400) is
     * [BotFailure.BrowserSignInFailed].
     */
    suspend fun signIn(browserCode: String, verifier: String) = withContext(HermesActor.dispatcher) {
        checkCurrent()
        val request = prepared(HermesREST.NativeToken(code = browserCode, verifier = verifier).request(address))
        val (data, response) = exchange(request, client)
        checkCurrent()
        when (response.code) {
            200 -> Unit
            400 -> throw BotFailure.BrowserSignInFailed
            else -> throw BotFailure.Rejected(response.code)
        }
        val tokens = BotJSON.parse(data)
        val access = tokens["access_token"].text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.Unsupported
        val refresh = tokens["refresh_token"].text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.Unsupported
        val provider = tokens["provider"].text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.Unsupported
        val user = tokens["user_id"].text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.Unsupported
        val old = connection
        connection = BotConnection(
            id = old.id, name = old.name, address = old.address, username = user, password = "",
            hermesVersion = old.hermesVersion, installID = old.installID, headers = old.headers,
            oauth = HermesOAuth(provider = provider, userID = user, refreshToken = refresh),
        )
        isSignedIn = false
        credential = Credential.AccessToken(access)
        signIn()
    }

    /**
     * Trades the refresh token for an access token and the rotated refresh token, which is
     * saved before the access token is kept. The exchange and the save run in
     * [refreshInFlight], which a retired connection's cancelled sign-in leaves to finish. 401
     * means the host no longer accepts the refresh token ([BotFailure.SignInExpired]); any
     * other refusal is [BotFailure.Rejected] and leaves the token for the next sign-in.
     */
    private suspend fun refresh(client: OkHttpClient) {
        savedRefreshToken?.let { source ->
            val saved = source()
            checkCurrent()
            if (saved != null) connection.oauth?.let { connection = connection.copy(oauth = it.copy(refreshToken = saved)) }
        }
        val account = connection.oauth ?: throw BotFailure.Unsupported
        val sent = account.refreshToken
        val request = prepared(HermesREST.NativeRefresh(refreshToken = sent, provider = account.provider).request(address))
        val rotation = refreshScope.async {
            val (data, response) = exchange(request, client)
            when (response.code) {
                200 -> Unit
                401 -> throw BotFailure.SignInExpired
                else -> throw BotFailure.Rejected(response.code)
            }
            val tokens = BotJSON.parse(data)
            val access = tokens["access_token"].text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.Unsupported
            val rotated = tokens["refresh_token"].text
            if (!rotated.isNullOrEmpty() && rotated != sent) {
                connection.oauth?.let { connection = connection.copy(oauth = it.copy(refreshToken = rotated)) }
                onRefreshTokenRotated?.invoke(rotated)
            }
            access
        }
        refreshInFlight = rotation
        try {
            val access = rotation.await()
            checkCurrent()
            credential = Credential.AccessToken(access)
        } finally {
            if (refreshInFlight === rotation) refreshInFlight = null
        }
    }

    /**
     * Reads the public `/api/status` once, with this connection's headers and no credentials,
     * classified as sign-in classifies it: [BotFailure.NotDashboard], [BotFailure.Blocked] or
     * [BotFailure.Rejected]. The connect form reads it to tell a Hermes dashboard from a webui
     * (#900). Here only the dashboard's own Host-header refusal is `Rejected(400)`, and any
     * other 400 is `NotDashboard`.
     */
    suspend fun status(): BotJSON = withContext(HermesActor.dispatcher) {
        checkCurrent()
        publicStatus(client, onlyHostRefusalIs400 = true)
    }

    /** One public `/api/status` read, without signing in: whether the host answers at all. */
    suspend fun answersStatus(): Boolean = withContext(HermesActor.dispatcher) {
        if (isRetired) return@withContext false
        try {
            publicStatus(client)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            false
        }
    }

    /**
     * The body of one public route the host answers before its auth gate, such as
     * `/api/health`, sent with this connection's headers and without signing in. Any status
     * but 200 throws [BotFailure.Rejected].
     */
    suspend fun publicData(rest: HermesREST): ByteArray = withContext(HermesActor.dispatcher) {
        checkCurrent()
        val data = send(prepared(rest.request(address)), client, 200..200)
        checkCurrent()
        data
    }

    /**
     * Sends one signed-in request built from [rest] and returns the body of a reply whose
     * status is in [accepting]. Any other status throws [BotFailure.Rejected].
     */
    suspend fun data(
        rest: HermesREST,
        deadline: Deadline = Deadline.STANDARD,
        accepting: IntRange = 200..200,
        validateDispatch: (() -> Unit)? = null,
    ): ByteArray = authorized(rest.request(address), deadline, validateDispatch) { request, client ->
        send(request, client, accepting)
    }

    /**
     * Sends one signed-in request built from [rest] and returns its body and status, whatever
     * the status, for routes whose refusals carry the host's reason (`{detail}`). A 401 still
     * signs in again and resends once, as [data] does.
     */
    suspend fun reply(
        rest: HermesREST,
        deadline: Deadline = Deadline.STANDARD,
        validateDispatch: (() -> Unit)? = null,
    ): Reply = reply(rest.request(address), deadline, validateDispatch)

    /** [reply] for a request built elsewhere from a [HermesREST] case, such as dictation's upload. */
    suspend fun reply(
        request: Request,
        deadline: Deadline = Deadline.STANDARD,
        validateDispatch: (() -> Unit)? = null,
    ): Reply = authorized(request, deadline, validateDispatch) { prepared, client ->
        val (data, response) = exchange(prepared, client)
        if (response.code == 401) throw BotFailure.Rejected(401)
        Reply(data, response.code)
    }

    /**
     * Runs [perform] signed in, with [request] given this connection's headers. [perform]
     * sends it on the client for [deadline] and throws `Rejected(401)` for an unauthenticated
     * reply, which alone signs in again and resends it, once. [validateDispatch] runs just
     * before each send, the resend included, once any sign-in it waited on is done: a consumer
     * that closed meanwhile throws there, and nothing goes out.
     */
    suspend fun <T> authorized(
        request: Request,
        deadline: Deadline = Deadline.STANDARD,
        validateDispatch: (() -> Unit)? = null,
        perform: suspend (Request, OkHttpClient) -> T,
    ): T = withContext(HermesActor.dispatcher) {
        val prepared = prepared(request)
        val client = requestClient(prepared, clientFor(deadline))
        signIn(deadline)
        validateDispatch?.invoke()
        val sentEpoch = epoch
        try {
            val value = perform(bearing(prepared), client)
            checkCurrent()
            value
        } catch (error: BotFailure.Rejected) {
            if (error.code != 401) throw error
            checkCurrent()
            // The first reply to show the session expired drops it; later ones join the same
            // sign-in, even one still checking a new credential, which a late reply must not drop.
            if (epoch == sentEpoch && isSignedIn) {
                isSignedIn = false
                credential = null
            }
            signIn(deadline)
            validateDispatch?.invoke()
            val value = perform(bearing(prepared), client)
            checkCurrent()
            value
        }
    }

    /** The gateway upgrade, presenting a single-use ticket minted for it or an open dashboard's page token. */
    suspend fun gatewayUpgrade(): Request = upgrade { HermesREST.gatewayUpgrade(address, it) }

    /** [board]'s Kanban event socket upgrade (#1045), presenting a single-use ticket, never the gateway's. */
    suspend fun kanbanEventsUpgrade(board: String, since: Int): Request =
        upgrade { HermesREST.kanbanEventsUpgrade(address, board, since, it) }

    /**
     * The upgrade [build] makes for a fresh pass, with this connection's headers. Either pass
     * is preceded by a signed-in read, so a 401 signs in again first: minting the ticket, or
     * for a page token a gated read, since a refused upgrade can't tell a token a host restart
     * replaced from any other refusal.
     */
    private suspend fun upgrade(build: (HermesREST.UpgradePass) -> Request): Request = withContext(HermesActor.dispatcher) {
        try {
            signIn()
            if (credential is Credential.PageToken) {
                data(HermesREST.ProfilesActive)
                (credential as? Credential.PageToken)?.let { return@withContext prepared(build(HermesREST.UpgradePass.PageToken(it.token))) }
            }
            val ticket = BotJSON.parse(data(HermesREST.Ticket))["ticket"].text?.takeIf { it.isNotEmpty() }
                ?: throw BotFailure.Unsupported
            prepared(build(HermesREST.UpgradePass.Ticket(ticket)))
        } catch (error: Throwable) {
            // A failed sign-in, including the one after the ticket's 401, leaves this
            // connection signed out and has already logged its own step.
            if (!isRetired && isSignedIn && error !is CancellationException) {
                HermesConnectionLog.error("c$serial: sign-in failed at ticket: ${HermesConnectionLog.reason(error)}")
            }
            throw error
        }
    }

    /**
     * Whether [saved] is still this connection: the same UUID, address, account, password,
     * headers and browser sign-in account, and no conflicting install id. A newly backfilled
     * install id is taken.
     */
    fun adopt(saved: BotConnection): Boolean {
        val current = connection
        if (isRetired || saved.id != current.id || saved.address != current.address ||
            saved.username != current.username || saved.password != current.password ||
            saved.headers.orEmpty() != current.headers.orEmpty()
        ) return false
        val live = current.installID
        val stored = saved.installID
        if (live != null && stored != null && live != stored) return false
        // A browser sign-in is the same account whatever its refresh token, which only this
        // connection's copy is sure to hold.
        if (saved.oauth?.provider != current.oauth?.provider || saved.oauth?.userID != current.oauth?.userID) return false
        if (current.installID == null && saved.installID != null) connection = current.copy(installID = saved.installID)
        return true
    }

    /**
     * Ends this connection when its server or configuration is replaced: a sign-in in flight
     * stops and stores nothing, every call, late reply and resend after this throws
     * [BotFailure.Stale], and the gateway socket closes, telling each attached screen once.
     */
    fun retire() {
        HermesConnectionLog.notice("c$serial: connection retired")
        isRetired = true
        isSignedIn = false
        credential = null
        signInTask?.cancel()
        signInTask = null
        gateway.retire()
    }

    /** The login, identity and token reads, which run before there is a session. */
    private suspend fun decoded(rest: HermesREST, client: OkHttpClient): BotJSON =
        BotJSON.parse(send(bearing(prepared(rest.request(address))), client, 200..200))

    /**
     * The first sign-in read. `/api/status` is public on every dashboard, so a 404, a body
     * that is not JSON, or a 401 whose body is a JSON object (the webui's auth gate) means the
     * address is something else, such as the webui. Any other 401 comes from something in
     * front of Hermes, such as Cloudflare Access.
     */
    private suspend fun publicStatus(client: OkHttpClient, onlyHostRefusalIs400: Boolean = false): BotJSON {
        val request = prepared(HermesREST.Status.request(address))
        val (data, response) = exchange(request, client)
        val body = BotJSON.parseOrNull(data)
        return when (response.code) {
            200 -> body ?: throw BotFailure.NotDashboard
            401 -> throw if (body?.isObject == true) BotFailure.NotDashboard else BotFailure.Blocked
            404 -> throw BotFailure.NotDashboard
            // The dashboard's Host-header middleware answers `{"detail": "Invalid Host header. …"}`.
            400 -> throw if (onlyHostRefusalIs400 && body?.get("detail")?.text?.startsWith("Invalid Host header") != true) {
                BotFailure.NotDashboard
            } else {
                BotFailure.Rejected(400)
            }
            else -> throw BotFailure.Rejected(response.code)
        }
    }

    private fun clientFor(deadline: Deadline): OkHttpClient =
        if (deadline == Deadline.PROVISIONING) provisioningClient else client

    /** GET/HEAD may recover a stale pooled socket; writes and upgrade tickets never replay. */
    private fun requestClient(request: Request, baseClient: OkHttpClient): OkHttpClient {
        if (request.method != "GET" && request.method != "HEAD") return baseClient
        return when (baseClient) {
            client -> readClient
            provisioningClient -> provisioningReadClient
            else -> baseClient
        }
    }

    private suspend fun send(request: Request, client: OkHttpClient, accepting: IntRange): ByteArray {
        val (data, response) = exchange(request, client)
        if (response.code !in accepting) throw BotFailure.Rejected(response.code)
        return data
    }

    /**
     * Sends [request] and returns the final reply, whatever its status. A reply from another
     * host means a redirect led to something in front of Hermes, such as an access proxy's
     * sign-in page, and throws [BotFailure.Blocked], like the status probe. A 3xx reply is a
     * redirect the guard refused, and is `Blocked` too. A transport failure is [BotFailure.Network].
     */
    private suspend fun exchange(request: Request, client: OkHttpClient): HermesExchange {
        val result = try {
            requestClient(request, client).exchange(request)
        } catch (_: io.github.jcartu.hermex.net.UnsafeCrossOriginRedirect) {
            throw BotFailure.Blocked
        } catch (error: IOException) {
            throw BotFailure.Network(error)
        }
        if (!HermesHeaders.isSameOrigin(result.response.request.url, request.url)) throw BotFailure.Blocked
        if (result.status in 300..399) throw BotFailure.Blocked
        return result
    }

    private fun prepared(request: Request): Request = headers.applied(request, address)

    /**
     * [request] with the connection's credential, when there is one. The socket upgrades never
     * carry it as a header: they present a ticket it minted, or the page token in the URL.
     */
    private fun bearing(request: Request): Request = when (val credential = credential) {
        is Credential.AccessToken -> request.newBuilder().header("Authorization", "Bearer ${credential.token}").build()
        is Credential.PageToken -> request.newBuilder().header(HermesHeaders.SESSION_TOKEN_NAME, credential.token).build()
        null -> request
    }

    private fun checkCurrent() {
        if (isRetired) throw BotFailure.Stale
    }

    companion object {
        private val connectionCount = AtomicInteger(0)
    }
}

/**
 * Port of `HermesConnections`: gives every consumer of the active server's saved Bot
 * connection the same [HermesConnection], and with it the same gateway socket. It keeps one
 * entry, keyed by configured server and connection UUID, and holds it weakly, so the
 * connection lives only while a consumer does. A request for another server or UUID, or for
 * the same UUID with a new address, account, password or headers, retires the old connection
 * first, so no cookie, sign-in, socket or late reply crosses servers, accounts, credentials or
 * headers. A browser sign-in's connection refreshes from the refresh token saved in [store],
 * not the one in `saved`, and saves each rotated one there (#708).
 */
class HermesConnections(
    private val httpClient: () -> OkHttpClient = { OkHttpClient() },
    private val store: BotConnectionStore = BotConnectionStore.none,
) {
    /**
     * The saved sign-in records whose refresh tokens the connections read and rotate: the
     * subset of the app's record store the registry needs (`BotConnectionStore` in Swift,
     * keyed by configured server). The settings slice backs it with its encrypted storage.
     */
    interface BotConnectionStore {
        @Throws(Exception::class)
        fun load(server: String): BotConnection?

        /**
         * Writes the refresh token the host just rotated into, only while [server]'s record is
         * still connection [id] holding [sent]: a record removed or signed in again meanwhile
         * keeps what it has. It never retires the live connection, which made the change.
         */
        @Throws(Exception::class)
        fun saveRotatedRefreshToken(rotated: String, sent: String, id: String, server: String)

        companion object {
            /** A store with no records: refresh tokens stay in memory only. */
            val none: BotConnectionStore = object : BotConnectionStore {
                override fun load(server: String): BotConnection? = null
                override fun saveRotatedRefreshToken(rotated: String, sent: String, id: String, server: String) = Unit
            }
        }
    }

    /**
     * Told which configured server's sign-in the host refused: `Rejected(401)` for a saved
     * username or password at the login step, [BotFailure.SignInExpired] for a browser
     * sign-in's refresh token, [BotFailure.SignInChanged] when the host's sign-in mode moved.
     * `AuthManager` sets it and signs that server out when it is the active Hermes server (#899).
     */
    @Volatile
    var onSignInRejected: ((server: String, failure: BotFailure, isCurrent: () -> Boolean) -> Unit)? = null

    private val lock = Any()
    private var server: String? = null
    private var current: WeakReference<HermesConnection>? = null

    /**
     * The refresh each server's last retired connection still had in flight, so the next
     * refresh there, by any connection, reads the token it rotated to, not the one it spent.
     */
    private val retiredRefreshes = HashMap<String, Deferred<String>>()

    /** `connection(for:server:)` — the shared connection for [saved] under the configured [server]. */
    fun connection(saved: BotConnection, server: String): HermesConnection = synchronized(lock) {
        val live = current?.get()
        if (live != null && this.server == server && live.adopt(saved)) return live
        retireCurrent()
        val fresh = HermesConnection(saved, httpClient())
        fresh.onLoginRejected = { failure ->
            onSignInRejected?.invoke(server, failure) {
                synchronized(lock) {
                    this.server == server && current?.get() === fresh && !fresh.isRetired
                }
            }
        }
        if (saved.oauth != null) keepRefreshTokens(fresh, server)
        current = WeakReference(fresh)
        this.server = server
        fresh
    }

    /**
     * `ownConnection(for:server:)` — a connection to [saved] of the caller's own, beside
     * [server]'s current one, which it never replaces or retires: Move to Hermes's destination
     * (#1265). [retireOwn] ends it.
     */
    fun ownConnection(saved: BotConnection, server: String): HermesConnection {
        val own = HermesConnection(saved, httpClient())
        if (saved.oauth != null) keepRefreshTokens(own, server)
        return own
    }

    /** Ends a connection [ownConnection] made, leaving any refresh it has in flight for the next connection on [server]. */
    fun retireOwn(own: HermesConnection, server: String) = synchronized(lock) {
        own.refreshInFlight?.let { retiredRefreshes[server] = it }
        own.retire()
    }

    /** Closes the current connection's gateway socket, silently, because the app went to the background (#902). */
    fun closeForBackground() {
        current?.get()?.gateway?.closeForBackground()
    }

    /**
     * Ends [server]'s gateway socket as lost because its dashboard restarted on an update
     * (#1075), so every Bot screen on it reconnects now. Nothing happens for another server.
     */
    fun reconnectGateway(server: String) {
        val live = synchronized(lock) { if (this.server == server) current?.get() else null } ?: return
        live.gateway.dropSocket()
    }

    /**
     * Retires [server]'s connection now unless [unlessStill], its newly saved record, is still
     * that connection. `AuthManager` calls it when [server] stops being active, and the record
     * store when its credentials are saved or removed.
     */
    fun retire(server: String, unlessStill: BotConnection? = null) = synchronized(lock) {
        val live = current?.get() ?: return
        if (this.server != server) return
        if (unlessStill != null && live.adopt(unlessStill)) return
        retireCurrent()
        current = null
    }

    private fun retireCurrent() {
        val live = current?.get() ?: return
        val server = server
        live.refreshInFlight?.let { if (server != null) retiredRefreshes[server] = it }
        live.retire()
    }

    /**
     * Starts each of [fresh]'s refreshes from the token saved for it when that changed since
     * it last looked, once any refresh a retired connection on [server] still has in flight
     * has saved its own, and saves each rotated one. A save only replaces the token this
     * connection last saw saved, and a failed save is retried at the next rotation.
     */
    private fun keepRefreshTokens(fresh: HermesConnection, server: String) {
        val id = fresh.connection.id
        val serial = fresh.serial
        var saved: String? = null
        fresh.savedRefreshToken = {
            val pending = synchronized(lock) { retiredRefreshes[server] }
            if (pending != null) runCatching { pending.await() }
            val record = store.load(server)
            val token = record?.takeIf { it.id == id }?.oauth?.refreshToken
            if (token != null && token != saved) {
                saved = token
                token
            } else {
                null
            }
        }
        fresh.onRefreshTokenRotated = { rotated ->
            val previous = saved
            if (previous != null) {
                try {
                    store.saveRotatedRefreshToken(rotated, previous, id, server)
                    saved = rotated
                } catch (error: Exception) {
                    HermesConnectionLog.error("c$serial: rotated refresh token not saved: ${HermesConnectionLog.reason(error)}")
                }
            }
        }
    }
}

/**
 * Port of `HermesRestartWait`: the wait for a Hermes dashboard that went away to restart.
 * Sleeps each of [delaysMillis] in turn and then asks [probe], until [isFinal] holds for its
 * answer or the schedule runs out. The last answer stands; a cancelled sleep ends the wait
 * with the answer before it.
 */
object HermesRestartWait {
    suspend fun <Answer> lastAnswer(
        delaysMillis: List<Long>,
        sleep: suspend (Long) -> Unit,
        probe: suspend () -> Answer?,
        isFinal: (Answer) -> Boolean,
    ): Answer? {
        var answer: Answer? = null
        for (delay in delaysMillis) {
            try {
                sleep(delay)
            } catch (error: CancellationException) {
                break
            }
            answer = probe()
            if (answer != null && isFinal(answer)) break
        }
        return answer
    }
}
