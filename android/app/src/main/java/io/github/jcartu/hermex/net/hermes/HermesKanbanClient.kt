package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanAddCommentResponse
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanBoardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBoardSnapshot
import io.github.jcartu.hermex.model.KanbanBoardsResponse
import io.github.jcartu.hermex.model.KanbanBulkActionEnvelope
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanBulkActionType
import io.github.jcartu.hermex.model.KanbanCardActionRequest
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanCardDetailRequest
import io.github.jcartu.hermex.model.KanbanCardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanDependencyMutationEnvelope
import io.github.jcartu.hermex.model.KanbanDependencyMutationRequest
import io.github.jcartu.hermex.model.KanbanDispatchRequest
import io.github.jcartu.hermex.model.KanbanDispatchResult
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanStats
import io.github.jcartu.hermex.model.KanbanWorkerLog
import io.github.jcartu.hermex.model.KanbanWorkerLogRequest
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Port of `KanbanCapabilityError.kanbanUnavailable`: a host without the Kanban plugin (404 on `/config`). */
class KanbanUnavailable : Exception() {
    override val message: String get() = "Kanban isn't available on this Hermes host. Install and enable the Kanban plugin, then restart the dashboard."
}

/** Port of `KanbanWriteRefusal`: a 400 or 409 whose body has the host's `detail`. */
class KanbanWriteRefusal(val status: Int, override val message: String) : Exception()

/** Port of `KanbanRequestError.runningStatusRequiresDispatcher`. */
class KanbanRunningStatusRequiresDispatcher : Exception() {
    override val message: String get() = "A Card can only enter Running through the Dispatcher."
}

/** Port of `KanbanDispatchResponseError.missingResultCategories`. */
class KanbanDispatchResultIncomplete : Exception() {
    override val message: String get() = "The Dispatcher answered without any result category."
}

/** Port of `KanbanResponseError.nonJSONContentType`: a reply that is not a JSON object. */
class KanbanNonJSONReply : Exception() {
    override val message: String get() = "The Kanban plugin answered with something other than JSON."
}

/**
 * Port of `HermesKanbanClient` (`Networking/Hermes/HermesKanbanClient.swift`): Kanban on a
 * Hermes host (#1043): the host's Kanban plugin under `/api/plugins/kanban`, read and written
 * (#1044) over the sign-in, cookie jar and headers the server's Bot screens share, adapted to
 * the Kanban models webui fills:
 * - host paths and process data ([HOST_ONLY_KEYS]) are dropped before decoding;
 * - every reply reads `read_only: false`, because the host has no read-only mode;
 * - the Assigned Profile filter runs here, because the host's `/board` takes none;
 * - block and unblock are Status changes, a prerequisite reply is filled with the request's
 *   ids, and a 400 or 409 throws [KanbanWriteRefusal] with the host's `detail`.
 *
 * A 404 on `/config` is a host without the Kanban plugin ([KanbanUnavailable]). Live updates
 * come over the Board's Kanban socket ([KanbanWebSocketEventClient], #1045). The host has no
 * REST events route; a `since` request reads as unchanged while the Board's `latest_event_id`
 * still equals it.
 */
class HermesKanbanClient(val http: HermesConnection) {

    fun makeEventStream(options: KanbanWebSocketEventClient.Options = KanbanWebSocketEventClient.Options()): KanbanWebSocketEventClient =
        KanbanWebSocketEventClient(http, options)

    suspend fun kanbanConfiguration(): KanbanConfiguration = try {
        read(HermesREST.KanbanConfig, KanbanConfiguration.serializer())
    } catch (error: BotFailure.Rejected) {
        if (error.code == 404) throw KanbanUnavailable()
        throw error
    }

    suspend fun kanbanBoards(): KanbanBoardsResponse = read(HermesREST.KanbanBoards, KanbanBoardsResponse.serializer())

