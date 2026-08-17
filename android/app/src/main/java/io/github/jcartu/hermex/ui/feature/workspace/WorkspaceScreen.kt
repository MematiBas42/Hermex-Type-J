package io.github.jcartu.hermex.ui.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Files tab: workspace roots → directory browser → file preview.
 *
 * Adaptive contract:
 * - 1 pane (cover screen): one surface at a time with back navigation
 *   (roots → browser → preview), system back walks the same stack.
 * - 2 panes: browser (roots list until a root is opened) | preview, split on
 *   the physical hinge by [HingeAwarePaneRow].
 * - 3 panes (unfolded trifold): roots | files | preview, boundaries snapped
 *   to both hinges.
 *
 * All selection/navigation state lives in [WorkspaceViewModel], so posture
 * changes never lose the browsed path or the previewed file.
 */
@Composable
fun WorkspaceRoute() {
    val app = LocalContext.current.applicationContext as HermexApplication
    val viewModel: WorkspaceViewModel = viewModel(
        factory = viewModelFactory { initializer { WorkspaceViewModel(app.container.api) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current

    LaunchedEffect(Unit) { viewModel.loadRootsIfNeeded() }

    BackHandler(enabled = state.canGoBack) {
        if (paneCount == 1) viewModel.goBack() else viewModel.goBackInBrowser()
    }

    when {
        paneCount >= 3 -> HingeAwarePaneRow(paneCount = 3) { index, modifier ->
            when (index) {
                0 -> RootsPane(state, viewModel, modifier)
                1 -> if (state.selectedRootPath == null) {
                    EmptyState(
                        title = "Select a workspace",
                        message = "Choose a workspace to browse its files.",
                        modifier = modifier,
                    )
                } else {
                    BrowserPane(state, viewModel, modifier, showRootsButton = false)
                }
                else -> FilePreviewPane(state, viewModel, modifier, showBack = false)
            }
        }
        paneCount == 2 -> HingeAwarePaneRow(paneCount = 2) { index, modifier ->
            when (index) {
                0 -> if (state.selectedRootPath == null) {
                    RootsPane(state, viewModel, modifier)
                } else {
                    BrowserPane(state, viewModel, modifier, showRootsButton = true)
                }
                else -> FilePreviewPane(state, viewModel, modifier, showBack = false)
            }
        }
        else -> when {
            state.selectedEntry != null ->
                FilePreviewPane(state, viewModel, Modifier.fillMaxSize(), showBack = true)
            state.selectedRootPath != null ->
                BrowserPane(state, viewModel, Modifier.fillMaxSize(), showRootsButton = true)
            else -> RootsPane(state, viewModel, Modifier.fillMaxSize())
        }
    }
}

/** Centers pane content and keeps text from stretching full-bleed on wide panes. */
@Composable
internal fun ConstrainedPane(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = maxWidth),
            content = content,
        )
    }
}

// MARK: - Workspace roots pane

