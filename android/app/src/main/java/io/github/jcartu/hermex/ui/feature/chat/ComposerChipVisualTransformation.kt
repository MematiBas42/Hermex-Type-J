package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Port of `ComposerChipRendering.swift`: path/skill chips retain identity offsets; a bot
 * chip displays its cached face and friendly name while the editor, clipboard and wire keep
 * its original @tag. The explicit monotonic mapping preserves native selection/IME offsets.
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
        val builder = AnnotatedString.Builder()
        val offsets = ArrayList<ComposerChipOffsetSegment>()
        var sourceOffset = 0
        for (token in effectiveTokens) {
            val source = token.serverText
            val shown = if (token is ComposerToken.BotChip) {
                (if (token.avatar != null) "\u2003" else "@") + token.displayName
            } else source
            val start = builder.length
            builder.append(shown)
            offsets += ComposerChipOffsetSegment(sourceOffset, sourceOffset + source.length, start, builder.length)
            sourceOffset += source.length
            if (token is ComposerToken.Text || shown.isEmpty()) continue
            val color = if (token is ComposerToken.SkillChip) skillContent else pathContent
            builder.addStyle(SpanStyle(color = color, background = chipBackground, fontWeight = FontWeight.SemiBold), start, builder.length)
            val annotation = when (token) {
                is ComposerToken.PathChip -> "path:${token.path}"
                is ComposerToken.SkillChip -> "skill:${token.name}"
                is ComposerToken.BotChip -> "bot:${token.profile}"
                is ComposerToken.Text -> "text"
            }
            builder.addStringAnnotation(COMPOSER_TOKEN_ANNOTATION, annotation, start, builder.length)
        }
        return TransformedText(builder.toAnnotatedString(), ComposerChipOffsetMapping(offsets))
    }
}

internal data class ComposerChipOffsetSegment(val sourceStart: Int, val sourceEnd: Int, val shownStart: Int, val shownEnd: Int)

/** Piecewise monotonic UTF-16 mapping; copy/cut always operate on the source tag. */
internal class ComposerChipOffsetMapping(private val segments: List<ComposerChipOffsetSegment>) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val segment = segments.firstOrNull { offset <= it.sourceEnd } ?: return segments.lastOrNull()?.shownEnd ?: 0
        val length = segment.sourceEnd - segment.sourceStart
        if (length == 0) return segment.shownStart
        return segment.shownStart + (offset - segment.sourceStart).coerceIn(0, length) * (segment.shownEnd - segment.shownStart) / length
    }

    override fun transformedToOriginal(offset: Int): Int {
        val segment = segments.firstOrNull { offset <= it.shownEnd } ?: return segments.lastOrNull()?.sourceEnd ?: 0
        val length = segment.shownEnd - segment.shownStart
        if (length == 0) return segment.sourceStart
        return segment.sourceStart + (offset - segment.shownStart).coerceIn(0, length) * (segment.sourceEnd - segment.sourceStart) / length
    }
}
