package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.data.ChatDraftAttachmentStore
import io.github.jcartu.hermex.data.ChatDraftFilePersistence
import io.github.jcartu.hermex.data.ChatDraftStore
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.model.hermes.HermesSessionRow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MoveToHermesResumeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `persisted resume settles dispatched import and skips all done work on replay`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        val stateFile = temporary.newFile("move.json").also { it.delete() }
        val store = MoveToHermesStore(stateFile, json, dispatcher)
        store.beginMove(
            sourceServerId = "source",
            destinationServerId = "destination",
            destinationCreatedAt = 1.0,
            profileMappings = listOf(MoveProfileMapping("default", "default")),
            hermesProfiles = listOf("default"),
            items = listOf(
                MoveItemProgress(
                    chat = MoveSourceChat("done"),
                    destinationProfile = "default",
                    locatedProfile = "default",
                    copyState = MoveCopyState.IMPORTED,
                ),
                MoveItemProgress(
                    chat = MoveSourceChat("in-flight"),
                    destinationProfile = "default",
                    locatedProfile = "default",
                    copyState = MoveCopyState.DISPATCHING,
                ),
            ),
        )
        val drafts = ChatDraftStore(
            ChatDraftFilePersistence(temporary.newFolder("drafts"), json, dispatcher),
            ChatDraftAttachmentStore(temporary.root),
            backgroundScope,
            ioDispatcher = dispatcher,
        )
        val webui = NeverExportWebui()
        val hermes = ExistingSessionsHermes(setOf("done", "in-flight"))

        val first = MoveToHermesRunner("source", "destination", store, webui, hermes, drafts).run()

        assertEquals(MoveStage.FINISHED, first.record.stage)
        assertEquals(
            MoveCopyState.PRESENT_UNCONFIRMED,
            first.record.items.first { it.chat.id == "in-flight" }.copyState,
        )
        assertEquals(0, hermes.importCalls)
        assertEquals(0, webui.exportCalls)

        val reloaded = MoveToHermesStore(stateFile, json, dispatcher)
        val secondHermes = ExistingSessionsHermes(setOf("done", "in-flight"))
        val second = MoveToHermesRunner("source", "destination", reloaded, webui, secondHermes, drafts).run()

        assertEquals(MoveStage.FINISHED, second.record.stage)
        assertEquals(0, secondHermes.importCalls)
        assertEquals(0, webui.exportCalls)
    }

    private class NeverExportWebui : MoveWebuiClient {
        var exportCalls = 0
        override suspend fun scanListing() = MoveWebuiListing(emptyList())
        override suspend fun profiles() = listOf("default")
        override suspend fun switchProfile(profile: String) = true
        override suspend fun exportSession(sessionId: String): ByteArray {
            exportCalls += 1
            error("A completed/dispatched-present item must not export again")
        }
    }

    private class ExistingSessionsHermes(private val ids: Set<String>) : MoveHermesClient {
        var importCalls = 0
        override suspend fun connect() = Unit
        override suspend fun profileCatalog() = HermesMoveProfileCatalog(listOf("default"), "default")
        override suspend fun sessionScanPage(profile: String, offset: Int) = HermesSessionPage(
            ids.map { HermesSessionRow(id = it, profile = profile) },
            total = ids.size,
        )
        override suspend fun sessionRow(sessionId: String, profile: String): BotJSON? =
            if (sessionId in ids) {
                BotJSON.objectOf(
                    "id" to BotJSON.string(sessionId),
                    "hidden" to BotJSON.bool(false),
                    "pinned" to BotJSON.bool(false),
                )
            } else null
        override suspend fun importSessions(body: ByteArray): BotJSON {
            importCalls += 1
            error("Import must be skipped after exact-id settlement")
        }
        override suspend fun updateTitle(sessionId: String, profile: String, title: String) = Unit
        override suspend fun updatePinned(sessionId: String, profile: String, pinned: Boolean) = Unit
        override suspend fun updateFolder(sessionId: String, profile: String, folder: String) = Unit
        override suspend fun close() = Unit
    }
}
