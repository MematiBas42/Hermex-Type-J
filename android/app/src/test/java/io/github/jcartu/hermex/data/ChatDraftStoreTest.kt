package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.ui.feature.chat.ComposerQuote
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Acceptance ports for Persistence/ChatDraftStore.swift: isolation, tolerant restore and budget ordering. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftStoreTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `draft round trip keeps server identity text quotes files and settings`() = runTest {
        val directory = Files.createTempDirectory("draft-roundtrip").toFile()
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val persistence = ChatDraftFilePersistence(directory, json, dispatcher)
            val key = ChatDraftKey("https://one.example/prefix", "session")
            val other = ChatDraftKey("https://two.example", "session")
            val draft = ChatDraft(
                text = "draft", quotes = listOf(ComposerQuote("quote", "selected passage")),
                attachments = listOf(ChatDraftAttachment("file-id", "report.pdf", "application/pdf", 20, false, "saved.pdf")),
                settings = ChatDraftSettings("model", "provider", "high", "work", "/workspace"), lastUsedAt = 42,
            )
            persistence.write(mapOf(key to draft, other to ChatDraft(text = "other server")))
            val loaded = ChatDraftFilePersistence(directory, json, dispatcher).load()
            assertEquals(draft, loaded[key])
            assertEquals("other server", loaded[other]?.text)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `legacy and malformed fields do not discard valid draft text`() = runTest {
        val directory = Files.createTempDirectory("draft-legacy").toFile()
        try {
            val folder = File(directory, "ChatDrafts").apply { mkdirs() }
            File(folder, "drafts.json").writeText("""{"version":1,"drafts":[null,{"serverID":"server","context":"session","sessionID":"s","text":"kept","quotes":[{}, {"id":"q","text":"quote"}],"attachments":[{"id":"a","name":"file","mime":"text/plain","file":"../escape"}],"settings":false}]}""")
            val draft = ChatDraftFilePersistence(directory, json, UnconfinedTestDispatcher(testScheduler)).load()
                .getValue(ChatDraftKey("server", "s"))
            assertEquals("kept", draft.text)
            assertEquals(1, draft.quotes.size)
            assertNull(draft.attachments.single().file)
            assertNull(draft.settings)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `200 MB default evicts oldest unused reference before recent draft`() = runTest {
        val sizes = linkedMapOf("old" to 100L * 1024 * 1024, "recent" to 100L * 1024 * 1024)
        val deleted = mutableListOf<String>()
        val files = object : ChatDraftAttachmentStoring {
            override fun save(data: ByteArray, suggestedFilename: String): String {
                sizes["incoming"] = data.size.toLong()
                return "incoming"
            }
            override fun retainedFileBytes(): Map<String, Long> = sizes.toMap()
            override fun data(fileName: String): ByteArray = byteArrayOf(1)
            override fun file(fileName: String): File = File(fileName)
            override fun delete(fileName: String) { sizes.remove(fileName); deleted += fileName }
            override fun sweep(keepingReferenced: Set<String>, maxAgeMillis: Long, now: Long) = Unit
        }
        var saved: Map<ChatDraftKey, ChatDraft> = emptyMap()
        val persistence = object : ChatDraftPersisting {
            override suspend fun load(): Map<ChatDraftKey, ChatDraft> = saved
            override suspend fun write(drafts: Map<ChatDraftKey, ChatDraft>) { saved = drafts }
        }
        var clock = 1L
        val store = ChatDraftStore(persistence, files, backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler), now = { clock })
        val old = ChatDraftKey("server", "old")
        val recent = ChatDraftKey("server", "recent")
        store.setText("old draft", old)
        store.setAttachments(listOf(ChatDraftAttachment("a", "old", "text/plain", 1, false, "old")), old)
        clock = 2
        store.setText("recent draft", recent)
        store.setAttachments(listOf(ChatDraftAttachment("b", "recent", "text/plain", 1, false, "recent")), recent)
        val lease = store.makeAttachmentLease(ChatDraftKey("server", "active"))
        val copy = store.stageAttachment(byteArrayOf(1), "incoming", lease)
        assertEquals("incoming", copy)
        assertEquals(listOf("old"), deleted)
        assertEquals("old draft", store.draft(old)?.text)
        assertTrue(store.draft(old)!!.attachments.isEmpty())
        assertEquals("recent", store.draft(recent)!!.attachments.single().file)
        assertTrue(sizes.values.sum() <= 200L * 1024 * 1024)
    }
}
