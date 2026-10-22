package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.model.hermes.HermesSessionRefusal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveImportBatcherTest {
    @Test
    fun `batches at host limit and resends only sessions not named by refusal`() = runTest {
        val prepared = (0..500).map { index ->
            val id = "session-$index"
            MoveImportCodec.Prepared(
                id = id,
                encoded = "{\"id\":\"$id\",\"messages\":[]}".toByteArray(),
                messageCount = 0,
            )
        }
        val dispatched = mutableListOf<List<String>>()
        val client = RefusingImportClient()

        val outcomes = MoveImportBatcher.run(prepared, "default", client) { dispatched += it }

        assertEquals(listOf(500, 499, 1), dispatched.map { it.size })
        assertEquals(MoveCopyState.OVERSIZED, outcomes.getValue("session-0").state)
        assertEquals(MoveCopyState.IMPORTED, outcomes.getValue("session-1").state)
        assertEquals(MoveCopyState.IMPORTED, outcomes.getValue("session-500").state)
        assertTrue("session-0" !in dispatched[1])
    }

    private class RefusingImportClient : MoveHermesClient {
        var calls = 0

        override suspend fun importSessions(body: ByteArray): BotJSON {
            val ids = BotJSON.parse(body)["sessions"].list.orEmpty().mapNotNull { it["id"].text }
            if (calls++ == 0) {
                throw HermesSessionRefusal(
                    message = "one session was refused",
                    entries = listOf(
                        HermesSessionRefusal.Entry(
                            index = 0,
                            sessionID = ids.first(),
                            reason = "messages exceeds the per-session import limit",
                        ),
                    ),
                )
            }
            return BotJSON.objectOf(
                "imported_ids" to BotJSON.array(ids.map(BotJSON::string)),
                "skipped_ids" to BotJSON.array(emptyList()),
            )
        }

        override suspend fun connect() = Unit
        override suspend fun profileCatalog() = HermesMoveProfileCatalog(listOf("default"), "default")
        override suspend fun sessionScanPage(profile: String, offset: Int) = HermesSessionPage(emptyList())
        override suspend fun sessionRow(sessionId: String, profile: String): BotJSON? = null
        override suspend fun updateTitle(sessionId: String, profile: String, title: String) = Unit
        override suspend fun updatePinned(sessionId: String, profile: String, pinned: Boolean) = Unit
        override suspend fun updateFolder(sessionId: String, profile: String, folder: String) = Unit
        override suspend fun close() = Unit
    }
}
