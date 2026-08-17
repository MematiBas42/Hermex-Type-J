package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.model.CachePolicy
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.SessionSummary
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CacheStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("cache-store-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun newStore(): CacheStore = CacheStore(directory, json)

    private val serverURL = "https://hermes.example.com:8080"

    private fun fullSession(id: String) = SessionSummary(
        sessionId = id,
        title = "Title $id",
        workspace = "/work/space",
        model = "claude-sonnet",
        modelProvider = "anthropic",
        messageCount = 12,
        createdAt = 1_700_000_000.5,
        updatedAt = 1_700_000_100.0,
        lastMessageAt = 1_700_000_200.25,
        pinned = true,
        archived = false,
        projectId = "proj-1",
        profile = "default",
        inputTokens = 123,
        outputTokens = 456,
        estimatedCost = 0.0123,
        activeStreamId = "stream-1",
        isStreaming = true,
        isCliSession = false,
        userMessageCount = 4,
        hasPendingUserMessage = true,
        pendingStartedAt = 1_700_000_150.0,
        worktreePath = "/work/tree",
        sourceTag = "tag",
        rawSource = "raw",
        sessionSource = "cli",
        sourceLabel = "CLI",
        parentSessionId = "parent-1",
        relationshipType = "fork",
        readOnly = false,
        isReadOnly = false,
        matchType = "title",
    )

    // MARK: - Sessions

    @Test
    fun sessionsRoundTripThroughDiskFilteringArchivedAndKeylessRows() {
        val full = fullSession("s1")
        val minimal = SessionSummary(sessionId = "s2")
        val archived = SessionSummary(sessionId = "s3", archived = true)
        val keyless = SessionSummary(title = "no session id")

        newStore().cacheSessions(listOf(full, minimal, archived, keyless), serverURL, cachedAt = 1_000L)

        // Fresh instance: everything must come back from the JSON files.
        val restored = newStore().cachedSessions(serverURL, now = 2_000L)

        // isStreaming is carried through the cache; matchType is always null.
        assertEquals(listOf(full.copy(matchType = null), minimal), restored)
        assertEquals(true, restored.first().isStreaming)
        assertNull(restored.first().matchType)
    }

    @Test
    fun sessionsExpireAfterTtl() {
        newStore().cacheSessions(listOf(fullSession("s1")), serverURL, cachedAt = 1_000L)

        val store = newStore()
        assertEquals(1, store.cachedSessions(serverURL, now = 1_000L + CachePolicy.ttl - 1).size)
        // Swift filter is `expiresAt > now`, so the exact expiry instant is stale.
        assertTrue(store.cachedSessions(serverURL, now = 1_000L + CachePolicy.ttl).isEmpty())
    }

    @Test
    fun secondCacheSessionsCallDropsStaleKeys() {
        val store = newStore()
        store.cacheSessions(listOf(fullSession("s1"), fullSession("s2")), serverURL, cachedAt = 1_000L)
        store.cacheSessions(listOf(fullSession("s1")), serverURL, cachedAt = 1_100L)

        assertEquals(listOf("s1"), newStore().cachedSessions(serverURL, now = 1_200L).map { it.sessionId })
    }

    @Test
    fun cachingArchivedSessionDeletesItsEntry() {
        val store = newStore()
        store.cacheSessions(listOf(fullSession("s1")), serverURL, cachedAt = 1_000L)
        store.cacheSession(fullSession("s1").copy(archived = true), serverURL, cachedAt = 1_100L)

        assertTrue(newStore().cachedSessions(serverURL, now = 1_200L).isEmpty())
    }

    @Test
    fun clearCacheRemovesOnlyTheGivenServer() {
        val otherServer = "https://other.example.com"
        val store = newStore()
        store.cacheSessions(listOf(fullSession("s1")), serverURL, cachedAt = 1_000L)
        store.cacheSessions(listOf(fullSession("s2")), otherServer, cachedAt = 1_000L)
        store.cacheMessages(listOf(ChatMessage(messageId = "m1")), serverURL, "s1", cachedAt = 1_000L)
        store.cacheMessages(listOf(ChatMessage(messageId = "m2")), otherServer, "s2", cachedAt = 1_000L)

        store.clearCache(serverURL)

        val reloaded = newStore()
        assertTrue(reloaded.cachedSessions(serverURL, now = 1_100L).isEmpty())
        assertTrue(reloaded.cachedMessages(serverURL, "s1", now = 1_100L).isEmpty())
        assertEquals(listOf("s2"), reloaded.cachedSessions(otherServer, now = 1_100L).map { it.sessionId })
        assertEquals(listOf("m2"), reloaded.cachedMessages(otherServer, "s2", now = 1_100L).map { it.messageId })

        reloaded.clearAll()
        assertTrue(newStore().cachedSessions(otherServer, now = 1_100L).isEmpty())
        assertTrue(newStore().cachedMessages(otherServer, "s2", now = 1_100L).isEmpty())
    }

    // MARK: - Messages

    @Test
    fun messagesRoundTripIncludingJsonValuesAndAttachments() {
        val rich = ChatMessage(
            role = "assistant",
            content = "hello",
            timestamp = 1_700_000_000.5,
            messageId = "m1",
            name = "assistant-name",
            toolCallId = "tc-1",
            toolUseId = "tu-1",
            toolCalls = listOf(
                JSONValue.objectOf(
                    mapOf(
                        "id" to JSONValue.string("call_1"),
                        "index" to JSONValue.number(0.0),
                        "ok" to JSONValue.bool(true),
                    )
                )
            ),
            contentParts = listOf(
                JSONValue.string("part"),
                JSONValue.array(listOf(JSONValue.NULL, JSONValue.number(2.5))),
            ),
            reasoning = "thinking",
            attachments = listOf(
                MessageAttachment(name = "a.png", path = "/tmp/a.png", mime = "image/png", size = 10, isImage = true)
            ),
            turnTps = 3.5,
        )
        // Empty lists are not stored (mirrors Swift apply()), so they come back null.
        val emptyLists = ChatMessage(
            role = "user",
            content = "hi",
            toolCalls = emptyList(),
            contentParts = emptyList(),
            attachments = emptyList(),
        )

        newStore().cacheMessages(listOf(rich, emptyLists), serverURL, "sess-1", cachedAt = 1_000L)

        val restored = newStore().cachedMessages(serverURL, "sess-1", now = 2_000L)
        assertEquals(
            listOf(
                rich,
                emptyLists.copy(toolCalls = null, contentParts = null, attachments = null),
            ),
            restored,
        )
    }

    @Test
    fun limitReturnsLastMessagesInAscendingOrder() {
        val messages = (0 until 5).map { ChatMessage(messageId = "m$it", timestamp = it.toDouble()) }
        val store = newStore()
        store.cacheMessages(messages, serverURL, "sess-1", cachedAt = 1_000L)

        assertEquals(
            listOf("m3", "m4"),
            store.cachedMessages(serverURL, "sess-1", limit = 2, now = 1_100L).map { it.messageId },
        )
        assertEquals(
            listOf("m0", "m1", "m2", "m3", "m4"),
            store.cachedMessages(serverURL, "sess-1", limit = 10, now = 1_100L).map { it.messageId },
        )
        assertTrue(store.cachedMessages(serverURL, "sess-1", limit = 0, now = 1_100L).isEmpty())
        assertTrue(store.cachedMessages(serverURL, "sess-1", limit = -1, now = 1_100L).isEmpty())
    }

    @Test
    fun messagesExpireAfterTtl() {
        newStore().cacheMessages(listOf(ChatMessage(messageId = "m1")), serverURL, "sess-1", cachedAt = 1_000L)

        val store = newStore()
        assertEquals(1, store.cachedMessages(serverURL, "sess-1", now = 1_000L + CachePolicy.ttl - 1).size)
        assertTrue(store.cachedMessages(serverURL, "sess-1", now = 1_000L + CachePolicy.ttl).isEmpty())
    }

    @Test
    fun secondCacheMessagesCallDropsStaleKeys() {
        val store = newStore()
        store.cacheMessages(
            listOf(ChatMessage(messageId = "m1"), ChatMessage(messageId = "m2")),
            serverURL,
            "sess-1",
            cachedAt = 1_000L,
        )
        store.cacheMessages(listOf(ChatMessage(messageId = "m1")), serverURL, "sess-1", cachedAt = 1_100L)

        assertEquals(
            listOf("m1"),
            newStore().cachedMessages(serverURL, "sess-1", now = 1_200L).map { it.messageId },
        )
    }

    @Test
    fun overflowBeyondMaxMessagesEvictsOldestByComparator() {
        val messages = (0..CachePolicy.maxMessages).map { // maxMessages + 1 rows
            ChatMessage(messageId = "m$it", timestamp = it.toDouble())
        }
        newStore().cacheMessages(messages, serverURL, "sess-1", cachedAt = 1_000L)

        val restored = newStore().cachedMessages(serverURL, "sess-1", now = 1_100L)
        // Same cachedAt everywhere, so the timestamp tie-break evicts m0.
        assertEquals(CachePolicy.maxMessages, restored.size)
        assertEquals("m1", restored.first().messageId)
        assertEquals("m${CachePolicy.maxMessages}", restored.last().messageId)
    }
}
