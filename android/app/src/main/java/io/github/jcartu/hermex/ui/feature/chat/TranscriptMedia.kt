package io.github.jcartu.hermex.ui.feature.chat

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Port of `Features/Chat/TranscriptMedia.swift`. */
sealed interface TranscriptMediaSource {
    data class LocalPath(val path: String) : TranscriptMediaSource
    data class RemoteUrl(val url: String) : TranscriptMediaSource
}

enum class TranscriptMediaKind { IMAGE, AUDIO, VIDEO, DOCUMENT, UNSUPPORTED }

data class TranscriptMediaReference(
    val rawReference: String,
    val altText: String? = null,
    val workspaceRoot: String? = null,
) {
    val id: String get() = rawReference

    val source: TranscriptMediaSource
        get() {
            val trimmed = rawReference.trim().removeSurrounding("<", ">")
            val lower = trimmed.lowercase(Locale.ROOT)
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return TranscriptMediaSource.RemoteUrl(trimmed)
            }
            if (lower.startsWith("file:")) {
                val decoded = runCatching { URI(trimmed).path }.getOrNull()
                    ?: URLDecoder.decode(trimmed.removePrefix("file://"), StandardCharsets.UTF_8.name())
                return TranscriptMediaSource.LocalPath(decoded)
            }
            val path = if (trimmed.startsWith('/')) {
                trimmed
            } else {
                workspaceRoot?.trimEnd('/')?.let { "$it/$trimmed" } ?: trimmed
            }
            return TranscriptMediaSource.LocalPath(path)
        }

    val displayName: String
        get() {
            val value = when (val source = source) {
                is TranscriptMediaSource.LocalPath -> source.path
                is TranscriptMediaSource.RemoteUrl -> runCatching { URI(source.url).path }.getOrNull().orEmpty()
            }
            return value.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "Media" }
        }

    val accessibilityName: String
        get() = altText?.trim()?.takeIf(String::isNotEmpty) ?: displayName

    val extension: String
        get() {
            val value = when (val source = source) {
                is TranscriptMediaSource.LocalPath -> source.path
                is TranscriptMediaSource.RemoteUrl -> runCatching { URI(source.url).path }.getOrNull().orEmpty()
            }
            return value.substringAfterLast('/', value).substringAfterLast('.', "").lowercase(Locale.ROOT)
        }

    val mediaKind: TranscriptMediaKind
        get() = when (extension) {
            in IMAGE_EXTENSIONS -> TranscriptMediaKind.IMAGE
            in AUDIO_EXTENSIONS -> TranscriptMediaKind.AUDIO
            in VIDEO_EXTENSIONS -> TranscriptMediaKind.VIDEO
            in DOCUMENT_EXTENSIONS -> TranscriptMediaKind.DOCUMENT
            else -> TranscriptMediaKind.UNSUPPORTED
        }

    val workspacePath: String?
        get() {
            val local = (source as? TranscriptMediaSource.LocalPath)?.path ?: return null
            val root = workspaceRoot?.trimEnd('/') ?: return local.takeUnless { it.startsWith('/') }
            return if (local == root) "" else local.removePrefix("$root/").takeUnless { it == local && local.startsWith('/') }
        }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "tif")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "avi", "mkv")
        private val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pages", "numbers", "key", "txt", "md", "csv", "json",
        )
    }
}

sealed interface TranscriptMediaSegment {
    data class Text(val markdown: String) : TranscriptMediaSegment
    data class Media(val reference: TranscriptMediaReference) : TranscriptMediaSegment
}

/** Splits assistant Markdown into prose and inline media without touching code. */
object TranscriptMediaParser {
    fun segments(markdown: String, workspaceRoot: String? = null): List<TranscriptMediaSegment> {
        if (markdown.isEmpty() || !mayContainMedia(markdown)) return listOf(TranscriptMediaSegment.Text(markdown))
        val builder = SegmentBuilder()
        var cursor = 0
        var inFence = false
        var fenceCharacter: Char? = null
        while (cursor < markdown.length) {
            val lineEnd = markdown.indexOf('\n', cursor).let { if (it < 0) markdown.length else it + 1 }
            val line = markdown.substring(cursor, lineEnd)
            val marker = fenceMarker(line)
            if (inFence) {
                builder.appendText(line)
                if (marker == fenceCharacter) {
                    inFence = false
                    fenceCharacter = null
                }
            } else if (marker != null) {
                inFence = true
                fenceCharacter = marker
                builder.appendText(line)
            } else {
                appendLine(line, workspaceRoot, builder)
            }
            cursor = lineEnd
        }
        return builder.build().ifEmpty { listOf(TranscriptMediaSegment.Text(markdown)) }
    }