    suspend fun kanbanBoard(request: KanbanBoardRequest): KanbanBoardSnapshot {
        val snapshot = read(
            HermesREST.KanbanBoard(board = request.board, tenant = request.tenant, includeArchived = request.includeArchived),
            KanbanBoardSnapshot.serializer(),
        )
        // The host's `/board` takes no `since`, so the reply's `latest_event_id` answers it the
        // way webui's `changed` does: a poll or a foreground check keeps an unchanged Board.
        val changed = request.since?.let { it != snapshot.latestEventId } ?: snapshot.changed
        val assignee = request.assignee?.takeIf { it.isNotEmpty() }
        if (assignee == null && changed == snapshot.changed) return snapshot
        return snapshot.copy(
            columns = if (assignee != null) {
                snapshot.columns?.map { column -> KanbanColumn(name = column.name, cards = column.cards?.filter { it.assignee == assignee }) }
            } else {
                snapshot.columns
            },
            changed = changed,
        )
    }

    suspend fun kanbanStats(board: String): KanbanStats = read(HermesREST.KanbanStats(board), KanbanStats.serializer())

    suspend fun kanbanAssignees(board: String): KanbanAssigneeHistory =
        read(HermesREST.KanbanAssignees(board), KanbanAssigneeHistory.serializer())

    suspend fun kanbanCardDetail(request: KanbanCardDetailRequest): KanbanCardDetailEnvelope =
        read(HermesREST.KanbanTask(request.cardId, request.board), KanbanCardDetailEnvelope.serializer())

    suspend fun kanbanWorkerLog(request: KanbanWorkerLogRequest): KanbanWorkerLog =
        read(
            HermesREST.KanbanTaskLog(request.cardId, request.board, tailBytes = request.tailBytes.coerceIn(1, 2_000_000)),
            KanbanWorkerLog.serializer(),
        )

    suspend fun createKanbanCard(request: KanbanCreateCardRequest): KanbanCardMutationEnvelope {
        // `status` would be ignored: the host starts a Card in Triage, To Do or Ready by its own
        // rule. No Assigned Profile is null, because `""` is a 400.
        val body = linkedMapOf(
            "title" to BotJSON.string(request.title),
            "assignee" to (request.assignee?.let(BotJSON::string) ?: BotJSON.NULL),
            "triage" to BotJSON.bool(request.status == "triage"),
            "idempotency_key" to BotJSON.string(request.idempotencyKey),
        )
        request.body?.let { body["body"] = BotJSON.string(it) }
        request.tenant?.let { body["tenant"] = BotJSON.string(it) }
        request.priority?.let { body["priority"] = BotJSON.number(it) }
        body["workspace_kind"] = BotJSON.string(request.workspaceKind)
        request.prerequisiteId?.let { body["parents"] = BotJSON.array(listOf(BotJSON.string(it))) }
        request.skills?.let { body["skills"] = BotJSON.array(it.map(BotJSON::string)) }
        request.maxRuntimeSeconds?.let { body["max_runtime_seconds"] = BotJSON.number(it) }
        return write(HermesREST.KanbanCreateTask(request.board, body), KanbanCardMutationEnvelope.serializer())
    }

    /**
     * Never sends `tenant`, which the host can't change after create. An unassigned Card is
     * `""`, because null leaves the assignee as it is. [changesAssignee] says whether
     * `assignee` differs from the server's Card the save replaces: a Hermes host reassigns on
     * any `assignee` it gets, and refuses a running Card's with a 409, so it goes only when changed.
     */
    suspend fun editKanbanCard(request: KanbanEditCardRequest, changesAssignee: Boolean = true): KanbanCardMutationEnvelope {
        val body = linkedMapOf(
            "title" to BotJSON.string(request.title),
            "body" to BotJSON.string(request.body),
            "priority" to BotJSON.number(request.priority),
        )
        if (changesAssignee) body["assignee"] = BotJSON.string(request.assignee ?: "")
        request.status?.let { body["status"] = BotJSON.string(it) }
        return write(HermesREST.KanbanUpdateTask(request.cardId, request.board, body), KanbanCardMutationEnvelope.serializer())
    }

    suspend fun setKanbanCardStatus(request: KanbanCardStatusRequest): KanbanCardMutationEnvelope {
        if (request.status.trim().lowercase() == "running") throw KanbanRunningStatusRequiresDispatcher()
        return write(
            HermesREST.KanbanUpdateTask(request.cardId, request.board, mapOf("status" to BotJSON.string(request.status))),
            KanbanCardMutationEnvelope.serializer(),
        )
    }

