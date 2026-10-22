package io.github.jcartu.hermex.ui.feature.movetohermes

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MoveToHermesStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `ask once flag is durable and scoped per webui server`() = runTest {
        val file = temporary.newFile("move-state.json").also { it.delete() }
        val first = MoveToHermesStore(file, json, UnconfinedTestDispatcher(testScheduler))

        assertTrue(first.claimQuestion("https://one.example"))
        assertFalse(first.claimQuestion("https://one.example"))

        val reloaded = MoveToHermesStore(file, json, UnconfinedTestDispatcher(testScheduler))
        assertFalse(reloaded.claimQuestion("https://one.example"))
        assertTrue(reloaded.claimQuestion("https://two.example"))
        assertEquals(MovePromptState.ASKED, reloaded.promptState("https://one.example"))
        assertEquals(MovePromptState.ASKED, reloaded.promptState("https://two.example"))
    }

    @Test
    fun `moved session and Profile lookups survive source removal but reject replaced destination`() = runTest {
        val file = temporary.newFile("routes.json").also { it.delete() }
        val store = MoveToHermesStore(file, json, UnconfinedTestDispatcher(testScheduler))
        store.beginMove(
            sourceServerId = "https://webui.example",
            destinationServerId = "https://hermes.example",
            destinationCreatedAt = 5.0,
            profileMappings = listOf(
                MoveProfileMapping("work", "agent"),
                MoveProfileMapping("legacy", keepOnWebui = true),
            ),
            hermesProfiles = listOf("agent"),
            items = listOf(
                MoveItemProgress(
                    chat = MoveSourceChat("session-a", profile = "work"),
                    destinationProfile = "agent",
                    locatedProfile = "agent",
                    copyState = MoveCopyState.ALREADY_PRESENT,
                ),
            ),
        )
        var configured = mapOf("https://hermes.example" to 5.0)
        val map = MovedSessionMap(store) { configured }

        assertEquals("https://hermes.example", map.destinationOf("https://webui.example"))
        assertEquals(
            MovedSessionTarget("https://hermes.example", "session-a", "agent"),
            map.lookup("https://webui.example", "session-a"),
        )
        assertEquals(MovedSessionMap.Mapping.Hermes("agent"), map.profileMapping("https://webui.example", "work"))
        assertEquals(MovedSessionMap.Mapping.KeepOnWebui, map.profileMapping("https://webui.example", "legacy"))
        assertEquals(
            listOf(MovedSessionMap.Move("https://hermes.example", "https://webui.example")),
            map.moves,
        )

        // Removing the source changes no route record; only the destination's generation matters.
        assertEquals("https://hermes.example", map.destinationOf("https://webui.example"))
        configured = emptyMap()
        assertNull(map.lookup("https://webui.example", "session-a"))
        configured = mapOf("https://hermes.example" to 6.0)
        assertNull(map.destinationOf("https://webui.example"))
    }
}
