package io.github.jcartu.hermex.ui.feature.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardActionRequest
import io.github.jcartu.hermex.model.KanbanCardDetailRequest
import io.github.jcartu.hermex.model.KanbanCardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanDependencyMutationRequest
import io.github.jcartu.hermex.model.KanbanDispatchRequest
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanEventsRequest
import io.github.jcartu.hermex.model.KanbanValidators
import io.github.jcartu.hermex.model.KanbanWorkerLogRequest
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KanbanViewModel(
    private val api: HermesApi,
    private val livePollMillis: Long = 5_000,
) : ViewModel() {
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()
    private var liveJob: Job? = null

    fun load() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val configuration = api.kanbanConfiguration()
            val boardsResponse = api.kanbanBoards()
            val boards = boardsResponse.boards.orEmpty().filter { it.slug?.isNotBlank() == true }
            val selected = boardsResponse.current?.takeIf { slug -> boards.any { it.slug == slug } }
                ?: _uiState.value.selectedBoardSlug?.takeIf { slug -> boards.any { it.slug == slug } }
                ?: boards.firstOrNull()?.slug
            _uiState.update {
                it.copy(
                    configuration = configuration,
                    boards = boards,
                    selectedBoardSlug = selected,
                    isLoading = false,
                    readOnly = configuration.readOnly == true || boardsResponse.readOnly == true,
                )
            }
            selected?.let { loadBoardInternal(it, preserveSelection = true) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load Kanban.") }
        }
    }

    fun refresh() = viewModelScope.launch {
        val slug = _uiState.value.selectedBoardSlug
        if (slug == null) {
            load()
            return@launch
        }
        loadBoardInternal(slug, preserveSelection = true)
    }

    fun selectBoard(slug: String) = viewModelScope.launch {
        if (slug == _uiState.value.selectedBoardSlug) return@launch
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
            )
        }
        loadBoardInternal(slug, preserveSelection = false)
    }

    fun updateFilters(filters: KanbanFilters) = viewModelScope.launch {
        _uiState.update { it.copy(filters = filters) }
        _uiState.value.selectedBoardSlug?.let { loadBoardInternal(it, preserveSelection = true) }
    }

    fun openTarget(boardSlug: String?, cardId: String?) = viewModelScope.launch {
        val targetBoard = boardSlug?.takeIf { slug -> _uiState.value.boards.any { it.slug == slug } }
            ?: _uiState.value.selectedBoardSlug
            ?: return@launch
        if (targetBoard != _uiState.value.selectedBoardSlug || _uiState.value.columns.isEmpty()) {
            _uiState.update {
                it.copy(
                    selectedBoardSlug = targetBoard,
                    selectedCardId = null,
                    detail = null,
                    selectedCardIds = emptySet(),
                )
            }
            loadBoardInternal(targetBoard, preserveSelection = false)
        }
        if (!cardId.isNullOrBlank()) {
            _uiState.update { it.copy(selectedCardId = cardId, detail = null) }
            loadDetailInternal(cardId)
        }
    }

    private suspend fun loadBoardInternal(slug: String, preserveSelection: Boolean) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val filters = _uiState.value.filters
            val snapshot = api.kanbanBoard(
                KanbanBoardRequest(
                    board = slug,
                    tenant = filters.tenant,
                    assignee = filters.assignee,
                    includeArchived = filters.includeArchived,
                    onlyMine = filters.onlyMine,
                ),
            )
            val ids = snapshot.columns.orEmpty().flatMap { it.cards.orEmpty() }.mapNotNull { it.cardId }.toSet()
            _uiState.update { state ->
                val selected = state.selectedCardId.takeIf { preserveSelection && it in ids }
                state.copy(
                    columns = snapshot.columns.orEmpty(),
                    tenants = snapshot.tenants.orEmpty(),
                    assignees = snapshot.assignees.orEmpty(),
                    latestEventId = maxOf(state.latestEventId, snapshot.latestEventId ?: 0),
                    selectedCardId = selected,
                    detail = state.detail.takeIf { selected != null },
                    selectedCardIds = state.selectedCardIds.intersect(ids),
                    isLoading = false,
                    readOnly = state.readOnly || snapshot.readOnly == true,
                )
            }
            _uiState.value.selectedCardId?.let { loadDetailInternal(it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load board.") }
        }
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
            val detail = api.kanbanCardDetail(KanbanCardDetailRequest(cardId, board))
            KanbanValidators.requireDetail(detail, cardId)
            _uiState.update {
                it.copy(
                    detail = detail,
                    isLoadingDetail = false,
                    readOnly = it.readOnly || detail.readOnly == true,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
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
        val envelope = api.createKanbanBoard(request)
        load()
        envelope.board?.slug?.let(::selectBoard)
    }

    fun editBoard(request: KanbanEditBoardRequest) = act("Board updated") {
        api.editKanbanBoard(request)
        load()
    }

    fun archiveBoard(slug: String) = act("Board archived") {
        api.archiveKanbanBoard(slug)
        load()
    }

    fun makeBoardActive(slug: String) = act("Active board updated") {
        api.makeKanbanBoardActive(slug)
        load()
    }

    fun createCard(request: KanbanCreateCardRequest) = act("Card created") {
        val card = KanbanValidators.requireCard(api.createKanbanCard(request))
        applyCard(card)
        selectCard(card.cardId)
    }

    fun editCard(request: KanbanEditCardRequest) = act("Card updated") {
        val card = KanbanValidators.requireCard(api.editKanbanCard(request), request.cardId)
        applyCard(card)
        loadDetailInternal(request.cardId)
    }

    fun setStatus(cardId: String, status: String) = act("Moved to $status") {
        val board = requireBoard()
        val card = KanbanValidators.requireCard(
            api.setKanbanCardStatus(KanbanCardStatusRequest(cardId, board, status)), cardId,
        )
        applyCard(card)
        loadDetailInternal(cardId)
    }

    fun block(cardId: String, reason: String?) = act("Card blocked") {
        val board = requireBoard()
        applyMutation(api.blockKanbanCard(KanbanCardActionRequest(cardId, board, reason)), cardId)
    }

    fun unblock(cardId: String) = act("Card unblocked") {
        val board = requireBoard()
        applyMutation(api.unblockKanbanCard(KanbanCardActionRequest(cardId, board)), cardId)
    }

    private suspend fun applyMutation(envelope: KanbanCardMutationEnvelope, cardId: String) {
        applyCard(KanbanValidators.requireCard(envelope, cardId))
        loadDetailInternal(cardId)
    }

    fun addComment(cardId: String, body: String) = act("Comment added") {
        val board = requireBoard()
        val response = api.addKanbanComment(KanbanAddCommentRequest(cardId, board, body.trim()))
        if (response.ok != true) error("Server rejected the comment")
        loadDetailInternal(cardId)
    }

    fun addDependency(prerequisiteId: String, dependentId: String) = act("Dependency added") {
        val request = KanbanDependencyMutationRequest(requireBoard(), prerequisiteId, dependentId)
        val response = api.addKanbanDependency(request)
        KanbanValidators.requireDependency(response, request)
        _uiState.value.selectedCardId?.let { loadDetailInternal(it) }
    }

    fun removeDependency(prerequisiteId: String, dependentId: String) = act("Dependency removed") {
        val request = KanbanDependencyMutationRequest(requireBoard(), prerequisiteId, dependentId)
        val response = api.removeKanbanDependency(request)
        KanbanValidators.requireDependency(response, request)
        _uiState.value.selectedCardId?.let { loadDetailInternal(it) }
    }

    fun bulk(request: KanbanBulkActionRequest) = act("Bulk action complete") {
        val response = api.performKanbanBulkAction(request)
        val failures = response.results.orEmpty().count { it.ok != true }
        if (failures > 0) error("$failures card${if (failures == 1) "" else "s"} failed")
        clearSelection()
        refresh()
    }

    fun previewDispatch() = dispatch(dryRun = true)
    fun runDispatcher() = dispatch(dryRun = false)

    private fun dispatch(dryRun: Boolean) = act(if (dryRun) "Dispatch preview ready" else "Dispatcher started") {
        val result = api.dispatchKanban(KanbanDispatchRequest(requireBoard(), dryRun))
        _uiState.update {
            if (dryRun) it.copy(dispatchPreview = result) else it.copy(dispatchResult = result, dispatchPreview = null)
        }
        if (!dryRun) refresh()
    }

    fun loadWorkerLog(cardId: String, runId: String? = null) = viewModelScope.launch {
        val board = _uiState.value.selectedBoardSlug ?: return@launch
        try {
            val log = api.kanbanWorkerLog(KanbanWorkerLogRequest(cardId, board))
            _uiState.update { it.copy(workerLog = log, actionMessage = runId?.let { id -> "Run $id" }) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(errorMessage = error.message ?: "Could not load worker log.") }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(errorMessage = null, actionMessage = null) }

    fun startLiveUpdates() {
        if (liveJob != null) return
        _uiState.update { it.copy(isLive = true) }
        liveJob = viewModelScope.launch {
            while (isActive) {
                delay(livePollMillis)
                val board = _uiState.value.selectedBoardSlug ?: continue
                runCatching {
                    api.kanbanEvents(KanbanEventsRequest(board, _uiState.value.latestEventId))
                }.onSuccess { envelope ->
                    val next = envelope.latestEventId ?: envelope.cursor ?: _uiState.value.latestEventId
                    _uiState.update { it.copy(latestEventId = next) }
                    if (!envelope.events.isNullOrEmpty()) loadBoardInternal(board, preserveSelection = true)
                }
            }
        }
    }

    fun stopLiveUpdates() {
        liveJob?.cancel(); liveJob = null
        _uiState.update { it.copy(isLive = false) }
    }

    override fun onCleared() {
        stopLiveUpdates()
    }

    private fun act(success: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (!_uiState.value.canMutate) {
            _uiState.update { it.copy(errorMessage = "This Kanban bridge is read-only.") }
            return@launch
        }
        _uiState.update { it.copy(isActing = true, errorMessage = null, actionMessage = null) }
        try {
            block()
            _uiState.update { it.copy(isActing = false, actionMessage = success) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
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
}
