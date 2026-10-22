package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotLiveStatuses
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomCapabilities
import io.github.jcartu.hermex.net.hermes.BotLiveStatus
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Swift BotInbox/BotProfile vectors: server metadata, grouping, attention and guarded writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class BotInboxTest {
    private fun bot(id: String, time: Double? = null, look: String = "{}", root: String = id, tip: String? = null): BotProfile =
        requireNotNull(BotProfile.fromJsonOrNull(BotJSON.parse("""{"name":"$id","ui_meta":{"hermes-bots":$look},"canonical_session":{"id":"$root","resolved_id":${tip?.let { "\"$it\"" } ?: "null"},"last_active":${time ?: "null"}},"unknown":{}}""")))

    @Test fun `profile identity and appearance tolerance follow Desktop`() {
        val row = BotJSON.parse("""{"name":"default","display_name":" Core ","description":"Core role","has_avatar":true,"ui_meta":{"hermes-bots":{"title":" Desktop ","description":"Role","sectionId":" sec ","sectionName":" Team ","future":{"a":1},"imageKind":"photo"}},"ui_meta_revisions":{"hermes-bots":8}}""")
        val profile = requireNotNull(BotProfile.fromJsonOrNull(row))
        assertEquals("default", profile.id)
        assertEquals("Desktop", profile.name)
        assertEquals("Role", profile.description)
        assertEquals("sec", profile.sectionID)
        assertEquals("Team", profile.sectionName)
        assertEquals(8, profile.lookRevision)
        assertTrue(profile.isPhoto)
        assertEquals(1, profile.look.getValue("future")["a"].integer)
        assertEquals("Hermes", bot("default").name)
        assertNull(BotProfile.fromJsonOrNull(BotJSON.parse("""{"name":42}""")))
        assertNull(BotProfile.fromJsonOrNull(BotJSON.parse("""{"name":""}""")))
    }

    @Test fun `waiting then working then unread then recency in each section`() {
        val profiles = listOf(bot("newest", 900.0), bot("working", 100.0), bot("waiting", 50.0), bot("unread", 200.0), bot("undated"))
        val state = BotInbox.State(profiles = profiles, seen = mapOf("unread" to 100.0), liveStatuses = mapOf("working" to BotLiveStatus.WORKING, "waiting" to BotLiveStatus.WAITING))
        assertEquals(listOf("bot:waiting", "bot:working", "bot:unread", "bot:newest", "bot:undated"), state.sections.single().chats.map { it.id })
        assertFalse(state.isUnread(profiles[0]))
    }

    @Test fun `pinned hidden sections and rooms remain in their own blocks`() {
        val profiles = listOf(
            bot("pin", 1.0, """{"pinned":true,"sectionId":"p","sectionName":"Pinned"}"""),
            bot("hidden", 900.0, """{"hidden":true,"pinned":true,"sectionId":"h","sectionName":"Hidden"}"""),
            bot("alpha", 2.0, """{"sectionId":"a","sectionName":"Alpha"}"""),
            bot("unnamed", 5.0, """{"sectionId":"orphan"}"""),
        )
        val room = BotGroupRoom("room", "Group", emptyList(), 4.0, 1, "own", 1, false)
        val state = BotInbox.State(profiles = profiles, rooms = listOf(room), roomCapabilities = BotRoomCapabilities(true, "own", 200, emptySet()))
        assertEquals(listOf("bot:pin"), state.pinned.map { it.id })
        assertEquals(listOf("Alpha", null), state.sections.map { it.name })
        assertEquals(listOf("bot:unnamed", "room:room"), state.sections.last().chats.map { it.id })
        assertEquals(listOf("Alpha", "Hidden", "Pinned"), state.sectionNames.map { it.name })
        assertEquals(listOf("Alpha", "Hidden"), state.reorderableSectionNames.map { it.name })
        assertEquals("bot:hidden", state.copy(showsHidden = true).sections.first { it.name == "Hidden" }.chats.single().id)
        assertEquals("hidden", state.rows("hidden").hidden.single().id)
    }

    @Test fun `section majority name ties follow first roster member and placement ignores dead ids`() {
        val profiles = listOf(
            bot("one", look = """{"sectionId":"s","sectionName":"Zebra"}"""),
            bot("two", look = """{"sectionId":"s","sectionName":"Renamed"}"""),
            bot("three", look = """{"sectionId":"a","sectionName":"Alpha"}"""),
        )
        val tied = BotInbox.State(profiles = profiles, sectionOrder = listOf("gone", "s", "s"))
        assertEquals(listOf("Zebra", "Alpha"), tied.sectionNames.map { it.name })
        val majority = tied.copy(profiles = profiles + bot("four", look = """{"sectionId":"s","sectionName":"Renamed"}"""))
        assertEquals("Renamed", majority.sectionNames.first().name)
        assertEquals(listOf("Alpha", "Renamed"), majority.copy(sectionOrder = emptyList()).sectionNames.map { it.name })
    }

    @Test fun `ambiguous stored keys never claim another Profiles live work`() {
        val profiles = listOf(bot("a", root = "shared"), bot("b", root = "shared"), bot("c", root = "root", tip = "tip"))
        val items = listOf(
            BotJSON.parse("""{"session_key":"shared","status":"waiting"}"""),
            BotJSON.parse("""{"session_key":"tip","status":"working"}"""),
            BotJSON.parse("""{"session_key":"root","status":"waiting"}"""),
            BotJSON.parse("""{"session_key":"tip","status":"resuming"}"""),
        )
        assertEquals(mapOf("c" to BotLiveStatus.WAITING), BotLiveStatuses.statuses(items, profiles))
    }

    @Test fun `section writes retain Desktop fields include CAS and remove with explicit null`() = runTest {
        val row = BotJSON.parse("""{"name":"bot","ui_meta":{"hermes-bots":{"title":"Helper","desktopOnly":42,"sectionId":"a","sectionName":"A"}},"ui_meta_revisions":{"hermes-bots":7}}""")
        val transport = ScriptedWire(row)
        val inbox = inbox(transport, backgroundScope)
        inbox.open()
        val profile = inbox.current.profiles.single()
        inbox.removeFromSection(profile)
        val params = transport.calls.filterIsInstance<HermesCall.ProfilesConfigure>().single().params()
        assertEquals(42, params.getValue("ui_meta")["hermes-bots"]["desktopOnly"].integer)
        assertTrue(params.getValue("ui_meta")["hermes-bots"]["sectionId"].isNull)
        assertTrue(params.getValue("ui_meta")["hermes-bots"]["sectionName"].isNull)
        assertEquals(7, params.getValue("ui_meta_expected_revisions")["hermes-bots"].integer)
        assertNotNull(inbox.current.notice)
        inbox.close()
    }

    @Test fun `first load seeds quiet unread and return marks next read seen`() = runTest {
        val transport = ScriptedWire(BotJSON.parse("""{"name":"bot","canonical_session":{"last_active":1}}"""))
        val inbox = inbox(transport, backgroundScope)
        inbox.open()
        assertFalse(inbox.current.isUnread(inbox.current.profiles.single()))
        transport.row = BotJSON.parse("""{"name":"bot","canonical_session":{"last_active":2}}""")
        inbox.open()
        assertTrue(inbox.current.isUnread(inbox.current.profiles.single()))
        inbox.noteReturn(inbox.current.profiles.single())
        transport.row = BotJSON.parse("""{"name":"bot","canonical_session":{"last_active":3}}""")
        inbox.open()
        assertEquals(3.0, inbox.current.seen["bot"])
        assertFalse(inbox.current.isUnread(inbox.current.profiles.single()))
        inbox.close()
    }

    @Test fun `rejected password stays unsent until saved sign in changes`() = runTest {
        val transport = ScriptedWire(BotJSON.parse("""{"name":"bot"}""")).also { it.connectFailure = BotFailure.Rejected(401) }
        val inbox = inbox(transport, backgroundScope)
        inbox.open()
        assertTrue(inbox.current.needsSignIn)
        inbox.open()
        runCurrent()
        assertEquals(1, transport.connects)
        inbox.signInSaved()
        transport.connectFailure = null
        inbox.open()
        assertEquals(2, transport.connects)
        inbox.close()
    }

    @Test fun `section id follows Desktop base36 shape`() {
        assertTrue(BotInbox.newSectionID(1_700_000_000_000, java.util.Random(2)).matches(Regex("sec-[a-z0-9]+-[a-z0-9]{5}")))
    }

    private fun inbox(wire: ScriptedWire, scope: kotlinx.coroutines.CoroutineScope): BotInbox {
        val defaults = BotLocalDefaults.InMemory()
        val record = BotConnection(name = "host", address = "https://host.example/".toHttpUrl(), username = "fixture", password = "fixture")
        val records = object : BotInbox.ConnectionRecords {
            override fun load(server: String) = record
            override fun save(connection: BotConnection, server: String) = Unit
        }
        return BotInbox("https://host.example", scope, records, BotUnreadStore(defaults), BotRoomOrganizeStore(defaults), BotSectionOrderStore(defaults), BotAvatarStore(), { wire })
    }

    private class ScriptedWire(var row: BotJSON) : BotTransport {
        override var onEvent: ((BotJSON) -> Unit)? = null
        override var onDisconnect: ((Throwable) -> Unit)? = null
        val calls = mutableListOf<HermesCall>()
        var connects = 0
        var connectFailure: Throwable? = null
        override suspend fun connect() { connects++; connectFailure?.let { throw it } }
        override suspend fun close() = Unit
        override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
            validateDispatch?.invoke()
            calls += call
            return when (call) {
                is HermesCall.ProfilesList -> BotJSON.objectOf("profiles" to BotJSON.array(listOf(row)))
                HermesCall.GroupsCapabilities -> BotJSON.NULL
                HermesCall.SessionActiveList -> BotJSON.objectOf("sessions" to BotJSON.array(emptyList()))
                is HermesCall.ProfilesConfigure -> BotJSON.parse("""{"applied":{"ui_meta":false,"ui_meta_conflicts":["hermes-bots"]}}""")
                else -> throw AssertionError("Unexpected ${call.method}")
            }
        }
    }
}
