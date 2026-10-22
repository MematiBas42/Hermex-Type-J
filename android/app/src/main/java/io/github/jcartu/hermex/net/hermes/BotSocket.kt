package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One inbound frame, or the end of the socket, as [BotSocket.frames] delivers it. */
sealed class BotSocketFrame {
    /** A text frame; JSON-RPC uses text frames. */
    data class Text(val text: String) : BotSocketFrame()

    /** A binary frame, decoded as UTF-8 JSON like a text one. */
    class Data(val bytes: ByteArray) : BotSocketFrame()

    /** The other end closed the socket with this code. */
    data class Closed(val code: Int, val reason: String) : BotSocketFrame()

    /**
     * The socket failed: a refused upgrade ([status] is its HTTP status, so the gateway can
     * tell a 403 from a dropped line), or a read or write error after it opened.
     */
    class Failed(val error: Throwable, val status: Int?) : BotSocketFrame()
}

/**
 * Port of `BotSocket` (`HermesGateway.swift`): the socket boundary, which permits scripted
 * frames without a backend. OkHttp pushes frames; [frames] queues them so the gateway's reader
 * pulls one at a time, as `URLSessionWebSocketTask.receive()` does, and can time silence out.
 */
interface BotSocket {
    val frames: ReceiveChannel<BotSocketFrame>

    /** Sends one text frame; throws when the socket can no longer take it. */
    suspend fun send(text: String)

    /** Closes the socket now, with a normal close, and ends [frames]. */
    fun cancel()
}

/**
 * Port of `NativeBotSocket`: the production socket on OkHttp. A refused upgrade arrives as
 * [BotSocketFrame.Failed] with the handshake's HTTP status; a socket the other end closed is
 * logged with its close code, never its reason: a proxy can put any bytes there.
 */
class OkHttpBotSocket(client: OkHttpClient, request: Request, private val label: String) : BotSocket {
    private val inbound = Channel<BotSocketFrame>(Channel.UNLIMITED)
    private val closedHere = AtomicBoolean(false)

    override val frames: ReceiveChannel<BotSocketFrame> get() = inbound

    private val webSocket: WebSocket = client.newWebSocket(
        request,
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                inbound.trySend(BotSocketFrame.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbound.trySend(BotSocketFrame.Data(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedHere.get()) {
                    HermesConnectionLog.error("$label closed by the other end with code $code")
                }
                webSocket.close(code, null)
                inbound.trySend(BotSocketFrame.Closed(code, reason))
                inbound.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbound.trySend(BotSocketFrame.Closed(code, reason))
                inbound.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedHere.get()) inbound.trySend(BotSocketFrame.Failed(t, response?.code))
                inbound.close()
            }
        },
    )

    override suspend fun send(text: String) {
        if (!webSocket.send(text)) throw BotFailure.Transport
    }

    override fun cancel() {
        closedHere.set(true)
        webSocket.close(1000, null)
        webSocket.cancel()
        inbound.close()
    }
}

/** The failure a [BotSocketFrame.Failed] stands for, as the gateway reads it. */
internal fun BotSocketFrame.Failed.asFailure(): Throwable {
    status?.let { BotFailure.fromUpgradeStatus(it)?.let { refusal -> return refusal } }
    return when (error) {
        is BotFailure -> error
        is IOException -> BotFailure.Network(error)
        else -> BotFailure.Transport
    }
}
