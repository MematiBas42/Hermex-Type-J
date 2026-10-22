package io.github.jcartu.hermex.ui.feature.settings

import io.github.jcartu.hermex.model.hermes.BotConnectionAdvice
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.HermesUpdateCheck
import io.github.jcartu.hermex.model.hermes.HermesUpdateReceipt
import io.github.jcartu.hermex.model.hermes.HermesUpdateStart
import io.github.jcartu.hermex.net.hermes.HermesAddress
import io.github.jcartu.hermex.net.hermes.HermesRestartWait
import io.github.jcartu.hermex.net.hermes.HermesUpdateClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Port of `HermesUpdateModel.Check/Run` (`Features/Settings/HermesUpdateMachine.swift`). */
data class HermesUpdateUiState(
    val check: HermesUpdateCheck? = null,
    val checkError: String? = null,
    val checkedAt: Long? = null,
    val isChecking: Boolean = false,
    val isWorking: Boolean = false,
    val run: Run? = null,
) {
    sealed interface Run {
        data object Starting : Run
        data class Refused(val message: String?, val command: String?) : Run
        data class CouldNotStart(val message: String) : Run
        data class Following(val machine: HermesUpdateMachine) : Run
    }
}

/**
 * Port of `HermesUpdateModel`: retained per configured server in AppContainer, not scoped to
 * the Settings view. POST is sent once, ambiguous delivery is reconciled with status, and
 * HermesRestartWait follows the reducer through dashboard restart and its final receipt.
 */
