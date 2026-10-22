package io.github.jcartu.hermex.ui.feature.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTokenizerTest {
    @Test
    fun `Kotlin tokens keywords escaped strings comments and numbers`() {
        val document = SourceTokenizer.tokenize(
            "val answer = 0x2A\nval text = \"say \\\"hi\\\"\" // 7 stays a comment",
            "Main.kt",
        )
        assertToken(document, "val", SourceTokenKind.KEYWORD)
        assertToken(document, "0x2A", SourceTokenKind.NUMBER)
        assertToken(document, "\"say \\\"hi\\\"\"", SourceTokenKind.STRING)
        assertToken(document, "// 7 stays a comment", SourceTokenKind.COMMENT)
    }

    @Test
    fun `Swift multiline strings and comments keep line state`() {
        val document = SourceTokenizer.tokenize(
            "let value = \"\"\"\nhello 12\n\"\"\"\n/* note\nstill note */ return 4",
            "Thing.swift",
        )
        assertToken(document, "let", SourceTokenKind.KEYWORD)
        assertToken(document, "hello 12", SourceTokenKind.STRING)
        assertToken(document, "still note */", SourceTokenKind.COMMENT)
        assertToken(document, "return", SourceTokenKind.KEYWORD)
        assertToken(document, "4", SourceTokenKind.NUMBER)
    }

    @Test
    fun `Python and JavaScript language comments do not leak tokens`() {
        val python = SourceTokenizer.tokenize("def f():\n    value = 'it\\'s' # number 99", "a.py")
        assertToken(python, "def", SourceTokenKind.KEYWORD)
        assertToken(python, "'it\\'s'", SourceTokenKind.STRING)
        assertToken(python, "# number 99", SourceTokenKind.COMMENT)

        val javascript = SourceTokenizer.tokenize("const n = 1.5e+2; const s = `x ${'$'}{n}`; // done", "a.js")
        assertToken(javascript, "const", SourceTokenKind.KEYWORD)
        assertToken(javascript, "1.5e+2", SourceTokenKind.NUMBER)
        assertToken(javascript, "`x ${'$'}{n}`", SourceTokenKind.STRING)
        assertToken(javascript, "// done", SourceTokenKind.COMMENT)
    }

    @Test
    fun `JSON and Markdown receive structural tokens`() {
        val json = SourceTokenizer.tokenize("{\"count\": -12, \"ok\": true}", "data.json")
        assertToken(json, "\"count\"", SourceTokenKind.STRING)
        assertToken(json, "-12", SourceTokenKind.NUMBER)
        assertToken(json, "true", SourceTokenKind.KEYWORD)

        val markdown = SourceTokenizer.tokenize("# Build 12\n`quoted`\n<!-- hidden 9 -->", "README.md")
        assertToken(markdown, "#", SourceTokenKind.MARKUP)
        assertToken(markdown, "12", SourceTokenKind.NUMBER)
        assertToken(markdown, "`quoted`", SourceTokenKind.MARKUP)
        assertToken(markdown, "<!-- hidden 9 -->", SourceTokenKind.COMMENT)
    }

    @Test
    fun `unknown extension remains plain text`() {
        val document = SourceTokenizer.tokenize("keyword 12 // text", "blob.unknown")
        assertEquals(null, document.language)
        assertTrue(document.lines.single().tokens.isEmpty())
    }

    private fun assertToken(document: SourceDocument, expectedText: String, kind: SourceTokenKind) {
        val matches = document.lines.flatMap { line ->
            line.tokens.map { token -> line.text.substring(token.start, token.endExclusive) to token.kind }
        }
        assertTrue("Expected $kind token '$expectedText' in $matches", expectedText to kind in matches)
    }
}
