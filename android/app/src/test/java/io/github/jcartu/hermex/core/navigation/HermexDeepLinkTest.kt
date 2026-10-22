package io.github.jcartu.hermex.core.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Grammar round-trips for every link form, old and new (port of the
 * `HermesDeepLink` builders/predicates — `LiveActivities/HermesDeepLink.swift`,
 * `Features/Bots/BotDeepLink.swift`, `Features/Share/SharedDraftStore.swift`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HermexDeepLinkTest {

    private val identity: (String) -> String? = { it.trim().ifEmpty { null } }

    // MARK: - Hermes session links (#1176)

    @Test fun `hermes session link round-trips with named profile and subagent flag`() {
        val destination = HermesSessionDestination(
            serverId = "http://100.64.1.5:9119",
            profile = "Inbox β",
            key = "session key/1",
            opensParent = true,
        )
        val uri = HermexDeepLink.hermesSessionUri(destination)!!
        assertEquals("hermex-type-j", uri.scheme)
        assertEquals("session", uri.host)
        val parsed = HermesSessionDestination.parse(uri, identity)!!
        assertEquals(destination.serverId, parsed.serverId)
        assertEquals(destination.profile, parsed.profile)
        assertEquals(destination.key, parsed.key)
        assertTrue(parsed.opensParent)
        assertNull(parsed.movedFromServerId)
    }

    @Test fun `hermes session link keeps the profile tri-state`() {
        // No profile item: unknown — the lookup reads every Profile.
        val unknown = HermexDeepLink.hermesSessionUri(
            HermesSessionDestination("https://h.example.com", null, "k"),
        )!!
        assertFalse(HermexDeepLink.hasQueryItem(unknown, "profile"))
        assertNull(HermesSessionDestination.parse(unknown, identity)!!.profile)

        // Present-empty: the host's default Profile.
        val default = HermexDeepLink.hermesSessionUri(
            HermesSessionDestination("https://h.example.com", "", "k"),
        )!!
        assertTrue(HermexDeepLink.hasQueryItem(default, "profile"))
        assertEquals("", HermesSessionDestination.parse(default, identity)!!.profile)
    }

    @Test fun `hermes session link omits subagent unless set and rejects a blank key`() {
        val uri = HermexDeepLink.hermesSessionUri(
            HermesSessionDestination("https://h.example.com", null, "k", opensParent = false),
        )!!
        assertFalse(HermexDeepLink.hasQueryItem(uri, "subagent"))
        assertFalse(HermesSessionDestination.parse(uri, identity)!!.opensParent)
        assertNull(HermexDeepLink.hermesSessionUri(HermesSessionDestination("https://h.example.com", null, "  ")))
    }

    @Test fun `legacy scheme parses the same grammar`() {
        val uri = Uri.parse("hermes-agent://session?server=https%3A%2F%2Fh.example.com&id=k1&profile=p")
        val parsed = HermesSessionDestination.parse(uri, identity)!!
        assertEquals("https://h.example.com", parsed.serverId)
        assertEquals("k1", parsed.key)
        assertEquals("p", parsed.profile)
    }

    @Test fun `server item is normalized so a trailing slash still matches`() {
        val stripSlash: (String) -> String? = { it.trim().removeSuffix("/").ifEmpty { null } }
        val uri = Uri.parse("hermex-type-j://session?server=https%3A%2F%2Fh.example.com%2F&id=k1")
        assertEquals("https://h.example.com", HermesSessionDestination.parse(uri, stripSlash)!!.serverId)
        // A server item the normalizer rejects drops the whole link.
        assertNull(HermesSessionDestination.parse(uri) { null })
    }

    @Test fun `legacy session link names no server and never parses as a Hermes session`() {
        val bare = Uri.parse("hermes-agent://session?id=abc")
        assertNull(HermesSessionDestination.parse(bare, identity))
        assertEquals("abc", HermexDeepLink.bareSessionId(bare))
    }

    // MARK: - Bare (serverless) session links

    @Test fun `bare session id reads id and legacy session_id items and the path form`() {
        assertEquals("abc", HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session?id=abc")))
        assertEquals("abc", HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session?session_id=abc")))
        assertEquals("abc", HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session/abc")))
        assertEquals("session 1", HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session/session%201")))
    }

    @Test fun `bare session id is null for server-named malformed and injection links`() {
        assertNull(HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session?server=x&id=abc")))
        assertNull(HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session/")))
        assertNull(HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://session/${"x".repeat(300)}")))
        assertNull(HermexDeepLink.bareSessionId(Uri.parse("hermex-type-j://kanban/abc")))
        assertNull(HermexDeepLink.bareSessionId(Uri.parse("https://example.com/session/abc")))
    }

    // MARK: - webui-push links (#1185, #1217)

    @Test fun `webui push link round-trips and onSource and activity open on source`() {
        val plain = HermexDeepLink.webuiPushUri("https://w.example.com", "s1")!!
        val parsedPlain = WebuiPushDestination.parse(plain, identity)!!
        assertEquals(WebuiPushDestination("https://w.example.com", "s1", opensOnSource = false), parsedPlain)

        val onSource = HermexDeepLink.webuiPushUri("https://w.example.com", "s1", opensOnSource = true)!!
        assertTrue(WebuiPushDestination.parse(onSource, identity)!!.opensOnSource)

        val activity = HermexDeepLink.webuiPushUri("https://w.example.com", "s1", activityId = "act-9")!!
        assertEquals("act-9", HermexDeepLink.activityId(activity))
        assertTrue(WebuiPushDestination.parse(activity, identity)!!.opensOnSource)
    }

    @Test fun `webui push link requires a server and a non-blank id`() {
        assertNull(WebuiPushDestination.parse(Uri.parse("hermex-type-j://webui-push?id=s1"), identity))
        assertNull(WebuiPushDestination.parse(Uri.parse("hermex-type-j://webui-push?server=x&id=%20"), identity))
        assertNull(HermexDeepLink.webuiPushUri("https://w.example.com", " "))
    }

    @Test fun `activity id is only read from session bot and webui-push hosts`() {
        assertEquals(
            "a1",
            HermexDeepLink.activityId(Uri.parse("hermex-type-j://session?server=x&id=k&activity=a1")),
        )
        assertEquals(
            "a1",
            HermexDeepLink.activityId(Uri.parse("hermex-type-j://bot?server=x&activity=a1")),
        )
        assertNull(HermexDeepLink.activityId(Uri.parse("hermex-type-j://new-chat?activity=a1")))
        assertNull(HermexDeepLink.activityId(Uri.parse("hermex-type-j://session?server=x&id=k&activity=")))
    }

    // MARK: - Bot links (#554)

    private val connectionUuid = "3F2504E0-4F89-41D3-9A0C-0305E82C3301"

    @Test fun `bot link round-trips and canonicalizes the connection UUID`() {
        val destination = BotDestination(
            serverId = "https://h.example.com",
            connectionId = java.util.UUID.fromString(connectionUuid).toString(),
            profile = "Inbox β",
            conversation = "root-1",
        )
        val uri = HermexDeepLink.botUri(destination)!!
        assertEquals("bot", uri.host)
        val parsed = BotDestination.parse(uri, identity)!!
        assertEquals(destination, parsed)

        // An upper-case UUID in a link parses to the same canonical id.
        val upper = Uri.parse(
            "hermex-type-j://bot?server=https%3A%2F%2Fh.example.com&connection=$connectionUuid&profile=p",
        )
        assertEquals(destination.copy(conversation = null).copy(profile = "p"),
            BotDestination.parse(upper, identity))
    }

    @Test fun `bot link drops when identity is missing rather than opening the wrong bot`() {
        assertNull(BotDestination.parse(Uri.parse("hermex-type-j://bot?server=x&profile=p"), identity))
        assertNull(BotDestination.parse(Uri.parse("hermex-type-j://bot?server=x&connection=nope&profile=p"), identity))
        assertNull(
            BotDestination.parse(
                Uri.parse("hermex-type-j://bot?server=x&connection=${java.util.UUID.randomUUID()}&profile=%20"),
                identity,
            ),
        )
        assertNull(HermexDeepLink.botUri(BotDestination("https://h.example.com", "c", " ")))
    }

    // MARK: - New chat links (#337, #338, #339, #1180)

    @Test fun `new chat and new chat voice never alias and legacy chat maps to new chat`() {
        assertTrue(HermexDeepLink.isNewChatUrl(HermexDeepLink.newChatUri()))
        assertTrue(HermexDeepLink.isNewChatUrl(Uri.parse("hermes-agent://chat")))
        assertFalse(HermexDeepLink.isNewChatVoiceUrl(HermexDeepLink.newChatUri()))
        assertTrue(HermexDeepLink.isNewChatVoiceUrl(HermexDeepLink.newChatVoiceUri()))
        assertFalse(HermexDeepLink.isNewChatUrl(HermexDeepLink.newChatVoiceUri()))
    }

    @Test fun `new chat in profile distinguishes bare webui links from server-named ones`() {
        val bare = HermexDeepLink.newChatInProfileUri("Inbox β")!!
        assertTrue(HermexDeepLink.isNewChatInProfileUrl(bare))
        assertTrue(HermexDeepLink.isWebuiNewChatInProfileUrl(bare))
        assertEquals("Inbox β", HermexDeepLink.profileNameFromNewChatInProfile(bare))

        val named = HermexDeepLink.newChatInProfileUri("Inbox β", serverId = "https://h.example.com")!!
        assertTrue(HermexDeepLink.isNewChatInProfileUrl(named))
        assertFalse(HermexDeepLink.isWebuiNewChatInProfileUrl(named))
        val destination = HermesNewChatDestination.parse(named, identity)!!
        assertEquals("https://h.example.com", destination.serverId)
        assertEquals("Inbox β", destination.profile)

        // A blank profile drops the link outright.
        assertNull(HermexDeepLink.newChatInProfileUri("  "))
        assertNull(HermexDeepLink.profileNameFromNewChatInProfile(Uri.parse("hermex-type-j://new-chat-profile?profile=%20")))
        // A server-named link whose server doesn't parse drops rather than taking the webui route.
        assertNull(HermesNewChatDestination.parse(Uri.parse("hermex-type-j://new-chat-profile?server=x&profile=p")) { null })
        // A bare link never parses as a Hermes new chat.
        assertNull(HermesNewChatDestination.parse(bare, identity))
    }

    // MARK: - Share link (#1181)

    @Test fun `share open url round-trips on both schemes`() {
        assertTrue(HermexDeepLink.isShareOpenUrl(HermexDeepLink.shareUri()))
        assertTrue(HermexDeepLink.isShareOpenUrl(Uri.parse("hermes-agent://share")))
        assertFalse(HermexDeepLink.isShareOpenUrl(Uri.parse("hermex-type-j://shared")))
    }
}
