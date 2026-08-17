package io.github.jcartu.hermex.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Filename derivation contract from `APIClient+SessionExport.swift`:
 * Content-Disposition wins (quoted or bare), then a sanitized title stem,
 * then `hermes-<id>.<ext>`; hostile header values can never escape the temp
 * directory or produce an unusable name.
 */
class SessionExportFileTest {

    private fun filename(
        contentDisposition: String? = null,
        fallbackTitle: String? = null,
        sessionId: String = "abc",
        format: SessionExportFormat = SessionExportFormat.html,
    ) = SessionExportFile.filename(contentDisposition, fallbackTitle, sessionId, format)

    @Test
    fun quotedContentDispositionFilenameWins() {
        assertEquals(
            "hermes-abc.html",
            filename(contentDisposition = """attachment; filename="hermes-abc.html"""", fallbackTitle = "Title"),
        )
    }

    @Test
    fun bareContentDispositionFilenameWins() {
        assertEquals("export.json", filename(contentDisposition = "attachment; filename=export.json"))
    }

    @Test
    fun filenameKeyIsCaseInsensitiveAndWhitespaceTolerant() {
        assertEquals("a.html", filename(contentDisposition = "attachment;  FILENAME = \"a.html\" "))
    }

    @Test
    fun pathComponentsAreStrippedFromHeaderFilename() {
        assertEquals("evil.html", filename(contentDisposition = """attachment; filename="../../evil.html""""))
        assertEquals("evil.html", filename(contentDisposition = """attachment; filename="..\..\evil.html""""))
    }

    @Test
    fun unusableHeaderFilenameFallsThrough() {
        assertEquals("hermes-abc.html", filename(contentDisposition = """attachment; filename="..""""))
        assertEquals("hermes-abc.html", filename(contentDisposition = """attachment; filename=""""))
        assertEquals("hermes-abc.html", filename(contentDisposition = "inline"))
    }

    @Test
    fun titleStemIsSanitizedAndCollapsed() {
        assertEquals("a b.html", filename(fallbackTitle = "  a  /  b  "))
        assertEquals("plan review.json", filename(fallbackTitle = "plan:\treview", format = SessionExportFormat.json))
    }

    @Test
    fun titleStemIsTruncatedTo80Characters() {
        val longTitle = "x".repeat(200)
        assertEquals("${"x".repeat(80)}.html", filename(fallbackTitle = longTitle))
    }

    @Test
    fun emptyTitleFallsBackToSessionId() {
        assertEquals("hermes-abc.html", filename(fallbackTitle = "  /:  "))
    }

    @Test
    fun unusableSessionIdFallsBackToSessionStem() {
        assertEquals("hermes-session.json", filename(sessionId = "///", format = SessionExportFormat.json))
    }

    @Test
    fun exportFileEqualityComparesContent() {
        val a = SessionExportFile(byteArrayOf(1, 2), "a.html")
        val b = SessionExportFile(byteArrayOf(1, 2), "a.html")
        val c = SessionExportFile(byteArrayOf(9), "a.html")
        assertEquals(a, b)
        org.junit.Assert.assertNotEquals(a, c)
    }
}
