package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotRoomRPC

/**
 * Port of `BotRoomStatus`, `BotRoomEvent`, `BotRoomThread`, and `BotRoomLog` from
 * `Features/Bots/BotGroupRoom.swift`. Unknown and invisible events still advance replay, while
 * the rendered log remains bounded by the room screen's 50-row projection.
 */
data class BotRoomStatus(
    val working: Boolean,
    val blocked: Boolean,
    val stopping: Int,
    val stoppable: Int,
    val actions: List<BotRoomAction>,
) {
    val pending: Boolean get() = actions.isNotEmpty()
    val pollIntervalMillis: Long get() = if (working || blocked || stopping > 0) 2_000L else 10_000L

    companion object {
        val EMPTY = from(BotJSON.NULL)

        fun from(value: BotJSON): BotRoomStatus = BotRoomStatus(
            working = value["working"].flag == true,
            blocked = value["blocked"].flag == true,
            stopping = maxOf(0, value["counts"]["stopping"].integer ?: 0),
            stoppable = maxOf(0, value["counts"]["queued"].integer ?: 0) +
                maxOf(0, value["counts"]["running"].integer ?: 0),
            actions = value["pending_actions"].list.orEmpty().map(BotRoomAction::from),
        )
    }
}

data class BotRoomEvent(
    val seq: Int,
    val roomID: String?,
    val eventID: String?,
    val kind: String,
    val actor: BotJSON,
    val payload: BotJSON,
    /** Epoch seconds. */
    val timestamp: Double?,
) {
    val threadID: String?
        get() = payload["thread_id"].text?.takeIf(BotRoomRPC::validID)

    val isMessage: Boolean get() = kind == "message.user" || kind == "message.member"

    val visible: Boolean
        get() = kind in VISIBLE_KINDS

    fun member(room: BotGroupRoom): BotGroupRoom.Member? {
        val memberID = payload["member_id"].text ?: actor["id"].text
        return room.members.firstOrNull { it.id == memberID }
    }

    fun sender(room: BotGroupRoom): String =
        actor["display_name"].text ?: member(room)?.name ?: actor["profile"].text ?: "Bot"

    val systemText: String
        get() = when (kind) {
            "turn.failed" -> payload["error"].text?.let { "Failed: $it" } ?: "Turn failed"
            "turn.cancelled" -> "Turn cancelled"
            "room.stop_requested" -> "Stop requested"
            "room.renamed" -> "Room renamed to ${payload["name"].text.orEmpty()}"
            else -> ""
        }

    companion object {
        private val VISIBLE_KINDS = setOf(
            "message.user",
            "message.member",
            "turn.failed",
            "turn.cancelled",
            "room.stop_requested",
            "room.renamed",
        )

        fun fromJsonOrNull(value: BotJSON): BotRoomEvent? {
            val seq = value["seq"].integer?.takeIf { it > 0 } ?: return null
            return BotRoomEvent(
                seq = seq,
                roomID = value["room_id"].text,
                eventID = value["event_id"].text,
                kind = value["kind"].text.orEmpty(),
                actor = value["actor"],
                payload = value["payload"],
                timestamp = value["created_at"].number?.takeIf { it.isFinite() },
            )
        }

        /** A rename or failure before anybody speaks does not end the new-room welcome. */
        fun hasConversation(events: Iterable<BotRoomEvent>): Boolean = events.any(BotRoomEvent::isMessage)
    }
}

/** A loaded projection, never a claim that the server's complete thread is present. */
data class BotRoomThread(
    val id: String,
    val events: List<BotRoomEvent>,
    val historyIsPartial: Boolean,
) {
    val messages: List<BotRoomEvent> get() = events.filter(BotRoomEvent::isMessage)
    val root: BotRoomEvent?
        get() = messages.firstOrNull()?.takeIf { !historyIsPartial && it.kind == "message.user" }
    val replyCount: Int get() = messages.size - if (root == null) 0 else 1
    val latest: BotRoomEvent get() = events.last()

    companion object {
        fun group(events: List<BotRoomEvent>, hasEarlier: Boolean): List<BotRoomThread> =
            events.asSequence()
                .mapNotNull { event -> event.threadID?.let { it to event } }
                .groupBy({ it.first }, { it.second })
                .map { (id, rows) -> BotRoomThread(id, rows.sortedBy(BotRoomEvent::seq), hasEarlier) }
                .sortedByDescending { it.latest.seq }
    }
}

