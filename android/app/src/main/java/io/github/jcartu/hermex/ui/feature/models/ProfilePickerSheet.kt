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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState

/**
 * Profile picker — port of `DefaultProfilePickerView.swift` (search, badges
 * for the active/default profile, model/provider/skills details, switch on
 * tap via `POST /api/profiles/switch`).
 *
 * Public API (stable — chat/settings agents call this):
 * ```
 * ProfilePickerSheet(
 *     onProfileSwitched = { selection -> /* selection.name / .displayName / .defaultModel */ },
 *     onDismiss = { showsPicker = false },
 *     currentProfileName = state.currentProfile, // optional
 * )
 * ```
 * The sheet switches the profile itself and dismisses after reporting the
 * [DefaultProfileSelection]. Presentation is adaptive (see [PickerSheetScaffold]).
 */
@Composable
fun ProfilePickerSheet(
    onProfileSwitched: (DefaultProfileSelection) -> Unit,
    onDismiss: () -> Unit,
    currentProfileName: String? = null,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as HermexApplication).container
    val viewModel: ProfilePickerViewModel = viewModel(
        key = "profile-picker:$currentProfileName",
        factory = viewModelFactory {
            initializer {
                ProfilePickerViewModel(api = container.api, currentProfileName = currentProfileName)
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val completed = state.completedSelection
    LaunchedEffect(completed) {
        if (completed != null) {
            viewModel.consumeCompletedSelection()
            onProfileSwitched(completed)
            onDismiss()
        }
    }

    PickerSheetScaffold(title = "Choose Profile", onDismiss = onDismiss) {
        ProfilePickerContent(
            state = state,
            onSearchTextChange = viewModel::updateSearchText,
            onRetry = viewModel::loadProfiles,
            onSelect = viewModel::switchTo,
        )
    }
}

/** Stateless picker body — separated for previews and reuse without a ViewModel. */
@Composable
internal fun ProfilePickerContent(
    state: ProfilePickerUiState,
    onSearchTextChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSelect: (ProfileSummary) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search profiles") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )

        val saveError = state.saveError
        if (saveError != null) {
            Text(
                saveError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        when {
            state.isLoading && state.profiles.isEmpty() ->
                LoadingState(label = "Loading profiles...")

            state.errorMessage != null && state.profiles.isEmpty() ->
                ErrorState(message = state.errorMessage, onRetry = onRetry)

            state.filteredProfiles.isEmpty() ->
                EmptyState(
                    title = "No Matching Profiles",
                    message = "Try a different profile name.",
                )

            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(
                    count = state.filteredProfiles.size,
                    key = { index -> state.filteredProfiles[index].id },
                ) { index ->
                    val profile = state.filteredProfiles[index]
                    ProfileRow(
                        profile = profile,
                        isSelected = state.isSelected(profile),
                        isSaving = state.isSaving && state.selectedProfileName == profile.normalizedName,
                        enabled = !state.isSaving && profile.normalizedName != null,
                        onSelect = { onSelect(profile) },
                    )
                    if (index < state.filteredProfiles.size - 1) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

/** Port of the Swift `profileRow`: name + status badge + details + trailing progress/check. */
@Composable
private fun ProfileRow(
    profile: ProfileSummary,
    isSelected: Boolean,
    isSaving: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    profile.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSelected) {
                    ProfileStatusBadge("Selected")
                } else if (profile.isDefault == true) {
                    ProfileStatusBadge("Server Default")
                }
            }
            val details = profileDetails(profile)
            if (details != null) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (isSelected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProfileStatusBadge(title: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
