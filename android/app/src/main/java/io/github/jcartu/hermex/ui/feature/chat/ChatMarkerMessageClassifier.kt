package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ChatMessage

enum class ChatMarkerMessageKind(val title: String, val iconHint: MarkerMessageIconHint) {
    CONTEXT_COMPACTION("Context compaction", MarkerMessageIconHint.COMPACTION),
    PRESERVED_TASK_LIST("Preserved task list", MarkerMessageIconHint.TASK_LIST),
    /** Synthesized from session compression-anchor metadata, never from [classification]. */
    COMPRESSION_REFERENCE("Context compaction", MarkerMessageIconHint.REFERENCE),
}

enum class MarkerMessageIconHint { COMPACTION, TASK_LIST, REFERENCE }

data class MarkerMessageClassification(
    val kind: ChatMarkerMessageKind,
    val title: String,
    val detail: String,
    val iconHint: MarkerMessageIconHint,
)

/**
 * Detects the literal context-compaction markers emitted as ordinary role-based
 * messages. Matching intentionally mirrors the web and iOS prefix checks.
 */
object ChatMarkerMessageClassifier {
    private const val PRESERVED_TASK_LIST_PREFIX =
        "[your active task list was preserved across context compression]"
    private val CONTEXT_COMPACTION_PREFIXES = listOf("[context compaction", "context compaction")

    fun classification(message: ChatMessage): MarkerMessageClassification? {
        val role = message.role ?: return null
        if (role == "tool") return null
        val text = message.content.orEmpty().trim()

        if (role == "user" && text.hasCaseInsensitivePrefix(PRESERVED_TASK_LIST_PREFIX)) {
            return classification(ChatMarkerMessageKind.PRESERVED_TASK_LIST, text)
        }
        if (isContextCompactionText(text)) {
            return classification(ChatMarkerMessageKind.CONTEXT_COMPACTION, text)
        }
        return null
    }

    fun isContextCompactionText(text: String?): Boolean {
        val trimmed = text.orEmpty().trim()
        return CONTEXT_COMPACTION_PREFIXES.any { trimmed.hasCaseInsensitivePrefix(it) }
    }

    fun cardBody(kind: ChatMarkerMessageKind, content: String?): String {
        val text = content.orEmpty().trim()
        if (
            kind != ChatMarkerMessageKind.PRESERVED_TASK_LIST ||
            !text.hasCaseInsensitivePrefix(PRESERVED_TASK_LIST_PREFIX)
        ) {
            return text
        }
        return text.substring(PRESERVED_TASK_LIST_PREFIX.length).trim()
    }

    /** Builds the non-literal reference card used by compression-anchor metadata. */
    fun compressionReference(content: String?): MarkerMessageClassification =
        classification(ChatMarkerMessageKind.COMPRESSION_REFERENCE, content)

    private fun classification(
        kind: ChatMarkerMessageKind,
        content: String?,
    ): MarkerMessageClassification = MarkerMessageClassification(
        kind = kind,
        title = kind.title,
        detail = cardBody(kind, content),
        iconHint = kind.iconHint,
    )

    private fun String.hasCaseInsensitivePrefix(prefix: String): Boolean =
        startsWith(prefix, ignoreCase = true)
}
