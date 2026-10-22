package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesReconnectBackoff
import kotlinx.coroutines.*

/** Port of `Features/Hermes/HermesConversation.swift`: the stored identity is never a runtime id. */
sealed class ConversationTarget {
    abstract val profile: String
    data class CanonicalChat(override val profile: String) : ConversationTarget()
    data class Session(override val profile: String, val key: String) : ConversationTarget()
    data class New(override val profile: String, val cwd: String? = null, val model: HermesCall.Model? = null) : ConversationTarget()
}

/** Port of HermesAnswerAction: the attach, runtime and request captured by a deliberate tap. */
data class HermesAnswerAction(val generation: Int, val runtime: String, val requestID: String)

/** Port of HermesRequestAnswer: every host request answer uses the same guarded path. */
sealed class HermesRequestAnswer {
    data class Approval(val choice: BotApprovalChoice) : HermesRequestAnswer()
    data class Questions(val answers: List<BotQuestionAnswer>) : HermesRequestAnswer()
    data class Value(val value: String) : HermesRequestAnswer()
}

/** Port of HermesConversationOwner. All callbacks run on the supplied Main scope. */
interface HermesConversationOwner {
    fun conversationDidReset()
    fun conversationDidIdentify(root: String) {}
    fun conversationWillReplay(newRuntime: Boolean) {}
    fun conversationDidReplay(reply: BotJSON, frames: List<BotJSON>)
    suspend fun conversationDidReadSnapshot(snapshot: BotJSON, runtime: String, attempt: Int)
    suspend fun conversationDidConnect(runtime: String, attempt: Int) {}
    fun conversationDidFailToAttach(error: Throwable) {}
    fun conversationDidReceive(frame: BotJSON, afterGap: Boolean)
    fun conversationDidLoseFrames()
    fun conversationDidHold(frame: BotJSON) {}
    fun conversationDidReceiveRequest(envelope: BotJSON) {}
    fun conversationWillDisconnect() {}
    fun conversationDidDisconnect(failure: BotFailure, retrying: Boolean)
}

/**
 * Port of `Features/Hermes/HermesConversation.swift`. One screen attaches, replays, reads its
 * snapshot, then releases the bounded live-frame hold. Recovery only reads: no command is
 * ever resent. Idle reclaim broadcasts reattach before the next send (#1234).
 */
