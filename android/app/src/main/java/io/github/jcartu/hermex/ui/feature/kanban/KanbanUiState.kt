package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanDispatchResult
import io.github.jcartu.hermex.model.KanbanWorkerLog

data class KanbanFilters(
    val tenant: String? = null,
    val assignee: String? = null,
    val includeArchived: Boolean = false,
    val onlyMine: Boolean = false,
)

data class KanbanUiState(
    val configuration: KanbanConfiguration? = null,
    val boards: List<KanbanBoard> = emptyList(),
    val selectedBoardSlug: String? = null,
    val columns: List<KanbanColumn> = emptyList(),
    val tenants: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val filters: KanbanFilters = KanbanFilters(),
    val selectedCardId: String? = null,
    val detail: KanbanCardDetailEnvelope? = null,
    val selectedCardIds: Set<String> = emptySet(),
    val workerLog: KanbanWorkerLog? = null,
    val dispatchPreview: KanbanDispatchResult? = null,
    val dispatchResult: KanbanDispatchResult? = null,
    val latestEventId: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val isActing: Boolean = false,
    val isLive: Boolean = false,
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
        get() = !readOnly && configuration?.readOnly != true && selectedBoard?.readOnly != true
}
