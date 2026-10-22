package io.github.jcartu.hermex.ui.feature.hermes.sessions

import androidx.compose.ui.text.font.FontWeight
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.sessions.SessionSearchExcerpt
import org.junit.Assert.*
import org.junit.Test

class HermesSessionReducersTest {
    @Test fun `paging counts unreadable slots and pins but never duplicates or shows rooms`() {
        val pages = HermesSessionPages()
        val row = HermesSessionRow("tip", lineageRootID = "root", pinned = true)
        pages.append(HermesSessionPage(listOf(row, row.copy(id = "tip2"), HermesSessionRow("arch", archived = true),
            HermesSessionRow("room", title = "Group: abc", hidden = true)), count = 100, total = 10000))
        assertEquals(listOf("tip"), pages.rows.map { it.id })
        assertEquals(100, pages.nextOffset)
        assertTrue(pages.hasMore)
        pages.append(HermesSessionPage(listOf(row), count = 100))
        assertFalse(pages.hasMore)
        assertEquals(200, pages.nextOffset)
    }
    @Test fun `optimistic archive rollback restores exact row position and offset`() {
        val pages = HermesSessionPages()
        val rows = listOf(HermesSessionRow("a"), HermesSessionRow("b"), HermesSessionRow("c"))
        pages.append(HermesSessionPage(rows))
        val before = pages.apply(HermesSessionChange.Archived(true), "b")!!
        assertEquals(listOf("a", "c"), pages.rows.map { it.id })
        assertEquals(99, pages.nextOffset)
        pages.restore(before.first, before.second)
        assertEquals(rows, pages.rows)
        assertEquals(100, pages.nextOffset)
        pages.apply(HermesSessionChange.Pinned(true), "b")
        assertTrue(pages.row("b")!!.pinned == true)
        pages.apply(HermesSessionChange.Title("kept"), "b")
        assertEquals("kept", pages.row("b")!!.title)
        pages.remove("a")
        assertNull(pages.row("a"))
        val archive = HermesSessionPages(true)
        archive.append(HermesSessionPage(listOf(rows[0], rows[1].copy(archived = true), HermesSessionRow("bot", title = "Bot Chat", archived = true, hidden = true))))
        assertEquals(listOf("b", "bot"), archive.rows.map { it.id })
    }
    @Test fun `all Profiles holds back older unpinned rows until every frontier passes them`() {
        val pages = HermesProfilePages()
        pages["a"].append(HermesSessionPage(listOf(HermesSessionRow("new-a", lastActive = 100.0)), count = 100))
        pages["b"].append(HermesSessionPage(listOf(HermesSessionRow("old-b", lastActive = 20.0), HermesSessionRow("pin", lastActive = 1.0, pinned = true))))
        assertEquals(setOf("new-a", "pin"), pages.rows.map { it.second.id }.toSet())
        pages["a"].append(HermesSessionPage(emptyList()))
        assertEquals(setOf("new-a", "old-b", "pin"), pages.rows.map { it.second.id }.toSet())
    }
    @Test fun `FTS snippet marks bold matched prefixes and never leak into text`() {
        val snippet = SessionSearchExcerpt.hermesSnippet("one >>>budget<<< and >>>budgeting<<<\\nnext", "budget")
        assertEquals("one budget and budgeting next", snippet.text)
        assertEquals(listOf("budget", "budgeting"), snippet.highlighted().spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
            .map { snippet.text.substring(it.start, it.end) })
        val stray = SessionSearchExcerpt.hermesSnippet("<<<plain >>>tail", "tail")
        assertEquals("plain tail", stray.text)
        assertEquals("tail", stray.highlighted().spanStyles.single().let { stray.text.substring(it.start, it.end) })
        val unmarked = SessionSearchExcerpt.hermesSnippet("你好世界", "世界")
        assertEquals(2, unmarked.highlighted().spanStyles.single().start)
    }
    @Test fun `search merges by Profile and lineage preserving host ordering and row metadata`() {
        val loaded = HermesListedSession("a", HermesSessionRow("tip", title = "other", lineageRootID = "root", pinned = true, unread = true))
        val state = HermesSessionListState(sessions = listOf(loaded), query = "needle", searchResults = listOf(
            HermesListedSession("a", HermesSessionRow("tip", lineageRootID = "root")),
            HermesListedSession("b", HermesSessionRow("tip", lineageRootID = "root", title = "different store")),
            HermesListedSession("a", HermesSessionRow("room", title = "Group: x"))))
        assertEquals(listOf(loaded, state.searchResults[1]), state.visibleSessions())
        assertTrue(state.visibleSessions().first().row.unread == true)
    }
    @Test fun `live-state derivation ignores unknown and is_active guesses with input winning`() {
        val items = BotJSON.parse("""{"sessions":[{"session_key":"a","status":"starting"},{"session_key":"a","status":"waiting"},{"session_key":"a","status":"streaming"},{"session_key":"b","status":"resuming","is_active":true},{"session_key":"c","status":"new-future-state"}]}""")["sessions"].list!!
        assertEquals(mapOf("a" to HermesSessionRowState.INPUT), HermesSessionRowStates.from(items))
        assertNull(BotLiveStatus.fromWire("idle"))
        assertEquals(BotLiveStatus.WORKING, BotLiveStatus.fromWire("working"))
    }
}
