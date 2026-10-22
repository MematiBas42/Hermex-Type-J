package io.github.jcartu.hermex.ui.feature.tasks

/**
 * Cron run transcripts arrive with the shell's terminal control bytes intact
 * (`\u001b[0;32m`). The UI has no notion of them and renders the raw bytes as
 * garbage, so every surface that shows server-supplied run text — the Tasks
 * row, Task Detail's error line, the recent-run output — puts it through
 * [String.strippingANSIEscapes] first.
 * Port of `Models/ANSIEscapes.swift`.
 */
fun String.strippingANSIEscapes(): String {
    if (!contains('\u001B')) return this

    val scalars = toList()
    val output = StringBuilder(scalars.size)

    var index = 0
    while (index < scalars.size) {
        val char = scalars[index]
        if (char != '\u001B') {
            output.append(char)
            index += 1
            continue
        }

        index += 1
        if (index >= scalars.size) break

        when (scalars[index]) {
            '[' -> {
                // CSI: parameter and intermediate bytes, then one final byte.
                index += 1
                while (index < scalars.size && scalars[index].code !in 0x40..0x7E) {
                    index += 1
                }
                if (index < scalars.size) index += 1
            }
            ']' -> {
                // OSC: runs until BEL or the string terminator `ESC \`.
                index += 1
                while (index < scalars.size) {
                    if (scalars[index] == '\u0007') {
                        index += 1
                        break
                    }
                    if (scalars[index] == '\u001B' &&
                        index + 1 < scalars.size &&
                        scalars[index + 1] == '\\'
                    ) {
                        index += 2
                        break
                    }
                    index += 1
                }
            }
            else -> index += 1
        }
    }

    return output.toString()
}

/**
 * The first meaningful line of a multi-line server transcript, escapes stripped
 * and clipped, for places that have room for one line only. The full text stays
 * reachable on Task Detail.
 */
fun String.firstLineSummary(limit: Int = 120): String? {
    val stripped = strippingANSIEscapes()
    val line = stripped.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return null

    if (line.length <= limit) return line
    return line.take(limit).trim() + "…"
}
