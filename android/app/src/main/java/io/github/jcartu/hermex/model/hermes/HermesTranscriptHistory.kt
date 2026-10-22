package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.HermesREST
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Port of `HermesTranscriptHistory` (`Features/Chat/HermesTranscriptHistory.swift`): a Hermes
 * session's settled history as its REST transcript pages bring it (#1047): display rows in
 * the host's order, the newest rows first, older pages put in front. A row's `id` is the
 * host's `messages.id`. Ids do not follow display order (a compaction re-inserts the
 * session's first rows under new ids), so pages join by position and repeat rows drop by id.
 * `offset` counts display rows back from the newest, so rows the host saved since the newest
 * rows were taken shift every older page: the chat takes the newest rows again, or counts
 * them while a turn runs, before it pages back.
 *
 * Immutable: every change returns the next history.
 */
class HermesTranscriptHistory private constructor(
    val rows: List<BotJSON>,
    /** Earlier rows remain on the host: the last page read was a full one. */
    val hasOlder: Boolean,
    private val ids: Set<Int>,
    /** Rows the host saved after the newest row held, as last counted: a running turn's. */
    private val newer: Int,
    /** A full older page added nothing: the next page waits until the newest rows are read again. */
    val needsRecount: Boolean,
    /** What the last newest read covered, for the offline cache (#1054); null until one succeeds. */
    val newestCoverage: HermesNewestCoverage?,
) {
    constructor() : this(emptyList(), false, emptySet(), 0, false, null)

    /** Where the next older page starts: the display rows held and the newer ones, counted from the newest. */
    val nextOffset: Int get() = rows.size + newer

    /** Whether newest rows read back from offset 0 reach the rows held: they share a row, or none is held. */
    fun reaches(rows: List<BotJSON>): Boolean = ids.isEmpty() || rows.any { id(it)?.let(ids::contains) == true }

    fun holds(id: Int): Boolean = id in ids

    /**
     * Takes the newest rows: pages read back from offset 0 until they reached the rows held,
     * oldest first. They replace the rows held from the first row they share, which also
     * drops rows the host stopped showing there, and keep the older ones. When they reached
     * the first row, or share none, the history starts again from them.
     */
    fun mergingNewest(fresh: List<BotJSON>, reachedStart: Boolean): HermesTranscriptHistory {
        val seen = HashSet<Int>()
        val unique = fresh.filter { id(it)?.let(seen::add) == true }
        val freshIDs = unique.mapNotNull(::id)
        val coverage = when {
            reachedStart -> HermesNewestCoverage.All
            freshIDs.isEmpty() -> null
            else -> HermesNewestCoverage.From(freshIDs)
        }
        val shared = if (reachedStart) -1 else rows.indexOfFirst { id(it)?.let(seen::contains) == true }
        return if (shared >= 0) {
            replacing(rows.subList(0, shared).filter { id(it)?.let(seen::contains) == false } + unique, hasOlder, coverage)
        } else {
            replacing(unique, !reachedStart, coverage)
        }
    }

    /**
     * Counts the rows read back from offset 0 after the newest one held, without taking them.
     * Null when they hold none, so the count is unknown.
     */
    fun countingNewer(fresh: List<BotJSON>): HermesTranscriptHistory? {
        val seen = HashSet<Int>()
        val unique = fresh.filter { id(it)?.let(seen::add) == true }
        val last = unique.indexOfLast { id(it)?.let(ids::contains) == true }
        if (last < 0) return null
        return HermesTranscriptHistory(rows, hasOlder, ids, unique.size - 1 - last, false, newestCoverage)
    }

    /** The page read at [nextOffset] put in front, and whether it added any row. */
    data class Prepended(val history: HermesTranscriptHistory, val added: Boolean)

    /**
     * Puts the page read at [nextOffset] in front: its rows before the first row already held,
     * since a page is oldest first and rows saved meanwhile push held rows into it. A short
     * page reached the first row. A full one that added nothing met the rows held, so more
     * rows were saved than counted ([needsRecount]).
     */
    fun prependingOlder(page: List<BotJSON>): Prepended {
        val seen = HashSet(ids)
        val older = page.takeWhile { id(it)?.let(ids::contains) != true }
            .filter { row -> id(row)?.let(seen::add) == true }
        val hasOlder = page.size >= HermesREST.TRANSCRIPT_PAGE_SIZE
        val next = HermesTranscriptHistory(
            rows = older + rows, hasOlder = hasOlder, ids = (older + rows).mapNotNull(::id).toSet(),
            newer = newer, needsRecount = hasOlder && older.isEmpty(), newestCoverage = newestCoverage,
        )
        return Prepended(next, older.isNotEmpty())
    }

    /**
     * Drops row [before] and every row after it, as the host's rewind did (#1049). The rows the
     * host saved since are unknown, so the next older page waits for a newest read.
     */
    fun cutting(before: Int): HermesTranscriptHistory {
        val index = rows.indexOfFirst { id(it) == before }
        if (index < 0) return this
        val kept = rows.subList(0, index)
        return HermesTranscriptHistory(kept, hasOlder, kept.mapNotNull(::id).toSet(), newer, true, newestCoverage)
    }

    private fun replacing(rows: List<BotJSON>, hasOlder: Boolean, coverage: HermesNewestCoverage?): HermesTranscriptHistory =
        HermesTranscriptHistory(rows, hasOlder, rows.mapNotNull(::id).toSet(), 0, false, coverage)

    override fun equals(other: Any?): Boolean =
        other is HermesTranscriptHistory && other.rows == rows && other.hasOlder == hasOlder &&
            other.newer == newer && other.needsRecount == needsRecount && other.newestCoverage == newestCoverage

    override fun hashCode(): Int = rows.hashCode() * 31 + hasOlder.hashCode()

    companion object {
        fun id(row: BotJSON): Int? = row["id"].integer
    }
}

