package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.core.logging.HermexLog
import io.github.jcartu.hermex.model.hermes.BotFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The final reply of one HTTP exchange: its body and the response it came in. */
internal class HermesExchange(val body: ByteArray, val response: Response) {
    operator fun component1(): ByteArray = body
    operator fun component2(): Response = response
    val status: Int get() = response.code
}

/**
 * Sends [request] on this client and reads the whole body, off the caller's thread, so a
 * suspended caller is never blocked. A transport failure is the [IOException] OkHttp raised;
 * cancelling the coroutine cancels the call.
 */
internal suspend fun OkHttpClient.exchange(request: Request): HermesExchange {
    val response = newCall(request).await()
    val body = withContext(Dispatchers.IO) { response.use { it.body.bytes() } }
    return HermesExchange(body, response)
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

/**
 * Port of `HermesConnectionLog` (`HermesConnection.swift`): the device log for a Hermes
 * connection, under the category `HermesConnection`. Lines name connections `c1`, `c2`, …
 * and each connection's sockets `s0`, `s1`, …, never the server itself. Interpolate only
 * numbers, step, case and method names, the release `/api/status` reports and
 * [reason]; never a host, address, URL, session or runtime id, Profile name, title,
 * message text, ticket, replay epoch or install id. Events, deltas and keepalive pongs are
 * never logged.
 */
object HermesConnectionLog {
    private const val CATEGORY = "HermesConnection"

    fun notice(message: String) {
        HermexLog.i("$CATEGORY: $message")
    }

    fun error(message: String) {
        HermexLog.e("$CATEGORY: $message")
    }

    /**
     * `reason(_:)` — names [error] for a log line without its description or user info: a
     * transport error's message can hold the failing URL, and so the host, and a rejection
     * can carry the host's own text.
     */
    fun reason(error: Throwable): String = when (error) {
        is BotFailure -> error.logName
        is CancellationException -> "cancelled"
        is SerializationException -> "DecodingError"
        is IOException -> "IOException ${error.javaClass.simpleName}"
        else -> "${error.javaClass.simpleName}"
    }
}
