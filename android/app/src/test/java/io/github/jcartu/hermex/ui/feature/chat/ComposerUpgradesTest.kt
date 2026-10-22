package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
    fun `parsing preserves hand typed sources that are not canonical skill slugs`() {
        val raw = "Compare /My_Skill with /code-review and @@private.txt"
        assertEquals(raw, parseComposerTokens(raw).toServerText())
        assertEquals("@@private.txt", listOf(ComposerToken.PathChip("@private.txt")).toServerText())
    }

    @Test
    fun `chip transformation preserves every caret and IME source offset`() {
        val tokens = listOf(
            ComposerToken.SkillChip("Code Review"),
            ComposerToken.Text(" @ "),
            ComposerToken.PathChip("docs/My File.md"),
        )
        val source = tokens.toServerText()
        val transformed = ComposerChipVisualTransformation(
            tokens = tokens,
            chipBackground = Color.Gray,
            pathContent = Color.White,
            skillContent = Color.Cyan,
        ).filter(AnnotatedString(source))
        assertEquals(source, transformed.text.text)
        for (offset in 0..source.length) {
            assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, transformed.offsetMapping.transformedToOriginal(offset))
        }
        assertEquals(2, transformed.text.getStringAnnotations(COMPOSER_TOKEN_ANNOTATION, 0, source.length).size)
    }

    @Test
    fun `stale workspace file completion cannot replace the newest query`() = runTest {
        val controller = ComposerFileAutocompleteController()
        val oldResult = CompletableDeferred<List<ComposerFileSuggestion>>()
        val newResult = CompletableDeferred<List<ComposerFileSuggestion>>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.search("old") { oldResult.await() }
        }
        val newJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.search("new") { newResult.await() }
        }
        val newest = ComposerFileSuggestion("new.kt", "new.kt", "", false)
        newResult.complete(listOf(newest))
        newJob.join()
        oldResult.complete(listOf(ComposerFileSuggestion("old.kt", "old.kt", "", false)))
        oldJob.join()
        assertEquals(listOf(newest), controller.matches)
        assertEquals("new", controller.answeredQuery)
        assertFalse(controller.isLoading)
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
