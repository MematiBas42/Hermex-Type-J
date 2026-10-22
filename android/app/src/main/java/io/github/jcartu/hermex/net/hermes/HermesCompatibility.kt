package io.github.jcartu.hermex.net.hermes

/**
 * Port of `Networking/Hermes/HermesCompatibility.swift`: the hermes-agent releases Hermex
 * signs in to. `HermesConnection` refuses a host older than [MINIMUM_VERSION] before any
 * password goes out; anything newer, tested or not (the user's 0.22.2 included), connects
 * without a warning (#626).
 */
object HermesCompatibility {
    /** The oldest release that publishes the gateway contract Hermex is built on. */
    const val MINIMUM_VERSION = "0.21.3"

    /** The release Hermex was validated against (line 2 of `HERMES_AGENT_TESTED_SHA`). */
    const val TESTED_VERSION = "0.21.5"

    /**
     * Whether a host reporting [version] may sign in. A missing or unreadable version counts
     * as supported: the pin always reports one, so its absence means a proxy or a fork, not
     * an old host.
     */
    fun isSupported(version: String?): Boolean {
        val reported = release(version) ?: return true
        val minimum = release(MINIMUM_VERSION) ?: return true
        return !reported.lexicographicallyPrecedes(minimum)
    }

    /**
     * The leading `MAJOR.MINOR.PATCH` of [version] as numbers, so a canary
     * (`0.21.4+canary…`) reads as its base release. Null when missing, partial or unreadable.
     */
    fun release(version: String?): List<Int>? {
        if (version == null) return null
        val numbers = version
            .takeWhile { it in '0'..'9' || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        return if (numbers.size >= 3) numbers.take(3) else null
    }

    /** `lexicographicallyPrecedes` — element-wise comparison, shorter-prefix first. */
    fun List<Int>.lexicographicallyPrecedes(other: List<Int>): Boolean {
        for (index in 0 until minOf(size, other.size)) {
            if (this[index] < other[index]) return true
            if (this[index] > other[index]) return false
        }
        return size < other.size
    }
}
