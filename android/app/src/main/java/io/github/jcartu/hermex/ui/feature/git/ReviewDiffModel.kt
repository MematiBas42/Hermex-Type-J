package io.github.jcartu.hermex.ui.feature.git

import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile

/** Unified-diff line kind shared with transcript edit colouring. */
enum class ReviewLineKind { ADDITION, DELETION, CONTEXT }

/** One parsed patch line with both source and destination line numbers. */
data class UnifiedDiffLine(
    val kind: ReviewLineKind,
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)

/** One unified hunk. */
data class UnifiedDiffHunk(
    val header: String,
    val displayLabel: String,
    val lines: List<UnifiedDiffLine>,
) {
    val additions: Int get() = lines.count { it.kind == ReviewLineKind.ADDITION }
    val deletions: Int get() = lines.count { it.kind == ReviewLineKind.DELETION }
}

/**
 * Port of `DiffHunk.parse` in `Features/Workspace/GitDiffView.swift`. It
 * handles ordinary `@@` hunks and headerless patch fragments from tool output.
 */
object UnifiedDiffParser {
    fun parse(raw: String): List<UnifiedDiffHunk> {
        if (raw.isEmpty()) return emptyList()
        val allLines = raw.split('\n')
        val headers = allLines.indices.filter { allLines[it].startsWith("@@") }
        if (headers.isEmpty()) {
            val groups = ArrayList<List<String>>()
            var current = ArrayList<String>()
            for (line in allLines) {
                if (line.startsWith("diff --git")) {
                    if (current.isNotEmpty()) groups += current
                    current = ArrayList()
                } else if (isPatchLine(line)) {
                    current += line
                }
            }
            if (current.isNotEmpty()) groups += current
            return groups.mapIndexed { index, lines ->
                makeHunk(
                    header = "",
                    rawLines = lines,
                    syntheticLabel = "Patch ${index + 1} of ${groups.size}",
                )
            }
        }
        return headers.mapIndexed { offset, start ->
            val end = headers.getOrNull(offset + 1) ?: allLines.size
            makeHunk(allLines[start], allLines.subList(start + 1, end), syntheticLabel = null)
        }
    }

    private fun makeHunk(
        header: String,
        rawLines: List<String>,
        syntheticLabel: String?,
    ): UnifiedDiffHunk {
        val range = parseRange(header)
        var oldLine = range.first
        var newLine = range.second
        val lines = rawLines.map { raw ->
            val marker = raw.startsWith('\\')
            val kind = when (raw.firstOrNull()) {
                '+' -> ReviewLineKind.ADDITION
                '-' -> ReviewLineKind.DELETION
                else -> ReviewLineKind.CONTEXT
            }
            val line = UnifiedDiffLine(
                kind = kind,
                content = if (marker) raw else raw.drop(1).replace("\t", "    "),
                oldLineNumber = if (marker || kind == ReviewLineKind.ADDITION) null else oldLine,
                newLineNumber = if (marker || kind == ReviewLineKind.DELETION) null else newLine,
            )
            if (!marker && kind != ReviewLineKind.ADDITION) oldLine = oldLine?.plus(1)
            if (!marker && kind != ReviewLineKind.DELETION) newLine = newLine?.plus(1)
            line
        }
        val count = range.third?.coerceAtLeast(1)
        val newStart = range.second
        val label = syntheticLabel ?: when {
            newStart == null -> header
            count == 1 -> "Line $newStart"
            else -> "Lines $newStart-${newStart + count!! - 1}"
        }
        return UnifiedDiffHunk(header, label, lines)
    }

    private fun parseRange(header: String): Triple<Int?, Int?, Int?> {
        val pieces = header.split(' ').filter(String::isNotEmpty)
        if (pieces.size < 3) return Triple(null, null, null)
        fun values(token: String): Pair<Int?, Int?> {
            val split = token.drop(1).split(',', limit = 2)
            return split.firstOrNull()?.toIntOrNull() to (split.getOrNull(1)?.toIntOrNull() ?: 1)
        }
        val old = values(pieces[1])
        val new = values(pieces[2])
        return Triple(old.first, new.first, new.second)
    }

    private fun isPatchLine(line: String): Boolean {
        if (line.startsWith("+++ b/") || line == "+++ /dev/null") return false
        if (line.startsWith("--- a/") || line == "--- /dev/null") return false
        return line.firstOrNull() in setOf('+', '-', ' ', '\\')
    }
}

/** Loading/result state for one changed file in the all-files review. */
sealed interface ReviewDiffState {
    data object Loading : ReviewDiffState
    data class Loaded(val diff: GitDiff) : ReviewDiffState
    data class Failed(val message: String) : ReviewDiffState
}

/** Every row for one changed file; Compose renders these in a lazy list. */
data class ReviewDiffFileModel(
    val file: GitFile,
    val hunks: List<UnifiedDiffHunk>,
    val notice: String?,
    val additions: Int,
    val deletions: Int,
)

/** Port of `ReviewDiffRowBuilder.rows(for:)`. */
object ReviewDiffMapper {
    fun map(file: GitFile, state: ReviewDiffState): ReviewDiffFileModel {
        val hunks: List<UnifiedDiffHunk>
        val notice: String?
        when (state) {
            ReviewDiffState.Loading -> {
                hunks = emptyList()
                notice = "Loading…"
            }
            is ReviewDiffState.Failed -> {
                hunks = emptyList()
                notice = state.message
            }
            is ReviewDiffState.Loaded -> {
                hunks = when {
                    state.diff.binary == true || state.diff.tooLarge == true -> emptyList()
                    else -> UnifiedDiffParser.parse(state.diff.diff.orEmpty())
                }
                notice = when {
                    state.diff.binary == true -> "Binary file changed"
                    state.diff.tooLarge == true -> "Diff too large to show."
                    hunks.isEmpty() -> "No Changes"
                    else -> null
                }
            }
        }
        return ReviewDiffFileModel(
            file = file,
            hunks = hunks,
            notice = notice,
            additions = file.additions ?: hunks.sumOf(UnifiedDiffHunk::additions),
            deletions = file.deletions ?: hunks.sumOf(UnifiedDiffHunk::deletions),
        )
    }
}