    /** Blocked is a Status here, which the host may turn into Triage after repeat blocks. */
    suspend fun blockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope {
        val body = linkedMapOf("status" to BotJSON.string("blocked"))
        request.reason?.let { body["block_reason"] = BotJSON.string(it) }
        return write(HermesREST.KanbanUpdateTask(request.cardId, request.board, body), KanbanCardMutationEnvelope.serializer())
    }

    /** Ready from Blocked unblocks, landing the Card in Ready, To Do or Review by the host's rule. */
    suspend fun unblockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope =
        write(
            HermesREST.KanbanUpdateTask(request.cardId, request.board, mapOf("status" to BotJSON.string("ready"))),
            KanbanCardMutationEnvelope.serializer(),
        )

    suspend fun addKanbanComment(request: KanbanAddCommentRequest): KanbanAddCommentResponse =
        write(HermesREST.KanbanComment(request.cardId, request.board, request.body), KanbanAddCommentResponse.serializer())

    suspend fun addKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope =
        write(
            HermesREST.KanbanLink(request.board, request.prerequisiteId, request.dependentId),
            KanbanDependencyMutationEnvelope.serializer(), echoing = request,
        )

    suspend fun removeKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope =
        write(
            HermesREST.KanbanUnlink(request.board, request.prerequisiteId, request.dependentId),
            KanbanDependencyMutationEnvelope.serializer(), echoing = request,
        )

    /** The same body webui takes: an unassigned Card is `""`, and only Archive sends `archive`. */
    suspend fun performKanbanBulkAction(request: KanbanBulkActionRequest): KanbanBulkActionEnvelope {
        val body = linkedMapOf("ids" to BotJSON.array(request.cardIds.map(BotJSON::string)))
        when (request.action) {
            KanbanBulkActionType.CHANGE_STATUS -> body["status"] = BotJSON.string(request.value ?: "")
            KanbanBulkActionType.ASSIGN_PROFILE -> body["assignee"] = BotJSON.string(request.value ?: "")
            KanbanBulkActionType.SET_PRIORITY -> body["priority"] = BotJSON.number(request.value?.toIntOrNull() ?: 0)
            KanbanBulkActionType.ARCHIVE -> body["archive"] = BotJSON.bool(true)
        }
        return write(HermesREST.KanbanBulk(request.board, body), KanbanBulkActionEnvelope.serializer())
    }

    suspend fun dispatchKanban(request: KanbanDispatchRequest): KanbanDispatchResult {
        val result = write(HermesREST.KanbanDispatch(request.board, request.dryRun), KanbanDispatchResult.serializer())
        if (!result.hasKnownCategory) throw KanbanDispatchResultIncomplete()
        return result
    }

    suspend fun createKanbanBoard(request: KanbanCreateBoardRequest): KanbanBoardMutationEnvelope =
        write(
            HermesREST.KanbanCreateBoard(
                mapOf(
                    "slug" to BotJSON.string(request.slug), "name" to BotJSON.string(request.name),
                    "description" to BotJSON.string(request.description), "icon" to BotJSON.string(request.icon),
                    "color" to BotJSON.string(request.color),
                ),
            ),
            KanbanBoardMutationEnvelope.serializer(),
        )

    suspend fun editKanbanBoard(request: KanbanEditBoardRequest): KanbanBoardMutationEnvelope =
        write(
            HermesREST.KanbanEditBoard(
                request.slug,
                mapOf(
                    "name" to BotJSON.string(request.name), "description" to BotJSON.string(request.description),
                    "icon" to BotJSON.string(request.icon), "color" to BotJSON.string(request.color),
                ),
            ),
            KanbanBoardMutationEnvelope.serializer(),
        )

    suspend fun archiveKanbanBoard(slug: String): KanbanBoardMutationEnvelope =
        write(HermesREST.KanbanArchiveBoard(slug), KanbanBoardMutationEnvelope.serializer())

    suspend fun makeKanbanBoardActive(slug: String): KanbanBoardMutationEnvelope =
        write(HermesREST.KanbanSwitchBoard(slug), KanbanBoardMutationEnvelope.serializer())

    private suspend fun <T> read(rest: HermesREST, serializer: DeserializationStrategy<T>): T =
        decoded(adapted(BotJSON.parseOrNull(http.data(rest))), serializer)

