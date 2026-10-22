package io.github.jcartu.hermex.ui.feature.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBoardSnapshot
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardActionRequest
import io.github.jcartu.hermex.model.KanbanCardDetailRequest
import io.github.jcartu.hermex.model.KanbanCardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanContractException
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanDependencyMutationRequest
import io.github.jcartu.hermex.model.KanbanDispatchRequest
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanEventsEnvelope
import io.github.jcartu.hermex.model.KanbanEventsRequest
import io.github.jcartu.hermex.model.KanbanStats
import io.github.jcartu.hermex.model.KanbanValidators
import io.github.jcartu.hermex.model.KanbanWorkerLogRequest
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.hermes.KanbanUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Timing from `docs/agents/kanban.md`: 1/2/5/10/30 s reconnects, then 30 s polls. */
data class KanbanLiveTiming(
    val coalescingMillis: Long = 300,
    val reconnectDelaysMillis: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
    val fallbackPollingMillis: Long = 30_000,
    val webuiPollingMillis: Long = 5_000,
    val failuresBeforePolling: Int = 3,
)

/** The result of admitting one live-event envelope at a cursor. */
internal sealed interface KanbanLiveAdmission {
    data class Apply(val cursor: KanbanLiveCursor) : KanbanLiveAdmission
    data object Stale : KanbanLiveAdmission
    data object Malformed : KanbanLiveAdmission
}

/**
 * Monotonic live cursor reducer. Fresh event envelopes apply, snapshots replace
 * only by advancing, and stale frames can never regress the resume cursor.
 */
internal data class KanbanLiveCursor(val value: Int = 0) {
    fun apply(envelope: KanbanEventsEnvelope): KanbanLiveAdmission {
        val next = envelope.cursor ?: envelope.latestEventId ?: return KanbanLiveAdmission.Malformed
        val events = envelope.events ?: return KanbanLiveAdmission.Malformed
        if (next < 0 || events.any { event -> event.eventId == null || event.eventId > next }) {
            return KanbanLiveAdmission.Malformed
        }
        if (next <= value) return KanbanLiveAdmission.Stale
        return KanbanLiveAdmission.Apply(KanbanLiveCursor(next))
    }

    fun replacing(snapshotCursor: Int?): KanbanLiveCursor {
        val admitted = snapshotCursor?.takeIf { it >= 0 } ?: return this
        return KanbanLiveCursor(maxOf(value, admitted))
    }
}

/**
 * Shared Kanban state for webui and Hermes servers. Hermes gets its own
 * [HermesKanbanDataSource], while all panes, editors and operational surfaces
 * remain the existing Compose UI.
 *
 * The Hermes live path ports `KanbanFeatureState` from
 * `Features/Kanban/KanbanFeatureState.swift`: one Board-pinned socket, cursor
 * admission, coalesced Board reconciliation, 1/2/5/10/30-second reconnects and
 * polling after the third failure (#1043–#1045).
 */
