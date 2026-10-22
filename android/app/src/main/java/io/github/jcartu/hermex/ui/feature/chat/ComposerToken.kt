package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import io.github.jcartu.hermex.model.SlashSkillFormatter

/**
 * One semantic run in the composer draft.
 *
 * Port of `Features/Chat/ComposerChipToken.swift`. Text remains ordinary editable
 * text; path and skill references are rendered atomically while their server
 * representation stays plain text.
 */
sealed interface ComposerToken {
    data class Text(val text: String) : ComposerToken
    data class PathChip(val path: String) : ComposerToken
    data class SkillChip(val name: String) : ComposerToken
}

/** Exact plain-text representation accepted by Hermes/webui. */
val ComposerToken.serverText: String
    get() = when (this) {
        is ComposerToken.Text -> text
        is ComposerToken.PathChip -> "@${path.removePrefix("@")}" 
        is ComposerToken.SkillChip -> "/${SlashSkillFormatter.slug(name.removePrefix("/"))}"
    }

/** Human-readable label used by the composer and sent-message bubble. */
val ComposerToken.displayText: String
    get() = when (this) {
        is ComposerToken.Text -> text
        is ComposerToken.PathChip -> {
            val normalized = path.removePrefix("@").trimEnd('/')
            normalized.substringAfterLast('/').ifEmpty { normalized }
        }
        is ComposerToken.SkillChip -> name.removePrefix("/")
    }

/**
 * Serializes without inserting implicit whitespace. Whitespace belongs to
 * [ComposerToken.Text], making the model lossless around punctuation and IME edits.
 */
fun List<ComposerToken>.toServerText(): String = buildString(sumOf { it.serverText.length }) {
    for (token in this@toServerText) append(token.serverText)
}

/**
 * Display form for transcript consumers. Chip runs carry a `composer-token`
 * annotation whose value is `path` or `skill`; callers can draw their own chip
 * surface while retaining a plain accessible fallback.
 */
fun List<ComposerToken>.toDisplayAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (token in this@toDisplayAnnotatedString) {
        when (token) {
            is ComposerToken.Text -> append(token.text)
            is ComposerToken.PathChip -> {
                pushStringAnnotation(tag = COMPOSER_TOKEN_ANNOTATION, annotation = "path:${token.path}")
                append(token.displayText)
                pop()
            }
            is ComposerToken.SkillChip -> {
                pushStringAnnotation(tag = COMPOSER_TOKEN_ANNOTATION, annotation = "skill:${token.name}")
                append(token.displayText)
                pop()
            }
        }
    }
}

const val COMPOSER_TOKEN_ANNOTATION: String = "composer-token"

/**
 * Reconstructs semantic runs from server text.
 *
 * This follows `ComposerChipTokenizer` from `ComposerChipToken.swift`: a marker
 * must begin the draft or follow whitespace. Known paths are matched longest
 * first, which also preserves a path containing spaces when the server supplied
 * that exact path. Without a catalog an `@` reference ends at whitespace. Skill
 * names are normalized through [SlashSkillFormatter], and built-in slash
 * commands deliberately remain text rather than masquerading as skill chips.
 * Unknown text is retained verbatim and adjacent text runs are coalesced.
 */
fun parseComposerTokens(
    text: String,
    knownPaths: Set<String> = emptySet(),
    knownSkills: Map<String, String> = emptyMap(),
): List<ComposerToken> {
    if (text.isEmpty()) return emptyList()

    val normalizedSkills = knownSkills.entries.associate { (slug, label) ->
        SlashSkillFormatter.slug(slug.removePrefix("/")) to label
    }
    val pathsLongestFirst = knownPaths
        .asSequence()
        .map { it.removePrefix("@") }
        .filter(String::isNotEmpty)
        .distinct()
        .sortedByDescending(String::length)
        .toList()

    val output = ArrayList<ComposerToken>()
    var textStart = 0
    var index = 0

    fun appendText(endExclusive: Int) {
        if (endExclusive <= textStart) return
        val value = text.substring(textStart, endExclusive)
        val previous = output.lastOrNull()
        if (previous is ComposerToken.Text) {
            output[output.lastIndex] = ComposerToken.Text(previous.text + value)
        } else {
            output += ComposerToken.Text(value)
        }
    }

    while (index < text.length) {
        val marker = text[index]
        val startsWord = index == 0 || text[index - 1].isWhitespace()
        if (!startsWord || (marker != '@' && marker != '/')) {
            index += 1
            continue
        }

        val token: ComposerToken?
        val endExclusive: Int
        if (marker == '@') {
            val knownPath = pathsLongestFirst.firstOrNull { path ->
                val source = "@$path"
                text.regionMatches(index, source, 0, source.length) &&
                    (index + source.length == text.length || text[index + source.length].isWhitespace())
            }
            if (knownPath != null) {
                endExclusive = index + knownPath.length + 1
                token = ComposerToken.PathChip(knownPath)
            } else {
                var end = index + 1
                while (end < text.length && !text[end].isWhitespace()) end += 1
                endExclusive = end
                token = text.substring(index + 1, end)
                    .takeIf(String::isNotEmpty)
                    ?.let(ComposerToken::PathChip)
            }
        } else {
            var end = index + 1
            while (end < text.length) {
                val character = text[end]
                if (!(character.isLetterOrDigit() || character == '-' || character == '_')) break
                end += 1
            }
            endExclusive = end
            val rawName = text.substring(index + 1, end)
            val slug = SlashSkillFormatter.slug(rawName)
            val isBuiltIn = SlashCommandCatalog.command(named = slug) != null
            token = if (slug.isNotEmpty() && !isBuiltIn) {
                ComposerToken.SkillChip(normalizedSkills[slug] ?: rawName)
            } else {
                null
            }
        }

        if (token == null) {
            index = maxOf(index + 1, endExclusive)
            continue
        }

        appendText(index)
        output += token
        index = endExclusive
        textStart = endExclusive
    }

    appendText(text.length)
    return output
}

/** Raw UTF-16 ranges of chip tokens in their serialized draft. */
internal fun List<ComposerToken>.chipRanges(): List<ComposerChipRange> {
    var offset = 0
    val ranges = ArrayList<ComposerChipRange>()
    for (token in this) {
        val source = token.serverText
        if (token !is ComposerToken.Text && source.isNotEmpty()) {
            ranges += ComposerChipRange(start = offset, endExclusive = offset + source.length, token = token)
        }
        offset += source.length
    }
    return ranges
}

internal data class ComposerChipRange(
    val start: Int,
    val endExclusive: Int,
    val token: ComposerToken,
)
