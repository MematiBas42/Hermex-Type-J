package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotRoomAction
import io.github.jcartu.hermex.model.hermes.BotRoomCapabilities
import io.github.jcartu.hermex.model.hermes.BotRoomEvent
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import io.github.jcartu.hermex.model.hermes.BotRoomLog
import io.github.jcartu.hermex.model.hermes.BotRoomStatus
import io.github.jcartu.hermex.model.hermes.BotRoomThread
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotRoomFailure
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/**
 * Port of `Features/Bots/BotRoomReader.swift`: one visible group room's state, bounded replay,
 * threads, polling, and guarded participant/lifecycle commands. It only uses `groups.*`; it never
 * opens or attaches the room's hidden `Group: …` session. Transport callbacks hop through [scope],
 * which the route supplies on Main.
 */
class BotRoomReader(
    val key: BotRoomKey,
    val connection: BotConnection,
    room: BotGroupRoom,
    private val scope: CoroutineScope,
    private val cache: BotHistoryCache,
    val initialSequence: Int? = null,
    private val makeWire: (BotConnection) -> BotTransport,
    private val onExpired: () -> Unit = {},
    private val onChanged: (BotGroupRoom) -> Unit = {},
    private val onDisbanded: () -> Unit = {},
) {
    enum class Link { IDLE, CONNECTING, LIVE, STOPPED }

    data class Send(
        val text: String,
        val eventID: String = UUID.randomUUID().toString().uppercase(),
        /** Existing detail replies keep this id; overview sends mint a new discussion. */
        val threadID: String,
        /** Null for the overview composer, otherwise the detail draft this send owns. */
        val contextThreadID: String?,
    ) {
        constructor(text: String, threadID: String? = null) : this(
            text = text,
            threadID = threadID ?: UUID.randomUUID().toString().uppercase(),
            contextThreadID = threadID,
        )

        /** Pinned gateway `user_event_id`: SHA-256 of the client retry key. */
        val serverEventID: String
            get() = "user:" + MessageDigest.getInstance("SHA-256")
                .digest(eventID.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    enum class FeedbackEvent { SENT, STOPPED, APPROVED_ONCE, DENIED }
    data class Feedback(val id: Long, val event: FeedbackEvent)

    data class State(
        val room: BotGroupRoom,
        val events: List<BotRoomEvent> = emptyList(),
        val threads: List<BotRoomThread> = emptyList(),
        val status: BotRoomStatus = BotRoomStatus.EMPTY,
        val link: Link = Link.IDLE,
        val errorMessage: String? = null,
        val needsSignIn: Boolean = false,
        val hasEarlier: Boolean = false,
        val loadingEarlier: Boolean = false,
        val foreignAuthority: Boolean = false,
        val drafts: Map<String, String> = emptyMap(),
        val uncertainSends: Map<String, Send> = emptyMap(),
        val commandMessage: String? = null,
        val uncertainDisband: Boolean = false,
        val busy: Boolean = false,
        val awaitingStop: Boolean = false,
        val inactiveActions: Set<BotRoomAction.Identity> = emptySet(),
        val feedback: Feedback? = null,
    )

    private val _state = MutableStateFlow(State(room = room))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    @Volatile private var wire: BotTransport? = null
    @Volatile private var commandID: String? = null
    @Volatile private var dispatched = false
    @Volatile private var sending: Send? = null
    private var activeCall: HermesCall? = null
    private var pollJob: Job? = null
    private var viewOwner: String? = null
    private var didOpen = false
    private var stateRevision = 0L
    private var log = BotRoomLog()
    private var recentOwner: String? = null
    private var historyWriter: BotHistoryCache.WriterToken? = null
    private var hasRecentLog = false
    private var capabilities = BotRoomCapabilities.NONE
    private var lastAuthority: BotJSON? = null
    private var renaming = false
    private var feedbackID = 0L

    init {
        cache.recent.log(BotRecentTranscripts.Key(key))?.let { recent ->
            log = recent
            hasRecentLog = true
            publishLog()
        }
    }

    /**
     * Reconnects by closing the old client first, restoring recent/disk history synchronously
     * into state, then reading fresh capabilities, state and replay. Fresh state alone grants
     * actions or send permission.
     */
    suspend fun open(owner: String = UUID.randomUUID().toString(), preservingLoadedHistory: Boolean = false) {
        viewOwner = owner
        pauseInternal()
        if (preservingLoadedHistory && log.events.isNotEmpty()) {
            hasRecentLog = true
        } else {
            val recent = cache.recent.log(BotRecentTranscripts.Key(key))
            if (recent != null) {
                log = recent
                hasRecentLog = true
                publishLog()
            } else {
                log = BotRoomLog()
                hasRecentLog = false
                _state.update { it.copy(events = emptyList(), threads = emptyList(), hasEarlier = false) }
            }
        }
        recentOwner = cache.recent.begin(BotRecentTranscripts.Key(key))
        historyWriter = cache.beginWriter(key)

        val cached = runCatching { cache.roomHistory(key) }.getOrNull()
        restore(cached)
        if (current.needsSignIn) {
            _state.update { it.copy(link = Link.STOPPED) }
            return
        }

        val client = makeWire(connection)
        wire = client
        _state.update { it.copy(link = Link.CONNECTING, errorMessage = null, status = BotRoomStatus.EMPTY) }
        client.onDisconnect = { error ->
            scope.launch {
                if (wire === client) fail(error, client)
            }
        }
        try {
            check(client)
            client.connect()
            check(client)
            capabilities = BotRoomCapabilities.from(client.call(HermesCall.GroupsCapabilities))
            check(client)
            if (!capabilities.enabled) throw BotFailure.Unsupported
            if (current.uncertainDisband && reconcileDisband(client)) return

            val latest = readState(client)
            var initial = log
            val target = if (didOpen) null else initialSequence
            if ((!hasRecentLog && cached == null) || latest < initial.cursor) {
                initial = initial.beginning(latest)
                if (target != null && target > 0 && target <= latest) {
                    initial = initial.beginning(if (latest - target < 199) latest else target + 199)
                }
            }
            if (target != null && target > 0 && target <= initial.earlierBoundary) {
                val earlier = readPages(client, since = target - 1, through = initial.earlierBoundary)
                check(client)
                for (page in earlier) initial = initial.applyPage(page)
                initial = initial.loadedEarlier(target - 1)
            }
            val pages = readPages(client, since = initial.cursor)
            check(client)
            for (page in pages) initial = initial.applyPage(page)
            log = initial
            publishLog()
            if (observeAuthority(pages.lastOrNull(), client)) readState(client)
            check(client)
            _state.update { it.copy(link = Link.LIVE) }
            didOpen = true
            hasRecentLog = true
            saveRecentTranscript()
            pollJob = scope.launch {
                while (wire === client && currentCoroutineContext().isActive) {
                    delay(current.status.pollIntervalMillis)
                    if (wire !== client || !currentCoroutineContext().isActive) return@launch
                    poll()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error, client)
        }
    }

    /** Timer-independent for focused tests and explicit post-command reconciliation. */
    suspend fun poll() {
        val client = wire ?: return
        if (current.link != Link.LIVE) return
        try {
            val latest = readState(client)
            if (latest <= log.cursor) return
            val pages = readPages(client, since = log.cursor)
            check(client)
            for (page in pages) log = log.applyPage(page)
            publishLog()
            if (observeAuthority(pages.lastOrNull(), client)) readState(client)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error, client)
        }
    }

    suspend fun loadEarlier() {
        val client = wire ?: return
        if (current.link != Link.LIVE || !current.hasEarlier || current.loadingEarlier) return
        _state.update { it.copy(loadingEarlier = true) }
        val boundary = log.earlierBoundary
        val start = BotRoomLog.windowStart(boundary)
        try {
            val pages = readPages(client, since = start, through = boundary)
            check(client)
            for (page in pages) log = log.applyPage(page)
            log = log.loadedEarlier(start)
            publishLog()
            _state.update { it.copy(loadingEarlier = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error, client)
        }
    }

    suspend fun leave(owner: String, preservingLoadedHistory: Boolean = false) {
        if (viewOwner != owner) return
        if (preservingLoadedHistory) pauseInternal() else close()
        viewOwner = null
    }

    /** Lifecycle-safe leave: uses the reader's Main scope, not a Compose scope being disposed. */
    fun leaveAsync(owner: String, preservingLoadedHistory: Boolean = false) {
        scope.launch { leave(owner, preservingLoadedHistory) }
    }

    /** Stops all reads and invalidates late replies while keeping the loaded room log. */
    suspend fun pause() = pauseInternal()

    private suspend fun pauseInternal() {
        saveRecentTranscript()
        if (current.busy && dispatched) {
            sending?.let { request ->
                _state.update { state ->
                    state.copy(uncertainSends = state.uncertainSends + (draftKey(request.contextThreadID) to request))
                }
            }
            if (activeCall is HermesCall.GroupsDisband) _state.update { it.copy(uncertainDisband = true) }
            _state.update {
                it.copy(commandMessage = UNKNOWN_OUTCOME)
            }
        }
        commandID = null
        dispatched = false
        sending = null
        activeCall = null
        renaming = false
        _state.update { it.copy(busy = false, loadingEarlier = false) }
        val previous = wire
        wire = null
        pollJob?.cancel()
        pollJob = null
        previous?.onDisconnect = null
        previous?.onEvent = null
        runCatching { previous?.close() }
        _state.update { it.copy(link = Link.IDLE) }
    }

    /** Leaves only the warm/disk caches; runtime status, actions and transcript state are dropped. */
    suspend fun close() {
        pauseInternal()
        log = BotRoomLog()
        hasRecentLog = false
        capabilities = BotRoomCapabilities.NONE
        lastAuthority = null
        _state.update {
            it.copy(
                events = emptyList(),
                threads = emptyList(),
                hasEarlier = false,
                status = BotRoomStatus.EMPTY,
                foreignAuthority = false,
            )
        }
    }

    fun draft(threadID: String? = null): String = current.drafts[draftKey(threadID)].orEmpty()

    fun setDraft(text: String, threadID: String? = null) {
        _state.update { state -> state.copy(drafts = state.drafts + (draftKey(threadID) to text)) }
    }

    fun uncertainSend(threadID: String? = null): Send? = current.uncertainSends[draftKey(threadID)]

    val canParticipate: Boolean
        get() = current.link == Link.LIVE && !current.uncertainDisband && !current.foreignAuthority &&
            capabilities.authority != null && current.room.authority == capabilities.authority
    val showsComposer: Boolean get() = !current.foreignAuthority
    val showsWelcome: Boolean
        get() = current.link == Link.LIVE && !current.hasEarlier && !BotRoomEvent.hasConversation(current.events)
    val showsStop: Boolean get() = current.status.working || current.status.stopping > 0 || current.awaitingStop
    val mayStop: Boolean
        get() = allows("groups.stop") && !current.busy && !current.awaitingStop && current.status.stopping == 0 && current.status.stoppable > 0
    val showsRename: Boolean get() = !current.foreignAuthority && "groups.rename" in capabilities.methods
    val showsDisband: Boolean get() = !current.foreignAuthority && "groups.disband" in capabilities.methods
    val mayRename: Boolean get() = allows("groups.rename") && !current.busy
    val finishingStop: Boolean get() = current.status.stopping > 0 || current.awaitingStop
    val mayDisband: Boolean get() = allows("groups.disband") && !current.busy && !finishingStop

    fun mayEditDraft(threadID: String? = null): Boolean = !current.busy && uncertainSend(threadID) == null

    fun maySend(threadID: String? = null): Boolean =
        allows("groups.send") && mayEditDraft(threadID) && BotRoomRPC.validText(draft(threadID)) &&
            (threadID == null || current.threads.any { it.id == threadID })

    fun mayResend(threadID: String? = null): Boolean =
        allows("groups.send") && !current.busy && uncertainSend(threadID) != null

    fun mayAct(action: BotRoomAction): Boolean =
        !current.busy && action.isAnswerable && action.id !in current.inactiveActions && action in current.status.actions &&
            allows(if (action.isRetry) "groups.retry" else "groups.approve")

    suspend fun rename(name: String) {
        if (!mayRename || !BotRoomRPC.validName(name) || name == current.room.name) return
        renaming = true
        command(
            call = HermesCall.GroupsRename(key.roomID, UUID.randomUUID().toString().uppercase(), name),
            validate = {},
        ) { result ->
            val updated = BotGroupRoom.fromJsonOrNull(result["room"])
            if (updated == null || updated.id != key.roomID || updated.disbanded) throw BotFailure.Unsupported
            stateRevision += 1
            _state.update { it.copy(room = updated) }
            onChanged(updated)
        }
    }

    suspend fun disband() {
        if (!mayDisband) return
        val owner = viewOwner
        command(
            call = HermesCall.GroupsDisband(key.roomID),
            validate = {
                if (finishingStop) throw BotFailure.Stale
                _state.update { it.copy(uncertainDisband = true) }
            },
        ) { result ->
            if (result["tombstone"]["room_id"].text != key.roomID || result["tombstone"]["disbanded_at"].number == null) {
                throw BotFailure.Unsupported
            }
            finishDisband()
        }
        if (viewOwner == owner && current.uncertainDisband && current.link == Link.STOPPED) open(owner ?: UUID.randomUUID().toString())
    }

    /** Overview starts a new thread; detail continues its exact server-owned thread. */
    suspend fun send(retry: Boolean = false, threadID: String? = null) {
        if (retry && !mayResend(threadID) || !retry && !maySend(threadID)) return
        val request = if (retry) requireNotNull(uncertainSend(threadID)) else Send(draft(threadID), threadID)
        sending = request
        command(
            call = HermesCall.GroupsSend(key.roomID, request.eventID, request.text, request.threadID),
            validate = {},
        ) { result ->
            val eventJSON = result["event"]
            val event = BotRoomEvent.fromJsonOrNull(eventJSON)
            if (
                result["accepted"].flag != true || result["client_event_id"].text != request.eventID ||
                eventJSON["room_id"].text != key.roomID || event == null || event.kind != "message.user" ||
                event.payload["thread_id"].text != request.threadID || event.payload["text"].text == null
            ) throw BotFailure.Unsupported
            log = log.acknowledge(eventJSON)
            _state.update { state ->
                val key = draftKey(request.contextThreadID)
                state.copy(
                    uncertainSends = state.uncertainSends - key,
                    drafts = if (state.drafts[key] == request.text) state.drafts + (key to "") else state.drafts,
                )
            }
            sending = null
            publishLog()
            feedback(FeedbackEvent.SENT)
        }
    }

    suspend fun stop() {
        if (!mayStop) return
        command(
            call = HermesCall.GroupsStop(key.roomID, UUID.randomUUID().toString().uppercase()),
            validate = { if (current.status.stoppable <= 0) throw BotFailure.Stale },
        ) { result ->
            if (result["cancelled"].integer == null) throw BotFailure.Unsupported
            _state.update { it.copy(awaitingStop = true) }
            feedback(FeedbackEvent.STOPPED)
        }
    }

    suspend fun act(action: BotRoomAction, choice: BotApprovalChoice? = null) {
        if (!mayAct(action)) return
        val call = action.call(key.roomID, choice) ?: return
        command(
            call = call,
            validate = {
                if (action !in current.status.actions || action.id in current.inactiveActions) throw BotFailure.Stale
                _state.update { it.copy(inactiveActions = it.inactiveActions + action.id) }
            },
        ) { result ->
            if (result[if (action.isRetry) "retried" else "approved"].flag != true) throw BotFailure.Unsupported
            when (choice) {
                BotApprovalChoice.ONCE -> feedback(FeedbackEvent.APPROVED_ONCE)
                BotApprovalChoice.DENY -> feedback(FeedbackEvent.DENIED)
                else -> Unit
            }
        }
    }

    fun dismissCommandMessage() {
        _state.update { it.copy(commandMessage = null, errorMessage = null) }
    }

    private suspend fun command(
        call: HermesCall,
        validate: () -> Unit,
        accept: suspend (BotJSON) -> Unit,
    ) {
        val client = wire
        if (client == null || !allows(call.method) || current.busy) {
            sending = null
            return
        }
        val token = UUID.randomUUID().toString()
        val epoch = current.room.epoch
        commandID = token
        activeCall = call
        dispatched = false
        _state.update { it.copy(busy = true, commandMessage = null) }
        try {
            val result = client.call(call) {
                if (commandID != token || wire !== client || !allows(call.method) || current.room.epoch != epoch) throw BotFailure.Stale
                validate()
                dispatched = true
                stateRevision += 1
            }
            if (commandID != token || wire !== client) return
            check(client)
            accept(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (commandID != token || wire !== client) return
            if (error is BotRoomFailure) {
                if (call is HermesCall.GroupsDisband) _state.update { it.copy(uncertainDisband = false) }
                _state.update {
                    it.copy(
                        commandMessage = error.message,
                        foreignAuthority = it.foreignAuthority || error.reason == "authority_conflict",
                    )
                }
                if (error.expired) {
                    discardHistory()
                    close()
                    onExpired()
                    return
                }
            } else if (dispatched) {
                sending?.let { request ->
                    _state.update { state ->
                        state.copy(uncertainSends = state.uncertainSends + (draftKey(request.contextThreadID) to request))
                    }
                }
                _state.update { it.copy(commandMessage = UNKNOWN_OUTCOME) }
            } else {
                _state.update { it.copy(commandMessage = error.message ?: "Hermes could not finish this command.") }
            }
        }
        if (commandID != token || wire !== client) return
        commandID = null
        dispatched = false
        sending = null
        activeCall = null
        renaming = false
        _state.update { it.copy(busy = false) }
        if (current.uncertainDisband) {
            try {
                if (reconcileDisband(client)) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(error, client)
                return
            }
        }
        // Every acknowledgement and rejection, including 5118/5119, reconciles state once.
        poll()
    }

    private fun allows(method: String): Boolean = canParticipate && method in capabilities.methods

    private fun check(client: BotTransport) {
        if (wire !== client) throw BotFailure.Stale
    }

    private suspend fun readState(client: BotTransport): Int {
        stateRevision += 1
        val revision = stateRevision
        val value = client.call(HermesCall.GroupsState(key.roomID))
        check(client)
        val updated = BotGroupRoom.fromJsonOrNull(value["room"])
        if (updated == null || updated.id != key.roomID) throw BotFailure.Unsupported
        if (revision != stateRevision) return current.room.latestSeq
        if (updated.disbanded) throw BotRoomFailure(4114, null)
        if (!renaming && updated != current.room) {
            _state.update { it.copy(room = updated) }
            onChanged(updated)
        }
        val status = BotRoomStatus.from(value["driver_status"])
        val pendingIDs = status.actions.mapTo(HashSet(), BotRoomAction::id)
        _state.update { state ->
            state.copy(
                status = status,
                awaitingStop = if (!state.busy && status.stopping == 0) false else state.awaitingStop,
                inactiveActions = state.inactiveActions.filterTo(HashSet()) { identity ->
                    identity.kind != "retry" || identity in pendingIDs
                },
                foreignAuthority = updated.isForeign(capabilities.authority),
            )
        }
        return updated.latestSeq
    }

    private suspend fun readPages(
        client: BotTransport,
        since: Int,
        through: Int? = null,
    ): List<BotJSON> {
        var cursor = since
        val pages = ArrayList<BotJSON>()
        val receivedAt = System.currentTimeMillis()
        while (true) {
            val limit = minOf(capabilities.pageLimit, through?.let { maxOf(1, it - cursor) } ?: capabilities.pageLimit)
            val page = client.call(HermesCall.GroupsLog(key.roomID, cursor, limit))
            check(client)
            val next = page["cursor"].integer
            val more = page["has_more"].flag
            if (page["events"].list == null || next == null || next < cursor || more == null) throw BotFailure.Unsupported
            pages += page
            if (!more || through?.let { next >= it } == true) break
            if (next <= cursor) throw BotFailure.Unsupported
            cursor = next
        }
        val projection = BotJSON.objectOf(
            "events" to BotJSON.array(pages.flatMap { it["events"].list.orEmpty() }),
            "cursor" to (pages.lastOrNull()?.get("cursor") ?: BotJSON.number(since)),
        )
        runCatching {
            cache.appendRoom(
                key = key,
                room = current.room,
                page = projection,
                since = since,
                receivedAtMillis = receivedAt,
                writer = historyWriter,
            )
        }
        currentCoroutineContext().ensureActive()
        check(client)
        return pages
    }

    private fun observeAuthority(page: BotJSON?, client: BotTransport): Boolean {
        check(client)
        val authority = page?.get("authority")?.takeUnless { it.isNull } ?: return false
        val moved = lastAuthority != null && lastAuthority != authority
        lastAuthority = authority
        val owner = authority["gateway_id"].text
        val local = capabilities.authority
        if (owner != null && local != null && owner != local) _state.update { it.copy(foreignAuthority = true) }
        return moved
    }

    private fun restore(snapshot: BotHistoryCache.Snapshot?) {
        if (snapshot == null || hasRecentLog) return
        log = BotRoomLog()
            .applyPage(snapshot.replayPage)
            .loadedEarlier(snapshot.earlierBoundary ?: 0)
        publishLog()
    }

    private fun publishLog() {
        val pendingIDs = buildSet {
            current.uncertainSends.values.forEach { add(it.serverEventID) }
            sending?.let { add(it.serverEventID) }
        }
        val visible = log.events.filter { event -> event.kind != "message.user" || event.eventID !in pendingIDs }
        val hasEarlier = log.earlierBoundary > 0
        _state.update {
            it.copy(
                events = visible,
                threads = BotRoomThread.group(visible, hasEarlier),
                hasEarlier = hasEarlier,
            )
        }
    }

    private fun saveRecentTranscript() {
        val owner = recentOwner ?: return
        if (current.link != Link.LIVE || current.busy || current.uncertainDisband) return
        cache.recent.save(log.recentWindow(), BotRecentTranscripts.Key(key), owner)
    }

    private suspend fun discardHistory() {
        cache.recent.remove { it == BotRecentTranscripts.Key(key) }
        recentOwner = null
        runCatching { cache.removeRoom(key) }
    }

    private suspend fun finishDisband() {
        // This is a confirmed terminal result, so closing must not reinterpret the in-flight
        // command as an unknown outcome.
        commandID = null
        dispatched = false
        sending = null
        activeCall = null
        _state.update { it.copy(uncertainDisband = false, busy = false, commandMessage = null) }
        discardHistory()
        close()
        onDisbanded()
    }

    private suspend fun reconcileDisband(client: BotTransport): Boolean {
        val rooms = BotRoomList.read(client) { check(client) }
        return if (rooms.none { it.id == key.roomID }) {
            finishDisband()
            true
        } else {
            _state.update {
                it.copy(uncertainDisband = false, commandMessage = "The room is still present. Disband was not confirmed.")
            }
            false
        }
    }

    private suspend fun fail(error: Throwable, client: BotTransport) {
        if (wire !== client) return
        pauseInternal()
        _state.update {
            it.copy(
                link = Link.STOPPED,
                errorMessage = error.message ?: "Connection lost.",
                needsSignIn = it.needsSignIn || (error is BotFailure.Rejected && error.code == 401),
            )
        }
        if (error is BotRoomFailure && error.expired) {
            discardHistory()
            close()
            onExpired()
        }
    }

    private fun feedback(event: FeedbackEvent) {
        feedbackID += 1
        _state.update { it.copy(feedback = Feedback(feedbackID, event)) }
    }

    private fun draftKey(threadID: String?): String = threadID.orEmpty()

    companion object {
        private const val UNKNOWN_OUTCOME =
            "Outcome unknown. Reconnect to check the room. Commands are never resent automatically."
    }
}

/** A complete active-room read used to settle uncertain create/disband outcomes. */
object BotRoomList {
    suspend fun read(client: BotTransport, check: () -> Unit): List<BotGroupRoom> {
        val rooms = ArrayList<BotGroupRoom>()
        var offset = 0
        while (true) {
            val page = client.call(HermesCall.GroupsList(offset))
            check()
            val rows = page["rooms"].list ?: throw BotFailure.Unsupported
            for (row in rows) {
                val room = BotGroupRoom.fromJsonOrNull(row) ?: throw BotFailure.Unsupported
                if (!room.disbanded) rooms += room
            }
            if (page["next_offset"].isNull) return rooms
            val next = page["next_offset"].integer ?: throw BotFailure.Unsupported
            if (next <= offset) throw BotFailure.Unsupported
            offset = next
        }
    }
}
