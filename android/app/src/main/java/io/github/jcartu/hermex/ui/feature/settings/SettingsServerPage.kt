package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.feature.models.ModelPickerSheet
import io.github.jcartu.hermex.ui.feature.models.ProfilePickerSheet

/**
 * One server's page (`SettingsServerPage.swift` + `WebuiServerSettings.swift`,
 * #1252): its identity, then its connection and defaults, the Main Page
 * entries, offline data, and the way to switch to it, sign out of it, or
 * remove it. The active webui server's sections load from it; another server
 * shows only what this device saved; a Hermes server renders
 * [HermesServerSettingsSlot] and makes no webui request (#899). Rendered as
 * cards inside the Settings route's scrolling column, so it fits every pane width.
 */
@Composable
internal fun SettingsServerPageContent(
    page: ServerPageViewModel,
    state: ServerPageUiState,
    settingsViewModel: SettingsViewModel,
    settingsState: SettingsUiState,
    onOpenArchived: () -> Unit,
) {
    val account = state.account ?: return

    SettingsCard(title = "Identity") {
        ServerIdentityEditor(
            displayName = state.displayName,
            initials = state.initials,
            colorHex = state.colorHex,
            fallbackName = state.hostFallback,
            onDisplayNameChange = page::setDisplayName,
            onInitialsChange = page::setInitials,
            onColorHexChange = page::setColorHex,
        )
    }

    when {
        account.kind == ServerKind.HERMES -> HermesServerSettingsSlot(account)
        state.loadsFromServer -> WebuiServerCards(page, state, settingsViewModel)
        else -> InactiveServerCard(state)
    }

    if (account.kind == ServerKind.WEBUI) {
        SettingsCard(title = "Sessions") {
            SettingsAccessoryRow(title = "Archived Sessions", icon = Icons.Filled.Archive, onClick = onOpenArchived)
            SettingsDivider()
            SettingsToggleRow(
                title = "Row States",
                icon = Icons.Filled.Visibility,
                checked = settingsState.settings.showsSessionRowStates,
                onCheckedChange = settingsViewModel::setShowsSessionRowStates,
            )
            SettingsFootnote("Shows Approval, Input and Working states on session rows.")
        }
        MainPageCard(settingsViewModel, settingsState)
    }

    if (!state.isActive) {
        SettingsCard(title = "Switch") {
            SettingsButtonRow(text = "Switch to This Server", onClick = page::switchToThisServer)
        }
    }

    // Signing out keeps a Hermes server and shows its sign-in form (#899); a
    // webui server's sign-out removes it, below.
    if (state.isActive && account.kind == ServerKind.HERMES) {
        SettingsCard(title = "Account") {
            SettingsButtonRow(
                text = "Sign Out of This Server",
                destructive = true,
                isLoading = state.isSigningOut,
                onClick = page::requestSignOut,
            )
            SettingsFootnote("Deletes the saved sign-in; the server stays.")
        }
    }

    SettingsCard(title = if (state.signsOut) "Account" else "Remove") {
        SettingsButtonRow(
            text = state.removeButtonTitle,
            destructive = true,
            isLoading = state.isRemoving,
            onClick = page::requestRemove,
        )
        SettingsFootnote(state.removeFootnote)
    }

    if (state.isConfirmingSignOut) {
        AlertDialog(
            onDismissRequest = page::cancelSignOut,
            title = { Text("Sign out of this server?") },
            text = { Text("Deletes this server's saved sign-in and shows its sign-in form.") },
            confirmButton = {
                TextButton(onClick = page::confirmSignOut) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = page::cancelSignOut) { Text("Cancel") } },
        )
    }

    if (state.isConfirmingRemove) {
        AlertDialog(
            onDismissRequest = page::cancelRemove,
            title = { Text(state.removeAlertTitle) },
            text = { Text(state.removeAlertMessage) },
            confirmButton = {
                TextButton(onClick = page::confirmRemove) {
                    Text(state.removeButtonTitle, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = page::cancelRemove) { Text("Cancel") } },
        )
    }

    if (state.isConfirmingClearCache) {
        AlertDialog(
            onDismissRequest = page::cancelClearCache,
            title = { Text("Clear this server's cache?") },
            text = {
                Text("This server's cached sessions and messages will be deleted. Other servers and online server data are not affected.")
            },
            confirmButton = {
                TextButton(onClick = page::confirmClearCache) { Text("Clear Cache", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = page::cancelClearCache) { Text("Cancel") } },
        )
    }

    // Shared pickers from ui/feature/models — adaptive presentation built in.
    if (state.isShowingModelPicker) {
        ModelPickerSheet(
            onSelect = { option -> page.saveDefaultModel(option.id) },
            onDismiss = page::dismissModelPicker,
            currentModel = state.defaultModel,
        )
    }
    if (state.isShowingProfilePicker) {
        ProfilePickerSheet(page::onDefaultProfileSaved, page::dismissProfilePicker, state.defaultProfileName)
    }
}

/** The active webui server's sections: connection, defaults, offline data. */
@Composable
private fun WebuiServerCards(page: ServerPageViewModel, state: ServerPageUiState, settingsViewModel: SettingsViewModel) {
    SettingsCard(title = "Server") {
        SettingsInfoRow(title = "URL", value = state.account?.urlString.orEmpty())
        SettingsDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("Status", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            SettingsStatusPill(label = state.serverStatusLabel, healthy = state.isServerStatusHealthy)
        }
        SettingsDivider()
        SettingsInfoRow(
            title = "Version",
            value = when {
                state.isLoadingServerSettings -> "Loading"
                else -> state.serverVersion ?: state.serverSettingsError ?: "Unknown"
            },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Connection Headers",
            icon = Icons.Filled.Shield,
            value = "Encrypted",
            onClick = { settingsViewModel.openDetail(SettingsDetail.CONNECTION_HEADERS) },
        )
    }
    SettingsCard(title = "Defaults") {
        SettingsAccessoryRow(
            title = "Default Model",
            icon = Icons.Filled.Tune,
            value = state.defaultModelLabel,
            onClick = page::openModelPicker,
        )
        state.defaultModelSaveError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp))
        }
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Default Profile",
            icon = Icons.Filled.AccountCircle,
            value = state.defaultProfileLabel,
            onClick = page::openProfilePicker,
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Providers",
            icon = Icons.Filled.Key,
            onClick = { settingsViewModel.openDetail(SettingsDetail.PROVIDERS) },
        )
    }
    SettingsCard(title = "Offline Data") {
        SettingsButtonRow(
            text = "Clear Offline Cache",
            destructive = true,
            isLoading = state.isClearingCache,
            onClick = page::requestClearCache,
        )
        SettingsFootnote(state.cacheStatusMessage ?: "Clears this server's cache only.")
    }
}

