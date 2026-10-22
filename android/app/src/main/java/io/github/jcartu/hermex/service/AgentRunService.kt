package io.github.jcartu.hermex.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.R
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.SseChatStream
import io.github.jcartu.hermex.ui.feature.chat.ChatStreamCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Android counterpart of `AgentLiveActivityManager` +
 * `ResponseCompletionNotificationService` (Config/AppTheme.swift): follows an
 * already-started webui run while the UI is backgrounded. Every monitor owns
 * its server URL, custom headers and session; switching servers never
 * redirects status/cancel/replay to a different host. It never sends a prompt.
 */
class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitors = mutableMapOf<RunKey, Monitor>()
    private val manager by lazy { NotificationManagerCompat.from(this) }
    private val container by lazy { (application as HermexApplication).container }

    private data class RunKey(val server: String, val stream: String) {
        val notificationId: Int get() = (hashCode() and Int.MAX_VALUE).coerceAtLeast(1000)
    }
    private class Monitor(val key: RunKey, val target: RunNotificationIntent.Target, val api: HermesApi, var state: BackgroundRunState) {
        lateinit var job: Job
        var approvalId: String? = null
        var questionId: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        RunNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val server = intent?.getStringExtra(RunNotificationIntent.EXTRA_SERVER_ID)?.takeIf { it.isNotBlank() }
            ?: return START_NOT_STICKY
        val stream = intent.getStringExtra(EXTRA_STREAM_ID) ?: return START_NOT_STICKY
        val key = RunKey(server, stream)
        when (intent.action) {
            ACTION_START -> {
                val session = intent.getStringExtra(RunNotificationIntent.EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                startMonitor(key, session, intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Hermes session")
            }
            ACTION_STOP -> stopMonitor(key)
            ACTION_CANCEL -> cancelRun(key)
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitor(key: RunKey, session: String, title: String) {
        if (key in monitors) return
        val api = HermesApi(container.okHttpClient, container.json,
            configProvider = { ServerConfig(key.server, "") },
            customHeaderProvider = { container.customHeaders.snapshotFor(key.server) })
        val monitor = Monitor(key, RunNotificationIntent.Target(key.server, session), api, BackgroundRunState(title))
        val initial = ongoingNotification(monitor)
        if (monitors.isEmpty()) startForeground(key.notificationId, initial) else notifyOngoing(key.notificationId, initial)
        activeRuns += key
        monitor.job = scope.launch(start = CoroutineStart.LAZY) {
            var replayAfter: Int? = null
            val prompts = launch {
                while (isActive && monitor.state.terminal == null) {
                    try {
                        approval(monitor, api.approvalPending(session))
                        question(monitor, api.clarifyPending(session))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A failed advisory read cannot dismiss a pending request. */ }
                    delay(1_500)
                }
            }
            try {
                while (isActive && monitor.state.terminal == null) {
                    val stream = SseChatStream(container.okHttpClient, container.json, api) {
                        container.customHeaders.snapshotFor(key.server)
                    }
                    stream.connect(key.stream, replayAfter).takeWhile { event ->
                        monitor.state = monitor.state.reduce(event)
                        when (event) {
                            is ChatStreamEvent.ApprovalPending -> approval(monitor, event.response)
                            is ChatStreamEvent.ClarificationPending -> question(monitor, event.response)
                            else -> Unit
                        }
                        if (monitor.state.terminal == null) notifyOngoing(key.notificationId, ongoingNotification(monitor))
                        monitor.state.terminal == null
                    }.collect { }
                    replayAfter = ChatStreamCoordinator.runJournalReplayAfterSeq(stream.lastEventId) ?: replayAfter
                    if (monitor.state.terminal != null || !isActive) break
                    val status = try { api.chatStreamStatus(key.stream) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (status?.active == true || status?.replayAvailable == true) {
                        monitor.state = monitor.state.copy(status = "Reconnecting")
                    } else if (status?.active == false) {
                        val terminal = when (status.journal?.terminalState) {
                            "errored", "interrupted-by-crash", "lost-worker-bookkeeping" -> BackgroundRunTerminal.FAILED
                            "interrupted-by-user" -> BackgroundRunTerminal.CANCELLED
                            else -> BackgroundRunTerminal.COMPLETE
                        }
                        monitor.state = monitor.state.copy(terminal = terminal)
                        break
                    } else monitor.state = monitor.state.copy(status = "Connection interrupted")
                    notifyOngoing(key.notificationId, ongoingNotification(monitor))
                    delay(RECONNECT_DELAY_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                monitor.state = monitor.state.copy(status = error.message ?: "Run monitor failed", terminal = BackgroundRunTerminal.FAILED)
            } finally {
                prompts.cancel()
                if (monitors[key] === monitor) monitors.remove(key)
                activeRuns -= key
                manager.cancel(key.notificationId)
                if (monitors.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val next = monitors.values.first()
                    startForeground(next.key.notificationId, ongoingNotification(next))
                }
                val terminal = monitor.state.terminal
                if (terminal != null && terminal != BackgroundRunTerminal.CANCELLED) withContext(NonCancellable) {
                    val failed = terminal == BackgroundRunTerminal.FAILED
                    RunNotifications.clear(this@AgentRunService, monitor.target)
                    RunNotifications.post(this@AgentRunService,
                        if (failed) RunNotificationKind.FAILED_RUN else RunNotificationKind.REPLY,
                        monitor.target, monitor.state.title, if (failed) "Response failed" else "Response complete",
                        container.settings.settings.first(), sceneIsActive = false)
                }
            }
        }
        monitors[key] = monitor
        monitor.job.start()
    }

    private suspend fun approval(monitor: Monitor, update: ApprovalPendingResponse) {
        val pending = update.pending
        if (pending == null || pending.isEmpty) {
            if (update.pendingCount == 0) {
                monitor.approvalId = null
                manager.cancel(monitor.target.notificationTag, RunNotificationKind.APPROVAL.id)
            }
            return
        }
        if (monitor.approvalId == pending.id) return
        monitor.approvalId = pending.id
        RunNotifications.post(this, RunNotificationKind.APPROVAL, monitor.target, monitor.state.title,
            pending.description?.takeIf { it.isNotBlank() } ?: "Approval required",
            container.settings.settings.first(), sceneIsActive = false)
    }

    private suspend fun question(monitor: Monitor, update: ClarificationPendingResponse) {
        val pending = update.pending
        if (pending == null || pending.isEmpty) {
            if (update.pendingCount == 0) {
                monitor.questionId = null
                manager.cancel(monitor.target.notificationTag, RunNotificationKind.QUESTION.id)
            }
            return
        }
        if (monitor.questionId == pending.id) return
        monitor.questionId = pending.id
        RunNotifications.post(this, RunNotificationKind.QUESTION, monitor.target, monitor.state.title,
            pending.displayQuestion, container.settings.settings.first(), sceneIsActive = false)
    }

    private fun stopMonitor(key: RunKey) {
        monitors[key]?.job?.cancel()
    }

    private fun cancelRun(key: RunKey) {
        val monitor = monitors[key] ?: return
        scope.launch {
            // Dispatch while this foreground owner is alive: cancelling its last
            // monitor first would stopSelf/onDestroy and cancel the POST too.
            try {
                val response = monitor.api.cancelChat(key.stream)
                if (response.ok == false) {
                    monitor.state = monitor.state.copy(status = response.error ?: "The server could not stop this run.")
                    notifyOngoing(key.notificationId, ongoingNotification(monitor))
                    return@launch
                }
                monitor.state = monitor.state.copy(terminal = BackgroundRunTerminal.CANCELLED)
                RunNotifications.clear(this@AgentRunService, monitor.target)
                monitor.job.cancelAndJoin()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (monitors[key] === monitor) {
                    monitor.state = monitor.state.copy(status = error.message ?: "The server could not stop this run.")
                    notifyOngoing(key.notificationId, ongoingNotification(monitor))
                }
            }
        }
    }

    private fun ongoingNotification(monitor: Monitor): Notification {
        val open = PendingIntent.getActivity(this, monitor.key.notificationId,
            RunNotificationIntent.contentIntent(this, monitor.target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, monitor.key.notificationId,
            Intent(this, AgentRunService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_STREAM_ID, monitor.key.stream)
                putExtra(RunNotificationIntent.EXTRA_SERVER_ID, monitor.key.server)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, RunNotifications.CHANNEL_RUNS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(monitor.state.title).setContentText(monitor.state.notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(monitor.state.notificationText))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, RunNotifications.CHANNEL_RUNS)
                .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle("Hermex-Type-J")
                .setContentText("Agent run in progress").build())
            .addAction(0, "Cancel run", cancel).build()
    }

    @SuppressLint("MissingPermission")
    private fun notifyOngoing(id: Int, notification: Notification) {
        if (canPost(this)) manager.notify(id, notification)
    }

    companion object {
        private const val ACTION_START = "io.github.jcartu.hermex.run.START"
        private const val ACTION_STOP = "io.github.jcartu.hermex.run.STOP"
        private const val ACTION_CANCEL = "io.github.jcartu.hermex.run.CANCEL"
        private const val EXTRA_STREAM_ID = "stream_id"
        private const val EXTRA_TITLE = "title"
        private val activeRuns = ConcurrentHashMap.newKeySet<RunKey>()
        private const val RECONNECT_DELAY_MS = 2_000L

        fun start(context: Context, serverId: String, streamId: String, sessionId: String, title: String) {
            ContextCompat.startForegroundService(context, Intent(context, AgentRunService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAM_ID, streamId)
                putExtra(RunNotificationIntent.EXTRA_SERVER_ID, serverId)
                putExtra(RunNotificationIntent.EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TITLE, title)
            })
        }

        fun stop(context: Context, serverId: String, streamId: String) {
            if (RunKey(serverId, streamId) !in activeRuns) return
            context.startService(Intent(context, AgentRunService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STREAM_ID, streamId)
                putExtra(RunNotificationIntent.EXTRA_SERVER_ID, serverId)
            })
        }

        fun canPost(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }
}
