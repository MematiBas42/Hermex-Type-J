package io.github.jcartu.hermex.service.hermes

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.R
import io.github.jcartu.hermex.core.logging.HermexLog
import io.github.jcartu.hermex.core.prefs.BotConnectionRecordStore
import io.github.jcartu.hermex.core.prefs.NotificationAuthorization
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.service.RunNotificationIntent
import io.github.jcartu.hermex.service.RunNotificationKind
import io.github.jcartu.hermex.service.RunNotificationOutcome
import io.github.jcartu.hermex.service.RunNotificationResult
import io.github.jcartu.hermex.service.RunNotifications
import io.github.jcartu.hermex.ui.feature.settings.NotificationPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Every field that makes one direct-Hermes observer the owner of one stored conversation. */
internal data class HermesRunMonitorKey(
    val serverId: String,
    val connectionId: String,
    val profile: String,
    val storedKey: String,
)

internal fun HermesRunTarget.ownershipKey(): HermesRunMonitorKey = HermesRunMonitorKey(
    serverId = serverId,
    connectionId = connectionId,
    profile = profile,
    storedKey = storedKey,
)

/**
 * Builds the captured handoff only for work the current chat already accepted. A new chat has
 * no stored key and therefore cannot accidentally create a profile-wide observer.
 */
internal fun hermesRunTargetForBackground(
    serverId: String,
    connectionId: String,
    profile: String,
    storedKey: String?,
    title: String,
    hasActiveRun: Boolean,
    hasPendingPrompt: Boolean,
): HermesRunTarget? {
    val key = storedKey?.takeIf(String::isNotBlank) ?: return null
    if (!hasActiveRun && !hasPendingPrompt) return null
    if (serverId.isBlank() || connectionId.isBlank() || profile.isBlank()) return null
    return HermesRunTarget(serverId, connectionId, profile, key, title)
}

internal fun HermesRunTarget.notificationTarget(serverKind: ServerKind): RunNotificationIntent.Target =
    RunNotificationIntent.Target(
        serverId = serverId,
        sessionId = storedKey,
        hermesProfile = profile,
        serverKind = serverKind,
    )

internal enum class HermesRunMonitorCommand { START, STOP_OBSERVING, INTERRUPT_HOST }

/** Explicit intent actions keep observation teardown separate from the host interrupt contract. */
internal object HermesRunMonitorActions {
    const val START = "io.github.jcartu.hermex.hermes.run.START"
    const val STOP = "io.github.jcartu.hermex.hermes.run.STOP"
    const val CANCEL = "io.github.jcartu.hermex.hermes.run.CANCEL"

    fun command(action: String?): HermesRunMonitorCommand? = when (action) {
        START -> HermesRunMonitorCommand.START
        STOP -> HermesRunMonitorCommand.STOP_OBSERVING
        CANCEL -> HermesRunMonitorCommand.INTERRUPT_HOST
        else -> null
    }
}

/**
 * Foreground owner for direct-Hermes runs after their chat disconnects. Each monitor is bound to
 * the saved connection record captured by the UI, and uses an own connection so changing the
 * app's active server cannot redirect observation, notification routing, or cancellation.
 */
class HermesRunMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitors = LinkedHashMap<HermesRunMonitorKey, Entry>()
    private val manager by lazy { NotificationManagerCompat.from(this) }
    private val container by lazy { (application as HermexApplication).container }
    private lateinit var recordsPreferences: SharedPreferences
    private var foregroundOwner: HermesRunMonitorKey? = null
    private var currentSettings: SettingsRepository.Settings? = null

    private class Entry(
        val target: HermesRunTarget,
        val key: HermesRunMonitorKey,
        val notificationTarget: RunNotificationIntent.Target,
        val connection: HermesConnection,
        val monitor: HermesRunMonitor,
    ) {
        lateinit var job: Job
        var update = HermesRunMonitorUpdate()
        var approvalAlert: HermesRunPrompt? = null
        var questionAlert: HermesRunPrompt? = null
        var outcomeHandled = false
        var interruptRequested = false
        var stopping = false
    }

    private val recordListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        if (changedKey == null) return@OnSharedPreferenceChangeListener
        scope.launch {
            monitors.values
                .filter { BotConnectionRecordStore.key(it.target.serverId) == changedKey }
                .toList()
                .forEach(::stopIfOwnerChanged)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RunNotifications.createChannels(this)
        recordsPreferences = getSharedPreferences(BotConnectionRecordStore.PREFERENCES_NAME, MODE_PRIVATE)
        recordsPreferences.registerOnSharedPreferenceChangeListener(recordListener)
        scope.launch {
            container.settings.settings.collect { settings ->
                currentSettings = settings
                if (!monitoringAllowed(this@HermesRunMonitorService, settings)) {
                    monitors.values.toList().forEach { stopMonitor(it, clearAlerts = true) }
                } else {
                    monitors.values.toList().forEach { reconcileNotifications(it, settings) }
                }
            }
        }
        scope.launch {
            container.foregroundMonitor.isInForeground.collect { foreground ->
                val settings = currentSettings ?: return@collect
                if (!monitoringAllowed(this@HermesRunMonitorService, settings)) {
                    monitors.values.toList().forEach { stopMonitor(it, clearAlerts = true) }
                } else if (!foreground) {
                    monitors.values.toList().forEach { reconcilePromptAlerts(it, settings) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = HermesRunMonitorActions.command(intent?.action)
        val target = intent?.hermesRunTarget()
        if (command == null || target == null) {
            if (monitors.isEmpty()) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val key = target.ownershipKey()
        when (command) {
            HermesRunMonitorCommand.START -> {
                foregroundClaims.remove(key)
                startMonitor(target)
                return START_REDELIVER_INTENT
            }
            HermesRunMonitorCommand.STOP_OBSERVING -> stopMonitor(monitors[key], clearAlerts = false)
            HermesRunMonitorCommand.INTERRUPT_HOST -> interrupt(monitors[key])
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    /** Android's data-sync budget ends observation only; it never becomes an implicit cancel. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        monitors.values.toList().forEach { stopMonitor(it, clearAlerts = false) }
        if (monitors.isEmpty()) stopSelf(startId)
    }


    override fun onDestroy() {
        recordsPreferences.unregisterOnSharedPreferenceChangeListener(recordListener)
        monitors.values.toList().forEach { entry ->
            entry.stopping = true
            activeTargets.remove(entry.key)
            foregroundClaims.remove(entry.key)
            entry.job.cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitor(target: HermesRunTarget) {
        val key = target.ownershipKey()
        if (key in monitors) return

        val account = container.serverRegistry.servers.firstOrNull { it.id == target.serverId }
        val notificationTarget = target.notificationTarget(account?.kind ?: ServerKind.HERMES)
        val initial = ongoingNotification(target, notificationTarget, HermesRunMonitorUpdate(status = "Connecting"))
        if (foregroundOwner == null) {
            startForeground(notificationId(key), initial)
            foregroundOwner = key
        } else {
            notifyOngoing(notificationId(key), initial)
        }

        val saved = runCatching { container.botConnectionStore.load(target.serverId) }.getOrNull()
        if (saved == null || saved.id != target.connectionId || account == null) {
            manager.cancel(notificationId(key))
            activeTargets.remove(key)
            if (foregroundOwner == key) {
                foregroundOwner = null
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            if (monitors.isEmpty()) stopSelf()
            return
        }

        val connection = container.hermesConnections.ownConnection(saved, target.serverId)
        val monitor = HermesRunMonitor(connection, target)
        val entry = Entry(target, key, notificationTarget, connection, monitor)
        entry.job = scope.launch(start = CoroutineStart.LAZY) {
            val updates = launch {
                monitor.updates.collect { update -> applyUpdate(entry, update) }
            }
            try {
                monitor.run()
                applyUpdate(entry, monitor.updates.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                HermexLog.w("Direct Hermes run observation ended without a host outcome", error)
            } finally {
                withContext(NonCancellable) {
                    updates.cancelAndJoin()
                    try {
                        monitor.close()
                    } catch (error: Throwable) {
                        HermexLog.w("Could not close direct Hermes run observation", error)
                    }
                    container.hermesConnections.retireOwn(connection, target.serverId)
                    removeMonitor(entry)
                }
            }
        }
        monitors[key] = entry
        activeTargets += key
        RunNotifications.clearRunOutcomes(this, entry.notificationTarget)
        entry.job.start()
    }

    private suspend fun applyUpdate(entry: Entry, update: HermesRunMonitorUpdate) {
        if (monitors[entry.key] !== entry || entry.stopping) return
        entry.update = update
        if (!ownsCurrentRecord(entry.target)) {
            stopMonitor(entry, clearAlerts = true)
            return
        }
        val settings = currentSettings ?: container.settings.settings.first().also { currentSettings = it }
        if (!monitoringAllowed(this, settings)) {
            stopMonitor(entry, clearAlerts = true)
            return
        }
        reconcileNotifications(entry, settings)
    }

    private fun stopIfOwnerChanged(entry: Entry) {
        if (!ownsCurrentRecord(entry.target)) stopMonitor(entry, clearAlerts = true)
    }

    private fun ownsCurrentRecord(target: HermesRunTarget): Boolean {
        if (container.serverRegistry.servers.none { it.id == target.serverId }) return false
        return runCatching { container.botConnectionStore.load(target.serverId)?.id == target.connectionId }
            .getOrDefault(false)
    }

    private suspend fun reconcileNotifications(entry: Entry, settings: SettingsRepository.Settings) {
        if (monitors[entry.key] !== entry || entry.stopping) return
        reconcilePromptAlerts(entry, settings)
        reconcileOutcome(entry, settings)
        if (shouldKeepOngoing(entry.update)) {
            val notification = ongoingNotification(entry.target, entry.notificationTarget, entry.update)
            if (foregroundOwner == entry.key) {
                startForeground(notificationId(entry.key), notification)
            } else {
                notifyOngoing(notificationId(entry.key), notification)
            }
        }
    }

    private fun shouldKeepOngoing(update: HermesRunMonitorUpdate): Boolean =
        update.active || update.outcome == null || update.approval != null || update.question != null

    private suspend fun reconcilePromptAlerts(entry: Entry, settings: SettingsRepository.Settings) {
        val target = entry.notificationTarget
        val update = entry.update
        if (update.approval != null) {
            if (!settings.notifyApprovals) {
                entry.approvalAlert = null
                manager.cancel(target.notificationTag, RunNotificationKind.APPROVAL.id)
            } else if (entry.approvalAlert != update.approval) {
                val result = RunNotifications.post(
                    this,
                    RunNotificationKind.APPROVAL,
                    target,
                    entry.target.title,
                    update.approval.summary,
                    settings,
                    sceneIsActive = container.foregroundMonitor.isInForeground.value,
                )
                if (result == RunNotificationResult.POSTED) entry.approvalAlert = update.approval
            }
        } else if (update.approvalsConfirmedClear) {
            entry.approvalAlert = null
            manager.cancel(target.notificationTag, RunNotificationKind.APPROVAL.id)
        }

        if (update.question != null) {
            if (!settings.notifyQuestions) {
                entry.questionAlert = null
                manager.cancel(target.notificationTag, RunNotificationKind.QUESTION.id)
            } else if (entry.questionAlert != update.question) {
                val result = RunNotifications.post(
                    this,
                    RunNotificationKind.QUESTION,
                    target,
                    entry.target.title,
                    update.question.summary,
                    settings,
                    sceneIsActive = container.foregroundMonitor.isInForeground.value,
                )
                if (result == RunNotificationResult.POSTED) entry.questionAlert = update.question
            }
        } else if (update.questionsConfirmedClear) {
            entry.questionAlert = null
            manager.cancel(target.notificationTag, RunNotificationKind.QUESTION.id)
        }
    }

    private fun reconcileOutcome(entry: Entry, settings: SettingsRepository.Settings) {
        if (entry.outcomeHandled) return
        val outcome = entry.update.outcome ?: return
        if (outcome == RunNotificationOutcome.CANCELLED) {
            entry.outcomeHandled = true
            return
        }
        if (
            entry.key in foregroundClaims ||
            container.foregroundMonitor.isInForeground.value
        ) {
            entry.outcomeHandled = true
            return
        }
        val failed = outcome == RunNotificationOutcome.FAILED
        RunNotifications.post(
            this,
            if (failed) RunNotificationKind.FAILED_RUN else RunNotificationKind.REPLY,
            entry.notificationTarget,
            entry.target.title,
            if (failed) "Response failed" else "Response complete",
            settings,
            sceneIsActive = false,
        )
        entry.outcomeHandled = true
    }

    private fun stopMonitor(entry: Entry?, clearAlerts: Boolean) {
        entry ?: return
        if (entry.stopping) return
        entry.stopping = true
        if (clearAlerts) RunNotifications.clear(this, entry.notificationTarget)
        entry.job.cancel()
    }

    private fun interrupt(entry: Entry?) {
        entry ?: return
        if (entry.stopping || entry.interruptRequested) return
        entry.interruptRequested = true
        scope.launch {
            try {
                entry.monitor.interrupt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (monitors[entry.key] === entry && !entry.stopping) {
                    entry.update = entry.update.copy(status = error.message ?: "The host could not stop this run.")
                    val notification = ongoingNotification(entry.target, entry.notificationTarget, entry.update)
                    if (foregroundOwner == entry.key) startForeground(notificationId(entry.key), notification)
                    else notifyOngoing(notificationId(entry.key), notification)
                }
            }
        }
    }

    private fun removeMonitor(entry: Entry) {
        if (monitors[entry.key] !== entry) return
        monitors.remove(entry.key)
        activeTargets.remove(entry.key)
        foregroundClaims.remove(entry.key)
        manager.cancel(notificationId(entry.key))
        if (foregroundOwner == entry.key) {
            foregroundOwner = null
            val next = monitors.values.firstOrNull()
            if (next == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                foregroundOwner = next.key
                startForeground(notificationId(next.key), ongoingNotification(next.target, next.notificationTarget, next.update))
            }
        }
        if (monitors.isEmpty()) stopSelf()
    }

    private fun ongoingNotification(
        target: HermesRunTarget,
        notificationTarget: RunNotificationIntent.Target,
        update: HermesRunMonitorUpdate,
    ): Notification {
        val key = target.ownershipKey()
        val click = PendingIntent.getActivity(
            this,
            notificationId(key),
            RunNotificationIntent.contentIntent(this, notificationTarget),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = update.status.trim().ifEmpty {
            when {
                update.approval != null && update.question != null -> "Approval and answer required"
                update.approval != null -> "Approval required"
                update.question != null -> "Answer required"
                update.active -> "Running"
                else -> "Connecting"
            }
        }
        val builder = NotificationCompat.Builder(this, RunNotifications.CHANNEL_RUNS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(target.title.trim().ifEmpty { "Hermes session" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(click)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, RunNotifications.CHANNEL_RUNS)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("Hermex-Type-J")
                    .setContentText(if (update.active) "Agent run in progress" else "Agent is waiting for a response")
                    .build(),
            )
        if (update.active) {
            val cancel = PendingIntent.getService(
                this,
                notificationId(key),
                Intent(this, HermesRunMonitorService::class.java)
                    .setAction(HermesRunMonitorActions.CANCEL)
                    .putHermesRunTarget(target),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Cancel run", cancel)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun notifyOngoing(id: Int, notification: Notification) {
        val settings = currentSettings ?: return
        if (monitoringAllowed(this, settings)) manager.notify(id, notification)
    }

    companion object {
        private const val EXTRA_CONNECTION_ID = "io.github.jcartu.hermex.extra.HERMES_CONNECTION_ID"
        private const val EXTRA_TITLE = "io.github.jcartu.hermex.extra.RUN_TITLE"
        private val activeTargets = ConcurrentHashMap.newKeySet<HermesRunMonitorKey>()
        private val foregroundClaims = ConcurrentHashMap.newKeySet<HermesRunMonitorKey>()

        /** The UI calls this synchronously before it tears down its foreground coordinator. */
        fun start(context: Context, target: HermesRunTarget): Boolean {
            val key = target.ownershipKey()
            if (!activeTargets.add(key)) return true
            foregroundClaims.remove(key)
            val app = context.applicationContext
            return try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, HermesRunMonitorService::class.java)
                        .setAction(HermesRunMonitorActions.START)
                        .putHermesRunTarget(target),
                )
                true
            } catch (_: RuntimeException) {
                activeTargets.remove(key)
                false
            }
        }

        /** Stops only this observer. It never sends a host call. */
        fun stop(context: Context, target: HermesRunTarget) {
            val key = target.ownershipKey()
            if (key !in activeTargets) return
            foregroundClaims += key
            val app = context.applicationContext
            if (runCatching {
                    app.startService(
                        Intent(app, HermesRunMonitorService::class.java)
                            .setAction(HermesRunMonitorActions.STOP)
                            .putHermesRunTarget(target),
                    )
                }.isFailure
            ) {
                foregroundClaims.remove(key)
            }
        }

        /** Master toggle, OS permission/app switch, and the ongoing channel all gate UI handoff. */
        fun canStart(context: Context, settings: SettingsRepository.Settings): Boolean =
            monitoringAllowed(context.applicationContext, settings)

        internal fun monitoringAllowed(context: Context, settings: SettingsRepository.Settings): Boolean {
            if (!settings.backgroundRunNotificationsEnabled) return false
            if (
                NotificationPermissions.authorization(context, settings.hasRequestedNotificationPermission) !=
                NotificationAuthorization.GRANTED
            ) return false
            RunNotifications.createChannels(context)
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(RunNotifications.CHANNEL_RUNS)
            return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        private fun notificationId(key: HermesRunMonitorKey): Int =
            0x60000000 or (key.hashCode() and 0x0fffffff)

        private fun Intent.putHermesRunTarget(target: HermesRunTarget): Intent = apply {
            putExtra(RunNotificationIntent.EXTRA_SERVER_ID, target.serverId)
            putExtra(EXTRA_CONNECTION_ID, target.connectionId)
            putExtra(RunNotificationIntent.EXTRA_HERMES_PROFILE, target.profile)
            putExtra(RunNotificationIntent.EXTRA_SESSION_ID, target.storedKey)
            putExtra(EXTRA_TITLE, target.title)
        }

        private fun Intent.hermesRunTarget(): HermesRunTarget? {
            val server = getStringExtra(RunNotificationIntent.EXTRA_SERVER_ID)?.takeIf(String::isNotBlank) ?: return null
            val connection = getStringExtra(EXTRA_CONNECTION_ID)?.takeIf(String::isNotBlank) ?: return null
            val profile = getStringExtra(RunNotificationIntent.EXTRA_HERMES_PROFILE)?.takeIf(String::isNotBlank) ?: return null
            val stored = getStringExtra(RunNotificationIntent.EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return null
            val title = getStringExtra(EXTRA_TITLE).orEmpty()
            return HermesRunTarget(server, connection, profile, stored, title)
        }
    }
}
