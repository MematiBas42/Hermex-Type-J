package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoomReaderTest {
    private val server = "https://server.example"
    private val connection = BotConnection(
        id = "connection-one",
        name = "Mac",
        address = "https://mac.example".toHttpUrl(),
        username = "user",
        password = "password",
    )

    @Test
    fun `thread reply unknown outcome stays scoped and explicit retry reuses event thread and text`() = runBlocking {
        val wire = FakeRoomTransport()
        wire.events += RoomTestFixture.event(1, text = "Desktop root", threadID = "thread:desktop", eventID = "desktop:event")
        val reader = reader(wire, this)
        reader.open("owner")
        assertEquals(listOf("thread:desktop"), reader.current.threads.map { it.id })

        reader.setDraft("  Phone reply\n", "thread:desktop")
        wire.loseNextWrite = true
        reader.send(threadID = "thread:desktop")
        val first = wire.writes.single { it.first == "groups.send" }.second
        assertEquals("thread:desktop", first["payload"]?.get("thread_id")?.text)
        assertEquals("  Phone reply\n", first["payload"]?.get("text")?.text)
        assertEquals("  Phone reply\n", reader.draft("thread:desktop"))
        assertNotNull(reader.uncertainSend("thread:desktop"))
        assertNull(reader.uncertainSend())
        assertEquals(listOf("Desktop root"), reader.current.events.map { it.payload["text"].text })

        reader.send(threadID = "thread:desktop")
        assertEquals(1, wire.writes.count { it.first == "groups.send" })
        reader.open("owner")
        assertEquals(1, wire.writes.count { it.first == "groups.send" })
        reader.send(retry = true, threadID = "thread:desktop")
        val sends = wire.writes.filter { it.first == "groups.send" }
        assertEquals(2, sends.size)
        assertEquals(first, sends[1].second)
        assertNull(reader.uncertainSend("thread:desktop"))
        assertEquals("", reader.draft("thread:desktop"))
        assertEquals(listOf("Desktop root", "Phone reply"), reader.current.events.map { it.payload["text"].text })
        reader.close()
    }

    @Test
    fun `welcome survives system history and ends with first conversation message`() = runBlocking {
        val wire = FakeRoomTransport()
        wire.events += RoomTestFixture.event(1, kind = "room.renamed", text = "Renamed")
        val reader = reader(wire, this)
        reader.open("owner")
        assertTrue(reader.showsWelcome)
        reader.setDraft("Hello group")
        reader.send()
        assertFalse(reader.showsWelcome)
        reader.close()
    }

    @Test
    fun `foreign authority prevents every participant write`() = runBlocking {
        val wire = FakeRoomTransport().apply { roomAuthority = "foreign" }
        val reader = reader(wire, this)
        reader.open("owner")
        assertTrue(reader.current.foreignAuthority)
        assertFalse(reader.showsComposer)
        reader.setDraft("hello")
        reader.send()
        reader.stop()
        reader.rename("No")
        reader.disband()
        assertTrue(wire.writes.isEmpty())
        reader.close()
    }

    @Test
    fun `stop rename and disband follow fresh state and acknowledged lifecycle results`() = runBlocking {
        val wire = FakeRoomTransport().apply { status = RoomTestFixture.status(running = 1) }
        var disbanded = 0
        val reader = BotRoomReader(
            key = BotRoomKey(server, connection.id, "fixture-room"),
            connection = connection,
            room = requireNotNull(BotGroupRoom.fromJsonOrNull(RoomTestFixture.room())),
            scope = this,
            cache = BotHistoryCache(),
            makeWire = { wire },
            onDisbanded = { disbanded += 1 },
        )
        reader.open("owner")
        assertTrue(reader.mayStop)
        reader.stop()
        assertTrue(reader.finishingStop)
        assertEquals("groups.stop", wire.writes.last().first)

        wire.status = RoomTestFixture.status()
        reader.poll()
        assertFalse(reader.finishingStop)
        reader.rename("Renamed")
        assertEquals("Renamed", reader.current.room.name)
        assertEquals("groups.rename", wire.writes.last().first)

        reader.disband()
        assertEquals(1, disbanded)
        assertEquals("groups.disband", wire.writes.last().first)
        assertEquals(BotRoomReader.Link.IDLE, reader.current.link)
        assertFalse(reader.current.uncertainDisband)
        assertNull(reader.current.commandMessage)
    }

    @Test
    fun `approval dispatch is once only after an uncertain answer and permanent choices never send`() = runBlocking {
        val wire = FakeRoomTransport().apply { status = RoomTestFixture.status(actions = listOf(RoomTestFixture.approval)) }
        val reader = reader(wire, this)
        reader.open("owner")
        val action = requireNotNull(reader.current.status.actions.firstOrNull())
        reader.act(action, BotApprovalChoice.ALWAYS)
        assertTrue(wire.writes.isEmpty())

        wire.loseNextWrite = true
        reader.act(action, BotApprovalChoice.ONCE)
        assertEquals(1, wire.writes.count { it.first == "groups.approve" })
        assertFalse(reader.mayAct(action))
        reader.open("owner")
        assertFalse(reader.mayAct(action))
        reader.act(action, BotApprovalChoice.DENY)
        assertEquals(1, wire.writes.count { it.first == "groups.approve" })
        reader.close()
    }

    @Test
    fun `create retry freezes room id name member order and optional display payload`() = runBlocking {
        val wire = FakeRoomTransport()
        val store = object : BotInbox.ConnectionRecords {
            var value: BotConnection? = connection
            override fun load(server: String): BotConnection? = value
            override fun save(connection: BotConnection, server: String) { value = connection }
        }
        val first = profile("default", "Hermes")
        val second = profile("dev", null)
        val creator = BotRoomCreator(
            server = server,
            connection = connection,
            roster = listOf(first, second),
            scope = this,
            store = store,
            makeWire = { wire },
        )
        creator.select(first)
        creator.select(second)
        creator.setName("Frozen team")
        wire.loseNextWrite = true
        creator.create()

        val initial = wire.writes.single { it.first == "groups.create" }.second
        assertNotNull(creator.current.attempt)
        creator.setName("Changed locally")
        creator.remove(first)
        assertEquals("Frozen team", creator.current.name)
        assertEquals(2, creator.current.selected.size)
        assertNotNull(java.util.UUID.fromString(initial["room_id"]?.text))
        assertEquals(2, initial["members"]?.list?.size)
        assertEquals("default", initial["members"]?.list?.get(0)?.get("handle")?.text)
        assertEquals("Hermes", initial["members"]?.list?.get(0)?.get("display_name")?.text)
        assertTrue(initial["members"]?.list?.get(1)?.get("display_name")?.isNull == true)
        assertTrue(initial["members"]?.list?.all { it["target"].isNull } == true)

        creator.create()
        val creates = wire.writes.filter { it.first == "groups.create" }
        assertEquals(2, creates.size)
        assertEquals(initial, creates[1].second)
        assertEquals(creator.current.attempt?.roomID, creator.current.created?.id)
    }

    private fun reader(wire: FakeRoomTransport, scope: kotlinx.coroutines.CoroutineScope): BotRoomReader = BotRoomReader(
        key = BotRoomKey(server, connection.id, "fixture-room"),
        connection = connection,
        room = requireNotNull(BotGroupRoom.fromJsonOrNull(RoomTestFixture.room())),
        scope = scope,
        cache = BotHistoryCache(),
        makeWire = { wire },
    )

    private fun profile(id: String, displayName: String?): BotProfile {
        val fields = linkedMapOf("name" to BotJSON.string(id))
        displayName?.let { fields["display_name"] = BotJSON.string(it) }
        return requireNotNull(BotProfile.fromJsonOrNull(BotJSON.objectOf(fields)))
    }
}
