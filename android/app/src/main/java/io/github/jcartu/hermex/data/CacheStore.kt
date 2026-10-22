package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.model.CachePolicy
import io.github.jcartu.hermex.model.CachedMessage
import io.github.jcartu.hermex.model.CachedSession
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.SessionSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Port of `CacheStore.swift`. iOS backs the offline cache with SwiftData; the
 * Android port uses two JSON files (`sessions.json` / `messages.json`) under
 * [directory], written atomically via temp-file rename. Rows are keyed by the
 * same unique `cacheKey` the Swift `@Attribute(.unique)` models use, so the
 * upsert / stale-key / maintenance semantics mirror the Swift statics exactly.
 *
 * All methods are synchronized; callers wrap calls in `Dispatchers.IO`.
 * Timestamps (`now` / `cachedAt`) are epoch millis, server URLs plain strings.
 */
class CacheStore(
    private val directory: File,
    private val json: Json,
    private val onClear: (String?) -> Unit = {},
) {
    private val sessionsFile = File(directory, "sessions.json")
    private val messagesFile = File(directory, "messages.json")
    private val lock = Any()
    /** Process-owned persistence survives a chat ViewModel being cleared. */
    val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** Drafts are not offline-cache entries and are never evicted by cache maintenance. */
    val chatDraftStore: ChatDraftStore by lazy {
        ChatDraftStore(ChatDraftFilePersistence(directory, json), ChatDraftAttachmentStore(directory), persistenceScope)
    }
    /** Live-run snapshots (`ActiveChatStreamSnapshotStore`): process-owned like drafts, never evicted by cache maintenance. */
    val activeStreamSnapshots: ActiveChatStreamSnapshotStore by lazy {
        ActiveChatStreamSnapshotStore(directory, json, persistenceScope)
    }

    /** Keyed by `cacheKey` (the SwiftData unique attribute). */
    private var sessions: LinkedHashMap<String, CachedSession>? = null
    private var messages: LinkedHashMap<String, CachedMessage>? = null

    /** Port of `cachedSessions(serverURL:in:now:)`. */
    fun cachedSessions(
        serverURL: String,
        now: Long = System.currentTimeMillis(),
    ): List<SessionSummary> = synchronized(lock) {
        loadedSessions().values
            .filter { it.serverURLString == serverURL && it.archived != true && it.expiresAt > now }
            .map(CachedSession::toSessionSummary)
    }

    /**
     * Port of `cachedMessages(serverURL:sessionID:in:limit:now:)`: a
     * non-positive limit returns nothing; with a limit, the LAST `limit`
     * messages by `sortIndex` are returned, in ascending order.
     */
    fun cachedMessages(
        serverURL: String,
        sessionID: String,
        limit: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): List<ChatMessage> = synchronized(lock) {
        if (limit != null && limit <= 0) return emptyList()

        var rows = loadedMessages().values
            .filter {
                it.serverURLString == serverURL && it.sessionID == sessionID && it.expiresAt > now
            }
            .sortedBy(CachedMessage::sortIndex)
        if (limit != null && rows.size > limit) {
            rows = rows.subList(rows.size - limit, rows.size)
        }
        rows.map(CachedMessage::toChatMessage)
    }

    /**
     * Port of `cacheSessions(_:serverURL:in:cachedAt:)`: upserts every
     * non-archived session with a `sessionId`, drops this server's rows that
     * are no longer present, then runs maintenance and persists.
     */
    fun cacheSessions(
        sessions: List<SessionSummary>,
        serverURL: String,
        cachedAt: Long = System.currentTimeMillis(),
    ): Unit = synchronized(lock) {
        val store = loadedSessions()
        val cacheableSessions = sessions.filter { it.archived != true && it.sessionId != null }
        val freshKeys = cacheableSessions.mapNotNull { session ->
            session.sessionId?.let { CachedSession.cacheKey(serverURL, it) }
        }.toHashSet()

        for (session in cacheableSessions) {
            val sessionID = session.sessionId ?: continue
            val cacheKey = CachedSession.cacheKey(serverURL, sessionID)
            store[cacheKey] = CachedSession.from(serverURL, session, cachedAt)
        }

        store.values.removeAll { it.serverURLString == serverURL && it.cacheKey !in freshKeys }

        performMaintenance(now = cachedAt)
        persist()
    }

    /**
     * Port of `cacheSession(_:serverURL:in:cachedAt:)`: an archived session is
     * deleted from the cache instead of upserted.
     */
    fun cacheSession(
        session: SessionSummary,
        serverURL: String,
        cachedAt: Long = System.currentTimeMillis(),
    ): Unit = synchronized(lock) {
        val sessionID = session.sessionId ?: return

        val store = loadedSessions()
        val cacheKey = CachedSession.cacheKey(serverURL, sessionID)
        if (session.archived == true) {
            store.remove(cacheKey)
        } else {
            store[cacheKey] = CachedSession.from(serverURL, session, cachedAt)
        }

        performMaintenance(now = cachedAt)
        persist()
    }

    /**
     * Port of `cacheMessages(_:serverURL:sessionID:in:cachedAt:)`: upserts by
     * cache key with `sortIndex` = list offset, drops stale rows for this
     * server+session, then runs maintenance and persists.
     */
    fun cacheMessages(
        messages: List<ChatMessage>,
        serverURL: String,
        sessionID: String,
        cachedAt: Long = System.currentTimeMillis(),
    ): Unit = synchronized(lock) {
        val store = loadedMessages()
        val freshKeys = messages.mapIndexedTo(HashSet<String>()) { offset, message ->
            CachedMessage.cacheKey(serverURL, sessionID, message, offset)
        }

        for ((offset, message) in messages.withIndex()) {
            val cacheKey = CachedMessage.cacheKey(serverURL, sessionID, message, offset)
            store[cacheKey] = CachedMessage.from(serverURL, sessionID, message, offset, cachedAt)
        }

        store.values.removeAll {
            it.serverURLString == serverURL && it.sessionID == sessionID && it.cacheKey !in freshKeys
        }

        performMaintenance(now = cachedAt)
        persist()
    }

    /** Port of `clearAll(in:)`. */
    fun clearAll(): Unit = synchronized(lock) {
        onClear(null)
        loadedSessions().clear()
        loadedMessages().clear()
        persist()
    }

    /**
     * Deletes only the cached sessions and messages belonging to [serverURL],
     * leaving every other configured server's offline data intact (#18). Backs
     * the Settings "Clear Offline Cache" action (active server) and the purge
     * of a server's cache when it is removed, so a removed/reset server never
     * leaves orphaned rows behind.
     */
    fun clearCache(serverURL: String): Unit = synchronized(lock) {
        onClear(serverURL)
        loadedSessions().values.removeAll { it.serverURLString == serverURL }
        loadedMessages().values.removeAll { it.serverURLString == serverURL }
        persist()
    }

    // MARK: - Maintenance (ports of the private CacheStore.swift statics)

    private fun performMaintenance(now: Long) {
        deleteExpiredSessions(now)
        deleteExpiredMessages(now)
        evictOldestMessagesIfNeeded()
    }

    private fun deleteExpiredSessions(now: Long) {
        loadedSessions().values.removeAll { it.expiresAt <= now }
    }

    private fun deleteExpiredMessages(now: Long) {
        loadedMessages().values.removeAll { it.expiresAt <= now }
    }

    /**
     * Port of `evictOldestMessagesIfNeeded(in:)`: overflow beyond
     * [CachePolicy.maxMessages] is evicted oldest-first, ordered by `cachedAt`,
     * then `timestamp ?? 0`, then `sortIndex` — the exact Swift comparator.
     */
    private fun evictOldestMessagesIfNeeded() {
        val store = loadedMessages()
        val overflowCount = store.size - CachePolicy.maxMessages
        if (overflowCount <= 0) return

        store.values
            .sortedWith(
                Comparator { left, right ->
                    if (left.cachedAt != right.cachedAt) {
                        left.cachedAt.compareTo(right.cachedAt)
                    } else if (left.timestamp != right.timestamp) {
                        (left.timestamp ?: 0.0).compareTo(right.timestamp ?: 0.0)
                    } else {
                        left.sortIndex.compareTo(right.sortIndex)
                    }
                }
            )
            .take(overflowCount)
            .forEach { store.remove(it.cacheKey) }
    }

    // MARK: - File backing

    private fun loadedSessions(): LinkedHashMap<String, CachedSession> =
        sessions ?: load(sessionsFile, CachedSession.serializer(), CachedSession::cacheKey)
            .also { sessions = it }

    private fun loadedMessages(): LinkedHashMap<String, CachedMessage> =
        messages ?: load(messagesFile, CachedMessage.serializer(), CachedMessage::cacheKey)
            .also { messages = it }

    /** A missing or unreadable file is an empty cache — never a crash. */
    private fun <T> load(
        file: File,
        serializer: kotlinx.serialization.KSerializer<T>,
        key: (T) -> String,
    ): LinkedHashMap<String, T> {
        if (!file.isFile) return LinkedHashMap()
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), file.readText(StandardCharsets.UTF_8))
                .associateByTo(LinkedHashMap<String, T>(), key)
        }.getOrDefault(LinkedHashMap<String, T>())
    }

    private fun persist() {
        directory.mkdirs()
        writeAtomically(
            sessionsFile,
            json.encodeToString(ListSerializer(CachedSession.serializer()), loadedSessions().values.toList()),
        )
        writeAtomically(
            messagesFile,
            json.encodeToString(ListSerializer(CachedMessage.serializer()), loadedMessages().values.toList()),
        )
    }

    private fun writeAtomically(file: File, content: String) {
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(content, StandardCharsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
