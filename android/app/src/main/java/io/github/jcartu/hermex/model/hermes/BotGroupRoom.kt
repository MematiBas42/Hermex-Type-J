package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotRoomRPC

/**
 * Port of `BotRoomKey` (`Features/Bots/BotGroupRoom.swift`): room identity never borrows a
 * display name or a hidden member session id. [server] is the configured server's id.
 */
data class BotRoomKey(val server: String, val connectionID: String, val roomID: String)

/**
 * Port of `BotRoomCapabilities`: `groups.capabilities` gates room rows (bots.md § Group
 * rooms). `driver` must be true and `methods` must include `groups.list`, `groups.state` and
 * `groups.log`; missing capabilities hide rooms, including name search.
 */
data class BotRoomCapabilities(
    val enabled: Boolean,
    val authority: String?,
    val pageLimit: Int,
    val methods: Set<String>,
) {
    companion object {
        val NONE: BotRoomCapabilities = from(BotJSON.NULL)

        fun from(value: BotJSON): BotRoomCapabilities {
            val methods = value["methods"].list?.mapNotNull { it.text }?.toSet() ?: emptySet()
            return BotRoomCapabilities(
                enabled = value["driver"].flag == true && methods.containsAll(setOf("groups.list", "groups.state", "groups.log")),
                authority = value["authority_gateway_id"].text,
                pageLimit = (value["max_log_limit"].integer ?: 200).coerceIn(1, 200),
                methods = methods,
            )
        }
    }
}

/** Port of `BotGroupRoom`: one `groups.list` row. Disbanded rooms carry `disbanded_at`. */
data class BotGroupRoom(
    val id: String,
    val name: String,
    val members: List<Member>,
    /** Epoch seconds. */
    val updatedAt: Double?,
    val latestSeq: Int,
    val authority: String?,
    val epoch: Int?,
    val disbanded: Boolean,
) {
    data class Member(val id: String, val profile: String?, val displayName: String?, val handle: String?) {
        val name: String get() = displayName ?: handle ?: profile ?: id

        companion object {
            fun fromJsonOrNull(value: BotJSON): Member? {
                val id = value["member_id"].text ?: return null
                return Member(id, value["profile"].text, value["display_name"].text, value["handle"].text)
            }
        }
    }

    /** `isForeign(to:)` — true when the room belongs to another gateway than this connection's. */
    fun isForeign(authority: String?): Boolean {
        val owner = this.authority ?: return false
        if (authority == null) return false
        return owner != authority
    }

    companion object {
        fun fromJsonOrNull(value: BotJSON): BotGroupRoom? {
            val id = value["room_id"].text?.takeIf(BotRoomRPC::validID) ?: return null
            return BotGroupRoom(
                id = id,
                name = value["name"].text ?: id,
                members = value["members"].list?.mapNotNull(Member::fromJsonOrNull) ?: emptyList(),
                updatedAt = value["updated_at"].number?.takeIf { it.isFinite() },
                latestSeq = maxOf(0, value["latest_seq"].integer ?: 0),
                authority = value["authority_gateway_id"].text,
                epoch = value["authority_epoch"].integer,
                disbanded = !value["disbanded_at"].isNull,
            )
        }
    }
}
