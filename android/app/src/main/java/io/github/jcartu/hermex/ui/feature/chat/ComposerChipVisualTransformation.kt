package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Draws semantic references as highlighted inline chips without replacing a
 * single character in the editable document.
 *
 * This is the Compose counterpart of `ComposerChipTextView.swift` and
 * `ComposerChipRendering.swift`. Keeping source and transformed offsets
 * identical is intentional: selection handles, IME composing regions, native
 * text gestures, copy/cut, and manual scrolling all operate on the exact text
 * sent to the server.
 */
internal class ComposerChipVisualTransformation(
    private val tokens: List<ComposerToken>,
    private val chipBackground: Color,
    private val pathContent: Color,
    private val skillContent: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val effectiveTokens = tokens.takeIf { it.toServerText() == text.text }
            ?: parseComposerTokens(text.text)
        val builder = AnnotatedString.Builder(text)
        for (range in effectiveTokens.chipRanges()) {
            if (range.start !in 0..text.length || range.endExclusive !in 0..text.length) continue
            if (range.start >= range.endExclusive) continue
            val contentColor = when (range.token) {
                is ComposerToken.PathChip -> pathContent
                is ComposerToken.SkillChip -> skillContent
                is ComposerToken.Text -> continue
            }
            builder.addStyle(
                style = SpanStyle(
                    color = contentColor,
                    background = chipBackground,
                    fontWeight = FontWeight.SemiBold,
                ),
                start = range.start,
                end = range.endExclusive,
            )
            builder.addStringAnnotation(
                tag = COMPOSER_TOKEN_ANNOTATION,
                annotation = when (val token = range.token) {
                    is ComposerToken.PathChip -> "path:${token.path}"
                    is ComposerToken.SkillChip -> "skill:${token.name}"
                    is ComposerToken.Text -> "text"
                },
                start = range.start,
                end = range.endExclusive,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
