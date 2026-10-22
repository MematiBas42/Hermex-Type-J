package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.BotConnectionOperation
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotPendingRequest
import io.github.jcartu.hermex.model.hermes.BotQuestionAnswer
import io.github.jcartu.hermex.model.hermes.BotRequestResolution
import io.github.jcartu.hermex.model.hermes.BotRequestWithdrawal
import io.github.jcartu.hermex.model.hermes.BotServerRequest
import io.github.jcartu.hermex.model.hermes.HermesRequestSubject
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotConnectionAnswer
import io.github.jcartu.hermex.net.hermes.BotSettingFailure
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Full port of `HermesChatRequests` (`Features/Chat/HermesChatRequests.swift`): one Hermes
 * session's live host requests, snapshot/replay reconciliation, guarded one-shot answers,
 * approval bypass and `manage_connections` operation. Mutable controller state is confined to
 * the conversation's Main scope; [onChange] lets the owning ViewModel publish one snapshot.
 */
class HermesChatRequests(private val engine: HermesConversation) {
    /** Reports a refused answer or setting for the chat's error line. */
    var onFailure: (String) -> Unit = {}

    /** Requests a fresh attach when a receipt says only the host can reconcile the batch. */
    var onNeedsReattach: () -> Unit = {}

    /** Runs whenever the host's open/waiting state changes. */
    var onOpenChange: () -> Unit = {}

    /** Runs after every observable controller mutation. */
    var onChange: () -> Unit = {}

    /** This runtime's open server requests, oldest first and one per envelope id. */
    var open: List<BotServerRequest> = emptyList()
        private set(value) {
            field = value
            val requestID = errorRequestID
            if (requestID != null && value.none { it.pending?.requestID == requestID }) {
                errorRequestID = null
                errorMessage = null
            }
            onOpenChange()
            onChange()
        }

    private var errorRequestID: String? = null

    /** The open `manage_connections` operation. */
    var connection: BotConnectionOperation? = null
        private set(value) {
            if (field == value) return
            field = value
            onOpenChange()
            onChange()
        }

    /** True when the host holds a connection operation this build cannot render. */
    var hasUnreadableConnection: Boolean = false
        private set(value) {
            if (field == value) return
            field = value
            onOpenChange()
            onChange()
        }

    private var unreadableConnectionID: String? = null
    private var settledConnectionID: String? = null

    /** A connection card's terminal or uncertain verdict, scoped by `op_id`. */
    var connectionResolution: BotRequestResolution? = null
        private set(value) {
            if (field == value) return
            field = value
            onChange()
        }

    /** The request whose one answer is currently at the wire boundary. */
    var answeringRequestID: String? = null
        private set(value) {
            if (field == value) return
            field = value
            onChange()
        }

    /** Why the latest answer or bypass write was refused. */
    var errorMessage: String? = null
        private set(value) {
            if (field == value) return
            field = value
            onChange()
        }

    /** The note left when the host withdrew the card that occupied the visible slot. */
    var withdrawal: BotRequestWithdrawal? = null
        private set(value) {
            if (field == value) return
            field = value
            onChange()
        }

    /** Whether this session or its host currently approves commands without asking. */
    var approvalBypass: Boolean = false
        private set(value) {
            if (field == value) return
            field = value
            onChange()
        }

    private var hostApprovesAll = false
    private var bypassOutlivedTurnOff = false
    private var bypassReports = 0

    /** True while this controller's session-scoped `config.set yolo` is in flight. */
    var isChangingApprovalBypass: Boolean = false
        private set(value) {
            if (field == value) return
            field = value
            onChange()
        }

    /** Set by the owner while this phone's Stop is in flight, suppressing its own withdrawal note. */
    var isStoppingHere: Boolean = false

    private var revision = 0
    private var replayRevision = 0
    private var snapshotRevision = 0
    private var envelopeShownWhenLeft: BotRequestWithdrawal.Envelope? = null

