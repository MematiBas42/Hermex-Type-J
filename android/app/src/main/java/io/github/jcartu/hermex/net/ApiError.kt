package io.github.jcartu.hermex.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Port of `Networking/APIError.swift`.
 *
 * Every request in [HermesApi] fails with one of these cases; ViewModels catch
 * them and surface [message]. The user-facing strings mirror the Swift
 * `errorDescription` exactly (English inline — v1 is English-only), except the
 * iOS-only App Transport Security case, which has no Android equivalent.
 */
sealed class ApiError(cause: Throwable? = null) : Exception(cause) {

    /** The stored server URL cannot be parsed into an HTTP(S) URL. */
    object InvalidServerUrl : ApiError() {
        private fun readResolve(): Any = InvalidServerUrl
    }

    /** Transport-level failure — the request never produced an HTTP response. */
    class Network(val underlying: Throwable) : ApiError(underlying)

    /** Non-2xx HTTP response (2xx bodies never reach here; 401 maps to [Unauthorized]). */
    class Http(val statusCode: Int, val body: String?) : ApiError()

    /** A 2xx response whose body could not be decoded into the expected type. */
    class Decoding(val underlying: Throwable) : ApiError(underlying)

    /** HTTP 401 — the session cookie or password was rejected. */
    object Unauthorized : ApiError() {
        private fun readResolve(): Any = Unauthorized
    }

    override val message: String
        get() = when (this) {
            is InvalidServerUrl ->
                "Enter a valid server URL, for example https://hermes.yourdomain.com or http://<server-tailscale-ip>:8787."
            is Network -> networkMessage(underlying)
            is Http -> httpMessage(statusCode, body)
            is Decoding -> "The server response could not be read."
            is Unauthorized -> "The password was rejected. Check the server password and try again."
        }

    val privacySafeLogCategory: String
        get() = when (this) {
            is InvalidServerUrl -> "invalidServerURL"
            is Network -> "network.${underlying.javaClass.simpleName}"
            is Http -> "http.$statusCode"
            is Decoding -> "decoding"
            is Unauthorized -> "unauthorized"
        }

    /** Machine-readable `code` from the server error payload, when present. */
    val serverCode: String?
        get() = (this as? Http)?.let { serverErrorPayload(it.body)?.code }

    /** Human-readable message from the server error payload, when present. */
    val serverMessage: String?
        get() = (this as? Http)?.let { serverErrorMessage(it.body) }

    /**
     * The `active_stream_id` a 409 conflict reports when another stream is
     * already running for the session; null for any other failure.
     */
    val activeStreamId: String?
        get() {
            val http = this as? Http ?: return null
            if (http.statusCode != 409) return null
            return serverErrorPayload(http.body)?.activeStreamId?.trim()?.ifEmpty { null }
        }

    /** True for a 404 whose payload says the chat stream no longer exists. */
    val indicatesMissingStream: Boolean
        get() {
            val http = this as? Http ?: return false
            if (http.statusCode != 404) return false
            return serverErrorMessage(http.body)?.contains("stream not found", ignoreCase = true) == true
        }

    /**
     * True for the documented "prompt already expired" respond rejection:
     * HTTP 409 with `{"stale": true, …}` in the body (issue #25). Used to show
     * a friendly expired state instead of a generic failure.
     */
    val indicatesExpiredPendingPrompt: Boolean
        get() {
            val http = this as? Http ?: return false
            if (http.statusCode != 409) return false
            return serverErrorPayload(http.body)?.stale == true
        }
    /**
     * Port of `APIError.sessionRotation`: only the durable compression conflict
     * (HTTP 409, `code: "session_rotated"`, a well-formed
     * `continuation_session_id`) authorizes continuation navigation. Decoded
     * separately so malformed continuation metadata cannot hide other errors.
     */
    val sessionRotation: SessionRotation?
        get() = (this as? Http)?.let { sessionRotation(it.statusCode, it.body) }

    /**
     * Set for hermes-webui's stale-runtime 409 (#955): Hermes Agent was updated
     * under the running WebUI, which refuses every local Agent action until it
     * is restarted on the server. [message] already shows its copy; chat uses
     * it to offer "Copy fix prompt".
     */
    val agentRuntimeStale: AgentRuntimeStale?
        get() = (this as? Http)?.let { agentRuntimeStale(it.statusCode, it.body) }

    /** Port of `APIError.SessionRotation`: a validated continuation session id. */
    data class SessionRotation private constructor(val continuationSessionId: String) {
        companion object {
            /** Null for an empty id or one carrying whitespace, control or path characters. */
            fun of(continuationSessionId: String): SessionRotation? {
                if (continuationSessionId.isEmpty()) return null
                if (continuationSessionId.any { it.isWhitespace() || it.isISOControl() }) return null
                if (continuationSessionId.any { it == '/' || it == '\\' || it == '?' || it == '#' }) return null
                return SessionRotation(continuationSessionId)
            }
        }
    }

    @Serializable
    private data class ErrorPayload(
        val error: String? = null,
        val message: String? = null,
        val detail: String? = null,
        val code: String? = null,
        val stale: Boolean? = null,
        @SerialName("active_stream_id") val activeStreamId: String? = null,
    )

