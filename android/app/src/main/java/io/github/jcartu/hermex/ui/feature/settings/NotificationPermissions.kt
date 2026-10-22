package io.github.jcartu.hermex.ui.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jcartu.hermex.MainActivity
import io.github.jcartu.hermex.R
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

    /**
     * Settings' end-to-end check (#874, "Send Test Notification"): posts one
     * local notification on the results channel the foreground run monitor
     * uses (`AgentRunService` creates it), so the user sees exactly how a
     * finished reply would arrive. No-op without the permission.
     */
    @SuppressLint("MissingPermission")
    fun postTestNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        platform.createNotificationChannel(
            NotificationChannel(RESULTS_CHANNEL_ID, "Agent results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Completion, cancellation, and failure notifications"
            },
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RESULTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Test notification")
            .setContentText("This is how a finished reply will reach you.")
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
    }

    /** `AgentRunService.CHANNEL_RESULTS`. */
    private const val RESULTS_CHANNEL_ID = "agent_results"
    private const val TEST_NOTIFICATION_ID = 0x5E57
}