class HermesUpdateModel(
    private val server: String,
    private val scope: CoroutineScope,
    private val makeClient: () -> HermesUpdateClient?,
    private val onUpdated: (String) -> Unit,
    private val reconnectGateway: () -> Unit,
    private val cadenceMillis: Long = 3_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _state = MutableStateFlow(HermesUpdateUiState())
    val state = _state.asStateFlow()
    private var task: Job? = null
    private var generation = 0

    suspend fun appear() {
        val following = _state.value.run as? HermesUpdateUiState.Run.Following
        if (following != null) {
            if (task == null && !following.machine.isSettled) follow(readFirst = true)
            return
        }
        if (_state.value.run != null || task != null || _state.value.isChecking) return
        val client = makeClient() ?: return
        val status = try { client.status() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (status?.running == true && _state.value.run == null && task == null) {
            _state.update { it.copy(run = HermesUpdateUiState.Run.Following(HermesUpdateMachine.started(status.receipt, status.pid, now()))) }
            follow(readFirst = false)
        } else if (_state.value.run == null) refresh(force = false, client)
    }

    fun leaveSettings() {
        if (task != null) return
        val run = _state.value.run
        val terminal = (run as? HermesUpdateUiState.Run.Following)?.machine?.state
        if (run is HermesUpdateUiState.Run.Refused || run is HermesUpdateUiState.Run.CouldNotStart ||
            terminal is HermesUpdateMachine.State.Done || terminal is HermesUpdateMachine.State.Partial || terminal is HermesUpdateMachine.State.Failed) {
            _state.update { it.copy(run = null) }
        }
    }

    fun checkNow() {
        if (task != null || _state.value.isChecking) return
        val machine = (_state.value.run as? HermesUpdateUiState.Run.Following)?.machine
        if (machine != null && !machine.isStateSettled) return
        val client = makeClient() ?: return
        _state.update { it.copy(run = null) }
        scope.launch(Dispatchers.Main.immediate) { refresh(true, client) }
    }

    fun apply() {
        if (task != null) return
        val machine = (_state.value.run as? HermesUpdateUiState.Run.Following)?.machine
        if (machine != null && !machine.isStateSettled) return
        val client = makeClient()
        if (client == null) {
            _state.update { it.copy(run = HermesUpdateUiState.Run.CouldNotStart("Connect to this Hermes host first.")) }
            return
        }
        _state.update { it.copy(run = HermesUpdateUiState.Run.Starting, isWorking = true) }
        launchRun {
            try {
                val baseline = try { client.status().receipt } catch (error: BotFailure.Rejected) {
                    if (error.code != 404) throw error
                    client.receipt()
                }
                val started = start(client, baseline)
                when (started) {
                    is HermesUpdateStart.Started -> {
                        _state.update { it.copy(run = HermesUpdateUiState.Run.Following(HermesUpdateMachine.started(baseline, started.pid, now()))) }
                        watch(client, readFirst = false)
                    }
                    is HermesUpdateStart.Refused -> _state.update { it.copy(run = HermesUpdateUiState.Run.Refused(started.message, started.hostCommand)) }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _state.update { it.copy(run = if (error === BotFailure.Stale) null else HermesUpdateUiState.Run.CouldNotStart(message(error))) }
            }
        }
    }

    fun checkAgain() {
        if (task != null) return
        val old = (_state.value.run as? HermesUpdateUiState.Run.Following)?.machine ?: return
        val next = old.checkAgain()
        if (next.isSettled) return
        _state.update { it.copy(run = HermesUpdateUiState.Run.Following(next)) }
        follow(readFirst = true)
    }

    private suspend fun refresh(force: Boolean, client: HermesUpdateClient) {
        val id = ++generation
        _state.update { it.copy(isChecking = true) }
        try {
            val check = client.check(force)
            if (id == generation) _state.update { it.copy(check = check, checkError = null, checkedAt = System.currentTimeMillis()) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            if (id == generation && error !== BotFailure.Stale) _state.update {
                if (error == BotFailure.Rejected(404)) it.copy(check = null, checkError = null)
                else it.copy(checkError = message(error))
            }
        } finally { if (id == generation) _state.update { it.copy(isChecking = false) } }
    }

    private suspend fun start(client: HermesUpdateClient, baseline: HermesUpdateReceipt?): HermesUpdateStart {
        try { return client.start() } catch (error: Exception) {
            if (error is CancellationException || error === BotFailure.Stale) throw error
            val status = try { client.status() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            if (status != null && (status.running || status.receipt?.let { it != baseline } == true)) return HermesUpdateStart.Started(status.pid)
            throw error
        }
    }

    private fun follow(readFirst: Boolean) {
        val client = makeClient() ?: return
        _state.update { it.copy(isWorking = true) }
        launchRun { watch(client, readFirst) }
    }

    private fun launchRun(block: suspend () -> Unit) {
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try { block() } finally { task = null; _state.update { it.copy(isWorking = false) } }
        }
        task = job
        job.start()
    }

    private suspend fun watch(client: HermesUpdateClient, readFirst: Boolean) {
        val reads = ((HermesUpdateMachine.CEILING + HermesUpdateMachine.SILENCE_LIMIT) / cadenceMillis.coerceAtLeast(1_000)).toInt() + 1
        val delays = (if (readFirst) listOf(0L) else emptyList()) + List(reads) { cadenceMillis }
        var retired = false
        HermesRestartWait.lastAnswer(delays, sleep, probe = {
            currentCoroutineContext().ensureActive()
            val machine = (_state.value.run as? HermesUpdateUiState.Run.Following)?.machine
            val read = machine?.nextRead
            if (read == null) true else {
                val event = event(read, client)
                if (event == null) { retired = true; true } else {
                    val next = machine.handle(event, now())
                    _state.update { it.copy(run = HermesUpdateUiState.Run.Following(next)) }
                    next.isSettled
                }
            }
        }, isFinal = { it })
        var machine = (_state.value.run as? HermesUpdateUiState.Run.Following)?.machine ?: return
        if (!machine.isSettled && !retired && currentCoroutineContext().isActive) {
            machine = machine.expire()
            _state.update { it.copy(run = HermesUpdateUiState.Run.Following(machine)) }
        }
        val done = machine.state as? HermesUpdateMachine.State.Done
        if (done != null) { done.version?.let(onUpdated); reconnectGateway() }
    }

    private suspend fun event(read: HermesUpdateMachine.Read, client: HermesUpdateClient): HermesUpdateMachine.Event? = try {
        when (read) {
            HermesUpdateMachine.Read.STATUS -> HermesUpdateMachine.Event.Status(client.status())
            HermesUpdateMachine.Read.RECEIPT -> HermesUpdateMachine.Event.Receipt(client.receipt())
            HermesUpdateMachine.Read.HEALTH -> HermesUpdateMachine.Event.Health(client.health())
        }
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        when {
            error === BotFailure.Stale -> null
            error == BotFailure.Rejected(404) && read == HermesUpdateMachine.Read.STATUS -> HermesUpdateMachine.Event.StatusMissing
            else -> HermesUpdateMachine.Event.Unreachable
        }
    }

    private fun message(error: Throwable): String {
        if (error is BotFailure.Rejected && error.code in 404..501 && error.code != 429)
            return "This Hermes host refused the step (HTTP ${error.code}). Check the host’s logs, then try again."
        return HermesAddress.url(server)?.let { BotConnectionAdvice.message(error, it) } ?: error.message.orEmpty()
    }
}
