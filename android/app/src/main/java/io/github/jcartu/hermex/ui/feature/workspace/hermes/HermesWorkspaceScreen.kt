package io.github.jcartu.hermex.ui.feature.workspace.hermes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceContext
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.workspace.ConstrainedPane
import io.github.jcartu.hermex.ui.feature.workspace.FilePreviewPane
import io.github.jcartu.hermex.ui.feature.workspace.LazyFileTree
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceActions
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceTreeRenderSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last open Hermes chat workspace, used by the app-level Files destination. */
object HermesWorkspaceNavigation {
    private val mutableContext = MutableStateFlow<HermesWorkspaceContext?>(null)
    val context: StateFlow<HermesWorkspaceContext?> = mutableContext.asStateFlow()

    fun open(context: HermesWorkspaceContext) {
        mutableContext.value = context
    }

    fun clear(serverId: String? = null) {
        if (serverId == null || mutableContext.value?.server == serverId) mutableContext.value = null
    }
}

/** Production route for one open Hermes session's host files. */
@Composable
fun HermesWorkspaceRoute(
    context: HermesWorkspaceContext,
    connection: HermesConnection,
    modifier: Modifier = Modifier,
) {
    HermesWorkspaceRoute(
        source = remember(context, connection) { DefaultHermesWorkspaceDataSource(context, connection) },
        modifier = modifier,
    )
}

/** Source-injected route retained for deterministic JVM/Robolectric rendering. */
@Composable
fun HermesWorkspaceRoute(
    source: HermesWorkspaceDataSource,
    modifier: Modifier = Modifier,
) {
    val viewModel: HermesWorkspaceViewModel = viewModel(
        key = "hermes-files:${source.context.server}:${source.context.profile}:${source.context.storedKey}:${source.context.cwd}",
        factory = viewModelFactory { initializer { HermesWorkspaceViewModel(source) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    HermesWorkspaceScreen(
        state = state,
        paneCount = LocalPaneCount.current,
        actions = HermesWorkspaceActions(
            refresh = viewModel::refresh,
            search = viewModel::setSearchQuery,
            toggleDirectory = viewModel::toggleDirectory,
            retryDirectory = viewModel::retryDirectory,
            openEntry = viewModel::openEntry,
            closePreview = viewModel::closePreview,
            setFolderQuery = viewModel::setFolderQuery,
            moveFolder = viewModel::moveFolder,
        ),
        modifier = modifier,
    )
}

data class HermesWorkspaceActions(
    val refresh: () -> Unit = {},
    val search: (String) -> Unit = {},
    val toggleDirectory: (String) -> Unit = {},
    val retryDirectory: (String) -> Unit = {},
    val openEntry: (io.github.jcartu.hermex.model.WorkspaceEntry) -> Unit = {},
    val closePreview: () -> Unit = {},
    val setFolderQuery: (String) -> Unit = {},
    val moveFolder: (String) -> Unit = {},
)

/** Adaptive Hermes Files composition: folder | tree | preview at three panes. */
@Composable
fun HermesWorkspaceScreen(
    state: HermesWorkspaceUiState,
    paneCount: Int = LocalPaneCount.current,
    actions: HermesWorkspaceActions = HermesWorkspaceActions(),
    modifier: Modifier = Modifier,
) {
    val workspaceActions = WorkspaceActions(
        refreshTree = actions.refresh,
        search = actions.search,
        toggleDirectory = actions.toggleDirectory,
        retryDirectory = actions.retryDirectory,
        openEntry = actions.openEntry,
        closePreview = actions.closePreview,
    )
    when {
        paneCount >= 3 -> HingeAwarePaneRow(paneCount = 3, modifier = modifier) { index, paneModifier ->
            when (index) {
                0 -> HermesFolderPane(state, actions, paneModifier)
                1 -> HermesFileTreePane(state, workspaceActions, paneModifier)
                else -> FilePreviewPane(state.workspace, actions.closePreview, paneModifier, showBack = false)
            }
        }

        paneCount == 2 -> HingeAwarePaneRow(paneCount = 2, modifier = modifier) { index, paneModifier ->
            if (index == 0) HermesFileTreePane(state, workspaceActions, paneModifier)
            else FilePreviewPane(state.workspace, actions.closePreview, paneModifier, showBack = false)
        }

        state.workspace.selectedEntry != null -> {
            BackHandler(onBack = actions.closePreview)
            FilePreviewPane(state.workspace, actions.closePreview, modifier.fillMaxSize(), showBack = true)
        }

        else -> HermesFileTreePane(state, workspaceActions, modifier.fillMaxSize())
    }
}

@Composable
private fun HermesFileTreePane(
    state: HermesWorkspaceUiState,
    actions: WorkspaceActions,
    modifier: Modifier,
) {
    ConstrainedPane(maxWidth = 620.dp, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${state.context.profile} · session workspace",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = actions.refreshTree) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh files")
            }
        }
        OutlinedTextField(
            value = state.workspace.searchQuery,
            onValueChange = actions.search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Find a file") },
            singleLine = true,
        )
        HorizontalDivider()
        val tree = state.workspace.tree
        when {
            tree.loadingPaths[LazyFileTree.ROOT_PATH] != null && !tree.isRootLoaded ->
                LoadingState(label = "Loading files…")

            tree.failedPaths[LazyFileTree.ROOT_PATH] != null && !tree.isRootLoaded ->
                ErrorState(tree.failedPaths[LazyFileTree.ROOT_PATH].orEmpty(), onRetry = actions.refreshTree)

            tree.isRootLoaded && state.workspace.visibleTreeNodes.isEmpty() ->
                EmptyState(
                    if (state.workspace.searchQuery.isBlank()) "No Files" else "No Matches",
                    message = if (state.workspace.searchQuery.isBlank()) {
                        "This working folder is empty."
                    } else {
                        "Try another search."
                    },
                )

            else -> WorkspaceTreeRenderSurface(state.workspace, actions)
        }
    }
}

@Composable
private fun HermesFolderPane(
    state: HermesWorkspaceUiState,
    actions: HermesWorkspaceActions,
    modifier: Modifier,
) {
    var selected by rememberSaveable(state.context.storedKey, state.context.cwd) {
        mutableStateOf(state.context.cwd)
    }
    val suggestions = (state.folderChoices + state.folderSuggestions).distinct()
    ConstrainedPane(maxWidth = 480.dp, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Working folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(state.context.profile, style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = state.folderQuery,
                onValueChange = {
                    selected = it
                    actions.setFolderQuery(it)
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Host folder") },
                supportingText = {
                    when {
                        state.needsMoreTyping -> Text("Type more of the folder name.")
                        state.folderError != null -> Text(state.folderError, color = MaterialTheme.colorScheme.error)
                        else -> Text("Start with / or ~/ to search the host.")
                    }
                },
                singleLine = true,
            )
            Button(
                onClick = { actions.moveFolder(selected) },
                enabled = selected.trim().trimEnd('/') != state.context.cwd.trimEnd('/') &&
                    selected.trim().isNotEmpty() && !state.isMovingFolder,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(if (state.isMovingFolder) "Moving…" else "Use This Folder")
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(suggestions, key = { it }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (folder == selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                            )
                            .clickable {
                                selected = folder
                                actions.setFolderQuery(folder)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(folder, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
        }
    }
}
