package io.github.jcartu.hermex.ui.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.core.prefs.ThinkingAndToolsMode
import io.github.jcartu.hermex.core.security.icon
import io.github.jcartu.hermex.core.prefs.NotificationAuthorization
import io.github.jcartu.hermex.ui.components.InAppBrowser
import io.github.jcartu.hermex.ui.theme.Palette

/*
 * The Hermex pages on the Settings index (`SettingsAppPages.swift`,
 * `SettingsNotificationsPage.swift`, `SettingsAboutPage.swift`, #1252). Each
 * applies on this device to every server.
 */

/** iOS `AppConfig` links; the fork's upstream project links stay. */
private const val UPSTREAM_PIN = "hermes-webui v0.51.85"
private const val PRIVACY_POLICY_URL = "https://www.uzairansar.com/hermes-mobile/privacy"
private const val SUPPORT_URL = "https://www.uzairansar.com/hermes-mobile"
private const val MEMBERSHIP_URL = "https://buymeacoffee.com/callmeuzi/membership"
private const val REPORT_PROBLEM_URL = "https://github.com/jcartu/Hermex-Type-J/issues/new/choose"
private const val UPSTREAM_PROJECT_URL = "https://github.com/uzairansaruzi/hermex"
private const val FORK_PROJECT_URL = "https://github.com/jcartu/Hermex-Type-J"

/** Theme, palette, and the accent (the active server's Header Logo Color). */
@Composable
internal fun AppearancePage(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Appearance") {
        Text(
            "Palette",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Palette.entries.forEachIndexed { index, palette ->
                SegmentedButton(
                    selected = uiState.settings.palette == palette,
                    onClick = { viewModel.setPalette(palette) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Palette.entries.size),
                ) {
                    Text(palette.displayName)
                }
            }
        }
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
                    Text(theme.title)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            val selectedHex = HeaderLogoColor.normalizedHex(uiState.settings.headerLogoColorHex)
                ?: uiState.settings.palette.defaultAccentHex
            HeaderLogoColor.presets.forEach { preset ->
                HeaderColorSwatch(
                    preset = preset,
                    isSelected = preset.hex == selectedHex,
                    onClick = { viewModel.setHeaderLogoColor(preset.hex) },
                )
            }
        }
        SettingsFootnote(
            if (uiState.activeServer != null) {
                "Tints primary actions across the app. It is ${uiState.activeServer?.let(ServerIdentity::displayName)}'s header color; each server keeps its own."
            } else {
                "Tints primary actions across the app. The preview updates live."
            },
        )
    }
}

/** How the composer sends and which buttons it shows (`SettingsComposerPage`). */
@Composable
internal fun ComposerPage(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Sending") {
        SettingsToggleRow(
            title = "Dismiss keyboard after sending",
            icon = Icons.Filled.KeyboardHide,
            checked = uiState.settings.dismissKeyboardAfterSend,
            onCheckedChange = viewModel::setDismissKeyboardAfterSend,
        )
        SettingsFootnote("Long-press Send to choose Queue, Steer, or Stop and send for one message.")
    }
    SettingsCard(title = "Controls") {
        SettingsToggleRow(
            title = "Files Button",
            icon = Icons.Filled.Folder,
            checked = uiState.settings.showsFilesButton,
            onCheckedChange = viewModel::setShowsFilesButton,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Git Actions",
            icon = Icons.Filled.Source,
            checked = uiState.settings.showsGitActions,
            onCheckedChange = viewModel::setShowsGitActions,
        )
        SettingsFootnote("These buttons are the only way into Files and Git from a chat; an entry you turn off comes back only from here.")
    }
}

