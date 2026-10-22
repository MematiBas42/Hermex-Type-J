package io.github.jcartu.hermex.service.hermes

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotPendingRequest
import io.github.jcartu.hermex.model.hermes.BotServerRequest
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesActor
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesTurnOutcome
import io.github.jcartu.hermex.service.RunNotificationOutcome
import io.github.jcartu.hermex.ui.feature.chat.hermes.BotClientTransport
import io.github.jcartu.hermex.ui.feature.chat.hermes.BotTransport
import io.github.jcartu.hermex.ui.feature.chat.hermes.ConversationTarget
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesConversation
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesConversationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The persisted identity and source connection a foreground Hermes observer owns. */
data class HermesRunTarget(
    val serverId: String,
    val connectionId: String,
    val profile: String,
    val storedKey: String,
    val title: String,
)

/** A notification-safe summary of one request which still needs the user. */
data class HermesRunPrompt(
    val id: String,
    val summary: String,
)

/**
 * State reported by [HermesRunMonitor]. Request-clear flags deliberately start false: an
 * omitted `open_requests` member is not evidence that the host has no request of that kind.
 */
data class HermesRunMonitorUpdate(
    val active: Boolean = false,
    val outcome: RunNotificationOutcome? = null,
    val approval: HermesRunPrompt? = null,
    val question: HermesRunPrompt? = null,
    val approvalsConfirmedClear: Boolean = false,
    val questionsConfirmedClear: Boolean = false,
    val status: String = "Connecting",
)

/**
 * A read-only foreground owner for one direct-Hermes run.
 *
 * The monitor uses the same attach/replay/snapshot engine as the chat. Its target is a stored
 * session key and Profile; the runtime returned by `session.resume` is ephemeral and fences all
 * live events and the sole deliberate write, [interrupt]. Recovery only resumes, replays and
 * snapshots. It never submits or re-submits a prompt.
 */
