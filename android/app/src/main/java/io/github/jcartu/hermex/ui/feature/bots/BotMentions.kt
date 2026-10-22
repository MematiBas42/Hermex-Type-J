package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.ui.feature.chat.ComposerMentionSuggestion
import java.util.Locale

/**
 * Port of `BotMentions` (`Features/Bots/BotMentions.swift`): identification only, using one
 * connection's inbox roster. No delivery or relay work belongs here; the annotated prompt
 * follows the ordinary command path. Autocomplete retains roster order, prefix-matches only
 * and offers at most eight other bots; ambiguous aliases never resolve.
 */
class BotMentions(roster: List<BotProfile>, excluding: String) {
    data class Completion(val profile: BotProfile, val tag: String)
    data class Mention(val range: IntRange, val form: String)

    private val candidates: List<Completion>
    val references: Map<String, BotProfile>

    init {
        val candidates = ArrayList<Completion>()
        val forms = LinkedHashMap<String, BotProfile>()
        val ambiguous = HashSet<String>()
        val seen = HashSet<String>()
        for (bot in roster) {
            if (bot.id == excluding || !seen.add(bot.id)) continue
            val friendlyForms = nameForms(bot.title) + nameForms(bot.displayName)
            candidates += Completion(bot, friendlyForms.firstOrNull() ?: handle(bot))
            for (form in (friendlyForms + handle(bot).lowercase(Locale.ROOT) + bot.id.lowercase(Locale.ROOT)).toSet()) {
                if (forms[form]?.id?.let { it != bot.id } == true) ambiguous += form
                forms[form] = bot
            }
        }
        ambiguous.forEach(forms::remove)
        this.references = forms.toMap()
        this.candidates = candidates.mapNotNull { candidate ->
            val bot = candidate.profile
            val tag = listOf(candidate.tag, handle(bot), bot.id).firstOrNull { forms[it.lowercase(Locale.ROOT)]?.id == bot.id }
            tag?.let { Completion(bot, it) }
        }
    }

    fun completions(query: String): List<Completion> {
        val needle = query.lowercase(Locale.ROOT)
        return candidates.asSequence().filter { candidate ->
            needle.isEmpty() || listOf(candidate.tag, handle(candidate.profile), candidate.profile.name)
                .any { it.lowercase(Locale.ROOT).startsWith(needle) }
        }.take(8).toList()
    }

    fun resolve(text: String): List<BotProfile> {
        val seen = HashSet<String>()
        return proseMentions(text).mapNotNull { mention -> references[mention.form]?.takeIf { seen.add(it.id) } }
    }

    /** The suffix to append to the prompt; never the text itself. Only the user's typed text is scanned. */
    fun annotation(text: String): String {
        val bots = resolve(text)
        if (bots.isEmpty()) return ""
        return NOTE_PREFIX + bots.joinToString("; ") { bot ->
            "@${handle(bot)} = agent profile \"${bot.id}\"" + (bot.title?.let { " (\"$it\")" } ?: "")
        } + NOTE_SUFFIX
    }

    fun suggestions(query: String, avatars: Map<String, Bitmap> = emptyMap()): List<ComposerMentionSuggestion> =
        completions(query).map { completion ->
            ComposerMentionSuggestion(completion.tag, completion.profile.name, completion.profile.id, avatars[completion.profile.id]?.asImageBitmap())
        }

    /** Every unambiguous alias is available to the shared atomic-chip editor. */
    fun chipReferences(avatars: Map<String, Bitmap> = emptyMap()): Map<String, ComposerMentionSuggestion> =
        references.mapValues { (form, bot) -> ComposerMentionSuggestion(form, bot.name, bot.id, avatars[bot.id]?.asImageBitmap()) }

