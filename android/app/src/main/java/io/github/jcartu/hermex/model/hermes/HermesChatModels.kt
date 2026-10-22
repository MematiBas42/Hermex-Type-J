package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.SubmittedGoal
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Port of `HermesPlan` (`Features/Chat/HermesChatActivity.swift`): the agent's plan, from
 * `todo.updated` and a snapshot's `todo_state`: `{todos: [{id, content, status}], revision}`.
 */
data class HermesPlan(val items: List<Item>, val revision: Int) {
    data class Item(val id: String, val content: String, val status: String) {
        /** `pending`, `in_progress`, `completed` or `cancelled`; anything else reads as pending. */
        val isDone: Boolean get() = status == "completed" || status == "cancelled"
    }

    val completedCount: Int get() = items.count { it.isDone }
    val current: Item? get() = items.firstOrNull { it.status == "in_progress" } ?: items.firstOrNull { !it.isDone }
    val isFinished: Boolean get() = items.all { it.isDone }

    /** "2 of 5 · current step", as the plan row and strip show it. */
    val progress: String
        get() {
            val count = "$completedCount of ${items.size}"
            val step = current ?: return count
            return "$count · ${step.content}"
        }

    /** The list as copied text, one `[x]`-style marker per item. */
    val copyText: String
        get() = items.joinToString("\n") { item ->
            val marker = when (item.status) {
                "completed" -> "[x]"
                "cancelled" -> "[-]"
                "in_progress" -> "[~]"
                else -> "[ ]"
            }
            "$marker ${item.content}"
        }

    companion object {
        const val ITEM_LIMIT = 50

        /** Null when the payload is malformed or carries no items. */
        fun from(json: BotJSON): HermesPlan? {
            val rows = json["todos"].list ?: return null
            val items = rows.take(ITEM_LIMIT).mapIndexedNotNull { index, row ->
                val content = row["content"].text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val id = row["id"].text?.takeIf { it.isNotEmpty() } ?: "plan-$index"
                Item(id, content, row["status"].text ?: "pending")
            }
            if (items.isEmpty()) return null
            return HermesPlan(items, max(0, json["revision"].integer ?: 0))
        }
    }
}

/**
 * Port of `HermesPlanState`: the newest plan a session's host reported. Revision-monotonic:
 * an older revision never replaces a newer one. An empty list at revision 1 or later is the
 * host clearing the plan, which hides it until a newer revision.
 */
data class HermesPlanState(val plan: HermesPlan? = null, val revision: Int? = null) {
    /** The state after a `todo.updated` payload or a snapshot's `todo_state`, or null when it changed nothing. */
    fun applying(json: BotJSON): HermesPlanState? {
        val todos = json["todos"].list ?: return null
        val next = max(0, json["revision"].integer ?: 0)
        if (next < (revision ?: 0)) return null
        val plan: HermesPlan? = if (todos.isEmpty()) {
            // An unused store reports an empty list at revision 0: no plan, and no clear.
            if (next < 1) return null
            null
        } else {
            HermesPlan.from(json) ?: return null
        }
        if (plan == this.plan && next == revision) return null
        return HermesPlanState(plan, next)
    }
}

/** Port of `HermesBtw` (`HermesChatSideTasks.swift`): the `/btw` side question the panel shows (#1013). */
data class HermesBtw(
    val question: String,
    /** This ask, so a reply never lands on a later one. */
    val id: String = UUID.randomUUID().toString(),
    /** The host's `btw_…` id, once the ask is confirmed. */
    val taskID: String? = null,
    val state: State = State.Waiting,
) {
    sealed class State {
        /** Asked; waiting for its `btw.complete`. */
        object Waiting : State()

        /** The host's answer. Empty when it produced none. */
        data class Answered(val text: String) : State()

        /** A reconnect lost the answer, or the host never confirmed the question. */
        object Unavailable : State()
    }
}

/** Port of `HermesBackgroundTask`: a side agent in its own `bg_<id>` session, shown as a transcript card (#1013). */
data class HermesBackgroundTask(
    /** The host's `bg_…` task id, which is also the side session's id. */
    val id: String,
    val prompt: String,
    val state: State,
) {
    sealed class State {
        object Running : State()

        /** `background.complete`'s text, or the side session's last reply. */
        data class Finished(val text: String) : State()

        /** Neither the replay nor the side session had a result. A later completion or read can still fill it. */
        object Unavailable : State()
    }
}

/** Port of `HermesGoalReply`: what the host made of a `/goal` command (`command.dispatch`). */
sealed class HermesGoalReply {
    /** Done on the host; [output] says what changed. */
    data class Exec(val output: String) : HermesGoalReply()

    /** The goal wants a turn: [message] goes out as a prompt, once; [display] is what the transcript shows. */
    data class Send(val notice: String?, val message: String, val display: String?) : HermesGoalReply()
}

/** Port of `HermesSideTaskFailure`: why a side question or background task did not start. */
sealed class HermesSideTaskFailure : Exception() {
    /** It never reached the host. */
    object NotSent : HermesSideTaskFailure()

    /** The host refused it. */
    object Refused : HermesSideTaskFailure()

    /** It went out, but the host's answer was lost: it may run with no way to follow it. */
    object Unconfirmed : HermesSideTaskFailure()
}

/**
 * Port of `BotSessionControl` (`Features/Bots/BotChatSettings.swift`): one row of a
 * `session.control.read` snapshot, the goal, the loop or the heartbeat, with the action the
 * host offers for its state (#1142).
 */
