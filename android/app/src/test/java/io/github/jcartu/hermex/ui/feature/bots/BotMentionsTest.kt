package io.github.jcartu.hermex.ui.feature.bots

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.ui.feature.chat.ComposerChipVisualTransformation
import io.github.jcartu.hermex.ui.feature.chat.ComposerMentionSuggestion
import io.github.jcartu.hermex.ui.feature.chat.ComposerToken
import io.github.jcartu.hermex.ui.feature.chat.applyAtomicBotChipEdit
import io.github.jcartu.hermex.ui.feature.chat.parseComposerTokens
import io.github.jcartu.hermex.ui.feature.chat.toServerText
import org.junit.Assert.*
import org.junit.Test

/** Port of `HermesMobileTests/BotMentionTests.swift`: exact identification bytes and alias ownership. */
class BotMentionsTest {
    private fun bot(id: String, title: String? = null, display: String? = null): BotProfile = requireNotNull(BotProfile.fromJsonOrNull(
        BotJSON.objectOf(
            "name" to BotJSON.string(id),
            "display_name" to (display?.let(BotJSON::string) ?: BotJSON.NULL),
            "ui_meta" to BotJSON.objectOf("hermes-bots" to BotJSON.objectOf("title" to (title?.let(BotJSON::string) ?: BotJSON.NULL))),
        ),
    ))

    @Test fun `Desktop name forms reserve primary handles`() {
        assertEquals(listOf("research-buddy", "researchbuddy"), BotMentions.nameForms(" Research + Buddy! "))
        assertEquals(listOf("a_b-2"), BotMentions.nameForms("A_B-2"))
        for (value in listOf("all", "EVERYONE", "user", "default", "Hermes", "_helper", "\uD83E\uDD16")) {
            assertTrue(value, BotMentions.nameForms(value).isEmpty())
        }
        assertEquals(listOf("helper"), BotMentions.nameForms("-Helper-"))
    }

    @Test fun `friendly name and handle rank in roster order with eight-result cap`() {
        val source = BotMentions(listOf(bot("research", "Research Buddy", "Scholar"), bot("plain")) + (1..12).map { bot("r$it") }, "active")
        assertEquals(listOf("research-buddy", "plain"), source.completions("").take(2).map { it.tag })
        assertEquals(8, source.completions("").size)
        assertEquals(listOf("research"), source.completions("RESEARCH").map { it.profile.id })
        assertTrue(source.completions("buddy").isEmpty())
        assertEquals(listOf("research", "plain"), source.resolve("@researchbuddy @scholar @plain").map { it.id })
    }

    @Test fun `ambiguous forms never recover with a third owner`() {
        val source = BotMentions(listOf(bot("one", "Same Name"), bot("two", display = "Same Name"), bot("three", "Same Name")), "active")
        assertTrue(source.resolve("@same-name @samename").isEmpty())
        assertEquals(listOf("one", "two", "three"), source.completions("").map { it.tag })
        val handles = BotMentions(listOf(bot("one"), bot("two", "One")), "active")
        assertTrue(handles.resolve("@one").isEmpty())
        assertEquals(listOf("two"), handles.completions("").map { it.tag })
    }

    @Test fun `renamed default retains Hermes alias and active bot is excluded`() {
        val roster = listOf(bot("default", "Chief of Staff"), bot("dev", "Hermes"))
        val source = BotMentions(roster, "dev")
        assertEquals(listOf("default"), source.resolve("@hermes @default @chief-of-staff @chiefofstaff @dev").map { it.id })
        assertEquals(listOf("chief-of-staff"), source.completions("hermes").map { it.tag })
        assertTrue(BotMentions(roster, "default").resolve("@hermes @chief-of-staff").isEmpty())
    }

    @Test fun `code emails paths and unknown names do not annotate`() {
        val source = BotMentions(listOf(bot("docs"), bot("research")), "active")
        val ignored = "user@research.com `@research`\n```swift\n@research\n``` @unknown"
        assertEquals("", source.annotation(ignored))
        for (path in listOf("@docs/plan.md", "@docs/", "@docs.md", "@docs\\plan.md", "@docs-v2/x", "@research.swift")) {
            assertTrue(path, source.resolve(path).isEmpty())
        }
        assertEquals(listOf("docs", "research"), source.resolve("@docs, look at @research.").map { it.id })
        assertEquals(listOf("research", "docs"), source.resolve("ask @research: then (@docs) @docs!").map { it.id })
        assertEquals(listOf("research"), source.resolve(ignored + "\n@RESEARCH please @research").map { it.id })
    }

