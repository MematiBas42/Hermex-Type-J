package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.*
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Port of `Features/Chat/HermesChatTurnCoordinator.swift`. Reduces only this conversation's
 * ordered events into the existing ChatTranscript state. Every prompt/write goes once; REST
 * pages supply durable history, replay supplies missed deltas, and an interrupted replay is
 * rebuilt from the host snapshot before held frames are released.
 */
class HermesChatTurnCoordinator(
    val engine: HermesConversation,
    private val state: MutableStateFlow<ChatUiState>,
    val policy: HermesChatPolicy = HermesChatPolicy.SESSION,
    private val now: () -> Double = { System.currentTimeMillis() / 1_000.0 },
) : HermesConversationOwner {
    class NotSent(val underlying: Throwable, val duringUpload: Boolean = false) : Exception(underlying)
    class BranchRowGone : Exception("This message is no longer in the session's history.")
    class BranchContinuesEarlierSession : Exception("This session continues an earlier session. Use /branch to fork the whole conversation.")
    data class OutgoingAttachment(val name: String, val mime: String, val isImage: Boolean, val data: suspend () -> ByteArray)
    data class SavedTurn(val promptRowID: Int?, val replyRowID: Int)
    sealed class Compression {
        data class Compacted(val headline: String?, val tokenLine: String?) : Compression()
        data class Unchanged(val reason: String?) : Compression()
    }

    val requests = HermesChatRequests(engine)
    val sideTasks = HermesChatSideTasks(engine)
    val settings = HermesChatSettings(engine)
    val slashCommands = HermesSlashCommands(engine, policy)
    val activity = HermesChatActivity(engine.wire, engine.scope)
    var onChange: () -> Unit = {}
    var onPromptAccepted: () -> Unit = {}
    var onHistory: (List<ChatMessage>, HermesNewestCoverage?) -> Unit = { _, _ -> }
    var onRunStarted: () -> Unit = {}
    var onRunFinished: (ChatRunEnding) -> Unit = {}
    var mentionAnnotation: ((String) -> String)? = null
    var cwd: String? = null
        private set
    var terminalBackend: String? = null
        private set
    var reportedModel: HermesCall.Model? = null
        private set
    var queuedPrompt: String? = null
        private set
    var needsSignIn = false
        private set
    var history = HermesTranscriptHistory()
        private set
    var compaction: HermesCompaction? = null
        private set
    var isUploadingAttachments = false
        private set
    var historyFailure: Throwable? = null
        private set
    private var hostRunning = false
    private var hostTurnStartedAt: Double? = null
    private var turnStartedAt: Double? = null
    private var pendingEnding: ChatRunEnding? = null
    private var awaitingStart = false
    private var stopRequested = false
    private var submittingSend = false
    private var turnsStarted = 0
    private var historyTurns = 0
    private var submitsInFlight = 0
    private var needsRebuild = false
    private var compactionPending = false
    private var savedTurn: SavedTurn? = null
    private var completedFailed = false
    private var historyRefresh: Job? = null
    private var reattaching: Job? = null
    private var attachmentUpload: Deferred<List<String>>? = null
    private var replayedReply = ""
    private val heldFrames = ArrayList<BotJSON>()
    private val deltasInRebuild = HashSet<Int>()
    private var renderBase = 1_000_000
    private var lastError: String? = null

    val activeStreamID: String? get() = state.value.activeStreamId
    val isIdle: Boolean get() = activeStreamID == null && submitsInFlight == 0
    val stopNeedsConfirmation: Boolean get() = queuedPrompt != null || requests.stopWithdrawsAnswers
    val workspace: HermesWorkspaceContext?
        get() {
            val folder = cwd ?: return null
            val key = engine.storedKey ?: return null
            return HermesWorkspaceContext(engine.server, engine.target.profile, key, folder, terminalBackend)
        }

    init {
        engine.owner = this
        engine.onChange = { syncConnection(); onChange() }
        requests.onChange = { onChange() }
        requests.onOpenChange = { onChange() }
        requests.onFailure = { message -> state.update { it.copy(sendErrorMessage = message) }; onChange() }
        requests.onNeedsReattach = ::reattach
        sideTasks.onChange = { onChange() }
        sideTasks.onNeedsReattach = ::reattach
        activity.onChange = { onChange() }
        activity.delegatedWork.onChange = { onChange() }
        settings.onChange = { syncSettings(); onChange() }
        slashCommands.onChange = { syncSlash(); onChange() }
        slashCommands.onNeedsReattach = ::reattach
    }

    suspend fun activate() { if (!needsSignIn) engine.activate() }
    suspend fun suspendConnection() { requests.willLeave(); engine.suspendConnection() }

    suspend fun submit(text: String, mode: BotPromptMode, attachments: List<OutgoingAttachment> = emptyList(), displayText: String? = null, beforePrompt: suspend () -> Unit = {}): BotPromptOutcome {
        submitsInFlight += 1
        try {
            activate()
            val runtime = engine.runtime
            if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED || runtime == null) throw NotSent(BotFailure.Transport)
            if (attachments.isNotEmpty() && !mode.startsTurn) throw NotSent(BotFailure.Unsupported)
            val attempt = engine.generation
            val references = try { upload(attachments, runtime, attempt) } catch (error: Throwable) { throw NotSent(error, true) }
            if (mode == BotPromptMode.STEER || mode == BotPromptMode.QUEUE) {
                try { requests.continueConnection(runtime, attempt) } catch (error: Throwable) { throw NotSent(error) }
            }
            try { beforePrompt() } catch (error: Throwable) { throw NotSent(error) }
            val prompt = (listOf(text) + references).filter { it.isNotEmpty() }.joinToString("\n\n")
            val annotated = prompt + (mentionAnnotation?.invoke(text) ?: "")
            val startsBefore = turnsStarted
            submittingSend = mode == BotPromptMode.SEND
            requests.isStoppingHere = mode == BotPromptMode.REDIRECT
            var dispatched = false
            val reply = try {
                engine.write(mode.call(runtime, annotated), attempt, runtime) { dispatched = true }
            } catch (error: Throwable) {
                if (HermesChatSideTasks.isReaped(error)) reattach()
                throw if (dispatched) error else NotSent(error)
            } finally {
                requests.isStoppingHere = false
                submittingSend = false
            }
            val outcome = mode.outcome(reply)
            when (outcome) {
                BotPromptOutcome.STARTED -> {
                    val shown = HermesTranscriptProjection.displayed(ChatMessage(role = "user", content = displayText ?: prompt, timestamp = now(), messageId = "local-user-${UUID.randomUUID()}"))
                    insertPrompt(shown)
                    if (turnsStarted == startsBefore && activeStreamID == null) {
                        beginTurn(null, null, showsPrompt = true)
                        awaitingStart = true
                    }
                }
                BotPromptOutcome.FOLLOW_UP_QUEUED, BotPromptOutcome.REDIRECT_QUEUED -> {
                    queuedPrompt = listOfNotNull(queuedPrompt, displayText ?: prompt).joinToString("\n\n")
                    state.update { it.copy(queuedMessagesReceipt = "Message queued") }
                }
                BotPromptOutcome.GUIDANCE_QUEUED, BotPromptOutcome.REDIRECTED -> {
                    val echo = ChatMessage(role = "user", content = text, timestamp = now(), messageId = "local-steer-${UUID.randomUUID()}", displayKind = ChatMessage.STEER_DISPLAY_KIND)
                    updateMessages(state.value.messages + echo)
                }
                BotPromptOutcome.REJECTED, BotPromptOutcome.UNKNOWN -> return outcome
                BotPromptOutcome.VOICE_STOPPED -> Unit
            }
            requests.promptAccepted()
            onPromptAccepted()
            onChange()
            return outcome
        } finally { submitsInFlight -= 1 }
    }

    private suspend fun upload(attachments: List<OutgoingAttachment>, runtime: String, attempt: Int): List<String> {
        if (attachments.isEmpty()) return emptyList()
        val key = engine.storedKey ?: throw BotFailure.Stale
        val context = BotArtifactContext(engine.connection.id, engine.target.profile, key, attempt)
        isUploadingAttachments = true; onChange()
        val task = engine.scope.async {
            attachments.map { attachment ->
                engine.check(attempt)
                val bytes = attachment.data()
                engine.check(attempt)
                if (attachment.isImage) {
                    val path = engine.wire.uploadImage(bytes, attachment.name, context)
                    BotAttachmentUpload.imageReference(BotAttachmentUpload.verifiedPath(path))
                } else {
                    val call = withContext(Dispatchers.Default) { BotAttachmentUpload.fileAttach(bytes, runtime, attachment.name, attachment.mime) }
                    val reply = engine.write(call, attempt, runtime)
                    val reference = reply["ref_text"].text
                    if (reply["attached"].flag != true || reference == null || !reference.startsWith("@file:") || '\n' in reference || '\r' in reference) throw BotFailure.Unsupported
                    BotAttachmentUpload.verifiedPath(reply["path"].text)
                    reference
                }
            }.also { engine.check(attempt) }
        }
        attachmentUpload = task
        return try { task.await() } finally { task.cancel(); attachmentUpload = null; isUploadingAttachments = false; onChange() }
    }

    fun cancelAttachmentUpload() { attachmentUpload?.cancel() }

    suspend fun attachmentData(path: String, whole: Boolean = false): ByteArray {
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED) throw BotFailure.Stale
        val key = engine.storedKey ?: throw BotFailure.Stale
        val attempt = engine.generation
        val verified = BotArtifactReference.path(path, engine.connection.address)
        val data = engine.wire.artifactData(verified, BotArtifactContext(engine.connection.id, engine.target.profile, key, attempt), if (whole) null else 25 * 1024 * 1024)
        engine.check(attempt)
        return data
    }

    suspend fun interrupt(expectedStreamID: String? = activeStreamID): Boolean {
        if (activeStreamID == null || stopRequested) return false
        val runtime = engine.runtime ?: throw BotFailure.Transport
        if (activeStreamID != expectedStreamID) throw BotFailure.Stale
        stopRequested = true; requests.isStoppingHere = true
        try { engine.write(HermesCall.SessionInterrupt(runtime), engine.generation, runtime) {
            if (state.value.activeStreamId != expectedStreamID) throw BotFailure.Stale
        } }
        catch (error: Throwable) { stopRequested = false; throw error }
        finally { requests.isStoppingHere = false }
        queuedPrompt = null
        requests.withdrawAll()
        state.update { it.copy(queuedMessagesReceipt = null) }
        return true
    }

    suspend fun rewind(before: Int, text: String, discardsLaterPrompts: Boolean, showsPrompt: Boolean = false) {
        submitsInFlight += 1
        try {
            activate()
            val runtime = connectedRuntime()
            val startsBefore = turnsStarted
            val beforeRows = state.value.messages
            val first = beforeRows.indexOfFirst { it.rowID == before }.takeIf { it >= 0 }
                ?: beforeRows.indexOfLast(HermesTranscriptProjection::isTurnBoundary).takeIf {
                    showsPrompt && it >= 0 && beforeRows[it].rowID == null
                }
            val cut = first?.let { beforeRows.drop(it).mapTo(HashSet()) { row -> row.id } }.orEmpty()
            val reply = try { writeOnce(HermesCall.PromptRewind(runtime, text, before, false), runtime) }
            catch (error: BotSettingFailure.Rejected) {
                if (error.code != 4033 || !discardsLaterPrompts) throw error
                writeOnce(HermesCall.PromptRewind(runtime, text, before, true), runtime)
            }
            if (reply["status"].text != "streaming") unconfirmedHistoryWrite()
            history = history.cutting(before)
            updateMessages(state.value.messages.filterNot { it.id in cut })
            state.update { current -> current.copy(
                completedToolCallGroups = current.completedToolCallGroups.filter { it.anchorMessageID != null && it.anchorMessageID !in cut },
                completedReasoningGroups = current.completedReasoningGroups.filter { it.anchorMessageID != null && it.anchorMessageID !in cut },
            ) }
            insertPrompt(HermesTranscriptProjection.displayed(ChatMessage(role = "user", content = text, messageId = "local-rewind-${UUID.randomUUID()}", timestamp = now())))
            if (turnsStarted == startsBefore && activeStreamID == null) { beginTurn(null, null, true); awaitingStart = true }
            requests.promptAccepted()
        } finally { submitsInFlight -= 1 }
    }

    suspend fun undo() {
        activate()
        val runtime = connectedRuntime()
        val attempt = engine.generation
        val reply = writeOnce(HermesCall.SessionUndo(runtime), runtime)
        val removed = reply["removed"].integer?.takeIf { it >= 0 } ?: unconfirmedHistoryWrite()
        if (removed == 0) return
        activity.newestTurnWasUndone()
        readNewestRows(attempt)
        engine.check(attempt)
        if (isIdle) replaceHistory()
    }

    suspend fun compress(focus: String?): Compression {
        activate()
        val runtime = connectedRuntime()
        val attempt = engine.generation
        val reply = try { writeOnce(HermesCall.SessionCompress(runtime, focus, engine.target.profile), runtime) }
        catch (error: Throwable) {
            if (error !is NotSent && error !is BotSettingFailure) { history = HermesTranscriptHistory(); needsRebuild = true }
            throw error
        }
        engine.adoptStoredKey(reply["info"]["stored_session_id"].text)
        val summary = reply["summary"]
        val reason = listOf(reply["message"], summary["note"], summary["headline"]).firstNotNullOfOrNull { words(it) }
        when (reply["status"].text) {
            "compressed" -> if (reply["removed"].integer == 0) return Compression.Unchanged(reason)
            "aborted" -> return Compression.Unchanged(reason)
            "pending" -> { compactionPending = true; return Compression.Unchanged(reason) }
            null -> if (reply["lock_held"].flag == true) return Compression.Unchanged(reason) else { history = HermesTranscriptHistory(); unconfirmedHistoryWrite() }
            else -> { history = HermesTranscriptHistory(); unconfirmedHistoryWrite() }
        }
        compactionPending = false
        historyRefresh?.cancel()
        history = HermesTranscriptHistory()
        readNewestRows(attempt)
        if (isIdle) replaceHistory()
        return Compression.Compacted(words(summary["headline"]), words(summary["token_line"]))
    }

    suspend fun branch(through: Int? = null, name: String? = null): String {
        activate()
        val runtime = connectedRuntime()
        var count: Int? = null
        if (through != null) {
            val own = engine.wire.sessionRow(engine.storedKey ?: throw BotFailure.Stale, engine.target.profile) ?: throw BranchRowGone()
            if (!HermesBranchParent.standsAlone(own)) throw BranchContinuesEarlierSession()
            var stalled = false
            while (history.hasOlder) {
                val held = history.rows.size
                loadOlderHistory()
                if (history.rows.size == held && history.hasOlder) {
                    if (stalled) throw NotSent(BotFailure.Transport)
                    stalled = true
                } else stalled = false
            }
            count = HermesBranchCount.count(through, history.rows) ?: throw BranchRowGone()
        }
        return writeOnce(HermesCall.SessionBranch(runtime, name, count), runtime)["stored_session_id"].text?.takeIf(String::isNotEmpty) ?: unconfirmedHistoryWrite()
    }

    private fun connectedRuntime(): String {
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED) throw NotSent(BotFailure.Transport)
        return engine.runtime ?: throw NotSent(BotFailure.Transport)
    }

    private suspend fun writeOnce(call: HermesCall, runtime: String): BotJSON {
        var dispatched = false
        try { return engine.write(call, engine.generation, runtime) { dispatched = true } }
        catch (error: Throwable) {
            if (HermesChatSideTasks.isReaped(error)) reattach()
            if (dispatched && error !is BotFailure.Rejected && error !is BotSettingFailure.Rejected && error !is CancellationException && error != BotFailure.Stale) recoverAfterLostAnswer()
            throw if (dispatched) error else NotSent(error)
        }
    }
    private fun unconfirmedHistoryWrite(): Nothing {
        recoverAfterLostAnswer()
        throw BotSettingFailure.UnknownOutcome
    }

    private fun beginTurn(startedAt: Double?, prompt: String?, showsPrompt: Boolean = prompt != null || submittingSend) {
        archiveLive()
        turnsStarted += 1
        pendingEnding = null; awaitingStart = false; stopRequested = false; savedTurn = null; completedFailed = false
        hostRunning = true; turnStartedAt = startedAt; hostTurnStartedAt = null; lastError = null
        if (prompt != null) insertPrompt(HermesTranscriptProjection.displayed(ChatMessage(role = "user", content = prompt, timestamp = startedAt ?: now(), messageId = "queued-${UUID.randomUUID()}")))
        state.update { it.copy(
            activeStreamId = "hermes:${engine.storedKey}:${startedAt ?: UUID.randomUUID()}",
            activeRunStartedAt = startedAt?.takeIf { time -> time.isFinite() && time > 0 }?.coerceAtMost(now()) ?: now(),
            streamingAssistantMessageId = null, latestRunOutcome = null, sendErrorMessage = null,
            queuedMessagesReceipt = queuedPrompt?.let { "Message queued" },
        ) }
        activity.turnDidStart(showsPrompt)
        onRunStarted(); onChange()
    }

    private fun ensureTurn() { if (activeStreamID == null) beginTurn(hostTurnStartedAt, null) }
    private fun ensureAssistant(): String {
        state.value.streamingAssistantMessageId?.let { return it }
        val id = "hermes-live-${UUID.randomUUID()}"
        state.update { it.copy(streamingAssistantMessageId = id) }
        updateMessages(state.value.messages + ChatMessage(role = "assistant", content = "", timestamp = now(), messageId = id))
        return id
    }

    private fun append(text: String, separator: Boolean = false) {
        if (text.isEmpty()) return
        val id = ensureAssistant()
        val messages = state.value.messages.map { row ->
            if (row.messageId != id) row else row.copy(content = row.content.orEmpty() + (if (separator && !row.content.isNullOrBlank()) "\n\n" else "") + text)
        }
        updateMessages(messages)
    }

    private fun archiveLive() {
        state.update { current -> current.copy(
            completedReasoningGroups = current.completedReasoningGroups + if (current.liveReasoningText.isBlank()) emptyList() else listOf(ReasoningGroup(anchorMessageID = current.streamingAssistantMessageId, text = current.liveReasoningText)),
            completedToolCallGroups = current.completedToolCallGroups + if (current.liveToolCalls.isEmpty()) emptyList() else listOf(ToolCallGroup(anchorMessageID = current.streamingAssistantMessageId, toolCalls = current.liveToolCalls)),
            liveToolCalls = emptyList(), liveReasoningText = "",
        ) }
    }

    private fun finish(ending: ChatRunEnding) {
        if (activeStreamID == null) return
        val current = state.value
        val promptIndex = current.messages.indexOfLast { HermesTranscriptProjection.isTurnBoundary(it) }
        archiveLive()
        activity.turnDidEnd()
        state.update { it.copy(
            activeStreamId = null, activeRunStartedAt = null, streamingAssistantMessageId = null,
            isCancellingStream = false, recoveryState = ActiveStreamRecoveryState.IDLE,
            responseCompletionTrigger = it.responseCompletionTrigger + 1,
            latestRunOutcome = TranscriptTurnRunOutcome("turn:user:${renderBase + promptIndex.coerceAtLeast(0)}", current.activeRunStartedAt ?: now(), now(), ending),
            sendErrorMessage = if (ending == ChatRunEnding.FAILED && activity.failure == null) lastError else it.sendErrorMessage,
        ) }
        turnStartedAt = null; pendingEnding = null; awaitingStart = false; stopRequested = false
        onRunFinished(ending)
        val receipt = savedTurn; savedTurn = null
        if (receipt != null) refreshHistory(receipt)
        if (completedFailed) { completedFailed = false; readFailedTurn() }
        onChange()
    }

    /** Pure event entry used by the engine after sequence/identity admission. */
    internal fun apply(frame: BotJSON) {
        val payload = frame["payload"]
        when (frame["type"].text) {
            "message.start" -> {
                if (activeStreamID != null) {
                    if (awaitingStart) { awaitingStart = false; return }
                    finish(pendingEnding ?: ChatRunEnding.COMPLETED)
                }
                val prompt = if (submittingSend) null else queuedPrompt.also { queuedPrompt = null }
                beginTurn(hostTurnStartedAt, prompt)
            }
            "message.delta" -> {
                if (frame["seq"].integer?.let(deltasInRebuild::remove) == true) return
                val text = payload["text"].text ?: return
                if (text.isNotEmpty()) { ensureTurn(); append(text) }
            }
            "message.interim" -> {
                ensureTurn()
                if (payload["already_streamed"].flag != true) append(payload["text"].text.orEmpty().trim(), true)
            }
            "reasoning.delta" -> {
                val text = payload["text"].text.orEmpty()
                if (text.isNotEmpty()) { ensureTurn(); ensureAssistant(); state.update { it.copy(liveReasoningText = it.liveReasoningText + text, streamingContentTrigger = it.streamingContentTrigger + 1) } }
            }
            "tool.start", "tool.complete" -> {
                ensureTurn(); ensureAssistant()
                val completed = frame["type"].text == "tool.complete"
                val id = payload["tool_id"].text ?: "live-tool-${UUID.randomUUID()}"
                val tools = state.value.liveToolCalls.toMutableList()
                val index = tools.indexOfFirst { it.id == id }
                val previous = tools.getOrNull(index)
                val tool = ToolCall(
                    id = id, name = payload["name"].text ?: previous?.name,
                    args = payload["args"].argumentDictionary ?: previous?.args,
                    preview = if (completed) payload["result"].toolResultPreview ?: previous?.preview else previous?.preview,
                    duration = if (completed) payload["duration_s"].number else previous?.duration,
                    isCompleted = completed || previous?.isCompleted == true,
                    startedAt = previous?.startedAt ?: now(),
                )
                if (index < 0) tools += tool else tools[index] = tool
                state.update { it.copy(liveToolCalls = tools, streamingContentTrigger = it.streamingContentTrigger + 1) }
            }
            "session.title" -> {
                val key = payload["session_id"].text
                if (key == null || key == engine.storedKey) words(payload["title"])?.let { title -> state.update { it.copy(displayTitle = title) } }
            }
            "session.usage" -> contextWindow(payload["usage"])?.let { usage -> state.update { it.copy(contextWindow = usage) } }
            "session.info" -> applyInfo(payload)
            "message.complete" -> complete(payload)
            "error" -> {
                lastError = words(payload["message"])
                if (activeStreamID != null && !awaitingStart) pendingEnding = pendingEnding ?: ChatRunEnding.FAILED
                else finish(ChatRunEnding.FAILED)
            }
            "request.cancel" -> requests.cancel(payload)
            "connection.request", "connection.update" -> requests.receiveConnection(payload, frame["type"].text == "connection.request")
            "todo.updated" -> activity.receivePlan(payload)
            "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.tool", "subagent.complete" -> activity.delegatedWork.noteSubagentEvent()
            "btw.complete", "background.complete", "session.control.update" -> sideTasks.receive(frame)
            "status.update" -> if (compactionPending && payload["kind"].text == "compacted") {
                compactionPending = false; history = HermesTranscriptHistory()
                historyRefresh?.cancel()
                historyRefresh = engine.scope.launch { readNewestRows(engine.generation); if (isIdle) replaceHistory() }
            }
            // thinking.delta is spinner copy; reasoning.available carries the answer itself.
        }
        onChange()
    }

    private fun applyInfo(info: BotJSON) {
        engine.adoptStoredKey(info["stored_session_id"].text)
        noteInfo(info)
        words(info["title"])?.let { title -> state.update { it.copy(displayTitle = title) } }
        requests.applyBypass(info)
        settings.apply(info, info["running"].flag?.not() ?: !hostRunning)
        val running = info["running"].flag ?: return
        hostRunning = running
        if (running) {
            val started = info["turn_started_at"].number
            if (activeStreamID == null) hostTurnStartedAt = started
            else if (turnStartedAt == null && started != null) adoptStart(started)
        } else {
            hostTurnStartedAt = null
            if (pendingEnding == null && awaitingStart) return
            finish(pendingEnding ?: if (stopRequested) ChatRunEnding.CANCELLED else ChatRunEnding.COMPLETED)
        }
    }

    private fun complete(payload: BotJSON) {
        if (activeStreamID == null) return
        val ending = when (payload["status"].text) { "interrupted" -> ChatRunEnding.CANCELLED; "error" -> ChatRunEnding.FAILED; else -> ChatRunEnding.COMPLETED }
        val reply = payload["text"].text.orEmpty()
        val shown = state.value.messages.firstOrNull { it.messageId == state.value.streamingAssistantMessageId }?.content
        if (reply.isNotEmpty() && shown.isNullOrEmpty() && (ending != ChatRunEnding.FAILED || payload["partial"].flag == true)) append(reply)
        activity.turnDidComplete(payload)
        completedFailed = ending == ChatRunEnding.FAILED
        contextWindow(payload["usage"])?.let { usage -> state.update { it.copy(contextWindow = usage) } }
        val receipt = payload["persisted_turn"]
        if (receipt["complete"].flag == true) receipt["final_assistant_row_id"].integer?.let { savedTurn = SavedTurn(receipt["user_row_id"].integer, it) }
        if (ending == ChatRunEnding.CANCELLED) {
            queuedPrompt = null; requests.withdrawAll(); state.update { it.copy(queuedMessagesReceipt = null) }
        }
        pendingEnding = ending
        if (!hostRunning) finish(ending)
    }

    private fun noteInfo(info: BotJSON) {
        cwd = words(info["cwd"]) ?: cwd
        terminalBackend = words(info["terminal_backend"]) ?: terminalBackend
        val model = words(info["model"]); val provider = words(info["provider"])
        if (model != null && provider != null) reportedModel = HermesCall.Model(model, provider)
        state.update { it.copy(selectedWorkspacePath = cwd, selectedModelTitle = model ?: it.selectedModelTitle) }
    }

    private fun adoptStart(start: Double) {
        turnStartedAt = start
        if (start.isFinite() && start > 0) state.update { it.copy(activeRunStartedAt = minOf(it.activeRunStartedAt ?: start, start, now())) }
    }

    private fun reconcile(snapshot: BotJSON) {
        val running = snapshot["running"].flag ?: snapshot["info"]["running"].flag ?: false
        val start = snapshot["turn_started_at"].number ?: snapshot["inflight"]["started_at"].number
        val followedStart = if (activeStreamID == null) null else turnStartedAt
        hostRunning = running
        queuedPrompt = words(snapshot["queued"]["user"])
        requests.didReadSnapshot(snapshot)
        noteInfo(snapshot["info"])
        settings.apply(snapshot["info"], !running)
        words(snapshot["info"]["title"])?.let { title -> state.update { it.copy(displayTitle = title) } }
        if (activeStreamID != null && (!running || start != null && turnStartedAt != null && start != turnStartedAt)) {
            completedFailed = false
            finish(pendingEnding ?: if (HermesTurnOutcome.fromInflight(snapshot["inflight"]) != null) ChatRunEnding.FAILED else if (stopRequested) ChatRunEnding.CANCELLED else ChatRunEnding.COMPLETED)
        }
        val continuing = running && activeStreamID != null
        if (running && activeStreamID == null) beginTurn(start, null)
        if (running && start != null && turnStartedAt == null) adoptStart(start)
        readOutcome(snapshot, continuing, if (running) null else followedStart)
        if (engine.replayWasReset || needsRebuild) rebuild(snapshot, running)
        state.update { it.copy(queuedMessagesReceipt = queuedPrompt?.let { "Message queued" }) }
    }

    private fun readOutcome(snapshot: BotJSON, followsTurn: Boolean, endedStart: Double? = null) {
        val inflight = snapshot["inflight"]
        val prompt = if (inflight["error"].isNull) null else savedPromptRowID(inflight["started_at"].number ?: snapshot["turn_started_at"].number)
        val ended = savedPromptRowID(endedStart)
        activity.readSnapshot(snapshot, prompt, followsTurn || ended != null, ended)
    }

    private fun savedPromptRowID(start: Double?): Int? {
        if (start == null) return null
        val messages = HermesTranscriptProjection.project(history.rows, engine.storedKey.orEmpty()).messages.filter(HermesTranscriptProjection::isTurnBoundary)
        val last = messages.lastOrNull() ?: return null
        if (last.timestamp == null || last.timestamp < start) return null
        val previous = messages.dropLast(1).lastOrNull()
        if (previous != null && (previous.timestamp == null || previous.timestamp >= start)) return null
        return last.rowID
    }

    private fun readFailedTurn() {
        val attempt = engine.generation; val turns = turnsStarted
        engine.scope.launch {
            try {
                val snapshot = engine.resume(attempt)
                if (turns != turnsStarted || !engine.isCurrent(snapshot)) return@launch
                readNewestRows(attempt)
                if (turns == turnsStarted) readOutcome(snapshot, false)
                onChange()
            } catch (error: CancellationException) { throw error } catch (_: Exception) { /* Retry stays hidden without a confirmed row. */ }
        }
    }

    private fun rebuild(snapshot: BotJSON, running: Boolean) {
        needsRebuild = false
        val projected = HermesTranscriptProjection.project(history.rows, engine.storedKey.orEmpty())
        val messages = projected.messages.map(HermesTranscriptProjection::displayed).toMutableList()
        val inflight = snapshot["inflight"]
        val start = inflight["started_at"].number ?: snapshot["turn_started_at"].number
        words(inflight["user"])?.let { text ->
            val prompt = HermesTranscriptProjection.displayed(ChatMessage(role = "user", content = text, timestamp = start, messageId = "${engine.storedKey}/live-user"))
            val last = messages.lastOrNull(HermesTranscriptProjection::isTurnBoundary)
            val holds = if (last?.timestamp != null && start != null) last.timestamp >= start else last?.content == prompt.content && last?.attachments == prompt.attachments
            if (!holds) messages += prompt
        }
        var streaming: String? = null
        words(inflight["assistant"])?.let { text ->
            val id = "${engine.storedKey}/live-${UUID.randomUUID()}"
            if (running || messages.lastOrNull()?.content != text) messages += ChatMessage(role = "assistant", content = text, messageId = id)
            if (running) streaming = id
            deltasInRebuild += heldDeltas(heldFrames, engine.sequence, text, replayedReply)
        }
        compaction = projected.compaction
        state.update { it.copy(
            completedToolCallGroups = projected.toolCallGroups, completedReasoningGroups = projected.reasoningGroups,
            liveReasoningText = "", liveToolCalls = emptyList(), streamingAssistantMessageId = streaming,
            canLoadOlderMessages = history.hasOlder,
        ) }
        updateMessages(messages)
    }

    private suspend fun page(offset: Int, attempt: Int): List<BotJSON> {
        engine.check(attempt)
        val rows = engine.wire.sessionMessages(engine.storedKey ?: throw BotFailure.Stale, engine.target.profile, offset).orEmpty()
        engine.check(attempt)
        return rows
    }

    private suspend fun newestRows(attempt: Int): Pair<List<BotJSON>, Boolean> {
        var rows = emptyList<BotJSON>()
        repeat(5) {
            val page = page(rows.size, attempt)
            rows = page + rows
            if (page.size < HermesREST.TRANSCRIPT_PAGE_SIZE) return rows to true
            if (history.reaches(page)) return rows to false
        }
        return rows to false
    }

    private suspend fun readNewestRows(attempt: Int) {
        val turns = turnsStarted
        try {
            val (rows, start) = newestRows(attempt)
            history = history.mergingNewest(rows, start)
            historyTurns = turns; historyFailure = null
            val projected = HermesTranscriptProjection.project(history.rows, engine.storedKey.orEmpty())
            onHistory(projected.messages.map(HermesTranscriptProjection::displayed), history.newestCoverage)
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) {
            if (attempt != engine.generation) return
            historyFailure = error
            state.update { it.copy(errorMessage = error.message) }
        }
    }

    private fun replaceHistory() {
        if (historyFailure != null) return
        val projected = HermesTranscriptProjection.project(history.rows, engine.storedKey.orEmpty())
        compaction = projected.compaction
        state.update { it.copy(completedReasoningGroups = projected.reasoningGroups, completedToolCallGroups = projected.toolCallGroups,
            canLoadOlderMessages = history.hasOlder, liveReasoningText = "", liveToolCalls = emptyList(), errorMessage = null) }
        updateMessages(projected.messages.map(HermesTranscriptProjection::displayed))
    }

    private fun refreshHistory(receipt: SavedTurn) {
        val attempt = engine.generation; val turns = turnsStarted
        historyRefresh?.cancel()
        historyRefresh = engine.scope.launch {
            try {
                val (rows, start) = newestRows(attempt)
                if (turns != turnsStarted || !isIdle) return@launch
                val merged = history.mergingNewest(rows, start)
                if (!merged.holds(receipt.replyRowID) || receipt.promptRowID?.let { !merged.holds(it) } == true) return@launch
                history = merged; historyTurns = turns; historyFailure = null
                replaceHistory()
                onHistory(state.value.messages, history.newestCoverage)
                onChange()
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (attempt == engine.generation) { historyFailure = error; state.update { it.copy(errorMessage = error.message) } } }
        }
    }

    suspend fun loadOlderHistory(): Boolean {
        if (!history.hasOlder || engine.connectionState != HermesConversation.ConnectionState.CONNECTED) return false
        val attempt = engine.generation; val turns = turnsStarted
        state.update { it.copy(isLoadingOlderMessages = true) }
        try {
            if (historyTurns != turns || !isIdle || history.needsRecount) {
                val (rows, start) = newestRows(attempt)
                if (turns == turnsStarted && isIdle) {
                    history = history.mergingNewest(rows, start); historyTurns = turns; replaceHistory()
                    if (!history.hasOlder) return false
                } else history = history.countingNewer(rows) ?: return false
            }
            val offset = history.nextOffset
            val page = page(offset, attempt)
            if (history.nextOffset != offset) return false
            val previous = HermesTranscriptProjection.project(history.rows, engine.storedKey.orEmpty()).messages
            val result = history.prependingOlder(page)
            history = result.history
            val projected = HermesTranscriptProjection.project(history.rows, engine.storedKey.orEmpty())
            val oldIDs = previous.map { it.id }.toSet()
            val older = projected.messages.takeWhile { it.id !in oldIDs }.map(HermesTranscriptProjection::displayed)
            renderBase -= older.size
            compaction = projected.compaction
            state.update { it.copy(completedReasoningGroups = (projected.reasoningGroups + it.completedReasoningGroups).distinctBy { group -> group.id },
                completedToolCallGroups = (projected.toolCallGroups + it.completedToolCallGroups).distinctBy { group -> group.id }, canLoadOlderMessages = history.hasOlder) }
            updateMessages(older + state.value.messages)
            onHistory(projected.messages, null)
            onChange()
            return result.added
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) { historyFailure = error; state.update { it.copy(errorMessage = error.message) }; return false }
        finally { state.update { it.copy(isLoadingOlderMessages = false) } }
    }

    suspend fun retryHistoryIfFailed() { if (isIdle) { readNewestRows(engine.generation); replaceHistory() } }
    fun recoverAfterLostAnswer() { needsRebuild = true; if (engine.isActive) reattach() }
    fun retryWasRefused(rowID: Int) { activity.refuseRetry(rowID); recoverAfterLostAnswer() }
    private fun reattach() {
        if (reattaching != null || needsSignIn) return
        reattaching = engine.scope.launch {
            try { requests.willLeave(); engine.suspendConnection(); activate() }
            finally { reattaching = null; onChange() }
        }
    }

    private fun insertPrompt(prompt: ChatMessage) {
        val rows = state.value.messages.toMutableList()
        val index = rows.indexOfFirst { it.messageId == state.value.streamingAssistantMessageId }
        if (index < 0) rows += prompt else rows.add(index, prompt)
        updateMessages(rows)
    }

    private fun updateMessages(messages: List<ChatMessage>) {
        state.update { it.copy(messages = messages, messagesOffset = renderBase,
            transcriptMessages = messages.mapIndexed { index, message -> TranscriptMessage(index, "transcript:${renderBase + index}", message.id, message) },
            streamingContentTrigger = it.streamingContentTrigger + 1) }
    }

    private fun syncSettings() {
        val selected = settings.selectedModel
        state.update { it.copy(
            selectedModelId = selected?.id, selectedModelProviderId = selected?.providerID,
            selectedModelTitle = selected?.displayName ?: it.selectedModelTitle,
            modelCatalogGroups = settings.catalog.groups, profiles = settings.profileOptions,
            selectedProfileTitle = engine.target.profile, reasoningEffort = settings.effort,
            supportedReasoningEfforts = settings.effortLevels, showsReasoningEffortControl = settings.showsEffort,
            isLoadingComposerConfiguration = settings.isLoading, composerConfigurationErrorMessage = settings.errorMessage,
        ) }
    }
    private fun syncSlash() { state.update { it.copy(serverCommands = slashCommands.commands, skillSuggestions = slashCommands.catalog.skills) } }
    private fun syncConnection() {
        val recovery = when (engine.connectionState) {
            HermesConversation.ConnectionState.CONNECTED -> ActiveStreamRecoveryState.IDLE
            HermesConversation.ConnectionState.RECOVERING -> ActiveStreamRecoveryState.CHECKING
            HermesConversation.ConnectionState.DISCONNECTED -> if (engine.isReconnecting) ActiveStreamRecoveryState.RECONNECTING else ActiveStreamRecoveryState.IDLE
        }
        state.update { it.copy(recoveryState = recovery, isStreamHeartbeatFresh = engine.connectionState == HermesConversation.ConnectionState.CONNECTED) }
    }

    override fun conversationDidReset() { requests.reset(); settings.disconnect(); activity.delegatedWork.disconnect(); heldFrames.clear(); deltasInRebuild.clear(); replayedReply = ""; onChange() }
    override fun conversationDidIdentify(root: String) { state.update { it.copy(sessionId = root) }; onChange() }
    override fun conversationWillReplay(newRuntime: Boolean) { requests.willReplay(); if (newRuntime) activity.dropPlan() }
    override fun conversationDidReplay(reply: BotJSON, frames: List<BotJSON>) {
        requests.didReplay(reply, frames)
        requests.willReadSnapshot()
        val lost = engine.replayWasReset || needsRebuild
        sideTasks.didReplay(frames, lost)
        if (lost) {
            activity.dropNotice()
            val lastStart = frames.indexOfLast { it["type"].text == "message.start" }
            replayedReply = frames.drop(lastStart + 1).filter { it["type"].text == "message.delta" }.joinToString("") { it["payload"]["text"].text.orEmpty() }
        } else frames.forEach(::apply)
    }
    override suspend fun conversationDidReadSnapshot(snapshot: BotJSON, runtime: String, attempt: Int) {
        if (!engine.isCurrent(snapshot)) throw BotFailure.Unsupported
        if (engine.replayWasReset || needsRebuild) {
            if (compactionPending) history = HermesTranscriptHistory()
            readNewestRows(attempt)
        }
        engine.check(attempt)
        reconcile(snapshot)
    }
    override fun conversationDidFailToAttach(error: Throwable) {
        if (history.newestCoverage == null) needsRebuild = true
        state.update { it.copy(isLoading = false) }
    }
    override suspend fun conversationDidConnect(runtime: String, attempt: Int) {
        heldFrames.clear(); deltasInRebuild.clear(); replayedReply = ""; needsSignIn = false
        state.update { it.copy(isLoading = false, isViewingCachedData = false, errorMessage = historyFailure?.message) }
        sideTasks.didConnect(runtime, attempt)
        engine.scope.launch { settings.connect(runtime, attempt) }
        engine.scope.launch { slashCommands.connect(runtime, attempt) }
        engine.scope.launch { if (engine.generation == attempt) activity.delegatedWork.connect(HermesDelegatedWork.Context(engine.connection.id, runtime, attempt)) }
        onChange()
    }
    override fun conversationDidReceive(frame: BotJSON, afterGap: Boolean) {
        if (afterGap) { sideTasks.receive(frame); needsRebuild = true; reattach() } else apply(frame)
    }
    override fun conversationDidLoseFrames() { needsRebuild = true; reattach() }
    override fun conversationDidHold(frame: BotJSON) {
        if (heldFrames.size < HermesConversation.HELD_FRAME_LIMIT) heldFrames += frame
        if (frame["type"].text == "request.cancel") requests.holdRequestFrame()
    }
    override fun conversationDidReceiveRequest(envelope: BotJSON) { requests.receive(envelope); onChange() }
    override fun conversationWillDisconnect() { requests.willLeave(); requests.disconnect(); activity.delegatedWork.disconnect() }
    override fun conversationDidDisconnect(failure: BotFailure, retrying: Boolean) {
        if (failure == BotFailure.Rejected(401) || failure == BotFailure.SignInExpired || failure == BotFailure.SignInChanged) needsSignIn = true
        if (!retrying) state.update { it.copy(errorMessage = BotConnectionAdvice.message(failure, engine.connection.address), isLoading = false) }
        onChange()
    }

    companion object {
        private fun words(value: BotJSON) = value.text?.takeIf { it.isNotBlank() }
        fun contextWindow(usage: BotJSON): ContextWindowSnapshot? {
            val used = usage["context_used"].integer ?: return null
            val length = usage["context_max"].integer?.takeIf { it > 0 } ?: return null
            return ContextWindowSnapshot(contextLength = length, lastPromptTokens = used, inputTokens = usage["input"].integer, outputTokens = usage["output"].integer)
        }
        internal fun heldDeltas(held: List<BotJSON>, after: Int, reply: String, replayed: String): Set<Int> {
            var cursor = after
            val joined = StringBuilder(replayed)
            val run = ArrayList<Int>(); var matched = emptySet<Int>()
            for (frame in held) {
                val seq = frame["seq"].integer ?: continue
                if (seq <= cursor) continue
                cursor = seq
                if (frame["type"].text == "message.start") break
                if (frame["type"].text != "message.delta") continue
                val text = frame["payload"]["text"].text?.takeIf(String::isNotEmpty) ?: continue
                joined.append(text)
                if (joined.length > reply.length) break
                run += seq
                if (reply.endsWith(joined.toString())) matched = run.toSet()
            }
            return matched
        }
    }
}
