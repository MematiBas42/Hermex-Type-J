package io.github.jcartu.hermex.model

// Port of `AttachmentAudioDetection.swift`.

/**
 * Pure, testable audio-attachment detection shared by the chat bubble and the
 * full-screen attachment preview. Mirrors the image-detection rules used by
 * `GridAttachmentCell.inferredIsImage` / `ChatAttachmentPreviewItem.inferredIsImage`.
 */
object AttachmentAudioDetection {
    /**
     * File extensions we treat as audio. `AVAudioPlayer` natively decodes the
     * first group (m4a/mp3/wav/aac/caf); ogg/oga/opus/flac are still detected
     * as audio so they surface an audio player with a graceful "can't play"
     * state instead of a dead file chip.
     */
    val audioExtensions: Set<String> = setOf(
        "m4a", "mp3", "wav", "aac", "caf", "ogg", "oga", "opus", "flac",
    )

    /**
     * Audio when the attachment is *not* an image and either its MIME type
     * starts with `audio/` or its filename carries a known audio extension.
     * The explicit `isImage == true` server flag always wins, so an image is
     * never misclassified as audio.
     */
    fun isAudio(isImage: Boolean?, mime: String?, name: String?, path: String?): Boolean {
        if (isImage == true) return false

        if (mime?.lowercase()?.startsWith("audio/") == true) {
            return true
        }

        // Check the display name first, then fall back to the path: a human
        // display name like "Voice note" carries no extension even when the
        // path ends in `.m4a`, so a single `name ?? path` candidate would miss it.
        for (candidate in listOf(name, path)) {
            val ext = pathExtension(candidate ?: "").lowercase()
            if (ext in audioExtensions) return true
        }
        return false
    }
}

/**
 * Foundation `URL(fileURLWithPath:).pathExtension` semantics with plain string
 * ops: the extension of the last path component (trailing slashes collapse),
 * empty when the component has no dot or only a leading dot (hidden file).
 */
private fun pathExtension(path: String): String {
    val component = path.trimEnd('/').substringAfterLast('/')
    val dot = component.lastIndexOf('.')
    if (dot <= 0) return ""
    return component.substring(dot + 1)
}

/**
 * Formats a playback offset/duration as `m:ss` (or `h:mm:ss` past an hour).
 * Non-finite or negative inputs clamp to `0:00` so the label stays monotonic
 * and never shows `NaN`.
 */
object AudioDurationFormatter {
    fun string(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return "0:00"

        val total = kotlin.math.floor(seconds).toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60

        return if (hours > 0) {
            "$hours:" + minutes.toString().padStart(2, '0') + ":" + secs.toString().padStart(2, '0')
        } else {
            "$minutes:" + secs.toString().padStart(2, '0')
        }
    }
}

/** Whether this attachment should render as a playable audio clip. */
val MessageAttachment.inferredIsAudio: Boolean
    get() = AttachmentAudioDetection.isAudio(isImage = isImage, mime = mime, name = name, path = path)
