package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.lang.ref.WeakReference

/**
 * Port of `HermesGateway` (`Networking/Hermes/HermesGateway.swift`): the one gateway
 * WebSocket a [HermesConnection] shares among its Bot screens. Each screen holds its own
 * [BotClient] on it. The socket opens when the first of them connects and closes when the
 * last one leaves, so it lives exactly while some Bot screen is connected. A backgrounded app
 * closes it once, silently ([closeForBackground]); screens reconnect when they come back.
 *
 * Every socket mints a fresh ticket and runs one handshake before anything else:
 * `gateway.ready` (recording [replayEpoch]), then `client.capabilities` as the first outbound
 * frame, then the keepalive (`gateway.ping` every [Options.heartbeatIntervalMillis]). Screens
 * that connect while it opens wait for that one attempt.
 *
 * A reply settles only the call that sent it. Every event and string-id server request goes
 * to every attached screen ([BotClient.onEvent]) and to [notifications]; the gateway never
 * answers a server request itself. A screen's `close()` ends only its own calls. A lost
 * socket (a read or send failure, [Options.silenceTimeoutMillis] of silence) ends every
 * attached screen's part, and each hears it once; anything later from that socket is dropped
 * by its generation. [retire] does the same with [BotFailure.Stale] and refuses reconnects.
 *
 * Reconnecting is the screen's: [BotClient.connectWithBackoff] runs the 1, 2, 4, 8, 16, then
 * 30-second schedule hermes-server.md § Reconnect contract names.
 *
 * All state lives on [HermesActor.dispatcher].
 */
class HermesGateway(val http: HermesConnection, private val options: Options = Options()) {

    /** Tests shorten the deadlines and script the socket; production takes the defaults. */
    data class Options(
        val rpcDeadlineMillis: Long = 30_000,
        /** The `gateway.ping` cadence. */
        val heartbeatIntervalMillis: Long = 15_000,
        /** A socket quiet for longer is dropped and reconnects. */
        val silenceTimeoutMillis: Long = 45_000,
        /** Gets the finished gateway upgrade; null opens an OkHttp socket on the connection's client. */
        val socketFactory: ((Request) -> BotSocket)? = null,
    )

    /** `replay_epoch` from the last socket's `gateway.ready`. */
    @Volatile
    var replayEpoch: String? = null
        private set

    private val notificationFlow = MutableSharedFlow<BotJSON>(extraBufferCapacity = 256)

