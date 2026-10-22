package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomCapabilities
import io.github.jcartu.hermex.net.hermes.BotRoomFailure
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Port of `Features/Bots/BotRoomCreator.swift`: one sheet owns one immutable create attempt.
 * The first actual socket dispatch freezes the room id, name, member order and member payload;
 * explicit Try Again reuses those bytes and cannot create a second room.
 */
class BotRoomCreator(
    val server: String,
    val connection: BotConnection,
    val roster: List<BotProfile>,
    private val scope: CoroutineScope,
    private val store: BotInbox.ConnectionRecords,
    private val makeWire: (BotConnection) -> BotTransport,
    private val onReconciled: (List<BotGroupRoom>) -> Unit = {},
) {
    data class State(
        val selected: List<BotProfile> = emptyList(),
        val name: String = "Group Chat",
        val query: String = "",
        val busy: Boolean = false,
        val message: String? = null,
        val created: BotGroupRoom? = null,
        val attempt: HermesCall.RoomCreation? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    @Volatile private var wire: BotTransport? = null
    @Volatile private var generation: String = UUID.randomUUID().toString()
    @Volatile private var dispatched = false

    val locked: Boolean get() = current.attempt != null
    val mayContinue: Boolean get() = current.selected.size in 2..6
    val mayCreate: Boolean
        get() = !current.busy && current.created == null && mayContinue && BotRoomRPC.validName(current.name)

    val remaining: List<BotProfile>
        get() {
            val filter = current.query.trim()
            val selected = current.selected.mapTo(HashSet(), BotProfile::id)
            return roster.filter { profile ->
                profile.id !in selected && if (filter.isEmpty()) {
                    !profile.hidden
                } else {
                    profile.name.contains(filter, ignoreCase = true) || profile.id.contains(filter, ignoreCase = true)
                }
            }
        }

    val preview: BotGroupRoom
        get() = BotGroupRoom(
            id = "preview",
            name = current.name,
            members = current.selected.map { profile ->
                val member = member(profile)
                BotGroupRoom.Member(member.memberID, member.profile, member.displayName, member.handle)
            },
            updatedAt = null,
            latestSeq = 0,
            authority = null,
            epoch = null,
            disbanded = false,
        )

    fun setName(name: String) {
        if (!locked) _state.update { it.copy(name = name) }
    }

    fun setQuery(query: String) {
        if (!locked) _state.update { it.copy(query = query) }
    }

    fun select(profile: BotProfile) {
        if (locked || current.selected.size >= 6 || profile !in roster || current.selected.any { it.id == profile.id }) return
        _state.update { it.copy(selected = it.selected + profile, query = "") }
    }

    fun remove(profile: BotProfile) {
        if (locked) return
        _state.update { it.copy(selected = it.selected.filterNot { row -> row.id == profile.id }) }
    }

    suspend fun create() {
        if (!mayCreate) return
        val owner = UUID.randomUUID().toString()
        generation = owner
        val client = makeWire(connection)
        wire = client
        dispatched = false
        _state.update { it.copy(busy = true, message = null) }
        var authority: String? = null
        try {
            check(owner, client)
            client.connect()
            check(owner, client)
            val capabilities = BotRoomCapabilities.from(client.call(HermesCall.GroupsCapabilities))
            check(owner, client)
            if (!capabilities.enabled || "groups.create" !in capabilities.methods || capabilities.authority == null) {
                throw BotFailure.Unsupported
            }
            authority = capabilities.authority
            val creation = current.attempt ?: HermesCall.RoomCreation(
                roomID = UUID.randomUUID().toString().uppercase(),
                name = current.name,
                members = current.selected.map(::member),
            )
            val result = client.call(HermesCall.GroupsCreate(creation)) {
                check(owner, client)
                val saved = store.load(server)
                if (saved?.id != connection.id) throw BotFailure.Stale
                _state.update { state -> state.copy(attempt = state.attempt ?: creation) }
                dispatched = true
            }
            check(owner, client)
            val room = BotGroupRoom.fromJsonOrNull(result["room"])
            if (room == null || room.id != creation.roomID || room.disbanded || room.authority != capabilities.authority) {
                throw BotFailure.Unsupported
            }
            _state.update { it.copy(created = room) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation != owner) return
            if (error is BotRoomFailure) {
                _state.update { it.copy(message = error.message) }
                if (error.code == 4110) reconcileConflict(client, owner, authority)
            } else {
                _state.update {
                    it.copy(
                        message = if (dispatched) {
                            "Outcome unknown. Try Again checks the same group creation; it will not create a second room."
                        } else {
                            error.message ?: "Hermes could not create this group."
                        },
                    )
                }
            }
        } finally {
            client.onDisconnect = null
            runCatching { client.close() }
            if (generation == owner) {
                wire = null
                dispatched = false
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Closing/backgrounding invalidates replies and never retries a write by itself. */
    fun cancel() {
        if (current.busy && current.attempt != null) {
            _state.update {
                it.copy(
                    message = "Outcome unknown. Try Again checks the same group creation; it will not create a second room.",
                )
            }
        }
        generation = UUID.randomUUID().toString()
        val previous = wire
        wire = null
        dispatched = false
        _state.update { it.copy(busy = false) }
        scope.launch { runCatching { previous?.close() } }
    }

    private suspend fun reconcileConflict(client: BotTransport, owner: String, authority: String?) {
        try {
            val rooms = BotRoomList.read(client) { check(owner, client) }
            check(owner, client)
            onReconciled(rooms)
            val attempt = current.attempt ?: return
            val recovered = rooms.firstOrNull { room ->
                room.id == attempt.roomID && authority != null && room.authority == authority &&
                    room.members.size == attempt.members.size && room.members.zip(attempt.members).all { (actual, expected) ->
                        actual.id == expected.memberID && actual.profile == expected.profile && actual.handle == expected.handle
                    }
            }
            if (recovered != null) _state.update { it.copy(created = recovered, message = null) }
        } catch (_: Throwable) {
            // The original conflict remains visible; an incomplete list is never proof of success.
        }
    }

    private fun check(owner: String, client: BotTransport) {
        if (generation != owner || wire !== client) throw BotFailure.Stale
    }

    companion object {
        /** `profiles.list` at the compatibility pin has no separate handle. */
        fun member(profile: BotProfile): HermesCall.RoomMember = HermesCall.RoomMember(
            memberID = profile.id,
            profile = profile.id,
            handle = profile.id,
            displayName = profile.displayName,
        )
    }
}
