package io.github.jcartu.hermex.ui.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.jcartu.hermex.core.prefs.NotificationAuthorization

/**
 * The OS side of local alerts — port of the `UNAuthorizationStatus` reads in
 * `ResponseCompletionNotificationService` (`Config/AppTheme.swift`) on
 * Android's permission model: `POST_NOTIFICATIONS` on API 33+ (a runtime
 * permission the app asks for once, remembered in
 * `SettingsRepository.Settings.hasRequestedNotificationPermission`), and the
 * app-level notification switch below it.
 */
object NotificationPermissions {
    fun authorization(context: Context, hasRequestedPermission: Boolean): NotificationAuthorization {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val enabledForApp = notificationManager?.areNotificationsEnabled() ?: true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            return when {
                granted && enabledForApp -> NotificationAuthorization.GRANTED
                hasRequestedPermission || !enabledForApp -> NotificationAuthorization.DENIED
                else -> NotificationAuthorization.NOT_DETERMINED
            }
        }
        return if (enabledForApp) NotificationAuthorization.GRANTED else NotificationAuthorization.DENIED
    }

    /** Whether the runtime permission prompt exists and hasn't been answered yet. */
    fun needsRuntimeRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** Port of `ResponseCompletionNotificationService.permissionLabel`: the line under the toggle. */
    fun permissionLabel(authorization: NotificationAuthorization): String = when (authorization) {
        NotificationAuthorization.GRANTED -> "Android notifications allowed."
        NotificationAuthorization.NOT_DETERMINED -> "Android permission not requested."
        NotificationAuthorization.DENIED -> "Android notifications disabled."
    }

    /** Opens Hermex's notification page in the system Settings (the iOS "Notifications are off" row's action). */
    fun openSystemSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Settings' end-to-end check shares the real reply channel, builder and permission checks with run alerts. */
    fun postTestNotification(context: Context): io.github.jcartu.hermex.service.RunNotificationResult =
        io.github.jcartu.hermex.service.RunNotifications.sendTestNotification(context)
}