    /** Question first, then approval, then another visible request, then a connection operation. */
    val onScreen: BotPendingRequest?
        get() {
            var first: BotPendingRequest? = null
            var approval: BotPendingRequest.Approval? = null
            for (envelope in open) {
                val request = envelope.pending ?: continue
                if (!isShownHere(request)) continue
                if (request is BotPendingRequest.Question) return request
                if (approval == null && request is BotPendingRequest.Approval) approval = request
                if (first == null) first = request
            }
            return approval ?: first ?: connection?.let(BotPendingRequest::Connection)
        }

    /** Unknown requests still park the session even though the phone draws no card. */
    val isWaiting: Boolean get() = open.isNotEmpty() || connection != null || hasUnreadableConnection

    /** Whether Stop would withdraw an answer this chat's user can still provide. */
    val stopWithdrawsAnswers: Boolean
        get() = connection != null || hasUnreadableConnection || open.any { it.pending !is BotPendingRequest.DesktopTask }

    /** Only a session-owned bypass can be turned off from its pill. */
    val mayTurnOffApprovalBypass: Boolean
        get() = approvalBypass && !hostApprovesAll && !bypassOutlivedTurnOff

    /** Number of open approvals, including approvals behind the visible request. */
    val approvalCount: Int get() = open.count { it.pending is BotPendingRequest.Approval }

