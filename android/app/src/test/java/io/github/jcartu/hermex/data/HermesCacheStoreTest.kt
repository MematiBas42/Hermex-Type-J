package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.hermes.HermesNewestCoverage
import io.github.jcartu.hermex.model.hermes.HermesSessionRow
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HermesCacheStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = System.currentTimeMillis()
    @Test fun `partial pages never sweep other cached rows or same ids in other profiles and servers`() {
        val directory = temp.newFolder()
        val cache = HermesCacheStore(directory, Json { ignoreUnknownKeys = true })
        cache.cacheHermesSessions(listOf(HermesSessionRow("same"), HermesSessionRow("old")), "a", true, "server", now)
        cache.cacheHermesSessions(listOf(HermesSessionRow("same")), "b", true, "server", now)
        cache.cacheHermesSessions(listOf(HermesSessionRow("same")), "a", true, "other", now)
        cache.cacheHermesSessions(listOf(HermesSessionRow("same", title = "changed")), "a", false, "server", now)
        assertEquals(3, cache.cachedHermesSessions("server", null, now).size)
        cache.cacheHermesSessions(listOf(HermesSessionRow("same", title = "changed")), "a", true, "server", now)
        assertEquals(2, cache.cachedHermesSessions("server", null, now).size)
        cache.clearCache("server")
        val reopened = HermesCacheStore(directory, Json { ignoreUnknownKeys = true })
        assertTrue(reopened.cachedHermesSessions("server", null, now).isEmpty())
        assertEquals(1, reopened.cachedHermesSessions("other", "a", now).size)
    }
    private fun row(id: Int) = HermesCachedRow(id, ChatMessage(role = "assistant", content = "row-$id", messageId = "id-$id"))
    @Test fun `newest coverage prunes undone rows but retains older cached pages in display order`() {
        val cache = HermesCacheStore(temp.newFolder(), Json)
        val scope = HermesCacheStore.hermesScope("default", "root")
        cache.cacheHermesMessages("server", scope, listOf(row(40), row(3), row(90), row(8)), HermesNewestCoverage.All, cachedAt = now)
        cache.cacheHermesMessages("server", scope, listOf(row(90), row(7)), HermesNewestCoverage.From(listOf(90)), cachedAt = now)
        assertEquals(listOf("row-40", "row-3", "row-90", "row-7"), cache.cachedHermesMessages("server", scope, 100, now).map { it.content })
        cache.cacheHermesMessages("server", scope, listOf(row(4)), HermesNewestCoverage.All, cachedAt = now)
        assertEquals(listOf("row-4"), cache.cachedHermesMessages("server", scope, 100, now).map { it.content })
    }
    @Test fun `clear revokes a pending writer and bot cleanup keeps other connections and archives independent`() {
        val cache = HermesCacheStore(temp.newFolder(), Json)
        val epoch = cache.hermesCacheEpoch("server")
        val live = HermesCacheStore.hermesBotChatScope("connection", "default", "root")
        val archive = HermesCacheStore.hermesArchivedBotChatScope("connection", "default", "old")
        val other = HermesCacheStore.hermesBotChatScope("other", "default", "root")
        listOf(live, archive, other).forEach { cache.cacheHermesMessages("server", it, listOf(row(1)), HermesNewestCoverage.All, epoch, now) }
        assertEquals("root", cache.cachedHermesBotChatRoot("server", "connection", "default", now))
        cache.removeHermesBotChats("server", "connection", "default")
        assertTrue(cache.cachedHermesMessages("server", live, 100, now).isEmpty())
        assertTrue(cache.cachedHermesMessages("server", archive, 100, now).isEmpty())
        assertEquals(1, cache.cachedHermesMessages("server", other, 100, now).size)
        cache.cacheHermesMessages("server", live, listOf(row(2)), HermesNewestCoverage.All, epoch, now)
        assertTrue(cache.cachedHermesMessages("server", live, 100, now).isEmpty())
        cache.clearAll()
        assertTrue(cache.cachedHermesMessages("server", other, 100, now).isEmpty())
    }
}