/**
 * Pure room replay seam. An acknowledgement may arrive ahead of unread log events, so
 * [acknowledge] inserts its row without advancing [cursor].
 */
data class BotRoomLog(
    val cursor: Int = 0,
    val earlierBoundary: Int = 0,
    val events: List<BotRoomEvent> = emptyList(),
    private val seenSequences: Set<Int> = events.mapTo(HashSet(), BotRoomEvent::seq),
) {
    fun recentWindow(maximumEvents: Int = 500): BotRoomLog {
        val retained = if (events.size <= maximumEvents) events else events.takeLast(maximumEvents)
        val boundary = if (events.size > maximumEvents && retained.isNotEmpty()) {
            maxOf(earlierBoundary, retained.first().seq - 1)
        } else {
            earlierBoundary
        }
        return copy(
            events = retained,
            earlierBoundary = boundary,
            seenSequences = retained.mapTo(HashSet(), BotRoomEvent::seq),
        )
    }

    fun beginning(latest: Int): BotRoomLog {
        val start = windowStart(latest)
        return BotRoomLog(cursor = start, earlierBoundary = start)
    }

    fun loadedEarlier(start: Int): BotRoomLog = copy(earlierBoundary = start)

    fun acknowledge(event: BotJSON): BotRoomLog {
        val previousCursor = cursor
        return applyPage(BotJSON.objectOf("events" to BotJSON.array(listOf(event))))
            .copy(cursor = previousCursor)
    }

    fun applyPage(page: BotJSON): BotRoomLog {
        val seen = seenSequences.toMutableSet()
        val fresh = page["events"].list.orEmpty()
            .mapNotNull(BotRoomEvent::fromJsonOrNull)
            .filter { seen.add(it.seq) }
        val nextCursor = maxOf(cursor, page["cursor"].integer ?: 0, fresh.maxOfOrNull(BotRoomEvent::seq) ?: 0)
        val visible = fresh.filter(BotRoomEvent::visible)
        return if (visible.isEmpty()) copy(cursor = nextCursor, seenSequences = seen) else copy(
            cursor = nextCursor,
            events = (events + visible).sortedBy(BotRoomEvent::seq),
            seenSequences = seen,
        )
    }

    companion object {
        fun windowStart(sequence: Int): Int = maxOf(0, sequence - 200)
    }
}

/** The eager transcript builds only this many rows at first; older loaded rows reveal in pages. */
data class BotRoomTranscriptWindow(
    private val oldestShown: Int? = null,
    private val overviewCount: Int = PAGE_SIZE,
) {
    fun start(events: List<BotRoomEvent>, keepingSequence: Int? = null, overview: Boolean = false): Int {
        val newestPage = maxOf(0, events.size - PAGE_SIZE)
        val anchored = if (overview) {
            maxOf(0, events.size - overviewCount)
        } else {
            oldestShown?.takeIf { sequence -> events.any { it.seq == sequence } }
                ?.let { sequence -> events.indexOfFirst { it.seq >= sequence }.takeIf { it >= 0 } }
                ?: newestPage
        }
        val hit = keepingSequence?.let { sequence -> events.indexOfFirst { it.seq == sequence }.takeIf { it >= 0 } }
        return if (hit == null) anchored else minOf(anchored, hit)
    }

    fun seed(events: List<BotRoomEvent>, live: Boolean, overview: Boolean = false): BotRoomTranscriptWindow {
        if (events.isEmpty()) return BotRoomTranscriptWindow()
        if (overview || !live) return this
        if (oldestShown != null && events.any { it.seq == oldestShown }) return this
        return copy(oldestShown = events[maxOf(0, events.size - PAGE_SIZE)].seq)
    }

    fun reveal(sequence: Int, events: List<BotRoomEvent>, overview: Boolean = false): BotRoomTranscriptWindow {
        if (events.isEmpty() || events.none { it.seq == sequence }) return this
        return if (overview) {
            copy(overviewCount = events.size - start(events, sequence, overview = true))
        } else {
            copy(oldestShown = minOf(events[start(events)].seq, sequence))
        }
    }

    fun showEarlier(events: List<BotRoomEvent>, overview: Boolean = false): BotRoomTranscriptWindow? {
        val start = start(events, overview = overview)
        if (start <= 0) return null
        return if (overview) {
            copy(overviewCount = events.size - maxOf(0, start - PAGE_SIZE))
        } else {
            copy(oldestShown = events[maxOf(0, start - PAGE_SIZE)].seq)
        }
    }

    companion object { const val PAGE_SIZE = 50 }
}
