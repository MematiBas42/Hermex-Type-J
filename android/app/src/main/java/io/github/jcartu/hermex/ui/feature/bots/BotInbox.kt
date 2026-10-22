package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotConnectionAdvice
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotLiveStatuses
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomCapabilities
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import io.github.jcartu.hermex.net.hermes.BotLiveStatus
import io.github.jcartu.hermex.net.hermes.BotRoomFailure
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesREST
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Port of `BotInbox` (`Features/Bots/BotInbox.swift`): the Bots inbox for one configured
 * server — the roster, Desktop's pin, hidden and section organization, each bot's live turn
 * state, device-local unread marks and section order, and one live subscription that lasts
 * while the inbox is visible. [open] owns the transport and [close] ends it; every late reply
 * is dropped by the wire identity check, so a replaced or closed connection never writes into
 * the screen. Every mutation runs on [scope] (Main); transport callbacks hop there.
 */
class BotInbox(
    /** The configured server's id, every Bot store's key. */
    val server: String,
    private val scope: CoroutineScope,
    private val store: ConnectionRecords,
    private val unread: BotUnreadStore,
    private val roomStore: BotRoomOrganizeStore,
    private val sectionOrderStore: BotSectionOrderStore,
    private val avatarStore: BotAvatarStore,
    private val makeWire: (BotConnection) -> BotTransport,
    /** Drops this phone's drafts and offline-cache copy for one deleted bot. */
    private val purgeLocalState: suspend (connectionID: String, profile: String) -> Unit = { _, _ -> },
    /** The room history cache's retain/remove hooks (`BotHistoryCache`), when rooms ship with one. */
    private val roomHistory: RoomHistory? = null,
    /** Where each roster read leaves the Profiles' names (the Shortcuts picker's cache, #1180). */
    private val onRosterRead: ((List<BotProfile>) -> Unit)? = null,
    private val reloadSpacingMillis: Long = 1_000,
    private val reconnectDelaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
    private val statusPollIntervalMillis: Long = 5_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The saved connection record, as the inbox reads and backfills it. */
    interface ConnectionRecords {
        fun load(server: String): BotConnection?
        fun save(connection: BotConnection, server: String)
    }

    /** Room history cache hooks the inbox calls on an authoritative room list and a disband. */
    interface RoomHistory {
        suspend fun retainRooms(ids: Set<String>, server: String, connectionID: String)
        suspend fun removeRoom(key: BotRoomKey)
    }

    enum class Link { IDLE, CONNECTING, LIVE, DISCONNECTED }

    /** The roster split the way Desktop draws it: pinned first, the rest in server order, hidden bots apart. */
    data class Rows(val pinned: List<BotProfile> = emptyList(), val others: List<BotProfile> = emptyList(), val hidden: List<BotProfile> = emptyList())

    sealed class ChatRow {
        data class Bot(val profile: BotProfile) : ChatRow()
        data class Room(val room: BotGroupRoom) : ChatRow()

        val id: String
            get() = when (this) {
                is Bot -> "bot:" + profile.id
                is Room -> "room:" + room.id
            }

        /** Epoch seconds of the row's latest activity. */
        val activity: Double?
            get() = when (this) {
                is Bot -> profile.lastActive
                is Room -> room.updatedAt
            }
    }

    /** One of Desktop's named sections, as this roster names it. */
    data class SectionName(val id: String, val name: String)

    /** One block of the list under the tiles: a named section, or the final unfiled block (`name == null`). */
    data class ChatSection(val id: String, val name: String?, val chats: List<ChatRow>)

    /** A bot whose Bot Chat matched a message search on the host (#1146). */
    data class BotChatHit(val profileID: String, val snippet: String?, val isFromUser: Boolean?)

    data class State(
        val connection: BotConnection? = null,
        val profiles: List<BotProfile> = emptyList(),
        val rooms: List<BotGroupRoom> = emptyList(),
        val hasRoomList: Boolean = false,
        val roomCapabilities: BotRoomCapabilities = BotRoomCapabilities.NONE,
        /** Rooms pinned or hidden on this phone; the host has no such fields for rooms. */
        val roomFlags: BotRoomOrganizeStore.Flags = BotRoomOrganizeStore.Flags(),
        /** Section ids in the order the user placed them on this phone; empty means A–Z. */
        val sectionOrder: List<String> = emptyList(),
        val avatars: Map<String, Bitmap> = emptyMap(),
        val link: Link = Link.IDLE,
        val errorMessage: String? = null,
        /** True after the host refused the saved username or password (`Rejected(401)`): Update sign-in replaces Reconnect. */
        val needsSignIn: Boolean = false,
        /** What to check once an empty inbox has failed to reach the host three times in a row. */
        val routeAdvice: String? = null,
        /** Outcome of the last pin, hide or section write when it did not apply. */
        val notice: String? = null,
        /** Profiles (and `room:` ids) with a write in flight; their actions stay inert. */
        val editing: Set<String> = emptySet(),
        /** Session-only reveal of hidden bots, as in Desktop. Never persisted. */
        val showsHidden: Boolean = false,
        val selection: BotInboxSelection = BotInboxSelection(),
        /** Device-local watermarks: the canonical `last_active` the user last saw per Profile. */
        val seen: Map<String, Double> = emptyMap(),
        /** Live turn state per Profile from the newest `session.active_list` read on this connection. */
        val liveStatuses: Map<String, BotLiveStatus> = emptyMap(),
    ) {
        private val visibleRooms: List<BotGroupRoom> get() = if (roomCapabilities.enabled) rooms else emptyList()

        fun isRoomPinned(room: BotGroupRoom): Boolean = room.id in roomFlags.pinned
        fun isRoomHidden(room: BotGroupRoom): Boolean = room.id in roomFlags.hidden

        val hiddenCount: Int get() = profiles.count { it.hidden } + visibleRooms.count(::isRoomHidden)

        /** True until the first roster lands: a skeleton instead of a connection message. */
        val isLoadingRoster: Boolean
            get() = connection != null && profiles.isEmpty() && errorMessage == null && routeAdvice == null && link != Link.LIVE

        fun rows(search: String): Rows {
            val matching = profiles.filter { search.isEmpty() || it.name.contains(search, ignoreCase = true) }
            val shown = matching.filter { !it.hidden }
            return Rows(
                pinned = shown.filter { it.pinned },
                others = shown.filter { !it.pinned },
                hidden = if (showsHidden || search.isNotEmpty()) matching.filter { it.hidden } else emptyList(),
            )
        }

        fun rooms(query: String): List<BotGroupRoom> =
            if (!roomCapabilities.enabled) emptyList() else rooms.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }

        /** True when the canonical chat moved past what this device last showed; a row without a watermark reads as seen. */
        fun isUnread(profile: BotProfile): Boolean {
            val lastActive = profile.lastActive ?: return false
            val mark = seen[profile.id] ?: return false
            return lastActive > mark
        }

        /**
         * Every named section on the roster, pinned and hidden members included, in this phone's
         * order: the ids the user placed first, the rest A–Z. A section takes the name most of
         * its members carry, ties going to the first member in roster order.
         */
        val sectionNames: List<SectionName>
            get() {
                val tallies = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
                for (profile in profiles) {
                    val id = profile.sectionID ?: continue
                    val name = profile.sectionName ?: continue
                    val names = tallies.getOrPut(id) { mutableListOf() }
                    val index = names.indexOfFirst { it.first == name }
                    if (index >= 0) names[index] = name to (names[index].second + 1) else names.add(name to 1)
                }
                val named = tallies.map { (id, names) ->
                    // Strictly greater keeps the earliest name on a tie.
                    val winner = names.drop(1).fold(names[0]) { best, next -> if (next.second > best.second) next else best }
                    SectionName(id, winner.first)
                }
                return ordered(named, sectionOrder)
            }

        /** The sections "Reorder Sections…" offers: only those the list can head (a pinned-only section lives in the tiles). */
        val reorderableSectionNames: List<SectionName>
            get() {
                val listed = profiles.filter { it.sectionName != null && (!it.pinned || it.hidden) }.mapNotNull { it.sectionID }.toSet()
                return sectionNames.filter { it.id in listed }
            }

        /** The tiles above the timeline: Desktop's pinned bots, then rooms pinned on this phone. */
        val pinned: List<ChatRow>
            get() = byAttention(rows("").pinned.map { ChatRow.Bot(it) }) +
                byAttention(visibleRooms.filter { isRoomPinned(it) && !isRoomHidden(it) }.map { ChatRow.Room(it) })

        /**
         * Everything except the pinned tiles, as sections in [sectionNames] order with the
         * unfiled block last. Each block is in attention order; an empty one is left out. With
         * no named sections this is one unfiled block, the flat list as before.
         */
        val sections: List<ChatSection>
            get() {
                val rows = rows("")
                val filed = LinkedHashMap<String, MutableList<ChatRow>>()
                val unfiled = ArrayList<ChatRow>()
                for (profile in rows.others + rows.hidden) {
                    val id = profile.sectionID
                    if (id != null && profile.sectionName != null) filed.getOrPut(id) { mutableListOf() }.add(ChatRow.Bot(profile))
                    else unfiled.add(ChatRow.Bot(profile))
                }
                // Rooms are never sectioned: Desktop keeps a room's section on the machine that filed it.
                unfiled += visibleRooms.filter { if (isRoomHidden(it)) showsHidden else !isRoomPinned(it) }.map { ChatRow.Room(it) }
                val blocks = ArrayList<ChatSection>()
                for (section in sectionNames) {
                    val chats = filed[section.id] ?: continue
                    blocks.add(ChatSection("section:" + section.id, section.name, byAttention(chats)))
                }
                if (unfiled.isNotEmpty()) blocks.add(ChatSection("unfiled", null, byAttention(unfiled)))
                return blocks
            }

        /** The one order inside every group: waiting, then working, then unread, then newest, undated last, ties by id. */
        fun byAttention(rows: List<ChatRow>): List<ChatRow> {
            fun rank(row: ChatRow): Int {
                val profile = (row as? ChatRow.Bot)?.profile ?: return 3
                return when (liveStatuses[profile.id]) {
                    BotLiveStatus.WAITING -> 0
                    BotLiveStatus.WORKING -> 1
                    null -> if (isUnread(profile)) 2 else 3
                }
            }
            return rows.sortedWith { a, b ->
                val rankA = rank(a)
                val rankB = rank(b)
                if (rankA != rankB) return@sortedWith rankA.compareTo(rankB)
                val activityA = a.activity
                val activityB = b.activity
                when {
                    activityA != null && activityB != null && activityA != activityB -> activityB.compareTo(activityA)
                    activityA != null && activityB == null -> -1
                    activityA == null && activityB != null -> 1
                    else -> a.id.compareTo(b.id)
                }
            }
        }

        fun roomKey(server: String, room: BotGroupRoom): BotRoomKey? = connection?.let { BotRoomKey(server, it.id, room.id) }

        /** A failed room read is not an authoritative empty list, even if the Bot roster is live. */
        val searchableRoomIDs: Set<String>? get() = if (hasRoomList && link == Link.LIVE) rooms.map { it.id }.toSet() else null

        fun mayEdit(profile: BotProfile): Boolean = link == Link.LIVE && profile.id !in editing

        /** The built-in Profile is the host itself; Hermes refuses to delete it. */
        fun mayDelete(profile: BotProfile): Boolean = profile.id != "default" && mayEdit(profile)

        fun mayRenameRoom(room: BotGroupRoom): Boolean = mayCommandRoom(room, "groups.rename")
        fun mayDisbandRoom(room: BotGroupRoom): Boolean = mayCommandRoom(room, "groups.disband")

        private fun mayCommandRoom(room: BotGroupRoom, method: String): Boolean =
            link == Link.LIVE && method in roomCapabilities.methods && !room.isForeign(roomCapabilities.authority) &&
                ChatRow.Room(room).id !in editing
    }

    private val _state = MutableStateFlow(State(connection = runCatching { store.load(server) }.getOrNull()))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    @Volatile
    private var wire: BotTransport? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var routeFailures = 0
    private var reloadJob: Job? = null
    private var reloadWanted = false
    private var reloadSerial = 0
    private var readsLiveStatus = true
    private var statusSerial = 0
    private var retriesStatusRead = false
    private var statusPollJob: Job? = null
    private var returnedFrom: String? = null
    private var uncertainDeletions = HashSet<String>()
    private var openJob: Job? = null

    private inline fun update(block: (State) -> State) = _state.update(block)

    private fun validate(client: BotTransport): () -> Unit {
        val saved = current.connection
        return {
            if (wire !== client || saved == null || store.load(server)?.id != saved.id) throw BotFailure.Stale
        }
    }

    // MARK: Device-local marks

    /** The user is entering this bot's chat: the row's activity is now seen. */
    fun markSeen(profile: BotProfile) {
        val lastActive = profile.lastActive ?: return
        if (current.seen[profile.id] == lastActive) return
        update { it.copy(seen = it.seen + (profile.id to lastActive)) }
        persistSeen()
    }

    /** The user came back from this bot's chat: the next roster read is marked seen once. */
    fun noteReturn(profile: BotProfile) {
        markSeen(profile)
        returnedFrom = profile.id
    }

    fun setShowsHidden(shows: Boolean) = update { it.copy(showsHidden = shows) }

    fun select(profile: BotProfile?) = update { it.copy(selection = it.selection.selecting(profile)) }
    fun selectRoom(key: BotRoomKey?) = update { it.copy(selection = it.selection.selectingRoom(key)) }
    fun clearSelection() = update { it.copy(selection = BotInboxSelection()) }

    /** Resolves a held destination against the live roster; a Profile the server no longer has selects nothing. */
    fun open(destination: BotDestination) = update {
        it.copy(selection = it.selection.opening(destination, it.connection, it.profiles))
    }

    // MARK: Lifecycle

    /**
     * Connects, reads the roster and avatars, and keeps the socket for live `sessions.changed`
     * reloads. Also the pull-to-refresh, Reconnect and foreground path, so a rejected password
     * stays unsent here until the saved record changes or [signInSaved] runs. Returns once the
     * roster, rooms, avatars and first statuses are in (or the attempt failed).
     */
    suspend fun open() {
        close()
        update { it.copy(hasRoomList = false) }
        var client: BotTransport? = null
        try {
            val saved = store.load(server)
            val previous = current.connection
            if (previous?.id != saved?.id) {
                update {
                    it.copy(
                        selection = BotInboxSelection(), profiles = emptyList(), avatars = emptyMap(), seen = emptyMap(),
                        rooms = emptyList(), roomCapabilities = BotRoomCapabilities.NONE, roomFlags = BotRoomOrganizeStore.Flags(),
                        sectionOrder = emptyList(), liveStatuses = emptyMap(),
                    )
                }
            }
            // The form can keep the UUID under a new address; advice for the old host does not carry over.
            if (previous?.id != saved?.id || previous?.address != saved?.address) {
                routeFailures = 0
                update { it.copy(routeAdvice = null) }
            }
            if (previous != saved) update { it.copy(needsSignIn = false) }
            update { it.copy(connection = saved) }
            if (saved == null) {
                update { it.copy(link = Link.IDLE) }
                return
            }
            if (current.seen.isEmpty()) update { it.copy(seen = unread.load(saved.id)) }
            if (current.roomFlags == BotRoomOrganizeStore.Flags()) update { it.copy(roomFlags = roomStore.load(saved.id)) }
            if (current.sectionOrder.isEmpty()) update { it.copy(sectionOrder = sectionOrderStore.load(server, saved.id)) }
            // Each resend spends one of the host's ten password logins a minute per client IP.
            if (current.needsSignIn) {
                update { it.copy(link = Link.DISCONNECTED) }
                return
            }
            val opened = makeWire(saved)
            client = opened
            wire = opened
            readsLiveStatus = true
            retriesStatusRead = false
            update { it.copy(link = Link.CONNECTING, errorMessage = null, notice = null) }
            opened.onEvent = { event ->
                if (event["type"].text == "sessions.changed") scope.launch { if (wire === opened) noteChange() }
            }
            opened.onDisconnect = { error ->
                scope.launch { if (wire === opened) drop(opened, error) }
            }
            opened.connect()
            if (wire !== opened || !currentCoroutineContext().isActive) return
            // The host answered, so the route advice no longer holds, even before the roster loads.
            routeFailures = 0
            update { it.copy(routeAdvice = null) }
            recordInstallID(opened.serverInstallID, saved)
            if (!reload(opened)) return
            // The status read runs beside rooms and avatars; nothing above waits for it.
            val statuses = statusPollJob
            refreshRooms(opened)
            if (wire !== opened || !currentCoroutineContext().isActive) return
            update { it.copy(link = Link.LIVE) }
            reconnectAttempts = 0
            refreshAvatars(opened)
            statuses?.join()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!currentCoroutineContext().isActive) return
            if (client != null) {
                // The socket's own disconnect callback may already have dropped this client.
                if (wire === client) drop(client, error)
            } else {
                update {
                    it.copy(
                        link = Link.DISCONNECTED,
                        errorMessage = (error as? BotFailure ?: BotFailure.Transport).message,
                        needsSignIn = error == BotFailure.Rejected(401),
                        liveStatuses = emptyMap(),
                    )
                }
            }
        }
    }

    /** [open] on [scope], replacing any open in flight. */
    fun openAsync(): Job {
        openJob?.cancel()
        return scope.launch { open() }.also { openJob = it }
    }

    /**
     * Trust on first use: stores the host's `install_id` on a record that has none, so every
     * later connect can refuse an address that starts reaching another host. The record is
     * re-read, because the connection form may have saved a new password or name under the same
     * UUID while this inbox connected. The in-memory connection gains the id too, because the
     * chats, rooms, creator and editor opened from this inbox build their clients from it.
     */
    private fun recordInstallID(live: String?, opened: BotConnection) {
        live ?: return
        val connection = current.connection
        if (connection?.id == opened.id && connection.address == opened.address && connection.installID == null) {
            update { it.copy(connection = connection.copy(installID = live)) }
        }
        val fresh = runCatching { store.load(server) }.getOrNull() ?: return
        if (fresh.id != opened.id || fresh.address != opened.address || fresh.installID != null) return
        runCatching { store.save(fresh.copy(installID = live), server) }
    }

    /** The connection form signed in and saved: the next [open] connects even when the record is unchanged. */
    fun signInSaved() = update { it.copy(needsSignIn = false) }

    /**
     * A chat or room opened from this inbox saw the host refuse `rejected` and is handing off to
     * the sign-in form. The inbox takes the same stop as its own 401, so the [open] that runs as
     * it reappears sends nothing. A record that signs in differently from the inbox's is ignored.
     */
    fun noteRejectedSignIn(rejected: BotConnection) {
        val connection = current.connection ?: return
        if (rejected.id != connection.id || rejected.address != connection.address ||
            rejected.username != connection.username || rejected.password != connection.password
        ) return
        close()
        routeFailures = 0
        update {
            it.copy(
                liveStatuses = emptyMap(), link = Link.DISCONNECTED, routeAdvice = null,
                errorMessage = BotConnectionAdvice.message(BotFailure.Rejected(401), rejected.address), needsSignIn = true,
            )
        }
    }

    fun close() {
        reconnectJob?.cancel(); reconnectJob = null
        reloadJob?.cancel(); reloadJob = null; reloadWanted = false
        statusPollJob?.cancel(); statusPollJob = null
        val client = wire
        wire = null
        if (client != null) scope.launch { client.close() }
        update { it.copy(link = Link.IDLE, editing = emptySet()) }
    }

    // MARK: Bot writes

    /**
     * Deletes the Profile on the host, then this phone's state for it. A refused delete leaves
     * everything in place; a lost reply is reported as uncertain and never retried on its own,
     * because the next roster read settles it.
     */
    suspend fun delete(profile: BotProfile) {
        val client = wire ?: return
        if (!current.mayDelete(profile)) return
        update { it.copy(editing = it.editing + profile.id, notice = null) }
        try {
            client.deleteProfile(profile.id)
            if (wire !== client) return
            forget(profile.id)
            if (wire !== client) return
            reload(client)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (wire !== client) return
            if (error is BotFailure.Rejected) {
                update { it.copy(notice = "Hermes did not delete this bot. It is still on the host.") }
            } else {
                uncertainDeletions.add(profile.id)
                update { it.copy(notice = "Could not confirm whether the bot was deleted. Pull down to refresh.") }
            }
        } finally {
            if (wire === client) update { it.copy(editing = it.editing - profile.id) }
        }
    }

    /** Drops everything this phone kept for a bot that no longer exists on the host. */
    private suspend fun forget(profile: String) {
        if (current.selection.profile?.id == profile) update { it.copy(selection = BotInboxSelection()) }
        val connection = current.connection ?: return
        update { it.copy(seen = it.seen - profile) }
        persistSeen()
        avatarStore.setImage(null, connection.id, profile, null)
        purgeLocalState(connection.id, profile)
    }

    suspend fun setPinned(pinned: Boolean, profile: BotProfile) = configure(profile, mapOf("pinned" to BotJSON.bool(pinned)))
    suspend fun setHidden(hidden: Boolean, profile: BotProfile) = configure(profile, mapOf("hidden" to BotJSON.bool(hidden)))

    /** Files the bot under a section already on the roster, stamping its id and the name the inbox heads it with. */
    suspend fun moveToSection(profile: BotProfile, section: SectionName) {
        if (profile.sectionID == section.id && profile.sectionName == section.name) return
        configure(profile, mapOf("sectionId" to BotJSON.string(section.id), "sectionName" to BotJSON.string(section.name)))
    }

    /**
     * Files the bot under a new section named [name], trimmed. A blank name writes nothing; a
     * name that exactly matches a section on the roster joins that one instead of making a twin.
     */
    suspend fun moveToNewSection(profile: BotProfile, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        current.sectionNames.firstOrNull { it.name == trimmed }?.let { return moveToSection(profile, it) }
        configure(profile, mapOf("sectionId" to BotJSON.string(newSectionID()), "sectionName" to BotJSON.string(trimmed)))
    }

    /** Back to unfiled. Explicit nulls, not missing keys: Desktop merges the host's meta over its local copy. */
    suspend fun removeFromSection(profile: BotProfile) =
        configure(profile, mapOf("sectionId" to BotJSON.NULL, "sectionName" to BotJSON.NULL))

    /**
     * Writes Desktop look fields through `profiles.configure`, sending the whole `hermes-bots`
     * object back with [changes] applied so unrelated Desktop fields survive, under the look
     * revision the row was read at. Nothing is shown as done until the host says it applied and
     * the roster is re-read; a conflict means Desktop wrote in between.
     */
    private suspend fun configure(profile: BotProfile, changes: Map<String, BotJSON>) {
        val client = wire ?: return
        if (!current.mayEdit(profile)) return
        update { it.copy(editing = it.editing + profile.id, notice = null) }
        val look = LinkedHashMap(profile.look).apply { putAll(changes) }
        try {
            val reply = client.call(
                HermesCall.ProfilesConfigure(HermesCall.ProfileChanges(name = profile.id, look = HermesCall.Look(look, profile.lookRevision ?: 0))),
                validate(client),
            )
            if (wire !== client) return
            if (reply["applied"]["ui_meta"].flag != true) {
                val notice = if (!reply["applied"]["ui_meta_conflicts"].isNull) {
                    "This bot changed in Hermes Desktop. The list was refreshed; try again."
                } else {
                    "Hermes did not save this change."
                }
                update { it.copy(notice = notice) }
            }
            if (reload(client)) refreshAvatars(client)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (wire !== client) return
            val notice = if (error == BotFailure.Rejected(-32601)) {
                "This Hermes version cannot organize bots from the phone."
            } else {
                "Hermes did not save this change."
            }
            update { it.copy(notice = notice) }
        } finally {
            if (wire === client) update { it.copy(editing = it.editing - profile.id) }
        }
    }

    // MARK: Roster reads

    /** Coalesces `sessions.changed` bursts: one reload in flight, at most one more queued, spaced by [reloadSpacingMillis]. */
    private fun noteChange() {
        reloadWanted = true
        if (reloadJob != null) return
        val client = wire ?: return
        reloadJob = scope.launch {
            while (reloadWanted && wire === client && isActive) {
                reloadWanted = false
                reload(client)
                if (wire !== client) break
                sleep(reloadSpacingMillis)
            }
            if (wire === client) reloadJob = null
        }
    }

    /**
     * One `profiles.list` on the live socket, then starts a live-status read against it without
     * waiting for one. Only the newest request's reply is applied, and only while [client] still
     * owns the inbox.
     */
    private suspend fun reload(client: BotTransport): Boolean {
        reloadSerial += 1
        val serial = reloadSerial
        try {
            val roster = client.call(HermesCall.ProfilesList(includeSessions = true))
            if (wire !== client || serial != reloadSerial) return false
            val rows = roster["profiles"].list ?: throw BotFailure.Unsupported
            val ids = HashSet<String>()
            val profiles = rows.mapNotNull(BotProfile::fromJsonOrNull).filter { ids.add(it.id) }
            onRosterRead?.invoke(profiles)
            var seen = current.seen
            var changed = false
            for (profile in profiles) {
                val lastActive = profile.lastActive ?: continue
                if (seen[profile.id] != null && profile.id != returnedFrom) continue
                seen = seen + (profile.id to lastActive)
                changed = true
            }
            returnedFrom = null
            update { it.copy(profiles = profiles, seen = seen) }
            if (changed) persistSeen()
            val present = profiles.map { it.id }.toSet()
            val settled = uncertainDeletions
            uncertainDeletions = HashSet()
            for (name in settled) {
                if (name in present) continue
                forget(name)
                if (wire !== client || serial != reloadSerial) return false
            }
            startLiveStatusRead(client)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (wire !== client || serial != reloadSerial) return false
            drop(client, error)
            return false
        }
    }

    /** Replaces any status read or re-read in flight with one read, now or after [delayMillis]. */
    private fun startLiveStatusRead(client: BotTransport, delayMillis: Long? = null) {
        statusPollJob?.cancel()
        statusPollJob = null
        if (!readsLiveStatus) return
        statusPollJob = scope.launch {
            if (delayMillis != null) sleep(delayMillis)
            if (!isActive || wire !== client) return@launch
            readLiveStatuses(client)
        }
    }

    /**
     * One read-only `session.active_list`, mapped onto the current roster. A failed read shows no
     * statuses rather than old ones, and never drops the socket: the roster owns the link. While
     * a bot is busy, or a read failed while one was, the next read is scheduled
     * [statusPollIntervalMillis] later; once every bot is idle it stops.
     */
    private suspend fun readLiveStatuses(client: BotTransport) {
        statusSerial += 1
        val serial = statusSerial
        val roster = reloadSerial
        suspend fun isCurrent() = currentCoroutineContext().isActive && wire === client && serial == statusSerial && roster == reloadSerial
        try {
            val reply = client.call(HermesCall.SessionActiveList)
            if (!isCurrent()) return
            setLiveStatuses(BotLiveStatuses.statuses(reply["sessions"].list ?: emptyList(), current.profiles))
            retriesStatusRead = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isCurrent()) return
            if (error == BotFailure.Rejected(-32601)) readsLiveStatus = false
            retriesStatusRead = retriesStatusRead || current.liveStatuses.isNotEmpty()
            setLiveStatuses(emptyMap())
        }
        if (current.liveStatuses.isEmpty() && !retriesStatusRead) statusPollJob = null
        else startLiveStatusRead(client, statusPollIntervalMillis)
    }

    private fun setLiveStatuses(statuses: Map<String, BotLiveStatus>) {
        if (statuses != current.liveStatuses) update { it.copy(liveStatuses = statuses) }
    }

    // MARK: Rooms

    fun updateRoom(room: BotGroupRoom, connectionID: String) {
        if (current.connection?.id != connectionID) return
        update { state ->
            val index = state.rooms.indexOfFirst { it.id == room.id }
            state.copy(rooms = if (index >= 0) state.rooms.toMutableList().also { it[index] = room } else listOf(room) + state.rooms)
        }
    }

    fun removeRoom(key: BotRoomKey) {
        if (key.server != server || key.connectionID != current.connection?.id) return
        update { it.copy(rooms = it.rooms.filter { room -> room.id != key.roomID }) }
    }

    fun reconcileRooms(values: List<BotGroupRoom>, connectionID: String) {
        if (current.connection?.id != connectionID) return
        update { it.copy(rooms = values) }
    }

    fun expireRoom(key: BotRoomKey) {
        if (key.server != server || key.connectionID != current.connection?.id) return
        update { it.copy(rooms = it.rooms.filter { room -> room.id != key.roomID }, notice = "This room’s history is no longer available.") }
    }

    /** Phone-only, so these apply at once and need no host round trip. */
    fun setRoomPinned(pinned: Boolean, room: BotGroupRoom) {
        update { it.copy(roomFlags = it.roomFlags.copy(pinned = if (pinned) it.roomFlags.pinned + room.id else it.roomFlags.pinned - room.id)) }
        persistRoomFlags()
    }

    fun setRoomHidden(hidden: Boolean, room: BotGroupRoom) {
        update { it.copy(roomFlags = it.roomFlags.copy(hidden = if (hidden) it.roomFlags.hidden + room.id else it.roomFlags.hidden - room.id)) }
        persistRoomFlags()
    }

    /** Places the named sections in [ids] order on this phone only; the rest follow A–Z after them. */
    fun setSectionOrder(ids: List<String>) {
        val connection = current.connection ?: return
        if (ids == current.sectionOrder) return
        update { it.copy(sectionOrder = ids) }
        sectionOrderStore.save(ids, server, connection.id)
    }

    /** Saves a drag in "Reorder Sections…"; a placed section the sheet leaves out keeps its slot. */
    fun placeReorderableSections(ids: List<String>) {
        val listed = ids.toSet()
        val next = ArrayDeque(ids)
        val order = current.sectionOrder
        setSectionOrder(
            current.sectionNames.mapNotNull { section ->
                if (section.id in listed) next.removeFirstOrNull() else section.id.takeIf { it in order }
            },
        )
    }

    /** Back to A–Z: forgets every placement for this connection. */
    fun resetSectionOrder() = setSectionOrder(emptyList())

    suspend fun renameRoom(room: BotGroupRoom, name: String) {
        val client = wire ?: return
        if (!current.mayRenameRoom(room) || !BotRoomRPC.validName(name) || name == room.name) return
        commandRoom(room, HermesCall.GroupsRename(room.id, UUID.randomUUID().toString().uppercase(), name), client) { result ->
            val updated = BotGroupRoom.fromJsonOrNull(result["room"])
            if (updated == null || updated.id != room.id || updated.disbanded) throw BotFailure.Unsupported
            update { state -> state.copy(rooms = state.rooms.map { if (it.id == room.id) updated else it }) }
        }
    }

    /** Nothing local is dropped until the host confirms the tombstone; a lost reply is settled by the next room list. */
    suspend fun disbandRoom(room: BotGroupRoom) {
        val client = wire ?: return
        if (!current.mayDisbandRoom(room)) return
        val key = current.roomKey(server, room) ?: return
        val disbanded = commandRoom(room, HermesCall.GroupsDisband(room.id), client) { result ->
            if (result["tombstone"]["room_id"].text != room.id || result["tombstone"]["disbanded_at"].number == null) throw BotFailure.Unsupported
            update { state ->
                state.copy(
                    rooms = state.rooms.filter { it.id != room.id },
                    roomFlags = state.roomFlags.copy(pinned = state.roomFlags.pinned - room.id, hidden = state.roomFlags.hidden - room.id),
                )
            }
            persistRoomFlags()
        }
        if (disbanded) runCatching { roomHistory?.removeRoom(key) }
    }

    /** True when the host accepted the write and [accept] took it. */
    private suspend fun commandRoom(room: BotGroupRoom, call: HermesCall, client: BotTransport, accept: (BotJSON) -> Unit): Boolean {
        val id = ChatRow.Room(room).id
        update { it.copy(editing = it.editing + id, notice = null) }
        try {
            val result = client.call(call, validate(client))
            if (wire !== client) return false
            accept(result)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (wire !== client) return false
            update { it.copy(notice = (error as? BotRoomFailure)?.message ?: "Hermes did not save this change.") }
            return false
        } finally {
            if (wire === client) update { it.copy(editing = it.editing - id) }
        }
    }

    /** Capabilities are re-read on every open/refresh, never inferred from a mutating probe. */
    private suspend fun refreshRooms(client: BotTransport) {
        try {
            val value = client.call(HermesCall.GroupsCapabilities)
            if (wire !== client || !currentCoroutineContext().isActive) return
            val capabilities = BotRoomCapabilities.from(value)
            update { it.copy(roomCapabilities = capabilities) }
            if (!capabilities.enabled) {
                update { it.copy(rooms = emptyList(), hasRoomList = true) }
                return
            }
            val found = ArrayList<BotGroupRoom>()
            var offset = 0
            while (true) {
                val page = client.call(HermesCall.GroupsList(offset))
                if (wire !== client || !currentCoroutineContext().isActive) return
                val rows = page["rooms"].list ?: throw BotFailure.Unsupported
                found += rows.mapNotNull(BotGroupRoom::fromJsonOrNull).filter { !it.disbanded }
                val next = page["next_offset"].integer ?: break
                if (next <= offset) throw BotFailure.Unsupported
                offset = next
            }
            val ids = HashSet<String>()
            val rooms = found.filter { ids.add(it.id) }
            val pruned = BotRoomOrganizeStore.Flags(
                pinned = current.roomFlags.pinned.intersect(ids),
                hidden = current.roomFlags.hidden.intersect(ids),
            )
            val flagsChanged = pruned != current.roomFlags
            update { it.copy(rooms = rooms, hasRoomList = true, roomFlags = pruned) }
            if (flagsChanged) persistRoomFlags()
            current.connection?.let { connection -> runCatching { roomHistory?.retainRooms(ids, server, connection.id) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (wire !== client || !currentCoroutineContext().isActive) return
            update { it.copy(rooms = emptyList(), hasRoomList = false, roomCapabilities = BotRoomCapabilities.NONE) }
            // Method absence is the expected gate on older Hermes hosts.
            if (error is BotRoomFailure && error.code != -32601) update { it.copy(notice = error.message) }
        }
    }

    private suspend fun refreshAvatars(client: BotTransport) {
        val connection = current.connection ?: return
        avatarStore.refresh(current.profiles, connection.id, client) {
            if (wire === client) update { it.copy(avatars = avatarStore.images(connection.id)) }
        }
    }

    // MARK: Search

    /**
     * Searches each roster bot's Bot Chat on the host for [query] (#1146), Profile by Profile.
     * Only the roster's current Bot Chat (root or tip) counts. Empty while the inbox is not live.
     * A reply that lands after the inbox closed or reconnected throws [BotFailure.Stale].
     */
    suspend fun searchBotChats(query: String): List<BotChatHit> {
        val client = wire ?: return emptyList()
        if (current.link != Link.LIVE || query.isEmpty()) return emptyList()
        val hits = ArrayList<BotChatHit>()
        for (profile in current.profiles) {
            val results = client.searchSessions(query, profile.id, HermesREST.SESSION_SEARCH_MAXIMUM)
            if (wire !== client) throw BotFailure.Stale
            if (!currentCoroutineContext().isActive) throw CancellationException()
            val currentIDs = setOfNotNull(profile.canonicalID, profile.canonicalTipID)
            val match = results.firstOrNull { it.row.isBotChat && (it.row.id in currentIDs || it.row.identity in currentIDs) } ?: continue
            hits.add(BotChatHit(profile.id, match.snippet, match.role?.let { it == "user" }))
        }
        return hits
    }

    // MARK: Drops and retries

    /**
     * A lost socket or a failed read is retried quietly, with growing delays, for as long as the
     * inbox stays open; the roster stays on screen meanwhile. Only a refusal the user has to act
     * on shows a message and the Reconnect button (Update sign-in for a rejected password). An
     * empty inbox shows [State.routeAdvice] if the host stays unreachable.
     */
    private fun drop(client: BotTransport, error: Throwable) {
        reloadJob?.cancel(); reloadJob = null; reloadWanted = false
        statusPollJob?.cancel(); statusPollJob = null
        setLiveStatuses(emptyMap())
        scope.launch { client.close() }
        wire = null
        update { it.copy(link = Link.DISCONNECTED, editing = emptySet()) }
        val address = current.connection?.address
        if (!BotConnectionAdvice.isRetryable(error)) {
            routeFailures = 0
            update {
                it.copy(
                    routeAdvice = null,
                    errorMessage = address?.let { url -> BotConnectionAdvice.message(error, url) } ?: (error as? BotFailure ?: BotFailure.Transport).message,
                    needsSignIn = error == BotFailure.Rejected(401),
                )
            }
            return
        }
        if (isRouteFailure(error)) {
            routeFailures += 1
        } else {
            routeFailures = 0
            update { it.copy(routeAdvice = null) }
        }
        if (routeFailures >= ROUTE_FAILURES_BEFORE_ADVICE && current.profiles.isEmpty() && address != null) {
            update { it.copy(routeAdvice = BotConnectionAdvice.message(error, address)) }
        }
        val delayMillis = reconnectDelaysMillis[minOf(reconnectAttempts, reconnectDelaysMillis.size - 1)]
        reconnectAttempts += 1
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            sleep(delayMillis)
            if (!isActive) return@launch
            reconnectJob = null
            open()
        }
    }

    private fun persistSeen() {
        val connection = current.connection ?: return
        unread.save(current.seen, connection.id)
    }

    private fun persistRoomFlags() {
        val connection = current.connection ?: return
        roomStore.save(current.roomFlags, connection.id)
    }

    companion object {
        private const val ROUTE_FAILURES_BEFORE_ADVICE = 3

        /** The host could not be reached at all: no answer, or a proxy or Cloudflare answering for it. */
        fun isRouteFailure(error: Throwable): Boolean {
            if (error is IOException || error is BotFailure.Network) return true
            val code = (error as? BotFailure.Rejected)?.code ?: return false
            return code in 502..504 || code in 520..530
        }

        /** Placed ids keep their position; unplaced sections follow A–Z by name (ties by id). */
        fun ordered(sections: List<SectionName>, placed: List<String>): List<SectionName> {
            val byID = LinkedHashMap<String, SectionName>()
            for (section in sections) byID.putIfAbsent(section.id, section)
            val used = HashSet<String>()
            val front = placed.mapNotNull { id -> if (used.add(id)) byID[id] else null }
            val rest = sections.filter { it.id !in used }.sortedWith(
                compareBy<SectionName, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }.thenBy { it.id },
            )
            return front + rest
        }

        /** Desktop's section id format: `sec-<epoch ms, base 36>-<5 base-36 chars>`. */
        fun newSectionID(nowMillis: Long = System.currentTimeMillis(), random: java.util.Random = java.util.Random()): String {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
            val suffix = (0 until 5).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            return "sec-" + java.lang.Long.toString(nowMillis, 36) + "-" + suffix
        }
    }
}
