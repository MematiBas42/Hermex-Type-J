package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import io.github.jcartu.hermex.model.hermes.BotRoomLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BotHistoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val server = "https://server.example"
    private val key = BotRoomKey(server, "connection", "fixture-room")
    private val room = requireNotNull(BotGroupRoom.fromJsonOrNull(RoomTestFixture.room(latest = 4)))

    @Test
    fun `disk search stores only bounded room messages and preserves thread identity`() = runBlocking {
        val directory = temporary.newFolder("history")
        val cache = BotHistoryCache(directory)
        val rows = listOf(
            RoomTestFixture.event(1, text = "Newport root", threadID = "thread:one", eventID = "root:event"),
            RoomTestFixture.event(2, kind = "tool.complete", text = "Newport credential output"),
            RoomTestFixture.event(3, kind = "message.member", text = "Newport reply", threadID = "thread:one"),
            RoomTestFixture.event(4, kind = "future.event", text = "Newport future"),
        )
        cache.appendRoom(key, room, RoomTestFixture.page(rows, cursor = 4), since = 0)
        val snapshot = cache.roomHistory(key)
        assertEquals(listOf(1, 3), snapshot?.messages?.mapNotNull { it.seq })
        assertEquals(4, snapshot?.cursor)
        assertEquals("thread:one", snapshot?.replayPage?.get("events")?.list?.first()?.get("payload")?.get("thread_id")?.text)
        assertEquals("root:event", snapshot?.replayPage?.get("events")?.list?.first()?.get("event_id")?.text)

        val restored = BotHistoryCache(directory)
        val hits = restored.search("NEWPORT", server, "connection", setOf("fixture-room"))
        assertEquals(listOf(3, 1), hits.map { it.sequence })
        assertEquals("Comms", hits.first().roomName)
        assertEquals("fixture-room", hits.first().cachedRoom.id)
        assertTrue(restored.search("credential", server, "connection", null).isEmpty())
        assertTrue(restored.search("Newport", server, "connection", emptySet()).isEmpty())
    }

    @Test
    fun `per-room rows message bytes and shared search results are bounded`() = runBlocking {
        val cache = BotHistoryCache()
        val rows = (1..501).map { RoomTestFixture.event(it, text = "Needle $it") } +
            RoomTestFixture.event(502, text = "Needle " + "x".repeat(BotHistoryCache.MAXIMUM_MESSAGE_BYTES + 1))
        cache.appendRoom(key, room, RoomTestFixture.page(rows, 502), since = 0)
        val snapshot = cache.roomHistory(key)
        assertEquals(BotHistoryCache.MAXIMUM_MESSAGES, snapshot?.messages?.size)
        assertEquals(1, snapshot?.earlierBoundary)
        assertEquals(502, snapshot?.cursor)
        assertTrue(snapshot?.messages?.none { it.seq == 502 } == true)
        assertEquals(
            BotHistoryCache.MAXIMUM_HITS,
            cache.search("Needle", server, key.connectionID, roomIDs = null).size,
        )
    }

    @Test
    fun `synchronous clear fence rejects old writer and delayed deletion preserves new page`() = runBlocking {
        var clock = 10L
        val cache = BotHistoryCache(temporary.newFolder("clear")) { clock }
        val oldWriter = cache.beginWriter(key)
        val cutoff = 20L
        cache.markServerCleared(server, cutoff)

        clock = 30L
        cache.appendRoom(
            key,
            room,
            RoomTestFixture.page(listOf(RoomTestFixture.event(1)), 1),
            since = 0,
            receivedAtMillis = clock,
            writer = oldWriter,
        )
        assertNull(cache.roomHistory(key, nowMillis = clock))

        val newWriter = cache.beginWriter(key)
        cache.appendRoom(
            key,
            room,
            RoomTestFixture.page(listOf(RoomTestFixture.event(1)), 1),
            since = 0,
            receivedAtMillis = clock,
            writer = newWriter,
        )
        assertNotNull(cache.roomHistory(key, nowMillis = clock))
        cache.clearServer(server, cutoff)
        assertNotNull(cache.roomHistory(key, nowMillis = clock))
    }

    @Test
    fun `room and connection removal revoke late writers without crossing scopes`() = runBlocking {
        var clock = 100L
        val cache = BotHistoryCache(temporary.newFolder("revoke")) { clock }
        val writer = cache.beginWriter(key)
        cache.removeRoom(key)
        clock += 1
        cache.appendRoom(key, room, RoomTestFixture.page(listOf(RoomTestFixture.event(1)), 1), 0, clock, writer)
        assertNull(cache.roomHistory(key, clock))

        val second = key.copy(roomID = "second")
        val secondRoom = requireNotNull(BotGroupRoom.fromJsonOrNull(RoomTestFixture.room(id = "second")))
        val secondWriter = cache.beginWriter(second)
        cache.removeConnection(server, key.connectionID)
        clock += 1
        cache.appendRoom(
            second,
            secondRoom,
            RoomTestFixture.page(listOf(RoomTestFixture.event(1, roomID = "second")), 1),
            0,
            clock,
            secondWriter,
        )
        assertNull(cache.roomHistory(second, clock))
    }

    @Test
    fun `authoritative room list revokes a pending writer even before its first snapshot`() = runBlocking {
        val cache = BotHistoryCache()
        val writer = cache.beginWriter(key)
        cache.retainRooms(emptySet(), server, key.connectionID)
        cache.appendRoom(
            key,
            room,
            RoomTestFixture.page(listOf(RoomTestFixture.event(1)), 1),
            since = 0,
            writer = writer,
        )
        assertNull(cache.roomHistory(key))
    }

    @Test
    fun `server removal retires old scopes but a fresh connection at the same address can cache`() = runBlocking {
        var clock = 100L
        val cache = BotHistoryCache(temporary.newFolder("server-removal")) { clock }
        val oldWriter = cache.beginWriter(key)
        cache.appendRoom(key, room, RoomTestFixture.page(listOf(RoomTestFixture.event(1)), 1), 0, clock, oldWriter)

        clock = 200L
        cache.removeServer(server)
        assertNull(cache.roomHistory(key, clock))

        val freshKey = key.copy(connectionID = "fresh-connection")
        val freshWriter = cache.beginWriter(freshKey)
        cache.appendRoom(
            freshKey,
            room,
            RoomTestFixture.page(listOf(RoomTestFixture.event(1)), 1),
            0,
            clock,
            freshWriter,
        )
        assertNotNull(cache.roomHistory(freshKey, clock + 1))

        cache.appendRoom(key, room, RoomTestFixture.page(listOf(RoomTestFixture.event(2)), 2), 1, clock + 1, oldWriter)
        assertNull(cache.roomHistory(key, clock + 1))
    }

    @Test
    fun `recent transcript newest owner wins and removal invalidates stale saves`() {
        val recent = BotRecentTranscripts(maximumEntries = 2)
        val recentKey = BotRecentTranscripts.Key(key)
        val old = recent.begin(recentKey)
        val current = recent.begin(recentKey)
        val first = BotRoomLog().applyPage(RoomTestFixture.page(listOf(RoomTestFixture.event(1, text = "current")), 1))
        val stale = BotRoomLog().applyPage(RoomTestFixture.page(listOf(RoomTestFixture.event(1, text = "stale")), 1))
        recent.save(first, recentKey, current)
        recent.save(stale, recentKey, old)
        assertEquals("current", recent.log(recentKey)?.events?.first()?.payload?.get("text")?.text)
        recent.remove { it == recentKey }
        recent.save(stale, recentKey, current)
        assertNull(recent.log(recentKey))
    }
}
