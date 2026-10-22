package io.github.jcartu.hermex.ui.feature.workspace

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A workspace-relative chat link plus its optional one-based source position. */
data class WorkspaceFileReference(
    val path: String,
    val line: Int? = null,
    val column: Int? = null,
)

/** One-shot app-shell request created by [openWorkspaceFile]. */
data class WorkspaceFileNavigationRequest(
    val id: Long,
    val reference: WorkspaceFileReference,
)

/**
 * Process-local navigation bridge for transcript Markdown links. The app shell
 * observes the newest request, switches to Files, and the Files route resolves
 * it against the currently selected chat session.
 */
object WorkspaceFileNavigation {
    private val sequence = AtomicLong()
    private val mutableRequest = MutableStateFlow<WorkspaceFileNavigationRequest?>(null)
    val request: StateFlow<WorkspaceFileNavigationRequest?> = mutableRequest.asStateFlow()

    /** Port of chat `FileReference` navigation into `FilePreviewView`. */
    fun open(path: String) {
        val reference = parseWorkspaceFileReference(path) ?: return
        mutableRequest.value = WorkspaceFileNavigationRequest(sequence.incrementAndGet(), reference)
    }

    /** Clears only the request the consumer handled; a newer request is retained. */
    fun consume(id: Long) {
        if (mutableRequest.value?.id == id) mutableRequest.value = null
    }
}

/**
 * Public transcript/attachment entry point. Accepts `path`, `path:line:column`,
 * and `path#LlineCcolumn`, matching upstream `FileReference.swift`.
 */
fun openWorkspaceFile(path: String) {
    WorkspaceFileNavigation.open(path)
}

/** Pure parser used by transcript links and unit tests. */
fun parseWorkspaceFileReference(value: String): WorkspaceFileReference? {
    var raw = value.trim().removeSurrounding("<", ">")
    if (raw.isEmpty()) return null
    raw = raw.substringBefore('?')

    var fragmentLine: Int? = null
    var fragmentColumn: Int? = null
    val fragment = raw.substringAfter('#', missingDelimiterValue = "")
    if (fragment.isNotEmpty()) {
        val match = Regex("(?i)^L([1-9]\\d*)(?:C([1-9]\\d*))?$").matchEntire(fragment)
        fragmentLine = match?.groupValues?.get(1)?.toIntOrNull()
        fragmentColumn = match?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toIntOrNull()
        raw = raw.substringBefore('#')
    }

    val suffix = Regex(":([1-9]\\d*)(?::([1-9]\\d*))?$").find(raw)
    val line = suffix?.groupValues?.get(1)?.toIntOrNull() ?: fragmentLine
    val column = suffix?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toIntOrNull() ?: fragmentColumn
    if (suffix != null) raw = raw.substring(0, suffix.range.first)

    if (raw.startsWith("file:", ignoreCase = true)) {
        raw = runCatching { java.net.URI(raw).path }.getOrNull() ?: return null
    }
    raw = raw.replace('\\', '/').removePrefix("./")
    val normalized = normalizeReferencePath(raw) ?: return null
    return WorkspaceFileReference(normalized, line, column)
}

private fun normalizeReferencePath(path: String): String? {
    val absolute = path.startsWith('/')
    val parts = ArrayDeque<String>()
    path.split('/').forEach { component ->
        when (component) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts.addLast(component)
        }
    }
    if (parts.isEmpty()) return null
    val joined = parts.joinToString("/")
    return if (absolute) "/$joined" else joined
}
