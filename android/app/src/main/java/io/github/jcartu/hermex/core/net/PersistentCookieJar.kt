package io.github.jcartu.hermex.core.net

import io.github.jcartu.hermex.core.prefs.CookieSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * CookieJar that keeps cookies in memory and persists them through
 * [CookieSerializer] so sessions survive process restarts. The upstream
 * hermes-webui server sets a single HMAC session cookie via /api/auth/login.
 *
 * On first access the store is loaded synchronously (cheap single DataStore
 * read against a tiny file); subsequent mutations are persisted async.
 */
class PersistentCookieJar(
    private val store: CookieSerializer,
    private val scope: CoroutineScope,
) : CookieJar {

    private val lock = Any()
    private val loaded: Boolean by lazy {
        runBlocking {
            val persisted = store.current()
            if (persisted.isBlank()) return@runBlocking
            synchronized(lock) {
                persisted.lineSequence().forEach { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2) {
                        runCatching {
                            Cookie.parse(parts[0].toHttpUrl(), parts[1])?.let { cookies.add(it) }
                        }
                    }
                }
            }
        }
        true
    }

    private val cookies = mutableSetOf<Cookie>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        loaded
        synchronized(lock) {
            val now = System.currentTimeMillis()
            return cookies.filter { it.matches(url) && it.expiresAt > now }
        }
    }

    override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) {
        loaded
        synchronized(lock) {
            cookies.removeAll { existing ->
                responseCookies.any { fresh ->
                    fresh.name == existing.name && fresh.domain == existing.domain && fresh.path == existing.path
                }
            }
            responseCookies.forEach { cookie ->
                if (cookie.expiresAt < System.currentTimeMillis()) {
                    // Expired cookie on the wire means "delete" — dropping matches.
                    cookies.removeIf { it.name == cookie.name && it.domain == cookie.domain }
                } else {
                    cookies.add(cookie)
                }
            }
        }
        scope.launch {
            store.save(serialize())
        }
    }

    fun clearAll() {
        loaded
        synchronized(lock) { cookies.clear() }
        scope.launch { store.clear() }
    }

    /**
     * Deletes only the cookies that would be sent to [url] — port of
     * `AuthManager.clearSessionCookies(for:)` (#16) — so signing out of or
     * expiring one server leaves other servers' cookies intact. Two servers
     * sharing a host but differing by port still share cookies (cookies are
     * not port-scoped), the documented #16 limitation.
     */
    fun clearFor(url: HttpUrl) {
        loaded
        synchronized(lock) { cookies.removeAll { it.matches(url) } }
        scope.launch { store.save(serialize()) }
    }

    private fun serialize(): String = synchronized(lock) {
        cookies.joinToString("\n") { cookie ->
            val url = "https://${cookie.domain}${cookie.path}".toHttpUrl()
            "$url\t${cookie}"
        }
    }
}
