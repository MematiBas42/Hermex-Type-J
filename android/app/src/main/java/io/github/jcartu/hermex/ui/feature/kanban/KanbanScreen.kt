package io.github.jcartu.hermex.ui.feature.kanban

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanDispatchResult
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanStaleness
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KanbanRoute(
    dataSource: KanbanDataSource,
    initialBoardSlug: String? = null,
    initialCardId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val viewModel: KanbanViewModel = viewModel(
        factory = viewModelFactory { initializer { KanbanViewModel(dataSource) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current
    var showBoardEditor by rememberSaveable { mutableStateOf(false) }
    var editingBoardSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var showCardEditor by rememberSaveable { mutableStateOf(false) }
    var editingCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showBulk by rememberSaveable { mutableStateOf(false) }
    var showDispatch by rememberSaveable { mutableStateOf(false) }
    var transientMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(initialBoardSlug, initialCardId, state.boards, state.isLoading) {
        if (
            (initialBoardSlug != null || initialCardId != null) &&
            !state.isLoading &&
            state.boards.isNotEmpty()
        ) {
            viewModel.openTarget(initialBoardSlug, initialCardId)
            onDeepLinkConsumed()
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startLiveUpdates()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startLiveUpdates()
                Lifecycle.Event.ON_STOP -> viewModel.stopLiveUpdates()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopLiveUpdates()
        }
    }
    LaunchedEffect(state.errorMessage, state.actionMessage, state.columns) {
        val transient = state.actionMessage ?: state.errorMessage?.takeIf { state.columns.isNotEmpty() }
        transient?.let { message ->
            transientMessage = message
            viewModel.dismissMessage()
            scope.launch {
                delay(4_000)
                if (transientMessage == message) transientMessage = null
            }
        }
    }

    val editBoard = state.boards.firstOrNull { it.slug == editingBoardSlug }
    val editCard = state.cards.firstOrNull { it.cardId == editingCardId }
    val boardPane: @Composable (Modifier, Boolean) -> Unit = { modifier, selectorVisible ->
        KanbanBoardPane(
            state = state,
            showBoardSelector = selectorVisible,
            onBoardSelected = viewModel::selectBoard,
            onRefresh = viewModel::refresh,
            onNewBoard = { editingBoardSlug = null; showBoardEditor = true },
            onEditBoard = { editingBoardSlug = it.slug; showBoardEditor = true },
            onArchiveBoard = { it.slug?.let(viewModel::archiveBoard) },
            onMakeActive = { it.slug?.let(viewModel::makeBoardActive) },
            onFilters = { showFilters = true },
            onNewCard = { editingCardId = null; showCardEditor = true },
            onSelectCard = viewModel::selectCard,
            onToggleSelection = viewModel::toggleCardSelection,
            onBulk = { showBulk = true },
            onDispatch = { showDispatch = true; viewModel.previewDispatch() },
            modifier = modifier,
        )
    }
    val detailPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { modifier, onBack ->
        KanbanDetailPane(
            state = state,
            onBack = onBack,
            onEdit = { editingCardId = it.cardId; showCardEditor = true },
            onSetStatus = viewModel::setStatus,
            onBlock = viewModel::block,
            onUnblock = viewModel::unblock,
            onComment = viewModel::addComment,
            onAddDependency = viewModel::addDependency,
            onRemoveDependency = viewModel::removeDependency,
            onLoadLog = viewModel::loadWorkerLog,
            onRefresh = viewModel::reloadDetail,
            modifier = modifier,
        )
    }

    Box(Modifier.fillMaxSize()) {
        when {
            paneCount == 1 && state.selectedCardId != null -> {
                BackHandler { viewModel.selectCard(null) }
                detailPane(Modifier.fillMaxSize(), { viewModel.selectCard(null) })
            }
            paneCount == 1 -> boardPane(Modifier.fillMaxSize(), true)
            paneCount == 2 -> HingeAwarePaneRow(2) { index, modifier ->
                if (index == 0) boardPane(modifier, true) else detailPane(modifier, null)
            }
            else -> HingeAwarePaneRow(3) { index, modifier ->
                when (index) {
                    0 -> KanbanBoardSelectorPane(
                        state, viewModel::selectBoard,
                        { editingBoardSlug = null; showBoardEditor = true },
                        { editingBoardSlug = it.slug; showBoardEditor = true },
                        { it.slug?.let(viewModel::archiveBoard) },
                        { it.slug?.let(viewModel::makeBoardActive) },
                        modifier,
                    )
                    1 -> boardPane(modifier, false)
                    else -> detailPane(modifier, null)
                }
            }
        }
        transientMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }

    if (showBoardEditor) KanbanBoardEditorDialog(
        board = editBoard,
        onDismiss = { showBoardEditor = false },
        onCreate = viewModel::createBoard,
        onEdit = viewModel::editBoard,
    )
    state.selectedBoardSlug?.takeIf { showCardEditor }?.let { boardSlug ->
        KanbanCardEditorDialog(
            board = boardSlug,
            backend = state.backend,
            columns = state.configuration?.columns.orEmpty().ifEmpty { state.columns.mapNotNull { it.name } },
            assignees = state.assignees,
            card = editCard,
            onDismiss = { showCardEditor = false },
            onCreate = viewModel::createCard,
            onEdit = viewModel::editCard,
        )
    }
    if (showFilters) KanbanFilterDialog(state, { showFilters = false }, viewModel::updateFilters)
    if (showBulk) KanbanBulkActionDialog(state, { showBulk = false }, viewModel::bulk)
    if (showDispatch) KanbanDispatchDialog(
        state.dispatchPreview,
        state.dispatchResult,
        state.isActing,
        { showDispatch = false },
        viewModel::previewDispatch,
        viewModel::runDispatcher,
    )
}

@Composable
private fun KanbanBoardSelectorPane(
    state: KanbanUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onEdit: (KanbanBoard) -> Unit,
    onArchive: (KanbanBoard) -> Unit,
    onActive: (KanbanBoard) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Dashboard, null)
            Text("Boards", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onNew, enabled = state.canMutate) { Icon(Icons.Filled.Add, "New board") }
        }
        HorizontalDivider()
        if (state.boards.isEmpty() && !state.isLoading) EmptyState(
            "No boards",
            modifier = modifier,
            message = "Create a board to organize agent work.",
        )
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.boards, key = { it.stableId }) { board ->
                KanbanBoardRow(board, board.slug == state.selectedBoardSlug, onSelect, onEdit, onArchive, onActive)
            }
        }
    }
}

