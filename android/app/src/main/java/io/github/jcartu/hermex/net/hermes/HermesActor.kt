package io.github.jcartu.hermex.net.hermes

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The confinement Swift's `@MainActor` gives `HermesConnection`, `HermesGateway` and
 * `HermesConnections`: one dispatcher on which no two of their coroutines ever run at once,
 * so their state needs no locks. Network I/O suspends on it rather than blocking it, and
 * callbacks (`onEvent`, `onDisconnect`, `onLoginRejected`) are invoked on it; a screen hops
 * to Main itself.
 */
object HermesActor {
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}

/**
 * The ephemeral cookie jar of one `HermesConnection` (`URLSessionConfiguration.ephemeral`):
 * the host's session cookie lives here, in memory, for this connection alone, so nothing is
 * pooled by hostname and two configured servers on one host get two jars.
 */
class HermesCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = ArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cookies.removeAll { it.expiresAt < now }
            cookies.filter { it.matches(url) }
        }
    }

    fun clear() {
        synchronized(lock) { cookies.clear() }
    }
}