/** How a chat's transcript shows turns, details, and long lines (`SettingsChatPage`). */
@Composable
internal fun ChatPage(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    SettingsCard(title = "Turns") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        ) {
            androidx.compose.material3.Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text("Thinking & Tools", style = MaterialTheme.typography.bodyMedium)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            ThinkingAndToolsMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = uiState.settings.thinkingAndToolsMode == mode,
                    onClick = { viewModel.setThinkingAndToolsMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThinkingAndToolsMode.entries.size),
                ) {
                    Text(mode.title)
                }
            }
        }
        SettingsFootnote("Whether a chat shows thinking and tool cards, and whether they start open. Tapping a card still toggles it.")
        SettingsDivider()
        SettingsToggleRow(
            title = "Fold Finished Turns",
            icon = Icons.Filled.UnfoldLess,
            checked = uiState.settings.foldsSettledTurns,
            onCheckedChange = viewModel::setFoldsSettledTurns,
        )
        SettingsFootnote("Collapses a finished turn behind one row.")
        SettingsDivider()
        SettingsToggleRow(
            title = "Show beginning of completed response",
            icon = Icons.Filled.VerticalAlignTop,
            checked = uiState.settings.readCompletedResponseFromStart,
            onCheckedChange = viewModel::setReadCompletedResponseFromStart,
        )
        SettingsFootnote("Position after a response completes: its beginning, instead of keeping the latest content in view.")
    }
    SettingsCard(title = "Details") {
        SettingsToggleRow(
            title = "Message Timestamps",
            icon = Icons.Filled.Schedule,
            checked = uiState.settings.showsAssistantTurnTimestamps,
            onCheckedChange = viewModel::setShowsAssistantTurnTimestamps,
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
            title = "Hide Attachment Paths",
            icon = Icons.Filled.VisibilityOff,
            checked = uiState.settings.hidesAttachmentPaths,
            onCheckedChange = viewModel::setHidesAttachmentPaths,
        )
        SettingsFootnote("The server still receives them.")
    }
    SettingsCard(title = "Layout") {
        SettingsToggleRow(
            title = "Wrap Code Block Lines",
            icon = Icons.Filled.WrapText,
            checked = uiState.settings.wrapsCodeBlockLines,
            onCheckedChange = viewModel::setWrapsCodeBlockLines,
        )
        SettingsFootnote("Or tap wrap in any code block.")
        SettingsDivider()
        SettingsToggleRow(
            title = "Right-to-Left Chat Layout",
            icon = Icons.AutoMirrored.Filled.FormatTextdirectionRToL,
            checked = uiState.settings.rtlChatLayoutEnabled,
            onCheckedChange = viewModel::setRtlChatLayoutEnabled,
        )
        SettingsFootnote("Messages and the composer only; code stays left-to-right.")
    }
}

/**
 * Notifications (`SettingsNotificationsPage.swift`): the local alerts the
 * foreground run monitor posts, per kind, a Send Test Notification button, and
 * the OS permission line. Push through the hermex-push relay is iOS-only
 * (APNs); the fork's local notifications are the Android equivalent.
 */
@Composable
internal fun NotificationsPage(viewModel: SettingsViewModel, uiState: SettingsUiState, settings: SettingsRepository) {
    val context = LocalContext.current
    val enabler = rememberLocalAlertsEnabler(settings, uiState.settings)
    val alertsOn = uiState.settings.backgroundRunNotificationsEnabled

    SettingsCard(title = "On this device · All servers") {
        SettingsToggleRow(
            title = "Response Complete Alerts",
            icon = Icons.Filled.NotificationsActive,
            checked = alertsOn,
            onCheckedChange = enabler::setEnabled,
        )
        SettingsFootnote(
            enabler.message
                ?: "Keeps an active response monitored in the foreground and posts completion or failure. " +
                NotificationPermissions.permissionLabel(enabler.authorization),
        )
    }
    SettingsCard(title = "Alerts") {
        SettingsToggleRow(
            title = "Reply Notifications",
            icon = Icons.Filled.Reply,
            checked = uiState.settings.notifyReplies,
            enabled = alertsOn,
            onCheckedChange = viewModel::setNotifyReplies,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Approval Notifications",
            icon = Icons.Filled.Shield,
            checked = uiState.settings.notifyApprovals,
            enabled = alertsOn,
            onCheckedChange = viewModel::setNotifyApprovals,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Question Notifications",
            icon = Icons.Filled.QuestionAnswer,
            checked = uiState.settings.notifyQuestions,
            enabled = alertsOn,
            onCheckedChange = viewModel::setNotifyQuestions,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Failed Run Notifications",
            icon = Icons.Filled.ReportProblem,
            checked = uiState.settings.notifyFailedRuns,
            enabled = alertsOn,
            onCheckedChange = viewModel::setNotifyFailedRuns,
        )
        SettingsFootnote("Which alerts a monitored run posts. Approval and question alerts arrive while a reply is still running.")
    }
    SettingsCard(title = "Test") {
        SettingsButtonRow(
            text = "Send Test Notification",
            enabled = alertsOn && enabler.authorization == NotificationAuthorization.GRANTED,
            onClick = { NotificationPermissions.postTestNotification(context) },
        )
        SettingsFootnote("Posts one local notification now; doesn't test your server.")
    }
    SettingsCard(title = "System") {
        SettingsAccessoryRow(
            title = "Notification settings",
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { NotificationPermissions.openSystemSettings(context) },
        )
    }
}

