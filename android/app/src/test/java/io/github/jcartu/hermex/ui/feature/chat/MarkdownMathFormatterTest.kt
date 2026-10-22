package io.github.jcartu.hermex.ui.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathFormatterTest {

    @Test
    fun `currency dollars are not treated as inline math`() {
        val input = """Prices are ${'$'}5 and ${'$'}10."""

        assertEquals(input, MarkdownMathFormatter.format(input))
        assertEquals(listOf(MarkdownMathSegment.Markdown(input)), MarkdownMathSegmenter.segments(input))
    }

    @Test
    fun `real inline dollar and parenthesis math become inline code`() {
        val input = """Use ${'$'}x^2 + y^2 = z^2${'$'} and \(e^{i\pi}+1=0\)."""

        assertEquals(
            """Use `x² + y² = z²` and `eⁱπ+1=0`.""",
            MarkdownMathFormatter.format(input),
        )
    }

    @Test
    fun `escaped dollars remain literal while adjacent math is formatted`() {
        val input = """Keep \${'$'}not math\${'$'} and solve ${'$'}x+1=2${'$'}."""

        assertEquals(
            """Keep \${'$'}not math\${'$'} and solve `x+1=2`.""",
            MarkdownMathFormatter.format(input),
        )
    }

    @Test
    fun `display dollars become a latex code fence`() {
        val input = """
            Before

            ${'$'}${'$'}\frac{1}{2}${'$'}${'$'}

            After
        """.trimIndent()
        val formatted = MarkdownMathFormatter.format(input)

        assertTrue(formatted.contains("```latex\n1/2\n```"))
        assertFalse(formatted.contains("${'$'}${'$'}"))
        assertTrue(formatted.startsWith("Before"))
        assertTrue(formatted.endsWith("After"))
    }

    @Test
    fun `bracket display math is segmented separately`() {
        val input = """
            Block:

            \[
            \int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}
            \]
        """.trimIndent()
        val display = MarkdownMathSegmenter.segments(input)
            .filterIsInstance<MarkdownMathSegment.DisplayMath>()

        assertEquals(1, display.size)
        assertEquals(
            """\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}""",
            display.single().latex,
        )
    }

    @Test
    fun `bare align environment becomes display source`() {
        val input = """
            Intro
            \begin{align}
            x &= 1 \\
            y &= 2
            \end{align}
            Outro
        """.trimIndent()
        val segments = MarkdownMathSegmenter.segments(input)
        val display = segments.filterIsInstance<MarkdownMathSegment.DisplayMath>().single()

        assertEquals(
            """
                \begin{align}
                x &= 1 \\
                y &= 2
                \end{align}
            """.trimIndent(),
            display.latex,
        )
        assertTrue(MarkdownMathFormatter.format(input).contains("```latex\nx = 1\ny = 2\n```"))
    }

    @Test
    fun `dollars inside fenced and inline code are never math`() {
        val input = """
            ```kotlin
            val price = "${'$'}5"
            val equation = "${'$'}x^2${'$'}"
            ```

            Inline `${'$'}y^2${'$'}` stays, but ${'$'}z^2${'$'} changes.
        """.trimIndent()
        val formatted = MarkdownMathFormatter.format(input)

        assertTrue(formatted.contains("val equation = \"${'$'}x^2${'$'}\""))
        assertTrue(formatted.contains("`${'$'}y^2${'$'}`"))
        assertTrue(formatted.contains("but `z²` changes"))
        assertEquals(1, MarkdownMathSegmenter.segments(input).filterIsInstance<MarkdownMathSegment.InlineMath>().size)
    }

    @Test
    fun `display dollars inside fenced code are not segmented`() {
        val input = """
            ~~~markdown
            ${'$'}${'$'}\sum_{n=1}^{\infty} n^{-2}${'$'}${'$'}
            ~~~
        """.trimIndent()

        assertEquals(input, MarkdownMathFormatter.format(input))
        assertFalse(MarkdownMathSegmenter.segments(input).containsMath)
    }

    @Test
    fun `inline subscripts superscripts assignments and numeric tuples render accurately`() {
        val input =
            """${'$'}Q_{447}${'$'} ${'$'}x_{i,j}^2${'$'} ${'$'}E=[4,-2]${'$'} ${'$'}(4,-2)${'$'} ${'$'}M_S${'$'}"""
        val formatted = MarkdownMathFormatter.format(input)

        assertEquals("`Q₄₄₇` `xᵢ,ⱼ²` `E=[4,-2]` `(4,-2)` `M_S`", formatted)
        assertEquals(
            listOf("Q_{447}", "x_{i,j}^2", "E=[4,-2]", "(4,-2)", "M_S"),
            MarkdownMathSegmenter.segments(input)
                .filterIsInstance<MarkdownMathSegment.InlineMath>()
                .map { it.latex },
        )
    }

    @Test
    fun `ordinary markdown passes through byte for byte`() {
        val input = "**Bold** [link](https://example.com) and `code`."

        assertEquals(input, MarkdownMathFormatter.format(input))
    }
}
