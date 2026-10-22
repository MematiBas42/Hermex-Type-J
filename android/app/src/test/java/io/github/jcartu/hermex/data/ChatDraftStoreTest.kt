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

    @Test fun `typed Hermes contexts survive reload and connection purge preserves webui and other hosts`() = runTest {
        val directory = Files.createTempDirectory("typed-drafts").toFile()
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val persistence = ChatDraftFilePersistence(directory, json, dispatcher)
            val files = ChatDraftAttachmentStore(directory)
            val ownedFile = files.save("owned".toByteArray(), "owned.txt")
            val webui = ChatDraftKey.session("server", "same")
            val hermes = ChatDraftKey.hermesSession("server", "connection", "", "same")
            val new = ChatDraftKey.hermesSession("server", "connection", "", null)
            val bot = ChatDraftKey.bot("server", "connection", "")
            val other = ChatDraftKey.hermesSession("server", "other", "", "same")
            val otherProfile = ChatDraftKey.bot("server", "connection", "different-bot")
            persistence.write(mapOf(webui to ChatDraft(text = "webui"), hermes to ChatDraft(text = "hermes", attachments = listOf(
                ChatDraftAttachment("f", "owned.txt", "text/plain", 5, false, ownedFile))),
                new to ChatDraft(text = "new"), bot to ChatDraft(botSubmissionUncertain = true), other to ChatDraft(text = "other"),
                otherProfile to ChatDraft(text = "keep other bot")))
            val store = ChatDraftStore(persistence, files, backgroundScope, dispatcher)
            assertTrue(store.draft(bot)!!.botSubmissionUncertain)
            assertEquals("new", store.draft(new)?.text)
            assertFalse(bot.isNewChat)
            assertTrue(new.isNewChat)
            store.discardHermesDrafts("server", "connection", profile = "")
            store.flush()
            assertEquals(setOf(webui, other, otherProfile), persistence.load().keys)
            assertFalse(files.file(ownedFile).exists())
            store.discardHermesDrafts("server", "connection")
            store.flush()
            val remaining = persistence.load()
            assertEquals(setOf(webui, other), remaining.keys)
            assertFalse(files.file(ownedFile).exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `share commit write failure rolls back text records occurrence and staged copies`() = runTest {
        val directory = Files.createTempDirectory("share-rollback").toFile()
        try {
            val key = ChatDraftKey("server", "s")
            var disk = mapOf(key to ChatDraft(text = "Original"))
            var refused = false
            val persistence = object : ChatDraftPersisting {
                override suspend fun load() = disk
                override suspend fun write(drafts: Map<ChatDraftKey, ChatDraft>) {
                    if (!refused && drafts[key]?.sharedImportIds?.isNotEmpty() == true) {
                        refused = true
                        throw java.io.IOException("disk full")
                    }
                    disk = drafts
                }
            }
            val files = ChatDraftAttachmentStore(directory)
            val store = ChatDraftStore(persistence, files, backgroundScope, UnconfinedTestDispatcher(testScheduler))
            try {
                store.appendShare("share", "Incoming", listOf(ChatDraftShareFile(byteArrayOf(1), "a.txt", "text/plain", false)), key, 100)
                fail("write should fail")
            } catch (_: java.io.IOException) { }
            assertEquals("Original", store.draft(key)?.text)
            assertTrue(store.draft(key)!!.attachments.isEmpty())
            assertTrue(store.draft(key)!!.sharedImportIds.isEmpty())
            assertEquals("Original", disk[key]?.text)
            assertTrue(files.retainedFileBytes().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
