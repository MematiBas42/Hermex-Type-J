package io.github.jcartu.hermex.ui.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.core.prefs.NotificationAuthorization
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Port of `ResponseCompletionNotificationService.enable` (`Config/AppTheme.swift`)
 * as a Compose hook: turns Response Complete Alerts on, asking Android for
 * `POST_NOTIFICATIONS` only if Hermex never has (API 33+). Settings' toggle and
 * the chat's one-time offer (#863) both use it, so the prompt, the asked-once
 * flag and the stored preference always agree. [authorization] is the current
 * OS state for the line under the toggle; [message] says why the alerts
 * stayed off after the last attempt, null when they are on.
 */
class LocalAlertsEnabler internal constructor(
    val authorization: NotificationAuthorization,
    val message: String?,
    private val enable: () -> Unit,
    private val disable: () -> Unit,
) {
    fun setEnabled(enabled: Boolean) = if (enabled) enable() else disable()
}

@Composable
fun rememberLocalAlertsEnabler(settings: SettingsRepository, current: SettingsRepository.Settings): LocalAlertsEnabler {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authorization by remember {
        mutableStateOf(NotificationPermissions.authorization(context, current.hasRequestedNotificationPermission))
    }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh(hasRequested: Boolean) {
        authorization = NotificationPermissions.authorization(context, hasRequested)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh(hasRequested = true)
        scope.launch {
            settings.setBackgroundRunNotificationsEnabled(granted)
            message = if (granted) null else NotificationPermissions.permissionLabel(authorization)
        }
    }

    return remember(authorization, message, current.hasRequestedNotificationPermission) {
        LocalAlertsEnabler(
            authorization = authorization,
            message = message,
            enable = {
                val now = NotificationPermissions.authorization(context, current.hasRequestedNotificationPermission)
                authorization = now
                when {
                    now == NotificationAuthorization.GRANTED -> scope.launch {
                        settings.setBackgroundRunNotificationsEnabled(true)
                        message = null
                    }
                    now == NotificationAuthorization.NOT_DETERMINED &&
                        NotificationPermissions.needsRuntimeRequest(context) -> scope.launch {
                        settings.setHasRequestedNotificationPermission(true)
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> scope.launch {
                        settings.setBackgroundRunNotificationsEnabled(false)
                        message = NotificationPermissions.permissionLabel(now)
                    }
                }
            },
            disable = {
                scope.launch {
                    settings.setBackgroundRunNotificationsEnabled(false)
                    refresh(current.hasRequestedNotificationPermission)
                    message = null
                }
            },
        )
    }
}

/**
 * Port of `notificationOfferAlert` (`Config/NotificationOffer.swift`, #863):
 * the one-time "Want a notification when it's done?" question the chat claims
 * as a run starts (`NotificationOffer.claimForRunStart`). Only the offer's own
 * button acts; "Not now" changes no setting and no permission. Hosted once at
 * the shell so every chat it holds inherits it.
 */
@Composable
fun NotificationOfferHost(container: AppContainer) {
    val pending by container.notificationOffer.pending.collectAsStateWithLifecycle()
    if (!pending) return
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = SettingsRepository.Settings())
    val enabler = rememberLocalAlertsEnabler(container.settings, settings)

    AlertDialog(
        onDismissRequest = container.notificationOffer::dismiss,
        title = { Text("Want a notification when it’s done?") },
        text = {
            Text(
                "Hermex can alert you when a reply finishes or fails while the app is in the background. You can change this in Settings → Notifications.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    enabler.setEnabled(true)
                    container.notificationOffer.dismiss()
                },
            ) { Text("Notify Me") }
        },
        dismissButton = {
            TextButton(onClick = container.notificationOffer::dismiss) { Text("Not now") }
        },
    )
}
