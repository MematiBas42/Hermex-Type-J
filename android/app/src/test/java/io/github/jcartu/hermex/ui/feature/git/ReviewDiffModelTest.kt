package io.github.jcartu.hermex.ui.feature.git

import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewDiffModelTest {
    @Test
    fun `unified hunk maps old and new line numbers`() {
        val diff = """
            diff --git a/src/App.kt b/src/App.kt
            --- a/src/App.kt
            +++ b/src/App.kt
            @@ -10,3 +10,4 @@ fun run() {
             keep()
            -old()
            +newOne()
            +newTwo()
             end()
        """.trimIndent()
        val hunk = UnifiedDiffParser.parse(diff).single()
        assertEquals("Lines 10-13", hunk.displayLabel)
        assertEquals(2, hunk.additions)
        assertEquals(1, hunk.deletions)

        val keep = hunk.lines[0]
        assertEquals(10, keep.oldLineNumber)
        assertEquals(10, keep.newLineNumber)
        val removed = hunk.lines[1]
        assertEquals(11, removed.oldLineNumber)
        assertNull(removed.newLineNumber)
        val firstAdded = hunk.lines[2]
        assertNull(firstAdded.oldLineNumber)
        assertEquals(11, firstAdded.newLineNumber)
        assertEquals("newOne()", firstAdded.content)
        assertEquals(13, hunk.lines.last().newLineNumber)
    }

    @Test
    fun `mapper emits notices and prefers server counts`() {
        val file = GitFile(
            id = "asset.bin",
            path = "asset.bin",
            status = "M",
            additions = 12,
            deletions = 4,
        )
        val binary = ReviewDiffMapper.map(file, ReviewDiffState.Loaded(GitDiff(binary = true)))
        assertEquals("Binary file changed", binary.notice)
        assertEquals(12, binary.additions)
        assertEquals(4, binary.deletions)

        val tooLarge = ReviewDiffMapper.map(file, ReviewDiffState.Loaded(GitDiff(tooLarge = true)))
        assertEquals("Diff too large to show.", tooLarge.notice)
    }

    @Test
    fun `headerless patches become separate synthetic hunks`() {
        val parsed = UnifiedDiffParser.parse(
            """
            diff --git a/a b/a
            -old
            +new
            diff --git a/b b/b
            -before
            +after
            """.trimIndent(),
        )
        assertEquals(listOf("Patch 1 of 2", "Patch 2 of 2"), parsed.map { it.displayLabel })
        assertEquals(listOf(1, 1), parsed.map { it.additions })
    }
}