class HermesRunMonitor internal constructor(
    private val target: HermesRunTarget,
    connection: BotConnection,
    private val wire: BotTransport,
    private val dispatcher: CoroutineDispatcher,
    reconnectDelay: suspend (Long) -> Unit = { delay(it) },
) : HermesConversationOwner {
    constructor(http: HermesConnection, target: HermesRunTarget) : this(
        target = target,
        connection = http.connection,
        wire = BotClientTransport(BotClient(http)),
        dispatcher = HermesActor.dispatcher,
    )

    init {
        require(target.serverId.isNotBlank()) { "Hermes run target requires a server" }
        require(target.connectionId.isNotBlank()) { "Hermes run target requires a connection" }
        require(target.profile.isNotBlank()) { "Hermes run target requires a Profile" }
        require(target.storedKey.isNotBlank()) { "Hermes run target requires a stored session" }
        require(connection.id == target.connectionId) { "Hermes run target does not own this connection" }
    }

    private val monitorJob = SupervisorJob()
    private val scope = CoroutineScope(monitorJob + dispatcher)
    private val engine = HermesConversation(
        server = target.serverId,
        connection = connection,
        target = ConversationTarget.Session(target.profile, target.storedKey),
        wire = wire,
        scope = scope,
        reconnectDelay = reconnectDelay,
    )

    private var currentUpdate = HermesRunMonitorUpdate()
    private val _updates = MutableStateFlow(currentUpdate)
    val updates: StateFlow<HermesRunMonitorUpdate> = _updates.asStateFlow()

    private val finished = CompletableDeferred<Unit>()
    private var started = false
    private var closed = false
    private var everConnected = false
    private var connectionStatus: String? = "Connecting"
    private var snapshotReady = false
    private var pendingOutcome: RunNotificationOutcome? = null
    private var interruptClaimed = false
    private var interruptRequested = false
    private var interruptInFlight = false
    private var reattachTask: Job? = null

    /** Valid parsed envelopes retained across reconnects until host evidence replaces them. */
    private var openRequests = linkedMapOf<String, BotServerRequest>()
    private var requestRevision = 0L
    private var replayRevision = 0L
    private var snapshotRevision = 0L

    init {
        engine.owner = this
    }

    /**
     * Observes until the run has a host-confirmed terminal outcome and every request actually
     * seen by this monitor is confirmed gone, or until [close] (or caller cancellation) ends
     * this observer. The monitor closes only its own [BotClient] consumer before returning.
     */
    suspend fun run() {
        try {
            withContext(dispatcher) {
                if (!closed && !started) {
                    started = true
                    engine.activate()
                }
            }
            finished.await()
        } finally {
            withContext(NonCancellable) { shutDown(if (finished.isCompleted) null else "Stopped") }
        }
    }

    /**
     * Sends the native interrupt once, bound to the currently attached runtime and generation.
     * A lost reply is never retried and [close] never calls this method implicitly.
     */
    suspend fun interrupt() = withContext(dispatcher) {
        if (closed || interruptClaimed || currentUpdate.outcome != null || !currentUpdate.active) return@withContext
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED) throw BotFailure.Transport
        val runtime = engine.runtime ?: throw BotFailure.Transport
        val attempt = engine.generation
        interruptClaimed = true
        interruptRequested = true
        interruptInFlight = true
        publish()
        try {
            engine.write(HermesCall.SessionInterrupt(runtime), attempt, runtime) {
                if (closed || engine.runtime != runtime || engine.generation != attempt || !currentUpdate.active) {
                    throw BotFailure.Stale
                }
            }
        } catch (error: Throwable) {
            interruptRequested = false
            throw error
        } finally {
            interruptInFlight = false
            publish()
        }
    }

    /** Ends this observer and its socket consumer without interrupting or closing host work. */
    suspend fun close() {
        withContext(NonCancellable) { shutDown("Stopped") }
    }

    private suspend fun shutDown(stoppedStatus: String?) {
        withContext(dispatcher) {
            if (closed) return@withContext
            closed = true
            reattachTask?.cancel()
            reattachTask = null
            if (stoppedStatus != null && currentUpdate.outcome == null) {
                connectionStatus = stoppedStatus
                publish(checkFinished = false)
            }
            finished.complete(Unit)
            engine.suspendConnection()
        }
        monitorJob.cancel()
    }

    override fun conversationDidReset() {
        if (closed) return
        snapshotReady = false
        connectionStatus = if (everConnected) "Reconnecting" else "Connecting"
        publish()
    }

    override fun conversationWillReplay(newRuntime: Boolean) {
        snapshotReady = false
        replayRevision = requestRevision
    }

    override fun conversationDidReplay(reply: BotJSON, frames: List<BotJSON>) {
        if (replayRevision == requestRevision) applyOpenRequests(reply)
        frames.forEach(::applyFrame)
        snapshotRevision = requestRevision
    }

    override suspend fun conversationDidReadSnapshot(snapshot: BotJSON, runtime: String, attempt: Int) {
        if (runtime != engine.runtime || !engine.isCurrent(snapshot)) throw BotFailure.WrongIdentity
        if (snapshotRevision == requestRevision) applyOpenRequests(snapshot)
        applySnapshot(snapshot)
        snapshotReady = true
        publish()
    }

    override suspend fun conversationDidConnect(runtime: String, attempt: Int) {
        if (runtime != engine.runtime || attempt != engine.generation) throw BotFailure.Stale
        everConnected = true
        connectionStatus = null
        publish()
    }

    override fun conversationDidReceive(frame: BotJSON, afterGap: Boolean) {
        applyFrame(frame)
        if (afterGap) reattach()
    }

    override fun conversationDidLoseFrames() {
        reattach()
    }

    override fun conversationDidHold(frame: BotJSON) {
        if (frame["type"].text == "request.cancel") requestRevision += 1
    }

    override fun conversationDidReceiveRequest(envelope: BotJSON) {
        val request = BotServerRequest.from(envelope) ?: return
        if (request.sessionID != engine.runtime) return
        requestRevision += 1
        openRequests[request.id] = request
        val update = currentUpdate
        currentUpdate = when (val pending = request.pending) {
            is BotPendingRequest.Approval -> update.copy(
                approval = approvalPrompt(pending),
                approvalsConfirmedClear = false,
            )
            is BotPendingRequest.Question -> update.copy(
                question = questionPrompt(pending),
                questionsConfirmedClear = false,
            )
            else -> when (request.method) {
                "approval" -> update.copy(approvalsConfirmedClear = false)
                "clarify" -> update.copy(questionsConfirmedClear = false)
                else -> update
            }
        }
        publish()
    }

    override fun conversationWillDisconnect() {
        if (closed) return
        connectionStatus = "Reconnecting"
        publish()
    }

    override fun conversationDidDisconnect(failure: BotFailure, retrying: Boolean) {
        if (closed) return
        if (retrying) {
            connectionStatus = "Reconnecting"
            publish()
            return
        }
        connectionStatus = if (failure === BotFailure.Stale) "Connection changed" else "Connection unavailable"
        publish(checkFinished = false)
        finished.complete(Unit)
    }

    private fun reattach() {
        if (closed || reattachTask?.isActive == true) return
        reattachTask = scope.launch {
            try {
                engine.suspendConnection()
                if (!closed) engine.activate()
            } finally {
                reattachTask = null
            }
        }
    }

    /** Applies only the run-state event vocabulary admitted by [HermesConversation]. */
    private fun applyFrame(frame: BotJSON) {
        when (frame["type"].text) {
            "message.start" -> {
                pendingOutcome = null
                interruptRequested = false
                currentUpdate = currentUpdate.copy(active = true, outcome = null)
            }
            "message.complete" -> if (currentUpdate.outcome == null) {
                pendingOutcome = when (frame["payload"]["status"].text) {
                    "interrupted" -> RunNotificationOutcome.CANCELLED
                    "error" -> RunNotificationOutcome.FAILED
                    else -> RunNotificationOutcome.COMPLETED
                }
            }
            "error" -> if (currentUpdate.outcome == null) {
                pendingOutcome = RunNotificationOutcome.FAILED
            }
            "session.info" -> frame["payload"]["running"].flag?.let { running ->
                if (running) markActive() else settleRun()
            }
            "request.cancel" -> applyRequestCancel(frame["payload"])
        }
        publish()
    }

    private fun applySnapshot(snapshot: BotJSON) {
        val running = snapshot["running"].flag ?: snapshot["info"]["running"].flag ?: false
        if (running) {
            markActive()
            return
        }
        if (currentUpdate.outcome != null) return
        val ending = pendingOutcome
            ?: HermesTurnOutcome.fromInflight(snapshot["inflight"])?.let { RunNotificationOutcome.FAILED }
            ?: if (interruptRequested) RunNotificationOutcome.CANCELLED else RunNotificationOutcome.COMPLETED
        settleRun(ending)
    }

    private fun markActive() {
        val update = currentUpdate
        if (update.outcome != null) {
            pendingOutcome = null
            interruptRequested = false
        }
        currentUpdate = update.copy(active = true, outcome = null)
    }

    private fun settleRun(ending: RunNotificationOutcome = terminalCandidate()) {
        if (currentUpdate.outcome != null) return
        pendingOutcome = ending
        currentUpdate = currentUpdate.copy(active = false, outcome = ending)
    }

    private fun terminalCandidate(): RunNotificationOutcome =
        pendingOutcome ?: if (interruptRequested) RunNotificationOutcome.CANCELLED else RunNotificationOutcome.COMPLETED

    /**
     * Replaces request state only when `open_requests` is an actual array. A missing or malformed
     * member leaves both the retained prompts and their confirmation flags untouched.
     */
    private fun applyOpenRequests(container: BotJSON) {
        val member = container.fields?.get("open_requests") ?: return
        val rows = member.list ?: return
        val runtime = engine.runtime ?: return
        val previous = openRequests
        val next = linkedMapOf<String, BotServerRequest>()
        var approvalUnreadable = false
        var questionUnreadable = false
        for (row in rows) {
            val request = BotServerRequest.from(row)
            if (request != null && request.sessionID == runtime) next[request.id] = request
            val session = row["params"]["session_id"].text
            if (session != null && session != runtime) continue
            when (row["method"].text) {
                "approval" -> if (request?.sessionID != runtime || request.pending !is BotPendingRequest.Approval) {
                    approvalUnreadable = true
                }
                "clarify" -> if (request?.sessionID != runtime || request.pending !is BotPendingRequest.Question) {
                    questionUnreadable = true
                }
            }
        }
        if (approvalUnreadable || questionUnreadable) {
            for (request in previous.values) {
                when {
                    approvalUnreadable && request.pending is BotPendingRequest.Approval -> next.putIfAbsent(request.id, request)
                    questionUnreadable && request.pending is BotPendingRequest.Question -> next.putIfAbsent(request.id, request)
                }
            }
        }
        openRequests = next
        requestRevision += 1

        var approval: BotPendingRequest.Approval? = null
        var question: BotPendingRequest.Question? = null
        for (request in next.values) {
            when (val pending = request.pending) {
                is BotPendingRequest.Approval -> if (approval == null) approval = pending
                is BotPendingRequest.Question -> if (question == null) question = pending
                else -> Unit
            }
        }
        val update = currentUpdate
        currentUpdate = update.copy(
            approval = approval?.let(::approvalPrompt) ?: if (approvalUnreadable) update.approval else null,
            question = question?.let(::questionPrompt) ?: if (questionUnreadable) update.question else null,
            approvalsConfirmedClear = !approvalUnreadable && approval == null,
            questionsConfirmedClear = !questionUnreadable && question == null,
        )
    }

    private fun applyRequestCancel(payload: BotJSON) {
        val id = payload["id"].text?.takeIf(String::isNotEmpty) ?: return
        val method = payload["method"].text?.takeIf(String::isNotEmpty) ?: return
        requestRevision += 1
        val request = openRequests[id]?.takeIf { it.method == method } ?: return
        openRequests.remove(id)
        val update = currentUpdate
        when (request.pending) {
            is BotPendingRequest.Approval -> {
                var next: BotPendingRequest.Approval? = null
                for (remaining in openRequests.values) {
                    next = remaining.pending as? BotPendingRequest.Approval ?: continue
                    break
                }
                currentUpdate = update.copy(
                    approval = next?.let(::approvalPrompt),
                    approvalsConfirmedClear = next == null,
                )
            }
            is BotPendingRequest.Question -> {
                var next: BotPendingRequest.Question? = null
                for (remaining in openRequests.values) {
                    next = remaining.pending as? BotPendingRequest.Question ?: continue
                    break
                }
                currentUpdate = update.copy(
                    question = next?.let(::questionPrompt),
                    questionsConfirmedClear = next == null,
                )
            }
            else -> Unit
        }
    }

    private fun approvalPrompt(pending: BotPendingRequest.Approval): HermesRunPrompt {
        val approval = pending.request
        val summary = nonEmpty(approval.consequence)
            ?: nonEmpty(approval.command)
            ?: nonEmpty(approval.toolName)
            ?: "Approval required"
        return HermesRunPrompt(approval.requestID, summary)
    }

    private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

    private fun questionPrompt(pending: BotPendingRequest.Question): HermesRunPrompt {
        val question = pending.request
        val summary = question.questions.firstOrNull { !it.isAnswered }?.prompt
            ?: question.questions.firstOrNull()?.prompt
            ?: "The agent needs more information before continuing."
        return HermesRunPrompt(question.requestID, summary)
    }

    /** Recomputes presentation copy without changing any host-derived state. */
    private fun publish(checkFinished: Boolean = true) {
        val update = currentUpdate
        val status = when {
            update.outcome != null && update.approval != null && update.question != null -> "Approval and answer required"
            update.outcome != null && update.approval != null -> "Approval required"
            update.outcome != null && update.question != null -> "Answer required"
            update.outcome == RunNotificationOutcome.COMPLETED -> "Response complete"
            update.outcome == RunNotificationOutcome.FAILED -> "Response failed"
            update.outcome == RunNotificationOutcome.CANCELLED -> "Response cancelled"
            connectionStatus != null -> connectionStatus.orEmpty()
            interruptRequested -> "Stopping"
            update.approval != null && update.question != null -> "Approval and answer required"
            update.approval != null -> "Approval required"
            update.question != null -> "Answer required"
            update.active && pendingOutcome != null -> "Finishing"
            update.active -> "Running"
            else -> "Connecting"
        }
        if (status != update.status) currentUpdate = update.copy(status = status)
        // Replay can include an older idle/complete event before the current turn.
        // Do not let notification collectors observe it before the snapshot reconciles it.
        if (snapshotReady) {
            _updates.value = currentUpdate
        } else {
            val waiting = connectionStatus ?: "Connecting"
            if (_updates.value.status != waiting) _updates.value = _updates.value.copy(status = waiting)
        }
        if (checkFinished) finishIfSafe()
    }

    private fun finishIfSafe() {
        val update = currentUpdate
        if (snapshotReady && !interruptInFlight &&
            update.outcome != null && update.approval == null && update.question == null
        ) {
            finished.complete(Unit)
        }
    }
}
