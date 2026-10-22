package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Port of `BotMentions.Completion` / `ComposerBotReference` (`Features/Bots/BotMentions.swift`,
 * `Features/Chat/ComposerChipToken.swift`): one connection-scoped bot above Files in the @
 * panel. The original [tag] is inserted/stored/sent; the friendly name and already-cached
 * image are presentation only. Filtering this source never fetches a picture.
 */
data class ComposerMentionSuggestion(
    val tag: String,
    val displayName: String,
    val profile: String,
    val avatar: ImageBitmap? = null,
)

/** Port of `BotAtAutocompleteView`: newest caret query wins; bot rows precede file rows. */
@Stable
class ComposerMentionAutocompleteController {
    private val generation = AtomicLong()
    var matches: List<ComposerMentionSuggestion> by mutableStateOf(emptyList())
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var answeredQuery: String? by mutableStateOf(null)
        private set

    suspend fun search(query: String, loader: suspend (String) -> List<ComposerMentionSuggestion>) {
        val owner = generation.incrementAndGet()
        matches = emptyList()
        answeredQuery = null
        if (query.any { it == '/' || it == '\\' || it.isWhitespace() }) {
            isLoading = false
            answeredQuery = query
            return
        }
        isLoading = true
        try {
            val result = loader(query)
            if (generation.get() != owner) return
            matches = result.take(8)
            answeredQuery = query
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation.get() == owner) answeredQuery = query
        } finally {
            if (generation.get() == owner) isLoading = false
        }
    }

    fun reset() {
        generation.incrementAndGet()
        matches = emptyList()
        isLoading = false
        answeredQuery = null
    }
}
