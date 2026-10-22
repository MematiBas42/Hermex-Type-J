package io.github.jcartu.hermex.ui.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerUpgradesTest {

    @Test
    fun `token serialization round trips path with spaces and skill plus text`() {
        val tokens = listOf(
            ComposerToken.Text("Review "),
            ComposerToken.PathChip("docs/My File.md"),
            ComposerToken.Text(" with "),
            ComposerToken.SkillChip("Code Review"),
            ComposerToken.Text(" please"),
        )

        val serverText = tokens.toServerText()
        assertEquals("Review @docs/My File.md with /code-review please", serverText)
        assertEquals(
            tokens,
            parseComposerTokens(
                text = serverText,
                knownPaths = setOf("docs/My File.md"),
                knownSkills = mapOf("code-review" to "Code Review"),
            ),
        )
    }

    @Test
    fun `token parser leaves built in commands as text`() {
        assertEquals(
            listOf(ComposerToken.Text("/goal status")),
            parseComposerTokens("/goal status"),
        )
    }

    @Test
    fun `ranking orders prefix before substring before fuzzy`() {
        data class Candidate(val id: String, val name: String)
        val candidates = listOf(
            Candidate("fuzzy", "a-long-phrase"),
            Candidate("substring", "zzalpzz"),
            Candidate("prefix", "alpha"),
        )

        val ranked = SlashCommandRanker.rank(candidates, query = "alp") {
            SlashRankableFields(name = it.name)
        }

        assertEquals(listOf("prefix", "substring", "fuzzy"), ranked.map(Candidate::id))
    }

    @Test
    fun `ranking preserves input order for true ties`() {
        data class Candidate(val id: Int)
        val candidates = listOf(Candidate(3), Candidate(1), Candidate(2))

        val ranked = SlashCommandRanker.rank(candidates, query = "same") {
            SlashRankableFields(name = "same", label = "same")
        }

        assertEquals(listOf(3, 1, 2), ranked.map(Candidate::id))
    }

    @Test
    fun `slash trigger is detected at caret only at a word start`() {
        val leading = detectComposerCaretTrigger("/mod later", selectionStart = 4)
        assertEquals(ComposerTriggerKind.SLASH, leading?.kind)
        assertEquals("/mod", leading?.text)
        assertTrue(leading?.startsDraft == true)

        val middle = detectComposerCaretTrigger("please /ski now", selectionStart = 11)
        assertEquals(ComposerTriggerKind.SLASH, middle?.kind)
        assertEquals("/ski", middle?.text)
        assertFalse(middle?.startsDraft ?: true)

        assertNull(detectComposerCaretTrigger("path/to/file", selectionStart = 4))
        assertNull(detectComposerCaretTrigger("/skill", selectionStart = 1, selectionEnd = 3))
    }

    @Test
    fun `file trigger rejects email addresses and replaces only caret run`() {
        assertNull(detectComposerCaretTrigger("a@b.com", selectionStart = 7))

        val trigger = detectComposerCaretTrigger("see @sr tomorrow", selectionStart = 7)
        assertEquals(ComposerTriggerKind.FILE, trigger?.kind)
        assertEquals("@sr", trigger?.text)
        assertEquals(
            ComposerCompletion("see @src/Main.kt tomorrow", caret = 17),
            trigger?.applying("@src/Main.kt ", "see @sr tomorrow"),
        )
    }

    @Test
    fun `send mode menu exists only for a running turn with payload`() {
        assertTrue(composerSendModeChoices(isRunActive = false, hasPayload = true).isEmpty())
        assertTrue(composerSendModeChoices(isRunActive = true, hasPayload = false).isEmpty())
        assertEquals(
            listOf(
                ComposerSendModeChoice.QUEUE,
                ComposerSendModeChoice.STEER,
                ComposerSendModeChoice.STOP_AND_SEND,
            ),
            composerSendModeChoices(isRunActive = true, hasPayload = true),
        )
    }

    @Test
    fun `last message recall is gated by an exactly empty composer`() {
        assertTrue(canRecallLastUserMessage(""))
        assertFalse(canRecallLastUserMessage(" "))
        assertFalse(canRecallLastUserMessage("draft"))
    }

    @Test
    fun `speech language candidates try other user languages before fallback`() {
        assertEquals(
            listOf("en-PK", "ur-PK", "de-DE", "fr-FR", "en-US"),
            composerSpeechLanguageCandidates(
                currentLanguageTag = "en_PK",
                preferredLanguageTags = listOf("en-PK", "ur-PK", "de-DE", "fr-FR", "it-IT"),
            ),
        )
    }
}
