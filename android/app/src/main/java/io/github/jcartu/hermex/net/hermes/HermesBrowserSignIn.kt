package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of `Networking/Hermes/HermesBrowserSignIn.swift`: signs a Hermes host in through the
 * system browser (#708), as RFC 8252 describes for a native app: a loopback listener on
 * `127.0.0.1`, the host's `/auth/native/authorize` in the browser, and PKCE. The host brokers
 * its own provider's pages and redirects to the listener with a single-use code, which
 * [HermesConnection.signIn] (browser code) trades for tokens.
 *
 * Showing the browser (a Custom Tab or `ACTION_VIEW`, and the `hermexsignin://done` intent
 * filter that brings the app back) is the onboarding screen's; [signIn] takes it as [Presenter]
 * and is otherwise pure.
 */
object HermesBrowserSignIn {
    /** The scheme the listener's `302` bounces to, which closes the browser sheet. */
    const val CALLBACK_SCHEME = "hermexsignin"

    /** Where the listener bounces every callback. */
    const val BOUNCE = "$CALLBACK_SCHEME://done"

    /**
     * Shows the host's authorize page and returns once the browser closes on the callback
     * scheme (or the user dismisses it). Throws [CancellationException] when the user closed
     * it; any other failure reads as [BotFailure.BrowserSignInFailed] unless the code arrived.
     */
    fun interface Presenter {
        suspend fun present(url: HttpUrl)

        companion object {
            /** A form that can't show a browser, which a browser host's sign-in then refuses. */
            val unavailable = Presenter { throw BotFailure.BrowserSignInFailed }
        }
    }

    /**
     * `isRequired(by:forced:)` — whether [status], a host's public `/api/status`, asks for
     * browser sign-in: the host offers it (`auth_flows ∋ native_pkce`) and has no `basic`
     * password provider, or [forced] (the testing toggle) and it offers it at all.
     */
    fun isRequired(status: BotJSON, forced: Boolean = false): Boolean {
        if (status["auth_flows"].list?.any { it.text == "native_pkce" } != true) return false
        return forced || status["auth_providers"].list?.any { it.text == "basic" } != true
    }

    /**
     * Runs one browser sign-in for [http], a connection not yet saved, and leaves it signed in
     * as a browser sign-in's connection (`http.connection`), which the caller saves. Closing
     * the browser throws [CancellationException]; a browser that closed without a matching
     * callback, or a code the host no longer accepts, throws [BotFailure.BrowserSignInFailed].
     */
    suspend fun signIn(http: HermesConnection, presenter: Presenter) {
        val verifier = randomURLSafe(32)
        val state = randomURLSafe(16)
        val listener = HermesLoopbackListener(bounce = BOUNCE, state = state)
        try {
            val port = try {
                listener.start()
            } catch (error: IOException) {
                throw BotFailure.BrowserSignInFailed
            }
            val url = authorizeURL(
                base = http.connection.address, challenge = challenge(verifier),
                redirect = "http://127.0.0.1:$port/callback", state = state,
            )
            try {
                presenter.present(url)
            } catch (error: Throwable) {
                // An error after the code arrived is only the sheet closing; without one, the
                // user closed it, the form's task was cancelled, or it never showed.
                if (listener.code == null) {
                    if (error is CancellationException) throw error
                    throw if (error is BotFailure) error else BotFailure.BrowserSignInFailed
                }
            }
            val code = listener.code ?: throw BotFailure.BrowserSignInFailed
            http.signIn(browserCode = code, verifier = verifier)
        } finally {
            listener.stop()
        }
    }

    /**
     * `/auth/native/authorize` on [base] with every query value fully percent-encoded, since
     * some proxies' filters refuse an unencoded `redirect_uri=http://…`. No `provider`: the
     * host picks its only interactive provider itself and shows its own chooser for several.
     */
    fun authorizeURL(base: HttpUrl, challenge: String, redirect: String, state: String): HttpUrl =
        base.newBuilder()
            .addPathSegments("auth/native/authorize")
            .addEncodedQueryParameter("code_challenge", encoded(challenge))
            .addEncodedQueryParameter("code_challenge_method", "S256")
            .addEncodedQueryParameter("redirect_uri", encoded(redirect))
            .addEncodedQueryParameter("state", encoded(state))
            .build()

    /** The S256 PKCE challenge for [verifier]: its SHA-256, base64url without padding. */
    fun challenge(verifier: String): String =
        base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** `count` random bytes as base64url without padding. */
    fun randomURLSafe(count: Int): String {
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return base64url(bytes)
    }

    private fun base64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** Everything outside RFC 3986's unreserved characters, percent-encoded. */
    fun encoded(value: String): String {
        val builder = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt() and 0xff
            if (char < 128 && UNRESERVED.indexOf(char.toChar()) >= 0) {
                builder.append(char.toChar())
            } else {
                builder.append('%').append(String.format("%02X", char))
            }
        }
        return builder.toString()
    }
}