@Composable
private fun RootsPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    ConstrainedPane(maxWidth = 480.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Workspaces",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.loadRoots() }, enabled = !state.isLoadingRoots) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh workspaces")
            }
        }
        HorizontalDivider()
        when {
            state.isLoadingRoots && !state.hasLoadedRoots ->
                LoadingState(label = "Loading workspaces...")
            state.rootsError != null && !state.hasLoadedRoots ->
                ErrorState(
                    message = state.rootsError,
                    retryLabel = "Try Again",
                    onRetry = { viewModel.loadRoots() },
                )
            state.hasLoadedRoots && state.roots.isEmpty() ->
                EmptyState(
                    title = "No Workspaces",
                    message = "Register a workspace on the server to browse its files.",
                )
            else -> {
                val listState = rememberSaveable(saver = LazyListState.Saver, key = "workspaceRootsList") {
                    LazyListState()
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.roots, key = { it.path }) { root ->
                        WorkspaceRootRow(
                            root = root,
                            isSelected = root.path == state.selectedRootPath,
                            isLastUsed = root.path == state.lastUsedPath,
                            onClick = { viewModel.selectRoot(root) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRootRow(
    root: WorkspaceRootItem,
    isSelected: Boolean,
    isLastUsed: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = root.isBrowsable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (root.isBrowsable) Icons.Filled.Folder else Icons.Outlined.FolderOff,
                contentDescription = null,
                tint = if (root.isBrowsable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    root.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLastUsed) {
                    Text(
                        "Last used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                root.path,
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (root.isBrowsable) {
                    if (root.sessionCount == 1) "1 session" else "${root.sessionCount} sessions"
                } else {
                    "No sessions yet — start a chat in this workspace to browse files"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (root.isBrowsable) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Directory browser pane (port of FileBrowserView.swift)

@Composable
private fun BrowserPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    showRootsButton: Boolean,
) {
    ConstrainedPane(maxWidth = 480.dp, modifier = modifier) {
        PathHeader(state, viewModel, showRootsButton)
        SearchBar(state.searchQuery, onQueryChange = viewModel::setSearchQuery)
        HorizontalDivider()
        BrowserContent(state, viewModel)
    }
}

@Composable
private fun PathHeader(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    showRootsButton: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showRootsButton) {
                IconButton(onClick = { viewModel.backToRoots() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to workspaces",
                    )
                }
            }
            Text(
                "Location",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                state.displayPath,
                style = CodeTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.loadBrowseRoot() },
                enabled = !state.isAtRoot,
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Root")
            }
            IconButton(
                onClick = { viewModel.goUp() },
                enabled = state.parentPath != null,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "Up one directory",
                    modifier = Modifier.size(18.dp),
                )
            }
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.breadcrumbs) { index, crumb ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (index > 0) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        AssistChip(
                            onClick = { viewModel.openBreadcrumb(crumb.path) },
                            enabled = crumb.path != state.currentPath,
                            label = { Text(crumb.title, maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Search files") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear file search")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp),
    )
}

@Composable
private fun BrowserContent(state: WorkspaceUiState, viewModel: WorkspaceViewModel) {
    when {
        state.isLoadingEntries && state.entries.isEmpty() ->
            LoadingState(label = "Loading files...")
        state.entriesError != null && state.entries.isEmpty() ->
            ErrorState(
                message = state.entriesError,
                retryLabel = "Try Again",
                onRetry = { viewModel.retryLastLoad() },
            )
        state.visibleEntries.isEmpty() ->
            EmptyState(
                title = if (state.searchQuery.isBlank()) "No Files" else "No Matches",
                message = if (state.searchQuery.isBlank()) {
                    state.currentPath
                } else {
                    "Try a different file name or path."
                },
            )
        else -> Column(Modifier.fillMaxSize()) {
            if (state.isLoadingEntries) {
                StatusBanner {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading files...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.entriesError != null) {
                StatusBanner {
                    Text(
                        state.entriesError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.retryLastLoad() }) { Text("Try Again") }
                }
            }
            val listState = rememberSaveable(saver = LazyListState.Saver, key = "workspaceBrowserList") {
                LazyListState()
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.visibleEntries, key = { it.id }) { entry ->
                    FileBrowserRow(
                        entry = entry,
                        isSelected = entry.path != null && entry.path == state.selectedEntry?.path,
                        onClick = { viewModel.openEntry(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Port of FileBrowserRow in FileBrowserView.swift. */
@Composable
private fun FileBrowserRow(
    entry: WorkspaceEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val isDirectory = entry.isBrowsableDirectory
    val clickable = isDirectory || entry.path != null
    val background = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isDirectory) Icons.Filled.Folder else Icons.Outlined.Description,
                contentDescription = if (isDirectory) "Folder" else "File",
                tint = if (isDirectory) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entryDetailText(entry)?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isDirectory) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Directories show their path; files show size (and age when reported). */
internal fun entryDetailText(entry: WorkspaceEntry): String? {
    if (entry.isBrowsableDirectory) return entry.path
    val size = entry.size ?: return entry.path
    val sizeText = formatByteCount(size.toLong())
    val modified = entry.modified ?: return sizeText
    return "$sizeText • ${relativeAgo(modified)}"
}