/**
 * Port of `HermesNewestCoverage`: the part of a Hermes transcript a newest read covered
 * (#1054): every row once it reached the first, else its rows, oldest first, and every row
 * the host shows after them.
 */
sealed class HermesNewestCoverage {
    object All : HermesNewestCoverage() {
        override fun toString(): String = "All"
    }

    data class From(val rowIDs: List<Int>) : HermesNewestCoverage()
}

/**
 * Port of `HermesBranchCount`: Fork From Here's `count` for `session.branch` (#1051): the
 * rows from the session's first one through the chosen one that are user or assistant rows
 * whose content has text (`_visible_branch_history`). Tool rows never count.
 */
object HermesBranchCount {
    /** The count through row [rowID] of [rows]; null when no such row is there, or it is one the host never copies. */
    fun count(through: Int, rows: List<BotJSON>): Int? {
        var count = 0
        for (row in rows) {
            val counts = copies(row)
            if (counts) count += 1
            if (HermesTranscriptHistory.id(row) == through) return if (counts) count else null
        }
        return null
    }

    private fun copies(row: BotJSON): Boolean {
        val role = row["role"].text ?: return false
        if (role != "user" && role != "assistant") return false
        return hasText(row["content"])
    }

    /**
     * Whether `content` has text as the host reads it (`_coerce_message_text`): a string, a
     * part's text, or any other typed part, such as an image, which reads as a mark.
     */
    private fun hasText(content: BotJSON): Boolean {
        val element = content.element
        return when {
            element is JsonNull -> false
            element is JsonPrimitive && element.isString -> isText(element.content)
            element is JsonPrimitive -> true
            element is JsonArray -> content.list.orEmpty().any { part ->
                val text = part.text ?: part["text"].text
                if (text != null) isText(text) else part["type"].text?.let { objectHasText(part, it) } ?: false
            }
            element is JsonObject -> objectHasText(content, content["type"].text)
            else -> false
        }
    }

    private fun objectHasText(obj: BotJSON, kind: String?): Boolean {
        if (kind == null) {
            val text = obj.fields?.get("text") ?: return true
            return isText(text.text)
        }
        return kind !in setOf("text", "input_text", "output_text") || isText(obj["text"].text ?: obj["content"].text)
    }

    private fun isText(text: String?): Boolean = text?.any { !it.isWhitespace() } == true
}
