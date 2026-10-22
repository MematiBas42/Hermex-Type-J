package io.github.jcartu.hermex.core.navigation

import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.service.RunNotificationIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `DeepLinkRepository` state after each entry point (deep link, notification
 * tap, launcher shortcut, share link): the parsed destination is queued —
 * onboarding-safe, held until the signed-in shell routes it — and only an
 * exact acknowledgement clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DeepLinkRepositoryStateTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = temp.newFile("links.preferences_pb")
    }
    @After fun tearDown() = scope.cancel()

    /** Mirrors the production wiring: a trailing slash never splits a server from its id. */
    private val normalizer: (String) -> String? = { it.trim().removeSuffix("/").ifEmpty { null } }

    private fun repository() = DeepLinkRepository(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        normalizeServer = normalizer,
    )

    @Test fun `every link form queues its typed destination`() = runBlocking {
        val repository = repository()
        val connection = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val cases = listOf(
            "hermex-type-j://new-chat" to AppDeepLink.NewChat,
            "hermex-type-j://new-chat-voice" to AppDeepLink.NewChatVoice,
            "hermes-agent://chat" to AppDeepLink.NewChat,
            "hermex-type-j://new-chat-profile?profile=Inbox" to AppDeepLink.WebuiNewChatInProfile("Inbox"),
            "hermex-type-j://session?server=http%3A%2F%2F100.64.1.5%3A9119&id=k1&profile=&subagent=1" to
                AppDeepLink.HermesSession(HermesSessionDestination("http://100.64.1.5:9119", "", "k1", opensParent = true)),
            "hermex-type-j://session?id=legacy-1" to AppDeepLink.Session("legacy-1"),
            "hermex-type-j://session/path-1" to AppDeepLink.Session("path-1"),
            "hermex-type-j://webui-push?server=https%3A%2F%2Fw.example.com&id=s1&onSource=1" to
                AppDeepLink.WebuiPush(WebuiPushDestination("https://w.example.com", "s1", opensOnSource = true)),
            "hermex-type-j://bot?server=http%3A%2F%2F100.64.1.5%3A9119&connection=$connection&profile=Inbox" to
                AppDeepLink.Bot(BotDestination("http://100.64.1.5:9119", connection, "Inbox")),
            "hermex-type-j://share" to AppDeepLink.Share,
            "hermex-type-j://kanban/main/card-1" to AppDeepLink.Kanban("main", "card-1"),
            "hermex-type-j://git/s1" to AppDeepLink.Git("s1"),
        )
        for ((raw, expected) in cases) {
            val parsed = repository.submit(Uri.parse(raw))
            assertEquals("form $raw", expected, parsed)
            assertEquals("queued $raw", expected, repository.pending.value?.second)
            repository.acknowledge(repository.pending.value!!.first)
            assertNull(repository.pending.value)
        }
        // A server-named New Chat in Profile carries a fresh request id (#1180): compare by fields.
        repository.submit(
            Uri.parse("hermex-type-j://new-chat-profile?server=http%3A%2F%2F100.64.1.5%3A9119&profile=Inbox"),
        )
        val newChat = (repository.pending.value?.second as AppDeepLink.HermesNewChat).destination
        assertEquals("http://100.64.1.5:9119", newChat.serverId)
        assertEquals("Inbox", newChat.profile)
    }

    @Test fun `queued link survives process death until acknowledged`() = runBlocking {
        val first = repository()
        first.submit(Uri.parse("hermex-type-j://session?server=http%3A%2F%2F100.64.1.5%3A9119&id=k1"))
        val raw = first.pending.value!!.first
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val restored = repository()
        restored.load()
        assertEquals(
            AppDeepLink.HermesSession(HermesSessionDestination("http://100.64.1.5:9119", null, "k1")),
            restored.pending.value?.second,
        )
        // A stale acknowledgement (an older link's raw) never clears the queue.
        restored.acknowledge("hermex-type-j://new-chat")
        assertTrue(restored.pending.value != null)
        restored.acknowledge(raw)
        assertNull(restored.pending.value)
    }

    @Test fun `a newer link replaces the queue like Swift's PendingLinks reset`() = runBlocking {
        val repository = repository()
        repository.submit(Uri.parse("hermex-type-j://new-chat"))
        val firstRaw = repository.pending.value!!.first
        repository.submit(Uri.parse("hermex-type-j://kanban/main"))
        assertEquals(AppDeepLink.Kanban("main", null), repository.pending.value?.second)
        repository.acknowledge(firstRaw)
        assertTrue(repository.pending.value != null)
    }

    @Test fun `server item is normalized so a trailing slash matches the registry id`() = runBlocking {
        val repository = repository()
        repository.submit(Uri.parse("hermex-type-j://session?server=http%3A%2F%2F100.64.1.5%3A9119%2F&id=k1"))
        val destination = (repository.pending.value?.second as AppDeepLink.HermesSession).destination
        assertEquals("http://100.64.1.5:9119", destination.serverId)
    }

    @Test fun `notification tap entry queues the exact Hermes session`() = runBlocking {
        val repository = repository()
        val intent = RunNotificationIntent.contentIntent(
            ApplicationProvider.getApplicationContext(),
            RunNotificationIntent.Target(
                serverId = "http://100.64.1.5:9119", sessionId = "k1",
                hermesProfile = "Inbox", serverKind = ServerKind.HERMES,
            ),
        )
        repository.submit(NotificationDeepLink.uriFrom(intent))
        assertEquals(
            AppDeepLink.HermesSession(HermesSessionDestination("http://100.64.1.5:9119", "Inbox", "k1")),
            repository.pending.value?.second,
        )
    }

    @Test fun `share intents queue in the durable inbox without touching the link queue`() = runBlocking {
        val repository = repository()
        val sharedImports = io.github.jcartu.hermex.core.share.SharedImportRepository(
            ApplicationProvider.getApplicationContext(),
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        sharedImports.ingest(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "shared text"),
        )
        assertTrue(sharedImports.pending.value != null)
        assertNull(repository.pending.value)
        sharedImports.acknowledge(sharedImports.pending.value!!.id)
        assertNull(sharedImports.pending.value)
    }
}
