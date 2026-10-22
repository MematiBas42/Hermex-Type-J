package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.model.CachePolicy
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.hermes.HermesSessionRow
import io.github.jcartu.hermex.model.hermes.HermesNewestCoverage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A settled transcript row; the host's row id is independent of the UI message id and display order. */
data class HermesCachedRow(val rowID: Int, val message: ChatMessage)

/**
 * Port of Persistence/CacheStore+Hermes.swift (#1054/#1258). The Android cache uses atomic JSON
 * files like CacheStore, but separate files preserve the webui cache's all-rows sweep semantics.
 * All methods are synchronized; call them on IO. Profile + lineage root scope both row kinds.
 */
class HermesCacheStore(private val directory: File, private val json: Json) {
    @Serializable private data class Session(
        val serverId: String, val profile: String, val row: HermesSessionRow,
        val cachedAt: Long = 0, val expiresAt: Long = 0,
    ) { val key: String get() = "$serverId|hermes|$profile|${row.identity}" }
    @Serializable private data class Message(
        val serverId: String, val scope: String, val rowID: Int, val message: ChatMessage,
        val sortIndex: Int, val cachedAt: Long = 0, val expiresAt: Long = 0,
    ) { val key: String get() = "$serverId|$scope|row|$rowID" }
    private val lock = Any()
    private var sessions: LinkedHashMap<String, Session>? = null
    private var messages: LinkedHashMap<String, Message>? = null
    private val epochs = mutableMapOf<String, Int>()
    private val sessionsFile = File(directory, "hermes-sessions.json")
    private val messagesFile = File(directory, "hermes-messages.json")

    fun cachedHermesSessions(serverId: String, profile: String?, now: Long = System.currentTimeMillis()): List<Pair<String, HermesSessionRow>> = synchronized(lock) {
        sessionRows().values.filter { it.serverId == serverId && (profile == null || it.profile == profile) && it.expiresAt > now }
            .sortedByDescending { it.row.lastActive ?: it.row.startedAt ?: 0.0 }.map { it.profile to it.row }
    }

    /** A partial walk only upserts; only a complete first-to-last-page walk may remove absent rows. */
    fun cacheHermesSessions(rows: List<HermesSessionRow>, profile: String, reachedEnd: Boolean, serverId: String,
                            cachedAt: Long = System.currentTimeMillis(), expectedEpoch: Int? = null) = synchronized(lock) {
        if (expectedEpoch != null && expectedEpoch != hermesCacheEpoch(serverId)) return@synchronized
        val store = sessionRows()
        val fresh = HashSet<String>()
        for (row in rows) {
            if (row.archived == true || row.isBotChat || row.isRoomSession || row.hidden == true) continue
            val entry = Session(serverId, profile, row, cachedAt, cachedAt + CachePolicy.ttl)
            if (!fresh.add(entry.key)) continue
            val old = store[entry.key]
            if (old == null || old.row != row || cachedAt - old.cachedAt >= ROW_REFRESH_INTERVAL) store[entry.key] = entry
        }
        if (reachedEnd) store.values.removeAll { it.serverId == serverId && it.profile == profile && it.key !in fresh }
        maintain(cachedAt)
        persist()
    }

    fun removeHermesSession(lineageRoot: String, profile: String, serverId: String) = synchronized(lock) {
        sessionRows().remove("$serverId|hermes|$profile|$lineageRoot")
        val scope = hermesScope(profile, lineageRoot)
        messageRows().values.removeAll { it.serverId == serverId && it.scope == scope }
        persist()
    }

    fun hermesLineageRoot(key: String, profile: String, serverId: String): String? = synchronized(lock) {
        sessionRows().values.firstOrNull { it.serverId == serverId && it.profile == profile && it.row.id == key }?.row?.identity
    }

    fun cachedHermesMessages(serverId: String, profile: String, lineageRoot: String, limit: Int = 100): List<ChatMessage> =
        cachedHermesMessages(serverId, hermesScope(profile, lineageRoot), limit)

    fun cachedHermesMessages(serverId: String, scope: String, limit: Int = 100, now: Long = System.currentTimeMillis()): List<ChatMessage> = synchronized(lock) {
        if (limit <= 0) return@synchronized emptyList()
        messageRows().values.filter { it.serverId == serverId && it.scope == scope && it.expiresAt > now }
            .sortedBy { it.sortIndex }.takeLast(limit).map { it.message }
    }

    fun cacheHermesMessages(serverId: String, profile: String, lineageRoot: String, rows: List<HermesCachedRow>,
                            coverage: HermesNewestCoverage?, expectedEpoch: Int? = null) =
        cacheHermesMessages(serverId, hermesScope(profile, lineageRoot), rows, coverage, expectedEpoch)

    /** Preserve earlier cached pages in display order; host ids are not chronological after compaction. */
    fun cacheHermesMessages(serverId: String, scope: String, rows: List<HermesCachedRow>, coverage: HermesNewestCoverage?,
                            expectedEpoch: Int? = null, cachedAt: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (expectedEpoch != null && expectedEpoch != hermesCacheEpoch(serverId)) return@synchronized
        val held = rows.distinctBy { it.rowID }
        val start = pruneCutRows(serverId, scope, held.mapTo(HashSet()) { it.rowID }, coverage)
        val store = messageRows()
        held.forEachIndexed { offset, row ->
            // Offline is plain settled text/attachments, never a stale live reasoning/tool projection.
            val message = row.message.copy(reasoning = null, toolCalls = null)
            val entry = Message(serverId, scope, row.rowID, message, start + offset, cachedAt, cachedAt + CachePolicy.ttl)
            store[entry.key] = entry
        }
        maintain(cachedAt)
        persist()
    }

