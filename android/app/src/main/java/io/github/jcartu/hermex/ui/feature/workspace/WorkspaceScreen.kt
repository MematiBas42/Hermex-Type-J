package io.github.jcartu.hermex.ui.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

/** Files destination and transcript-link landing surface. */
@Composable
fun WorkspaceRoute(initialSessionId: String? = null) {
    val app = LocalContext.current.applicationContext as HermexApplication
    val viewModel: WorkspaceViewModel = viewModel(
        factory = viewModelFactory { initializer { WorkspaceViewModel(app.container.api) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val request by WorkspaceFileNavigation.request.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current

    LaunchedEffect(Unit) { viewModel.loadRootsIfNeeded() }
    LaunchedEffect(request?.id, initialSessionId) {
        request?.let {
            viewModel.openWorkspaceFile(it.reference, initialSessionId, it.id)
            WorkspaceFileNavigation.consume(it.id)
        }
    }
    BackHandler(enabled = state.canGoBack) {
        if (paneCount == 1) viewModel.goBack() else viewModel.goBackInBrowser()
    }

    WorkspaceScreen(
        state = state,
        paneCount = paneCount,
        actions = WorkspaceActions(
            refreshRoots = viewModel::loadRoots,
            selectRoot = viewModel::selectRoot,
            backToRoots = viewModel::backToRoots,
            refreshTree = viewModel::reloadCurrentPath,
            search = viewModel::setSearchQuery,
            toggleDirectory = viewModel::toggleDirectory,
            retryDirectory = viewModel::retryDirectory,
            openEntry = viewModel::openEntry,
            closePreview = viewModel::closePreview,
        ),
    )
}

/** Callbacks separated from rendering so pane-count render tests need no ViewModel. */
internal data class WorkspaceActions(
    val refreshRoots: () -> Unit = {},
    val selectRoot: (WorkspaceRootItem) -> Unit = {},
    val backToRoots: () -> Unit = {},
    val refreshTree: () -> Unit = {},
    val search: (String) -> Unit = {},
    val toggleDirectory: (String) -> Unit = {},
    val retryDirectory: (String) -> Unit = {},
    val openEntry: (WorkspaceEntry) -> Unit = {},
    val closePreview: () -> Unit = {},
)

/** Adaptive tree/viewer composition for one, two, and three physical panes. */
@Composable
internal fun WorkspaceScreen(
    state: WorkspaceUiState,
    paneCount: Int = LocalPaneCount.current,
    actions: WorkspaceActions = WorkspaceActions(),
) {
    when {
        paneCount >= 3 -> HingeAwarePaneRow(paneCount = 3) { index, modifier ->
            when (index) {
                0 -> RootsPane(state, actions, modifier)
                1 -> if (state.selectedRootPath == null) {
                    EmptyState("Select a workspace", modifier, "Choose a workspace to browse its files.")
                } else {
                    FileTreePane(state, actions, modifier, showRootsButton = false)
                }
                else -> FilePreviewPane(state, actions.closePreview, modifier, showBack = false)
            }
        }
        paneCount == 2 -> HingeAwarePaneRow(paneCount = 2) { index, modifier ->
            if (index == 0) {
                if (state.selectedRootPath == null) RootsPane(state, actions, modifier)
                else FileTreePane(state, actions, modifier, showRootsButton = true)
            } else {
                FilePreviewPane(state, actions.closePreview, modifier, showBack = false)
            }
        }
        state.selectedEntry != null ->
            FilePreviewPane(state, actions.closePreview, Modifier.fillMaxSize(), showBack = true)
        state.selectedRootPath != null ->
            FileTreePane(state, actions, Modifier.fillMaxSize(), showRootsButton = true)
        else -> RootsPane(state, actions, Modifier.fillMaxSize())
    }
}

/** Centers pane content and prevents unreadably wide text on tablets. */
@Composable
internal fun ConstrainedPane(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = maxWidth),
            content = content,
        )
    }
}

