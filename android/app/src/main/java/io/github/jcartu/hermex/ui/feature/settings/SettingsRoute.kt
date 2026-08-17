package io.github.jcartu.hermex.ui.feature.settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.feature.models.ModelPickerSheet
import io.github.jcartu.hermex.ui.feature.models.ProfilePickerSheet
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import kotlinx.coroutines.launch

private const val UPSTREAM_PIN = "hermes-webui v0.51.85"
private const val PRIVACY_POLICY_URL = "https://www.uzairansar.com/hermes-mobile/privacy"
private const val SUPPORT_URL = "https://www.uzairansar.com/hermes-mobile"

/**
 * Settings screen, port of `SettingsView.swift` minus iOS-only sections (app
 * icon picker, streaming lab, Siri, notifications, Live Activities, multi-server
 * registry). Model/profile picking reuses the shared sheets from
 * `ui/feature/models`. Adaptive: compact = single column with the providers
 * detail pushed in place; medium = two grouped columns split on the hinge;
 * trifold = two columns + a persistent detail pane.
 */
@Composable
fun SettingsRoute(onOpenArchived: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as HermexApplication).container
    }

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    api = container.api,
                    settingsRepository = container.settings,
                    visibilitySettings = VisibilitySettings.create(context.applicationContext),
                    serverConfig = container.serverConfig,
                    authManager = container.authManager,
                )
            }
        },
    )
    val providersViewModel: ProvidersViewModel = viewModel(
        key = "settingsProviders",
        factory = viewModelFactory { initializer { ProvidersViewModel(container.api) } },
    )
    val customHeadersViewModel: CustomHeadersViewModel = viewModel(
        key = "settingsCustomHeaders",
        factory = viewModelFactory {
            initializer { CustomHeadersViewModel(container.customHeaders, container.serverConfig) }
        },
    )
    val customHeadersState by customHeadersViewModel.uiState.collectAsStateWithLifecycle()
    var showCustomHeaders by rememberSaveable { mutableStateOf(false) }

    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current

    // (Re)load the providers list each time its detail opens.
    LaunchedEffect(uiState.isShowingProviders) {
        if (uiState.isShowingProviders) providersViewModel.load()
    }

    when {
        paneCount <= 1 && uiState.isShowingProviders -> {
            BackHandler(onBack = settingsViewModel::closeProviders)
            ProvidersPane(
                viewModel = providersViewModel,
                onClose = settingsViewModel::closeProviders,
                modifier = Modifier.fillMaxSize(),
            )
        }

        paneCount <= 1 -> SettingsColumn(
            settingsViewModel,
            uiState,
            onOpenArchived,
            { showCustomHeaders = true },
            SettingsColumnGroup.ALL,
            Modifier.fillMaxSize(),
        )

        paneCount == 2 -> HingeAwarePaneRow(paneCount = 2) { index, paneModifier ->
            if (uiState.isShowingProviders) {
                when (index) {
                    0 -> SettingsColumn(
                        settingsViewModel, uiState, onOpenArchived, { showCustomHeaders = true },
                        SettingsColumnGroup.ALL, paneModifier,
                    )
                    else -> ProvidersPane(
                        viewModel = providersViewModel,
                        onClose = settingsViewModel::closeProviders,
                        modifier = paneModifier,
                    )
                }
            } else {
                SettingsColumn(
                    settingsViewModel,
                    uiState,
                    onOpenArchived,
                    { showCustomHeaders = true },
                    if (index == 0) SettingsColumnGroup.PREFERENCES else SettingsColumnGroup.SERVER,
                    paneModifier,
                )
            }
        }

        else -> HingeAwarePaneRow(paneCount = 3) { index, paneModifier ->
            when (index) {
                0 -> SettingsColumn(
                    settingsViewModel, uiState, onOpenArchived, { showCustomHeaders = true },
                    SettingsColumnGroup.PREFERENCES, paneModifier,
                )
                1 -> SettingsColumn(
                    settingsViewModel, uiState, onOpenArchived, { showCustomHeaders = true },
                    SettingsColumnGroup.SERVER, paneModifier,
                )
                else -> if (uiState.isShowingProviders) {
                    ProvidersPane(
                        viewModel = providersViewModel,
                        onClose = settingsViewModel::closeProviders,
                        modifier = paneModifier,
                    )
                } else {
                    EmptyState(
                        title = "Nothing selected",
                        message = "Open Providers to inspect the server's model providers here.",
                        modifier = paneModifier,
                    )
                }
            }
        }
    }

    // Shared pickers from ui/feature/models — adaptive presentation built in.
    if (uiState.isShowingModelPicker) {
        ModelPickerSheet(
            onSelect = { option -> settingsViewModel.saveDefaultModel(option.id) },
            onDismiss = settingsViewModel::dismissModelPicker,
            currentModel = uiState.defaultModel,
        )
    }

    if (uiState.isShowingProfilePicker) {
        ProfilePickerSheet(
            onProfileSwitched = settingsViewModel::onDefaultProfileSaved,
            onDismiss = settingsViewModel::dismissProfilePicker,
            currentProfileName = uiState.defaultProfileName,
        )
    }

    if (uiState.isConfirmingSignOut) {
        AlertDialog(
            onDismissRequest = settingsViewModel::cancelSignOut,
            title = { Text("Sign out of this server?") },
            text = {
                Text("You'll return to onboarding and need the server URL and password to sign back in.")
            },
            confirmButton = {
                TextButton(onClick = settingsViewModel::confirmSignOut) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = settingsViewModel::cancelSignOut) { Text("Cancel") }
            },
        )
    }

    if (showCustomHeaders) {
        CustomHeadersDialog(
            state = customHeadersState,
            validate = customHeadersViewModel::validate,
            onSave = customHeadersViewModel::save,
            onDismiss = {
                showCustomHeaders = false
                customHeadersViewModel.consumeSaved()
            },
        )
    }
}