    /** True while the card on screen may accept a fresh deliberate answer. */
    val mayAnswer: Boolean
        get() {
            val request = onScreen ?: return false
            if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED ||
                answeringRequestID != null || isChangingApprovalBypass || !isAnsweredHere(request)
            ) {
                return false
            }
            return resolution(request)?.blocksFurtherAnswers != true
        }

    /** The verdict belonging to the card currently on screen. */
    val onScreenResolution: BotRequestResolution? get() = onScreen?.let(::resolution)

    /** Captures the attach/runtime/request fence for one user tap. */
    fun prepareAnswer(): HermesAnswerAction? {
        val requestID = onScreen?.requestID ?: return null
        val runtime = engine.runtime ?: return null
        if (!mayAnswer) return null
        return HermesAnswerAction(engine.generation, runtime, requestID)
    }

    /** Answers the visible approval with one of the exact choices its host offered. */
    suspend fun respond(action: HermesAnswerAction, choice: BotApprovalChoice): Boolean {
        val approval = (onScreen as? BotPendingRequest.Approval)?.request ?: return false
        if (approval.requestID != action.requestID || choice !in approval.choices || action != prepareAnswer()) return false
        return deliver(HermesRequestAnswer.Approval(choice), action) == BotRequestResolution.Outcome.ANSWERED
    }

    /** Answers every still-unlocked question in the visible single request or batch. */
    suspend fun answerQuestion(action: HermesAnswerAction, answers: List<BotQuestionAnswer>): Boolean {
        val question = (onScreen as? BotPendingRequest.Question)?.request ?: return false
        if (question.requestID != action.requestID || action != prepareAnswer()) return false
        if (question.isBatch) {
            val outstanding = question.questions.filterNot { it.isAnswered }.mapNotNull { it.wireID }
            if (answers.size != outstanding.size || answers.mapNotNull { it.questionID }.toSet() != outstanding.toSet()) {
                return false
            }
        } else if (answers.size != 1 || answers.single().questionID != null) {
            return false
        }
        return deliver(HermesRequestAnswer.Questions(answers), action) == BotRequestResolution.Outcome.ANSWERED
    }

    /**
     * Skips a batch with one empty `clarify.lock` per outstanding qid. A single question uses
     * one unkeyed empty `request.answer`; a bare answer is never sent for a batch.
     */
    suspend fun skipQuestion(action: HermesAnswerAction): Boolean {
        val question = (onScreen as? BotPendingRequest.Question)?.request ?: return false
        if (question.requestID != action.requestID || action != prepareAnswer()) return false
        val answers = if (question.isBatch) {
            question.questions.filterNot { it.isAnswered }.mapNotNull { item ->
                item.wireID?.let { BotQuestionAnswer(it, "") }
            }
        } else {
            listOf(BotQuestionAnswer(null, ""))
        }
        if (answers.isEmpty()) return false
        return deliver(HermesRequestAnswer.Questions(answers), action) == BotRequestResolution.Outcome.ANSWERED
    }

    /** Sends a sudo or secret value straight to the guarded dispatch; empty deliberately skips. */
    suspend fun answerCredential(action: HermesAnswerAction, value: String): Boolean {
        val credential = (onScreen as? BotPendingRequest.Credential)?.request ?: return false
        if (credential.requestID != action.requestID || action != prepareAnswer()) return false
        return deliver(HermesRequestAnswer.Value(value), action) == BotRequestResolution.Outcome.ANSWERED
    }

    /** Turns session bypass on, then releases this approval once; neither write is retried. */
    suspend fun skipApprovals(action: HermesAnswerAction): Boolean {
        val approval = (onScreen as? BotPendingRequest.Approval)?.request ?: return false
        if (approval.requestID != action.requestID || action != prepareAnswer()) return false
        if (!setApprovalBypass(true, action) || BotApprovalChoice.ONCE !in approval.choices) return false
        val outcome = deliver(HermesRequestAnswer.Approval(BotApprovalChoice.ONCE), action)
        return outcome == BotRequestResolution.Outcome.ANSWERED ||
            outcome == BotRequestResolution.Outcome.ALREADY_RESOLVED
    }

    /** Turns off this session's bypass when the host is not the owner of it. */
    suspend fun turnOffApprovalBypass() {
        if (mayTurnOffApprovalBypass) setApprovalBypass(false, null)
    }

    /** Implements `/yolo`, returning the confirmed post-change bypass or null on refusal. */
    suspend fun toggleApprovalBypass(): Boolean? {
        val enabled = !approvalBypass
        if (!enabled && !mayTurnOffApprovalBypass) return null
        if (!setApprovalBypass(enabled, null)) return null
        return approvalBypass
    }

    /** Answers one currently offered row move, or Continue, and waits for a settled frame. */
    suspend fun respondToConnection(action: HermesAnswerAction, answer: BotConnectionAnswer): Boolean {
        val operation = (onScreen as? BotPendingRequest.Connection)?.operation ?: return false
        if (operation.opID != action.requestID || !operation.offers(answer) || action != prepareAnswer()) return false
        answeringRequestID = action.requestID
        errorMessage = null
        try {
            val reply = engine.write(
                HermesCall.ConnectionRespond(action.runtime, action.requestID, answer),
                action.generation,
                action.runtime,
            ) {
                val current = (onScreen as? BotPendingRequest.Connection)?.operation
                if (current?.opID != action.requestID || !current.offers(answer)) throw BotFailure.Stale
            }
            if (action.generation != engine.generation || !currentCoroutineContext().isActive) return false
            if (reply["status"].text != "ok" || reply["settled"].flag == null) throw BotFailure.Unsupported
            answeringRequestID = null
            if (reply["settled"].flag == true) closeConnection(action.requestID)
            return true
        } catch (error: CancellationException) {
            if (answeringRequestID == action.requestID) answeringRequestID = null
            throw error
        } catch (error: Throwable) {
            if (action.generation != engine.generation || !currentCoroutineContext().isActive) return false
            answeringRequestID = null
            if (error === BotFailure.Stale) return false
            if (error is BotFailure.Rejected) {
                if (error.code == 4004) {
                    connectionResolution = BotRequestResolution(action.requestID, BotRequestResolution.Outcome.ALREADY_RESOLVED)
                    onNeedsReattach()
                } else {
                    fail(
                        if (isIdentityRejection(error.code)) error.message ?: ANSWER_REFUSED else ANSWER_REFUSED,
                    )
                }
                return false
            }
            connectionResolution = BotRequestResolution(action.requestID, BotRequestResolution.Outcome.UNCERTAIN)
            engine.disconnect(error)
            return false
        }
    }

    /** Releases an operation before Steer or Queue; refusal permits the message, ambiguity does not. */
    suspend fun continueConnection(runtime: String, attempt: Int) {
        val opID = connection?.opID ?: return
        if (answeringRequestID != null) throw BotFailure.Stale
        answeringRequestID = opID
        try {
            val reply = engine.write(
                HermesCall.ConnectionRespond(runtime, opID, BotConnectionAnswer.ContinueWithout),
                attempt,
                runtime,
            ) {
                if (connection?.opID != opID) throw BotFailure.Stale
            }
            if (reply["settled"].flag == true) closeConnection(opID)
        } catch (_: BotFailure.Rejected) {
            // A definitive refusal means Continue did not take effect, but it no longer blocks the message.
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error !== BotFailure.Stale) {
                connectionResolution = BotRequestResolution(opID, BotRequestResolution.Outcome.UNCERTAIN)
                engine.disconnect(error)
            }
            throw error
        } finally {
            if (answeringRequestID == opID) answeringRequestID = null
        }
    }

    private suspend fun deliver(
        answer: HermesRequestAnswer,
        action: HermesAnswerAction,
    ): BotRequestResolution.Outcome? {
        answeringRequestID = action.requestID
        errorMessage = null
        try {
            val outcome = engine.answer(answer, action) {
                if (onScreen?.requestID != action.requestID) throw BotFailure.Stale
            }
            if (action.generation != engine.generation || !currentCoroutineContext().isActive) return null
            answeringRequestID = null
            if (outcome == null) {
                onNeedsReattach()
                return null
            }
            revision += 1
            if (outcome == BotRequestResolution.Outcome.UNCERTAIN) {
                if (open.any { it.pending?.requestID == action.requestID }) {
                    fail(
                        BotRequestResolution(action.requestID, outcome).message(HermesRequestSubject.HERMES),
                        action.requestID,
                    )
                }
                return outcome
            }
            open = open.filterNot { it.pending?.requestID == action.requestID }
            return outcome
        } catch (error: CancellationException) {
            if (answeringRequestID == action.requestID) answeringRequestID = null
            throw error
        } catch (error: Throwable) {
            if (action.generation != engine.generation || !currentCoroutineContext().isActive) return null
            answeringRequestID = null
            if (error === BotFailure.Stale) return null
            if (error is BotFailure.Rejected) {
                fail(
                    if (isIdentityRejection(error.code)) error.message ?: ANSWER_REFUSED else ANSWER_REFUSED,
                    action.requestID,
                )
                return null
            }
            engine.disconnect(error)
            return null
        }
    }

    private suspend fun setApprovalBypass(enabled: Boolean, action: HermesAnswerAction?): Boolean {
        val runtime = engine.runtime ?: return false
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED ||
            isChangingApprovalBypass || answeringRequestID != null
        ) {
            return false
        }
        val attempt = engine.generation
        val reportsBefore = bypassReports
        isChangingApprovalBypass = true
        errorMessage = null
        var dispatched = false
        try {
            val reply = engine.write(
                HermesCall.ConfigSet(runtime, engine.target.profile, HermesCall.SessionSetting.Yolo(enabled)),
                attempt,
                runtime,
            ) {
                if (action != null && onScreen?.requestID != action.requestID) throw BotFailure.Stale
                dispatched = true
            }
            if (attempt != engine.generation || !currentCoroutineContext().isActive) return false
            isChangingApprovalBypass = false
            val value = reply["value"].text
            if (reply["key"].text != "yolo" || (value != "0" && value != "1") || (value == "1") != enabled) {
                throw BotSettingFailure.UnknownOutcome
            }
            if (bypassReports == reportsBefore) {
                approvalBypass = enabled
            } else if (!enabled && approvalBypass) {
                if (!bypassOutlivedTurnOff) {
                    bypassOutlivedTurnOff = true
                    onChange()
                }
            }
            return true
        } catch (error: CancellationException) {
            if (attempt == engine.generation) isChangingApprovalBypass = false
            throw error
        } catch (error: Throwable) {
            if (attempt != engine.generation || !currentCoroutineContext().isActive) return false
            isChangingApprovalBypass = false
            if (error === BotFailure.Stale) return false
            when {
                error is BotSettingFailure.Rejected -> fail(error.message ?: BotSettingFailure.UnknownOutcome.message.orEmpty())
                !dispatched -> fail(error.message ?: BotSettingFailure.UnknownOutcome.message.orEmpty())
                else -> fail(BotSettingFailure.UnknownOutcome.message.orEmpty())
            }
            return false
        }
    }

    private fun fail(message: String, about: String? = null) {
        errorRequestID = about
        errorMessage = message
        onFailure(message)
    }

    /** Adds or replaces a live server-request envelope belonging to this runtime. */
    fun receive(envelope: BotJSON) {
        val request = BotServerRequest.from(envelope) ?: return
        if (request.sessionID != engine.runtime) return
        revision += 1
        val index = open.indexOfFirst { it.id == request.id }
        open = if (index >= 0) open.toMutableList().also { it[index] = request } else open + request
        withdrawal = null
    }

    /** Withdraws only the envelope whose id and method both match `request.cancel`. */
    fun cancel(payload: BotJSON) {
        val id = payload["id"].text?.takeIf(String::isNotEmpty) ?: return
        val method = payload["method"].text?.takeIf(String::isNotEmpty) ?: return
        revision += 1
        val envelope = BotRequestWithdrawal.Envelope(id, method)
        val wasShown = envelope == envelopeOnScreen
        open = open.filterNot { it.id == id && it.method == method }
        if (wasShown) withdrawal = if (onScreen == null) note(envelope, payload["reason"].text) else null
    }

    /** Applies `connection.request` (`opens`) or a `connection.update` for this session. */
    fun receiveConnection(payload: BotJSON, opens: Boolean) {
        val frame = BotConnectionOperation.from(payload)
        if (frame == null) {
            if (opens) holdUnreadableConnection(payload)
            else if (payload["settled"].flag == true) hasUnreadableConnection = false
            return
        }
        applyConnection(frame, opens)
    }

    /** Fences an `open_requests` read behind a held request frame. */
    fun holdRequestFrame() {
        revision += 1
    }

    /** A stop withdrew all requests and settled any visible connection operation. */
    fun withdrawAll() {
        revision += 1
        open = emptyList()
        connection?.opID?.let(::closeConnection)
        hasUnreadableConnection = false
    }

    /** Applies `session.info`'s `yolo` and `approval_mode`, live or from a snapshot. */
    fun applyBypass(info: BotJSON) {
        info["yolo"].flag?.let { yolo ->
            bypassReports += 1
            approvalBypass = yolo
            if (!yolo && bypassOutlivedTurnOff) {
                bypassOutlivedTurnOff = false
                onChange()
            }
        }
        info["approval_mode"].text?.let { mode ->
            val next = mode == "off"
            if (hostApprovesAll != next) {
                hostApprovesAll = next
                onChange()
            }
        }
    }

    /** Clears a served withdrawal note once the host accepts the next prompt. */
    fun promptAccepted() {
        withdrawal = null
    }

    /** Remembers the visible envelope while leaving a live connection for replay reconciliation. */
    fun willLeave() {
        if (engine.connectionState == HermesConversation.ConnectionState.CONNECTED) {
            envelopeShownWhenLeft = envelopeOnScreen
        }
    }

    /** Drops a connection operation with its socket until `pending_connection` restores it. */
    fun disconnect() {
        connection = null
        hasUnreadableConnection = false
    }

    /** Drops attach-owned requests while preserving connection verdict and settled fences. */
    fun reset() {
        open = emptyList()
        answeringRequestID = null
        isChangingApprovalBypass = false
        errorMessage = null
        errorRequestID = null
        withdrawal = null
        connection = null
        hasUnreadableConnection = false
    }

    /** Captures the request revision before `session.events.since`. */
    fun willReplay() {
        replayRevision = revision
    }

    /** Restores replay `open_requests` only when no newer live request frame won the race. */
    fun didReplay(reply: BotJSON, frames: List<BotJSON>) {
        if (replayRevision == revision) restore(reply["open_requests"])
        envelopeShownWhenLeft?.let { left ->
            envelopeShownWhenLeft = null
            val cancel = frames.lastOrNull { it["type"].text == "request.cancel" }?.get("payload")
            if (!engine.replayWasReset && cancel != null && onScreen == null &&
                BotRequestWithdrawal.Envelope(cancel["id"].text.orEmpty(), cancel["method"].text.orEmpty()) == left
            ) {
                withdrawal = note(left, cancel["reason"].text)
            }
        }
    }

    /** Captures the request revision before the full `session.resume` snapshot. */
    fun willReadSnapshot() {
        snapshotRevision = revision
    }

    /** Applies snapshot requests, connection operation and bypass under their individual fences. */
    fun didReadSnapshot(snapshot: BotJSON) {
        if (snapshotRevision == revision) restore(snapshot["open_requests"])
        restoreConnection(snapshot["pending_connection"])
        applyBypass(snapshot["info"])
        if (withdrawal != null && onScreen != null) withdrawal = null
    }

    private val envelopeOnScreen: BotRequestWithdrawal.Envelope?
        get() {
            val shown = onScreen?.requestID ?: return null
            return open.firstOrNull { it.pending?.requestID == shown }?.let {
                BotRequestWithdrawal.Envelope(it.id, it.method)
            }
        }

    private fun note(envelope: BotRequestWithdrawal.Envelope, reason: String?): BotRequestWithdrawal? {
        val note = BotRequestWithdrawal.from(envelope.method, reason) ?: return null
        return if (note.reason == BotRequestWithdrawal.Reason.STOPPED && isStoppingHere) null else note
    }

    private fun restoreConnection(pending: BotJSON) {
        val frame = BotConnectionOperation.from(pending)
        if (frame == null) {
            connection = null
            if (!pending.isNull) holdUnreadableConnection(pending) else hasUnreadableConnection = false
            return
        }
        hasUnreadableConnection = false
        applyConnection(frame, opens = true)
    }

    private fun holdUnreadableConnection(payload: BotJSON) {
        connection = null
        unreadableConnectionID = payload["op_id"].text
        hasUnreadableConnection = true
    }

    private fun applyConnection(frame: BotConnectionOperation, opens: Boolean) {
        if (frame.opID == settledConnectionID) return
        val held = connection
        val next = when {
            held?.opID == frame.opID -> held.applying(frame)
            opens || frame.isSettled -> frame
            else -> return
        }
        if (opens) hasUnreadableConnection = false
        if (next.isSettled) {
            if (hasUnreadableConnection && unreadableConnectionID == next.opID) hasUnreadableConnection = false
            closeConnection(next.opID)
            return
        }
        if (connectionResolution?.requestID != next.opID) connectionResolution = null
        if (next.opID != connection?.opID) withdrawal = null
        connection = next
    }

    private fun closeConnection(opID: String) {
        settledConnectionID = opID
        if (connectionResolution?.requestID == opID) connectionResolution = null
        if (connection?.opID == opID) connection = null
    }

    private fun resolution(request: BotPendingRequest): BotRequestResolution? {
        val operation = (request as? BotPendingRequest.Connection)?.operation ?: return null
        return connectionResolution?.takeIf { it.requestID == operation.opID }
    }

    private fun restore(rows: BotJSON) {
        val seen = HashSet<String>()
        open = rows.list.orEmpty().mapNotNull(BotServerRequest::from)
            .filter { it.sessionID == engine.runtime && seen.add(it.id) }
    }

    companion object {
        private const val ANSWER_REFUSED = "The server did not accept that response. The request is still waiting."

        private fun isIdentityRejection(code: Int): Boolean = code == 401 || code == 403 || code == -32601

        /** Whether a regular Hermes chat renders this request. Desktop tasks are informational. */
        fun isShownHere(request: BotPendingRequest): Boolean =
            request is BotPendingRequest.DesktopTask || isAnsweredHere(request)

        /** Whether a regular Hermes chat may answer this request. */
        fun isAnsweredHere(request: BotPendingRequest): Boolean = request !is BotPendingRequest.DesktopTask
    }
}
