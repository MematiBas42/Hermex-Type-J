package io.github.jcartu.hermex.ui.feature.bots

import android.net.Uri
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Identity-only bot entry: no display-name guessing or cross-connection fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BotDeepLinkTest {
    private val connectionID = "39ee67b7-09fb-4a1b-84f5-c40d448907c8"

    @Test fun `both schemes round-trip encoded profile and canonical root`() {
        val target = BotDestination("https://host.example", connectionID.uppercase(), "research + помощник", "root/key?name")
        for (scheme in listOf("hermex-type-j", "hermes-agent")) {
            assertEquals(target, BotDeepLink.parse(BotDeepLink.url(target, scheme)))
        }
        val trailing = Uri.parse("hermes-agent://bot?server=https%3A%2F%2Fhost.example%2F&connection=$connectionID&profile=helper")
        assertEquals("https://host.example", BotDeepLink.parse(trailing)?.server)
    }

    @Test fun `missing or shortened UUID cannot route`() {
        for (value in listOf("", "garbage", "1-2-3-4-5")) {
            assertNull(BotDeepLink.parse(Uri.parse("hermes-agent://bot?server=https%3A%2F%2Fhost.example&connection=$value&profile=helper")))
        }
        assertNull(BotDeepLink.parse(Uri.parse("https://bot?server=x&connection=$connectionID&profile=helper")))
        assertNull(BotDeepLink.parse(Uri.parse("hermes-agent://bot?connection=$connectionID&profile=helper")))
    }

    @Test fun `a link replaces whole selection and respects connection UUID`() {
        val profile = BotProfile.fromJsonOrNull(BotJSON.parse("""{"name":"helper"}"""))!!
        val connection = BotConnection(id = connectionID, name = "host", address = "https://host.example/".toHttpUrl(), username = "fixture", password = "fixture")
        val room = BotRoomKey("https://host.example", connectionID, "room")
        val initial = BotInboxSelection(room = room)
        val link = BotDestination(room.server, connectionID.uppercase(), profile.id, "root")
        val opened = initial.opening(link, connection, listOf(profile))
        assertEquals(profile, opened.profile)
        assertNull(opened.room)
        assertEquals("root", opened.conversation)
        val missing = opened.opening(link.copy(profile = "gone"), connection, listOf(profile))
        assertTrue(missing.isEmpty)
        assertNull(missing.conversation)
        assertTrue(opened.opening(link.copy(connectionID = "other"), connection, listOf(profile)).isEmpty)
    }

    @Test fun `mailbox only hands off navigation intent`() {
        val target = BotDestination("https://host.example", connectionID, "helper")
        BotLinkMailbox.post(target)
        assertEquals(target, BotLinkMailbox.pending.value)
        BotLinkMailbox.consume()
        assertNull(BotLinkMailbox.pending.value)
    }
}