data class BotSessionControl(val kind: Kind, val status: String, val title: String) {
    enum class Kind(val rawValue: String) {
        GOAL("goal"), LOOP("loop"), HEARTBEAT("heartbeat");

        val label: String
            get() = when (this) {
                GOAL -> "Goal"
                LOOP -> "Loop"
                HEARTBEAT -> "Heartbeat"
            }
    }

    val id: String get() = kind.rawValue

    val action: String?
        get() = when (status) {
            "active" -> "$id.pause"
            "paused" -> "$id.resume"
            else -> null
        }

    val actionTitle: String get() = if (status == "paused") "Resume" else "Pause"

    val consequence: String
        get() = if (status == "paused") {
            "Resume automatic work for this chat?"
        } else {
            "Pause automatic follow-up work? The current response continues. You can resume here."
        }

    companion object {
        fun read(payload: BotJSON): List<BotSessionControl> = Kind.entries.mapNotNull { kind ->
            val row = payload[kind.rawValue]
            val status = row["status"].text ?: return@mapNotNull null
            BotSessionControl(kind, status, row["title"].text ?: row["prompt"].text ?: kind.label)
        }

        /** A control snapshot's goal as the goal menu reads it; null once cleared. */
        fun goal(goal: BotJSON): SubmittedGoal? {
            if (goal.fields == null) return null
            val title = goal["title"].text ?: return null
            return SubmittedGoal(
                goal = title, status = goal["status"].text, turnsUsed = goal["turns_used"].integer,
                maxTurns = goal["max_turns"].integer, lastVerdict = goal["last_verdict"].text,
                lastReason = goal["last_reason"].text, pausedReason = goal["paused_reason"].text,
            )
        }
    }
}

/**
 * Port of `HermesDelegatedWorker` (`Features/Chat/HermesDelegatedWork.swift`): one live
 * worker projected by the direct-Hermes gateway. Tolerant: identity is the minimum needed to
 * show a row.
 */
data class HermesDelegatedWorker(
    val subagentID: String,
    val parentID: String?,
    val depth: Int,
    val goal: String,
    val delegationID: String?,
    val model: String?,
    val startedAt: Double?,
    val status: String,
    val toolCount: Int?,
    val lastTool: String?,
) {
    data class Identity(val subagentID: String, val startedAt: Double?, val delegationID: String?)

    val identity: Identity get() = Identity(subagentID, startedAt, delegationID)

    /** Destructive control also requires the generation-bearing timestamp. */
    val canInterrupt: Boolean get() = startedAt != null

    companion object {
        fun from(value: BotJSON): HermesDelegatedWorker? {
            val subagentID = value["subagent_id"].text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return HermesDelegatedWorker(
                subagentID = subagentID,
                parentID = value["parent_id"].text?.takeIf { it.isNotEmpty() },
                depth = max(0, value["depth"].integer ?: 0),
                goal = value["goal"].text?.trim()?.takeIf { it.isNotEmpty() } ?: "Delegated worker",
                delegationID = value["delegation_id"].text?.takeIf { it.isNotEmpty() },
                model = value["model"].text?.takeIf { it.isNotEmpty() },
                startedAt = value["started_at"].number,
                status = value["status"].text?.trim()?.takeIf { it.isNotEmpty() } ?: "Working",
                toolCount = value["tool_count"].integer?.takeIf { it >= 0 },
                lastTool = value["last_tool"].text?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** Port of `HermesDelegatedTail`: one worker's latest output, capped at 16 KiB. */
data class HermesDelegatedTail(
    val worker: HermesDelegatedWorker.Identity,
    val available: Boolean,
    val text: String,
    val truncated: Boolean,
)

/**
 * Port of `HermesDelegationCompletion`: a durable async-delegation delivery projected from a
 * transcript row's display metadata. The report stays opaque.
 */
data class HermesDelegationCompletion(
    val id: String,
    val report: String,
    val delegationID: String?,
    val taskCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val durationSeconds: Double?,
) {
    val hasFailures: Boolean get() = failedCount > 0

    companion object {
        const val DISPLAY_KIND = "async_delegation_complete"
        const val MAXIMUM_DISPLAY_COUNT = 999

        /** Null unless [message] is a delegation completion row. */
        fun from(message: ChatMessage): HermesDelegationCompletion? {
            if (message.displayKind != DISPLAY_KIND) return null
            val metadata = message.displayMetadata ?: emptyMap()
            val tasks = boundedCount(metadata["task_count"], fallback = 1)
            val failed = min(tasks, boundedCount(metadata["failed_count"], fallback = 0))
            val completed = min(max(0, tasks - failed), boundedCount(metadata["completed_count"], fallback = max(0, tasks - failed)))
            val duration = number(metadata["duration_seconds"])?.takeIf { it.isFinite() && it >= 0 }
            return HermesDelegationCompletion(
                id = message.id, report = message.content ?: "", delegationID = string(metadata["delegation_id"]),
                taskCount = tasks, completedCount = completed, failedCount = failed, durationSeconds = duration,
            )
        }

        private fun boundedCount(value: JSONValue?, fallback: Int): Int {
            val number = number(value) ?: return fallback
            if (!number.isFinite() || number != Math.floor(number)) return fallback
            if (number <= 0) return 0
            if (number >= MAXIMUM_DISPLAY_COUNT.toDouble()) return MAXIMUM_DISPLAY_COUNT
            return number.toInt()
        }

        private fun string(value: JSONValue?): String? = value?.string?.trim()?.takeIf { it.isNotEmpty() }

        private fun number(value: JSONValue?): Double? = value?.number
    }
}