class HermesConversation(
    val server: String,
    val connection: BotConnection,
    target: ConversationTarget,
    val wire: BotTransport,
    val scope: CoroutineScope,
    private val linkedRoot: String? = null,
    private val reconnectDelay: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class ConnectionState { DISCONNECTED, RECOVERING, CONNECTED }
    var owner: HermesConversationOwner? = null
    var onChange: () -> Unit = {}
    var target: ConversationTarget = target
        private set
    @Volatile var connectionState = ConnectionState.DISCONNECTED
        private set
    @Volatile var generation = 0
        private set
    var root: String? = linkedRoot
        private set
    var linkedRootIsStale = false
        private set
    var storedKey: String? = null
        private set
    @Volatile var runtime: String? = null
        private set
    var sequence = 0
        private set
    var epoch: String? = null
        private set
    var replayWasReset = false
        private set
    var isActive = false
        private set
    var isReconnecting = false
        private set
    private var shouldRetryConnection = false
    private var resumeRefusedSince: Long? = null
    private var attachTask: Deferred<Unit>? = null
    private var reconnectTask: Job? = null
    private val heldFrames = ArrayList<BotJSON>()
    private var framesPastHold = 0

    init {
        wire.onEvent = { event ->
            val receivedGeneration = generation
            scope.launch { if (generation == receivedGeneration) receive(event) }
        }
        wire.onDisconnect = { error ->
            val receivedGeneration = generation
            scope.launch { if (generation == receivedGeneration) disconnectNow(error) }
        }
    }

    suspend fun activate() {
        isActive = true
        if (connectionState == ConnectionState.CONNECTED) return
        attachTask?.let { it.await(); return }
        if (isReconnecting) return
        val task = scope.async(start = CoroutineStart.LAZY) { attach() }
        attachTask = task
        try { task.await() } finally { if (attachTask === task) attachTask = null }
    }

    suspend fun suspendConnection() {
        isActive = false
        shouldRetryConnection = false
        isReconnecting = false
        reconnectTask?.cancel(); reconnectTask = null
        attachTask?.cancel(); attachTask = null
        resumeRefusedSince = null
        reset()
    }

    fun adoptStoredKey(key: String?) {
        if (!key.isNullOrEmpty() && key != storedKey && connectionState == ConnectionState.CONNECTED) {
            storedKey = key
            onChange()
        }
    }

    fun check(attempt: Int) { if (generation != attempt) throw BotFailure.Stale }

    suspend fun request(call: HermesCall, attempt: Int, validateDispatch: (() -> Unit)? = null): BotJSON {
        currentCoroutineContext().ensureActive()
        check(attempt)
        val reply = wire.call(call) { check(attempt); validateDispatch?.invoke() }
        currentCoroutineContext().ensureActive()
        check(attempt)
        return reply
    }

    suspend fun write(call: HermesCall, attempt: Int, runtime: String, stillCurrent: (() -> Unit)? = null): BotJSON =
        request(call, attempt) {
            if (this.runtime != runtime || connectionState != ConnectionState.CONNECTED) throw BotFailure.Stale
            stillCurrent?.invoke()
        }

    suspend fun resume(attempt: Int): BotJSON {
        try {
            return request(HermesCall.SessionResume(target.profile, storedKey.orEmpty(), true), attempt)
        } catch (error: BotFailure.Rejected) {
            if (error.code == 4007 || error.code == 4009) throw ResumeRefusal(error.code)
            throw error
        }
    }

    fun isCurrent(reply: BotJSON): Boolean = reply["session_id"].text == runtime && attachedKey(reply) == storedKey

    fun disconnect(error: Throwable) { scope.launch { disconnectNow(error) } }

    private suspend fun disconnectNow(error: Throwable) {
        owner?.conversationWillDisconnect()
        connectionState = ConnectionState.DISCONNECTED
        wire.close()
        heldFrames.clear(); framesPastHold = 0
        val refusal = error as? ResumeRefusal
        val failure = refusal?.let { BotFailure.Rejected(it.code) } ?: (error as? BotFailure) ?: BotFailure.Transport
        shouldRetryConnection = if (refusal != null) {
            val since = resumeRefusedSince ?: now().also { resumeRefusedSince = it }
            now() - since < 60_000
        } else {
            resumeRefusedSince = null
            failure == BotFailure.Transport || failure is BotFailure.Network ||
                failure is BotFailure.Rejected && (failure.code in setOf(408, 429) || failure.code in 500..599)
        }
        owner?.conversationDidDisconnect(failure, shouldRetryConnection)
        onChange()
        scheduleReconnect()
    }

    /** A missing, negative or noninteger approval verdict warns without removing the card (#1103). */
    suspend fun answer(answer: HermesRequestAnswer, action: HermesAnswerAction, stillCurrent: () -> Unit): BotRequestResolution.Outcome? {
        suspend fun send(call: HermesCall) = write(call, action.generation, action.runtime, stillCurrent)
        suspend fun proxy(result: HermesCall.Answer): BotJSON {
            val reply = send(HermesCall.RequestAnswer(action.requestID, result))
            if (reply["status"].text !in setOf("ok", "expired")) throw BotFailure.Unsupported
            return reply
        }
        return when (answer) {
            is HermesRequestAnswer.Approval -> {
                val reply = send(HermesCall.ApprovalRespond(action.runtime, action.requestID, answer.choice))
                val resolved = reply["resolved"].integer
                when {
                    resolved == null || resolved < 0 -> BotRequestResolution.Outcome.UNCERTAIN
                    resolved == 0 -> BotRequestResolution.Outcome.ALREADY_RESOLVED
                    else -> BotRequestResolution.Outcome.ANSWERED
                }
            }
            is HermesRequestAnswer.Value -> if (proxy(HermesCall.Answer.Value(answer.value))["status"].text == "expired") {
                BotRequestResolution.Outcome.ALREADY_RESOLVED
            } else BotRequestResolution.Outcome.ANSWERED
            is HermesRequestAnswer.Questions -> {
                for ((index, item) in answer.answers.withIndex()) {
                    val reply = item.questionID?.let { send(HermesCall.ClarifyLock(action.requestID, it, item.text)) }
                        ?: proxy(HermesCall.Answer.Text(item.text))
                    if (reply["status"].text == "expired") return BotRequestResolution.Outcome.ALREADY_RESOLVED
                    if (item.questionID != null) {
                        val remaining = reply["remaining"].list
                        if (reply["status"].text != "ok" || remaining == null) throw BotFailure.Unsupported
                        if (remaining.isEmpty()) return BotRequestResolution.Outcome.ANSWERED
                        if (index == answer.answers.lastIndex) return null
                    }
                }
                BotRequestResolution.Outcome.ANSWERED
            }
        }
    }

    private class ResumeRefusal(val code: Int) : Exception()

    private suspend fun reset() {
        generation += 1
        connectionState = ConnectionState.DISCONNECTED
        wire.close()
        heldFrames.clear(); framesPastHold = 0
        owner?.conversationDidReset()
        onChange()
    }

    private suspend fun attach() {
        reset()
        val attempt = generation
        connectionState = ConnectionState.RECOVERING
        onChange()
        try {
            coroutineScope {
                val note = async { wire.keepPlatformNote(target.profile) }
                wire.connect()
                check(attempt)
                note.await()
            }
            identify(attempt)
            val first = resume(attempt)
            val foundKey = attachedKey(first) ?: throw BotFailure.WrongIdentity
            val foundRuntime = first["session_id"].text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.WrongIdentity
            val foundEpoch = wire.replayEpoch ?: throw BotFailure.WrongIdentity
            replayWasReset = epoch != foundEpoch || runtime != foundRuntime
            val newRuntime = runtime != foundRuntime
            if (replayWasReset) sequence = 0
            runtime = foundRuntime; epoch = foundEpoch; storedKey = foundKey
            owner?.conversationWillReplay(newRuntime)
            val replay = request(HermesCall.SessionEventsSince(foundRuntime, sequence), attempt)
            owner?.conversationDidReplay(replay, reconcile(replay))
            val snapshot = resume(attempt)
            owner?.conversationDidReadSnapshot(snapshot, foundRuntime, attempt)
            check(attempt)
            if (connectionState != ConnectionState.RECOVERING) throw BotFailure.Transport
            connectionState = ConnectionState.CONNECTED
            releaseHeldFrames()
            check(attempt)
            resumeRefusedSince = null
            shouldRetryConnection = false
            owner?.conversationDidConnect(foundRuntime, attempt)
            onChange()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation != attempt || !isActive) return
            owner?.conversationDidFailToAttach(error)
            disconnectNow(error)
        }
    }

    private suspend fun identify(attempt: Int) {
        when (val target = target) {
            is ConversationTarget.CanonicalChat -> {
                val lookup = request(HermesCall.SessionList(target.profile), attempt)
                val rows = lookup["sessions"].list ?: throw BotFailure.Unsupported
                if (rows.size != 1) throw BotFailure.MissingChat
                val foundRoot = rows[0]["id"].text?.takeIf(String::isNotEmpty) ?: throw BotFailure.Unsupported
                val foundTip = rows[0]["resolved_id"].text?.takeIf(String::isNotEmpty) ?: throw BotFailure.Unsupported
                if (root != null && root != foundRoot) {
                    if (root == linkedRoot) linkedRootIsStale = true
                    throw BotFailure.WrongIdentity
                }
                root = foundRoot; storedKey = foundTip
                owner?.conversationDidIdentify(foundRoot)
            }
            is ConversationTarget.Session -> {
                root = target.key; storedKey = target.key
                owner?.conversationDidIdentify(target.key)
            }
            is ConversationTarget.New -> {
                val reply = request(HermesCall.SessionNew(target.profile, target.cwd, target.model), attempt)
                val key = reply["stored_session_id"].text?.takeIf(String::isNotEmpty) ?: throw BotFailure.Unsupported
                if (reply["session_id"].text.isNullOrEmpty()) throw BotFailure.Unsupported
                this.target = ConversationTarget.Session(target.profile, key)
                root = key; storedKey = key
                owner?.conversationDidIdentify(key)
            }
        }
    }

    private fun attachedKey(reply: BotJSON): String? {
        if (target is ConversationTarget.CanonicalChat) return storedKey?.takeIf { reply["session_key"].text == it }
        return (reply["session_key"].text ?: reply["stored_session_id"].text)?.takeIf(String::isNotEmpty)
    }

    private fun reconcile(reply: BotJSON): List<BotJSON> {
        val latest = reply["latest_seq"].integer?.takeIf { it >= 0 } ?: throw BotFailure.Unsupported
        val receivedEpoch = reply["epoch"].text?.takeIf(String::isNotEmpty) ?: throw BotFailure.Unsupported
        val truncated = reply["truncated"].flag ?: throw BotFailure.Unsupported
        val events = reply["events"].list ?: throw BotFailure.Unsupported
        if (epoch != receivedEpoch || truncated || latest < sequence) replayWasReset = true
        var cursor = sequence
        val missed = ArrayList<BotJSON>()
        for (event in events) {
            if (event["session_id"].text != runtime) throw BotFailure.WrongIdentity
            val next = event["seq"].integer?.takeIf { it > 0 && it <= latest } ?: throw BotFailure.Unsupported
            if (next <= cursor) continue
            if (next != cursor + 1) replayWasReset = true
            cursor = next; missed += event
        }
        if (cursor < latest) replayWasReset = true
        epoch = receivedEpoch; sequence = latest
        return missed
    }

    private fun receive(event: BotJSON) {
        if (connectionState == ConnectionState.DISCONNECTED || runtime == null) return
        if (event["type"].text == "session.reclaimed") {
            val payload = event["payload"]
            if (payload["session_id"].text == runtime || storedKey != null && payload["stored_session_id"].text == storedKey) {
                replayWasReset = true
                // Disable writes immediately, before the owner's asynchronous reattach starts.
                connectionState = ConnectionState.RECOVERING
                owner?.conversationDidLoseFrames()
                onChange()
            }
            return
        }
        val requestSession = requestSession(event)
        if (requestSession != null) {
            if (requestSession == runtime) owner?.conversationDidReceiveRequest(event)
            return
        }
        if (event["session_id"].text != runtime) return
        if (connectionState != ConnectionState.CONNECTED) {
            if (heldFrames.size < HELD_FRAME_LIMIT) heldFrames += event else framesPastHold += 1
            owner?.conversationDidHold(event)
            return
        }
        admit(event)
    }

    private fun admit(event: BotJSON) {
        val next = event["seq"].integer
        if (next == null || next <= 0) {
            replayWasReset = true
            owner?.conversationDidLoseFrames()
            return
        }
        if (next == sequence) return
        val gap = next != sequence + 1
        if (gap) replayWasReset = true
        sequence = next
        owner?.conversationDidReceive(event, gap)
    }

    private fun releaseHeldFrames() {
        val frames = heldFrames.toList()
        val lost = framesPastHold
        heldFrames.clear(); framesPastHold = 0
        if (lost != 0) {
            replayWasReset = true
            owner?.conversationDidLoseFrames()
            return
        }
        for (frame in frames) {
            if (frame["session_id"].text != runtime) continue
            val seq = frame["seq"].integer
            if (seq != null && seq > 0 && seq <= sequence) continue
            admit(frame)
        }
    }

    private fun scheduleReconnect() {
        if (!isActive || !shouldRetryConnection || reconnectTask != null) return
        isReconnecting = true
        reconnectTask = scope.launch {
            try {
                var attempt = 0
                while (isActive && shouldRetryConnection) {
                    reconnectDelay(HermesReconnectBackoff.delayMillis(attempt++))
                    if (!this@HermesConversation.isActive || !shouldRetryConnection) break
                    attach()
                    if (connectionState == ConnectionState.CONNECTED) break
                }
            } finally {
                reconnectTask = null
                isReconnecting = false
                onChange()
            }
        }
    }

    companion object {
        const val HELD_FRAME_LIMIT = 512
        private fun requestSession(event: BotJSON): String? {
            if (event["id"].text.isNullOrEmpty() || event["method"].text.isNullOrEmpty()) return null
            return event["params"]["session_id"].text?.takeIf(String::isNotEmpty)
        }
    }
}
