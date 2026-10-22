package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.model.hermes.HermesSessionRow

/**
 * Port of `HermesSessionPages` (`Networking/Hermes/HermesSessionPage.swift`): a Hermes
 * Profile's list across the pages read so far (#1046). Every page repeats each pinned row its
 * own rows missed, archived ones included, and rows shift between pages as sessions move, so a
 * row is kept once, by identity, and an archived one never. The Archived screen's pages
 * (#1048) keep only archived rows, and leave a room's session out (#1146).
 *
 * A mutable accumulator, like the Swift struct's `mutating` methods; copy it with [copy] to
 * keep a snapshot.
 */
class HermesSessionPages(
    /** True for the Archived screen's pages. */
    val archived: Boolean = false,
) {
    private val _rows = ArrayList<HermesSessionRow>()
    val rows: List<HermesSessionRow> get() = _rows

    /** Where the next page starts. A row that leaves moves back one; a back-filled pinned row only re-reads a row. */
    var nextOffset: Int = 0
        private set

    /** False once a page shows the list's end. */
    var hasMore: Boolean = true
        private set

    private val identities = HashSet<String>()

    var total: Int? = null
        private set

    val totalCount: Int get() = total ?: _rows.size

    /**
     * Adds the page read at [nextOffset]. One shorter than a full page ends the list. The
     * back-filled pinned rows can't be told from the page's own, so a page at least that long
     * reads on, unless it brought no new row.
     */
    fun append(page: HermesSessionPage) {
        total = page.total
        var added = 0
        for (row in page.rows) {
            if ((row.archived == true) != archived || !identities.add(row.identity)) continue
            // Still new to the list, so a page of rooms alone does not end it.
            added += 1
            if (!row.isRoomSession) _rows += row
        }
        nextOffset += HermesREST.SESSION_PAGE_SIZE
        hasMore = page.count >= HermesREST.SESSION_PAGE_SIZE && added > 0
    }

    /** The row this phone changed, opened and changed by [id] (#1048). */
    fun row(id: String): HermesSessionRow? = _rows.firstOrNull { it.id == id }

    /**
     * Shows a change this phone wrote before the next read does: a pin or title in place, and
     * an archive or restore as the row leaving these pages. Returns the row as it was and
     * where it stood, so a refused write can put it back.
     */
    fun apply(change: HermesSessionChange, id: String): Pair<HermesSessionRow, Int>? {
        val index = _rows.indexOfFirst { it.id == id }
        if (index < 0) return null
        val before = _rows[index]
        when (change) {
            is HermesSessionChange.Pinned -> _rows[index] = before.copy(pinned = change.flag)
            is HermesSessionChange.Title -> _rows[index] = before.copy(title = change.title)
            is HermesSessionChange.Archived -> if (change.flag != archived) remove(id)
            is HermesSessionChange.Unread -> Unit
        }
        return before to index
    }

    /** Drops the row with [id], as a delete the host confirmed does. */
    fun remove(id: String) {
        val index = _rows.indexOfFirst { it.id == id }
        if (index < 0) return
        identities.remove(_rows.removeAt(index).identity)
        total?.let { total = maxOf(it - 1, 0) }
        nextOffset = maxOf(nextOffset - 1, 0)
    }

    /** Puts [row] back as it was, where it stood: in place of the row with its identity, else at [index]. */
    fun restore(row: HermesSessionRow, index: Int) {
        val current = _rows.indexOfFirst { it.identity == row.identity }
        if (current >= 0) {
            _rows[current] = row
            return
        }
        _rows.add(minOf(index, _rows.size), row)
        identities.add(row.identity)
        total?.let { total = it + 1 }
        nextOffset += 1
    }

    fun copy(): HermesSessionPages = HermesSessionPages(archived).also { copy ->
        copy._rows.addAll(_rows)
        copy.nextOffset = nextOffset
        copy.hasMore = hasMore
        copy.identities.addAll(identities)
        copy.total = total
    }
}