/**
 * Port of `HermesLoopbackListener`: the loopback receiver for one browser sign-in: binds
 * `127.0.0.1` on an ephemeral port, keeps the first `GET /callback` whose `state` matches,
 * and answers every callback `302 <bounce>` so the custom-scheme bounce closes the browser.
 * Any other path is a 404.
 */
class HermesLoopbackListener(private val bounce: String, private val state: String) {
    /** The code from the first callback carrying the expected `state`. */
    @Volatile
    var code: String? = null
        private set

    private var server: ServerSocket? = null
    private val stopped = AtomicBoolean(false)
    private val codeArrived = CompletableDeferred<String>()

    /** The code once a matching callback lands; waits until then or [stop]. */
    suspend fun awaitCode(): String = codeArrived.await()

    /** Binds loopback on an ephemeral port and returns it once the listener is ready. */
    @Throws(IOException::class)
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        val thread = Thread({ serve(socket) }, "hermex-signin-listener")
        thread.isDaemon = true
        thread.start()
        socket.localPort
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
        codeArrived.cancel()
    }

    private fun serve(socket: ServerSocket) {
        while (!stopped.get()) {
            val connection = try {
                socket.accept()
            } catch (error: SocketException) {
                return
            } catch (error: IOException) {
                return
            }
            val worker = Thread({ handle(connection) }, "hermex-signin-callback")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun handle(connection: Socket) {
        connection.use { client ->
            client.soTimeout = 10_000
            val target = try {
                requestTarget(client.getInputStream())
            } catch (error: IOException) {
                return
            } ?: return
            val response = respond(target)
            runCatching {
                client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
            }
        }
    }

    /** The reply to one request line's target, recording the code when it is the callback. */
    internal fun respond(target: String): String {
        val components = runCatching { URI("http://127.0.0.1$target") }.getOrNull()
        if (components?.path != "/callback") {
            return "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
        val items = queryItems(components.rawQuery)
        if (code == null && items.firstOrNull { it.first == "state" }?.second == state) {
            val value = items.firstOrNull { it.first == "code" }?.second
            if (!value.isNullOrEmpty()) {
                code = value
                codeArrived.complete(value)
            }
        }
        return "HTTP/1.1 302 Found\r\nLocation: $bounce\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }

    companion object {
        /** The most a request may send before its request line ends. */
        private const val REQUEST_LINE_LIMIT = 16_384

        /**
         * `requestTarget(in:)` — the request target of the request line (`GET <target> HTTP/1.1`),
         * or null when the line never ends within the limit.
         */
        internal fun requestTarget(input: java.io.InputStream): String? {
            val line = StringBuilder()
            while (line.length < REQUEST_LINE_LIMIT) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    return text.split(' ').drop(1).firstOrNull() ?: "/"
                }
                line.append(byte.toChar())
            }
            return null
        }

        internal fun queryItems(rawQuery: String?): List<Pair<String, String>> {
            if (rawQuery.isNullOrEmpty()) return emptyList()
            return rawQuery.split('&').filter { it.isNotEmpty() }.map { item ->
                val equals = item.indexOf('=')
                val name = if (equals < 0) item else item.substring(0, equals)
                val value = if (equals < 0) "" else item.substring(equals + 1)
                decode(name) to decode(value)
            }
        }

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
