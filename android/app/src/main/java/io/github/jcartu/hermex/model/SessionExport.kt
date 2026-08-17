package io.github.jcartu.hermex.model

/**
 * Port of the export types in `Networking/APIClient+SessionExport.swift`.
 *
 * Formats accepted by `GET /api/session/export?format=…`. The server defaults
 * to JSON when the param is absent, but the app always sends it explicitly.
 * HTML is the self-contained human-readable transcript (best for sharing);
 * JSON is the machine-readable session dump (pairs with a future import).
 */
enum class SessionExportFormat(val rawValue: String) {
    html("html"),
    json("json"),
    ;

    val fileExtension: String get() = rawValue
}

/**
 * A downloaded session export: the raw file bytes plus the filename the share
 * sheet should offer (never "download.bin").
 */
class SessionExportFile(
    val data: ByteArray,
    val filename: String,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionExportFile && filename == other.filename && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * filename.hashCode() + data.contentHashCode()

    override fun toString(): String = "SessionExportFile(filename=$filename, ${data.size} bytes)"

    companion object {
        /**
         * Derives the filename to offer in the share sheet.
         *
         * Preference order:
         * 1. `filename="…"` (or unquoted `filename=…`) from `Content-Disposition`.
         * 2. Sanitized session title + the format's extension.
         * 3. `hermes-<session-id>.<ext>` (mirrors the upstream server's own name).
         */
        fun filename(
            contentDisposition: String?,
            fallbackTitle: String?,
            sessionId: String,
            format: SessionExportFormat,
        ): String {
            filenameParameter(contentDisposition)
                ?.let(::sanitizedFilename)
                ?.let { return it }

            fallbackTitle
                ?.let(::sanitizedFilenameStem)
                ?.let { return "$it.${format.fileExtension}" }

            val sanitizedId = sanitizedFilenameStem(sessionId) ?: "session"
            return "hermes-$sanitizedId.${format.fileExtension}"
        }

        /**
         * Extracts the `filename` parameter value from a `Content-Disposition`
         * header (quoted or bare token). Intentionally does not implement the
         * RFC 5987 `filename*=` extended form — upstream never sends it, and
         * the sanitized fallback covers any server that does.
         */
        private fun filenameParameter(contentDisposition: String?): String? {
            if (contentDisposition == null) return null

            for (parameter in contentDisposition.split(";").drop(1)) {
                val trimmed = parameter.trim()
                val separatorIndex = trimmed.indexOf('=')
                if (separatorIndex < 0) continue

                val key = trimmed.substring(0, separatorIndex).trim().lowercase()
                if (key != "filename") continue

                var value = trimmed.substring(separatorIndex + 1).trim()
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                }
                return value.ifEmpty { null }
            }

            return null
        }

        /**
         * Keeps only the last path component and strips characters that are
         * unsafe in filenames, so a hostile/buggy header can't escape the temp
         * directory or produce an unusable name.
         */
        private fun sanitizedFilename(raw: String): String? {
            val lastComponent = raw
                .replace("\\", "/")
                .split("/")
                .lastOrNull { it.isNotEmpty() }
                ?: raw

            val cleaned = replacingUnsafeFilenameCharacters(lastComponent)
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
            return cleaned
        }

        /**
         * Sanitizes free text (session title / ID) into a filename stem, or
         * null when nothing usable remains.
         */
        private fun sanitizedFilenameStem(raw: String): String? {
            val cleaned = replacingUnsafeFilenameCharacters(raw)
            if (cleaned.isEmpty()) return null
            return cleaned.take(80)
        }

        private fun replacingUnsafeFilenameCharacters(raw: String): String {
            val forbidden = setOf('/', '\\', ':', '"', '<', '>', '|', '?', '*')
            val replaced = raw.map { char ->
                if (char in forbidden || char.isISOControl()) ' ' else char
            }.joinToString("")

            // Collapse whitespace runs and trim so titles like "  a  /  b  "
            // come out as "a b" rather than "a    b".
            return replaced
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }
    }
}
