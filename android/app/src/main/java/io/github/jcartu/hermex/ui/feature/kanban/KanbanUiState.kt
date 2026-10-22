package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanDispatchResult
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanStats
import io.github.jcartu.hermex.model.KanbanWorkerLog

data class KanbanFilters(
    val tenant: String? = null,
    val assignee: String? = null,
    val includeArchived: Boolean = false,
    val onlyMine: Boolean = false,
)

data class KanbanUiState(
    val backend: KanbanBackend = KanbanBackend.WEBUI,
    val capabilities: KanbanCapabilities = KanbanCapabilities(),
    val configuration: KanbanConfiguration? = null,
    val boards: List<KanbanBoard> = emptyList(),
    val selectedBoardSlug: String? = null,
    val columns: List<KanbanColumn> = emptyList(),
    val tenants: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val assigneeHistory: KanbanAssigneeHistory? = null,
    val stats: KanbanStats? = null,
    val filters: KanbanFilters = KanbanFilters(),
    val selectedCardId: String? = null,
    val detail: KanbanCardDetailEnvelope? = null,
    val selectedCardIds: Set<String> = emptySet(),
    val workerLog: KanbanWorkerLog? = null,
    val dispatchPreview: KanbanDispatchResult? = null,
    val dispatchResult: KanbanDispatchResult? = null,
    /** Highest accepted live-event cursor. It never regresses on stale frames or snapshots. */
    val latestEventId: Int = 0,
    /** Cursor represented by [columns], which may trail [latestEventId] until reconciliation. */
    val snapshotEventId: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val isActing: Boolean = false,
    val isLive: Boolean = false,
    val liveUpdatesDelayed: Boolean = false,
    val isOffline: Boolean = false,
    val isUnavailable: Boolean = false,
    val readOnly: Boolean = false,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
) {
    val selectedBoard: KanbanBoard?
        get() = boards.firstOrNull { it.slug == selectedBoardSlug }

    val cards: List<KanbanCard>
        get() = columns.flatMap { it.cards.orEmpty() }

    val selectedCard: KanbanCard?
        get() = detail?.card ?: cards.firstOrNull { it.cardId == selectedCardId }

    val canMutate: Boolean
        get() = !isOffline &&
            !isUnavailable &&
            !readOnly &&
            configuration?.readOnly != true &&
            selectedBoard?.readOnly != true
}