@Composable
private fun RootsPane(
    state: WorkspaceUiState,
    actions: WorkspaceActions,
    modifier: Modifier = Modifier,
) {
    ConstrainedPane(480.dp, modifier) {
        PaneHeader("Workspaces", onRefresh = actions.refreshRoots)
        HorizontalDivider()
        when {
            state.isLoadingRoots && !state.hasLoadedRoots -> LoadingState(label = "Loading workspaces…")
            state.rootsError != null && !state.hasLoadedRoots ->
                ErrorState(state.rootsError, onRetry = actions.refreshRoots)
            state.hasLoadedRoots && state.roots.isEmpty() ->
                EmptyState("No Workspaces", message = "Register a workspace on the server to browse its files.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(state.roots, key = WorkspaceRootItem::path) { root ->
                    WorkspaceRootRow(
                        root = root,
                        selected = root.path == state.selectedRootPath,
                        lastUsed = root.path == state.lastUsedPath,
                        onClick = { actions.selectRoot(root) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRootRow(
    root: WorkspaceRootItem,
    selected: Boolean,
    lastUsed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = root.isBrowsable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(
                if (root.isBrowsable) Icons.Filled.Folder else Icons.Filled.FolderOff,
                null,
                modifier = Modifier.padding(8.dp).size(20.dp),
                tint = if (root.isBrowsable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(root.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lastUsed) Text("Last used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(root.path, style = CodeTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (root.isBrowsable) "${root.sessionCount} ${if (root.sessionCount == 1) "session" else "sessions"}"
                else "Start a chat here before browsing files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (root.isBrowsable) Icon(Icons.Filled.ChevronRight, null)
    }
}

@Composable
private fun FileTreePane(
    state: WorkspaceUiState,
    actions: WorkspaceActions,
    modifier: Modifier = Modifier,
    showRootsButton: Boolean,
) {
    ConstrainedPane(620.dp, modifier) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showRootsButton) {
                IconButton(onClick = actions.backToRoots) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to workspaces")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(state.selectedRoot?.displayName ?: "Files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.selectedRoot?.path?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = actions.refreshTree) { Icon(Icons.Filled.Refresh, "Refresh files") }
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = actions.search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Find a file") },
            singleLine = true,
        )
        HorizontalDivider()
        when {
            state.tree.loadingPaths[LazyFileTree.ROOT_PATH] != null && !state.tree.isRootLoaded ->
                LoadingState(label = "Loading files…")
            state.tree.failedPaths[LazyFileTree.ROOT_PATH] != null && !state.tree.isRootLoaded ->
                ErrorState(
                    state.tree.failedPaths[LazyFileTree.ROOT_PATH].orEmpty(),
                    onRetry = actions.refreshTree,
                )
            state.tree.isRootLoaded && state.visibleTreeNodes.isEmpty() ->
                EmptyState(
                    if (state.searchQuery.isBlank()) "No Files" else "No Matches",
                    message = if (state.searchQuery.isBlank()) "This workspace is empty." else "Try another search.",
                )
            else -> WorkspaceTreeRenderSurface(
                state = state,
                actions = actions,
            )
        }
    }
}

/** Lazy tree rows extracted for deterministic pane-count render tests. */
@Composable
internal fun WorkspaceTreeRenderSurface(
    state: WorkspaceUiState,
    modifier: Modifier = Modifier,
    actions: WorkspaceActions = WorkspaceActions(),
    showFileIcons: Boolean = true,
) {
    LazyColumn(modifier.fillMaxSize()) {
        items(state.visibleTreeNodes, key = { it.node.path }) { visible ->
            FileTreeRow(
                visible = visible,
                tree = state.tree,
                selected = visible.node.path == state.selectedEntry?.path,
                onToggle = actions.toggleDirectory,
                onRetry = actions.retryDirectory,
                onOpen = actions.openEntry,
                showFileIcon = showFileIcons,
            )
        }
    }
}

@Composable
private fun FileTreeRow(
    visible: VisibleFileTreeNode,
    tree: LazyFileTree,
    selected: Boolean,
    onToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (WorkspaceEntry) -> Unit,
    showFileIcon: Boolean,
) {
    val node = visible.node
    val loading = node.path in tree.loadingPaths
    val failure = tree.failedPaths[node.path]
    val expanded = node.path in tree.expandedPaths
    val disabled = node.entry.targetOutsideWorkspace == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = !disabled) {
                when {
                    failure != null -> onRetry(node.path)
                    node.isDirectory -> onToggle(node.path)
                    else -> onOpen(node.entry)
                }
            }
            .padding(start = (8 + visible.depth * 16).dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "Collapse ${node.name}" else "Expand ${node.name}",
                modifier = Modifier.size(20.dp),
            )
            Icon(Icons.Filled.Folder, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.width(20.dp))
            if (showFileIcon) {
                FileTypeIcon(node.path, Modifier.size(22.dp))
            } else {
                Spacer(Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (failure != null) {
                Text(failure, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
            } else if (disabled) {
                Text("Link target is outside this workspace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        when {
            loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            failure != null -> Icon(Icons.Filled.Warning, "Retry folder", tint = MaterialTheme.colorScheme.error)
            node.isDirectory && tree.isLoaded(node.path) -> {
                Text(tree.childrenOf(node.path).orEmpty().size.toString(), style = MaterialTheme.typography.labelSmall)
            }
            node.entry.size != null -> Text(formatByteCount(node.entry.size.toLong()), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PaneHeader(title: String, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh $title") }
    }
}