    fun dropHermesMessagesTheHostCut(serverId: String, scope: String, rows: List<HermesCachedRow>, coverage: HermesNewestCoverage) = synchronized(lock) {
        pruneCutRows(serverId, scope, rows.mapTo(HashSet()) { it.rowID }, coverage)
        persist()
    }

    private fun pruneCutRows(serverId: String, scope: String, held: Set<Int>, coverage: HermesNewestCoverage?): Int {
        val cached = messageRows().values.filter { it.serverId == serverId && it.scope == scope }
        val byId = cached.associateBy { it.rowID }
        val covered = when (coverage) {
            HermesNewestCoverage.All -> Int.MIN_VALUE
            is HermesNewestCoverage.From -> coverage.rowIDs.firstNotNullOfOrNull { byId[it]?.sortIndex }
            null -> null
        }
        var start = 0
        for (row in cached) if (row.rowID !in held) {
            if (covered != null && row.sortIndex > covered) messageRows().remove(row.key)
            else start = maxOf(start, row.sortIndex + 1)
        }
        return start
    }

    fun cachedHermesBotChatRoot(serverId: String, connectionID: String, profile: String, now: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        val prefix = botPrefix(connectionID, profile, false)
        messageRows().values.filter { it.serverId == serverId && it.scope.startsWith(prefix) && it.expiresAt > now }
            .maxByOrNull { it.cachedAt }?.scope?.removePrefix(prefix)
    }

    fun removeHermesBotChats(exceptRoot: String, serverId: String, connectionID: String, profile: String) = synchronized(lock) {
        val prefix = botPrefix(connectionID, profile, false)
        messageRows().values.removeAll { it.serverId == serverId && it.scope.startsWith(prefix) && it.scope != prefix + exceptRoot }
        persist()
    }

    fun removeHermesBotChats(serverId: String, connectionID: String, profile: String? = null) = synchronized(lock) {
        revokeHermesWriters(serverId)
        val prefixes = listOf(botPrefix(connectionID, profile, false), botPrefix(connectionID, profile, true))
        messageRows().values.removeAll { it.serverId == serverId && prefixes.any(it.scope::startsWith) }
        persist()
    }

    fun hermesCacheEpoch(serverId: String): Int = synchronized(lock) { epochs[serverId] ?: 0 }
    fun revokeHermesWriters(serverId: String): Unit = synchronized(lock) { epochs[serverId] = (epochs[serverId] ?: 0) + 1 }
    fun clearCache(serverId: String) = synchronized(lock) {
        revokeHermesWriters(serverId)
        sessionRows().values.removeAll { it.serverId == serverId }
        messageRows().values.removeAll { it.serverId == serverId }
        persist()
    }
    fun clearAll() = synchronized(lock) {
        (epochs.keys + sessionRows().values.map { it.serverId } + messageRows().values.map { it.serverId }).toSet().forEach(::revokeHermesWriters)
        sessionRows().clear(); messageRows().clear(); persist()
    }
    private fun maintain(now: Long) {
        sessionRows().values.removeAll { it.expiresAt <= now }
        val store = messageRows()
        store.values.removeAll { it.expiresAt <= now }
        val overflow = store.size - CachePolicy.maxMessages
        if (overflow > 0) store.values.sortedWith(compareBy<Message> { it.cachedAt }.thenBy { it.message.timestamp ?: 0.0 }.thenBy { it.sortIndex })
            .take(overflow).forEach { store.remove(it.key) }
    }
    private fun sessionRows(): LinkedHashMap<String, Session> = sessions ?: read(sessionsFile, Session.serializer()) { it.key }.also { sessions = it }
    private fun messageRows(): LinkedHashMap<String, Message> = messages ?: read(messagesFile, Message.serializer()) { it.key }.also { messages = it }
    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>, key: (T) -> String): LinkedHashMap<String, T> =
        if (!file.isFile) LinkedHashMap() else runCatching {
            json.decodeFromString(ListSerializer(serializer), file.readText()).associateByTo(LinkedHashMap(), key)
        }.getOrDefault(LinkedHashMap())
    private fun persist() {
        directory.mkdirs()
        write(sessionsFile, json.encodeToString(ListSerializer(Session.serializer()), sessionRows().values.toList()))
        write(messagesFile, json.encodeToString(ListSerializer(Message.serializer()), messageRows().values.toList()))
    }
    private fun write(file: File, content: String) {
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(content)
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    companion object {
        private const val ROW_REFRESH_INTERVAL = 60 * 60 * 1000L
        fun hermesScope(profile: String, lineageRoot: String) = "hermes|$profile|$lineageRoot"
        fun hermesBotChatScope(connectionID: String, profile: String, root: String) = botPrefix(connectionID, profile, false) + root
        fun hermesArchivedBotChatScope(connectionID: String, profile: String, root: String) = botPrefix(connectionID, profile, true) + root
        private fun botPrefix(connectionID: String, profile: String?, archived: Boolean) =
            "${if (archived) "hermes-bot-archive" else "hermes-bot"}|$connectionID|" + (profile?.let { "$it|" } ?: "")
    }
}
