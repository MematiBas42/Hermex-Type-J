package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotAddressError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * Port of `BotConnection.address(_:)` (`Features/Bots/BotConnection.swift`): parses the
 * connection form's text into the dashboard root every request is built from, so a saved
 * address never has a path, query or fragment. A pasted link is reduced to its root:
 * `ws`/`wss` become `http`/`https`, the query and fragment are dropped, and a path starting
 * with a [DASHBOARD_PATHS] segment is cleared. Any other path throws [BotAddressError.Path];
 * credentials, other schemes and bad hosts or ports throw [BotAddressError.Invalid]. A missing
 * scheme becomes HTTPS, or HTTP for private and local hosts (never as a retry after TLS fails).
 */
object HermesAddress {

    /**
     * First path segments a browser, config file or curl command adds to the dashboard's
     * root: the pages the root redirects to, sign-in, and the API and gateway socket.
     */
    private val DASHBOARD_PATHS = setOf("login", "auth", "api", "chat", "sessions")

    @Throws(BotAddressError::class)
    fun parse(text: String): HttpUrl {
        var value = text.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) throw BotAddressError.Invalid
        val hasScheme = value.contains("://")
        if (!hasScheme) {
            // A bare IPv6 literal needs brackets; bracketed literals may include a port.
            if (isIPv6Literal(value)) value = "[$value]"
            value = "https://$value"
        }
        val parts = try {
            URI(value)
        } catch (error: URISyntaxException) {
            throw BotAddressError.Invalid
        }
        val scheme = when (parts.scheme?.lowercase()) {
            "ws" -> "http"
            "wss" -> "https"
            "http" -> "http"
            "https" -> "https"
            else -> throw BotAddressError.Invalid
        }
        val host = parts.host?.takeIf { it.isNotEmpty() } ?: throw BotAddressError.Invalid
        if (parts.rawUserInfo != null) throw BotAddressError.Invalid
        val port = parts.port
        if (port != -1 && port !in 1..65535) throw BotAddressError.Invalid
        val plainHost = host.trim('[', ']')
        // Something that is only digits and dots must be a well-formed IPv4 address.
        if (plainHost.all { it.isDigit() || it == '.' } && parseIPv4(plainHost) == null) throw BotAddressError.Invalid

        var path = parts.rawPath ?: ""
        while (path.endsWith("/")) path = path.dropLast(1)
        val first = path.split("/").firstOrNull { it.isNotEmpty() }
        if (first != null && first.lowercase() !in DASHBOARD_PATHS) throw BotAddressError.Path(path)

        val finalScheme = if (!hasScheme && defaultsToHTTP(plainHost.lowercase())) "http" else scheme
        val builder = HttpUrl.Builder()
            .scheme(finalScheme)
            .host(host.lowercase())
        if (port != -1) builder.port(port)
        return builder.build()
    }

    /**
     * `defaultsToHTTP(_:)` — infer a scheme only for an omitted one. Private and loopback
     * IPv4 ranges (10/8, 127/8, 172.16/12, 192.168/16, 169.254/16, Tailscale's 100.64/10),
     * their IPv4-mapped IPv6 forms, IPv6 loopback, unique-local and link-local, `localhost`,
     * `.localhost`, `.local` and single-label names.
     */
    fun defaultsToHTTP(host: String): Boolean {
        parseIPv4(host)?.let { return privateIPv4(it) }
        parseIPv6(host)?.let { bytes ->
            if (bytes.copyOfRange(0, 12).contentEquals(ByteArray(10) + byteArrayOf(-1, -1))) {
                return privateIPv4(bytes.copyOfRange(12, 16).map { it.toInt() and 0xff })
            }
            if (bytes.contentEquals(ByteArray(15) + byteArrayOf(1))) return true
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            return (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
        }
        return host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            (!host.contains(".") && !host.contains(":"))
    }

    private fun privateIPv4(octets: List<Int>): Boolean =
        octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 100 && octets[1] in 64..127)

    /** The four octets of a dotted-quad IPv4 address, else null. */
    private fun parseIPv4(text: String): List<Int>? {
        val parts = text.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
        return octets
    }

    private fun isIPv6Literal(text: String): Boolean = text.contains(":") && parseIPv6(text) != null

    /** The sixteen bytes of an IPv6 literal (without brackets or a zone), else null. */
    private fun parseIPv6(text: String): ByteArray? {
        val literal = text.trim('[', ']')
        if (!literal.contains(":") || literal.contains("%")) return null
        val halves = literal.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val items = part.split(":")
            val result = mutableListOf<Int>()
            for ((index, item) in items.withIndex()) {
                if (item.contains(".")) {
                    // An embedded IPv4 tail must be the last item.
                    if (index != items.lastIndex) return null
                    val v4 = parseIPv4(item) ?: return null
                    result += (v4[0] shl 8) or v4[1]
                    result += (v4[2] shl 8) or v4[3]
                } else {
                    if (item.isEmpty() || item.length > 4 || !item.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                    result += item.toInt(16)
                }
            }
            return result
        }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val total = head.size + tail.size
        val all: List<Int> = when {
            halves.size == 2 -> {
                if (total > 7) return null
                head + List(8 - total) { 0 } + tail
            }
            total == 8 -> head
            else -> return null
        }
        val bytes = ByteArray(16)
        for ((index, group) in all.withIndex()) {
            bytes[index * 2] = (group shr 8).toByte()
            bytes[index * 2 + 1] = (group and 0xff).toByte()
        }
        return bytes
    }

    /** The stored id of a Hermes server: its address as [parse] normalizes it (`url.absoluteString`). */
    fun serverID(address: HttpUrl): String = address.toString().trimEnd('/')

    /** `HttpUrl` of a stored server id or address string, or null when it isn't an HTTP(S) URL. */
    fun url(serverID: String): HttpUrl? = serverID.toHttpUrlOrNull()
}
