package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.hermes.HermesCall
import java.security.MessageDigest

internal object RoomTestFixture {
    val methods = listOf(
        "groups.capabilities", "groups.list", "groups.state", "groups.log", "groups.send",
        "groups.stop", "groups.approve", "groups.retry", "groups.create", "groups.rename", "groups.disband",
    )

    fun capabilities(authority: String = "local"): BotJSON = BotJSON.objectOf(
        "driver" to BotJSON.bool(true),
        "methods" to BotJSON.array(methods.map(BotJSON::string)),
        "authority_gateway_id" to BotJSON.string(authority),
        "max_log_limit" to BotJSON.number(200),
    )

    fun room(
        id: String = "fixture-room",
        name: String = "Comms",
        latest: Int = 0,
        authority: String = "local",
        epoch: Int = 1,
        members: List<BotJSON> = listOf(member("chief")),
    ): BotJSON = BotJSON.objectOf(
        "room_id" to BotJSON.string(id),
        "name" to BotJSON.string(name),
        "latest_seq" to BotJSON.number(latest),
        "authority_gateway_id" to BotJSON.string(authority),
        "authority_epoch" to BotJSON.number(epoch),
        "members" to BotJSON.array(members),
    )

    fun member(id: String): BotJSON = BotJSON.objectOf(
        "member_id" to BotJSON.string(id),
        "profile" to BotJSON.string(id),
        "handle" to BotJSON.string(id),
        "display_name" to BotJSON.string(id.replaceFirstChar(Char::uppercase)),
    )

    fun event(
        seq: Int,
        kind: String = "message.user",
        text: String = "Message $seq",
        threadID: String? = null,
        eventID: String? = null,
        roomID: String = "fixture-room",
    ): BotJSON = BotJSON.objectOf(
        "room_id" to BotJSON.string(roomID),
        "seq" to BotJSON.number(seq),
        "kind" to BotJSON.string(kind),
        "event_id" to (eventID?.let(BotJSON::string) ?: BotJSON.NULL),
        "actor" to BotJSON.objectOf("id" to BotJSON.string("chief"), "display_name" to BotJSON.string("Chief")),
        "payload" to BotJSON.objectOf(
            "text" to BotJSON.string(text),
            "thread_id" to (threadID?.let(BotJSON::string) ?: BotJSON.NULL),
            "member_id" to BotJSON.string("chief"),
        ),
        "created_at" to BotJSON.number(1_800_000_000.0 + seq),
    )

    fun page(events: List<BotJSON>, cursor: Int, more: Boolean = false, authority: String = "local"): BotJSON = BotJSON.objectOf(
        "events" to BotJSON.array(events),
        "cursor" to BotJSON.number(cursor),
        "has_more" to BotJSON.bool(more),
        "authority" to BotJSON.objectOf("gateway_id" to BotJSON.string(authority), "epoch" to BotJSON.number(1)),
    )

    fun status(actions: List<BotJSON> = emptyList(), running: Int = 0, stopping: Int = 0): BotJSON = BotJSON.objectOf(
        "working" to BotJSON.bool(running > 0),
        "blocked" to BotJSON.bool(actions.isNotEmpty() || stopping > 0),
        "counts" to BotJSON.objectOf(
            "queued" to BotJSON.number(0),
            "running" to BotJSON.number(running),
            "stopping" to BotJSON.number(stopping),
        ),
        "pending_actions" to BotJSON.array(actions),
    )

    val approval: BotJSON = BotJSON.objectOf(
        "kind" to BotJSON.string("approval"),
        "member_id" to BotJSON.string("chief"),
        "task_id" to BotJSON.string("task:1"),
        "execution_generation" to BotJSON.number(1),
        "request_id" to BotJSON.string("approval:1"),
        "approval" to BotJSON.objectOf(
            "command" to BotJSON.string("echo hello"),
            "choices" to BotJSON.array(listOf("once", "session", "always", "deny").map(BotJSON::string)),
        ),
    )

