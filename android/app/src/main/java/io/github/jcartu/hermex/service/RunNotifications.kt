package io.github.jcartu.hermex.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jcartu.hermex.MainActivity
import io.github.jcartu.hermex.R
import io.github.jcartu.hermex.core.prefs.NotificationAuthorization
import io.github.jcartu.hermex.core.prefs.NotificationOffer
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.ui.feature.settings.NotificationPermissions
import kotlinx.coroutines.flow.first

/** Android local equivalent of the reply/approval/question/failure alert kinds in `Push` and `Config/AppTheme.swift`. */
enum class RunNotificationKind(val channelId: String, val channelName: String, val importance: Int, val id: Int) {
    // Keep the existing results channel id: upgraded installs retain the OS setting for replies.
    REPLY("agent_results", "Replies", NotificationManager.IMPORTANCE_DEFAULT, 1),
    APPROVAL("agent_approvals", "Approvals", NotificationManager.IMPORTANCE_HIGH, 2),
    QUESTION("agent_questions", "Questions", NotificationManager.IMPORTANCE_HIGH, 3),
    FAILED_RUN("agent_failed_runs", "Failed runs", NotificationManager.IMPORTANCE_DEFAULT, 4),
}

enum class RunNotificationOutcome { COMPLETED, CANCELLED, FAILED }
enum class RunNotificationResult { POSTED, DISABLED, KIND_DISABLED, PERMISSION_DENIED, CHANNEL_DISABLED, FOREGROUND, CANCELLED }

/** Port of `ResponseCompletionNotificationPolicy`: never alert for a stopped run or one the user is watching. */
object RunNotificationPolicy {
    fun decision(kind: RunNotificationKind, settings: SettingsRepository.Settings,
                 authorization: NotificationAuthorization, sceneIsActive: Boolean): RunNotificationResult? = when {
        authorization != NotificationAuthorization.GRANTED -> RunNotificationResult.PERMISSION_DENIED
        !settings.backgroundRunNotificationsEnabled -> RunNotificationResult.DISABLED
        !(when (kind) {
            RunNotificationKind.REPLY -> settings.notifyReplies
            RunNotificationKind.APPROVAL -> settings.notifyApprovals
            RunNotificationKind.QUESTION -> settings.notifyQuestions
            RunNotificationKind.FAILED_RUN -> settings.notifyFailedRuns
        }) -> RunNotificationResult.KIND_DISABLED
        sceneIsActive -> RunNotificationResult.FOREGROUND
        else -> null
    }
}

/** Shared channel/build/post path for the background monitor, Hermes hooks and Settings' test button. */
object RunNotifications {
    const val CHANNEL_RUNS = "agent_runs"
    const val TEST_NOTIFICATION_ID = 0x5E57

    fun createChannels(context: Context) {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        platform.createNotificationChannel(NotificationChannel(CHANNEL_RUNS, "Agent runs", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live status while an agent response runs in the background"
            setShowBadge(false)
        })
        for (kind in RunNotificationKind.entries) {
            platform.createNotificationChannel(NotificationChannel(kind.channelId, kind.channelName, kind.importance).apply {
                description = when (kind) {
                    RunNotificationKind.REPLY -> "Completed agent responses"
                    RunNotificationKind.APPROVAL -> "An agent is waiting for your approval"
                    RunNotificationKind.QUESTION -> "An agent is waiting for your answer"
                    RunNotificationKind.FAILED_RUN -> "Agent runs that could not finish"
                }
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            })
        }
    }

    fun notification(context: Context, kind: RunNotificationKind, target: RunNotificationIntent.Target?,
                     title: String, body: String): Notification {
        val intent = target?.let { RunNotificationIntent.contentIntent(context, it) }
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, kind.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, kind.channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title.trim().ifEmpty { "Hermes session" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (kind.importance == NotificationManager.IMPORTANCE_HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (kind == RunNotificationKind.APPROVAL || kind == RunNotificationKind.QUESTION) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, kind.channelId)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Hermex-Type-J")
                .setContentText("Agent run updated").build())
            .build()
    }

