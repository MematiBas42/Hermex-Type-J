package io.github.jcartu.hermex.ui.feature.bots

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomCapabilities
import io.github.jcartu.hermex.net.hermes.BotLiveStatus
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Robolectric rendering coverage for the pure Bot inbox surface. No engine lifecycle method is
 * called and [NoNetworkTransport] fails the test if a future render accidentally opens a socket.
 * Assertions walk Compose semantics directly because compose-ui-test is intentionally absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BotInboxComposeTest {
    private val server = "https://hermes.example/"

    @Test
    fun `live inbox renders roster organization and overlay bar at pane counts one two and three`() {
        val connection = connection()
        val inbox = inbox(connection)
        val alpha = profile(
            id = "alpha",
            name = "Alpha",
            pinned = true,
            lastActive = 300.0,
            preview = "Planning the release",
        )
        val builder = profile(
            id = "builder",
            name = "Builder",
            sectionID = "sec-team",
            sectionName = "Team",
            lastActive = 200.0,
            preview = "Reviewing the patch",
        )
        val hidden = profile(id = "secret", name = "Secret", hidden = true, lastActive = 100.0)
        val room = BotGroupRoom(
            id = "room-1",
            name = "Study room",
            members = listOf(BotGroupRoom.Member("member-alpha", "alpha", "Alpha", "alpha")),
            updatedAt = 250.0,
            latestSeq = 4,
            authority = "gateway",
            epoch = 1,
            disbanded = false,
        )
        val state = BotInbox.State(
            connection = connection,
            profiles = listOf(alpha, builder, hidden),
            rooms = listOf(room),
            hasRoomList = true,
            roomCapabilities = BotRoomCapabilities(
                enabled = true,
                authority = "gateway",
                pageLimit = 200,
                methods = setOf("groups.list", "groups.state", "groups.log", "groups.create"),
            ),
            link = BotInbox.Link.LIVE,
            seen = mapOf("alpha" to 300.0, "builder" to 100.0, "secret" to 100.0),
            liveStatuses = mapOf("alpha" to BotLiveStatus.WAITING),
        )

        for (paneCount in 1..3) {
            val dump = composeAndDump(paneCount) {
                BotsInboxScreen(
                    inbox = inbox,
                    state = state,
                    onOpenBot = {},
                    onOpenRoom = { _, _ -> },
                    onEdit = {},
                    onCreate = {},
                    onCreateRoom = {},
                    onConnection = {},
                    bottomBar = { Text("Bots navigation") },
                )
            }
            assertTrue("pane=$paneCount pinned bot missing: $dump", dump.contains("Alpha"))
            assertTrue("pane=$paneCount live status missing: $dump", dump.contains("Waiting for you"))
            assertTrue("pane=$paneCount section missing: $dump", dump.contains("Team"))
            assertTrue("pane=$paneCount roster row missing: $dump", dump.contains("Builder"))
            assertTrue("pane=$paneCount unread semantics missing: $dump", dump.contains("Unread"))
            assertTrue("pane=$paneCount room missing: $dump", dump.contains("Study room"))
            assertTrue("pane=$paneCount hidden control missing: $dump", dump.contains("Show hidden (1)"))
            assertFalse("pane=$paneCount hidden bot leaked: $dump", dump.contains("Secret"))
            assertTrue("pane=$paneCount list overlay bar missing: $dump", dump.contains("Bots navigation"))
        }
    }

    @Test
    fun `disconnected inbox renders welcome without list overlay bar at pane counts one two and three`() {
        val inbox = inbox(null)
        val state = BotInbox.State(connection = null, link = BotInbox.Link.IDLE)

        for (paneCount in 1..3) {
            val dump = composeAndDump(paneCount) {
                BotsInboxScreen(
                    inbox = inbox,
                    state = state,
                    onOpenBot = {},
                    onOpenRoom = { _, _ -> },
                    onEdit = {},
                    onCreate = {},
                    onCreateRoom = {},
                    onConnection = {},
                    bottomBar = { Text("Bots navigation") },
                )
            }
            assertTrue("pane=$paneCount welcome title missing: $dump", dump.contains("Your bots, together."))
            assertTrue("pane=$paneCount connect action missing: $dump", dump.contains("Connect"))
            assertTrue("pane=$paneCount setup help missing: $dump", dump.contains("Copy setup prompt"))
            assertTrue("pane=$paneCount connection action missing: $dump", dump.contains("Bot connection"))
            assertFalse("pane=$paneCount bottom bar must not cover welcome: $dump", dump.contains("Bots navigation"))
        }
    }

    private fun composeAndDump(paneCount: Int, content: @Composable () -> Unit): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            HermexTheme(darkTheme = true) {
                CompositionLocalProvider(LocalPaneCount provides paneCount) {
                    Box(Modifier.requiredWidth((if (paneCount == 1) 390 else 560).dp).height(820.dp)) {
                        content()
                    }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val dump = activity.semanticsDump()
        controller.destroy()
        return dump
    }

    private fun connection() = BotConnection(
        id = "4FC17FB4-87DF-4B18-A1FA-5503D934B6A4",
        name = "Hermes Lab",
        address = "https://dashboard.example/".toHttpUrl(),
        username = "josh",
        password = "saved",
    )

    private fun profile(
        id: String,
        name: String,
        pinned: Boolean = false,
        hidden: Boolean = false,
        sectionID: String? = null,
        sectionName: String? = null,
        lastActive: Double? = null,
        preview: String? = null,
    ) = BotProfile(
        id = id,
        name = name,
        title = name,
        displayName = name,
        description = "Assistant",
        preview = preview,
        lastActive = lastActive,
        canonicalID = "chat-$id",
        canonicalTipID = "chat-$id",
        pinned = pinned,
        hidden = hidden,
        sectionID = sectionID,
        sectionName = sectionName,
        look = emptyMap(),
        hasAvatar = false,
        lookRevision = null,
    )

    private fun inbox(connection: BotConnection?): BotInbox {
        val defaults = BotLocalDefaults.InMemory()
        return BotInbox(
            server = server,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = object : BotInbox.ConnectionRecords {
                override fun load(server: String): BotConnection? = connection
                override fun save(connection: BotConnection, server: String) = Unit
            },
            unread = BotUnreadStore(defaults),
            roomStore = BotRoomOrganizeStore(defaults),
            sectionOrderStore = BotSectionOrderStore(defaults),
            avatarStore = BotAvatarStore(),
            makeWire = { NoNetworkTransport() },
        )
    }

    private class NoNetworkTransport : BotTransport {
        override var onEvent: ((BotJSON) -> Unit)? = null
        override var onDisconnect: ((Throwable) -> Unit)? = null

        override suspend fun connect(): Nothing = error("render attempted network connect")
        override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): Nothing =
            error("render attempted RPC ${call::class.simpleName}")
        override suspend fun close() = Unit
    }

    private companion object {
        fun android.app.Activity.semanticsDump(): List<String> {
            val composeView = window.decorView.findComposeView() ?: return emptyList()
            val owner = composeView.javaClass.getMethod("getSemanticsOwner").invoke(composeView) as SemanticsOwner
            val output = mutableListOf<String>()
            fun walk(node: SemanticsNode) {
                for (entry in node.config) {
                    when (entry.key) {
                        SemanticsProperties.Text -> {
                            @Suppress("UNCHECKED_CAST")
                            (entry.value as? List<AnnotatedString>)?.forEach { output += it.text }
                        }
                        SemanticsProperties.ContentDescription -> {
                            @Suppress("UNCHECKED_CAST")
                            (entry.value as? List<String>)?.let(output::addAll)
                        }
                        SemanticsProperties.EditableText ->
                            (entry.value as? AnnotatedString)?.let { output += it.text }
                    }
                }
                node.children.forEach(::walk)
            }
            walk(owner.rootSemanticsNode)
            return output
        }

        fun android.view.View.findComposeView(): android.view.View? = when {
            javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" -> this
            this is android.view.ViewGroup ->
                (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findComposeView() }
            else -> null
        }
    }
}