@Composable
private fun KanbanBoardRow(
    board: KanbanBoard,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onEdit: (KanbanBoard) -> Unit,
    onArchive: (KanbanBoard) -> Unit,
    onActive: (KanbanBoard) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { board.slug?.let(onSelect) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.width(8.dp).height(42.dp), color = colorFromHex(board.color), shape = CircleShape) {}
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${board.icon.orEmpty()} ${board.name ?: board.slug}", fontWeight = FontWeight.SemiBold)
                Text("${board.total ?: 0} cards", style = MaterialTheme.typography.labelSmall)
            }
            if (board.isCurrent == true) Icon(Icons.Filled.CheckCircle, "Active board", tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Board actions") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Make active") }, { menu = false; onActive(board) })
                DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit(board) }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                DropdownMenuItem({ Text("Archive") }, { menu = false; onArchive(board) }, leadingIcon = { Icon(Icons.Filled.Archive, null) })
            }
        }
    }
}

@Composable
private fun KanbanBoardPane(
    state: KanbanUiState,
    showBoardSelector: Boolean,
    onBoardSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onNewBoard: () -> Unit,
    onEditBoard: (KanbanBoard) -> Unit,
    onArchiveBoard: (KanbanBoard) -> Unit,
    onMakeActive: (KanbanBoard) -> Unit,
    onFilters: () -> Unit,
    onNewCard: () -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onBulk: () -> Unit,
    onDispatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBoardSelector) {
                var menu by remember { mutableStateOf(false) }
                TextButton(onClick = { menu = true }) {
                    Text(state.selectedBoard?.name ?: "Choose board", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(menu, { menu = false }) {
                    state.boards.forEach { board ->
                        DropdownMenuItem(
                            { Text(board.name ?: board.slug.orEmpty()) },
                            { menu = false; board.slug?.let(onBoardSelected) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("New board") }, { menu = false; onNewBoard() })
                    state.selectedBoard?.let { board ->
                        DropdownMenuItem({ Text("Edit board") }, { menu = false; onEditBoard(board) })
                        DropdownMenuItem({ Text("Make active") }, { menu = false; onMakeActive(board) })
                        DropdownMenuItem({ Text("Archive board") }, { menu = false; onArchiveBoard(board) })
                    }
                }
            } else {
                Text(
                    state.selectedBoard?.name ?: "Board",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.isLive) Surface(Modifier.width(8.dp).height(8.dp), shape = CircleShape, color = Color(0xFF34C759)) {}
            IconButton(onClick = onFilters) {
                BadgedBox(
                    badge = { if (state.filters != KanbanFilters()) Badge { Text("•") } },
                ) { Icon(Icons.Filled.FilterList, "Filters") }
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton(onClick = onNewCard, enabled = state.canMutate && state.selectedBoardSlug != null) { Icon(Icons.Filled.Add, "New card") }
            if (state.capabilities.dispatcher) {
                IconButton(onClick = onDispatch, enabled = state.canMutate && state.selectedBoardSlug != null) {
                    Icon(Icons.Filled.AutoAwesome, "Dispatcher")
                }
            }
        }
        if (state.liveUpdatesDelayed) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    "Live updates delayed",
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (state.isOffline) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "Offline—showing previously loaded data",
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (state.selectedCardIds.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SelectAll, null)
                    Text("${state.selectedCardIds.size} selected", Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = onBulk) { Text("Bulk action") }
                }
            }
        }
        if (state.isLoading && state.columns.isEmpty()) return LoadingState(Modifier.fillMaxSize(), "Loading board")
        if (state.errorMessage != null && state.columns.isEmpty()) return ErrorState(state.errorMessage, onRetry = onRefresh)
        if (state.selectedBoardSlug == null) return EmptyState("Choose a board", message = "Boards keep autonomous work visible and actionable.")
        if (state.columns.isEmpty()) return EmptyState("No cards", message = "Create a card or adjust the board filters.")

        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.columns, key = { it.name.orEmpty() }) { column ->
                KanbanColumnView(
                    column.name.orEmpty(), column.cards.orEmpty(), state,
                    onSelectCard, onToggleSelection,
                    modifier = if (LocalPaneCount.current >= 3) {
                        Modifier.fillParentMaxWidth(0.94f)
                    } else {
                        Modifier.width(310.dp)
                    },
                )
            }
        }
    }
}

