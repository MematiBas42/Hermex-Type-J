package io.github.jcartu.hermex.ui.feature.git.hermes

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.net.hermes.HermesGitClient
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.git.GitCheckoutDialog
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceActions
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Repository client from the last Hermes chat whose Git button was opened. */
object HermesGitNavigation {
    private val mutableClient = MutableStateFlow<HermesGitClient?>(null)
    val client: StateFlow<HermesGitClient?> = mutableClient.asStateFlow()

    fun open(client: HermesGitClient) {
        mutableClient.value = client
    }

    fun clear(serverId: String? = null) {
        if (serverId == null || mutableClient.value?.context?.server == serverId) mutableClient.value = null
    }
}

private sealed interface HermesGitConfirmation {
    data object Pull : HermesGitConfirmation
    data object Push : HermesGitConfirmation
    data object Discard : HermesGitConfirmation
    data object Commit : HermesGitConfirmation
}

/** Direct-Hermes Git route reusing the Wave 1 status/review panes. */
@Composable
fun HermesGitWorkspaceRoute(client: HermesGitClient) {
    val viewModel: HermesGitWorkspaceViewModel = viewModel(
        key = "hermes-git:${client.context.server}:${client.context.profile}:${client.context.storedKey}:${client.context.cwd}",
        factory = viewModelFactory { initializer { HermesGitWorkspaceViewModel(client) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<HermesGitConfirmation?>(null) }
    var showCheckout by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.load() }

    val actions = GitWorkspaceActions(
        selectSession = viewModel::selectSession,
        refresh = viewModel::refresh,
        fetch = viewModel::fetch,
        pull = { confirmation = HermesGitConfirmation.Pull },
        push = { confirmation = HermesGitConfirmation.Push },
        checkout = { showCheckout = true },
        selectFile = viewModel::selectFile,
        closeReview = viewModel::closeDiff,
        togglePath = viewModel::togglePath,
        stage = viewModel::stageSelected,
        unstage = viewModel::unstageSelected,
        discard = { confirmation = HermesGitConfirmation.Discard },
        messageChange = viewModel::setCommitMessage,
        generate = viewModel::generateCommitMessage,
        commit = { confirmation = HermesGitConfirmation.Commit },
        dismissMessage = viewModel::dismissMessage,
    )
    GitWorkspaceScreen(state = state, paneCount = LocalPaneCount.current, actions = actions)

    confirmation?.let { action ->
        val (title, body) = when (action) {
            HermesGitConfirmation.Pull -> "Pull remote changes" to "Fast-forward this repository from its upstream?"
            HermesGitConfirmation.Push -> "Push commits" to "Push the current branch to its configured upstream?"
            HermesGitConfirmation.Discard -> "Discard selected changes" to
                "This permanently discards ${state.selectedPaths.size} selected path(s). Untracked files will be deleted."
            HermesGitConfirmation.Commit -> "Create commit" to
                "Commit the ${if (state.selectedPaths.isEmpty()) "staged" else "selected"} changes with this message?"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    when (action) {
                        HermesGitConfirmation.Pull -> viewModel.pull()
                        HermesGitConfirmation.Push -> viewModel.push()
                        HermesGitConfirmation.Discard -> viewModel.discardSelected(deleteUntracked = true)
                        HermesGitConfirmation.Commit -> viewModel.commit()
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("Cancel") }
            },
        )
    }

    if (showCheckout) {
        GitCheckoutDialog(
            state = state,
            stashFirst = false,
            onDismiss = { showCheckout = false },
            onCheckout = {
                showCheckout = false
                viewModel.checkout(it)
            },
        )
    }
}
