package io.github.jcartu.hermex.ui.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingWordBoundaryTest {
    @Test
    fun `cuts at final whitespace and holds partial word`() {
        assertEquals(
            StreamingWordBoundaryChunker.Cut("alpha beta ", "gam"),
            StreamingWordBoundaryChunker.cut("alpha beta gam", heldPreviousTick = false),
        )
    }

    @Test
    fun `held fragment finishes at next boundary`() {
        assertEquals(
            StreamingWordBoundaryChunker.Cut("gamma ", "delt"),
            StreamingWordBoundaryChunker.cut("gamma delt", heldPreviousTick = true),
        )
    }

    @Test
    fun `boundaryless CJK is held one tick then released`() {
        val first = StreamingWordBoundaryChunker.cut("你好世界", heldPreviousTick = false)
        assertEquals("", first.shown)
        assertEquals("你好世界", first.held)
        val second = StreamingWordBoundaryChunker.cut("你好世界，再见", heldPreviousTick = true)
        assertEquals("你好世界，再见", second.shown)
        assertEquals("", second.held)
    }

    @Test
    fun `never splits grapheme cluster around a boundary`() {
        val text = "cafe\u0301 👩‍👩‍👧‍👦"
        val cut = StreamingWordBoundaryChunker.cut(text, heldPreviousTick = false)
        assertEquals("cafe\u0301 ", cut.shown)
        assertEquals("👩‍👩‍👧‍👦", cut.held)
        assertEquals(text, cut.shown + cut.held)
    }

    @Test
    fun `accumulator coalesces deltas into boundary steps`() {
        val state = StreamingWordBoundaryState("")
        state.offer("hello")
        assertEquals("", state.tick())
        state.offer("hello world")
        assertEquals("hello ", state.tick())
        state.offer("hello world!")
        assertEquals("hello world!", state.tick())
    }
}