    fun serverEventID(clientID: String): String = "user:" + MessageDigest.getInstance("SHA-256")
        .digest(clientID.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class FakeRoomTransport : BotTransport {
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null
    var localAuthority = "local"
    var roomAuthority = "local"
    var epoch = 1
    var roomID = "fixture-room"
    var roomName = "Comms"
    var status = RoomTestFixture.status()
    val events = mutableListOf<BotJSON>()
    val writes = mutableListOf<Pair<String, Map<String, BotJSON>>>()
    var loseNextWrite = false
    var rejectNext: Throwable? = null
    var closed = 0
    private val sent = LinkedHashMap<String, BotJSON>()
    private var createdRoom: BotJSON? = null

    override suspend fun connect() = Unit
    override suspend fun close() { closed += 1 }

    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
        val params = call.params()
        val write = call.method in setOf(
            "groups.send", "groups.stop", "groups.approve", "groups.retry", "groups.create", "groups.rename", "groups.disband",
        )
        if (write) {
            validateDispatch?.invoke()
            writes += call.method to params
            rejectNext?.let { failure -> rejectNext = null; throw failure }
        }
        val result = when (call) {
            HermesCall.GroupsCapabilities -> RoomTestFixture.capabilities(localAuthority)
            is HermesCall.GroupsList -> BotJSON.objectOf(
                "rooms" to BotJSON.array(listOfNotNull(createdRoom ?: RoomTestFixture.room(roomID, roomName, events.size, roomAuthority, epoch))),
                "next_offset" to BotJSON.NULL,
            )
            is HermesCall.GroupsState -> BotJSON.objectOf(
                "room" to RoomTestFixture.room(roomID, roomName, events.size, roomAuthority, epoch),
                "driver_status" to status,
            )
            is HermesCall.GroupsLog -> {
                val rows = events.filter { (it["seq"].integer ?: 0) > call.sinceSeq }.take(call.limit)
                val cursor = rows.lastOrNull()?.get("seq")?.integer ?: call.sinceSeq
                RoomTestFixture.page(rows, cursor, events.any { (it["seq"].integer ?: 0) > cursor }, roomAuthority)
            }
            is HermesCall.GroupsSend -> {
                val event = sent.getOrPut(call.eventID) {
                    RoomTestFixture.event(
                        seq = events.size + 1,
                        text = call.text.trim(),
                        threadID = call.threadID,
                        eventID = RoomTestFixture.serverEventID(call.eventID),
                        roomID = roomID,
                    ).also(events::add)
                }
                BotJSON.objectOf(
                    "accepted" to BotJSON.bool(true),
                    "client_event_id" to BotJSON.string(call.eventID),
                    "event" to event,
                )
            }
            is HermesCall.GroupsStop -> {
                status = RoomTestFixture.status(stopping = 1)
                BotJSON.objectOf("cancelled" to BotJSON.number(1))
            }
            is HermesCall.GroupsApprove -> BotJSON.objectOf("approved" to BotJSON.bool(true))
            is HermesCall.GroupsRetry -> BotJSON.objectOf("retried" to BotJSON.bool(true))
            is HermesCall.GroupsCreate -> {
                createdRoom = RoomTestFixture.room(
                    id = call.room.roomID,
                    name = call.room.name,
                    authority = localAuthority,
                    members = call.room.members.map { member ->
                        BotJSON.objectOf(
                            "member_id" to BotJSON.string(member.memberID),
                            "profile" to BotJSON.string(member.profile),
                            "handle" to BotJSON.string(member.handle),
                            "display_name" to (member.displayName?.let(BotJSON::string) ?: BotJSON.NULL),
                        )
                    },
                )
                BotJSON.objectOf("room" to createdRoom!!)
            }
            is HermesCall.GroupsRename -> {
                roomName = call.name
                BotJSON.objectOf("room" to RoomTestFixture.room(roomID, roomName, events.size, roomAuthority, epoch))
            }
            is HermesCall.GroupsDisband -> BotJSON.objectOf(
                "tombstone" to BotJSON.objectOf("room_id" to BotJSON.string(roomID), "disbanded_at" to BotJSON.number(1.0)),
            )
            else -> error("Unexpected call ${call.method}")
        }
        if (write && loseNextWrite) {
            loseNextWrite = false
            throw io.github.jcartu.hermex.model.hermes.BotFailure.Transport
        }
        return result
    }
}