/** Which section cards a column shows; medium+ splits preferences | server. */
private enum class SettingsColumnGroup { ALL, PREFERENCES, SERVER }

@Composable
private fun SettingsColumn(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onOpenArchived: () -> Unit,
    onOpenCustomHeaders: () -> Unit,
    group: SettingsColumnGroup,
    modifier: Modifier = Modifier,
) {
    // Scroll survives pane-count changes: the explicit registry key keeps the
    // state stable even though the composition branch differs per pane count.
    val listState = rememberSaveable(saver = LazyListState.Saver, key = "settingsList-$group") {
        LazyListState()
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            if (group != SettingsColumnGroup.SERVER) {
                item("appearance") { AppearanceCard(viewModel, uiState) }
                item("interaction") { InteractionCard(viewModel, uiState) }
                item("chat") { ChatCard(viewModel, uiState) }
            }
            if (group != SettingsColumnGroup.PREFERENCES) {
                item("mainPage") { MainPageCard(viewModel, uiState) }
                item("archived") { ArchivedSessionsCard(onOpenArchived) }
                item("activeServer") { ActiveServerCard(viewModel, uiState, onOpenCustomHeaders) }
                item("account") { AccountCard(viewModel, uiState) }
                item("about") { AboutCard() }
            }
        }
    }
}

