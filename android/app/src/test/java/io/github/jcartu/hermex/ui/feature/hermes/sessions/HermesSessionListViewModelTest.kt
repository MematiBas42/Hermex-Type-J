package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.data.HermesCacheStore
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.sessions.SessionRowAttentionState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HermesSessionListViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val server = "https://hermes.example"
    private val prefs = MemoryListPreferences().apply { selected = "default" }
    private val wire = SessionTestWire()
    private val models = mutableListOf<HermesSessionListViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { runTest(dispatcher) { models.forEach { it.close() } }; Dispatchers.resetMain() }
    private fun model(cache: HermesCacheStore? = null, profile: String? = "default", archived: Boolean = false) = HermesSessionListViewModel(
        server, HermesAddress.parse(server), { wire }, prefs, cache, profile, archived, dispatcher,
    ).also(models::add)
    private fun setRows(vararg rows: HermesSessionRow) {
        wire.onPage = { _, _, _, scheduled -> HermesSessionPage(if (scheduled) emptyList() else rows.toList()) }
    }

    @Test fun `cache is provisional before connect and host rows replace it after connect`() = runTest(dispatcher) {
        val cache = HermesCacheStore(temp.newFolder(), Json)
        cache.cacheHermesSessions(listOf(HermesSessionRow("cached", title = "Saved")), "default", true, server)
        val gate = CompletableDeferred<Unit>()
        wire.onConnect = { gate.await() }
        setRows(HermesSessionRow("live", title = "Host"))
        val vm = model(cache)
        val opening = launch { vm.open() }
        runCurrent()
        assertEquals(listOf("cached"), vm.uiState.value.sessions.map { it.row.id })
        assertTrue(vm.uiState.value.isViewingCachedData)
        assertTrue(vm.uiState.value.isCachedCopyProvisional)
        assertFalse(vm.uiState.value.showsOfflineCache)
        vm.toggleUnread(vm.uiState.value.sessions.single())
        assertTrue(wire.writes.isEmpty())
        gate.complete(Unit); opening.join(); runCurrent()
        assertEquals(listOf("live"), vm.uiState.value.sessions.map { it.row.id })
        assertTrue(vm.uiState.value.isLive)
        assertFalse(vm.uiState.value.isViewingCachedData)
    }
    @Test fun `cache reads last listed without turning it into a saved Profile pick`() = runTest(dispatcher) {
        val cache = HermesCacheStore(temp.newFolder(), Json)
        cache.cacheHermesSessions(listOf(HermesSessionRow("old")), "old", true, server)
        prefs.selected = null; prefs.lastListed = "old"
        val gate = CompletableDeferred<Unit>()
        wire.onConnect = { gate.await() }
        wire.current = "new"; wire.profiles = listOf("new")
        setRows(HermesSessionRow("fresh"))
        val vm = model(cache, profile = null)
        val open = launch { vm.open() }; runCurrent()
        assertEquals("old", vm.uiState.value.sessions.single().profile)
        assertNull(vm.uiState.value.profile)
        assertNull(prefs.selected)
        gate.complete(Unit); open.join()
        assertEquals("new", vm.uiState.value.profile)
        assertEquals("new", prefs.lastListed)
    }
    @Test fun `non-connectivity refusal drops provisional cache rather than masking wrong identity`() = runTest(dispatcher) {
        val cache = HermesCacheStore(temp.newFolder(), Json)
        cache.cacheHermesSessions(listOf(HermesSessionRow("cached")), "default", true, server)
        wire.onConnect = { throw BotFailure.DifferentHost }
        val vm = model(cache); vm.open()
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertFalse(vm.uiState.value.isViewingCachedData)
        assertNotNull(vm.uiState.value.errorMessage)
    }
    @Test fun `pagination and optimistic archive rollback are visible through VM state`() = runTest(dispatcher) {
        wire.onPage = { _, offset, _, scheduled ->
            if (scheduled) HermesSessionPage(emptyList())
            else if (offset == 0) HermesSessionPage(listOf(HermesSessionRow("a"), HermesSessionRow("b")), count = 100)
            else HermesSessionPage(listOf(HermesSessionRow("c")))
        }
        val vm = model(); vm.open(); runCurrent()
        assertTrue(vm.uiState.value.hasMore)
        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.sessions.map { it.row.id })
        assertFalse(vm.uiState.value.hasMore)
        val gate = CompletableDeferred<Unit>()
        wire.onUpdate = { _, _, _ -> gate.await(); throw HermesSessionRefusal("refused") }
        val row = vm.uiState.value.sessions[1]
        val mutation = async { vm.archive(row) }; runCurrent()
        assertEquals(listOf("a", "c"), vm.uiState.value.sessions.map { it.row.id })
        gate.complete(Unit)
        assertFalse(mutation.await())
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.sessions.map { it.row.id })
        assertEquals("refused", vm.uiState.value.actionErrorMessage)
    }
    @Test fun `deletion outcomes leave busy rows and surface the Swift refusal copy`() = runTest(dispatcher) {
        setRows(HermesSessionRow("a")); val vm = model(); vm.open()
        val row = vm.uiState.value.sessions.single()
        wire.deletion = HermesSessionDeletion.Outcome.BUSY_HERE
        assertFalse(vm.delete(row))
        assertEquals("This session is still replying here. Stop the reply first, then delete it.", vm.uiState.value.actionErrorMessage)
        assertEquals(1, vm.uiState.value.sessions.size)
        wire.deletion = HermesSessionDeletion.Outcome.OPEN_ELSEWHERE
        assertFalse(vm.delete(row))
        assertEquals("This session is open in another app, so nothing was deleted. Close it there, then try again.", vm.uiState.value.actionErrorMessage)
        wire.deletion = HermesSessionDeletion.Outcome.DELETED
        assertTrue(vm.delete(row))
        assertTrue(vm.uiState.value.sessions.isEmpty())
    }
    @Test fun `host unread writes are serialized and a later mark wins`() = runTest(dispatcher) {
        setRows(HermesSessionRow("a", unread = true)); val vm = model(); vm.open(); runCurrent()
        val first = CompletableDeferred<Unit>()
        wire.onUpdate = { _, _, _ -> if (wire.writes.size == 1) first.await(); null }
        val row = vm.uiState.value.sessions.single()
        vm.beginViewing(row); runCurrent()
        assertFalse(vm.uiState.value.isUnread(row))
        vm.toggleUnread(row); runCurrent()
        assertEquals(1, wire.writes.size)
        assertTrue(vm.uiState.value.isUnread(row))
        first.complete(Unit); runCurrent()
        assertEquals(listOf(HermesSessionChange.Unread(false), HermesSessionChange.Unread(true)), wire.writes.map { it.third })
        assertTrue(vm.uiState.value.isUnread(row))
    }
    @Test fun `active list maps waiting and starting to row states without using unread timestamps`() = runTest(dispatcher) {
        setRows(HermesSessionRow("a"), HermesSessionRow("b"))
        wire.active = BotJSON.parse("""{"sessions":[{"session_key":"a","status":"waiting"},{"session_key":"b","status":"starting"}]}""")
        val vm = model(); vm.open(); runCurrent()
        assertEquals(mapOf("default|a" to SessionRowAttentionState.INPUT, "default|b" to SessionRowAttentionState.WORKING), vm.uiState.value.attentionStates)
        vm.pause()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, wire.calls.count { it == HermesCall.SessionActiveList })
    }
    @Test fun `all Profile pages keep equal stored ids and each mutation names its own Profile`() = runTest(dispatcher) {
        prefs.showsAllProfiles = true; wire.profiles = listOf("default", "other")
        setRows(HermesSessionRow("same", title = "Same key"))
        val vm = model(); vm.open(); runCurrent()
        assertEquals(setOf("default|same", "other|same"), vm.uiState.value.sessions.map { it.key }.toSet())
        val other = vm.uiState.value.sessions.first { it.profile == "other" }
        vm.setPinned(other, true)
        assertEquals("other", wire.writes.single().second)
        assertTrue(vm.uiState.value.sessions.first { it.profile == "other" }.row.pinned == true)
        assertNull(vm.uiState.value.sessions.first { it.profile == "default" }.row.pinned)
    }
}