    /**
     * Sends a write and decodes a 2xx reply. A prerequisite write passes its request as [echoing]:
     * the host names neither Card, so the reply is filled with them. A 400 or 409 whose body
     * has a `detail` throws [KanbanWriteRefusal]; any other status throws [BotFailure.Rejected].
     */
    private suspend fun <T> write(
        rest: HermesREST,
        serializer: DeserializationStrategy<T>,
        echoing: KanbanDependencyMutationRequest? = null,
    ): T {
        val reply = http.reply(rest)
        if (reply.status !in 200..299) {
            val detail = reply.json?.get("detail")?.text
            if ((reply.status == 400 || reply.status == 409) && !detail.isNullOrEmpty()) throw KanbanWriteRefusal(reply.status, detail)
            throw BotFailure.Rejected(reply.status)
        }
        val filled: Map<String, JsonElement> = echoing?.let {
            mapOf("ok" to JsonPrimitive(true), "parent_id" to JsonPrimitive(it.prerequisiteId), "child_id" to JsonPrimitive(it.dependentId))
        } ?: emptyMap()
        return decoded(adapted(reply.json, filled), serializer)
    }

    private fun <T> decoded(element: JsonElement, serializer: DeserializationStrategy<T>): T = try {
        JSON.decodeFromJsonElement(serializer, element)
    } catch (error: SerializationException) {
        throw KanbanNonJSONReply()
    } catch (error: IllegalArgumentException) {
        throw KanbanNonJSONReply()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        /** Keys whose values are host paths or process data, dropped at every depth. */
        val HOST_ONLY_KEYS: Set<String> = setOf(
            "workspace_path", "stored_path", "path", "db_path", "default_workdir", "new_path", "worker_pid", "claim_lock",
        )

        /**
         * `adapted(_:filling:)` — a reply without [HOST_ONLY_KEYS], marked writable, with
         * [filling]'s top-level keys set. A body that is not a JSON object is an incompatible reply.
         */
        fun adapted(reply: BotJSON?, filling: Map<String, JsonElement> = emptyMap()): JsonObject {
            val obj = reply?.element as? JsonObject ?: throw KanbanNonJSONReply()
            val kept = LinkedHashMap<String, JsonElement>()
            for ((key, value) in obj) {
                if (key in HOST_ONLY_KEYS) continue
                kept[key] = withoutHostOnlyKeys(value)
            }
            kept.putAll(filling)
            kept["read_only"] = JsonPrimitive(false)
            return JsonObject(kept)
        }

        private fun withoutHostOnlyKeys(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it !in HOST_ONLY_KEYS }.mapValues { withoutHostOnlyKeys(it.value) })
            is JsonArray -> JsonArray(value.map { withoutHostOnlyKeys(it) })
            else -> value
        }
    }
}

/** One frame of a Board's Kanban event socket as the client reports it. */
sealed class KanbanSocketFrame {
    /** The first pong or frame proved the upgrade. */
    object Opened : KanbanSocketFrame()

    /** `{events, cursor}`, as the host sends it whenever events land. */
    data class Events(val payload: BotJSON) : KanbanSocketFrame()
}

/**
 * Port of `KanbanWebSocketEventClient` (`HermesKanbanClient.swift`): live Kanban events from
 * a Hermes host (#1045): one WebSocket per open Board, `/api/plugins/kanban/events?board=&since=&ticket=`
 * on the Board's [HermesConnection]. Every connect mints a fresh single-use ticket there,
 * never the gateway's, so the upgrade carries the connection's headers to its own origin only.
 * It offers no subprotocol.
 *
 * The host sends `{events, cursor}` only when events land: no hello and no heartbeat, and a
 * dashboard bound to loopback sends no protocol pings either, so a tunnel would close a quiet
 * socket at about 100 s. This client pings every [Options.keepaliveMillis] (OkHttp's own
 * ping/pong, which fails the socket when a pong is overdue). A 403 on the upgrade, the host's
 * answer to a used or expired ticket, opens once more on a fresh ticket before the failure is
 * reported. [stop] closes the socket cleanly, and nothing from it is reported after.
 */
class KanbanWebSocketEventClient(private val http: HermesConnection, private val options: Options = Options()) {
    /** Tests script the socket; production takes the defaults. */
    data class Options(
        /** The ping cadence, and how long a ping may go without its pong. */
        val keepaliveMillis: Long = 25_000,
        /** Gets the finished upgrade; null opens an OkHttp socket on the connection's client. */
        val socketFactory: ((Request) -> BotSocket)? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + HermesActor.dispatcher)
    private var onFrame: ((KanbanSocketFrame) -> Unit)? = null
    private var onFailure: (() -> Unit)? = null

