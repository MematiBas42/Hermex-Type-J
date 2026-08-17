package io.github.jcartu.hermex.ui.feature.workspace

import java.util.Locale

/**
 * Decimal byte formatting matching iOS `ByteCountFormatter` (.file style):
 * 1000-based units, at most one fraction digit, no trailing ".0".
 */
fun formatByteCount(bytes: Long): String {
    if (bytes < 0) return "0 bytes"
    if (bytes < 1000) return if (bytes == 1L) "1 byte" else "$bytes bytes"
    var value = bytes.toDouble()
    val units = listOf("KB", "MB", "GB", "TB")
    var unitIndex = -1
    while (value >= 1000 && unitIndex < units.size - 1) {
        value /= 1000
        unitIndex++
    }
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.floor(rounded)) {
        String.format(Locale.US, "%.0f %s", rounded, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", rounded, units[unitIndex])
    }
}

/**
 * Compact relative-time text for epoch-second timestamps, mirroring the iOS
 * `Text(date, style: .relative) + " ago"` labels ("5m ago", "3h ago").
 */
fun relativeAgo(epochSeconds: Double, nowMillis: Long = System.currentTimeMillis()): String {
    val seconds = nowMillis / 1000.0 - epochSeconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${(seconds / 60).toInt()}m ago"
        seconds < 86_400 -> "${(seconds / 3_600).toInt()}h ago"
        seconds < 604_800 -> "${(seconds / 86_400).toInt()}d ago"
        else -> "${(seconds / 604_800).toInt()}w ago"
    }
}