/** Haptics and the app lock (`SettingsGeneralPage` + `AppLockSection`, #885). */
@Composable
internal fun GeneralPage(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // The screen lock or biometrics may have changed in device Settings.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshAppLockCapability() }
    }
    val lock = uiState.appLock
    val capability = lock.capability

    SettingsCard(title = "Interaction") {
        SettingsToggleRow(
            title = "Haptic Feedback",
            icon = Icons.Filled.Vibration,
            checked = uiState.settings.hapticsEnabled,
            onCheckedChange = viewModel::setHapticsEnabled,
        )
    }
    SettingsCard(title = "Privacy") {
        SettingsToggleRow(
            title = capability.method.settingsTitle,
            icon = capability.method.icon,
            checked = lock.isEnabled,
            // Without a screen lock it can still be turned off: nothing could unlock it anyway.
            enabled = !lock.isAuthenticating && (capability.hasPasscode || lock.isEnabled),
            onCheckedChange = viewModel::setAppLockEnabled,
        )
        SettingsFootnote(
            if (capability.hasPasscode) {
                "Locks Hermex when it returns from the background and hides chats from the app switcher. Notifications and sharing still show content."
            } else {
                "Set a screen lock in device Settings to use this."
            },
        )
    }
}

/** The app's version and its outside links (`SettingsAboutPage`). */
@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
    }
    val appBuild = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
        }.getOrNull() ?: "Unknown"
    }

    SettingsCard(title = "App") {
        SettingsInfoRow(title = "Version", value = appVersion)
        SettingsDivider()
        SettingsInfoRow(title = "Build", value = appBuild)
        SettingsDivider()
        // Wire-format compatibility pin, see android/AGENTS.md (../UPSTREAM_TESTED_SHA).
        SettingsInfoRow(title = "Tested against", value = UPSTREAM_PIN)
    }
    SettingsCard(title = "Links") {
        SettingsAccessoryRow(
            title = "Privacy Policy",
            icon = Icons.Filled.Shield,
            onClick = { InAppBrowser.open(context, PRIVACY_POLICY_URL) },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Support",
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            onClick = { InAppBrowser.open(context, SUPPORT_URL) },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Report a Problem",
            icon = Icons.Filled.BugReport,
            onClick = { InAppBrowser.open(context, REPORT_PROBLEM_URL) },
        )
    }
    SettingsCard(title = "Project") {
        SettingsAccessoryRow(
            title = "Become a supporter",
            icon = Icons.Filled.Favorite,
            value = "memberships",
            onClick = { InAppBrowser.open(context, MEMBERSHIP_URL) },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Hermex (upstream)",
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { InAppBrowser.open(context, UPSTREAM_PROJECT_URL) },
        )
        SettingsDivider()
        SettingsAccessoryRow(
            title = "Hermex-Type-J",
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { InAppBrowser.open(context, FORK_PROJECT_URL) },
        )
    }
}

internal val SettingsRepository.Theme.title: String
    get() = when (this) {
        SettingsRepository.Theme.SYSTEM -> "System"
        SettingsRepository.Theme.LIGHT -> "Light"
        SettingsRepository.Theme.DARK -> "Dark"
    }
