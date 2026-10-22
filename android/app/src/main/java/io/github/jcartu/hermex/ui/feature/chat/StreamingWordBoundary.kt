package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Port of `Features/Chat/StreamingRevealGate.swift` (#1128/#1131). */
object StreamingWordBoundaryChunker {
    data class Cut(val shown: String, val held: String)

    /**
     * Reveals through the last whitespace. A fragment with no boundary is held
     * for one tick only so CJK, long URLs and code cannot stall indefinitely.
     */
    fun cut(pending: String, heldPreviousTick: Boolean): Cut {
        val lastWhitespace = pending.indexOfLast(Char::isWhitespace)
        if (lastWhitespace < 0) {
            return if (heldPreviousTick) Cut(pending, "") else Cut("", pending)
        }
        val boundary = lastWhitespace + 1
        return Cut(pending.substring(0, boundary), pending.substring(boundary))
    }
}

/** Mutable, allocation-light state machine behind [rememberWordBoundaryText]. */
internal class StreamingWordBoundaryState(initialText: String) {
    private var sourceText: String = initialText
    private var pending: String = ""
    private var heldPreviousTick: Boolean = false
    var shownText: String = initialText
        private set

    fun offer(text: String) {
        if (text == sourceText) return
        if (!text.startsWith(sourceText)) {
            sourceText = text
            shownText = text
            pending = ""
            heldPreviousTick = false
            return
        }
        pending += text.substring(sourceText.length)
        sourceText = text
    }

    fun tick(): String {
        if (pending.isEmpty()) {
            heldPreviousTick = false
            return shownText
        }
        val cut = StreamingWordBoundaryChunker.cut(pending, heldPreviousTick)
        if (cut.shown.isNotEmpty()) shownText += cut.shown
        pending = cut.held
        heldPreviousTick = pending.isNotEmpty()
        return shownText
    }

    fun finish(text: String): String {
        sourceText = text
        shownText = text
        pending = ""
        heldPreviousTick = false
        return shownText
    }
}

/**
 * Displays a growing assistant reply in coalesced word-boundary steps. The
 * source still updates every ChatCore flush; this leaf alone ticks while the
 * message streams, so settled transcript rows do no repeated work.
 */
@Composable
fun rememberWordBoundaryText(
    text: String,
    isStreaming: Boolean,
    streamKey: Any?,
): String {
    val state = remember(streamKey) { StreamingWordBoundaryState(text) }
    var displayed by remember(streamKey) { mutableStateOf(text) }

    LaunchedEffect(text, isStreaming, streamKey) {
        if (isStreaming) {
            state.offer(text)
        } else {
            displayed = state.finish(text)
        }
    }

    LaunchedEffect(isStreaming, streamKey) {
        if (!isStreaming) {
            displayed = state.finish(text)
            return@LaunchedEffect
        }
        while (true) {
            delay(16)
            val next = state.tick()
            if (next != displayed) displayed = next
        }
    }

    return displayed
}