    private fun appendLine(line: String, workspaceRoot: String?, builder: SegmentBuilder) {
        val codeRanges = inlineCodeRanges(line)
        var index = 0
        var textStart = 0
        while (index < line.length) {
            if (codeRanges.any { index in it }) {
                index += 1
                continue
            }
            val markdownImage = if (line.startsWith("![", index)) parseMarkdownImage(line, index) else null
            if (markdownImage != null) {
                val reference = TranscriptMediaReference(markdownImage.destination, markdownImage.alt, workspaceRoot)
                if (reference.mediaKind == TranscriptMediaKind.IMAGE ||
                    (reference.source is TranscriptMediaSource.RemoteUrl && reference.extension.isEmpty())
                ) {
                    builder.appendText(line.substring(textStart, index))
                    builder.appendMedia(reference)
                    index = markdownImage.endExclusive
                    textStart = index
                    continue
                }
            }
            val markerLength = when {
                line.startsWith("MEDIA:", index, ignoreCase = false) -> 6
                isBareFileUrl(line, index) -> 0
                else -> -1
            }
            if (markerLength >= 0) {
                val start = index + markerLength
                val end = referenceEnd(line, start)
                if (end > start) {
                    val raw = line.substring(start, end).trimEnd('.', ',', ';', ':')
                    val reference = TranscriptMediaReference(raw, workspaceRoot = workspaceRoot)
                    if (reference.mediaKind != TranscriptMediaKind.UNSUPPORTED) {
                        builder.appendText(line.substring(textStart, index))
                        builder.appendMedia(reference)
                        index = end
                        textStart = index
                        continue
                    }
                }
            }
            index += 1
        }
        builder.appendText(line.substring(textStart))
    }

    private data class MarkdownImage(val alt: String, val destination: String, val endExclusive: Int)

    private fun parseMarkdownImage(line: String, start: Int): MarkdownImage? {
        val altEnd = line.indexOf("](", start + 2)
        if (altEnd < 0) return null
        var depth = 1
        var index = altEnd + 2
        var escaped = false
        while (index < line.length) {
            val character = line[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '(') {
                depth += 1
            } else if (character == ')') {
                depth -= 1
                if (depth == 0) {
                    val body = line.substring(altEnd + 2, index).trim()
                    val destination = if (body.startsWith('<') && '>' in body) {
                        body.substring(1, body.indexOf('>'))
                    } else {
                        body.substringBefore(' ')
                    }
                    if (destination.isEmpty()) return null
                    return MarkdownImage(
                        alt = line.substring(start + 2, altEnd).trim(),
                        destination = unescape(destination),
                        endExclusive = index + 1,
                    )
                }
            }
            index += 1
        }
        return null
    }

    private fun inlineCodeRanges(line: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var index = 0
        while (index < line.length) {
            if (line[index] != '`') {
                index += 1
                continue
            }
            val run = backtickRun(line, index)
            val close = findBacktickRun(line, index + run, run)
            if (close < 0) {
                index += run
            } else {
                result += index until close + run
                index = close + run
            }
        }
        return result
    }

    private fun backtickRun(line: String, start: Int): Int {
        var end = start
        while (end < line.length && line[end] == '`') end += 1
        return end - start
    }

    private fun findBacktickRun(line: String, start: Int, length: Int): Int {
        var index = start
        while (index < line.length) {
            if (line[index] == '`' && backtickRun(line, index) == length) return index
            index += 1
        }
        return -1
    }

    private fun fenceMarker(line: String): Char? {
        val trimmed = line.dropWhile(Char::isWhitespace)
        return when {
            trimmed.startsWith("```") -> '`'
            trimmed.startsWith("~~~") -> '~'
            else -> null
        }
    }

    private fun referenceEnd(line: String, start: Int): Int {
        var end = start
        while (end < line.length && !line[end].isWhitespace() && line[end] !in setOf('<', '>', '"')) end += 1
        return end
    }

    private fun isBareFileUrl(line: String, index: Int): Boolean =
        line.startsWith("file://", index, ignoreCase = true) && (index == 0 || line[index - 1].isWhitespace())

    private fun unescape(value: String): String = buildString(value.length) {
        var escaped = false
        value.forEach { character ->
            if (escaped) {
                append(character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else {
                append(character)
            }
        }
        if (escaped) append('\\')
    }

    private fun mayContainMedia(markdown: String): Boolean =
        "![" in markdown || "MEDIA:" in markdown || "file://" in markdown.lowercase(Locale.ROOT)

    private class SegmentBuilder {
        private val segments = mutableListOf<TranscriptMediaSegment>()
        private val pending = StringBuilder()

        fun appendText(text: String) {
            pending.append(text)
        }

        fun appendMedia(reference: TranscriptMediaReference) {
            flushText()
            segments += TranscriptMediaSegment.Media(reference)
        }

        fun build(): List<TranscriptMediaSegment> {
            flushText()
            return segments
        }

        private fun flushText() {
            if (pending.isNotEmpty()) {
                segments += TranscriptMediaSegment.Text(pending.toString())
                pending.clear()
            }
        }
    }
}
