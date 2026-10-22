package io.github.jcartu.hermex.core.share

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.jcartu.hermex.data.ChatDraftKey
import io.github.jcartu.hermex.data.ChatDraftShareFile
import io.github.jcartu.hermex.data.ChatDraftStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The #1181 Hermes share transfer: the durable inbox clears only after the
 * new-chat draft holds the share, typed to the Hermes new-session key, with
 * every file prepared like a composer-staged upload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HermesShareImportTest {
    @get:Rule val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun repository(): SharedImportRepository =
        SharedImportRepository(ApplicationProvider.getApplicationContext(), json)

    private suspend fun SharedImportRepository.ingestTextAndFile(): SharedImport {
        val source = temp.newFile("notes.txt").apply { writeText("file body") }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "shared text")
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(source))
            type = "text/plain"
        }
        return ingest(intent)!!
    }

    @Test fun `transfer returns false when nothing is pending or the id mismatches`() = runBlocking {
        val repository = repository()
        val draftStore = mockk<ChatDraftStore>()
        assertFalse(HermesShareImport.transfer(repository, draftStore, "srv", "conn", "Inbox", "missing"))
        coVerify(exactly = 0) { draftStore.appendShare(any(), any(), any(), any(), any(), any()) }

        val import = repository.ingestTextAndFile()
        assertFalse(HermesShareImport.transfer(repository, draftStore, "srv", "conn", "Inbox", import.id + "-other"))
        coVerify(exactly = 0) { draftStore.appendShare(any(), any(), any(), any(), any(), any()) }
        // The mismatch left the inbox untouched.
        assertNotNull(repository.pending.value)
        repository.acknowledge(import.id)
    }

    @Test fun `transfer commits to the typed hermes new-chat draft with prepared files then acknowledges`() = runBlocking {
        val repository = repository()
        val import = repository.ingestTextAndFile()
        val draftStore = mockk<ChatDraftStore>()
        val keySlot = slot<ChatDraftKey>()
        val shareIdSlot = slot<String>()
        val textSlot = slot<String>()
        val filesSlot = slot<List<ChatDraftShareFile>>()
        coEvery {
            draftStore.appendShare(
                capture(shareIdSlot), capture(textSlot), capture(filesSlot), capture(keySlot), any(), any(),
            )
        } returns Unit

        val transferred = HermesShareImport.transfer(
            repository, draftStore,
            serverId = "http://100.64.1.5:9119", connectionID = "conn-1", profile = "Inbox",
            shareId = import.id,
        )

        assertTrue(transferred)
        assertEquals(import.id, shareIdSlot.captured)
        assertEquals("shared text", textSlot.captured)
        assertEquals(
            ChatDraftKey.hermesSession("http://100.64.1.5:9119", "conn-1", "Inbox", null),
            keySlot.captured,
        )
        assertEquals(1, filesSlot.captured.size)
        assertEquals("notes.txt", filesSlot.captured[0].name)
        assertFalse(filesSlot.captured[0].isImage)
        assertEquals("file body", filesSlot.captured[0].data.toString(Charsets.UTF_8))
        // The durable inbox cleared only after the draft held the share.
        assertNull(repository.pending.value)
    }

    @Test fun `transfer keeps the inbox queued when the draft write fails`() = runBlocking {
        val repository = repository()
        val import = repository.ingestTextAndFile()
        val draftStore = mockk<ChatDraftStore>()
        coEvery { draftStore.appendShare(any(), any(), any(), any(), any(), any()) } throws IOException("disk full")

        try {
            HermesShareImport.transfer(repository, draftStore, "srv", "conn", "Inbox", import.id)
            fail("the store failure must propagate so the home can offer Discard / Keep Queued")
        } catch (expected: IOException) {
        }
        assertNotNull(repository.pending.value)
        repository.acknowledge(import.id)
    }

    @Test fun `transfer refuses a second concurrent claim of the same share`() = runBlocking {
        val repository = repository()
        val import = repository.ingestTextAndFile()
        val draftStore = mockk<ChatDraftStore>()
        val gate = CompletableDeferred<Unit>()
        coEvery { draftStore.appendShare(any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }

        val first = async { HermesShareImport.transfer(repository, draftStore, "srv", "conn", "Inbox", import.id) }
        // Let the first transfer reach the store write before claiming again.
        withContext(Dispatchers.Default) { kotlinx.coroutines.yield() }
        assertFalse(HermesShareImport.transfer(repository, draftStore, "srv", "conn", "Inbox", import.id))
        gate.complete(Unit)
        assertTrue(first.await())
        assertNull(repository.pending.value)
    }
}
