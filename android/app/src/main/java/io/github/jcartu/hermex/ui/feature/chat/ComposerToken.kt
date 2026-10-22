package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.SlashSkillFormatter

/**
 * Port of `ComposerChipToken.swift`: what a chip in the composer stands for.
 *
 * The chip is a picture of the text the user would have typed, never a
 * replacement for it: what the server receives and what the draft store keeps
 * is exactly [toServerText]. ComposerUpgrades owns this file's rendering and
 * tokenizer helpers; the three cases, [toServerText] and [parseComposerTokens]
 * are the contract `ChatViewModel` codes against.
 */
sealed interface ComposerToken {
    /** Plain typed text between chips. */
    data class Text(val text: String) : ComposerToken

    /** A workspace file reference typed as `@path` (1.6.0). */
    data class PathChip(val path: String) : ComposerToken

    /** A picked skill, sent as the `/skill-name` prefix (`HermesSlashCommands.swift`). */
    data class SkillChip(val name: String) : ComposerToken
}

/**
 * The text the server expects for a token list: `@` + path for a file chip,
 * `/` + the normalized skill slug for a skill chip, typed text verbatim.
 * Tokens are joined at their boundaries exactly as they appear in the draft.
 */
fun List<ComposerToken>.toServerText(): String = buildString {
    for (token in this@toServerText) {
        when (token) {
            is ComposerToken.Text -> append(token.text)
            is ComposerToken.PathChip -> append('@').append(token.path)
            is ComposerToken.SkillChip -> append('/').append(SlashSkillFormatter.slug(token.name))
        }
    }
}

/**
 * Tolerant round-trip of [toServerText]: a leading `/slug` becomes a
 * [ComposerToken.SkillChip], each whitespace-delimited `@path` a
 * [ComposerToken.PathChip]; everything else stays [ComposerToken.Text] so the
 * draft text is preserved byte for byte.
 */
fun parseComposerTokens(text: String): List<ComposerToken> {
    if (text.isEmpty()) return emptyList()
    val tokens = mutableListOf<ComposerToken>()
    var index = 0
    val pending = StringBuilder()

    fun flushText() {
        if (pending.isNotEmpty()) {
            tokens += ComposerToken.Text(pending.toString())
            pending.setLength(0)
        }
    }

    fun isBoundary(position: Int): Boolean = position <= 0 || text[position - 1].isWhitespace()

    while (index < text.length) {
        val character = text[index]
        val boundary = isBoundary(index)
        if (character == '/' && index == 0) {
            val end = text.indexOfFirst(index + 1) { it.isWhitespace() }
            val slug = text.substring(index + 1, end)
            if (slug.isNotEmpty() && slug.none { it.isWhitespace() }) {
                flushText()
                tokens += ComposerToken.SkillChip(slug)
                index = end
                continue
            }
        }
        if (character == '@' && boundary) {
            val end = text.indexOfFirst(index + 1) { it.isWhitespace() }
            val path = text.substring(index + 1, end)
            if (path.isNotEmpty()) {
                flushText()
                tokens += ComposerToken.PathChip(path)
                index = end
                continue
            }
        }
        pending.append(character)
        index += 1
    }
    flushText()
    return tokens
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    var position = from
    while (position < length) {
        if (predicate(this[position])) return position
        position += 1
    }
    return length
}
