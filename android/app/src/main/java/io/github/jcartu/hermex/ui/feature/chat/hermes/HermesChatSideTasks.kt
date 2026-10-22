package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.SubmittedGoal
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotSessionControl
import io.github.jcartu.hermex.model.hermes.HermesBackgroundTask
import io.github.jcartu.hermex.model.hermes.HermesBtw
import io.github.jcartu.hermex.model.hermes.HermesGoalReply
import io.github.jcartu.hermex.model.hermes.HermesSideTaskFailure
import io.github.jcartu.hermex.net.hermes.BotSettingFailure
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Port of `Features/Chat/HermesChatSideTasks.swift`: one `/btw`, durable background cards,
 * `/goal`, and generation-fenced loop/heartbeat control for one [HermesConversation].
 * Completions match only the host task id and early completions are buffered until the start
 * reply reveals that id.
 */
class HermesChatSideTasks(
    private val engine: HermesConversation,
) {
    /** A task card was added, finished, or marked unavailable. */
    var onBackgroundChange: (HermesBackgroundTask) -> Unit = {}

    /** The goal reported by `session.control` changed. */
    var onGoalChange: (SubmittedGoal?) -> Unit = {}

    /** The host reaped this runtime (4001); the coordinator should reattach read-only. */
    var onNeedsReattach: () -> Unit = {}

    /** Called after an externally visible value changes. */
    var onChange: () -> Unit = {}

    /** The side question currently occupying the panel. */
    var btw: HermesBtw? = null
        private set

    /** Background tasks, oldest first. */
    var backgroundTasks: List<HermesBackgroundTask> = emptyList()
        private set

    /** The goal from the newest control snapshot. */
    var goal: SubmittedGoal? = null
        private set

    /** Loop then heartbeat, when the host reports them. */
    var automations: List<BotSessionControl> = emptyList()
        private set

    /** A deliberate Pause or Resume waiting for confirmation. */
    var pendingAutomation: BotSessionControl? = null
        private set

    /** True only while a confirmed control write is in flight. */
    var isChangingAutomation: Boolean = false
        private set

    private var controlAttempt: Int? = null
    private val unclaimed = mutableMapOf<String, BotJSON>()
    private var startsInFlight = 0
    private var controlRevision = 0
    private var needsResultRead = false
    private var pendingAttempt = 0

    /** A question is out and still waiting; only one may be started at a time. */
    val isAsking: Boolean get() = btw?.state == HermesBtw.State.Waiting

    /** Whether this host supports changing loop and heartbeat state. */
    val controlsAutomations: Boolean
        get() = "session.control" !in engine.wire.unavailableMethods

    /** Whether the displayed controls came from this exact attach. */
    val canChangeAutomations: Boolean
        get() = controlAttempt == engine.generation

    /**
     * Port of `isGoalControl(_:)`: controls may run while a goal-owned turn is active; setting
     * unrelated new goal text still follows the normal turn admission path.
     */
    companion object {
        private val GOAL_CONTROL_VERBS = setOf(
            "", "status", "show", "pause", "resume", "clear", "stop", "done", "unwait",
        )

        fun isGoalControl(argument: String): Boolean {
            val normalized = argument.trim().lowercase()
            return normalized in GOAL_CONTROL_VERBS ||
                startsWithWord(normalized, "wait") ||
                startsWithWord(normalized, "gate")
        }

        private fun startsWithWord(text: String, word: String): Boolean =
            text.startsWith(word) && (text.length == word.length || text[word.length].isWhitespace())

        /** A side session's last non-tool assistant reply. */
        fun result(messages: List<BotJSON>): String? {
            for (index in messages.indices.reversed()) {
                val message = messages[index]
                if (message["role"].text != "assistant" || message["tool_calls"].list.orEmpty().isNotEmpty()) continue
                val candidate = text(message["content"])
                if (nonEmpty(candidate) != null) return candidate
            }
            return null
        }

        /** True only for the host's runtime-reaped verdict. */
        fun isReaped(error: Throwable): Boolean = when (error) {
            is BotFailure.Rejected -> error.code == 4001
            is BotSettingFailure.Rejected -> error.code == 4001
            else -> false
        }

        /** A content row may be a string or an array of text parts. */
        private fun text(content: BotJSON): String? =
            content.text ?: content.list?.mapNotNull { it["text"].text }?.joinToString("")

        private fun nonEmpty(text: String?): String? = text?.takeIf { it.isNotBlank() }
    }

    /** Runs one `command.dispatch goal` write and decodes the host's exec/send union. */
    suspend fun dispatchGoal(argument: String): HermesGoalReply {
        val reply = write(call = { runtime -> HermesCall.CommandDispatch("goal", argument, runtime) })
        return when (reply["type"].text) {
            "exec" -> HermesGoalReply.Exec(reply["output"].text.orEmpty())
            "send" -> {
                val message = reply["message"].text?.takeIf { it.isNotBlank() }
                    ?: throw BotFailure.Unsupported
                HermesGoalReply.Send(
                    notice = nonEmpty(reply["notice"].text),
                    message = message,
                    display = nonEmpty(reply["display"].text),
                )
            }
            else -> throw BotFailure.Unsupported
        }
    }

    /** Holds a host-offered automation action until the user confirms it. */
    fun ask(control: BotSessionControl) {
        if (
            control.action == null || !canChangeAutomations || control !in automations ||
            isChangingAutomation
        ) return
        pendingAutomation = control
        pendingAttempt = engine.generation
        onChange()
    }

    /** Dismisses an automation confirmation without writing. */
    fun cancelAutomation() {
        if (pendingAutomation == null) return
        pendingAutomation = null
        onChange()
    }

    /**
     * Sends one confirmed Pause/Resume. The attach, displayed control, and control revision are
     * checked again at socket dispatch, so a replacement loop or heartbeat is never changed.
     */
    suspend fun confirmAutomation(control: BotSessionControl) {
        val hadPending = pendingAutomation != null
        pendingAutomation = null
        if (hadPending) onChange()
        val action = control.action
        if (
            pendingAttempt != engine.generation || !canChangeAutomations || control !in automations ||
            action == null || isChangingAutomation
        ) throw BotFailure.Stale

        isChangingAutomation = true
        onChange()
        try {
            val revision = controlRevision
            val attempt = engine.generation
            val profile = engine.target.profile
            val reply = try {
                write(
                    call = { runtime -> HermesCall.SessionControl(runtime, profile, action) },
                    stillCurrent = {
                        if (control !in automations) throw BotFailure.Stale
                    },
                )
            } catch (error: HermesChatTurnCoordinator.NotSent) {
                if (control !in automations) throw BotFailure.Stale
                throw error
            }
            if (reply["control"].fields == null) throw BotSettingFailure.UnknownOutcome
            if (controlRevision == revision) {
                controlRevision += 1
                applyControl(reply["control"], attempt)
            }
        } finally {
            if (isChangingAutomation) {
                isChangingAutomation = false
                onChange()
            }
        }
    }

    /**
     * Starts one `/btw` ask. An unconfirmed write keeps the panel open as unavailable; a call
     * that never left or was explicitly refused closes it and throws.
     */
    suspend fun ask(question: String) {
        val asked = HermesBtw(question = question)
        btw = asked
        onChange()
        val started = try {
            start { runtime -> HermesCall.PromptBtw(runtime, question) }
        } catch (error: HermesSideTaskFailure) {
            if (error == HermesSideTaskFailure.Unconfirmed) {
                updateBtw(asked.id) { copy(state = HermesBtw.State.Unavailable) }
                return
            }
            if (btw?.id == asked.id) {
                btw = null
                onChange()
            }
            throw error
        }
        val taskID = nonEmpty(started.reply["task_id"].text)
        if (taskID == null) {
            updateBtw(asked.id) { copy(state = HermesBtw.State.Unavailable) }
            return
        }
        updateBtw(asked.id) { copy(taskID = taskID) }
        started.early?.let(::receive)
    }

    /** Closes the panel; an eventual host answer is deliberately ignored. */
    fun closeBtw() {
        if (btw == null) return
        btw = null
        onChange()
    }

    /** Starts one durable side session and publishes its running card. */
    suspend fun startBackground(prompt: String) {
        val started = start { runtime -> HermesCall.PromptBackground(runtime, prompt) }
        val taskID = nonEmpty(started.reply["task_id"].text)
            ?: throw HermesSideTaskFailure.Unconfirmed
        val task = HermesBackgroundTask(taskID, prompt, HermesBackgroundTask.State.Running)
        backgroundTasks = backgroundTasks + task
        onBackgroundChange(task)
        onChange()
        started.early?.let(::receive)
    }

    /** Applies a side-task/control frame, returning false only for unrelated frame types. */
    fun receive(frame: BotJSON): Boolean {
        val payload = frame["payload"]
        when (frame["type"].text) {
            "btw.complete" -> {
                val taskID = nonEmpty(payload["task_id"].text) ?: return true
                val current = btw
                if (current?.taskID == taskID) {
                    updateBtw(current.id) {
                        copy(state = HermesBtw.State.Answered(payload["text"].text.orEmpty()))
                    }
                } else if (startsInFlight > 0) {
                    unclaimed[taskID] = frame
                }
                return true
            }
            "background.complete" -> {
                val taskID = nonEmpty(payload["task_id"].text) ?: return true
                val settled = settle(
                    taskID,
                    HermesBackgroundTask.State.Finished(payload["text"].text.orEmpty()),
                )
                if (!settled && startsInFlight > 0) unclaimed[taskID] = frame
                return true
            }
            "session.control.update" -> {
                controlRevision += 1
                applyControl(payload["control"], engine.generation)
                return true
            }
            else -> return false
        }
    }

    /** Applies a lossy replay and schedules exactly one durable background-result recovery. */
    fun didReplay(frames: List<BotJSON>, lostFrames: Boolean) {
        if (!lostFrames) return
        frames.forEach(::receive)
        btw?.takeIf { it.state == HermesBtw.State.Waiting }?.let { current ->
            updateBtw(current.id) { copy(state = HermesBtw.State.Unavailable) }
        }
        if (!needsResultRead) {
            needsResultRead = true
            onChange()
        }
    }

    /** Starts this attach's control read and the one recovery read requested by a lossy replay. */
    fun didConnect(runtime: String, attempt: Int) {
        if (pendingAttempt != attempt && pendingAutomation != null) {
            pendingAutomation = null
            onChange()
        }
        readControl(runtime, attempt)
        if (needsResultRead) {
            needsResultRead = false
            readResults()
        }
    }

    private fun readControl(runtime: String, attempt: Int) {
        if ("session.control.read" in engine.wire.unavailableMethods) return
        val revision = controlRevision
        engine.scope.launch {
            val reply = try {
                engine.request(
                    HermesCall.SessionControlRead(runtime, engine.target.profile),
                    attempt,
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                return@launch
            }
            if (controlRevision == revision) applyControl(reply["control"], attempt)
        }
    }

    private fun readResults() {
        val pending = backgroundTasks.filter { it.state !is HermesBackgroundTask.State.Finished }
        if (pending.isEmpty()) return
        val profile = engine.target.profile
        val wire = engine.wire
        val attempt = engine.generation
        engine.scope.launch {
            for (task in pending) {
                if (engine.generation != attempt) {
                    needsResultRead = true
                    return@launch
                }
                val state = try {
                    val messages = wire.sessionMessages(task.id, profile)
                    result(messages.orEmpty())?.let(HermesBackgroundTask.State::Finished)
                        ?: HermesBackgroundTask.State.Unavailable
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (error == BotFailure.Stale || engine.generation != attempt) {
                        needsResultRead = true
                        return@launch
                    }
                    HermesBackgroundTask.State.Unavailable
                }
                if (engine.generation != attempt) {
                    needsResultRead = true
                    return@launch
                }
                settle(task.id, state)
            }
        }
    }

    /** Port of the early-completion tuple returned by `start` in `HermesChatSideTasks.swift`. */
    private data class Started(val reply: BotJSON, val early: BotJSON?)

    /** Maps one side-start write into the three user-facing failure classes. */
    private suspend fun start(call: (String) -> HermesCall): Started {
        startsInFlight += 1
        try {
            val reply = write(call)
            val taskID = nonEmpty(reply["task_id"].text)
            return Started(reply, taskID?.let(unclaimed::remove))
        } catch (_: HermesChatTurnCoordinator.NotSent) {
            throw HermesSideTaskFailure.NotSent
        } catch (error: BotFailure.Rejected) {
            throw HermesSideTaskFailure.Refused
        } catch (_: Throwable) {
            throw HermesSideTaskFailure.Unconfirmed
        } finally {
            startsInFlight -= 1
            if (startsInFlight == 0) unclaimed.clear()
        }
    }

    /** One write bound to this exact attached runtime; nothing here retries automatically. */
    private suspend fun write(
        call: (String) -> HermesCall,
        stillCurrent: (() -> Unit)? = null,
    ): BotJSON {
        val runtime = engine.runtime
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED || runtime == null) {
            throw HermesChatTurnCoordinator.NotSent(BotFailure.Transport)
        }
        var dispatched = false
        return try {
            engine.write(call(runtime), engine.generation, runtime) {
                stillCurrent?.invoke()
                dispatched = true
            }
        } catch (error: Throwable) {
            if (isReaped(error)) onNeedsReattach()
            if (dispatched) throw error
            throw HermesChatTurnCoordinator.NotSent(error)
        }
    }

    private fun updateBtw(id: String, transform: HermesBtw.() -> HermesBtw) {
        val current = btw ?: return
        if (current.id != id) return
        val next = current.transform()
        if (next == current) return
        btw = next
        onChange()
    }

    /** A finished background result is final; a later durable read cannot replace it. */
    private fun settle(taskID: String, state: HermesBackgroundTask.State): Boolean {
        val index = backgroundTasks.indexOfFirst { it.id == taskID }
        if (index < 0) return false
        val current = backgroundTasks[index]
        if (current.state == state || current.state is HermesBackgroundTask.State.Finished) return true
        val next = current.copy(state = state)
        backgroundTasks = backgroundTasks.toMutableList().also { it[index] = next }
        onBackgroundChange(next)
        onChange()
        return true
    }

    private fun applyControl(control: BotJSON, attempt: Int) {
        if (control.fields == null) return
        var changed = false
        if (controlAttempt != attempt) {
            controlAttempt = attempt
            changed = true
        }
        val nextAutomations = BotSessionControl.read(control).filter {
            it.kind != BotSessionControl.Kind.GOAL
        }
        if (automations != nextAutomations) {
            automations = nextAutomations
            changed = true
        }
        val nextGoal = BotSessionControl.goal(control["goal"])
        if (goal != nextGoal) {
            goal = nextGoal
            onGoalChange(nextGoal)
            changed = true
        }
        if (changed) onChange()
    }
}