    /** The Board and cursor of the current [start], for the one fresh-ticket retry. */
    private var target: Pair<String, Int>? = null
    private var mayRetryRefusal = false

    /** Numbers sockets. Anything from an older one, or from before [stop], is dropped. */
    private var generation = 0
    private var socket: BotSocket? = null
    private var opener: Job? = null
    private var reader: Job? = null
    private var isOpen = false

    fun start(board: String, since: Int, onFrame: (KanbanSocketFrame) -> Unit, onFailure: () -> Unit) {
        scope.launch {
            close()
            this@KanbanWebSocketEventClient.onFrame = onFrame
            this@KanbanWebSocketEventClient.onFailure = onFailure
            target = board to since
            mayRetryRefusal = true
            connect()
        }
    }

    fun stop() {
        scope.launch { stopNow() }
    }

    private fun stopNow() {
        close()
        onFrame = null
        onFailure = null
        target = null
    }

    /** Mints a ticket and opens a socket on it. */
    private fun connect() {
        val (board, since) = target ?: return
        val owner = generation
        opener = scope.launch {
            try {
                val upgrade = http.kanbanEventsUpgrade(board, since)
                open(upgrade, owner)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error, owner)
            }
        }
    }

    private fun open(upgrade: Request, owner: Int) {
        if (owner != generation) return
        val socket = options.socketFactory?.invoke(upgrade) ?: OkHttpBotSocket(
            http.socketClient.newBuilder().pingInterval(options.keepaliveMillis, TimeUnit.MILLISECONDS).build(),
            upgrade, label(owner),
        )
        this.socket = socket
        reader = scope.launch {
            try {
                while (true) {
                    val frame = try {
                        socket.frames.receive()
                    } catch (error: ClosedReceiveChannelException) {
                        throw BotFailure.Transport
                    }
                    if (generation != owner) return@launch
                    when (frame) {
                        is BotSocketFrame.Text -> consume(BotJSON.parseOrNull(frame.text))
                        is BotSocketFrame.Data -> consume(BotJSON.parseOrNull(frame.bytes))
                        is BotSocketFrame.Closed -> throw BotFailure.Transport
                        is BotSocketFrame.Failed -> throw frame.asFailure()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error, owner)
            }
        }
        // OkHttp reports the accepted upgrade at once; a scripted socket's first frame does.
        if (options.socketFactory == null) markAlive()
    }

    private fun consume(payload: BotJSON?) {
        markAlive()
        onFrame?.invoke(KanbanSocketFrame.Events(payload ?: BotJSON.NULL))
    }

    /** A pong or a frame: the socket is alive, and the first one reports it open. */
    private fun markAlive() {
        if (isOpen) return
        isOpen = true
        mayRetryRefusal = false
        HermesConnectionLog.notice("${label(generation)} open")
        onFrame?.invoke(KanbanSocketFrame.Opened)
    }

    private fun fail(error: Throwable, owner: Int) {
        if (owner != generation) return
        val retry = mayRetryRefusal && error == BotFailure.UpgradeRefused(403)
        val name = label(owner)
        val wasOpen = isOpen
        val reason = HermesConnectionLog.reason(error)
        close()
        if (retry) {
            mayRetryRefusal = false
            connect()
            return
        }
        if (wasOpen) HermesConnectionLog.error("$name dropped: $reason") else HermesConnectionLog.error("$name failed to open: $reason")
        val onFailure = this.onFailure
        stopNow()
        if (onFailure == null) return
        // Reported from a task of its own: the one failing here may be the reader, which
        // `close()` has just cancelled. A later start or stop drops it.
        val reported = generation
        scope.launch {
            if (generation == reported) onFailure()
        }
    }

    /** Closes the socket and ends its tasks; the callbacks stay for a retry. */
    private fun close() {
        generation += 1
        opener?.cancel(); opener = null
        reader?.cancel(); reader = null
        socket?.cancel(); socket = null
        isOpen = false
    }

    /** Names socket [number] in the log, such as `c1 kanban socket s0`, never the Board or host. */
    private fun label(number: Int): String = "c${http.serial} kanban socket s$number"
}
