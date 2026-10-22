package io.github.jcartu.hermex.core.navigation

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.service.RunNotificationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notification-intent extras → destination (#1190, #1192): a tapped run
 * notification lands on the exact session its server names, through the one
 * grammar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NotificationDeepLinkTest {

    private val identity: (String) -> String? = { it.trim().ifEmpty { null } }

    private fun intentFor(target: RunNotificationIntent.Target): Intent =
        RunNotificationIntent.contentIntent(ApplicationProvider.getApplicationContext(), target)

    @Test fun `hermes target extras produce the server-named session link with the profile tri-state`() {
        val unknown = NotificationDeepLink.uriFrom(intentFor(RunNotificationIntent.Target(
            serverId = "http://100.64.1.5:9119", sessionId = "k1", hermesProfile = null, serverKind = ServerKind.HERMES,
        )))!!
        val parsedUnknown = HermesSessionDestination.parse(unknown, identity)!!
        assertEquals("http://100.64.1.5:9119", parsedUnknown.serverId)
        assertEquals("k1", parsedUnknown.key)
        assertNull(parsedUnknown.profile)

        val hostDefault = NotificationDeepLink.uriFrom(intentFor(RunNotificationIntent.Target(
            serverId = "http://100.64.1.5:9119", sessionId = "k1", hermesProfile = "", serverKind = ServerKind.HERMES,
        )))!!
        assertEquals("", HermesSessionDestination.parse(hostDefault, identity)!!.profile)

        val named = NotificationDeepLink.uriFrom(intentFor(RunNotificationIntent.Target(
            serverId = "http://100.64.1.5:9119", sessionId = "k1", hermesProfile = "Inbox β", serverKind = ServerKind.HERMES,
        )))!!
        assertEquals("Inbox β", HermesSessionDestination.parse(named, identity)!!.profile)
    }

    @Test fun `webui target extras produce the on-source push link`() {
        val uri = NotificationDeepLink.uriFrom(intentFor(RunNotificationIntent.Target(
            serverId = "https://w.example.com", sessionId = "s1", serverKind = ServerKind.WEBUI,
        )))!!
        assertEquals(
            WebuiPushDestination("https://w.example.com", "s1", opensOnSource = true),
            WebuiPushDestination.parse(uri, identity),
        )
    }

    @Test fun `extras win over the intent data uri`() {
        val intent = intentFor(RunNotificationIntent.Target(
            serverId = "http://100.64.1.5:9119", sessionId = "k1", hermesProfile = "p", serverKind = ServerKind.HERMES,
        ))
        intent.data = Uri.parse("hermex-type-j://new-chat")
        val uri = NotificationDeepLink.uriFrom(intent)!!
        assertEquals("session", uri.host)
        assertEquals("k1", HermesSessionDestination.parse(uri, identity)!!.key)
    }

    @Test fun `the data uri is the fallback and non-local extras are ignored`() {
        val plain = Intent(Intent.ACTION_VIEW, Uri.parse("hermex-type-j://session/abc"))
        assertEquals(Uri.parse("hermex-type-j://session/abc"), NotificationDeepLink.uriFrom(plain))

        val foreign = Intent(Intent.ACTION_VIEW, Uri.parse("hermex-type-j://session/abc"))
        foreign.putExtra(RunNotificationIntent.EXTRA_SOURCE, "relay")
        foreign.putExtra(RunNotificationIntent.EXTRA_SERVER_ID, "http://100.64.1.5:9119")
        foreign.putExtra(RunNotificationIntent.EXTRA_SESSION_ID, "k1")
        assertEquals(Uri.parse("hermex-type-j://session/abc"), NotificationDeepLink.uriFrom(foreign))
    }

    @Test fun `null intent and intent naming nothing yield null`() {
        assertNull(NotificationDeepLink.uriFrom(null))
        assertNull(NotificationDeepLink.uriFrom(Intent(Intent.ACTION_VIEW)))
    }

    @Test fun `the content intent's own data uri parses to the same destination as its extras`() {
        // ChatCoreFollowup builds data through the grammar, so even the
        // fallback path lands on the exact session (#1192).
        val target = RunNotificationIntent.Target(
            serverId = "http://100.64.1.5:9119", sessionId = "k1", hermesProfile = "p", serverKind = ServerKind.HERMES,
        )
        val intent = intentFor(target)
        assertEquals(target.serverId, intent.getStringExtra(RunNotificationIntent.EXTRA_SERVER_ID))
        val fromData = HermesSessionDestination.parse(intent.data!!, identity)!!
        assertEquals("http://100.64.1.5:9119", fromData.serverId)
        assertEquals("p", fromData.profile)
        assertEquals("k1", fromData.key)
    }
}
