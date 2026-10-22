package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.HermesCompatibility
import okhttp3.HttpUrl
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Port of `BotFailure` (`Networking/Bots/BotProtocol.swift`): why a direct Hermes
 * connection, sign-in, request or gateway call failed. This is the sign-in error
 * hierarchy (`HermesSignInError`) every Hermes surface reads: `DifferentHost`,
 * `Outdated` (version too old), `Blocked`, `BrowserSignInFailed`, `SignInChanged`,
 * `Rejected(401)` (unauthorized), [Network] (transport, Swift's unwrapped `URLError`)
 * and `Rejected(code)` (any other HTTP status).
 *
 * `message` mirrors `errorDescription` exactly (English inline).
 */
sealed class BotFailure(cause: Throwable? = null) : Exception(cause) {

    /** The action is no longer current: its connection was retired or its screen left. */
    object Stale : BotFailure() {
        private fun readResolve(): Any = Stale
    }

    /** The host answered something this build can't use. */
    object Unsupported : BotFailure() {
        private fun readResolve(): Any = Unsupported
    }

    object MissingChat : BotFailure() {
        private fun readResolve(): Any = MissingChat
    }

    object WrongIdentity : BotFailure() {
        private fun readResolve(): Any = WrongIdentity
    }

    /** The address now reaches a host whose `install_id` differs from the stored one. */
    object DifferentHost : BotFailure() {
        private fun readResolve(): Any = DifferentHost
    }

    /** An HTTP status, or a JSON-RPC error code, the host answered. */
    data class Rejected(val code: Int) : BotFailure()

    /** The socket was lost, or a required call got no reply in time. */
    object Transport : BotFailure() {
        private fun readResolve(): Any = Transport
    }

    /**
     * The request never produced an HTTP reply. Swift lets `URLError` pass through
     * unwrapped; here the transport failure is carried so `BotConnectionAdvice` can name it.
     */
    class Network(val underlying: IOException) : BotFailure(underlying)

    object InvalidAddress : BotFailure() {
        private fun readResolve(): Any = InvalidAddress
    }

    /**
     * The address answered `/api/status` with 404, a JSON 401 (the webui's auth gate) or a
     * body that is not JSON, so it is not a Hermes dashboard (often the webui address). Permanent.
     */
    object NotDashboard : BotFailure() {
        private fun readResolve(): Any = NotDashboard
    }

    /**
     * Something in front of Hermes wants its own sign-in: a request ended on another host
     * (an access proxy's login page), or `/api/status` answered a 401 whose body is not a
     * JSON object. Permanent.
     */
    object Blocked : BotFailure() {
        private fun readResolve(): Any = Blocked
    }

    /**
     * The host requires sign-in but offers neither a `basic` provider nor browser sign-in
     * (`auth_flows ∋ native_pkce`), or a form that can't sign in through the browser met
     * one that offers only that. Permanent.
     */
    object BrowserSignIn : BotFailure() {
        private fun readResolve(): Any = BrowserSignIn
    }

    /** The host no longer accepts a browser sign-in's refresh token (#708). Permanent. */
    object SignInExpired : BotFailure() {
        private fun readResolve(): Any = SignInExpired
    }

    /** A browser sign-in that closed without a code, or whose code the host no longer accepts. */
    object BrowserSignInFailed : BotFailure() {
        private fun readResolve(): Any = BrowserSignInFailed
    }

    /**
     * The gateway upgrade was refused with this HTTP status after a good sign-in. Permanent;
     * 408, 429 and 5xx arrive as [Rejected] instead ([fromUpgradeStatus]).
     */
    data class UpgradeRefused(val status: Int) : BotFailure()

    /** `/api/status` reported this release, older than `HermesCompatibility.minimumVersion`. */
    data class Outdated(val version: String) : BotFailure()

    /**
     * The saved sign-in no longer matches the host's (#1314): a connection saved without
     * credentials met a sign-in, or one saved with them met a dashboard with none. Permanent.
     */
    object SignInChanged : BotFailure() {
        private fun readResolve(): Any = SignInChanged
    }

    override val message: String
        get() = when (this) {
            Stale -> "This action is no longer current. Refresh the conversation."
            Unsupported, NotDashboard -> "This Hermes connection does not support Bot chat here."
            MissingChat -> "Open this bot’s chat in Hermes Desktop, then refresh."
            WrongIdentity -> "The conversation identity changed. Check this bot in Desktop."
            DifferentHost ->
                "The Hermes host at this address reports a different identity than the one you " +
                    "connected to. Check the address in the Hermes connection."
            is Rejected -> when (code) {
                401 -> "Hermes didn't accept the username or password."
                403 -> "Something in front of Hermes, such as Cloudflare Access, blocked the request."
                in 502..504 -> "Your proxy answered, but Hermes didn't. Check that the dashboard is running on the host."
                in 520..530 -> "Cloudflare can't reach your tunnel. Check that cloudflared and the dashboard are running on the host."
                -32601 -> "This Hermes connection does not support Bot chat here."
                4090 -> "Another Hermes process owns this conversation. Resolve it on the host, then refresh."
                4130 -> "This conversation is too large to open here. Use Desktop."
                else -> CONNECTION_LOST
            }
            InvalidAddress -> "Enter a Hermes HTTP or HTTPS address without a path, credentials or query."
            Blocked ->
                "Something in front of Hermes, such as Cloudflare Access, wants its own sign-in first. " +
                    "Add its service token under Connection Headers in the Hermes connection, or use an " +
                    "address that skips it, such as the dashboard's local network address."
            BrowserSignIn ->
                "This Hermes host only offers sign-in with a browser, which Hermex doesn't support yet. " +
                    "To connect now, add a dashboard username and password on the host."
            SignInExpired -> "Your Hermes sign-in expired. Sign in again."
            SignInChanged -> "This server’s sign-in changed. Sign in again to continue."
            BrowserSignInFailed -> "The browser sign-in didn't finish. Try again."
            is UpgradeRefused ->
                "Hermes accepted the sign-in, but the live connection was refused. If a proxy or tunnel " +
                    "sits in front of Hermes, turn on WebSocket support and let the Sec-WebSocket-Protocol header through."
            is Outdated ->
                "This Hermes host runs $version. Hermex needs Hermes ${HermesCompatibility.MINIMUM_VERSION} " +
                    "or later. Update Hermes on the host, then try again."
            Transport, is Network -> CONNECTION_LOST
        }

    /** `"\(failure)"` for the log: the case and its numeric payload, never host text. */
    val logName: String
        get() = when (this) {
            is Rejected -> "rejected($code)"
            is UpgradeRefused -> "upgradeRefused($status)"
            is Outdated -> "outdated($version)"
            is Network -> "network(${underlying.javaClass.simpleName})"
            else -> this::class.simpleName?.replaceFirstChar { it.lowercase() } ?: "failure"
        }

    companion object {
        private const val CONNECTION_LOST =
            "Connection lost. The bot may still be working. Reconnect to check its current conversation."

        /**
         * `init?(upgradeStatus:)` — the failure a gateway upgrade answered with `status`
         * stands for, or null for 101, an accepted upgrade whose later errors are the
         * socket's own. 408, 429 and 5xx keep [Rejected] and its quiet retry and proxy copy;
         * any other status is [UpgradeRefused], which does not heal on its own.
         */
        fun fromUpgradeStatus(status: Int): BotFailure? = when (status) {
            101 -> null
            408, 429, in 500..599 -> Rejected(status)
            else -> UpgradeRefused(status)
        }
    }
}

/** The task-level name of the sign-in error hierarchy (plan Contract B). */
typealias HermesSignInError = BotFailure

/**
 * Port of `BotAddressError` (`Features/Bots/BotConnection.swift`): why the connection
 * form's address can't be used. Thrown only by `HermesAddress.parse`, so typing mistakes
 * stay apart from [BotFailure] connection failures.
 */
sealed class BotAddressError : Exception() {
    /** A path that isn't a dashboard page, usually a reverse-proxy prefix. */
    data class Path(val path: String) : BotAddressError()

    /** No usable HTTP or HTTPS host, or the address carries a username or password. */
    object Invalid : BotAddressError() {
        private fun readResolve(): Any = Invalid
    }

    override val message: String
        get() = when (this) {
            is Path ->
                "Remove “$path” from the address. Hermex needs the dashboard's main address, like " +
                    "https://example.com:9119, and can't use a dashboard served under a path yet."
            Invalid ->
                "Enter the dashboard's HTTP or HTTPS address, like https://example.com:9119, " +
                    "without a username or password."
        }
}

/**
 * Port of `BotConnectionAdvice` (`BotProtocol.swift`): what to check when a Bot connection
 * fails, for the connection form, the inbox and a chat. Names only the host of the
 * connection's own address, never a credential.
 */
object BotConnectionAdvice {

    /**
     * Whether a screen that lost its connection to [error] should reconnect on its backoff.
     * False for a refusal the user has to act on. Server errors, rate limits and JSON-RPC
     * faults other than "method missing" are the retry loop's problem.
     */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        BotFailure.Unsupported, BotFailure.WrongIdentity, BotFailure.DifferentHost, BotFailure.InvalidAddress,
        BotFailure.NotDashboard, BotFailure.Blocked, BotFailure.BrowserSignIn, BotFailure.SignInExpired,
        BotFailure.SignInChanged, is BotFailure.UpgradeRefused, is BotFailure.Outdated,
        -> false
        is BotFailure.Rejected -> when (error.code) {
            -32601, 4090, 4130 -> false
            408, 429 -> true
            else -> error.code !in 400..499
        }
        else -> true
    }

    /** The copy for [error] from a connection to [address]. */
    fun message(error: Throwable, address: HttpUrl): String {
        val host = address.host
        val transport = (error as? BotFailure.Network)?.underlying ?: (error as? IOException)
        if (transport != null) {
            return when (transport) {
                is UnknownHostException ->
                    "Couldn't find $host. Check the address. For a Tailscale or VPN name, make sure this device is connected to it."
                is ConnectException, is NoRouteToHostException ->
                    "$host refused the connection. Check the port and that the Hermes dashboard is running."
                is SocketTimeoutException ->
                    "$host didn't answer. Check that this device can reach it on this network, or use your tunnel address."
                is SSLException ->
                    "Couldn't make a secure connection to $host. Check its certificate. A dashboard on your " +
                        "local network without HTTPS needs http://."
                else -> "Couldn't reach $host. Check the address and network."
            }
        }
        return when (error) {
            is BotFailure.Rejected -> when (error.code) {
                400 ->
                    "Hermes doesn't accept $host as its address. On the host, set dashboard.public_url to " +
                        "$address, then restart the dashboard."
                429 -> "Too many sign-in attempts. Wait a minute, then try again."
                else -> error.message
            }
            BotFailure.NotDashboard -> "$host isn't a Hermes dashboard. Use the dashboard address, not the Hermes Web UI."
            is BotFailure -> error.message
            else -> "Couldn't reach $host. Check the address and network."
        }
    }
}
