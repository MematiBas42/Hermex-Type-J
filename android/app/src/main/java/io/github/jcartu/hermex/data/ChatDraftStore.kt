package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.ui.feature.chat.ComposerQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Port of `ChatDraftAttachment` (`Persistence/ChatDraftStore.swift`): one
 * staged composer attachment as referenced by a persisted draft. [file] names
 * the durable app-owned copy in [ChatDraftAttachmentStore]; it stays optional
 * so older or partially corrupt records decode safely.
 */
data class ChatDraftAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Int?,
    val isImage: Boolean,
    val file: String?,
)

/**
 * Port of `ChatDraftSettings`: effective composer choices snapshotted for a
 * draft context. Restored for new-chat contexts after revalidation against
 * the live server configuration; existing sessions keep loading theirs from
 * the server.
 */
data class ChatDraftSettings(
    val modelId: String? = null,
    val modelProviderId: String? = null,
    val reasoningEffort: String? = null,
    val profileName: String? = null,
    val workspacePath: String? = null,
) {
    val isEmpty: Boolean
        get() = normalized().let {
            it.modelId == null && it.modelProviderId == null && it.reasoningEffort == null &&
                it.profileName == null && it.workspacePath == null
        }

    /** Trims every field and collapses blanks to null. */
    fun normalized(): ChatDraftSettings = ChatDraftSettings(
        modelId = value(modelId),
        modelProviderId = value(modelProviderId),
        reasoningEffort = value(reasoningEffort),
        profileName = value(profileName),
        workspacePath = value(workspacePath),
    )

    private fun value(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Port of `ChatDraft`: everything a composer context restores after
 * navigation, process death, or an abandoned new chat — typed text, quoted
 * passages, staged attachments, effective settings, and the shares already
 * merged in (so a retried share is never added twice, #1181).
 */
data class ChatDraft(
    val text: String = "",
    val quotes: List<ComposerQuote> = emptyList(),
    val attachments: List<ChatDraftAttachment> = emptyList(),
    val settings: ChatDraftSettings? = null,
    /** Epoch millis of the last change or use; drives attachment-budget recency. */
    val lastUsedAt: Long? = null,
    val sharedImportIds: Set<String> = emptySet(),
    /** Swift #508: an unacknowledged Hermes send must remain uncertain across relaunch. */
    val botSubmissionUncertain: Boolean = false,
) {
    val isEmpty: Boolean
        get() = text.isEmpty() && quotes.isEmpty() && attachments.isEmpty() && (settings?.isEmpty ?: true) && !botSubmissionUncertain
}

/** Port of `ChatDraftKey.Context`: webui, Hermes stored/new sessions, and bot roots never share a draft. */
data class ChatDraftKey(
    val serverId: String,
    val sessionId: String?,
    val hermesConnectionID: String? = null,
    val hermesProfile: String? = null,
    val isBot: Boolean = false,
) {
    val isNewChat: Boolean get() = sessionId == null && !isBot

    companion object {
        fun session(serverId: String, sessionId: String): ChatDraftKey = ChatDraftKey(serverId, sessionId)
        fun newChat(serverId: String): ChatDraftKey = ChatDraftKey(serverId, null)
        fun hermesSession(serverId: String, connectionID: String, profile: String, key: String?): ChatDraftKey =
            ChatDraftKey(serverId, key, connectionID, profile)
        fun bot(serverId: String, connectionID: String, profile: String): ChatDraftKey =
            ChatDraftKey(serverId, null, connectionID, profile, isBot = true)
    }
}

/** A message parked from the run queue into a draft (`QueuedSlashMessage` shape). */
data class QueuedDraftMessage(
    val text: String,
    val attachments: List<ChatDraftAttachment>,
)

/**
 * Port of `ChatDraftSendReconciliation`: a send carries exactly the
 * attachments staged in the composer when it is submitted. Any other record
 * in the draft — one awaiting a re-upload retry, or one a restore pass has not
 * reached — was never carried, so its durable copy must survive.
 */
object ChatDraftSendReconciliation {
    data class Outcome(
        /** Records the send carried; their durable copies are unreferenced and can go. */
        val consumed: List<ChatDraftAttachment> = emptyList(),
        /** Records the send did not carry; they stay in the draft with their copies. */
        val retained: List<ChatDraftAttachment> = emptyList(),
    )

    fun outcome(draftRecords: List<ChatDraftAttachment>, stagedAttachmentIds: Set<String>): Outcome {
        val consumed = mutableListOf<ChatDraftAttachment>()
        val retained = mutableListOf<ChatDraftAttachment>()
        for (record in draftRecords) {
            if (record.id in stagedAttachmentIds) consumed += record else retained += record
        }
        return Outcome(consumed, retained)
    }
}

/**
 * Port of `ChatDraftQueueParking`: folds messages queued behind a run into
 * their chat's draft when the user leaves mid-run (#857). Queued texts first,
 * in queue order, then the draft's own text, joined by blank lines; queued
 * attachments after the draft's own, without duplicate ids.
 */
object ChatDraftQueueParking {
    fun merged(
        draft: ChatDraft,
        queuedTexts: List<String>,
        queuedAttachments: List<ChatDraftAttachment>,
    ): ChatDraft {
        val attachmentIds = draft.attachments.mapTo(HashSet()) { it.id }
        return draft.copy(
            text = (queuedTexts + draft.text).filter { it.isNotEmpty() }.joinToString("\n\n"),
            attachments = draft.attachments + queuedAttachments.filter { attachmentIds.add(it.id) },
        )
    }
}

/** Port of `ChatDraftStorageError`. */
sealed class ChatDraftStorageException(message: String) : Exception(message) {
    object Unavailable : ChatDraftStorageException("Attachment storage is busy. Try again shortly.") {
        private fun readResolve(): Any = Unavailable
    }

    object AttachmentLimit : ChatDraftStorageException("A draft can have up to 10 attachments.") {
        private fun readResolve(): Any = AttachmentLimit
    }
}

/**
 * Port of `ChatDraftAttachmentLease`: held across restore, upload, queueing
 * and send; a live owner protects its files from budget reclamation even when
 * they temporarily leave the pending strip.
 */
class ChatDraftAttachmentLease(var key: ChatDraftKey?) {
    val files: MutableSet<String> = mutableSetOf()
    val slotIds: MutableSet<String> = mutableSetOf()
    val filesByAttachmentId: MutableMap<String, String> = mutableMapOf()
}

interface ChatDraftPersisting {
    suspend fun load(): Map<ChatDraftKey, ChatDraft>
    suspend fun write(drafts: Map<ChatDraftKey, ChatDraft>)
}

/**
 * Port of `ChatDraftFilePersistence`: one `ChatDrafts/drafts.json` document,
 * written atomically. Decoding is tolerant at every level — one malformed
 * record, attachment or quote never discards the rest, and any field of an
 * unexpected shape reads as absent.
 */
class ChatDraftFilePersistence(
    baseDirectory: File,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatDraftPersisting {
    private val directory = File(baseDirectory, "ChatDrafts")
    private val file = File(directory, "drafts.json")

    override suspend fun load(): Map<ChatDraftKey, ChatDraft> = withContext(ioDispatcher) {
        if (!file.exists()) return@withContext emptyMap()
        val document = runCatching { json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)) as? JsonObject }
            .getOrNull() ?: return@withContext emptyMap()
        val version = document["version"]?.let(::intValue)
        if (version == null || version !in READABLE_VERSIONS) return@withContext emptyMap()
        val records = document["drafts"] as? JsonArray ?: return@withContext emptyMap()
        val result = LinkedHashMap<ChatDraftKey, ChatDraft>()
        for (record in records) {
            val decoded = decodeRecord(record as? JsonObject ?: continue) ?: continue
            result[decoded.first] = decoded.second
        }
        result
    }

    override suspend fun write(drafts: Map<ChatDraftKey, ChatDraft>): Unit = withContext(ioDispatcher) {
        val records = drafts.filterValues { !it.isEmpty }.entries
            .sortedWith(compareBy({ it.key.serverId }, { it.key.isNewChat }, { it.key.sessionId ?: "" }))
            .map { (key, draft) -> encodeRecord(key, draft) }
        val document = buildJsonObject {
            put("version", JsonPrimitive(CURRENT_VERSION))
            put("drafts", JsonArray(records))
        }
        directory.mkdirs()
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(json.encodeToString(JsonElement.serializer(), document), StandardCharsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    private fun encodeRecord(key: ChatDraftKey, draft: ChatDraft): JsonObject = buildJsonObject {
        put("serverID", JsonPrimitive(key.serverId))
        put("context", JsonPrimitive(when {
            key.isBot -> "bot"
            key.hermesConnectionID != null -> "hermesSession"
            key.isNewChat -> "newChat"
            else -> "session"
        }))
        key.hermesConnectionID?.let { put("hermesConnectionID", JsonPrimitive(it)) }
        key.hermesProfile?.let { put("hermesProfile", JsonPrimitive(it)) }
        if (key.isBot) put("isBot", JsonPrimitive(true))
        if (draft.botSubmissionUncertain) put("botSubmissionUncertain", JsonPrimitive(true))
        key.sessionId?.let { put("sessionID", JsonPrimitive(it)) }
        put("text", JsonPrimitive(draft.text))
        put(
            "quotes",
            buildJsonArray {
                draft.quotes.forEach { quote ->
                    add(buildJsonObject { put("id", JsonPrimitive(quote.id)); put("text", JsonPrimitive(quote.text)) })
                }
            },
        )
        put(
            "attachments",
            buildJsonArray {
                draft.attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(attachment.id))
                            put("name", JsonPrimitive(attachment.name))
                            put("mime", JsonPrimitive(attachment.mime))
                            attachment.size?.let { put("size", JsonPrimitive(it)) }
                            put("isImage", JsonPrimitive(attachment.isImage))
                            attachment.file?.let { put("file", JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
        draft.settings?.let { settings ->
            put(
                "settings",
                buildJsonObject {
                    settings.modelId?.let { put("modelID", JsonPrimitive(it)) }
                    settings.modelProviderId?.let { put("modelProviderID", JsonPrimitive(it)) }
                    settings.reasoningEffort?.let { put("reasoningEffort", JsonPrimitive(it)) }
                    settings.profileName?.let { put("profileName", JsonPrimitive(it)) }
                    settings.workspacePath?.let { put("workspacePath", JsonPrimitive(it)) }
                },
            )
        }
        draft.lastUsedAt?.let { put("lastUsedAt", JsonPrimitive(it)) }
        if (draft.sharedImportIds.isNotEmpty()) {
            put("sharedImportIDs", JsonArray(draft.sharedImportIds.sorted().map(::JsonPrimitive)))
        }
    }

    private fun decodeRecord(record: JsonObject): Pair<ChatDraftKey, ChatDraft>? {
        val serverId = stringValue(record["serverID"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = when (stringValue(record["context"])) {
            "session" -> {
                val sessionId = stringValue(record["sessionID"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                ChatDraftKey.session(serverId, sessionId)
            }
            "newChat" -> ChatDraftKey.newChat(serverId)
            "hermesSession", "bot" -> {
                val connection = stringValue(record["hermesConnectionID"])?.takeIf { it.isNotBlank() } ?: return null
                val profile = stringValue(record["hermesProfile"]) ?: return null
                if (stringValue(record["context"]) == "bot") ChatDraftKey.bot(serverId, connection, profile)
                else ChatDraftKey.hermesSession(serverId, connection, profile, stringValue(record["sessionID"]))
            }
            else -> return null
        }

        val quotes = (record["quotes"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = stringValue(obj["id"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = stringValue(obj["text"])?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ComposerQuote(id = id, text = text)
        }
        val attachments = (record["attachments"] as? JsonArray).orEmpty().mapNotNull { element ->
            decodeAttachment(element as? JsonObject ?: return@mapNotNull null)
        }
        val settings = (record["settings"] as? JsonObject)?.let { obj ->
            ChatDraftSettings(
                modelId = stringValue(obj["modelID"]),
                modelProviderId = stringValue(obj["modelProviderID"]),
                reasoningEffort = stringValue(obj["reasoningEffort"]),
                profileName = stringValue(obj["profileName"]),
                workspacePath = stringValue(obj["workspacePath"]),
            ).normalized().takeIf { !it.isEmpty }
        }
        val draft = ChatDraft(
            text = stringValue(record["text"]) ?: "",
            quotes = quotes,
            attachments = attachments,
            settings = settings,
            lastUsedAt = record["lastUsedAt"]?.let(::longValue),
            sharedImportIds = (record["sharedImportIDs"] as? JsonArray).orEmpty().mapNotNull(::stringValue).toSet(),
            // Missing means false; malformed means uncertain, as Swift's decoder fails closed for Hermes drafts.
            botSubmissionUncertain = key.hermesConnectionID != null && record.containsKey("botSubmissionUncertain") &&
                ((record["botSubmissionUncertain"] as? JsonPrimitive)?.booleanOrNull ?: true),
        )
        if (draft.isEmpty) return null
        return key to draft
    }

    private fun decodeAttachment(obj: JsonObject): ChatDraftAttachment? {
        val id = stringValue(obj["id"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = stringValue(obj["name"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mime = stringValue(obj["mime"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Stored file names are plain names generated by ChatDraftAttachmentStore;
        // reject anything with path components so a corrupted document cannot
        // point outside the attachments directory. A rejected name degrades the
        // record to metadata-only instead of dropping it.
        val file = stringValue(obj["file"])?.trim()?.takeIf {
            it.isNotEmpty() && it != "." && it != ".." && it == ChatDraftAttachmentStore.normalizedFilename(it)
        }
        return ChatDraftAttachment(
            id = id,
            name = name,
            mime = mime,
            size = obj["size"]?.let(::intValue),
            isImage = (obj["isImage"] as? JsonPrimitive)?.booleanOrNull ?: false,
            file = file,
        )
    }

    private fun stringValue(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content
    }

    private fun intValue(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
    }

    private fun longValue(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    private companion object {
        const val CURRENT_VERSION = 4
        val READABLE_VERSIONS = setOf(1, 2, 3, CURRENT_VERSION)
    }
}

/** One file of an incoming share, for [ChatDraftStore.appendShare]. */
data class ChatDraftShareFile(
    val data: ByteArray,
    val name: String,
    val mime: String,
    val isImage: Boolean,
)

/**
 * Port of `ChatDraftStore` (`Persistence/ChatDraftStore.swift`): unsent
 * composer text, quotes, attachments and settings per session and per server
 * (1.6.0), with the retained-attachment budget (200 MB shared, oldest unused
 * reclaimed first, 1.9.0) and transactional share imports (#1181).
 *
 * In-memory state is guarded by [stateLock]; admission, record commits and
 * deletion serialize on [storageLock] so new copies are counted on disk before
 * the next admission begins. Non-suspending mutators schedule a debounced
 * persist; [flush] writes immediately.
 */
class ChatDraftStore(
    private val persistence: ChatDraftPersisting,
    private val attachmentStore: ChatDraftAttachmentStoring?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = 200,
    private val attachmentSweepMaxAgeMillis: Long = 24L * 60 * 60 * 1_000,
    private val retainedByteLimit: Long = 200L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stateLock = Any()
    private var drafts: MutableMap<ChatDraftKey, ChatDraft> = LinkedHashMap()
    private val keysChangedBeforeLoad = mutableSetOf<ChatDraftKey>()

    /**
     * Sessions deleted in this launch: a chat still on screen parks its queue
     * after the discard, which must not bring back a draft nobody can open.
     */
    private val deletedSessionKeys = mutableSetOf<ChatDraftKey>()
    private var loadDeferred: Deferred<Map<ChatDraftKey, ChatDraft>>? = null
    private var persistJob: Job? = null

    @Volatile
    private var isLoaded = false
    private val retentionLeases = mutableListOf<java.lang.ref.WeakReference<ChatDraftAttachmentLease>>()
    private var retiringAttachmentFiles: Set<String> = emptySet()
    private val storageLock = Mutex()

    suspend fun draft(key: ChatDraftKey): ChatDraft? {
        loadIfNeeded()
        // Hydration must not capture a reference from a tentative cleanup write.
        return storageLock.withLock { synchronized(stateLock) { drafts[key] } }
    }

    /** Updates the draft's typed text without disturbing its attachments or settings. */
    fun setText(text: String, key: ChatDraftKey) {
        markChangedBeforeLoad(key)
        updateDraft(key) { it.copy(text = text) }
    }

    /** Replaces explicit quoted passages without disturbing typed text, attachments, or settings. */
    fun setQuotes(quotes: List<ComposerQuote>, key: ChatDraftKey) {
        markChangedBeforeLoad(key)
        updateDraft(key) { it.copy(quotes = quotes) }
    }

    fun setContent(text: String, quotes: List<ComposerQuote>, key: ChatDraftKey) {
        markChangedBeforeLoad(key)
        updateDraft(key) { it.copy(text = text, quotes = quotes) }
    }

    /**
     * Replaces the draft's staged-attachment records without disturbing its
     * text or settings. A composer passes the records it last synced as
     * [previous]: records it never saw (a share moved in meanwhile, #1181)
     * stay after its own. Null replaces every record.
     */
    fun setAttachments(
        attachments: List<ChatDraftAttachment>,
        key: ChatDraftKey,
        previous: List<ChatDraftAttachment>? = null,
    ) {
        markChangedBeforeLoad(key)
        updateDraft(key) { draft ->
            val unseen = if (previous != null) {
                val seen = (previous + attachments).mapTo(HashSet()) { it.id }
                draft.attachments.filter { it.id !in seen }
            } else {
                emptyList()
            }
            draft.copy(attachments = attachments + unseen)
        }
    }

    /** Replaces the draft's settings snapshot without disturbing its text or attachments. */
    fun setSettings(settings: ChatDraftSettings, key: ChatDraftKey) {
        markChangedBeforeLoad(key)
        val normalized = settings.normalized()
        updateDraft(key) { it.copy(settings = normalized.takeIf { s -> !s.isEmpty }) }
    }

    /**
     * Parks the messages queued behind a run in the chat's draft, in one
     * write (#857). Only files with a durable copy are recorded: nothing else
     * can be restored on reopen. Synchronous, so it lands even while the
     * chat's view is going away. Returns the merged draft, or null when the
     * chat's session was deleted.
     */
    fun parkQueuedMessages(queued: List<QueuedDraftMessage>, key: ChatDraftKey): ChatDraft? {
        if (key in deletedSessionKeys) return null
        markChangedBeforeLoad(key)
        updateDraft(key) { draft ->
            ChatDraftQueueParking.merged(
                draft,
                queuedTexts = queued.map { it.text },
                queuedAttachments = queued.flatMap { it.attachments }.filter { it.file != null },
            )
        }
        return synchronized(stateLock) { drafts[key] }
    }

    /**
     * Port of `appendShare`: adds share [shareId] to [key]'s draft — [text]
     * after any unsent text and [files] after any staged ones. Each file gets
     * its durable copy first; the records, text and [shareId] then land in one
     * change that is on disk before this returns, so the caller can let go of
     * the share. A share already there adds nothing. [fits] vets the records
     * with the share's files added and throws if they don't fit. A throw takes
     * back only what this share added, so the share can be moved again.
     */
    suspend fun appendShare(
        shareId: String,
        text: String,
        files: List<ChatDraftShareFile>,
        key: ChatDraftKey,
        maximumFileBytes: Int,
        fits: (List<ChatDraftAttachment>) -> Unit = {},
    ) {
        loadIfNeeded()
        fun isHere(): Boolean = synchronized(stateLock) { drafts[key]?.sharedImportIds?.contains(shareId) == true }
        val lease = makeAttachmentLease(key)
        val records = mutableListOf<ChatDraftAttachment>()
        suspend fun deleteCopies() {
            for (file in records.mapNotNull { it.file }) {
                lease.files.remove(file)
                deleteAttachmentIfUnreferenced(file)
            }
        }
        try {
            for (file in files) {
                if (isHere()) break
                val id = UUID.randomUUID().toString()
                val copy = stageAttachment(
                    data = file.data,
                    filename = file.name,
                    lease = lease,
                    attachmentId = id,
                    maximumFileBytes = maximumFileBytes,
                )
                records += ChatDraftAttachment(
                    id = id, name = file.name, mime = file.mime, size = file.data.size, isImage = file.isImage, file = copy,
                )
            }
            if (!isHere()) coroutineContext.ensureActive()
        } catch (error: Throwable) {
            deleteCopies()
            throw error
        }
        if (isHere()) {
            deleteCopies()
            flush()
            if (!isHere()) throw ChatDraftStorageException.Unavailable
            return
        }
        if (records.isNotEmpty()) {
            // Checked in the step that commits, so files a composer added meanwhile count.
            try {
                fits(synchronized(stateLock) { drafts[key]?.attachments.orEmpty() } + records)
            } catch (error: Throwable) {
                deleteCopies()
                throw error
            }
        }
        val unsent = synchronized(stateLock) { drafts[key]?.text ?: "" }
        updateDraft(key) { draft ->
            val trimmed = draft.text.trim('\n')
            draft.copy(
                text = if (text.isEmpty()) draft.text else if (trimmed.isEmpty()) text else "$trimmed\n\n$text",
                attachments = draft.attachments + records,
                sharedImportIds = draft.sharedImportIds + shareId,
            )
        }
        val merged = synchronized(stateLock) { drafts[key]?.text }
        val ids = records.mapTo(HashSet()) { it.id }
        try {
            flush()
            // A composer's send, snapshot or typing during the write may have dropped a record or the text.
            val current = synchronized(stateLock) { drafts[key] }
            val recordsPresent = current?.attachments?.mapTo(HashSet()) { it.id }?.containsAll(ids) == true
            if (!isHere() || !recordsPresent || (text.isNotEmpty() && current?.text?.contains(text) != true)) {
                throw ChatDraftStorageException.Unavailable
            }
        } catch (error: Throwable) {
            updateDraft(key) { draft ->
                draft.copy(
                    sharedImportIds = draft.sharedImportIds - shareId,
                    attachments = draft.attachments.filterNot { it.id in ids },
                    text = if (draft.text == merged) unsent else draft.text,
                )
            }
            // A replay after a quit must not find the share here. Unsaved, the copies stay.
            if (runCatching { flush() }.isSuccess) deleteCopies()
            throw error
        }
    }

    /**
     * Removes one composer draft and deletes attachment copies that no other
     * draft still references. Used after the server accepts session deletion;
     * a later queue park for the key is skipped.
     */
    suspend fun discardDraft(key: ChatDraftKey) {
        deletedSessionKeys += key
        loadIfNeeded()
        discardDrafts { it == key }
    }

    /** Removes every composer draft owned by a server before that server is removed. */
    suspend fun discardDrafts(serverId: String) {
        loadIfNeeded()
        discardDrafts { it.serverId == serverId }
    }

    /** Port of Swift's `discardBotDrafts`: scope by server, optional connection, and optional deleted bot/Profile. */
    suspend fun discardHermesDrafts(serverId: String, connectionID: String? = null, profile: String? = null) {
        loadIfNeeded()
        discardDrafts { it.serverId == serverId && it.hermesConnectionID != null &&
            (connectionID == null || it.hermesConnectionID == connectionID) &&
            (profile == null || it.hermesProfile == profile) }
    }

    /** Port of `setBotSubmissionUncertain`: callers flush before sending to the host. */
    fun setBotSubmissionUncertain(uncertain: Boolean, key: ChatDraftKey) {
        if (key.hermesConnectionID == null) return
        markChangedBeforeLoad(key)
        updateDraft(key) { it.copy(botSubmissionUncertain = uncertain) }
    }

    /** Clears the draft's user-authored content; attachments and settings are managed separately. */
    fun clearDraft(key: ChatDraftKey) = setContent("", emptyList(), key)

    /** Retains a rejected send's captured files even if its view already left; merge by identity. */
    fun retainRejectedAttachments(attachments: List<ChatDraftAttachment>, key: ChatDraftKey) {
        if (attachments.isEmpty() || key in deletedSessionKeys) return
        markChangedBeforeLoad(key)
        updateDraft(key) { draft ->
            val existing = draft.attachments.mapTo(HashSet()) { it.id }
            draft.copy(attachments = draft.attachments + attachments.filter { it.id !in existing })
        }
    }

    /**
     * Reconciles a server-accepted composer submission against the draft context
     * that owned it. Content is consumed only while it still exactly matches the
     * captured submission; later text or quote edits survive. Attachment records
     * are independently consumed by identity so files added while the request was
     * pending are never touched.
     *
     * Returns the records removed from the draft so the caller can release their
     * leases and delete copies after persisting this mutation.
     */
    suspend fun consumeAcceptedSubmission(
        submittedText: String,
        submittedQuotes: List<ComposerQuote>,
        submittedAttachmentIds: Set<String>,
        key: ChatDraftKey,
    ): List<ChatDraftAttachment> {
        loadIfNeeded()
        var consumed = emptyList<ChatDraftAttachment>()
        updateDraft(key) { draft ->
            val contentUnchanged = draft.text == submittedText && draft.quotes == submittedQuotes
            if (submittedAttachmentIds.isEmpty()) {
                draft.copy(
                    text = if (contentUnchanged) "" else draft.text,
                    quotes = if (contentUnchanged) emptyList() else draft.quotes,
                )
            } else {
                consumed = draft.attachments.filter { it.id in submittedAttachmentIds }
                draft.copy(
                    text = if (contentUnchanged) "" else draft.text,
                    quotes = if (contentUnchanged) emptyList() else draft.quotes,
                    attachments = draft.attachments.filterNot { it.id in submittedAttachmentIds },
                )
            }
        }
        return consumed
    }

    /**
     * Moves the entire draft (text, quotes, attachments, settings) between
     * contexts, e.g. a new-chat draft into its created session. The target's
     * existing draft is replaced only when the source holds one.
     */
    fun moveDraft(from: ChatDraftKey, to: ChatDraftKey): ChatDraft {
        markChangedBeforeLoad(from)
        markChangedBeforeLoad(to)
        val moved: ChatDraft
        synchronized(stateLock) {
            moved = (drafts[from] ?: drafts[to] ?: ChatDraft()).copy(lastUsedAt = now())
            liveLeases().filter { it.key == from }.forEach { it.key = to }
            drafts.remove(from)
            if (moved.isEmpty) drafts.remove(to) else drafts[to] = moved
        }
        schedulePersist()
        return moved
    }

    /** A lease lives with its composer, not with a view redraw; weak registration releases abandoned owners. */
    fun makeAttachmentLease(key: ChatDraftKey?): ChatDraftAttachmentLease {
        val lease = ChatDraftAttachmentLease(key)
        synchronized(stateLock) {
            retentionLeases.removeAll { it.get() == null }
            retentionLeases += java.lang.ref.WeakReference(lease)
        }
        return lease
    }

    suspend fun markUsed(key: ChatDraftKey) {
        loadIfNeeded()
        synchronized(stateLock) {
            val draft = drafts[key] ?: return
            drafts[key] = draft.copy(lastUsedAt = now())
        }
        schedulePersist()
    }

    private fun liveLeases(): List<ChatDraftAttachmentLease> = retentionLeases.mapNotNull { it.get() }

    private fun protectedAttachmentFiles(): Set<String> = synchronized(stateLock) {
        retentionLeases.removeAll { it.get() == null }
        val files = HashSet<String>()
        for (lease in liveLeases()) {
            files += lease.files
            files += lease.filesByAttachmentId.values
            lease.key?.let { key -> drafts[key]?.attachments?.mapNotNullTo(files) { it.file } }
        }
        files
    }

    /**
     * Port of `stageAttachment`: serializes admission, record commits and
     * deletion. When the retained copies plus [data] exceed the budget, the
     * oldest unused copies (unreferenced leftovers first, then by draft
     * recency and strip position) are removed from their records on disk and
     * deleted before the new copy is written. Throws
     * [ChatDraftStorageException.Unavailable] when the budget cannot be met.
     */
    suspend fun stageAttachment(
        data: ByteArray,
        filename: String,
        lease: ChatDraftAttachmentLease,
        attachmentId: String = UUID.randomUUID().toString(),
        maximumFileBytes: Int = io.github.jcartu.hermex.model.PendingAttachment.maximumUploadBytes,
    ): String {
        val attachmentStore = attachmentStore ?: throw ChatDraftStorageException.Unavailable
        if (data.size > maximumFileBytes || data.size > retainedByteLimit) throw ChatDraftStorageException.Unavailable
        lease.slotIds += attachmentId
        var didStage = false
        try {
            loadIfNeeded()
            storageLock.withLock {
                coroutineContext.ensureActive()
                lease.key?.let { key ->
                    val ids = synchronized(stateLock) {
                        val ids = drafts[key]?.attachments?.mapTo(HashSet()) { it.id } ?: HashSet()
                        liveLeases().filter { it.key == key }.forEach { ids += it.slotIds }
                        ids
                    }
                    ids.remove(attachmentId)
                    if (ids.size >= MAXIMUM_ATTACHMENT_COUNT) throw ChatDraftStorageException.AttachmentLimit
                }
                val inventory = withContext(ioDispatcher) { attachmentStore.retainedFileBytes() }
                val required = inventory.values.sum() + data.size - retainedByteLimit
                if (required > 0) {
                    reclaimRetainedBytes(required, inventory, attachmentStore)
                    // Deletion can fail; never assume it freed bytes.
                    val remaining = withContext(ioDispatcher) { attachmentStore.retainedFileBytes() }.values.sum()
                    if (remaining + data.size > retainedByteLimit) throw ChatDraftStorageException.Unavailable
                }
                coroutineContext.ensureActive()
                val file = withContext(ioDispatcher) { attachmentStore.save(data, filename) }
                lease.files += file
                didStage = true
                return file
            }
        } finally {
            if (!didStage) lease.slotIds -= attachmentId
        }
    }

    /** Evicts the oldest unused copies until [required] bytes are reclaimed; caller holds [storageLock]. */
    private suspend fun reclaimRetainedBytes(
        required: Long,
        inventory: Map<String, Long>,
        attachmentStore: ChatDraftAttachmentStoring,
    ) {
        val original = synchronized(stateLock) { LinkedHashMap(drafts) }
        val protected = protectedAttachmentFiles()
        // A shared copy is as recent as its most recently used reference.
        val recency = HashMap<String, Long>()
        val position = HashMap<String, Int>()
        for (draft in original.values) {
            draft.attachments.forEachIndexed { index, attachment ->
                val file = attachment.file ?: return@forEachIndexed
                if (file in protected) return@forEachIndexed
                recency[file] = maxOf(recency[file] ?: Long.MIN_VALUE, draft.lastUsedAt ?: Long.MIN_VALUE)
                position[file] = minOf(position[file] ?: index, index)
            }
        }
        // Interrupted saves/deletions can leave unreferenced copies behind.
        // Reclaim those before draft content, but keep every live reservation.
        val referenced = original.values.flatMap { draft -> draft.attachments.mapNotNull { it.file } }.toSet()
        for (file in inventory.keys) {
            if (file !in referenced && file !in protected) {
                recency[file] = Long.MIN_VALUE
                position[file] = -1
            }
        }
        val ordered = recency.keys.sortedWith(
            compareBy<String>({ recency.getValue(it) }, { position.getValue(it) }, { it }),
        )
        val selected = LinkedHashSet<String>()
        var reclaimed = 0L
        for (file in ordered) {
            if (reclaimed >= required) break
            val bytes = inventory[file] ?: continue
            if (bytes <= 0) continue
            selected += file
            reclaimed += bytes
        }
        if (reclaimed < required) throw ChatDraftStorageException.Unavailable

        val updated = LinkedHashMap<ChatDraftKey, ChatDraft>()
        for ((key, draft) in original) {
            val remaining = draft.attachments.filterNot { it.file != null && it.file in selected }
            val next = draft.copy(attachments = remaining)
            if (!next.isEmpty) updated[key] = next
        }
        // Do not expose the tentative removal. If a composer opens, edits or
        // moves a draft while the write suspends, repair the disk and refuse
        // this admission; none of its selected files have been deleted.
        try {
            persistence.write(updated)
        } catch (error: Throwable) {
            runCatching { persistence.write(synchronized(stateLock) { LinkedHashMap(drafts) }) }
            throw error
        }
        val unchanged = synchronized(stateLock) { drafts == original }
        if (!coroutineContext.isActive || !unchanged || protectedAttachmentFiles().any { it in selected }) {
            persistence.write(synchronized(stateLock) { LinkedHashMap(drafts) })
            coroutineContext.ensureActive()
            throw ChatDraftStorageException.Unavailable
        }
        synchronized(stateLock) {
            drafts = updated
            retiringAttachmentFiles = selected
        }
        try {
            // From this point readers see only the committed records.
            for (file in selected.sorted()) {
                withContext(ioDispatcher) { attachmentStore.delete(file) }
            }
        } finally {
            synchronized(stateLock) { retiringAttachmentFiles = emptySet() }
        }
    }

    fun removeAttachmentReference(id: String, key: ChatDraftKey) {
        updateDraft(key) { draft -> draft.copy(attachments = draft.attachments.filterNot { it.id == id }) }
    }

    /**
     * Used after explicit removal or a successful send. Persists the record
     * removal first and retains copies still owned by another draft or lease.
     */
    suspend fun deleteAttachmentIfUnreferenced(file: String) {
        loadIfNeeded()
        storageLock.withLock {
            val attachmentStore = attachmentStore ?: return
            if (runCatching { persistence.write(synchronized(stateLock) { LinkedHashMap(drafts) }) }.isFailure) return
            val referenced = synchronized(stateLock) {
                drafts.values.any { draft -> draft.attachments.any { it.file == file } }
            }
            if (referenced || file in protectedAttachmentFiles()) return
            withContext(ioDispatcher) { attachmentStore.delete(file) }
        }
    }

    /** Reads a durable copy's bytes; throws so the caller can classify the failure. */
    suspend fun attachmentData(file: String): ByteArray {
        val attachmentStore = attachmentStore ?: throw ChatDraftStorageException.Unavailable
        return withContext(ioDispatcher) { attachmentStore.data(file) }
    }

    /** `ChatDraftAttachmentStore.fileURL`: PDF thumbnails need a seekable descriptor, not another copied file. */
    fun attachmentFile(file: String): File =
        (attachmentStore ?: throw ChatDraftStorageException.Unavailable).file(file)

    suspend fun flush() {
        persistJob?.cancel()
        persistJob = null
        persistNow()
    }

    private suspend fun persistNow() {
        loadIfNeeded()
        storageLock.withLock {
            persistence.write(synchronized(stateLock) { LinkedHashMap(drafts) })
        }
    }

    private fun updateDraft(key: ChatDraftKey, mutate: (ChatDraft) -> ChatDraft) {
        var changed = false
        synchronized(stateLock) {
            val current = drafts[key]
            var draft = mutate(current ?: ChatDraft())
            draft = draft.copy(attachments = draft.attachments.filterNot { it.file != null && it.file in retiringAttachmentFiles })
            if (draft.isEmpty) {
                if (current != null) {
                    drafts.remove(key)
                    changed = true
                }
            } else if (current != draft) {
                drafts[key] = draft.copy(lastUsedAt = now())
                changed = true
            }
        }
        if (changed) schedulePersist()
    }

    private suspend fun discardDrafts(shouldDiscard: (ChatDraftKey) -> Boolean) {
        val filesToDelete: Set<String>
        synchronized(stateLock) {
            val discardedKeys = drafts.keys.filter(shouldDiscard)
            if (discardedKeys.isEmpty()) return
            val discarded = discardedKeys.mapNotNull { drafts.remove(it) }
            val stillReferenced = drafts.values.flatMap { draft -> draft.attachments.mapNotNull { it.file } }.toSet()
            filesToDelete = discarded.flatMap { draft -> draft.attachments.mapNotNull { it.file } }.toSet() - stillReferenced
        }
        schedulePersist()
        for (file in filesToDelete.sorted()) {
            deleteAttachmentIfUnreferenced(file)
        }
    }

    private fun markChangedBeforeLoad(key: ChatDraftKey) {
        if (!isLoaded) synchronized(stateLock) { keysChangedBeforeLoad += key }
    }

    private suspend fun loadIfNeeded() {
        if (isLoaded) return
        val deferred = synchronized(stateLock) {
            loadDeferred ?: scope.async { persistence.load() }.also { loadDeferred = it }
        }
        val persisted = deferred.await()
        var shouldSweep = false
        synchronized(stateLock) {
            if (isLoaded) return
            for ((key, draft) in persisted) {
                if (key !in keysChangedBeforeLoad) drafts[key] = draft
            }
            isLoaded = true
            loadDeferred = null
            shouldSweep = true
        }
        if (shouldSweep) sweepOrphanedAttachmentFiles()
    }

    /**
     * Backstop cleanup for orphaned attachment copies — e.g. an ingest that
     * crashed between writing the durable copy and persisting its record.
     * Age-gated so copies whose record write is still pending survive.
     */
    private fun sweepOrphanedAttachmentFiles() {
        val attachmentStore = attachmentStore ?: return
        scope.launch {
            storageLock.withLock {
                val referenced = synchronized(stateLock) {
                    drafts.values.flatMap { draft -> draft.attachments.mapNotNull { it.file } }.toSet()
                } + protectedAttachmentFiles()
                withContext(ioDispatcher) { attachmentStore.sweep(referenced, attachmentSweepMaxAgeMillis, now()) }
            }
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            try {
                delay(debounceMillis)
                persistNow()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A failed debounced write is retried by the next change or flush.
            }
        }
    }

    companion object {
        const val MAXIMUM_ATTACHMENT_COUNT = 10
    }
}