class KanbanViewModel(
    private val dataSource: KanbanDataSource,
    private val liveTiming: KanbanLiveTiming = KanbanLiveTiming(),
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        KanbanUiState(
            backend = dataSource.backend,
            capabilities = dataSource.capabilities,
        ),
    )
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    private var liveCursor = KanbanLiveCursor()
    private var liveRequested = false
    private var liveGeneration = 0
    private var streamAttempt = 0
    private var streamFailureCount = 0
    private var eventSource: KanbanLiveEventSource? = null
    private var reconnectJob: Job? = null
    private var pollingJob: Job? = null
    private var coalescingJob: Job? = null
    private var reconciliationRequested = false

    fun load() = viewModelScope.launch {
        loadInitial(preferredBoard = _uiState.value.selectedBoardSlug)
    }

    private suspend fun loadInitial(preferredBoard: String? = null) {
        suspendLiveTransport()
        liveCursor = KanbanLiveCursor()
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                isUnavailable = false,
                isOffline = false,
                latestEventId = 0,
                snapshotEventId = 0,
            )
        }
        try {
            // The compatibility handshake is intentionally ordered: config,
            // Boards, then the selected Board snapshot.
            val configuration = dataSource.kanbanConfiguration()
            val boardsResponse = dataSource.kanbanBoards()
            val boards = boardsResponse.boards.orEmpty().filter { !it.slug.isNullOrBlank() }
            val selected = preferredBoard?.takeIf { slug -> boards.any { it.slug == slug } }
                ?: boardsResponse.current?.takeIf { slug -> boards.any { it.slug == slug } }
                ?: boards.firstOrNull()?.slug
            _uiState.update { state ->
                state.copy(
                    configuration = configuration,
                    boards = boards,
                    selectedBoardSlug = selected,
                    columns = if (selected == state.selectedBoardSlug) state.columns else emptyList(),
                    selectedCardId = state.selectedCardId.takeIf { selected == state.selectedBoardSlug },
                    detail = state.detail.takeIf { selected == state.selectedBoardSlug },
                    isLoading = selected != null,
                    readOnly = configuration.readOnly == true || boardsResponse.readOnly == true,
                )
            }
            if (selected == null) {
                _uiState.update { it.copy(isLoading = false) }
            } else {
                loadBoardInternal(selected, preserveSelection = true, usingCursor = false)
                loadSupplementary(selected)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val unavailable = error is KanbanUnavailable
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isUnavailable = unavailable,
                    errorMessage = error.message ?: if (unavailable) {
                        "Kanban isn't available on this Hermes host. Install and enable the Kanban plugin, then restart the dashboard."
                    } else {
                        "Could not load Kanban."
                    },
                )
            }
        } finally {
            if (liveRequested) restartLiveUpdates()
        }
    }

    fun refresh() = viewModelScope.launch {
        val slug = _uiState.value.selectedBoardSlug
        if (slug == null) {
            loadInitial()
            return@launch
        }
        val loaded = loadBoardInternal(slug, preserveSelection = true, usingCursor = false)
        if (loaded) loadSupplementary(slug)
    }

    fun selectBoard(slug: String) = viewModelScope.launch {
        if (slug == _uiState.value.selectedBoardSlug) return@launch
        suspendLiveTransport()
        liveCursor = KanbanLiveCursor()
        _uiState.update {
            it.copy(
                selectedBoardSlug = slug,
                columns = emptyList(),
                selectedCardId = null,
                detail = null,
                selectedCardIds = emptySet(),
                workerLog = null,
                dispatchPreview = null,
                dispatchResult = null,
                latestEventId = 0,
                snapshotEventId = 0,
                stats = null,
                assigneeHistory = null,
            )
        }
        if (loadBoardInternal(slug, preserveSelection = false, usingCursor = false)) {
            loadSupplementary(slug)
        }
        if (liveRequested) restartLiveUpdates()
    }

    fun updateFilters(filters: KanbanFilters) = viewModelScope.launch {
        val admitted = if (dataSource.capabilities.onlyMineFilter) filters else filters.copy(onlyMine = false)
        _uiState.update { it.copy(filters = admitted) }
        _uiState.value.selectedBoardSlug?.let { loadBoardInternal(it, preserveSelection = true, usingCursor = false) }
    }

    fun openTarget(boardSlug: String?, cardId: String?) = viewModelScope.launch {
        val targetBoard = boardSlug?.takeIf { slug -> _uiState.value.boards.any { it.slug == slug } }
            ?: _uiState.value.selectedBoardSlug
            ?: return@launch
        if (targetBoard != _uiState.value.selectedBoardSlug || _uiState.value.columns.isEmpty()) {
            suspendLiveTransport()
            liveCursor = KanbanLiveCursor()
            _uiState.update {
                it.copy(
                    selectedBoardSlug = targetBoard,
                    selectedCardId = null,
                    detail = null,
                    selectedCardIds = emptySet(),
                    latestEventId = 0,
                    snapshotEventId = 0,
                )
            }
            loadBoardInternal(targetBoard, preserveSelection = false, usingCursor = false)
            if (liveRequested) restartLiveUpdates()
        }
        if (!cardId.isNullOrBlank()) {
            _uiState.update { it.copy(selectedCardId = cardId, detail = null) }
            loadDetailInternal(cardId)
        }
    }

    /** Returns true only when a current, compatible Board snapshot was applied or confirmed unchanged. */
    private suspend fun loadBoardInternal(
        slug: String,
        preserveSelection: Boolean,
        usingCursor: Boolean,
    ): Boolean {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val filters = _uiState.value.filters
        val cursorAtRequest = liveCursor.value
        val request = KanbanBoardRequest(
            board = slug,
            tenant = filters.tenant,
            assignee = filters.assignee,
            includeArchived = filters.includeArchived,
            onlyMine = filters.onlyMine && dataSource.capabilities.onlyMineFilter,
            since = if (usingCursor) _uiState.value.snapshotEventId else null,
        )
        return try {
            val snapshot = dataSource.kanbanBoard(request)
            if (_uiState.value.selectedBoardSlug != slug) return false

            val responseCursor = snapshot.latestEventId?.coerceAtLeast(0)
            val becameStaleWhileReading = responseCursor != null &&
                liveCursor.value > cursorAtRequest &&
                responseCursor < liveCursor.value
            if (becameStaleWhileReading) {
                _uiState.update { it.copy(isLoading = false) }
                return true
            }

            liveCursor = liveCursor.replacing(responseCursor)
            if (request.since != null && snapshot.changed == false) {
                _uiState.update {
                    it.copy(
                        latestEventId = liveCursor.value,
                        isLoading = false,
                        isOffline = false,
                    )
                }
                return true
            }

            validateSnapshot(snapshot)
            val columns = snapshot.columns.orEmpty()
            val ids = columns.flatMap { it.cards.orEmpty() }.mapNotNull { it.cardId }.toSet()
            val columnNames = columns.mapNotNull { it.name }
            _uiState.update { state ->
                val selected = state.selectedCardId.takeIf { preserveSelection && it in ids }
                val configured = if (dataSource.backend == KanbanBackend.HERMES || state.configuration?.columns.isNullOrEmpty()) {
                    state.configuration?.copy(columns = columnNames)
                } else {
                    state.configuration
                }
                state.copy(
                    configuration = configured,
                    columns = columns,
                    tenants = snapshot.tenants.orEmpty(),
                    assignees = (snapshot.assignees.orEmpty() + configured?.assignees.orEmpty()).distinct(),
                    latestEventId = liveCursor.value,
                    snapshotEventId = responseCursor ?: state.snapshotEventId,
                    selectedCardId = selected,
                    detail = state.detail.takeIf { selected != null },
                    selectedCardIds = state.selectedCardIds.intersect(ids),
                    isLoading = false,
                    isOffline = false,
                    readOnly = state.readOnly || snapshot.readOnly == true,
                )
            }
            _uiState.value.selectedCardId?.let { loadDetailInternal(it) }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (_uiState.value.selectedBoardSlug != slug) return false
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isOffline = it.isOffline || isOfflineFailure(error),
                    errorMessage = error.message ?: "Could not load board.",
                )
            }
            false
        }
    }

    private fun validateSnapshot(snapshot: KanbanBoardSnapshot) {
        val columns = snapshot.columns ?: throw KanbanContractException(
            "This server's Kanban response is incompatible with Hermex.",
        )
        if (columns.any { column ->
                column.name.isNullOrBlank() || column.cards.orEmpty().any { card ->
                    card.cardId.isNullOrBlank() || card.status?.rawValue.isNullOrBlank()
                }
            }
        ) {
            throw KanbanContractException("This server's Kanban response is incompatible with Hermex.")
        }
    }

    private suspend fun loadSupplementary(board: String) = coroutineScope {
        val stats = async { optionalRead { dataSource.kanbanStats(board) } }
        val assignees = async { optionalRead { dataSource.kanbanAssignees(board) } }
        val loadedStats: KanbanStats? = stats.await()
        val history: KanbanAssigneeHistory? = assignees.await()
        if (_uiState.value.selectedBoardSlug != board) return@coroutineScope
        _uiState.update { state ->
            state.copy(
                stats = loadedStats ?: state.stats,
                assigneeHistory = history ?: state.assigneeHistory,
                assignees = (state.assignees + history?.assignees.orEmpty()).distinct(),
            )
        }
    }

    private suspend fun <T> optionalRead(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    fun selectCard(cardId: String?) = viewModelScope.launch {
        _uiState.update { it.copy(selectedCardId = cardId, detail = null, workerLog = null) }
        cardId?.let { loadDetailInternal(it) }
    }

    fun reloadDetail() = viewModelScope.launch {
        _uiState.value.selectedCardId?.let { loadDetailInternal(it) }
    }

    private suspend fun loadDetailInternal(cardId: String) {
        val board = _uiState.value.selectedBoardSlug ?: return
        _uiState.update { it.copy(isLoadingDetail = true, errorMessage = null) }
        try {
            val detail = dataSource.kanbanCardDetail(KanbanCardDetailRequest(cardId, board))
            KanbanValidators.requireDetail(detail, cardId)
            if (_uiState.value.selectedBoardSlug != board || _uiState.value.selectedCardId != cardId) return
            _uiState.update {
                it.copy(
                    detail = detail,
                    isLoadingDetail = false,
                    readOnly = it.readOnly || detail.readOnly == true,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoadingDetail = false, errorMessage = error.message ?: "Could not load card.") }
        }
    }

    fun toggleCardSelection(cardId: String) {
        _uiState.update { state ->
            state.copy(selectedCardIds = state.selectedCardIds.toMutableSet().apply {
                if (!add(cardId)) remove(cardId)
            })
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedCardIds = emptySet()) }

    fun createBoard(request: KanbanCreateBoardRequest) = act("Board created") {
        val envelope = dataSource.createKanbanBoard(request)
        loadInitial(preferredBoard = envelope.board?.slug ?: _uiState.value.selectedBoardSlug)
    }

    fun editBoard(request: KanbanEditBoardRequest) = act("Board updated") {
        dataSource.editKanbanBoard(request)
        loadInitial(preferredBoard = request.slug)
    }

    fun archiveBoard(slug: String) = act("Board archived") {
        dataSource.archiveKanbanBoard(slug)
        loadInitial(preferredBoard = null)
    }

    fun makeBoardActive(slug: String) = act("Active board updated") {
        dataSource.makeKanbanBoardActive(slug)
        loadInitial(preferredBoard = _uiState.value.selectedBoardSlug)
    }

    fun createCard(request: KanbanCreateCardRequest) = act("Card created") {
        val card = KanbanValidators.requireCard(dataSource.createKanbanCard(request))
        reconcileCardWrite(card)
        card.cardId?.let { id ->
            _uiState.update { it.copy(selectedCardId = id) }
            loadDetailInternal(id)
        }
    }

    fun editCard(request: KanbanEditCardRequest) = act("Card updated") {
        val baseline = _uiState.value.cards.firstOrNull { it.cardId == request.cardId }
            ?: _uiState.value.detail?.card
        val changesAssignee = baseline?.assignee != request.assignee
        val card = KanbanValidators.requireCard(
            dataSource.editKanbanCard(request, changesAssignee),
            request.cardId,
        )
        reconcileCardWrite(card)
        loadDetailInternal(request.cardId)
    }

    fun setStatus(cardId: String, status: String) = act("Moved to $status") {
        val board = requireBoard()
        val previous = _uiState.value.cards.firstOrNull { it.cardId == cardId }?.status?.rawValue
        val card = KanbanValidators.requireCard(
            dataSource.setKanbanCardStatus(KanbanCardStatusRequest(cardId, board, status)),
            cardId,
        )
        if (!dataSource.backend.accepts(card.status?.rawValue, status, previous)) {
            throw KanbanContractException("The server did not confirm the requested Status.")
        }
        reconcileCardWrite(card)
        loadDetailInternal(cardId)
    }

    fun block(cardId: String, reason: String?) = act("Card blocked") {
        val board = requireBoard()
        val previous = _uiState.value.cards.firstOrNull { it.cardId == cardId }?.status?.rawValue
        val card = KanbanValidators.requireCard(
            dataSource.blockKanbanCard(KanbanCardActionRequest(cardId, board, reason)),
            cardId,
        )
        if (!dataSource.backend.accepts(card.status?.rawValue, "blocked", previous)) {
            throw KanbanContractException("The server did not confirm that the Card was blocked.")
        }
        reconcileCardWrite(card)
        loadDetailInternal(cardId)
    }

    fun unblock(cardId: String) = act("Card unblocked") {
        val board = requireBoard()
        val card = KanbanValidators.requireCard(
            dataSource.unblockKanbanCard(KanbanCardActionRequest(cardId, board)),
            cardId,
        )
        if (!dataSource.backend.accepts(card.status?.rawValue, "ready", "blocked")) {
            throw KanbanContractException("The server did not confirm that the Card was unblocked.")
        }
        reconcileCardWrite(card)
        loadDetailInternal(cardId)
    }

    private suspend fun reconcileCardWrite(card: KanbanCard) {
        if (dataSource.backend == KanbanBackend.HERMES) {
            _uiState.value.selectedBoardSlug?.let {
                loadBoardInternal(it, preserveSelection = true, usingCursor = false)
            }
        } else {
            applyCard(card)
        }
        _uiState.value.selectedBoardSlug?.let { loadSupplementary(it) }
    }

    fun addComment(cardId: String, body: String) = act("Comment added") {
        val response = dataSource.addKanbanComment(KanbanAddCommentRequest(cardId, requireBoard(), body.trim()))
        if (response.ok != true) error("Server rejected the comment")
        loadDetailInternal(cardId)
    }

    fun addDependency(prerequisiteId: String, dependentId: String) = act("Dependency added") {
        val request = KanbanDependencyMutationRequest(requireBoard(), prerequisiteId, dependentId)
        KanbanValidators.requireDependency(dataSource.addKanbanDependency(request), request)
        reconcileBoardAfterRelationshipWrite()
    }

    fun removeDependency(prerequisiteId: String, dependentId: String) = act("Dependency removed") {
        val request = KanbanDependencyMutationRequest(requireBoard(), prerequisiteId, dependentId)
        KanbanValidators.requireDependency(dataSource.removeKanbanDependency(request), request)
        reconcileBoardAfterRelationshipWrite()
    }

    private suspend fun reconcileBoardAfterRelationshipWrite() {
        val board = requireBoard()
        loadBoardInternal(board, preserveSelection = true, usingCursor = false)
        _uiState.value.selectedCardId?.let { loadDetailInternal(it) }
        loadSupplementary(board)
    }

    fun bulk(request: KanbanBulkActionRequest) = act("Bulk action complete") {
        val response = dataSource.performKanbanBulkAction(request)
        val failures = response.results.orEmpty().filter { it.ok != true }
        loadBoardInternal(request.board, preserveSelection = true, usingCursor = false)
        loadSupplementary(request.board)
        _uiState.update { state ->
            state.copy(selectedCardIds = failures.mapNotNull { it.cardId }.toSet())
        }
        if (failures.isNotEmpty()) {
            error("${failures.size} card${if (failures.size == 1) "" else "s"} failed")
        }
    }

    fun previewDispatch() = dispatch(dryRun = true)
    fun runDispatcher() = dispatch(dryRun = false)

    private fun dispatch(dryRun: Boolean): Job {
        if (!dataSource.capabilities.dispatcher) return viewModelScope.launch { }
        return act(if (dryRun) "Dispatch preview ready" else "Dispatcher started") {
            val board = requireBoard()
            val result = dataSource.dispatchKanban(KanbanDispatchRequest(board, dryRun, maximum = 8))
            _uiState.update {
                if (dryRun) {
                    it.copy(dispatchPreview = result)
                } else {
                    it.copy(dispatchResult = result, dispatchPreview = null)
                }
            }
            if (!dryRun) {
                loadBoardInternal(board, preserveSelection = true, usingCursor = false)
                loadSupplementary(board)
            }
        }
    }

    fun loadWorkerLog(cardId: String, runId: String? = null) = viewModelScope.launch {
        if (!dataSource.capabilities.workerLogs) return@launch
        val board = _uiState.value.selectedBoardSlug ?: return@launch
        try {
            val log = dataSource.kanbanWorkerLog(KanbanWorkerLogRequest(cardId, board))
            _uiState.update { it.copy(workerLog = log, actionMessage = runId?.let { id -> "Run $id" }) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.message ?: "Could not load worker log.") }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(errorMessage = null, actionMessage = null) }

    /** Begin live work; the call is safe before the initial Board load finishes. */
    fun startLiveUpdates() {
        liveRequested = true
        restartLiveUpdates()
    }

    fun stopLiveUpdates() {
        liveRequested = false
        suspendLiveTransport()
        streamFailureCount = 0
        _uiState.update { it.copy(isLive = false, liveUpdatesDelayed = false) }
    }

    private fun restartLiveUpdates() {
        suspendLiveTransport()
        val board = _uiState.value.selectedBoardSlug ?: return
        if (_uiState.value.columns.isEmpty() && _uiState.value.snapshotEventId == 0) return
        when (dataSource.backend) {
            KanbanBackend.WEBUI -> startWebuiPolling(board)
            KanbanBackend.HERMES -> startHermesStream(board)
        }
    }

    private fun startHermesStream(board: String) {
        if (!liveRequested || _uiState.value.selectedBoardSlug != board) return
        val source = dataSource.makeLiveEventSource() ?: run {
            startFallbackPolling(board)
            return
        }
        eventSource = source
        streamAttempt += 1
        val attempt = streamAttempt
        val generation = liveGeneration
        source.start(
            board = board,
            since = liveCursor.value,
            onFrame = { frame ->
                // Hermes transport callbacks run on HermesActor.dispatcher.
                // viewModelScope is Main, so no Compose state is touched there.
                viewModelScope.launch {
                    if (isCurrentLiveWork(board, generation, attempt)) handleLiveFrame(frame, board, generation, attempt)
                }
            },
            onFailure = {
                viewModelScope.launch {
                    if (isCurrentLiveWork(board, generation, attempt)) handleStreamFailure(board, generation, attempt)
                }
            },
        )
    }

    private suspend fun handleLiveFrame(
        frame: KanbanLiveFrame,
        board: String,
        generation: Int,
        attempt: Int,
    ) {
        when (frame) {
            KanbanLiveFrame.Opened -> {
                streamFailureCount = 0
                reconnectJob?.cancel()
                reconnectJob = null
                pollingJob?.cancel()
                pollingJob = null
                _uiState.update { it.copy(isLive = true, liveUpdatesDelayed = false) }
                if (_uiState.value.isOffline || liveCursor.value > _uiState.value.snapshotEventId) {
                    scheduleReconciliation(board, generation)
                }
            }
            is KanbanLiveFrame.Events -> when (val admission = liveCursor.apply(frame.envelope)) {
                is KanbanLiveAdmission.Apply -> {
                    liveCursor = admission.cursor
                    _uiState.update { it.copy(latestEventId = liveCursor.value) }
                    scheduleReconciliation(board, generation)
                }
                KanbanLiveAdmission.Stale -> Unit
                KanbanLiveAdmission.Malformed -> handleStreamFailure(board, generation, attempt)
            }
            KanbanLiveFrame.Malformed -> handleStreamFailure(board, generation, attempt)
        }
    }

    private fun handleStreamFailure(board: String, generation: Int, attempt: Int) {
        if (!isCurrentLiveWork(board, generation, attempt)) return
        streamAttempt += 1 // Duplicate callbacks from this attempt become inert.
        eventSource?.stop()
        eventSource = null
        _uiState.update { it.copy(isLive = false) }
        streamFailureCount += 1
        if (streamFailureCount >= liveTiming.failuresBeforePolling) {
            _uiState.update { it.copy(liveUpdatesDelayed = true) }
            startFallbackPolling(board)
        }
        val delays = liveTiming.reconnectDelaysMillis.ifEmpty { listOf(1_000L) }
        val wait = delays[minOf(streamFailureCount - 1, delays.lastIndex)]
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            sleepMillis(wait)
            if (liveRequested && liveGeneration == generation && _uiState.value.selectedBoardSlug == board) {
                startHermesStream(board)
            }
        }
    }

    private fun scheduleReconciliation(board: String, generation: Int) {
        reconciliationRequested = true
        if (coalescingJob != null) return
        coalescingJob = viewModelScope.launch {
            try {
                while (reconciliationRequested && isCurrentLiveBoard(board, generation)) {
                    reconciliationRequested = false
                    sleepMillis(liveTiming.coalescingMillis)
                    if (!isCurrentLiveBoard(board, generation)) return@launch
                    val loaded = loadBoardInternal(board, preserveSelection = true, usingCursor = false)
                    if (!loaded && _uiState.value.isOffline) startFallbackPolling(board)
                }
            } finally {
                coalescingJob = null
            }
        }
    }

    private fun startWebuiPolling(board: String) {
        val generation = liveGeneration
        _uiState.update { it.copy(isLive = true) }
        pollingJob = viewModelScope.launch {
            while (isActive && isCurrentLiveBoard(board, generation)) {
                sleepMillis(liveTiming.webuiPollingMillis)
                if (!isCurrentLiveBoard(board, generation)) return@launch
                try {
                    val envelope = dataSource.kanbanEvents(KanbanEventsRequest(board, liveCursor.value))
                    when (val admission = liveCursor.apply(envelope)) {
                        is KanbanLiveAdmission.Apply -> {
                            liveCursor = admission.cursor
                            _uiState.update { it.copy(latestEventId = liveCursor.value, isOffline = false) }
                            loadBoardInternal(board, preserveSelection = true, usingCursor = false)
                        }
                        KanbanLiveAdmission.Stale -> Unit
                        KanbanLiveAdmission.Malformed -> Unit
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (isOfflineFailure(error)) {
                        _uiState.update { it.copy(isOffline = true) }
                    }
                }
            }
        }
    }

    /** Hermes fallback: reload the Board every 30 s while the socket keeps reconnecting. */
    private fun startFallbackPolling(board: String) {
        if (pollingJob != null) return
        val generation = liveGeneration
        pollingJob = viewModelScope.launch {
            while (isActive && isCurrentLiveBoard(board, generation)) {
                sleepMillis(liveTiming.fallbackPollingMillis)
                if (!isCurrentLiveBoard(board, generation)) return@launch
                loadBoardInternal(board, preserveSelection = true, usingCursor = true)
            }
        }
    }

    private fun suspendLiveTransport() {
        liveGeneration += 1
        streamAttempt += 1
        eventSource?.stop()
        eventSource = null
        reconnectJob?.cancel()
        reconnectJob = null
        pollingJob?.cancel()
        pollingJob = null
        coalescingJob?.cancel()
        coalescingJob = null
        reconciliationRequested = false
    }

    private fun isCurrentLiveWork(board: String, generation: Int, attempt: Int): Boolean =
        attempt == streamAttempt && isCurrentLiveBoard(board, generation)

    private fun isCurrentLiveBoard(board: String, generation: Int): Boolean =
        liveRequested && generation == liveGeneration && _uiState.value.selectedBoardSlug == board

    private fun act(success: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (!_uiState.value.canMutate) {
            val message = if (_uiState.value.isOffline) {
                "Offline—showing previously loaded data. Reconnect before changing this Board."
            } else {
                "This Kanban bridge is read-only."
            }
            _uiState.update { it.copy(errorMessage = message) }
            return@launch
        }
        if (_uiState.value.isActing) return@launch
        _uiState.update { it.copy(isActing = true, errorMessage = null, actionMessage = null) }
        try {
            block()
            _uiState.update { it.copy(isActing = false, actionMessage = success) }
        } catch (cancellation: CancellationException) {
            _uiState.update { it.copy(isActing = false) }
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(isActing = false, errorMessage = error.message ?: "Kanban action failed.") }
        }
    }

    private fun requireBoard(): String =
        _uiState.value.selectedBoardSlug ?: error("No board selected")

    private fun applyCard(card: KanbanCard) {
        val cardId = card.cardId ?: return
        val status = card.status?.rawValue ?: return
        _uiState.update { state ->
            val without = state.columns.map { column ->
                column.copy(cards = column.cards.orEmpty().filterNot { it.cardId == cardId })
            }.toMutableList()
            val target = without.indexOfFirst { it.name.equals(status, ignoreCase = true) }
            if (target >= 0) {
                without[target] = without[target].copy(cards = without[target].cards.orEmpty() + card)
            } else {
                without += KanbanColumn(status, listOf(card))
            }
            state.copy(columns = without)
        }
    }

    private fun isOfflineFailure(error: Throwable): Boolean = when (error) {
        is ApiError.Network -> true
        is ApiError.Http -> error.statusCode in 502..504 || error.statusCode in 520..530
        is BotFailure.Network -> true
        BotFailure.Transport -> true
        is BotFailure.Rejected -> error.code in 502..504 || error.code in 520..530
        else -> false
    }

    override fun onCleared() {
        liveRequested = false
        suspendLiveTransport()
    }
}
