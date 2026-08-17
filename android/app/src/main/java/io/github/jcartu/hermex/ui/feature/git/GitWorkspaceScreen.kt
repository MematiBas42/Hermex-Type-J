package io.github.jcartu.hermex.ui.feature.git

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
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
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

private sealed interface GitConfirmation {
    data object Pull : GitConfirmation
    data object Push : GitConfirmation
    data object Discard : GitConfirmation
    data object Commit : GitConfirmation
}

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
    val paneCount = LocalPaneCount.current
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

    val sessionsPane: @Composable (Modifier) -> Unit = { modifier ->
        GitSessionsPane(state, viewModel::selectSession, modifier)
    }
    val statusPane: @Composable (Modifier) -> Unit = { modifier ->
        GitStatusPane(
            state = state,
            onRefresh = viewModel::refresh,
            onFetch = viewModel::fetch,
            onPull = { confirmation = GitConfirmation.Pull },
            onPush = { confirmation = GitConfirmation.Push },
            onCheckout = { stash -> stashCheckout = stash; showCheckout = true },
            onSelectFile = viewModel::selectFile,
            onTogglePath = viewModel::togglePath,
            onStage = viewModel::stageSelected,
            onUnstage = viewModel::unstageSelected,
            onDiscard = { confirmation = GitConfirmation.Discard },
            modifier = modifier,
        )
    }
    val diffPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { modifier, onBack ->
        GitDiffPane(
            state = state,
            onBack = onBack,
            onDiffKind = { kind ->
                state.files.firstOrNull { it.path == state.selectedFilePath }?.let { viewModel.selectFile(it, kind) }
            },
            onMessageChange = viewModel::setCommitMessage,
            onGenerate = viewModel::generateCommitMessage,
            onCommit = { confirmation = GitConfirmation.Commit },
            modifier = modifier,
        )
    }

    when {
        paneCount == 1 && state.selectedFilePath != null -> {
            BackHandler(onBack = viewModel::closeDiff)
            diffPane(Modifier.fillMaxSize(), viewModel::closeDiff)
        }
        paneCount == 1 -> statusPane(Modifier.fillMaxSize())
        paneCount == 2 -> HingeAwarePaneRow(2) { index, modifier ->
            if (index == 0) statusPane(modifier) else diffPane(modifier, null)
        }
        else -> HingeAwarePaneRow(3) { index, modifier ->
            when (index) { 0 -> sessionsPane(modifier); 1 -> statusPane(modifier); else -> diffPane(modifier, null) }
        }
    }

    confirmation?.let { action ->
        val (title, body) = when (action) {
            GitConfirmation.Pull -> "Pull remote changes" to "This can merge remote work into the selected workspace. Continue?"
            GitConfirmation.Push -> "Push commits" to "Push the current branch to its configured upstream?"
            GitConfirmation.Discard -> "Discard selected changes" to "This permanently discards ${state.selectedPaths.size} selected path(s). Untracked files will be deleted."
            GitConfirmation.Commit -> "Create commit" to "Commit the ${if (state.selectedPaths.isEmpty()) "staged" else "selected"} changes with this message?"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) }, text = { Text(body) },
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

    if (showCheckout) GitCheckoutDialog(
        state = state,
        stashFirst = stashCheckout,
        onDismiss = { showCheckout = false },
        onCheckout = { viewModel.checkout(it, stashCheckout); showCheckout = false },
    )
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
    onRefresh: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onCheckout: (Boolean) -> Unit,
    onSelectFile: (GitFile) -> Unit,
    onTogglePath: (String) -> Unit,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
    onDiscard: () -> Unit,
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
                Text(
                    state.selectedSession?.workspace ?: "Choose a workspace",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val ahead = state.status?.ahead ?: 0; val behind = state.status?.behind ?: 0
            if (ahead > 0) AssistChip(onClick = {}, enabled = false, label = { Text("↑$ahead") })
            if (behind > 0) AssistChip(onClick = {}, enabled = false, label = { Text("↓$behind") })
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh Git") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Git actions") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Fetch") }, { menu = false; onFetch() }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                DropdownMenuItem({ Text("Pull…") }, { menu = false; onPull() }, leadingIcon = { Icon(Icons.Filled.CloudDownload, null) })
                DropdownMenuItem({ Text("Push…") }, { menu = false; onPush() }, leadingIcon = { Icon(Icons.Filled.CloudUpload, null) })
                DropdownMenuItem({ Text("Checkout branch…") }, { menu = false; onCheckout(false) }, leadingIcon = { Icon(Icons.Filled.CallMerge, null) })
                DropdownMenuItem({ Text("Stash + checkout…") }, { menu = false; onCheckout(true) })
            }
        }
        state.errorMessage?.let { ErrorState(it, onRetry = onRefresh) }
        if (state.isLoading && state.status == null) return LoadingState(Modifier.fillMaxSize(), "Loading Git status")
        if (state.sessions.isEmpty()) return EmptyState("No workspace sessions", message = "Open a chat in a Git workspace first.")
        if (!state.isGitRepository && !state.isLoading) return EmptyState(
            "Not a Git repository",
            message = state.selectedSession?.workspace ?: "This workspace has no Git metadata.",
        )

        val totals = state.status?.totals
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "${totals?.changed ?: 0} changed",
                "${totals?.staged ?: 0} staged",
                "${totals?.conflicts ?: 0} conflicts",
            ).forEach { label ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(label, maxLines = 1, softWrap = false) },
                )
            }
        }
        if (state.selectedPaths.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.selectedPaths.size} selected", Modifier.weight(1f))
                    TextButton(onClick = onStage) { Text("Stage") }
                    TextButton(onClick = onUnstage) { Text("Unstage") }
                    TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.files, key = { it.id }) { file ->
                GitFileRow(file, file.path == state.selectedFilePath, file.path in state.selectedPaths, onSelectFile, onTogglePath)
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
            onLongClick = { file.path?.let(onToggle) },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { file.path?.let(onToggle) })
            Icon(Icons.Filled.Folder, null, tint = changeColor(file.changeKind))
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
private fun GitDiffPane(
    state: GitWorkspaceUiState,
    onBack: (() -> Unit)?,
    onDiffKind: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            Text(state.selectedFilePath ?: "Diff & commit", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            if (state.selectedFilePath != null) {
                FilterChip(state.selectedDiffKind == "unstaged", { onDiffKind("unstaged") }, { Text("Unstaged") })
                FilterChip(state.selectedDiffKind == "staged", { onDiffKind("staged") }, { Text("Staged") })
            }
        }
        HorizontalDivider()
        if (state.isLoadingDiff) CircularProgressIndicator(Modifier.padding(16.dp))
        val diff = state.diff
        if (diff != null) {
            when {
                diff.binary == true -> EmptyState("Binary file", modifier = Modifier.weight(1f), message = "A text diff is not available.")
                diff.tooLarge == true -> EmptyState("Diff too large", modifier = Modifier.weight(1f), message = "The server's 512 KiB preview limit was exceeded.")
                else -> SelectionContainer {
                    Text(
                        diff.diff.orEmpty().ifEmpty { "No ${state.selectedDiffKind} diff." },
                        style = CodeTextStyle,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        } else {
            EmptyState("Select a changed file", modifier = Modifier.weight(1f), message = "Its staged or unstaged unified diff appears here.")
        }
        HorizontalDivider()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = onMessageChange,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onGenerate, enabled = !state.isActing) { Icon(Icons.Filled.AutoAwesome, null); Text("Generate") }
                Button(onClick = onCommit, enabled = state.commitMessage.trim().isNotEmpty() && !state.isActing) {
                    Icon(Icons.Filled.Commit, null); Text(if (state.selectedPaths.isEmpty()) "Commit staged" else "Commit selected")
                }
            }
            state.actionMessage?.let { Text(it, color = Color(0xFF34C759)) }
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
                    val remoteBranch = branch in remote
                    onCheckout(
                        GitCheckoutTarget(
                            ref = branch.name.orEmpty(),
                            mode = if (remoteBranch) GitBranchMode.REMOTE else GitBranchMode.LOCAL,
                            newBranch = newBranch.trim().takeIf(String::isNotEmpty),
                            track = remoteBranch,
                        ),
                    )
                },
            ) { Text("Checkout") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun changeColor(kind: GitFile.ChangeKind): Color = when (kind) {
    GitFile.ChangeKind.CONFLICT -> Color(0xFFFF453A)
    GitFile.ChangeKind.UNTRACKED -> Color(0xFF64D2FF)
    GitFile.ChangeKind.ADDED -> Color(0xFF34C759)
    GitFile.ChangeKind.DELETED -> Color(0xFFFF453A)
    GitFile.ChangeKind.RENAMED -> Color(0xFFBF5AF2)
    GitFile.ChangeKind.MODIFIED -> Color(0xFFFFC629)
    GitFile.ChangeKind.IGNORED, GitFile.ChangeKind.UNKNOWN -> Color.Gray
}
