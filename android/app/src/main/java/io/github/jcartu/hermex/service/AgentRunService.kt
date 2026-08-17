package io.github.jcartu.hermex.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.MainActivity
import io.github.jcartu.hermex.R
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.SseChatStream
import kotlinx.coroutines.CancellationException
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground monitor for agent runs while the UI is backgrounded. It follows
 * the existing stream, reconnects through status/replay, never submits a user
 * message, and hands control back to ChatPane when the app returns.
 */
class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitors = ConcurrentHashMap<String, Job>()
    private val monitorSessionIds = ConcurrentHashMap<String, String>()
    private val manager by lazy { NotificationManagerCompat.from(this) }
    private val container by lazy { (application as HermexApplication).container }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val streamId = intent.getStringExtra(EXTRA_STREAM_ID) ?: return START_NOT_STICKY
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank) ?: "Hermes run"
                startMonitor(streamId, sessionId, title)
            }
            ACTION_STOP -> intent.getStringExtra(EXTRA_STREAM_ID)?.let(::stopMonitor)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_STREAM_ID)?.let(::cancelRun)
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitor(streamId: String, sessionId: String, title: String) {
        if (monitors.containsKey(streamId)) return
        activeStreamIds += streamId
        monitorSessionIds[streamId] = sessionId
        val id = notificationId(streamId)
        val initial = BackgroundRunState(title)
        if (monitors.isEmpty()) startForeground(id, notification(initial, streamId, sessionId, ongoing = true))
        else notify(id, notification(initial, streamId, sessionId, ongoing = true))

        monitors[streamId] = scope.launch {
            var state = initial
            var replayAfter: Int? = null
            try {
                while (isActive && state.terminal == null) {
                    val stream = SseChatStream(
                        httpClient = container.okHttpClient,
                        json = container.json,
                        api = container.api,
                        customHeaderProvider = { container.customHeaderStore.snapshot() },
                    )
                    stream.connect(streamId, replayAfter)
                        .takeWhile { event ->
                            state = state.reduce(event)
                            notify(id, notification(state, streamId, sessionId, ongoing = state.terminal == null))
                            state.terminal == null
                        }
                        .collect()
                    replayAfter = stream.lastEventId?.toIntOrNull() ?: replayAfter
                    if (state.terminal != null || !isActive) break

                    val status = runCatching { container.api.chatStreamStatus(streamId) }.getOrNull()
                    if (status?.active == true || status?.replayAvailable == true) {
                        state = state.copy(status = "Reconnecting")
                        notify(id, notification(state, streamId, sessionId, ongoing = true))
                        delay(RECONNECT_DELAY_MS)
                    } else if (status != null) {
                        state = state.copy(status = "Complete", terminal = BackgroundRunTerminal.COMPLETE)
                    } else {
                        state = state.copy(status = "Connection interrupted")
                        notify(id, notification(state, streamId, sessionId, ongoing = true))
                        delay(RECONNECT_DELAY_MS)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                state = state.copy(status = error.message ?: "Run monitor failed", terminal = BackgroundRunTerminal.FAILED)
            } finally {
                monitors.remove(streamId)
                activeStreamIds -= streamId
                monitorSessionIds.remove(streamId)
                if (state.terminal != null) {
                    notify(id, notification(state, streamId, sessionId, ongoing = false))
                } else {
                    manager.cancel(id)
                }
                if (monitors.isEmpty()) {
                    stopForeground(if (state.terminal != null) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopMonitor(streamId: String) {
        activeStreamIds -= streamId
        monitors.remove(streamId)?.cancel()
        manager.cancel(notificationId(streamId))
        if (monitors.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelRun(streamId: String) {
        scope.launch {
            val sessionId = monitorSessionIds.remove(streamId).orEmpty()
            activeStreamIds -= streamId
            monitors.remove(streamId)?.cancelAndJoin()
            runCatching { container.api.cancelChat(streamId) }
            val state = BackgroundRunState("Hermes run", "Cancelled", terminal = BackgroundRunTerminal.CANCELLED)
            if (monitors.isEmpty()) stopForeground(STOP_FOREGROUND_DETACH)
            notify(notificationId(streamId), notification(state, streamId, sessionId, ongoing = false))
            if (monitors.isEmpty()) stopSelf()
        }
    }

    private fun notification(
        state: BackgroundRunState,
        streamId: String,
        sessionId: String,
        ongoing: Boolean,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "hermex-type-j://session/${Uri.encode(sessionId)}".toUri()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, notificationId(streamId), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = Intent(this, AgentRunService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_STREAM_ID, streamId)
        }
        val cancelPending = PendingIntent.getService(
            this, notificationId(streamId) + 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (ongoing) CHANNEL_RUNS else CHANNEL_RESULTS
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(state.title)
            .setContentText(state.notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.notificationText))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(ongoing)
            .setContentIntent(openPending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, channel)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("Hermex-Type-J")
                    .setContentText(if (ongoing) "Agent run in progress" else "Agent run updated")
                    .build(),
            )
            .apply { if (ongoing) addAction(0, "Cancel run", cancelPending) }
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(id, notification)
        }
    }

    private fun createChannels() {
        val platform = getSystemService(NotificationManager::class.java)
        platform.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNS, "Agent runs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live status while an agent response runs in the background"
                setShowBadge(false)
                lightColor = Color.rgb(255, 198, 41)
            },
        )
        platform.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Agent results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Completion, cancellation, and failure notifications"
            },
        )
    }

    companion object {
        private const val ACTION_START = "io.github.jcartu.hermex.run.START"
        private const val ACTION_STOP = "io.github.jcartu.hermex.run.STOP"
        private const val ACTION_CANCEL = "io.github.jcartu.hermex.run.CANCEL"
        private const val EXTRA_STREAM_ID = "stream_id"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_TITLE = "title"
        private const val CHANNEL_RUNS = "agent_runs"
        private const val CHANNEL_RESULTS = "agent_results"
        private val activeStreamIds = ConcurrentHashMap.newKeySet<String>()
        private const val RECONNECT_DELAY_MS = 2_000L

        fun start(context: Context, streamId: String, sessionId: String, title: String) {
            val intent = Intent(context, AgentRunService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAM_ID, streamId)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, streamId: String) {
            if (streamId !in activeStreamIds) return
            context.startService(Intent(context, AgentRunService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STREAM_ID, streamId)
            })
        }

        private fun notificationId(streamId: String): Int =
            (streamId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1000)
    }
}