/**
 * Port of `HermesProfilePages`: the Sessions list's pages, by Profile (#709): one Profile's,
 * or every Profile's for "All Profiles". The host lists one Profile at a time, so a merged
 * list holds back each row older than the oldest row some Profile with more pages has read.
 */
class HermesProfilePages {
    private val pages = HashMap<String, HermesSessionPages>()

    /** The pages read of [profile]'s list; none yet when it was never read. */
    operator fun get(profile: String): HermesSessionPages = pages.getOrPut(profile) { HermesSessionPages() }

    operator fun set(profile: String, value: HermesSessionPages) {
        pages[profile] = value
    }

    val profiles: Set<String> get() = pages.keys

    /** More of some Profile's sessions wait on the host. */
    val hasMore: Boolean get() = pages.values.any { it.hasMore }

    val totalCount: Int get() = pages.values.sumOf { it.totalCount }

    /** The rows to show, each with the Profile whose list holds it, in each list's order. */
    val rows: List<Pair<String, HermesSessionRow>>
        get() {
            val frontier = if (pages.size > 1) frontier else null
            return pages.keys.sorted().flatMap { profile ->
                pages.getValue(profile).rows.mapNotNull { row ->
                    if (frontier != null && row.pinned != true && recency(row) < frontier) null else profile to row
                }
            }
        }

    /** The Profile whose list holds the row [id]. */
    fun profile(holding: String): String? = pages.entries.firstOrNull { it.value.row(holding) != null }?.key

    /** Shows a change this phone wrote in the list that holds [id], as [HermesSessionPages.apply]. */
    fun apply(change: HermesSessionChange, id: String): Triple<String, HermesSessionRow, Int>? {
        val profile = profile(id) ?: return null
        val (row, index) = pages[profile]?.apply(change, id) ?: return null
        return Triple(profile, row, index)
    }

    fun remove(id: String) {
        val profile = profile(id) ?: return
        pages[profile]?.remove(id)
    }

    /** The newest of the oldest unpinned rows each Profile with more pages has read; null when none has more. */
    private val frontier: Double?
        get() = pages.values.filter { it.hasMore }
            .mapNotNull { pages -> pages.rows.filter { it.pinned != true }.minOfOrNull { recency(it) } }
            .maxOrNull()

    private fun recency(row: HermesSessionRow): Double = row.lastActive ?: row.startedAt ?: 0.0
}

/**
 * Port of `BotLiveStatus` (`Features/Bots/BotConnection.swift`): a bot's live turn state from
 * `session.active_list`. Only what the inbox shows: any status this build does not know reads
 * as no status at all.
 */
enum class BotLiveStatus {
    /** A turn is running (`working`, `starting`, `streaming`). */
    WORKING,

    /** The runtime has an open approval, question or other request for the user. */
    WAITING,
    ;

    companion object {
        fun fromWire(wire: String?): BotLiveStatus? = when (wire) {
            "waiting" -> WAITING
            "working", "starting", "streaming" -> WORKING
            else -> null
        }
    }
}

/** A Hermes list row's attention state (`SessionRowAttentionState`): working, or waiting for input. */
enum class HermesSessionRowState { WORKING, INPUT }

/**
 * Port of `SessionRowAttentionState.hermesStates(_:)`: a Hermes list's row states from one
 * `session.active_list` reply (#1046), by the stored session each runtime runs (`session_key`).
 * `waiting` reads as Input; `starting`, `working` and `streaming` as Working; idle, `resuming`
 * and unknown states as nothing. Of several runtimes on one session, Input wins.
 */
object HermesSessionRowStates {
    fun from(items: List<BotJSON>): Map<String, HermesSessionRowState> {
        val states = LinkedHashMap<String, HermesSessionRowState>()
        for (item in items) {
            val key = item["session_key"].text?.takeIf { it.isNotEmpty() } ?: continue
            val status = BotLiveStatus.fromWire(item["status"].text) ?: continue
            if (status == BotLiveStatus.WAITING || states[key] == null) {
                states[key] = if (status == BotLiveStatus.WAITING) HermesSessionRowState.INPUT else HermesSessionRowState.WORKING
            }
        }
        return states
    }
}
