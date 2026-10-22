package io.github.jcartu.hermex.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.jcartu.hermex.core.prefs.NotificationAuthorization
import io.github.jcartu.hermex.core.prefs.NotificationOffer
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.feature.settings.NotificationPermissions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RunNotificationsTest {
    private lateinit var app: Application
    private lateinit var manager: NotificationManager
    private val enabled = SettingsRepository.Settings(backgroundRunNotificationsEnabled = true)

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.cancelAll()
    }

    @Test fun `every kind has its own channel with time sensitive equivalent approvals`() {
        RunNotifications.createChannels(app)
        assertEquals(4, RunNotificationKind.entries.map { it.channelId }.distinct().size)
        for (kind in RunNotificationKind.entries) {
            assertEquals(kind.importance, manager.getNotificationChannel(kind.channelId).importance)
        }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(RunNotificationKind.APPROVAL.channelId).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(RunNotifications.CHANNEL_RUNS).importance)
    }

    @Test fun `kind toggle matrix master switch permission and foreground gate are independent`() {
        for (mask in 0 until 16) {
            val settings = enabled.copy(notifyReplies = mask and 1 != 0, notifyApprovals = mask and 2 != 0,
                notifyQuestions = mask and 4 != 0, notifyFailedRuns = mask and 8 != 0)
            for ((index, kind) in RunNotificationKind.entries.withIndex()) {
                val expected = if (mask and (1 shl index) != 0) null else RunNotificationResult.KIND_DISABLED
                assertEquals("$kind mask $mask", expected, RunNotificationPolicy.decision(kind, settings, NotificationAuthorization.GRANTED, false))
            }
        }
        for (kind in RunNotificationKind.entries) {
            assertEquals(RunNotificationResult.DISABLED, RunNotificationPolicy.decision(kind, enabled.copy(backgroundRunNotificationsEnabled = false), NotificationAuthorization.GRANTED, false))
            assertEquals(RunNotificationResult.FOREGROUND, RunNotificationPolicy.decision(kind, enabled, NotificationAuthorization.GRANTED, true))
            assertEquals(RunNotificationResult.PERMISSION_DENIED, RunNotificationPolicy.decision(kind, enabled, NotificationAuthorization.DENIED, false))
        }
    }

    @Test fun `all kinds including failure carry exact server and session in tap intent`() {
        for (kind in RunNotificationKind.entries) {
            for (serverKind in ServerKind.entries) {
                val target = RunNotificationIntent.Target("https://host.example:9119", "stored/key ?&=", "Work space", serverKind)
                assertEquals(RunNotificationResult.POSTED, RunNotifications.post(app, kind, target, " My chat ", "Body", enabled, false))
                val posted = shadowOf(manager).getNotification(target.notificationTag, kind.id)
                assertNotNull(posted)
                assertEquals(kind.channelId, posted.channelId)
                assertEquals("My chat", posted.extras.getString(Notification.EXTRA_TITLE))
                val intent = shadowOf(posted.contentIntent).savedIntent
                assertEquals(target, RunNotificationIntent.target(intent))
                assertEquals(target.serverId, intent.data?.getQueryParameter("server"))
                assertEquals(target.sessionId, intent.data?.getQueryParameter("id"))
                assertEquals(if (serverKind == ServerKind.HERMES) "session" else "webui-push", intent.data?.host)
            }
        }
    }

    @Test fun `same session on different servers retains independent pending intents and alerts`() {
        val one = RunNotificationIntent.Target("https://one", "same")
        val two = RunNotificationIntent.Target("https://two", "same")
        RunNotifications.post(app, RunNotificationKind.REPLY, one, "One", "Done", enabled, false)
        RunNotifications.post(app, RunNotificationKind.REPLY, two, "Two", "Done", enabled, false)
        assertNotEquals(one.notificationTag, two.notificationTag)
        assertEquals(one, RunNotificationIntent.target(shadowOf(shadowOf(manager).getNotification(one.notificationTag, 1).contentIntent).savedIntent))
        assertEquals(two, RunNotificationIntent.target(shadowOf(shadowOf(manager).getNotification(two.notificationTag, 1).contentIntent).savedIntent))
    }

    @Test fun `run termination retains prompt alerts while explicit teardown clears every kind`() {
        val target = RunNotificationIntent.Target("https://host", "session")
        for (kind in RunNotificationKind.entries) {
            assertEquals(
                RunNotificationResult.POSTED,
                RunNotifications.post(app, kind, target, "Run", kind.name, enabled, false),
            )
        }

        RunNotifications.clearRunOutcomes(app, target)

        assertNull(shadowOf(manager).getNotification(target.notificationTag, RunNotificationKind.REPLY.id))
        assertNull(shadowOf(manager).getNotification(target.notificationTag, RunNotificationKind.FAILED_RUN.id))
        assertNotNull(shadowOf(manager).getNotification(target.notificationTag, RunNotificationKind.APPROVAL.id))
        assertNotNull(shadowOf(manager).getNotification(target.notificationTag, RunNotificationKind.QUESTION.id))

        RunNotifications.clear(app, target)
        for (kind in RunNotificationKind.entries) {
            assertNull(shadowOf(manager).getNotification(target.notificationTag, kind.id))
        }
    }

    @Test fun `Hermes unknown default and named profiles remain distinct`() {
        for (profile in listOf(null, "", "Team & work")) {
            val target = RunNotificationIntent.Target("https://host", "key", profile, ServerKind.HERMES)
            val intent = RunNotificationIntent.contentIntent(app, target)
            assertEquals(target, RunNotificationIntent.target(intent))
            assertEquals(profile != null, intent.data!!.queryParameterNames.contains("profile"))
            assertEquals(profile, intent.data!!.getQueryParameter("profile"))
        }
        assertNull(RunNotificationIntent.target(Intent().putExtra(RunNotificationIntent.EXTRA_SESSION_ID, "not-local")))
    }

    @Test fun `denied runtime permission or app switch never reports a successful post`() {
        val target = RunNotificationIntent.Target("https://a", "s")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationAuthorization.DENIED, NotificationPermissions.authorization(app, true))
        assertEquals(RunNotificationResult.PERMISSION_DENIED, RunNotifications.post(app, RunNotificationKind.FAILED_RUN, target, "Failed", "Failure", enabled, false))
        assertEquals(RunNotificationResult.PERMISSION_DENIED, NotificationPermissions.postTestNotification(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(AgentRunService.canPost(app))
        assertEquals(NotificationAuthorization.DENIED, NotificationPermissions.authorization(app, true))
        assertEquals(RunNotificationResult.PERMISSION_DENIED, NotificationPermissions.postTestNotification(app))
    }

    @Test fun `Send Test Notification exercises production reply channel and builder`() {
        assertEquals(RunNotificationResult.POSTED, NotificationPermissions.postTestNotification(app))
        val notification = shadowOf(manager).getNotification(RunNotifications.TEST_NOTIFICATION_ID)
        assertEquals(RunNotificationKind.REPLY.channelId, notification.channelId)
        assertEquals("Test notification", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
    }

    @Test fun `first admitted run claims offer once and Hermes failures use failed kind`() = runBlocking {
        val values = MutableStateFlow(SettingsRepository.Settings())
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns values
        coEvery { settings.setHasOfferedNotifications(true) } coAnswers {
            values.value = values.value.copy(hasOfferedNotifications = true)
            androidx.datastore.preferences.core.emptyPreferences()
        }
        val offer = NotificationOffer(settings) { NotificationAuthorization.NOT_DETERMINED }
        val hooks = RunNotificationHooks(app, settings, offer) { false }
        val account = ServerAccount("https://host", "https://host", kind = ServerKind.HERMES)
        assertTrue(hooks.runStarted(account, "session", "Chat", ""))
        assertTrue(offer.pending.value)
        assertFalse(hooks.runStarted(account, "session", "Chat", ""))
        values.value = enabled
        assertEquals(RunNotificationResult.POSTED, hooks.runFinished(account, "session", "Chat", RunNotificationOutcome.FAILED, ""))
        val target = RunNotificationIntent.Target(account.id, "session", "", ServerKind.HERMES)
        assertNotNull(shadowOf(manager).getNotification(target.notificationTag, RunNotificationKind.FAILED_RUN.id))
        assertEquals(RunNotificationResult.CANCELLED, hooks.runFinished(account, "session", "Chat", RunNotificationOutcome.CANCELLED, ""))
    }
}
