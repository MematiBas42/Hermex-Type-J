package io.github.jcartu.hermex.net.hermes

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory store for Hermes dashboard session tokens (`X-Hermes-Session-Token`).
 * Used across [HermesConnection] and [io.github.jcartu.hermex.net.HermesApi] so API calls
 * to the dashboard carry the required session token without requiring manual credentials.
 */
object HermesSessionTokenStore {
    private val tokens = ConcurrentHashMap<String, String>()

    private fun originKey(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

    fun get(url: HttpUrl): String? =
        tokens[originKey(url)] ?: tokens["${url.host}:${url.port}"] ?: tokens[url.host]

    fun get(originOrHost: String): String? = tokens[originOrHost]

    fun set(url: HttpUrl, token: String) {
        tokens[originKey(url)] = token
        tokens["${url.host}:${url.port}"] = token
        tokens[url.host] = token
    }

    fun set(originOrAddress: String, token: String) {
        tokens[originOrAddress] = token
        val parsed = originOrAddress.toHttpUrlOrNull()
        parsed?.let { url ->
            set(url, token)
        }
    }

    fun remove(url: HttpUrl) {
        tokens.remove(originKey(url))
        tokens.remove("${url.host}:${url.port}")
        tokens.remove(url.host)
    }

    fun remove(originOrAddress: String) {
        tokens.remove(originOrAddress)
        val parsed = originOrAddress.toHttpUrlOrNull()
        parsed?.let { url ->
            remove(url)
        }
    }

    fun clear() {
        tokens.clear()
    }
}