/** A server that isn't active shows what this device saved for it. */
@Composable
private fun InactiveServerCard(state: ServerPageUiState) {
    SettingsCard(title = "Server") {
        SettingsInfoRow(title = "URL", value = state.account?.urlString.orEmpty())
        SettingsDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("Status", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            SettingsStatusPill(label = "Inactive", healthy = false)
        }
        SettingsDivider()
        SettingsInfoRow(title = "Kind", value = if (state.kind == ServerKind.HERMES) "Hermes" else "webui (Legacy)")
    }
}

/** Main Page configures a webui server's home: the session-list entries (#189). */
@Composable
private fun MainPageCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Main Page") {
        SettingsToggleRow("Tasks", Icons.Filled.EventNote, uiState.settings.showsTasksEntry, onCheckedChange = viewModel::setShowsTasksEntry)
        SettingsDivider()
        SettingsToggleRow("Kanban", Icons.Filled.ViewKanban, uiState.settings.showsKanbanEntry, onCheckedChange = viewModel::setShowsKanbanEntry)
        SettingsDivider()
        SettingsToggleRow("Skills", Icons.Filled.Bolt, uiState.settings.showsSkillsEntry, onCheckedChange = viewModel::setShowsSkillsEntry)
        SettingsDivider()
        SettingsToggleRow("Memory", Icons.Filled.Memory, uiState.settings.showsMemoryEntry, onCheckedChange = viewModel::setShowsMemoryEntry)
        SettingsDivider()
        SettingsToggleRow("Usage", Icons.Filled.BarChart, uiState.settings.showsInsightsEntry, onCheckedChange = viewModel::setShowsInsightsEntry)
        SettingsDivider()
        SettingsToggleRow("Active Profile", Icons.Filled.Person, uiState.settings.showsActiveProfileEntry, onCheckedChange = viewModel::setShowsActiveProfileEntry)
        SettingsDivider()
        SettingsToggleRow("Projects", Icons.Filled.Folder, uiState.settings.showsProjectsEntry, onCheckedChange = viewModel::setShowsProjectsEntry)
        SettingsFootnote("An entry you turn off comes back only from here.")
    }
}