    /** Returns why a post was refused, including the OS app switch and a disabled individual channel. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, kind: RunNotificationKind, target: RunNotificationIntent.Target,
             title: String, body: String, settings: SettingsRepository.Settings,
             sceneIsActive: Boolean): RunNotificationResult {
        val authorization = NotificationPermissions.authorization(context, settings.hasRequestedNotificationPermission)
        RunNotificationPolicy.decision(kind, settings, authorization, sceneIsActive)?.let { return it }
        createChannels(context)
        if (context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(kind.channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return RunNotificationResult.CHANNEL_DISABLED
        }
        return try {
            NotificationManagerCompat.from(context).notify(target.notificationTag, kind.id,
                notification(context, kind, target, title, body))
            RunNotificationResult.POSTED
        } catch (_: SecurityException) {
            RunNotificationResult.PERMISSION_DENIED
        }
    }

    fun clear(context: Context, target: RunNotificationIntent.Target) {
        val manager = NotificationManagerCompat.from(context)
        for (kind in RunNotificationKind.entries) manager.cancel(target.notificationTag, kind.id)
    }

    /** Settings' local end-to-end test: the real reply channel/builder, not a separate test-only channel. */
    @SuppressLint("MissingPermission")
    fun sendTestNotification(context: Context): RunNotificationResult {
        if (NotificationPermissions.authorization(context, hasRequestedPermission = true) != NotificationAuthorization.GRANTED) {
            return RunNotificationResult.PERMISSION_DENIED
        }
        createChannels(context)
        val kind = RunNotificationKind.REPLY
        if (context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(kind.channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return RunNotificationResult.CHANNEL_DISABLED
        }
        return try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID,
                notification(context, kind, null, "Test notification", "This is how a finished reply will reach you."))
            RunNotificationResult.POSTED
        } catch (_: SecurityException) {
            RunNotificationResult.PERMISSION_DENIED
        }
    }
}

/**
 * Small shared API for webui and Hermes chats, porting `NotificationOffer.claim`
 * and `ResponseCompletionNotificationService.scheduleRunEndedIfAllowed`.
 * Constructed once in AppContainer. Call from the UI's Main dispatcher after
 * a host event is admitted; the supplied account/session are captured owners,
 * never resolved from whichever server happens to be active at delivery time.
 */
class RunNotificationHooks(
    private val context: Context,
    private val settings: SettingsRepository,
    private val offer: NotificationOffer,
    private val sceneIsActive: () -> Boolean,
) {
    suspend fun runStarted(server: ServerAccount, session: String, title: String, hermesProfile: String? = null): Boolean {
        RunNotifications.clear(context, target(server, session, hermesProfile))
        return offer.claimForRunStart()
    }

    suspend fun runFinished(server: ServerAccount, session: String, title: String,
                            outcome: RunNotificationOutcome, hermesProfile: String? = null): RunNotificationResult {
        if (outcome == RunNotificationOutcome.CANCELLED) return RunNotificationResult.CANCELLED
        val failed = outcome == RunNotificationOutcome.FAILED
        return post(server, session, title, if (failed) "Response failed" else "Response complete",
            if (failed) RunNotificationKind.FAILED_RUN else RunNotificationKind.REPLY, hermesProfile)
    }

    suspend fun approvalNeeded(server: ServerAccount, session: String, title: String,
                               summary: String = "Approval required", hermesProfile: String? = null): RunNotificationResult =
        post(server, session, title, summary, RunNotificationKind.APPROVAL, hermesProfile)

    suspend fun questionAsked(server: ServerAccount, session: String, title: String,
                              question: String = "The agent needs more information before continuing.", hermesProfile: String? = null): RunNotificationResult =
        post(server, session, title, question, RunNotificationKind.QUESTION, hermesProfile)

    private suspend fun post(server: ServerAccount, session: String, title: String, text: String,
                             kind: RunNotificationKind, profile: String?): RunNotificationResult {
        val preferences = settings.settings.first()
        return RunNotifications.post(context, kind, target(server, session, profile), title, text, preferences, sceneIsActive())
    }

    private fun target(server: ServerAccount, session: String, profile: String?) =
        RunNotificationIntent.Target(server.id, session, profile, server.kind)
}