    companion object {
        private val payloadJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun privacySafeLogCategory(error: Throwable): String = when (error) {
            is ApiError -> error.privacySafeLogCategory
            is kotlinx.coroutines.CancellationException -> "cancelled"
            else -> "other"
        }

        private fun networkMessage(error: Throwable): String {
            val underlying = (error as? Network)?.underlying ?: error
            return when (underlying) {
                is SocketTimeoutException ->
                    "The server did not respond in time. Check that the Mac is awake, hermes-webui is running, and the tunnel is connected."
                is UnknownHostException ->
                    "Could not find that server. Check the URL and Cloudflare DNS hostname."
                is ConnectException, is NoRouteToHostException ->
                    "Could not connect to the server. Check that hermes-webui is running and the tunnel is connected."
                is SSLException ->
                    "The HTTPS connection failed. Check the server URL and certificate."
                is InterruptedIOException ->
                    // OkHttp signals call timeouts and cancellation this way.
                    "The request was cancelled."
                is SocketException ->
                    "Could not connect to the server. Check that hermes-webui is running and the tunnel is connected."
                else ->
                    "Could not reach the server. Check the URL, network connection, and tunnel status."
            }
        }

        private fun httpMessage(statusCode: Int, body: String?): String {
            agentRuntimeStale(statusCode, body)?.let { return it.message }
            if (isVanishedSession(statusCode, body)) {
                return "That session no longer exists on the server. Reopen another session or create a new one."
            }

            return when (statusCode) {
                -1 -> "The server response could not be read. Check that the URL points to a Hermes Web UI server."
                400 -> displayableServerMessage(body)?.let { "The server rejected the request: $it" }
                    ?: "The server rejected the request."
                // A 403 is a per-request refusal (read-only imported session, missing
                // permission), not a bad password: 401 owns the password copy (1.6.0).
                403 -> displayableServerMessage(body)?.let { "The server refused the request: $it" }
                    ?: "The server refused the request. Check the server permissions and try again."
                404 -> "The server endpoint was not found. Check that the URL points to a Hermes Web UI server."
                408 -> "The server did not respond in time. Check that the server is running and the connection is available."
                429 -> "The server is receiving too many requests. Wait a moment, then try again."
                500 -> "The Hermes server hit an internal error. Check the server logs, then try again."
                502, 503, 504 ->
                    "Could not connect to the server. Check that hermes-webui is running and the tunnel is connected."
                else -> displayableServerMessage(body)?.let { "Server returned HTTP $statusCode: $it" }
                    ?: "Server returned HTTP $statusCode."
            }
        }

        /** Longest server-provided message interpolated into user-facing copy. */
        private const val DISPLAYED_SERVER_MESSAGE_LIMIT = 200

        /** The structured server message, bounded for display so an oversized body never floods an alert. */
        private fun displayableServerMessage(body: String?): String? {
            val message = serverErrorMessage(body) ?: return null
            if (message.length <= DISPLAYED_SERVER_MESSAGE_LIMIT) return message
            return message.take(DISPLAYED_SERVER_MESSAGE_LIMIT) + "…"
        }

        /**
         * Decoded apart from [ErrorPayload], so a `type` of another shape in some
         * other error can't hide that error's message or `active_stream_id`.
         */
        @Serializable
        private data class AgentRuntimeStalePayload(
            val type: String? = null,
            @SerialName("agent_update_state") val agentUpdateState: String? = null,
        )

        private fun agentRuntimeStale(statusCode: Int, body: String?): AgentRuntimeStale? {
            if (statusCode != 409 || body.isNullOrEmpty()) return null
            val payload = runCatching { payloadJson.decodeFromString<AgentRuntimeStalePayload>(body) }.getOrNull()
                ?: return null
            if (payload.type != "agent_runtime_stale") return null
            return AgentRuntimeStale.fromAgentUpdateState(payload.agentUpdateState)
        }

        @Serializable
        private data class SessionRotationPayload(
            val code: String? = null,
            @SerialName("continuation_session_id") val continuationSessionId: String? = null,
        )

        private fun sessionRotation(statusCode: Int, body: String?): SessionRotation? {
            if (statusCode != 409 || body.isNullOrEmpty()) return null
            val payload = runCatching { payloadJson.decodeFromString<SessionRotationPayload>(body) }.getOrNull()
                ?: return null
            if (payload.code != "session_rotated") return null
            return SessionRotation.of(payload.continuationSessionId ?: return null)
        }

        private fun isVanishedSession(statusCode: Int, body: String?): Boolean {
            if (statusCode != 404) return false
            return serverErrorMessage(body)?.contains("Session not found", ignoreCase = true) == true
        }

        private fun serverErrorMessage(body: String?): String? {
            val payload = serverErrorPayload(body) ?: return null
            val message = payload.error ?: payload.message ?: payload.detail
            return message?.trim()?.ifEmpty { null }
        }

        private fun serverErrorPayload(body: String?): ErrorPayload? {
            if (body.isNullOrEmpty()) return null
            return runCatching { payloadJson.decodeFromString<ErrorPayload>(body) }.getOrNull()
        }
    }
}