    companion object {
        private val reserved = setOf("all", "everyone", "user", "default", "hermes")
        private val tokens = Regex("(^|[\\p{Z}\\t\\n\\u000B\\f\\r])@([a-z0-9][a-z0-9_-]*+)(?![/\\\\]|\\.[a-z0-9_-])", RegexOption.IGNORE_CASE)
        private val fences = Regex("```[\\s\\S]*?```")
        private val inlineCode = Regex("`[^`\\n]*`")
        private val nameCharacters = Regex("[^a-z0-9_-]+")
        private val validForm = Regex("^[a-z0-9][a-z0-9_-]*$")
        const val NOTE_PREFIX = "\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: "
        const val NOTE_SUFFIX = ". If they want one of these agents contacted, compose your own message and send it with your message_agent tool (agents on other connected machines are reachable too — the Desktop relays it); never forward the user’s text verbatim. If this session has no message_agent tool, agent messaging is unavailable here — say so.]"
        private val noteLines = Regex("\\A@[a-zA-Z0-9][a-zA-Z0-9_-]* = agent profile \"[^\"\\r\\n]+\"(?: \\(\"[\\s\\S]*?\"\\))?(?:; @[a-zA-Z0-9][a-zA-Z0-9_-]* = agent profile \"[^\"\\r\\n]+\"(?: \\(\"[\\s\\S]*?\"\\))?)*\\z")

        fun handle(bot: BotProfile): String = if (bot.id.equals("default", ignoreCase = true)) "hermes" else bot.id

        /** Desktop slug and collapsed forms; reserved words block friendly aliases only. */
        fun nameForms(value: String?): List<String> {
            val name = value?.trim()?.lowercase(Locale.ROOT) ?: return emptyList()
            val slug = name.replace(nameCharacters, "-").trim('-')
            val collapsed = name.replace(nameCharacters, "")
            return listOf(slug, collapsed).filter { validForm.matches(it) && it !in reserved }.distinct()
        }

        /** Mask code with spaces so UTF-16 offsets stay aligned with the actual draft. */
        fun proseMentions(text: String): List<Mention> {
            val prose = StringBuilder(text)
            for (pattern in listOf(fences, inlineCode)) {
                for (match in pattern.findAll(prose.toString()).toList().asReversed()) {
                    for (index in match.range) prose.setCharAt(index, ' ')
                }
            }
            return tokens.findAll(prose).map { match ->
                val token = requireNotNull(match.groups[2])
                Mention((token.range.first - 1)..token.range.last, token.value.lowercase(Locale.ROOT))
            }.toList()
        }

        /** Hide only a complete, valid trailing identification note; similar user-authored text stays. */
        fun displayText(text: String): String {
            val start = text.lastIndexOf(NOTE_PREFIX)
            if (start < 0 || !text.endsWith(NOTE_SUFFIX)) return text
            val contentStart = start + NOTE_PREFIX.length
            val end = text.length - NOTE_SUFFIX.length
            if (contentStart > end || !noteLines.matches(text.substring(contentStart, end))) return text
            return text.substring(0, start)
        }

        /** Agreed pane callback: returns the note to append, not a duplicate copy of the prompt. */
        fun annotate(text: String, roster: List<BotProfile>, excluding: String): String = BotMentions(roster, excluding).annotation(text)
        fun strip(text: String): String = displayText(text)
    }
}

/** Port of `BotMentionTrigger`: caret-local UTF-16 replacement, preserving surrounding text. */
data class BotMentionTrigger(val range: IntRange, val query: String) {
    data class Replacement(val draft: String, val selection: TextRange)

    fun applying(tag: String, text: String): Replacement {
        val replacement = "@$tag "
        // Reuse an existing separator rather than inserting a second space at the caret.
        val end = range.last + 1
        val skip = if (text.getOrNull(end) == ' ') 1 else 0
        return Replacement(text.replaceRange(range.first, end + skip, replacement), TextRange(range.first + replacement.length))
    }

    companion object {
        private val token = Regex("(^|[\\p{Z}\\t\\n\\u000B\\f\\r])@([a-z0-9_-]*)$", RegexOption.IGNORE_CASE)
        fun detect(text: String, selection: TextRange): BotMentionTrigger? {
            if (!selection.collapsed || selection.start !in 0..text.length) return null
            val match = token.find(text.substring(0, selection.start)) ?: return null
            val query = requireNotNull(match.groups[2])
            return BotMentionTrigger((query.range.first - 1) until selection.start, query.value)
        }
    }
}
