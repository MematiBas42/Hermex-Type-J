package io.github.jcartu.hermex.ui.feature.bots

import android.content.Context
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotRoomEvent
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import io.github.jcartu.hermex.model.hermes.BotRoomLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Port of `Features/Bots/BotHistoryCache.swift`: a disposable, bounded room-message search
 * index plus a separate process-memory recent transcript store. Disk work and matching are
 * serialized off Main. Only user/member text is retained; tools, request payloads, credentials,
 * drafts, runtimes, authority and live permissions never cross this boundary.
 */
class BotHistoryCache(
    private val directory: File? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : BotInbox.RoomHistory {
    @Serializable
    data class Scope(val serverKey: String = "", val connectionID: String = "") {
        companion object {
            fun of(server: String, connectionID: String): Scope =
                Scope(BotHistoryCache.serverKey(server), connectionID)
        }
    }

    @Serializable
    data class Message(
        val id: String = "",
        val role: String = "",
        val text: String = "",
        val seq: Int? = null,
        val sender: String? = null,
        val memberID: String? = null,
        /** Epoch seconds. */
        val timestamp: Double? = null,
        val displayKind: String? = null,
        val threadID: String? = null,
        val eventID: String? = null,
    ) {
        val roomEvent: BotJSON?
            get() {
                val sequence = seq ?: return null
                return BotJSON.objectOf(
                    "seq" to BotJSON.number(sequence),
                    "kind" to BotJSON.string(role),
                    "event_id" to (eventID?.let(BotJSON::string) ?: BotJSON.NULL),
                    "actor" to BotJSON.objectOf(
                        "id" to (memberID?.let(BotJSON::string) ?: BotJSON.NULL),
                        "display_name" to (sender?.let(BotJSON::string) ?: BotJSON.NULL),
                    ),
                    "payload" to BotJSON.objectOf(
                        "text" to BotJSON.string(text),
                        "thread_id" to (threadID?.let(BotJSON::string) ?: BotJSON.NULL),
                    ),
                    "created_at" to (timestamp?.let(BotJSON::number) ?: BotJSON.NULL),
                )
            }
    }

    @Serializable
    data class Snapshot(
        val id: String = UUID.randomUUID().toString(),
        val scope: Scope = Scope(),
        /** Kept under Swift's historical field name so old disposable snapshots remain readable. */
        @SerialName("profileName") val roomName: String? = null,
        val savedAtMillis: Long = 0L,
        val messages: List<Message> = emptyList(),
        val roomID: String? = null,
        val cursor: Int? = null,
        val earlierBoundary: Int? = null,
    ) {
        val replayPage: BotJSON
            get() = BotJSON.objectOf(
                "events" to BotJSON.array(messages.mapNotNull(Message::roomEvent)),
                "cursor" to BotJSON.number(cursor ?: 0),
            )
    }

    data class Hit(val snapshot: Snapshot, val message: Message, val excerpt: String) {
        val id: String get() = "${snapshot.id}/${message.id}"
        val roomID: String get() = requireNotNull(snapshot.roomID)
        val roomName: String get() = snapshot.roomName ?: roomID
        val sequence: Int get() = requireNotNull(message.seq)
        val sender: String? get() = message.sender

        /**
         * Minimal identity/display fallback for cold/offline search. A reader must replace it
         * with `groups.state` before granting authority, members, approvals or send permission.
         */
        val cachedRoom: BotGroupRoom
            get() = BotGroupRoom(
                id = roomID,
                name = roomName,
                members = emptyList(),
                updatedAt = message.timestamp ?: snapshot.savedAtMillis / 1_000.0,
                latestSeq = maxOf(snapshot.cursor ?: 0, sequence),
                authority = null,
                epoch = null,
                disbanded = false,
            )
    }

    /** A recovery's disk-write lease. Removal/clear/newer recovery revokes older writers. */
    data class WriterToken internal constructor(
        internal val scope: Scope,
        internal val roomID: String,
        internal val generation: Long,
        internal val capturedAtMillis: Long,
    )

    val recent = BotRecentTranscripts()

    private data class RoomIdentity(val scope: Scope, val roomID: String)

    private val mutex = Mutex()
    private var loaded = false
    private var needsPrunePersistence = false
    private var lastRoomWriteAtMillis = Long.MIN_VALUE
    private var snapshots = mutableListOf<Snapshot>()
    private val retiredRooms = HashSet<RoomIdentity>()
    private val retiredScopes = HashSet<Scope>()
    private val clearedAt = ConcurrentHashMap<String, Long>()
    private val clearedAllAt = AtomicLong(Long.MIN_VALUE)
    private val writerGenerations = HashMap<RoomIdentity, Long>()
    private val writerStartedAt = HashMap<RoomIdentity, Long>()

    suspend fun beginWriter(key: BotRoomKey): WriterToken = io {
        loadLocked()
        val identity = identity(key)
        val generation = (writerGenerations[identity] ?: 0L) + 1L
        val cutoff = maxOf(clearedAllAt.get(), clearedAt[identity.scope.serverKey] ?: Long.MIN_VALUE)
        val now = clockMillis()
        val startedAt = if (now <= cutoff && cutoff < Long.MAX_VALUE) cutoff + 1 else now
        writerGenerations[identity] = generation
        writerStartedAt[identity] = minOf(writerStartedAt[identity] ?: startedAt, startedAt)
        WriterToken(identity.scope, identity.roomID, generation, startedAt)
    }

    /**
     * Finds individual messages newest first. `roomIDs == null` is the cold/offline fallback;
     * an empty set is an authoritative empty room list. All rooms share [MAXIMUM_HITS].
     */
    suspend fun search(
        query: String,
        server: String,
        connectionID: String,
        roomIDs: Set<String>?,
        nowMillis: Long = clockMillis(),
    ): List<Hit> = io {
        val needle = query.trim()
        if (needle.isEmpty()) return@io emptyList()
        loadLocked()
        pruneExpiredLocked(nowMillis)
        persistPendingPruneLocked()
        val scope = Scope.of(server, connectionID)
        val hits = ArrayList<Hit>(MAXIMUM_HITS)
        for (snapshot in snapshots.asReversed()) {
            currentCoroutineContext().ensureActive()
            val roomID = snapshot.roomID ?: continue
            if (snapshot.scope != scope || roomIDs?.contains(roomID) == false) continue
            for (message in snapshot.messages.asReversed()) {
                currentCoroutineContext().ensureActive()
                if (message.seq == null) continue
                val match = message.text.indexOf(needle, ignoreCase = true)
                if (match < 0) continue
                val before = maxOf(0, match - 55)
                val after = minOf(message.text.length, match + needle.length + 100)
                val excerpt = buildString {
                    if (before > 0) append('…')
                    append(message.text.substring(before, after))
                    if (after < message.text.length) append('…')
                }
                hits += Hit(snapshot, message, excerpt)
                if (hits.size == MAXIMUM_HITS) return@io hits
            }
        }
        hits
    }

    /** Completed contiguous replay windows commit together; overlaps merge by sequence. */
    suspend fun appendRoom(
        key: BotRoomKey,
        room: BotGroupRoom,
        page: BotJSON,
        since: Int,
        receivedAtMillis: Long = clockMillis(),
        writer: WriterToken? = null,
    ) = io {
        loadLocked()
        val identity = identity(key)
        if (!writerAdmitted(identity, writer, receivedAtMillis)) return@io
        val writeAtMillis = maxOf(receivedAtMillis, writer?.capturedAtMillis ?: receivedAtMillis)
        if (room.id != key.roomID || room.disbanded) return@io
        val cursor = page["cursor"].integer?.takeIf { it >= since } ?: return@io
        val previous = snapshots.firstOrNull { it.scope == identity.scope && it.roomID == identity.roomID }
        val overlaps = previous?.let {
            since <= (it.cursor ?: 0) && cursor >= (it.earlierBoundary ?: 0)
        } == true
        val rows = if (overlaps) previous!!.messages.toMutableList() else mutableListOf()
        val seen = rows.mapNotNullTo(HashSet(), Message::seq)
        for (value in page["events"].list.orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (value["room_id"].text != key.roomID) continue
            val event = BotRoomEvent.fromJsonOrNull(value) ?: continue
            if (event.seq <= since || event.seq > cursor || event.kind !in INDEXED_KINDS || !seen.add(event.seq)) continue
            val text = event.payload["text"].text?.takeIf { it.isNotEmpty() } ?: continue
            if (text.toByteArray(Charsets.UTF_8).size > MAXIMUM_MESSAGE_BYTES) continue
            rows += Message(
                id = event.seq.toString(),
                role = event.kind,
                text = text,
                seq = event.seq,
                sender = if (event.kind == "message.user") "You" else event.sender(room),
                memberID = event.payload["member_id"].text ?: event.actor["id"].text,
                timestamp = event.timestamp,
                threadID = event.threadID,
                eventID = event.eventID,
            )
        }
        rows.sortBy { it.seq ?: 0 }
        var boundary = if (overlaps) minOf(previous?.earlierBoundary ?: since, since) else since
        if (rows.size > MAXIMUM_MESSAGES) {
            boundary = maxOf(boundary, rows[rows.size - MAXIMUM_MESSAGES - 1].seq ?: boundary)
            rows.subList(0, rows.size - MAXIMUM_MESSAGES).clear()
        }
        val next = Snapshot(
            id = previous?.id ?: UUID.randomUUID().toString(),
            scope = identity.scope,
            roomName = room.name,
            savedAtMillis = maxOf(previous?.savedAtMillis ?: writeAtMillis, writeAtMillis),
            messages = rows,
            roomID = key.roomID,
            cursor = if (overlaps) maxOf(previous?.cursor ?: 0, cursor) else cursor,
            earlierBoundary = boundary,
        )
        val cursorOnly = previous != null && previous.messages == next.messages &&
            previous.earlierBoundary == next.earlierBoundary && previous.roomName == next.roomName
        if (cursorOnly && previous?.cursor == next.cursor) return@io
        snapshots.removeAll { it.scope == identity.scope && it.roomID == identity.roomID }
        snapshots += next
        if (cursorOnly && writeAtMillis - lastRoomWriteAtMillis < CURSOR_ONLY_SAVE_INTERVAL_MILLIS) return@io
        persistLocked(pruneLocked(writeAtMillis))
        lastRoomWriteAtMillis = writeAtMillis
    }

    suspend fun roomHistory(key: BotRoomKey, nowMillis: Long = clockMillis()): Snapshot? = io {
        loadLocked()
        pruneExpiredLocked(nowMillis)
        persistPendingPruneLocked()
        val identity = identity(key)
        snapshots.firstOrNull { it.scope == identity.scope && it.roomID == identity.roomID }
    }

    /** Expiry/disband permanently retires this id and revokes every queued writer. */
    override suspend fun removeRoom(key: BotRoomKey) = io {
        recent.remove { it == BotRecentTranscripts.Key(key) }
        loadLocked()
        val identity = identity(key)
        retiredRooms += identity
        revokeLocked(identity)
        snapshots.removeAll { it.scope == identity.scope && it.roomID == identity.roomID }
        persistLocked()
    }

    /** Called only after a complete authoritative `groups.list`, never a partial page. */
    override suspend fun retainRooms(ids: Set<String>, server: String, connectionID: String) = io {
        val scope = Scope.of(server, connectionID)
        recent.remove { it.scope == scope && it.roomID !in ids }
        loadLocked()
        val removed = buildSet {
            snapshots.forEach { snapshot ->
                snapshot.roomID?.takeIf { snapshot.scope == scope && it !in ids }?.let(::add)
            }
            writerGenerations.keys.forEach { identity ->
                if (identity.scope == scope && identity.roomID !in ids) add(identity.roomID)
            }
        }
        if (removed.isEmpty()) return@io
        for (roomID in removed) {
            val identity = RoomIdentity(scope, roomID)
            retiredRooms += identity
            revokeLocked(identity)
        }
        snapshots.removeAll { it.scope == scope && it.roomID?.let { roomID -> roomID !in ids } == true }
        persistLocked()
    }

    /**
     * Permanently removes one saved connection and revokes all of its old writers. A future
     * connection has a different UUID and therefore a different scope.
     */
    suspend fun removeConnection(server: String, connectionID: String) = io {
        val scope = Scope.of(server, connectionID)
        recent.remove { it.scope == scope }
        loadLocked()
        retiredScopes += scope
        writerGenerations.keys.filter { it.scope == scope }.forEach(::revokeLocked)
        snapshots.filter { it.scope == scope }.forEach { snapshot ->
            snapshot.roomID?.let { revokeLocked(RoomIdentity(scope, it)) }
        }
        snapshots.removeAll { it.scope == scope }
        persistLocked()
    }

    /**
     * Synchronous writer fence for CacheStore's synchronous clear hook. Call this before
     * launching [clearServer]; repeating the same cutoff is inert, so delayed disk deletion
     * cannot remove a recent transcript saved by a post-clear recovery.
     */
    fun markServerCleared(server: String, cutoffMillis: Long) {
        val key = serverKey(server)
        var advanced = false
        clearedAt.compute(key) { _, previous ->
            val next = maxOf(previous ?: Long.MIN_VALUE, cutoffMillis)
            advanced = next > (previous ?: Long.MIN_VALUE)
            next
        }
        if (advanced) recent.remove { it.scope.serverKey == key }
    }

    fun markAllCleared(cutoffMillis: Long) {
        while (true) {
            val previous = clearedAllAt.get()
            if (cutoffMillis <= previous) return
            if (clearedAllAt.compareAndSet(previous, cutoffMillis)) {
                recent.remove { true }
                return
            }
        }
    }

    suspend fun clearServer(server: String, nowMillis: Long = clockMillis()) {
        markServerCleared(server, nowMillis)
        io {
            val key = serverKey(server)
            loadLocked()
            snapshots.removeAll { it.scope.serverKey == key && it.savedAtMillis <= nowMillis }
            persistLocked()
        }
    }

    suspend fun clearAll(nowMillis: Long = clockMillis()) {
        markAllCleared(nowMillis)
        io {
            loadLocked()
            snapshots.removeAll { it.savedAtMillis <= nowMillis }
            persistLocked()
        }
    }

    /**
     * Server removal retires only scopes that existed at the cutoff. A later connection at the
     * same configured address has a fresh UUID and may cache again in this process.
     */
    suspend fun removeServer(server: String) {
        val key = serverKey(server)
        val cutoff = clockMillis()
        markServerCleared(server, cutoff)
        io {
            loadLocked()
            val oldScopes = buildSet {
                writerStartedAt.forEach { (identity, startedAt) ->
                    if (identity.scope.serverKey == key && startedAt <= cutoff) add(identity.scope)
                }
                snapshots.forEach { snapshot ->
                    if (snapshot.scope.serverKey == key && snapshot.savedAtMillis <= cutoff) add(snapshot.scope)
                }
            }
            retiredScopes += oldScopes
            writerGenerations.keys.filter { it.scope in oldScopes }.forEach(::revokeLocked)
            snapshots.removeAll { it.scope in oldScopes }
            persistLocked()
        }
    }


    private fun writerAdmitted(identity: RoomIdentity, writer: WriterToken?, receivedAtMillis: Long): Boolean {
        if (identity in retiredRooms || identity.scope in retiredScopes) return false
        val watermark = maxOf(clearedAllAt.get(), clearedAt[identity.scope.serverKey] ?: Long.MIN_VALUE)
        if (writer == null) return receivedAtMillis > watermark
        return writer.scope == identity.scope && writer.roomID == identity.roomID &&
            writer.capturedAtMillis > watermark &&
            writer.generation == writerGenerations[identity]
    }

    private fun revokeLocked(identity: RoomIdentity) {
        writerGenerations[identity] = (writerGenerations[identity] ?: 0L) + 1L
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun identity(key: BotRoomKey): RoomIdentity = RoomIdentity(Scope.of(key.server, key.connectionID), key.roomID)

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val file = historyFile() ?: return
        if (!file.isFile || file.length() > MAXIMUM_BYTES * 2L) return
        val decoded = runCatching {
            json.decodeFromString(ListSerializer(Snapshot.serializer()), file.readText())
        }.getOrElse { emptyList() }
        snapshots = decoded
            .filter { it.roomID != null && it.scope.serverKey.isNotEmpty() && it.scope.connectionID.isNotEmpty() }
            .toMutableList()
        lastRoomWriteAtMillis = snapshots.maxOfOrNull(Snapshot::savedAtMillis) ?: Long.MIN_VALUE
        val previousCount = snapshots.size
        pruneLocked(clockMillis())
        if (snapshots.size != previousCount || snapshots.size != decoded.size) {
            needsPrunePersistence = true
            persistPendingPruneLocked()
        }
    }

    private fun pruneExpiredLocked(nowMillis: Long) {
        val before = snapshots.size
        snapshots.removeAll { nowMillis - it.savedAtMillis >= LIFETIME_MILLIS }
        if (snapshots.size != before) needsPrunePersistence = true
    }

    /** Returns an encoding of the current bounded list, avoiding a second encode on write. */
    private fun pruneLocked(nowMillis: Long): ByteArray {
        snapshots.removeAll { nowMillis - it.savedAtMillis >= LIFETIME_MILLIS }
        snapshots.sortBy(Snapshot::savedAtMillis)
        while (snapshots.size > MAXIMUM_ROOMS) snapshots.removeAt(0)
        while (snapshots.isNotEmpty()) {
            val encoded = encodeLocked()
            if (encoded.size <= MAXIMUM_BYTES) return encoded
            snapshots.removeAt(0)
        }
        return encodeLocked()
    }

    private fun persistPendingPruneLocked() {
        if (!needsPrunePersistence) return
        runCatching { persistLocked(pruneLocked(clockMillis())) }
            .onSuccess { needsPrunePersistence = false }
    }

    private fun encodeLocked(): ByteArray =
        json.encodeToString(ListSerializer(Snapshot.serializer()), snapshots).toByteArray(Charsets.UTF_8)

    private fun persistLocked(encoded: ByteArray = encodeLocked()) {
        val file = historyFile() ?: return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun historyFile(): File? = directory?.resolve("history.json")

    companion object {
        const val MAXIMUM_MESSAGES = 500
        const val MAXIMUM_MESSAGE_BYTES = 16_384
        const val MAXIMUM_BYTES = 8 * 1024 * 1024
        const val MAXIMUM_ROOMS = 100
        const val MAXIMUM_HITS = 100
        const val LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val CURSOR_ONLY_SAVE_INTERVAL_MILLIS = 10L * 60L * 1_000L
        private val INDEXED_KINDS = setOf("message.user", "message.member")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        @Volatile private var processShared: BotHistoryCache? = null

        /** Process singleton shared by the inbox, room readers and local search sheet. */
        fun shared(context: Context): BotHistoryCache = processShared ?: synchronized(this) {
            processShared ?: BotHistoryCache(File(context.applicationContext.cacheDir, "BotHistory"))
                .also { processShared = it }
        }

        fun serverKey(server: String): String = MessageDigest.getInstance("SHA-256")
            .digest(server.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Process-memory recent room logs. Every recovery claims a writer token; a later screen,
 * removal or eviction makes an older writer inert. Values are bounded by count and estimated
 * payload bytes and disappear with the process.
 */
class BotRecentTranscripts(
    private val maximumEntries: Int = 12,
    private val maximumBytes: Int = 8 * 1024 * 1024,
) {
    data class Key(val scope: BotHistoryCache.Scope, val roomID: String) {
        constructor(room: BotRoomKey) : this(BotHistoryCache.Scope.of(room.server, room.connectionID), room.roomID)
    }

    private data class Entry(val owner: String, val log: BotRoomLog?, val cost: Int, val access: Long)

    private val entries = LinkedHashMap<Key, Entry>()
    private var access = 0L

    @Synchronized
    fun log(key: Key): BotRoomLog? {
        val entry = entries[key] ?: return null
        access += 1
        entries[key] = entry.copy(access = access)
        return entry.log
    }

    @Synchronized
    fun begin(key: Key): String {
        val owner = UUID.randomUUID().toString()
        access += 1
        val previous = entries[key]
        entries[key] = Entry(owner, previous?.log, previous?.cost ?: 0, access)
        prune()
        return owner
    }

    @Synchronized
    fun save(log: BotRoomLog, key: Key, owner: String) {
        if (entries[key]?.owner != owner) return
        val cost = log.events.sumOf { event ->
            512 + event.actor.encoded.toByteArray(Charsets.UTF_8).size + event.payload.encoded.toByteArray(Charsets.UTF_8).size
        } * 2
        if (cost > maximumBytes) {
            entries.remove(key)
            return
        }
        access += 1
        entries[key] = Entry(owner, log, cost, access)
        prune()
    }

    @Synchronized
    fun remove(predicate: (Key) -> Boolean) {
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) if (predicate(iterator.next())) iterator.remove()
    }

    private fun prune() {
        var bytes = entries.values.sumOf(Entry::cost)
        while (entries.size > maximumEntries || bytes > maximumBytes) {
            val oldest = entries.minByOrNull { it.value.access } ?: break
            bytes -= oldest.value.cost
            entries.remove(oldest.key)
        }
    }
}