@Composable
private fun AppearanceCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Appearance") {
        Text(
            "Theme",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            SettingsRepository.Theme.entries.forEachIndexed { index, theme ->
                SegmentedButton(
                    selected = uiState.settings.theme == theme,
                    onClick = { viewModel.setTheme(theme) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SettingsRepository.Theme.entries.size,
                    ),
                ) {
                    Text(
                        when (theme) {
                            SettingsRepository.Theme.SYSTEM -> "System"
                            SettingsRepository.Theme.LIGHT -> "Light"
                            SettingsRepository.Theme.DARK -> "Dark"
                        },
                    )
                }
            }
        }

        SettingsDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("Accent Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                HeaderLogoColor.displayName(uiState.settings.headerLogoColorHex),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            val selectedHex = HeaderLogoColor.normalizedHex(uiState.settings.headerLogoColorHex)
                ?: HeaderLogoColor.defaultHex
            HeaderLogoColor.presets.forEach { preset ->
                HeaderColorSwatch(
                    preset = preset,
                    isSelected = preset.hex == selectedHex,
                    onClick = { viewModel.setHeaderLogoColor(preset.hex) },
                )
            }
        }
        SettingsFootnote("Tints primary actions across the app. The preview updates live.")
    }
}

@Composable
private fun InteractionCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setBackgroundRunNotificationsEnabled(granted)
    }
    SettingsCard(title = "Interaction") {
        SettingsToggleRow(
            title = "Haptic Feedback",
            icon = Icons.Filled.Vibration,
            checked = uiState.settings.hapticsEnabled,
            onCheckedChange = viewModel::setHapticsEnabled,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Background Run Notifications",
            icon = Icons.Filled.NotificationsActive,
            checked = uiState.settings.backgroundRunNotificationsEnabled,
            onCheckedChange = { enabled ->
                if (
                    enabled &&
                    Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.setBackgroundRunNotificationsEnabled(enabled)
                }
            },
        )
        SettingsFootnote("Keeps an active response monitored in the foreground and posts completion or failure.")
    }
}

@Composable
private fun ChatCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Chat") {
        SettingsToggleRow(
            title = "Thinking and Tool Cards",
            icon = Icons.Filled.Psychology,
            checked = uiState.settings.showsThinkingAndToolCards,
            onCheckedChange = viewModel::setShowsThinkingAndToolCards,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Expand Thinking by Default",
            icon = Icons.Filled.UnfoldMore,
            checked = uiState.settings.thinkingCardsStartExpanded,
            onCheckedChange = viewModel::setThinkingCardsStartExpanded,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Expand Tools by Default",
            icon = Icons.Filled.Construction,
            checked = uiState.settings.toolCardsStartExpanded,
            onCheckedChange = viewModel::setToolCardsStartExpanded,
        )
        SettingsFootnote(
            "Thinking and Tool cards start expanded instead of collapsed. Tapping a card still toggles it.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Response Timestamps",
            icon = Icons.Filled.Schedule,
            checked = uiState.settings.showsAssistantTurnTimestamps,
            onCheckedChange = viewModel::setShowsAssistantTurnTimestamps,
        )
        SettingsFootnote(
            "Adds a small marker and the time above each response so back-to-back replies are easier to tell apart.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Response Speed",
            icon = Icons.Filled.Speed,
            checked = uiState.settings.showsResponseSpeed,
            onCheckedChange = viewModel::setShowsResponseSpeed,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Wrap Code Block Lines",
            icon = Icons.Filled.WrapText,
            checked = uiState.settings.wrapsCodeBlockLines,
            onCheckedChange = viewModel::setWrapsCodeBlockLines,
        )
        SettingsFootnote(
            "Wraps long lines in code blocks to fit the screen instead of scrolling sideways. You can also tap the wrap button in any code block.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Right-to-Left Chat Layout",
            icon = Icons.AutoMirrored.Filled.FormatTextdirectionRToL,
            checked = uiState.settings.rtlChatLayoutEnabled,
            onCheckedChange = viewModel::setRtlChatLayoutEnabled,
        )
        SettingsFootnote(
            "Lays out messages and the composer right-to-left for Arabic, Hebrew, Persian, and Urdu. Code, math, tables, and tool output stay left-to-right. Other screens are unaffected.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Hide Attachment Paths",
            icon = Icons.Filled.VisibilityOff,
            checked = uiState.settings.hidesAttachmentPaths,
            onCheckedChange = viewModel::setHidesAttachmentPaths,
        )
        SettingsFootnote(
            "Hides the appended file-path line in your sent messages. Attachments still appear as previews, and the server still receives the paths.",
        )
    }
}

