package io.github.jcartu.hermex.ui.feature.git

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.GitBranchMode
import io.github.jcartu.hermex.model.GitBranchRef
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.workspace.FileTypeIcon

private sealed interface GitConfirmation {
    data object Pull : GitConfirmation
    data object Push : GitConfirmation
    data object Discard : GitConfirmation
    data object Commit : GitConfirmation
}

/** Git destination preserving the fork's write/sync actions around the new review. */
@Composable
fun GitWorkspaceRoute(
    initialSessionId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val container = (LocalContext.current.applicationContext as HermexApplication).container
    val viewModel: GitWorkspaceViewModel = viewModel(
        factory = viewModelFactory { initializer { GitWorkspaceViewModel(container.api) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<GitConfirmation?>(null) }
    var showCheckout by rememberSaveable { mutableStateOf(false) }
    var stashCheckout by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(initialSessionId, state.sessions, state.isLoading) {
        if (initialSessionId != null && !state.isLoading && state.sessions.isNotEmpty()) {
            state.sessions.firstOrNull { it.sessionId == initialSessionId }?.sessionId?.let(viewModel::selectSession)
            onDeepLinkConsumed()
        }
    }

    val actions = GitWorkspaceActions(
        selectSession = viewModel::selectSession,
        refresh = viewModel::refresh,
        fetch = viewModel::fetch,
        pull = { confirmation = GitConfirmation.Pull },
        push = { confirmation = GitConfirmation.Push },
        checkout = { stash -> stashCheckout = stash; showCheckout = true },
        selectFile = viewModel::selectFile,
        closeReview = viewModel::closeDiff,
        togglePath = viewModel::togglePath,
        stage = viewModel::stageSelected,
        unstage = viewModel::unstageSelected,
        discard = { confirmation = GitConfirmation.Discard },
        messageChange = viewModel::setCommitMessage,
        generate = viewModel::generateCommitMessage,
        commit = { confirmation = GitConfirmation.Commit },
        dismissMessage = viewModel::dismissMessage,
    )
    GitWorkspaceScreen(state, LocalPaneCount.current, actions)

    confirmation?.let { action ->
        val (title, body) = when (action) {
            GitConfirmation.Pull -> "Pull remote changes" to "Fast-forward the selected workspace from its upstream?"
            GitConfirmation.Push -> "Push commits" to "Push the current branch to its configured upstream?"
            GitConfirmation.Discard -> "Discard selected changes" to "This permanently discards ${state.selectedPaths.size} selected path(s). Untracked files will be deleted."
            GitConfirmation.Commit -> "Create commit" to "Commit the ${if (state.selectedPaths.isEmpty()) "staged" else "selected"} changes with this message?"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    when (action) {
                        GitConfirmation.Pull -> viewModel.pull()
                        GitConfirmation.Push -> viewModel.push()
                        GitConfirmation.Discard -> viewModel.discardSelected(deleteUntracked = true)
                        GitConfirmation.Commit -> viewModel.commit()
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
    if (showCheckout) {
        GitCheckoutDialog(
            state = state,
            stashFirst = stashCheckout,
            onDismiss = { showCheckout = false },
            onCheckout = { viewModel.checkout(it, stashCheckout); showCheckout = false },
        )
    }
}

internal data class GitWorkspaceActions(
    val selectSession: (String) -> Unit = {},
    val refresh: () -> Unit = {},
    val fetch: () -> Unit = {},
    val pull: () -> Unit = {},
    val push: () -> Unit = {},
    val checkout: (Boolean) -> Unit = {},
    val selectFile: (GitFile) -> Unit = {},
    val closeReview: () -> Unit = {},
    val togglePath: (String) -> Unit = {},
    val stage: () -> Unit = {},
    val unstage: () -> Unit = {},
    val discard: () -> Unit = {},
    val messageChange: (String) -> Unit = {},
    val generate: () -> Unit = {},
    val commit: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/** Adaptive 1/2/3-pane Git composition used by render tests and the route. */
@Composable
internal fun GitWorkspaceScreen(
    state: GitWorkspaceUiState,
    paneCount: Int = LocalPaneCount.current,
    actions: GitWorkspaceActions = GitWorkspaceActions(),
) {
    Box(Modifier.fillMaxSize()) {
        when {
            paneCount == 1 && state.selectedFilePath != null -> {
                BackHandler(onBack = actions.closeReview)
                GitReviewPane(state, actions, Modifier.fillMaxSize(), actions.closeReview)
            }
            paneCount == 1 -> GitStatusPane(state, actions, Modifier.fillMaxSize())
            paneCount == 2 -> HingeAwarePaneRow(2) { index, modifier ->
                if (index == 0) GitStatusPane(state, actions, modifier)
                else GitReviewPane(state, actions, modifier, null)
            }
            else -> HingeAwarePaneRow(3) { index, modifier ->
                when (index) {
                    0 -> GitSessionsPane(state, actions.selectSession, modifier)
                    1 -> GitStatusPane(state, actions, modifier)
                    else -> GitReviewPane(state, actions, modifier, null)
                }
            }
        }
        GitActionToast(
            message = state.errorMessage ?: state.actionMessage,
            isError = state.errorMessage != null,
            onDismiss = actions.dismissMessage,
        )
    }
}

@Composable
private fun GitSessionsPane(
    state: GitWorkspaceUiState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Source, null)
            Text("Git workspaces", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.sessions, key = { it.sessionId.orEmpty() }) { session ->
                SessionWorkspaceRow(session, session.sessionId == state.selectedSessionId, onSelect)
            }
        }
    }
}

@Composable
private fun SessionWorkspaceRow(session: SessionSummary, selected: Boolean, onSelect: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { session.sessionId?.let(onSelect) }),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(session.title ?: "Untitled session", fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(session.workspace.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun GitStatusPane(
    state: GitWorkspaceUiState,
    actions: GitWorkspaceActions,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.status?.branch ?: state.info?.branch ?: "Git",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(state.selectedSession?.workspace ?: "Choose a workspace", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            val ahead = state.status?.ahead ?: 0
            val behind = state.status?.behind ?: 0
            if (ahead > 0) AssistChip(onClick = {}, enabled = false, label = { Text("↑$ahead") })
            if (behind > 0) AssistChip(onClick = {}, enabled = false, label = { Text("↓$behind") })
            IconButton(onClick = actions.refresh) { Icon(Icons.Filled.Refresh, "Refresh Git") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Git actions") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Fetch") }, { menu = false; actions.fetch() }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                DropdownMenuItem({ Text("Pull…") }, { menu = false; actions.pull() }, leadingIcon = { Icon(Icons.Filled.CloudDownload, null) })
                DropdownMenuItem({ Text("Push…") }, { menu = false; actions.push() }, leadingIcon = { Icon(Icons.Filled.CloudUpload, null) })
                DropdownMenuItem({ Text("Checkout branch…") }, { menu = false; actions.checkout(false) }, leadingIcon = { Icon(Icons.Filled.CallMerge, null) })
                DropdownMenuItem({ Text("Stash + checkout…") }, { menu = false; actions.checkout(true) })
            }
        }
        state.errorMessage?.let { ErrorState(it, onRetry = actions.refresh) }
        if (state.isLoading && state.status == null) return LoadingState(Modifier.fillMaxSize(), "Loading Git status")
        if (state.sessions.isEmpty()) return EmptyState("No workspace sessions", message = "Open a chat in a Git workspace first.")
        if (!state.isGitRepository && !state.isLoading) return EmptyState(
            "Not a Git repository",
            message = state.selectedSession?.workspace ?: "This workspace has no Git metadata.",
        )
        val totals = state.status?.totals
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "${state.status?.changedCount ?: 0} changed",
                "${totals?.staged ?: 0} staged",
                "${totals?.conflicts ?: 0} conflicts",
            ).forEach { label -> AssistChip(onClick = {}, enabled = false, label = { Text(label) }) }
        }
        if (state.selectedPaths.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.selectedPaths.size} selected", Modifier.weight(1f))
                    TextButton(onClick = actions.stage, enabled = !state.isActing) { Text("Stage") }
                    TextButton(onClick = actions.unstage, enabled = !state.isActing) { Text("Unstage") }
                    TextButton(onClick = actions.discard, enabled = !state.isActing) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.files, key = GitFile::id) { file ->
                GitFileRow(
                    file = file,
                    selected = file.displayPath == state.selectedFilePath,
                    checked = file.displayPath in state.selectedPaths,
                    onSelect = actions.selectFile,
                    onToggle = actions.togglePath,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GitFileRow(
    file: GitFile,
    selected: Boolean,
    checked: Boolean,
    onSelect: (GitFile) -> Unit,
    onToggle: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { onSelect(file) },
            onLongClick = { file.displayPath.takeIf(String::isNotBlank)?.let(onToggle) },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { file.displayPath.takeIf(String::isNotBlank)?.let(onToggle) })
            FileTypeIcon(file.displayPath, Modifier.padding(2.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(file.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                file.parentDirectory?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
            if (file.staged == true) Badge { Text("S") }
            Text("+${file.additions ?: 0} −${file.deletions ?: 0}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GitReviewPane(
    state: GitWorkspaceUiState,
    actions: GitWorkspaceActions,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)?,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            Text(
                if (state.reviewFiles.size == 1) state.reviewFiles.first().file.displayPath else "${state.reviewFiles.size} files changed",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        HorizontalDivider()
        ReviewDiffScreen(
            files = state.reviewFiles,
            initialPath = state.selectedFilePath,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = actions.messageChange,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.generate, enabled = !state.isActing) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Text("Generate", Modifier.padding(start = 4.dp))
                }
                Button(onClick = actions.commit, enabled = state.commitMessage.trim().isNotEmpty() && !state.isActing) {
                    Icon(Icons.Filled.Commit, null)
                    Text(if (state.selectedPaths.isEmpty()) "Commit staged" else "Commit selected", Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun GitCheckoutDialog(
    state: GitWorkspaceUiState,
    stashFirst: Boolean,
    onDismiss: () -> Unit,
    onCheckout: (GitCheckoutTarget) -> Unit,
) {
    var selected by remember { mutableStateOf<GitBranchRef?>(null) }
    var newBranch by remember { mutableStateOf("") }
    val local = state.branches?.local.orEmpty()
    val remote = state.branches?.remote.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (stashFirst) "Stash and checkout" else "Checkout branch") },
        text = {
            Column {
                if (stashFirst) Text("Local changes will be stashed before checkout and restored afterward.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(local) { branch -> DropdownMenuItem({ Text(branch.name.orEmpty()) }, { selected = branch }) }
                    items(remote) { branch -> DropdownMenuItem({ Text(branch.name.orEmpty()) }, { selected = branch }) }
                }
                OutlinedTextField(newBranch, { newBranch = it }, label = { Text("New local branch (optional)") }, singleLine = true)
                selected?.name?.let { Text("Selected: $it", fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            Button(
                enabled = selected?.name?.isNotBlank() == true,
                onClick = {
                    val branch = selected ?: return@Button
                    val isRemote = branch in remote
                    onCheckout(
                        GitCheckoutTarget(
                            ref = branch.name.orEmpty(),
                            mode = if (isRemote) GitBranchMode.REMOTE else GitBranchMode.LOCAL,
                            newBranch = newBranch.trim().takeIf(String::isNotEmpty),
                            track = isRemote,
                        ),
                    )
                },
            ) { Text("Checkout") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
