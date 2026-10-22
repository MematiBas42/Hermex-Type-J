package io.github.jcartu.hermex.ui.feature.settings

import io.github.jcartu.hermex.model.hermes.HermesUpdateReceipt
import io.github.jcartu.hermex.model.hermes.HermesUpdateStatus

/**
 * Port of `Features/Settings/HermesUpdateMachine.swift` (#1075/#1083). A new receipt outranks
 * exit status; old receipts and another process's exit cannot finish this run. Success waits
 * for public health on the installed release. Only watched time counts toward the limits.
 */
data class HermesUpdateMachine(
    val baseline: HermesUpdateReceipt?,
    val pid: Int?,
    val state: State = State.Applying,
    val isRechecking: Boolean = false,
    private val succeeded: HermesUpdateReceipt? = null,
    private val exitedCleanly: Boolean = false,
    private val statusMissing: Boolean = false,
    private val lines: List<String> = emptyList(),
    private val lastAnswer: Long = 0,
    private val waitStarted: Long = 0,
    private val lastEvent: Long = 0,
    private val finishedAt: Long? = null,
) {
    sealed interface State {
        data object Applying : State
        data object Recovering : State
        data class Done(val version: String?) : State
        data class Partial(val summary: String?) : State
        data class Failed(val summary: String?) : State
        data class NeedsDashboardRestart(val running: String?) : State
        data object StillRunning : State
    }
    enum class Read { STATUS, RECEIPT, HEALTH }
    sealed interface Event {
        data class Status(val value: HermesUpdateStatus) : Event
        data object StatusMissing : Event
        data class Receipt(val value: HermesUpdateReceipt?) : Event
        data class Health(val version: String?) : Event
        data object Unreachable : Event
    }
    val isStateSettled: Boolean get() = state != State.Applying && state != State.Recovering
    val isSettled: Boolean get() = isStateSettled && !isRechecking
    private val isFinished: Boolean get() = succeeded != null || exitedCleanly
    val nextRead: Read? get() = when {
        isSettled -> null
        isFinished -> Read.HEALTH
        statusMissing -> Read.RECEIPT
        else -> Read.STATUS
    }

    fun handle(event: Event, now: Long): HermesUpdateMachine {
        if (isSettled) return this
        val unwatched = (now - lastEvent - LONGEST_READ).coerceAtLeast(0)
        var next = copy(lastEvent = now, lastAnswer = lastAnswer + unwatched,
            waitStarted = waitStarted + unwatched, finishedAt = finishedAt?.plus(unwatched))
        if (next.isRechecking) {
            next = next.copy(isRechecking = false)
            if (event == Event.Unreachable) return next
            if (event is Event.Health && !next.isInstalled(event.version)) return next.copy(state = State.NeedsDashboardRestart(event.version))
            next = next.copy(state = State.Applying, waitStarted = now)
        }
        if (event == Event.Unreachable) {
            next = next.copy(state = if (now - next.lastAnswer >= SILENCE_LIMIT) State.NeedsDashboardRestart(null) else State.Recovering)
        } else {
            next = next.copy(lastAnswer = now, state = if (next.state == State.Recovering) State.Applying else next.state)
            next = when (event) {
                Event.StatusMissing -> next.copy(statusMissing = true)
                is Event.Status -> {
                    var updated = next.copy(lines = event.value.lines)
                    val receipt = event.value.receipt
                    if (receipt != null && updated.isThisRun(receipt)) updated = updated.finish(receipt, now)
                    else if (!event.value.running && event.value.exitCode != null && pid != null && event.value.pid == pid) {
                        updated = if (event.value.exitCode == 0) updated.copy(exitedCleanly = true, finishedAt = now)
                        else updated.copy(state = State.Failed(updated.summary()))
                    }
                    updated
                }
                is Event.Receipt -> if (event.value != null && next.isThisRun(event.value)) next.finish(event.value, now) else next
                is Event.Health -> when {
                    next.isInstalled(event.version) -> next.copy(state = State.Done(event.version ?: next.succeeded?.postVersion))
                    next.finishedAt != null && now - next.finishedAt >= SILENCE_LIMIT -> next.copy(state = State.NeedsDashboardRestart(event.version))
                    else -> next
                }
                Event.Unreachable -> next
            }
        }
        if (!next.isStateSettled && !next.isFinished && now - next.waitStarted >= CEILING) next = next.expire()
        return next
    }

    fun expire(): HermesUpdateMachine = if (isStateSettled) this else copy(
        state = if (state == State.Recovering || isFinished) State.NeedsDashboardRestart(null) else State.StillRunning)
    fun checkAgain(): HermesUpdateMachine = if (state is State.NeedsDashboardRestart || state == State.StillRunning) copy(isRechecking = true) else this

    private fun finish(receipt: HermesUpdateReceipt, now: Long): HermesUpdateMachine = when (receipt.outcome) {
        null, HermesUpdateReceipt.Outcome.Running -> this
        HermesUpdateReceipt.Outcome.Success -> copy(succeeded = receipt, finishedAt = finishedAt ?: now)
        HermesUpdateReceipt.Outcome.Partial -> copy(state = State.Partial(summary()))
        else -> copy(state = State.Failed(summary()))
    }
    private fun isThisRun(receipt: HermesUpdateReceipt): Boolean {
        val before = baseline ?: return true
        return if (receipt.startedAt != null && before.startedAt != null) receipt.startedAt != before.startedAt else receipt != before
    }
    private fun isInstalled(version: String?): Boolean = version == null || succeeded?.postVersion == null || version == succeeded.postVersion
    private fun summary(): String? {
        val markers = setOf('\u2717', '\u26A0', '\uFE0F')
        val shown = lines.map { it.trim() }.filter { it.isNotEmpty() && !(it.startsWith("===") && it.endsWith("===")) }
        val line = shown.lastOrNull { it.first() in markers } ?: shown.lastOrNull() ?: return null
        return line.dropWhile { it in markers || it.isWhitespace() }.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val SILENCE_LIMIT = 120_000L
        const val CEILING = 600_000L
        const val LONGEST_READ = 60_000L
        fun started(baseline: HermesUpdateReceipt?, pid: Int?, now: Long): HermesUpdateMachine =
            HermesUpdateMachine(baseline, pid, lastAnswer = now, waitStarted = now, lastEvent = now)
    }
}
