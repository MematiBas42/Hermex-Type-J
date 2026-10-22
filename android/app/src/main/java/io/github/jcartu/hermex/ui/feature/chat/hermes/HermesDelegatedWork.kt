package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.HermesDelegatedTail
import io.github.jcartu.hermex.model.hermes.HermesDelegatedWorker
import io.github.jcartu.hermex.net.hermes.HermesCall
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Port of `Features/Chat/HermesDelegatedWork.swift`: live delegated-worker inspection for one
 * conversation attach. It refreshes only on connect, explicit refresh, or coalesced lifecycle
 * events; tails are opt-in and destructive interruption always re-lists the worker identity.
 */
class HermesDelegatedWork(
    private val wire: BotTransport,
    private val scope: CoroutineScope,
) {
    /** Port of `HermesDelegatedWork.Availability` in `Features/Chat/HermesDelegatedWork.swift`. */
    enum class Availability { UNKNOWN, SUPPORTED, UNSUPPORTED }

    /** Port of `HermesDelegatedWork.Context` in `Features/Chat/HermesDelegatedWork.swift`. */
    data class Context(
        val connectionID: String,
        val runtime: String,
        val generation: Int,
    )

    /** Port of `HermesDelegatedWork.InterruptAction` in `Features/Chat/HermesDelegatedWork.swift`. */
    data class InterruptAction(
        val context: Context,
        val worker: HermesDelegatedWorker.Identity,
        val id: String = UUID.randomUUID().toString(),
    )

    companion object {
        const val MAXIMUM_WORKERS = 64
        const val MAXIMUM_TAIL_BYTES = 16 * 1024
        private const val EVENT_COALESCE_MILLIS = 200L

        internal fun hierarchyOrder(workers: List<HermesDelegatedWorker>): List<HermesDelegatedWorker> {
            val ids = workers.mapTo(HashSet(workers.size)) { it.subagentID }
            val children = HashMap<String, MutableList<HermesDelegatedWorker>>()
            for (worker in workers) {
                val parent = worker.parentID ?: continue
                children.getOrPut(parent) { ArrayList() }.add(worker)
            }
            val result = ArrayList<HermesDelegatedWorker>(workers.size)
            val visited = HashSet<HermesDelegatedWorker.Identity>(workers.size)

            fun append(worker: HermesDelegatedWorker) {
                if (!visited.add(worker.identity)) return
                result.add(worker)
                val directChildren = children[worker.subagentID] ?: return
                for (child in directChildren) append(child)
            }

            for (worker in workers) {
                if (worker.parentID == null || worker.parentID !in ids) append(worker)
            }
            for (worker in workers) append(worker)
            return result
        }

        internal fun boundedTail(text: String): Pair<String, Boolean> {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAXIMUM_TAIL_BYTES) return text to false
            val suffix = bytes.copyOfRange(bytes.size - MAXIMUM_TAIL_BYTES, bytes.size)
            return suffix.toString(Charsets.UTF_8) to true
        }

        private fun isUnsupported(error: Throwable): Boolean =
            error == BotFailure.Unsupported ||
                (error is BotFailure.Rejected && error.code == -32601)

        private fun message(error: Throwable): String = when {
            error is BotFailure.Rejected && error.code == 4001 ->
                "This Hermes connection no longer owns these workers. Reconnect to inspect them."
            isUnsupported(error) ->
                "This Hermes host does not support delegated-work inspection."
            error == BotFailure.Transport ->
                "The result is unknown. Reconnect and refresh before acting again."
            !error.message.isNullOrBlank() -> error.message.orEmpty()
            else -> "Delegated work could not be refreshed."
        }
    }

    var availability: Availability = Availability.UNKNOWN
        private set
    var workers: List<HermesDelegatedWorker> = emptyList()
        private set
    var omittedWorkerCount: Int = 0
        private set

    /** All active workers, including rows omitted by the 64-row presentation cap. */
    var activeCount: Int = 0
        private set
    var isRefreshing: Boolean = false
        private set
    var loadingTail: HermesDelegatedWorker.Identity? = null
        private set
    var tail: HermesDelegatedTail? = null
        private set
    var interruptingWorker: HermesDelegatedWorker.Identity? = null
        private set
    var interruptedWorker: HermesDelegatedWorker.Identity? = null
        private set
    var errorMessage: String? = null
        private set

    /** Fired after a roster install may have changed [activeCount]. */
    var onWorkersChanged: (() -> Unit)? = null

    /** Called after any externally visible value changes. */
    var onChange: () -> Unit = {}

    private var context: Context? = null
    private var listRequestID = 0L
    private var tailRequestID = 0L
    private var eventRefreshJob: Job? = null
    private var eventRefreshGeneration = 0L
    private var eventRefreshPending = false

    /** Drops the previous generation, adopts [next], and performs its one connect-time list. */
    suspend fun connect(next: Context) {
        disconnect()
        context = next
        refresh()
    }

    /** Retires every request and clears all attach-owned presentation state. */
    fun disconnect() {
        context = null
        listRequestID += 1
        tailRequestID += 1
        eventRefreshGeneration += 1
        eventRefreshJob?.cancel()
        eventRefreshJob = null
        eventRefreshPending = false
        availability = Availability.UNKNOWN
        workers = emptyList()
        omittedWorkerCount = 0
        activeCount = 0
        isRefreshing = false
        loadingTail = null
        tail = null
        interruptingWorker = null
        interruptedWorker = null
        errorMessage = null
        onChange()
    }

    /** Explicitly reads the current generation's roster once; it never schedules polling. */
    suspend fun refresh() {
        val owner = context ?: return
        listRequestID += 1
        val requestID = listRequestID
        isRefreshing = true
        onChange()
        try {
            val next = fetchWorkers(owner)
            currentCoroutineContext().ensureActive()
            if (!owns(owner) || listRequestID != requestID) return
            install(next)
            availability = Availability.SUPPORTED
            errorMessage = null
            isRefreshing = false
            onChange()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!owns(owner) || listRequestID != requestID) return
            isRefreshing = false
            if (isUnsupported(error)) {
                availability = Availability.UNSUPPORTED
                workers = emptyList()
                omittedWorkerCount = 0
                activeCount = 0
                tail = null
                errorMessage = null
            } else if (error != BotFailure.Stale) {
                errorMessage = message(error)
            }
            onChange()
        }
    }

    /**
     * Coalesces finite subagent lifecycle/tool events. Token and reasoning deltas must not call
     * this method, and no timer remains after pending lifecycle refreshes are drained.
     */
    fun noteSubagentEvent() {
        if (context == null) return
        eventRefreshPending = true
        if (eventRefreshJob != null) return
        eventRefreshGeneration += 1
        val refreshGeneration = eventRefreshGeneration
        eventRefreshJob = scope.launch {
            try {
                delay(EVENT_COALESCE_MILLIS)
                while (eventRefreshPending) {
                    eventRefreshPending = false
                    refresh()
                    if (eventRefreshPending) delay(EVENT_COALESCE_MILLIS)
                }
            } finally {
                if (eventRefreshGeneration == refreshGeneration) eventRefreshJob = null
            }
        }
    }

    /** Loads at most the newest 16 KiB of one still-current worker's output. */
    suspend fun loadTail(worker: HermesDelegatedWorker) {
        val owner = context ?: return
        if (current(worker.identity) == null) return
        tailRequestID += 1
        val requestID = tailRequestID
        loadingTail = worker.identity
        tail = null
        errorMessage = null
        onChange()
        try {
            val reply = wire.call(
                HermesCall.SubagentTail(owner.runtime, worker.subagentID),
            ) {
                if (!owns(owner) || current(worker.identity) == null) throw BotFailure.Stale
            }
            currentCoroutineContext().ensureActive()
            if (!owns(owner) || tailRequestID != requestID || current(worker.identity) == null) return
            if (reply["subagent_id"].text != worker.subagentID) throw BotFailure.Unsupported
            val available = reply["available"].flag ?: throw BotFailure.Unsupported
            val text = reply["text"].text ?: throw BotFailure.Unsupported
            val hostTruncated = reply["truncated"].flag ?: throw BotFailure.Unsupported
            val (bounded, wasTruncated) = boundedTail(text)
            tail = HermesDelegatedTail(
                worker = worker.identity,
                available = available,
                text = if (available) bounded else "",
                truncated = available && (hostTruncated || wasTruncated),
            )
            loadingTail = null
            onChange()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!owns(owner) || tailRequestID != requestID) return
            loadingTail = null
            if (error != BotFailure.Stale) errorMessage = message(error)
            onChange()
        }
    }

    /** Retires any tail read and closes the expanded output. */
    fun hideTail() {
        if (loadingTail == null && tail == null) return
        tailRequestID += 1
        loadingTail = null
        tail = null
        onChange()
    }

    /** Captures the exact generation-bearing worker identity before confirmation. */
    fun prepareInterrupt(worker: HermesDelegatedWorker): InterruptAction? {
        val owner = context ?: return null
        if (!worker.canInterrupt || interruptingWorker != null || current(worker.identity) == null) return null
        return InterruptAction(owner, worker.identity)
    }

    /**
     * Re-lists immediately before the irreversible call. A completed worker or reused id fails
     * closed, and duplicate confirmation taps cannot dispatch a second interrupt.
     */
    suspend fun interrupt(action: InterruptAction) {
        if (!owns(action.context) || current(action.worker) == null || interruptingWorker != null) return
        interruptingWorker = action.worker
        interruptedWorker = null
        errorMessage = null
        onChange()
        try {
            val fresh = fetchWorkers(action.context)
            currentCoroutineContext().ensureActive()
            if (!owns(action.context)) throw BotFailure.Stale
            install(fresh)
            if (current(action.worker) == null) {
                interruptingWorker = null
                errorMessage = "This worker is no longer active."
                onChange()
                return
            }
            val reply = wire.call(
                HermesCall.SubagentInterrupt(action.context.runtime, action.worker.subagentID),
            ) {
                if (
                    !owns(action.context) || interruptingWorker != action.worker ||
                    current(action.worker) == null
                ) throw BotFailure.Stale
            }
            currentCoroutineContext().ensureActive()
            if (!owns(action.context) || interruptingWorker != action.worker) return
            if (reply["subagent_id"].text != action.worker.subagentID) throw BotFailure.Unsupported
            val found = reply["found"].flag ?: throw BotFailure.Unsupported
            interruptingWorker = null
            if (found) {
                interruptedWorker = action.worker
                onChange()
            } else {
                refresh()
                if (!owns(action.context)) return
                errorMessage = "This worker is no longer active."
                onChange()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!owns(action.context) || interruptingWorker != action.worker) return
            interruptingWorker = null
            if (error != BotFailure.Stale) errorMessage = message(error)
            onChange()
        }
    }

    private suspend fun fetchWorkers(owner: Context): List<HermesDelegatedWorker> {
        if (!owns(owner)) throw BotFailure.Stale
        val reply = wire.call(HermesCall.SubagentList(owner.runtime)) {
            if (!owns(owner)) throw BotFailure.Stale
        }
        currentCoroutineContext().ensureActive()
        if (!owns(owner)) throw BotFailure.Stale
        val rows = reply["subagents"].list ?: throw BotFailure.Unsupported
        return rows.mapNotNull(HermesDelegatedWorker::from)
    }

    private fun install(received: List<HermesDelegatedWorker>) {
        val bounded = received.take(MAXIMUM_WORKERS)
        workers = hierarchyOrder(bounded)
        omittedWorkerCount = max(0, received.size - bounded.size)
        activeCount = workers.size + omittedWorkerCount
        onWorkersChanged?.invoke()
        tail?.takeIf { current(it.worker) == null }?.let { tail = null }
        loadingTail?.takeIf { current(it) == null }?.let { loadingTail = null }
        interruptedWorker?.takeIf { current(it) == null }?.let { interruptedWorker = null }
        onChange()
    }

    private fun current(identity: HermesDelegatedWorker.Identity): HermesDelegatedWorker? =
        workers.firstOrNull { it.identity == identity }

    private fun owns(owner: Context): Boolean = context == owner
}
