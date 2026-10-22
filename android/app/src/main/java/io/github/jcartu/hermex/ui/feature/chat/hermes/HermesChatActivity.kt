package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesPlan
import io.github.jcartu.hermex.model.hermes.HermesPlanState
import io.github.jcartu.hermex.net.hermes.HermesTurnOutcome
import io.github.jcartu.hermex.ui.feature.chat.TranscriptMessage
import kotlinx.coroutines.CoroutineScope

/**
 * Port of `Features/Chat/HermesChatActivity.swift`: a Hermes chat's revision-monotonic plan,
 * retained turn failure, live completion notice, safe retry target, and delegated workers.
 * The coordinator owns this controller and publishes [onChange] into its immutable UI state.
 */
class HermesChatActivity(
    wire: BotTransport,
    scope: CoroutineScope,
) {
    /** Port of `HermesChatActivity.SettledPlan` in `Features/Chat/HermesChatActivity.swift`. */
    data class SettledPlan(
        val plan: HermesPlan,
        val rowID: Int?,
        val followsLastPrompt: Boolean,
    ) {
        /**
         * Port of `SettledPlan.afterRenderID(in:)`: never place a plan below a prompt from a
         * different turn when its durable prompt row disappeared or is not known yet.
         */
        fun afterRenderID(rows: List<TranscriptMessage>): String? {
            rowID?.let { id ->
                rows.lastOrNull { it.message.rowID == id }?.let { return it.renderID }
            }
            if (!followsLastPrompt) return null
            val prompt = rows.lastOrNull {
                it.message.role == "user" && !it.message.isSteerMessage
            } ?: return null
            if (rowID != null && prompt.message.rowID != null) return null
            return prompt.renderID
        }
    }

    /** Port of `HermesChatActivity.RetryTarget` in `Features/Chat/HermesChatActivity.swift`. */
    data class RetryTarget(
        val rowID: Int,
        val text: String,
        val showsPrompt: Boolean,
    )

    /** Port of private `PlanTurn` in `Features/Chat/HermesChatActivity.swift`. */
    private data class PlanTurn(
        val turn: Int,
        val rowID: Int?,
        val showsPrompt: Boolean,
    )

    /** Port of `HermesChatActivity.delegatedWork` from the Swift source. */
    val delegatedWork = HermesDelegatedWork(wire = wire, scope = scope)

    /** Called after any externally visible value changes. */
    var onChange: () -> Unit = {}

    private var planState = HermesPlanState()
    private var planTurn: PlanTurn? = null
    private var planRuntimeIsNew = false
    private var turn = 0
    private var isTurnRunning = false
    private var turnShowsPrompt = false
    private var turnPromptRowID: Int? = null

    /** The host-retained failure for the newest completed turn. */
    var failure: HermesTurnOutcome? = null
        private set

    /** The live/replayed completion-only warning and billing link. */
    var notice: HermesTurnOutcome? = null
        private set

    private var failedPrompt: String? = null
    private var failedPromptRowID: Int? = null
    private var failedTurnStartedAt: Double? = null
    private var uncuttableRowID: Int? = null

    init {
        delegatedWork.onChange = { onChange() }
    }

    /** The newest plan the host holds, wherever it is rendered. */
    val plan: HermesPlan? get() = planState.plan

    /** The unfinished plan pinned above the composer while its owning turn runs. */
    val pinnedPlan: HermesPlan?
        get() {
            val current = planState.plan ?: return null
            if (!isTurnRunning || planTurn?.turn != turn || current.isFinished) return null
            return current
        }

    /** The plan settled into the transcript once it is no longer pinned. */
    val settledPlan: SettledPlan?
        get() {
            if (pinnedPlan != null) return null
            val current = planState.plan ?: return null
            val owner = planTurn ?: return null
            val followsLastPrompt = owner.turn == turn && owner.showsPrompt
            if (!followsLastPrompt && owner.rowID == null) return null
            return SettledPlan(current, owner.rowID, followsLastPrompt)
        }

    /**
     * Retry is offered only for a recoverable failure whose exact raw prompt and durable,
     * still-cuttable row are known.
     */
    val retryTarget: RetryTarget?
        get() {
            if (failure?.offersRetry != true) return null
            val text = failedPrompt ?: return null
            val rowID = failedPromptRowID ?: return null
            if (rowID == uncuttableRowID) return null
            return RetryTarget(
                rowID = rowID,
                text = text,
                showsPrompt = turnShowsPrompt && (turnPromptRowID ?: rowID) == rowID,
            )
        }

    /** Port of `turnDidStart(showsPrompt:)`. */
    fun turnDidStart(showsPrompt: Boolean) {
        turn += 1
        isTurnRunning = true
        turnShowsPrompt = showsPrompt
        turnPromptRowID = null
        failure = null
        notice = null
        failedPrompt = null
        failedPromptRowID = null
        failedTurnStartedAt = null
        onChange()
    }

    /** Port of `turnDidEnd()`: an unfinished open plan settles into its turn. */
    fun turnDidEnd() {
        if (!isTurnRunning) return
        isTurnRunning = false
        onChange()
    }

    /** Applies and places one running turn's `todo.updated` payload. */
    fun receivePlan(json: BotJSON) {
        val revision = json["revision"].integer?.coerceAtLeast(0)
        if (!applyRevision(json) && revision != planState.revision) return
        placePlan(inTurn = true)
        onChange()
    }

    /**
     * Port of `turnDidComplete(_:)`: records the saved prompt row, failure, and live-only
     * notice without inventing a retry prompt before a snapshot supplies one.
     */
    fun turnDidComplete(payload: BotJSON) {
        turnPromptRowID = payload["persisted_turn"]["user_row_id"].integer
        val promptRowID = turnPromptRowID
        if (promptRowID != null && planTurn?.turn == turn) {
            planTurn = planTurn?.copy(rowID = promptRowID)
        }
        failure = if (payload["status"].text == "error") {
            HermesTurnOutcome.fromInflight(payload)
        } else {
            null
        }
        failedPrompt = null
        failedPromptRowID = null
        failedTurnStartedAt = null
        notice = HermesTurnOutcome.fromComplete(payload)
        onChange()
    }

    /**
     * Port of `readSnapshot(_:promptRowID:followsTurn:followedPromptRowID:)`. The snapshot's
     * plan is placed only when it can still be proven to belong to the turn this chat followed.
     */
    fun readSnapshot(
        snapshot: BotJSON,
        promptRowID: Int?,
        followsTurn: Boolean,
        followedPromptRowID: Int? = null,
    ) {
        val inflight = snapshot["inflight"]
        val next = HermesTurnOutcome.fromInflight(inflight)
        failure = next
        val startedAt = if (next == null) null else inflight["started_at"].number
        failedPrompt = if (next == null) {
            null
        } else {
            inflight["user"].text?.takeIf { it.isNotBlank() }
        }
        failedPromptRowID = if (next == null) {
            null
        } else {
            promptRowID ?: failedPromptRowID.takeIf {
                startedAt != null && startedAt == failedTurnStartedAt
            }
        }
        failedTurnStartedAt = startedAt

        if (applyRevision(snapshot["todo_state"])) {
            placePlan(
                inTurn = followsTurn && !planRuntimeIsNew,
                promptRowID = followedPromptRowID,
            )
        } else if (followsTurn && !planRuntimeIsNew && planTurn?.turn == turn) {
            placePlan(inTurn = true, promptRowID = followedPromptRowID)
        }
        planRuntimeIsNew = false
        onChange()
    }

    /** `/undo` removes the newest exchange and any plan that exchange revised. */
    fun newestTurnWasUndone() {
        if (planTurn?.turn != turn) return
        planTurn = null
        onChange()
    }

    /** A new runtime restarts plan revisions, so the old runtime's plan cannot survive. */
    fun dropPlan() {
        planState = HermesPlanState()
        planTurn = null
        planRuntimeIsNew = true
        onChange()
    }

    /** Lost frames can hide a newer turn, so completion-only notice data is discarded. */
    fun dropNotice() {
        if (notice == null) return
        notice = null
        onChange()
    }

    /** The host refused this durable cut (4018), so further taps cannot repeat it. */
    fun refuseRetry(rowID: Int) {
        if (uncuttableRowID == rowID) return
        uncuttableRowID = rowID
        onChange()
    }

    private fun applyRevision(json: BotJSON): Boolean {
        val next = planState.applying(json) ?: return false
        planState = next
        return true
    }

    private fun placePlan(inTurn: Boolean, promptRowID: Int? = null) {
        if (planState.plan == null || !inTurn) {
            planTurn = null
            return
        }
        val owner = planTurn
        planTurn = when {
            owner?.turn != turn -> PlanTurn(turn, promptRowID, turnShowsPrompt)
            promptRowID != null && owner.rowID != promptRowID -> owner.copy(rowID = promptRowID)
            else -> owner
        }
    }
}
