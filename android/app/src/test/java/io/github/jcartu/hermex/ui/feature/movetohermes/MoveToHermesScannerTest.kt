package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.model.hermes.HermesSessionRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveToHermesScannerTest {
    @Test
    fun `read-only scan locations feed Review plan without changing either server`() = runTest {
        val webui = object : MoveWebuiClient {
            override suspend fun scanListing() = MoveWebuiListing(
                sessions = listOf(
                    MoveSourceChat("already", "default"),
                    MoveSourceChat("missing", "work"),
                    MoveSourceChat("duplicate", "work"),
                ),
                allProfiles = true,
            )
            override suspend fun profiles() = listOf("default", "work")
            override suspend fun switchProfile(profile: String) = error("scan must not switch a webui Profile")
            override suspend fun exportSession(sessionId: String) = error("scan must not export")
        }
        val hermes = object : MoveHermesClient {
            override suspend fun connect() = Unit
            override suspend fun profileCatalog() = HermesMoveProfileCatalog(listOf("default", "work"), "default")
            override suspend fun sessionScanPage(profile: String, offset: Int): HermesSessionPage {
                val ids = when (profile) {
                    "default" -> listOf("already", "duplicate")
                    else -> listOf("duplicate")
                }
                return HermesSessionPage(ids.map { HermesSessionRow(it, profile = profile) }, total = ids.size)
            }
            override suspend fun sessionRow(sessionId: String, profile: String): BotJSON? = null
            override suspend fun importSessions(body: ByteArray) = error("scan must not import")
            override suspend fun updateTitle(sessionId: String, profile: String, title: String) = error("scan must not write")
            override suspend fun updatePinned(sessionId: String, profile: String, pinned: Boolean) = error("scan must not write")
            override suspend fun updateFolder(sessionId: String, profile: String, folder: String) = error("scan must not write")
            override suspend fun close() = Unit
        }
        val catalog = hermes.profileCatalog()

        val scan = MoveToHermesScanner.scan(webui, hermes, catalog)
        val mappings = MoveToHermesPlanner.initialMappings(
            MoveToHermesPlanner.profileChoices(scan, webui.profiles(), catalog),
            catalog,
        )
        val plan = MoveToHermesPlanner.progressItems(scan, mappings).associateBy { it.chat.id }

        assertEquals(MoveCopyState.ALREADY_PRESENT, plan.getValue("already").copyState)
        assertEquals("default", plan.getValue("already").locatedProfile)
        assertEquals(MoveCopyState.PENDING, plan.getValue("missing").copyState)
        assertEquals("work", plan.getValue("missing").destinationProfile)
        assertEquals(MoveCopyState.AMBIGUOUS, plan.getValue("duplicate").copyState)
    }
}