    @Test fun `annotation exact bytes and strict trailing stripping`() {
        val source = BotMentions(listOf(bot("research", "Research Buddy"), bot("default")), "active")
        val expected = BotMentions.NOTE_PREFIX + "@hermes = agent profile \"default\"; @research = agent profile \"research\" (\"Research Buddy\")" + BotMentions.NOTE_SUFFIX
        assertEquals(expected, source.annotation("@hermes @research @hermes"))
        val typed = " @research please  "
        val note = source.annotation(typed)
        assertEquals(typed, BotMentions.displayText(typed + note))
        assertEquals(typed + note + "\nmore", BotMentions.displayText(typed + note + "\nmore"))
        val malformed = note.replace("@research = agent profile", "something else")
        assertEquals(typed + malformed, BotMentions.displayText(typed + malformed))
    }

    @Test fun `caret replacement keeps emoji UTF16 and following text`() {
        val text = "\uD83E\uDD16 Ask @res about this"
        val caret = "\uD83E\uDD16 Ask @res".length
        val trigger = requireNotNull(BotMentionTrigger.detect(text, TextRange(caret)))
        val result = trigger.applying("research", text)
        assertEquals("\uD83E\uDD16 Ask @research about this", result.draft)
        assertEquals(TextRange("\uD83E\uDD16 Ask @research ".length), result.selection)
        assertNotNull(BotMentionTrigger.detect("@", TextRange(1)))
        for (invalid in listOf("mail@research", "(@research", "@research ", "@name@connection")) assertNull(BotMentionTrigger.detect(invalid, TextRange(invalid.length)))
        assertNull(BotMentionTrigger.detect("@res", TextRange(1, 2)))
        assertNull(BotMentionTrigger.detect("@res", TextRange(99)))
    }

    @Test fun `bot chips keep wire tag and unknown code plain`() {
        val known = mapOf("helper" to ComposerMentionSuggestion("helper", "Research Buddy", "research"))
        val text = "Ask @helper then `@helper` @unknown "
        val tokens = parseComposerTokens(text, knownBots = known)
        assertEquals(text, tokens.toServerText())
        assertEquals(1, tokens.filterIsInstance<ComposerToken.BotChip>().size)
        assertTrue(tokens.none { it is ComposerToken.PathChip })
        assertTrue(parseComposerTokens("@helper", knownBots = known).none { it is ComposerToken.BotChip })
        assertTrue(parseComposerTokens("@helper", knownBots = known, preservingTrailing = parseComposerTokens("@helper ", knownBots = known)).first() is ComposerToken.BotChip)
    }

    @Test fun `partial native delete removes entire bot token and mapping remains monotonic`() {
        val tag = "@helper"
        val tokens = listOf(ComposerToken.Text("Ask "), ComposerToken.BotChip("helper", "Research Buddy", "research"), ComposerToken.Text(" "))
        val original = tokens.toServerText()
        val updated = applyAtomicBotChipEdit(TextFieldValue(original, TextRange(4 + tag.length)), TextFieldValue("Ask @helpe ", TextRange(10)), tokens)
        assertEquals("Ask  ", updated.text)
        assertEquals(TextRange(4), updated.selection)
        val transformed = ComposerChipVisualTransformation(tokens, Color.Black, Color.White, Color.White).filter(AnnotatedString(original))
        assertEquals("Ask @Research Buddy ", transformed.text.text)
        val forwards = (0..original.length).map(transformed.offsetMapping::originalToTransformed)
        assertEquals(forwards.sorted(), forwards)
        val reverse = (0..transformed.text.length).map(transformed.offsetMapping::transformedToOriginal)
        assertEquals(reverse.sorted(), reverse)
        assertEquals(original.length, reverse.last())
    }
}
