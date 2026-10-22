package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** One workspace path row offered by the composer's `@` panel. */
data class ComposerFileSuggestion(
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
)

/**
 * Owns one autocomplete panel's asynchronous results.
 *
 * Port of `Features/Chat/FilePathSearch.swift`: a generation guard prevents a
 * slow answer for an older query from replacing a newer query. Directory
 * listing and ranking live in ChatViewModel so that its per-session cache is
 * discarded together with the session/workspace scope.
 */
@Stable
class ComposerFileAutocompleteController {
    private val generation = AtomicLong(0L)

    var matches: List<ComposerFileSuggestion> by mutableStateOf(emptyList())
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var answeredQuery: String? by mutableStateOf(null)
        private set

    suspend fun search(
        query: String,
        loader: suspend (String) -> List<ComposerFileSuggestion>,
    ) {
        val requestGeneration = generation.incrementAndGet()
        isLoading = true
        matches = emptyList()
        answeredQuery = null
        try {
            val loaded = loader(query)
            if (generation.get() != requestGeneration) return
            matches = loaded
            answeredQuery = query
            isLoading = false
        } catch (cancelled: CancellationException) {
            if (generation.get() == requestGeneration) {
                isLoading = false
                matches = emptyList()
                answeredQuery = query
            }
            throw cancelled
        } catch (_: Exception) {
            if (generation.get() != requestGeneration) return
            isLoading = false
            matches = emptyList()
            answeredQuery = query
        }
    }

    fun reset() {
        generation.incrementAndGet()
        matches = emptyList()
        isLoading = false
        answeredQuery = null
    }
}