@Composable
private fun MainPageCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Main Page") {
        SettingsToggleRow(
            title = "Tasks",
            icon = Icons.Filled.EventNote,
            checked = uiState.visibility.tasks,
            onCheckedChange = { viewModel.setSectionVisible(VisibilitySettings.Section.TASKS, it) },
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Skills",
            icon = Icons.Filled.Bolt,
            checked = uiState.visibility.skills,
            onCheckedChange = { viewModel.setSectionVisible(VisibilitySettings.Section.SKILLS, it) },
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Memory",
            icon = Icons.Filled.Memory,
            checked = uiState.visibility.memory,
            onCheckedChange = { viewModel.setSectionVisible(VisibilitySettings.Section.MEMORY, it) },
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Insights",
            icon = Icons.Filled.BarChart,
            checked = uiState.visibility.insights,
            onCheckedChange = { viewModel.setSectionVisible(VisibilitySettings.Section.INSIGHTS, it) },
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Projects",
            icon = Icons.Filled.Folder,
            checked = uiState.visibility.projects,
            onCheckedChange = { viewModel.setSectionVisible(VisibilitySettings.Section.PROJECTS, it) },
        )
        SettingsFootnote(
            "Turn off the entries you never use to shorten the top of the session list. Each one is the only way into its screen, so turn it back on here when you need it again.",
        )
    }
}

@Composable
private fun ArchivedSessionsCard(onOpenArchived: () -> Unit) {
    SettingsCard(title = "Archived Sessions") {
        SettingsAccessoryRow(
            title = "Archived Sessions",
            icon = Icons.Filled.Archive,
            onClick = onOpenArchived,
        )
    }
}

@Composable
private fun ActiveServerCard(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onOpenCustomHeaders: () -> Unit,
) {
    SettingsCard(title = "Active Server") {
        SettingsInfoRow(
            title = "Server",
            value = uiState.serverBaseUrl.ifEmpty { "Not configured" },
        )
        SettingsDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            SettingsStatusPill(
                label = uiState.serverStatusLabel,
                healthy = uiState.isServerStatusHealthy,
            )
        }
        SettingsDivider()
        SettingsInfoRow(
            title = "Version",
            value = when {
                uiState.isLoadingServerSettings -> "Loading"
                else -> uiState.serverVersion ?: uiState.serverSettingsError ?: "Unknown"
            },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Default Model",
            icon = Icons.Filled.Tune,
            value = uiState.defaultModelLabel,
            onClick = viewModel::openModelPicker,
        )
        uiState.defaultModelSaveError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Default Profile",
            icon = Icons.Filled.AccountCircle,
            value = uiState.defaultProfileLabel,
            onClick = viewModel::openProfilePicker,
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Providers",
            icon = Icons.Filled.Key,
            onClick = viewModel::openProviders,
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Encrypted Custom Headers",
            icon = Icons.Filled.Shield,
            value = "Per server",
            onClick = onOpenCustomHeaders,
        )
    }
}

@Composable
private fun AccountCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Account") {
        SettingsFootnote("Signs out of the active server and returns to onboarding.")
        SettingsButtonRow(
            text = "Sign Out of This Server",
            destructive = true,
            isLoading = uiState.isSigningOut,
            onClick = viewModel::requestSignOut,
        )
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "Unknown"
    }

    SettingsCard(title = "App") {
        SettingsInfoRow(title = "Version", value = appVersion)
        SettingsDivider()
        // Wire-format compatibility pin, see android/AGENTS.md (../UPSTREAM_TESTED_SHA).
        SettingsInfoRow(title = "Tested against", value = UPSTREAM_PIN)
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Privacy Policy",
            icon = Icons.Filled.Shield,
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
            },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Support",
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, SUPPORT_URL.toUri())) },
        )
    }
}
