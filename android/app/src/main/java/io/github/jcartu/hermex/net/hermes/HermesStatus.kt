package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Port of `HermesSignInMethod` (`Networking/Bots/BotProtocol.swift`): how a dashboard's
 * `/api/status` says a phone signs in.
 */
enum class HermesSignInMethod {
    /** A username and password. */
    PASSWORD,

    /** The host's browser page (`HermesBrowserSignIn.isRequired`, #708). */
    BROWSER,

    /**
     * No sign-in (`auth_required` false, #1314): a dashboard bound to loopback without
     * `dashboard.public_url`, reached through a proxy. The connection reads the session token
     * the dashboard's page carries, and anyone who reaches the address can do the same.
     */
    OPEN,
    ;

    companion object {
        /** Shown wherever such a dashboard is connected or saved, in place of its sign-in. */
        const val OPEN_WARNING =
            "This dashboard has no password. Anyone who can reach this address can use your agent. " +
                "To require one, set dashboard.public_url on the host and turn on its password."

        /**
         * `init?(_ status:)` — null for a status without an `auth_required` flag, which is no
         * dashboard Hermex knows. [forcedBrowser] is the testing toggle
         * (`HermesBrowserSignIn.isForcedForTesting`).
         */
        fun from(status: BotJSON, forcedBrowser: Boolean = false): HermesSignInMethod? =
            when (status["auth_required"].flag) {
                false -> OPEN
                true -> if (HermesBrowserSignIn.isRequired(status, forcedBrowser)) BROWSER else PASSWORD
                null -> null
            }
    }
}

/**
 * Port of `BotHostStatus` (`BotProtocol.swift`): the public `GET /api/status` fields the
 * connection screen and the connect form read. Every field is optional because hosts add,
 * omit and rename them between releases.
 */
data class HermesStatus(
    val version: String? = null,
    /** The host's `install_id`, one per Hermes root; null when omitted. */
    val installID: String? = null,
    /** `auth_required`; null for a reply that isn't a dashboard's. */
    val authRequired: Boolean? = null,
    /** `auth_flows`, such as `native_pkce` (#708). */
    val authFlows: List<String> = emptyList(),
    /** `auth_providers`, such as `basic`. */
    val authProviders: List<String> = emptyList(),
    val gatewayRunning: Boolean? = null,
    /** `starting`, `running`, `draining`, `degraded`, `startup_failed` or `stopped` at the pin. */
    val gatewayState: String? = null,
    /** Null after a clean stop. */
    val gatewayExitReason: String? = null,
    /** Seconds since the gateway's last heartbeat, set only while its process is alive but wedged. */
    val heartbeatStale: Double? = null,
    val platformsConnected: Int? = null,
    val platformsConfigured: Int? = null,
    /** Every Profile's name, which the host reports even behind its sign-in (#1180); null when none. */
    val profiles: List<String>? = null,
    /** How the host says a phone signs in; a password for a status without `auth_required`. */
    val signInMethod: HermesSignInMethod = HermesSignInMethod.PASSWORD,
    /** The reply as it came, for callers that read fields this build doesn't name. */
    val raw: BotJSON = BotJSON.NULL,
) {
    companion object {
        fun from(json: BotJSON, forcedBrowser: Boolean = false): HermesStatus = HermesStatus(
            version = json["version"].text,
            installID = json["install_id"].text?.takeIf { it.isNotEmpty() },
            authRequired = json["auth_required"].flag,
            authFlows = json["auth_flows"].list?.mapNotNull { it.text }.orEmpty(),
            authProviders = json["auth_providers"].list?.mapNotNull { it.text }.orEmpty(),
            gatewayRunning = json["gateway_running"].flag,
            gatewayState = json["gateway_state"].text,
            gatewayExitReason = json["gateway_exit_reason"].text,
            heartbeatStale = json["gateway_heartbeat_stale_s"].number,
            platformsConnected = json["components"]["platforms"]["connected"].integer,
            platformsConfigured = json["components"]["platforms"]["configured"].integer,
            profiles = json["profiles"].list?.let { items ->
                val seen = LinkedHashSet<String>()
                items.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() && seen.add(it) }
            },
            signInMethod = HermesSignInMethod.from(json, forcedBrowser) ?: HermesSignInMethod.PASSWORD,
            raw = json,
        )
    }
}

/**
 * Port of `BotHostProbeFailure`: why the status probe produced no status. Kept apart from
 * [BotFailure], whose copy is about chats.
 */
sealed class HermesStatusProbeFailure : Exception() {
    /** The transport failed; carries the system's reason. */
    data class Unreachable(val reason: String) : HermesStatusProbeFailure()

    /** Something in front of Hermes refused the public route: a 401 or 403, or a redirect elsewhere. */
    object Blocked : HermesStatusProbeFailure() {
        private fun readResolve(): Any = Blocked
    }

    data class Answered(val status: Int) : HermesStatusProbeFailure()

    /** A 200 whose body is not a JSON object. */
    object NotHermes : HermesStatusProbeFailure() {
        private fun readResolve(): Any = NotHermes
    }
}

/**
 * Port of `BotHostStatusProbe`: one unauthenticated `GET /api/status` on its own short-lived
 * client. It sends no cookies or credentials, so checking never counts against the host's
 * sign-in limit; only the saved Connection Headers, which a proxy such as Cloudflare Access
 * needs even for this public route. A redirect to another host drops them.
 */
class HermesStatusProbe(base: OkHttpClient = OkHttpClient()) {
    private val template: OkHttpClient = base.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(address: HttpUrl, headers: HermesHeaders = HermesHeaders.NONE): Result<HermesStatus> {
        val client = template.newBuilder().addNetworkInterceptor(headers.redirectGuard(address)).build()
        val request = headers.applied(HermesREST.Status.request(address), address)
        return try {
            val (data, response) = client.exchange(request)
            if (!response.request.url.host.equals(request.url.host, ignoreCase = true) || response.code in setOf(401, 403)) {
                return Result.failure(HermesStatusProbeFailure.Blocked)
            }
            if (response.code != 200) return Result.failure(HermesStatusProbeFailure.Answered(response.code))
            val json = BotJSON.parseOrNull(data)?.takeIf { it.isObject }
                ?: return Result.failure(HermesStatusProbeFailure.NotHermes)
            Result.success(HermesStatus.from(json))
        } catch (error: IOException) {
            Result.failure(HermesStatusProbeFailure.Unreachable(error.toString()))
        }
    }
}
