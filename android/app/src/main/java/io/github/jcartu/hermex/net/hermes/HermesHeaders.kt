package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.net.CrossOriginHeaderStripper
import io.github.jcartu.hermex.net.CustomHeader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Port of `HermesHeaders` (`Networking/Hermes/HermesConnection.swift`): request headers for
 * one direct-Hermes origin, admitted by the policy the host needs: no name the transport owns
 * (OkHttp, the cookie jar and the gateway handshake set those), no name Hermes reads for its
 * own checks (`Origin` for the gateway upgrade, `X-Forwarded-Prefix` for the session cookie's
 * path, `X-Hermes-Session-Token`), and no `Bearer` authorization, which Hermes also reads as
 * its session token. Any other `Authorization` value passes, such as Cloudflare Access's
 * single-header JSON service token, except on a browser sign-in's connection (#708), whose
 * own bearer is its `Authorization`. The only source is the Hermes connection's own record
 * (`BotConnection.headers`), never the webui's custom headers.
 */
class HermesHeaders private constructor(val values: List<CustomHeader>) {

    /** Why the connection form refuses a header row. Shown under the Connection Headers row. */
    sealed class Rejection : Exception() {
        data class Malformed(val name: String) : Rejection()
        data class Reserved(val name: String) : Rejection()
        object Bearer : Rejection() {
            private fun readResolve(): Any = Bearer
        }

        /** Any `Authorization` header on a connection that signs in through the browser. */
        object Authorization : Rejection() {
            private fun readResolve(): Any = Authorization
        }

        override val message: String
            get() = when (this) {
                Bearer -> "Hermes reads Authorization: Bearer as its own sign-in and refuses it. Remove this header to connect."
                Authorization ->
                    "This server signs in through the browser, so Hermex sends its own Authorization header. " +
                        "Remove this header to connect. For Cloudflare Access, use the CF-Access-Client-Id and " +
                        "CF-Access-Client-Secret headers instead."
                is Reserved -> "$name is reserved for Hermex and Hermes, so it can't be a connection header. Remove this header to connect."
                is Malformed ->
                    "$name isn't a valid header: names can't contain spaces or colons, and values must fit on one line. " +
                        "Fix or remove it to connect."
            }
    }

    /**
     * `applied(to:origin:)` — [request] with these headers added when it is for [origin]. A
     * header the request already carries, such as the JSON content type or the gateway
     * subprotocols, keeps its built-in value.
     */
    fun applied(request: Request, origin: HttpUrl): Request {
        if (values.isEmpty() || !isSameOrigin(request.url, origin)) return request
        val builder = request.newBuilder()
        for (header in values) {
            if (request.header(header.sanitizedName) == null) {
                builder.header(header.sanitizedName, header.sanitizedValue)
            }
        }
        return builder.build()
    }

    /**
     * `redirectGuard(for:)` — the network interceptor that drops these headers, and an open
     * dashboard's session token, from a redirect that leaves [origin], such as an access
     * proxy's bounce to its sign-in page.
     */
    fun redirectGuard(origin: HttpUrl): CrossOriginHeaderStripper {
        val admitted = values + CustomHeader(name = SESSION_TOKEN_NAME, value = "-")
        return CrossOriginHeaderStripper(baseUrlProvider = { origin }, customHeaderProvider = { admitted })
    }

    companion object {
        val NONE = HermesHeaders(emptyList())

        private val RESERVED = setOf(
            "host", "connection", "upgrade", "content-length", "transfer-encoding", "te", "trailer",
            "keep-alive", "proxy-connection", "proxy-authorization", "cookie",
            "origin", "x-forwarded-prefix", "x-hermes-session-token",
        )

        /** The header an open dashboard reads its page's session token from (#1314). */
        const val SESSION_TOKEN_NAME = "X-Hermes-Session-Token"

        /**
         * `init(_:signsInWithBrowser:)` — admits [headers] for a connection, which
         * [signsInWithBrowser] when it sends its own bearer (`BotConnection.oauth`).
         */
        @Throws(Rejection::class)
        fun admit(headers: List<CustomHeader>, signsInWithBrowser: Boolean = false): HermesHeaders {
            for (header in headers) {
                val name = header.sanitizedName.lowercase()
                if (!header.isApplicable) throw Rejection.Malformed(header.sanitizedName)
                if (name in RESERVED || name.startsWith("sec-websocket-")) throw Rejection.Reserved(header.sanitizedName)
                val scheme = header.sanitizedValue.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() }?.lowercase()
                if (name == "authorization" && scheme == "bearer") throw Rejection.Bearer
                if (name == "authorization" && signsInWithBrowser) throw Rejection.Authorization
            }
            return HermesHeaders(headers)
        }

        /**
         * `init(saved:)` — the headers [saved]'s requests carry. A saved list the policy now
         * refuses sends none: the connection form shows why and keeps Connect off until fixed.
         */
        fun saved(saved: BotConnection): HermesHeaders =
            runCatching { admit(saved.headers.orEmpty(), signsInWithBrowser = saved.oauth != null) }
                .getOrDefault(NONE)

        /**
         * `isSameOrigin(_:as:)` — same scheme, host and port as [address], reading the
         * gateway's `ws`/`wss` as `http`/`https`.
         */
        fun isSameOrigin(url: HttpUrl, address: HttpUrl): Boolean =
            CrossOriginHeaderStripper.isSameOrigin(url, address)

        /** [isSameOrigin] for a `ws`/`wss` string, which `HttpUrl` can't hold. */
        fun isSameOrigin(url: String, address: HttpUrl): Boolean {
            val http = when {
                url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
                url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
                else -> url
            }
            val parsed = http.toHttpUrlOrNull() ?: return false
            return isSameOrigin(parsed, address)
        }
    }
}
