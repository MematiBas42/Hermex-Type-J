package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.ui.feature.models.ModelPickerSheet
import io.github.jcartu.hermex.ui.feature.models.ProviderGlyph
import io.github.jcartu.hermex.ui.feature.models.ReasoningEffortOption
import io.github.jcartu.hermex.ui.feature.models.WorkspacePickerSheet

internal enum class ChatComposerSelectorSheet { MODEL, PROFILE, WORKSPACE }

/** Compact, horizontally scrollable composer configuration controls. */
@Composable
internal fun ChatComposerSelectorChips(
    state: ChatUiState,
    enabled: Boolean,
    onOpenModel: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
    profileSelectionEnabled: Boolean = true,
    trailingContent: @Composable () -> Unit = {},
) {
    var modelEffortMenuOpen by remember { mutableStateOf(false) }
    val selectedEffortTitle = state.reasoningEffort
        ?.takeIf { state.showsReasoningEffortControl }
        ?.let(ReasoningEffortOption::titleFor)
    val modelEffortTitle = listOfNotNull(state.selectedModelTitle, selectedEffortTitle)
        .joinToString(" · ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.03f to Color.Black,
                        0.97f to Color.Black,
                        1.0f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { modelEffortMenuOpen = true },
                enabled = enabled,
                label = {
                    Text(
                        text = modelEffortTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    ProviderGlyph(
                        providerId = state.selectedModelProviderId,
                        modifier = Modifier.size(20.dp),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Choose model or reasoning effort",
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            DropdownMenu(
                expanded = modelEffortMenuOpen,
                onDismissRequest = { modelEffortMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Choose model…") },
                    leadingIcon = {
                        ProviderGlyph(
                            providerId = state.selectedModelProviderId,
                            modifier = Modifier.size(20.dp),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        modelEffortMenuOpen = false
                        onOpenModel()
                    },
                )
                if (state.showsReasoningEffortControl) {
                    ReasoningEffortOption.optionsForSupportedEfforts(state.supportedReasoningEfforts)
                        .forEach { option ->
                            DropdownMenuItem(
                                text = { Text("Reasoning: ${option.title}") },
                                leadingIcon = {
                                    if (state.reasoningEffort == option.id) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                                onClick = {
                                    modelEffortMenuOpen = false
                                    onSelectReasoningEffort(option.id)
                                },
                            )
                        }
                }
            }
        }

        AssistChip(
            onClick = onOpenProfile,
            enabled = enabled && profileSelectionEnabled,
            label = {
                Text(
                    text = state.selectedProfileTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )

        AssistChip(
            onClick = onOpenWorkspace,
            enabled = enabled,
            label = {
                Text(
                    text = workspaceTitle(state),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )

        trailingContent()
    }
}

/**
 * Composer selector presentation. Model/workspace behavior is shared with the
 * models feature; profile selection remains chat-local so ChatViewModel can
 * preserve or create the session as required.
 */
@Composable
internal fun ChatComposerSelectorSheets(
    state: ChatUiState,
    actions: ChatComposerActions,
    activeSheet: ChatComposerSelectorSheet?,
    onDismiss: () -> Unit,
    onSelectWorkspace: ((String) -> Unit)? = null,
) {
    var pendingProfile by remember { mutableStateOf<ProfileSummary?>(null) }

    LaunchedEffect(activeSheet) {
        if (activeSheet == ChatComposerSelectorSheet.MODEL) {
            actions.refreshModelCatalogForPickerOpen()
        }
    }

    when (activeSheet) {
        ChatComposerSelectorSheet.MODEL -> ModelPickerSheet(
            onSelect = actions::selectModel,
            onDismiss = onDismiss,
            currentModel = state.selectedModelId,
            currentModelProvider = state.selectedModelProviderId,
            initialGroups = state.modelCatalogGroups.takeIf { it.isNotEmpty() },
        )

        ChatComposerSelectorSheet.WORKSPACE -> WorkspacePickerSheet(
            onSelect = { path ->
                if (onSelectWorkspace != null) onSelectWorkspace(path) else actions.selectWorkspace(path)
            },
            onDismiss = onDismiss,
            selectedWorkspacePath = state.selectedWorkspacePath,
        )

        ChatComposerSelectorSheet.PROFILE -> ChatProfilePickerSheet(
            profiles = state.profiles,
            selectedProfileTitle = state.selectedProfileTitle,
            onSelect = { profile ->
                if (isSelectedProfile(profile, state.selectedProfileTitle)) {
                    onDismiss()
                } else if (state.messages.isEmpty()) {
                    actions.switchProfile(profile, false)
                    onDismiss()
                } else {
                    pendingProfile = profile
                    onDismiss()
                }
            },
            onDismiss = onDismiss,
        )

        null -> Unit
    }

    pendingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingProfile = null },
            title = { Text("Start New Session?") },
            text = {
                Text(
                    "Switch to ${profile.displayName} and start a new session. " +
                        "This keeps the current transcript on its original profile.",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingProfile = null }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingProfile = null
                        actions.switchProfile(profile, true)
                    },
                ) {
                    Text("Start New Session")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatProfilePickerSheet(
    profiles: List<ProfileSummary>,
    selectedProfileTitle: String,
    onSelect: (ProfileSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Choose Profile",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close profile picker")
                }
            }

            HorizontalDivider()

            if (profiles.isEmpty()) {
                Text(
                    text = "No profiles available",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(profiles, key = ProfileSummary::id) { profile ->
                        val selected = isSelectedProfile(profile, selectedProfileTitle)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(profile) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            ) {
                                Text(
                                    text = profile.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                profileSubtitle(profile)?.let { subtitle ->
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Active profile",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = "Switch",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    item { Spacer(Modifier.padding(bottom = 12.dp)) }
                }
            }
        }
    }
}

private fun workspaceTitle(state: ChatUiState): String {
    val selectedPath = state.selectedWorkspacePath?.trim().orEmpty()
    if (selectedPath.isEmpty()) return "Workspace"

    state.workspaceRoots.firstOrNull { it.path == selectedPath }
        ?.name
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

    return selectedPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { selectedPath }
}

private fun profileSubtitle(profile: ProfileSummary): String? {
    val model = profile.model?.trim()?.takeIf(String::isNotEmpty)
    val provider = profile.provider?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(model, provider).joinToString(" · ").takeIf(String::isNotEmpty)
}

private fun isSelectedProfile(profile: ProfileSummary, selectedProfileTitle: String): Boolean {
    if (profile.isActive == true) return true
    return profile.displayName.equals(selectedProfileTitle, ignoreCase = true)
}