    /** Every event and server request the socket delivered, as attached screens saw it. */
    val notifications: SharedFlow<BotJSON> = notificationFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + HermesActor.dispatcher)
    private var socket: BotSocket? = null

    /** The open attempt every connecting screen waits on; null once the socket is open. */
    private var opening: Deferred<Unit>? = null
    private var reader: Job? = null
    private var heartbeat: Job? = null

    /** Numbers sockets. A frame, failure or deadline from an older one is ignored. */
    private var generation = 0
    private var isRetired = false
    private val consumers = ArrayList<Consumer>()
    private var consumerCount = 0
    private var nextID = 0
    private val pending = HashMap<Int, Pending>()

    private class Consumer(val id: Int, client: BotClient, var attached: Boolean) {
        private val reference = WeakReference(client)
        val client: BotClient? get() = reference.get()
    }

    private class Pending(
        val reply: CompletableDeferred<BotJSON>,
        /** The screen that sent it; null for the handshake's own call. */
        val consumer: Int?,
        val method: String,
        val deadline: Job,
        val rejection: HermesCall.Rejection,
    )

    /** A new identity for one screen's [BotClient]. */
    fun makeConsumerID(): Int = synchronized(this) { ++consumerCount }

    fun isAttached(consumer: Int): Boolean = consumers.any { it.id == consumer && it.attached }

    /** Whether [consumer] may send: attached, or null for the handshake's own call. */
    private fun maySend(consumer: Int?): Boolean = consumer == null || isAttached(consumer)

    /**
     * Attaches [client] to the open socket, or waits for the socket to open, starting that
     * when nobody has. Throws what opening threw, [BotFailure.Transport] for a client whose
     * socket dropped before it could attach, and [BotFailure.Stale] once retired.
     */
    suspend fun join(client: BotClient) = withContext(HermesActor.dispatcher) {
        if (isRetired) throw BotFailure.Stale
        val isOpen = socket != null && opening == null
        consumers.removeAll { it.id == client.consumerID || it.client == null }
        consumers += Consumer(client.consumerID, client, attached = isOpen)
        if (isOpen) return@withContext
        (opening ?: open()).await()
        if (!isAttached(client.consumerID)) throw BotFailure.Transport
    }

    /**
     * Ends [client]'s part: its calls in flight fail with [BotFailure.Transport] and their
     * late replies reach no one. The last screen to leave closes the socket, silently.
     */
    suspend fun leave(client: BotClient) = withContext(HermesActor.dispatcher) { leaveNow(client) }

    /** [leave] for a caller already on the actor. */
    internal fun leaveNow(client: BotClient) {
        consumers.removeAll { it.id == client.consumerID }
        for ((id, entry) in pending.entries.toList()) {
            if (entry.consumer == client.consumerID) {
                pending.remove(id)
                entry.deadline.cancel()
                entry.reply.completeExceptionally(BotFailure.Transport)
            }
        }
        if (consumers.any { it.client != null }) return
        if (socket != null || opening != null) {
            HermesConnectionLog.notice("${socketLabel(generation)} closed, last screen left")
        }
        end(null)
    }

    /**
     * Closes the socket because the app went to the background, so the host sees a clean
     * close rather than a half-open socket. Silent: no attached screen hears it, because each
     * suspends on background itself and reconnects when active.
     */
    fun closeForBackground() {
        scope.launch {
            if (socket == null && opening == null) return@launch
            HermesConnectionLog.notice("${socketLabel(generation)} closed, app in background")
            end(null)
        }
    }

    /**
     * Ends the socket as lost after the dashboard restarted (#1075): attached screens hear
     * [BotFailure.Transport] once and reconnect on their own, as after any drop.
     */
    fun dropSocket() {
        scope.launch {
            if (socket == null && opening == null) return@launch
            HermesConnectionLog.notice("${socketLabel(generation)} closed, dashboard restarted")
            end(BotFailure.Transport)
        }
    }

    /** Ends the socket for good because the connection was replaced. Attached screens hear [BotFailure.Stale] once. */
    fun retire() {
        isRetired = true
        scope.launch { end(BotFailure.Stale) }
    }

    /**
     * Admits and sends one typed request for [consumer] (null: the handshake's own), then
     * waits for its reply. [HermesCall.params] refuses a value the host must never receive
     * and shapes it for the release the last sign-in read; [validateDispatch] runs at the
     * actual socket write. A cancelled read discards its late reply; a cancelled call that may
     * have reached the agent ends its screen's part.
     */
    suspend fun send(call: HermesCall, consumer: Int?, validateDispatch: (() -> Unit)? = null): BotJSON =
        withContext(HermesActor.dispatcher) {
            val params = call.params(http.serverVersion)
            ensureActive()
            val socket = socket
            if (socket == null || !maySend(consumer)) throw BotFailure.Stale
            nextID += 1
            val id = nextID
            val owner = generation
            val frame = BotJSON.objectOf(
                "jsonrpc" to BotJSON.string("2.0"), "id" to BotJSON.number(id),
                "method" to BotJSON.string(call.method), "params" to BotJSON.objectOf(params),
            )
            val text = if (call is HermesCall.FileAttach) {
                // Encoding a large attachment off the actor.
                val encoded = withContext(Dispatchers.Default) { frame.encoded }
                ensureActive()
                if (owner != generation || !maySend(consumer)) throw BotFailure.Stale
                encoded
            } else {
                frame.encoded
            }
            val deadlineMillis = call.deadlineMillis(options.rpcDeadlineMillis)
            val reply = CompletableDeferred<BotJSON>()
            val deadline = scope.launch {
                delay(deadlineMillis)
                if (generation != owner || !pending.containsKey(id)) return@launch
                HermesConnectionLog.error("${socketLabel(owner)}: ${call.method} got no reply in ${deadlineMillis / 1000} s")
                if (call.timesOutLocally) settle(id, BotFailure.Transport) else expire(id)
            }
            pending[id] = Pending(reply, consumer, call.method, deadline, call.rejection)
            // Validate at the actual socket dispatch.
            try {
                validateDispatch?.invoke()
            } catch (error: Throwable) {
                settle(id, error)
            }
            if (pending.containsKey(id)) {
                try {
                    socket.send(text)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    fail(error, owner)
                }
            }
            val result = try {
                reply.await()
            } catch (error: CancellationException) {
                cancel(id, owner, call.isCancellationSafe)
                throw error
            }
            when (call) {
                // A branch's reply names the runtime the host made for it (#1051).
                is HermesCall.SessionResume, is HermesCall.SessionBranch ->
                    result["session_id"].text?.takeIf { it.isNotEmpty() }?.let { http.noteAttached(it) }
                is HermesCall.SessionClose -> http.noteClosed(call.runtime)
                else -> Unit
            }
            result
        }

    /**
     * A cancelled read discards its late reply. Any other cancelled call may have reached the
     * agent, so its screen ends as `close()` ends it; the socket stays for the others.
     */
    private fun cancel(id: Int, owner: Int, safely: Boolean) {
        if (owner != generation) return
        val entry = pending[id] ?: return
        if (safely) {
            settle(id, CancellationException("cancelled"))
            return
        }
        val consumer = entry.consumer ?: return
        consumers.firstOrNull { it.id == consumer }?.client?.closeNow()
    }

    /**
     * A required call past its deadline. A screen's ends only that screen, which hears
     * [BotFailure.Transport] once; the handshake's own call means the socket never became usable.
     */
    private fun expire(id: Int) {
        val entry = pending[id] ?: return
        val consumer = entry.consumer
        if (consumer == null) {
            end(BotFailure.Transport)
            return
        }
        val client = consumers.firstOrNull { it.id == consumer }?.client
        if (client == null) {
            settle(id, BotFailure.Transport)
            return
        }
        leaveNow(client)
        client.socketEnded(BotFailure.Transport)
    }

    private fun settle(id: Int, error: Throwable) {
        val entry = pending.remove(id) ?: return
        entry.deadline.cancel()
        entry.reply.completeExceptionally(error)
    }

    private fun open(): Deferred<Unit> {
        val owner = generation
        val attempt = scope.async {
            try {
                handshake(owner)
                opening = null
                for (consumer in consumers) consumer.attached = true
            } catch (error: Throwable) {
                // An attempt a leave, fault or retirement already ended has nothing left to end.
                if (owner == generation) {
                    if (error !is CancellationException) {
                        HermesConnectionLog.error("${socketLabel(owner)} failed to open: ${HermesConnectionLog.reason(error)}")
                    }
                    end(null)
                }
                throw error
            }
        }
        opening = attempt
        return attempt
    }

    /**
     * Signs in unless the connection already is, mints a fresh ticket, opens the socket and
     * completes the handshake. No screen sends anything until it returns.
     */
    private suspend fun handshake(owner: Int) {
        fun check() {
            if (owner != generation) throw BotFailure.Stale
        }
        val upgrade = http.gatewayUpgrade()
        check()
        val label = socketLabel(owner)
        val socket = options.socketFactory?.invoke(upgrade) ?: OkHttpBotSocket(http.socketClient, upgrade, label)
        this.socket = socket
        val ready = receive(socket, label)
        check()
        val epoch = ready["params"]["payload"]["replay_epoch"].text
        if (ready["method"].text != "event" || ready["params"]["type"].text != "gateway.ready" || epoch.isNullOrEmpty()) {
            throw BotFailure.Unsupported
        }
        replayEpoch = epoch
        startReader(socket, owner)
        // Without this the host treats the socket as a build that predates server→client
        // requests: approvals are withdrawn unsent, clarify is answered empty, and sudo/secret
        // are skipped. A host older than the capability answers -32601 and connects as before.
        try {
            send(HermesCall.ClientCapabilities, null, null)
        } catch (error: BotFailure.Rejected) {
            // Any JSON-RPC rejection here is ignored, as the shared web client ignores it.
        }
        check()
        startHeartbeat(socket, owner)
        HermesConnectionLog.notice("$label open")
    }

    private fun startReader(socket: BotSocket, owner: Int) {
        val label = socketLabel(owner)
        reader = scope.launch {
            try {
                while (isActive) {
                    val frame = receive(socket, label)
                    if (generation != owner) return@launch
                    consume(frame, owner)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error, owner)
            }
        }
    }

    /**
     * Sends `gateway.ping` on a fixed cadence, because the host sends no JSON heartbeat of
     * its own and an idle socket would otherwise hit the silence deadline. The pong's string
     * id matches no pending call, so [consume] drops it. A failed send is a lost socket.
     */
    private fun startHeartbeat(socket: BotSocket, owner: Int) {
        heartbeat = scope.launch {
            var beat = 0
            while (isActive) {
                delay(options.heartbeatIntervalMillis)
                if (generation != owner) return@launch
                beat += 1
                val frame = """{"jsonrpc":"2.0","id":"heartbeat-$beat","method":"gateway.ping","params":{}}"""
                try {
                    socket.send(frame)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    fail(error, owner)
                    return@launch
                }
            }
        }
    }

    /** Reads one frame, or closes [socket] after the silence timeout. */
    private suspend fun receive(socket: BotSocket, label: String): BotJSON {
        val frame = withTimeoutOrNull(options.silenceTimeoutMillis) {
            try {
                socket.frames.receive()
            } catch (error: ClosedReceiveChannelException) {
                null
            }
        }
        if (frame == null) {
            if (socket.frames.isClosedForReceive) throw BotFailure.Transport
            HermesConnectionLog.error("$label: no frame for ${options.silenceTimeoutMillis / 1000} s; closing")
            socket.cancel()
            throw BotFailure.Transport
        }
        return when (frame) {
            is BotSocketFrame.Text -> BotJSON.parse(frame.text)
            is BotSocketFrame.Data -> BotJSON.parse(frame.bytes)
            is BotSocketFrame.Closed -> throw BotFailure.Transport
            is BotSocketFrame.Failed -> throw frame.asFailure()
        }
    }

    private fun consume(frame: BotJSON, owner: Int) {
        if (frame["method"].text == "event") {
            deliver(frame["params"], owner)
            return
        }
        // Server requests have string ids and no replay sequence. Forward the original
        // envelope so screens cannot mistake them for sequenced events.
        if (frame["id"].text != null && frame["method"].text != null) {
            deliver(frame, owner)
            return
        }
        // The keepalive's pongs have string ids and are dropped here, unlogged.
        val id = frame["id"].integer ?: return
        val entry = pending.remove(id)
        if (entry == null) {
            // Usually a late reply to a screen that has left.
            HermesConnectionLog.notice("${socketLabel(owner)}: reply for call $id matched no open call")
            return
        }
        entry.deadline.cancel()
        val code = frame["error"]["code"].integer
        if (code != null) {
            // The host lacks this method, so every screen on the connection leaves it off.
            // The handshake's own `client.capabilities` is exempt: an older host connects as before.
            if (code == -32601 && entry.consumer != null) http.noteUnavailable(entry.method)
            when (entry.rejection) {
                HermesCall.Rejection.ROOM ->
                    entry.reply.completeExceptionally(BotRoomFailure(code, frame["error"]["data"]["reason"].text))
                HermesCall.Rejection.SETTING ->
                    entry.reply.completeExceptionally(
                        BotSettingFailure.Rejected(code, frame["error"]["message"].text ?: BotFailure.Rejected(code).message),
                    )
                HermesCall.Rejection.PLAIN -> entry.reply.completeExceptionally(BotFailure.Rejected(code))
            }
        } else if (!frame["result"].isNull) {
            entry.reply.complete(frame["result"])
        } else {
            entry.reply.completeExceptionally(BotFailure.Unsupported)
        }
    }

    private fun deliver(event: BotJSON, owner: Int) {
        notificationFlow.tryEmit(event)
        for (consumer in consumers.toList()) {
            if (!consumer.attached) continue
            // A callback may close a screen or end the socket; check before each one.
            if (generation != owner) return
            if (!isAttached(consumer.id)) continue
            val client = consumer.client ?: continue
            client.onEvent?.invoke(event)
        }
    }

    private fun fail(error: Throwable, owner: Int) {
        if (owner != generation) return
        end(error)
    }

    /**
     * Closes the socket, fails every call on it with [BotFailure.Transport] and drops every
     * screen. The attached ones hear [error] once, and the log records it; null ends it
     * silently (the last screen left, the app went to the background, or opening failed and
     * its waiters get the error from [join]).
     */
    private fun end(error: Throwable?) {
        val label = socketLabel(generation)
        val wasLive = socket != null || opening != null
        generation += 1
        opening = null
        reader?.cancel(); reader = null
        heartbeat?.cancel(); heartbeat = null
        socket?.cancel(); socket = null
        val interrupted = pending.values.toList()
        pending.clear()
        val lost = consumers.filter { it.attached }.mapNotNull { it.client }
        consumers.clear()
        for (entry in interrupted) {
            entry.deadline.cancel()
            entry.reply.completeExceptionally(BotFailure.Transport)
        }
        if (error == null) return
        if (wasLive && error === BotFailure.Stale) {
            HermesConnectionLog.notice("$label closed, connection retired")
        } else if (wasLive) {
            HermesConnectionLog.error(
                "$label dropped: ${HermesConnectionLog.reason(error)}; calls failed: ${interrupted.size}, screens told: ${lost.size}",
            )
        }
        for (client in lost) client.socketEnded(error)
    }

    /** Names socket [number] of this connection in the log, such as `c1 socket s0`. */
    private fun socketLabel(number: Int): String = "c${http.serial} socket s$number"
}

/**
 * The reconnect schedule hermes-server.md § Reconnect contract names for a lost gateway socket
 * while a screen stays active: 1, 2, 4, 8, 16 and then at most 30 seconds, the last repeating.
 */
object HermesReconnectBackoff {
    val delaysMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    /** The wait before reconnect attempt number [attempt] (0 for the first). */
    fun delayMillis(attempt: Int): Long = delaysMillis[attempt.coerceIn(0, delaysMillis.lastIndex)]
}