@Composable
private fun KanbanColumnView(
    name: String,
    cards: List<KanbanCard>,
    state: KanbanUiState,
    onSelect: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name.ifEmpty { "Unknown" }, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Badge { Text(cards.size.toString()) }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards, key = { it.stableId }) { card ->
                    KanbanCardRow(card, card.cardId == state.selectedCardId, card.cardId in state.selectedCardIds, onSelect, onToggle)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KanbanCardRow(
    card: KanbanCard,
    selected: Boolean,
    checked: Boolean,
    onSelect: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    val id = card.cardId.orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (id.isNotEmpty()) onSelect(id) },
            onLongClick = { if (id.isNotEmpty()) onToggle(id) },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked, { if (id.isNotEmpty()) onToggle(id) })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(card.title ?: "Untitled", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("P${card.priority ?: 0}", style = MaterialTheme.typography.labelSmall)
                    card.assignee?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    if (card.commentCount ?: 0 > 0) Text("${card.commentCount} comments", style = MaterialTheme.typography.labelSmall)
                }
                when (card.staleness) {
                    KanbanStaleness.WARNING -> Text("Stale", color = Color(0xFFFF9F0A), style = MaterialTheme.typography.labelSmall)
                    KanbanStaleness.CRITICAL -> Text("Needs attention", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    KanbanStaleness.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun KanbanDispatchDialog(
    preview: KanbanDispatchResult?,
    result: KanbanDispatchResult?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onRun: () -> Unit,
) {
    var confirmsRun by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dispatcher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preview Dispatch is advisory. Run Dispatcher may start workers and consume API budget.")
                if (loading) CircularProgressIndicator()
                (result ?: preview)?.let { summary ->
                    DispatchMetric("Spawned", summary.spawnedCount)
                    DispatchMetric("Promoted", summary.promotedCount)
                    DispatchMetric("Reclaimed", summary.reclaimedCount)
                    DispatchMetric("Skipped", summary.skippedCount)
                    DispatchMetric("Failures", summary.failureCount)
                }
            }
        },
        confirmButton = {
            Button(onClick = { confirmsRun = true }, enabled = !loading) { Text("Run Dispatcher") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPreview, enabled = !loading) { Text("Preview Dispatch") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
    if (confirmsRun) {
        AlertDialog(
            onDismissRequest = { confirmsRun = false },
            title = { Text("Run Dispatcher?") },
            text = { Text("This may start up to 8 workers and consume API budget.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmsRun = false
                        onRun()
                    },
                ) { Text("Run Dispatcher") }
            },
            dismissButton = {
                TextButton(onClick = { confirmsRun = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DispatchMetric(label: String, value: Int?) {
    Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text(value?.toString() ?: "—", fontWeight = FontWeight.Bold) }
}

private fun colorFromHex(value: String?): Color {
    val raw = value?.removePrefix("#")
    val isValid = raw != null && raw.length == 6 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    if (!isValid) return Color(0xFFFFC629)
    return Color((raw!!.toLong(16) or 0xFF000000L).toInt())
}
