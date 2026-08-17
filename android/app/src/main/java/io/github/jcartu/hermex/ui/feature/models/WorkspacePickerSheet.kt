package io.github.jcartu.hermex.ui.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.LoadingState

/**
 * Workspace picker — port of `ComposerWorkspacePickerSheet`
 * (ChatComposerSelectorSheets.swift): free-text path entry with live,
 * debounced suggestions from the server's trusted workspace roots, plus
 * Current / Saved Workspaces / Suggestions sections.
 *
 * Public API (stable — chat/settings agents call this):
 * ```
 * WorkspacePickerSheet(
 *     onSelect = { path -> /* move the session / set the workspace */ },
 *     onDismiss = { showsPicker = false },
 *     selectedWorkspacePath = state.currentWorkspace, // optional
 * )
 * ```
 * A tap reports the path once (accept-once guard) and dismisses. Presentation
 * is adaptive (see [PickerSheetScaffold]).
 */
@Composable
fun WorkspacePickerSheet(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedWorkspacePath: String? = null,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as HermexApplication).container
    val viewModel: WorkspacePickerViewModel = viewModel(
        key = "workspace-picker:$selectedWorkspacePath",
        factory = viewModelFactory {
            initializer {
                WorkspacePickerViewModel(
                    api = container.api,
                    selectedWorkspacePath = selectedWorkspacePath,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PickerSheetScaffold(title = "Choose Workspace", onDismiss = onDismiss) {
        WorkspacePickerContent(
            state = state,
            onPrefixChange = viewModel::updatePrefix,
            onSelect = { path ->
                if (viewModel.accept(path)) {
                    onSelect(path)
                    onDismiss()
                }
            },
        )
    }
}

/** Stateless picker body — separated for previews and reuse without a ViewModel. */
@Composable
internal fun WorkspacePickerContent(
    state: WorkspacePickerUiState,
    onPrefixChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.prefix,
            onValueChange = onPrefixChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Workspace path") },
            supportingText = {
                Text("Suggestions are limited to trusted workspace roots from the server.")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )

        val errorMessage = state.errorMessage
        if (errorMessage != null && (state.savedRows.isNotEmpty() || state.suggestionPaths.isNotEmpty())) {
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        val currentPath = state.effectiveSelectedWorkspacePath
        val savedRows = state.savedRows
        val suggestionPaths = state.suggestionPaths
        val accepted = state.acceptedWorkspacePath != null

        when {
            state.isLoading && savedRows.isEmpty() && suggestionPaths.isEmpty() ->
                LoadingState(label = "Loading workspaces...")

            savedRows.isEmpty() && suggestionPaths.isEmpty() && currentPath.isNullOrEmpty() ->
                EmptyState(
                    title = "No Workspaces",
                    message = "Try typing a path under your home folder or an existing workspace root.",
                )

            else -> LazyColumn(Modifier.fillMaxWidth()) {
                if (!currentPath.isNullOrEmpty()) {
                    item(key = "current-header") { SectionHeader("Current") }
                    item(key = "current:$currentPath") {
                        WorkspaceRowItem(
                            path = currentPath,
                            name = "Current Workspace",
                            isSelected = true,
                            enabled = !accepted,
                            onSelect = onSelect,
                        )
                    }
                }

                if (savedRows.isNotEmpty()) {
                    item(key = "saved-header") { SectionHeader("Saved Workspaces") }
                    items(count = savedRows.size, key = { "saved:${savedRows[it].path}" }) { index ->
                        val row = savedRows[index]
                        WorkspaceRowItem(
                            path = row.path,
                            name = row.name,
                            isSelected = row.path == currentPath,
                            enabled = !accepted,
                            onSelect = onSelect,
                        )
                    }
                }

                if (suggestionPaths.isNotEmpty()) {
                    item(key = "suggestions-header") { SectionHeader("Suggestions") }
                    items(count = suggestionPaths.size, key = { "suggestion:${suggestionPaths[it]}" }) { index ->
                        WorkspaceRowItem(
                            path = suggestionPaths[index],
                            name = null,
                            isSelected = false,
                            enabled = !accepted,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Port of the Swift `workspaceButton` — folder/check icon, display name, full path caption. */
@Composable
private fun WorkspaceRowItem(
    path: String,
    name: String?,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(path) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (!name.isNullOrEmpty()) name else lastPathComponentFallback(path),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
