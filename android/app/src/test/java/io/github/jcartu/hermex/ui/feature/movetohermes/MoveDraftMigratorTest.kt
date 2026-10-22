package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.data.ChatDraftAttachmentStore
import io.github.jcartu.hermex.data.ChatDraftFilePersistence
import io.github.jcartu.hermex.data.ChatDraftKey
import io.github.jcartu.hermex.data.ChatDraftStore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MoveDraftMigratorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `moves the whole draft key and a replay reports already moved`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val directory = temporary.newFolder("drafts")
        val store = ChatDraftStore(
            ChatDraftFilePersistence(directory, Json { ignoreUnknownKeys = true }, dispatcher),
            ChatDraftAttachmentStore(directory),
            backgroundScope,
            ioDispatcher = dispatcher,
        )
        val source = ChatDraftKey.session("https://webui.example", "session-a")
        val destination = ChatDraftKey.session("https://hermes.example", "session-a")
        store.draft(source)
        store.setText("unsent draft", source)
        store.flush()

        assertEquals(MoveDraftState.MOVED, MoveDraftMigrator.move(store, source, destination))
        assertNull(store.draft(source))
        assertEquals("unsent draft", store.draft(destination)?.text)
        assertEquals(MoveDraftState.ALREADY_MOVED, MoveDraftMigrator.move(store, source, destination))
    }

    @Test
    fun `destination conflict keeps both drafts`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val directory = temporary.newFolder("conflict")
        val store = ChatDraftStore(
            ChatDraftFilePersistence(directory, Json { ignoreUnknownKeys = true }, dispatcher),
            ChatDraftAttachmentStore(directory),
            backgroundScope,
            ioDispatcher = dispatcher,
        )
        val source = ChatDraftKey.session("source", "session-a")
        val destination = ChatDraftKey.session("destination", "session-a")
        store.draft(source)
        store.setText("source text", source)
        store.setText("destination text", destination)
        store.flush()

        assertEquals(MoveDraftState.CONFLICT, MoveDraftMigrator.move(store, source, destination))
        assertEquals("source text", store.draft(source)?.text)
        assertEquals("destination text", store.draft(destination)?.text)
    }
}
