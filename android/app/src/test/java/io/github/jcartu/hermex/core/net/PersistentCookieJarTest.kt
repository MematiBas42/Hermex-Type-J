package io.github.jcartu.hermex.core.net

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jcartu.hermex.core.prefs.CookieSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class PersistentCookieJarTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: CookieSerializer
    private val url = "https://hermes.example.com/api/auth/login".toHttpUrl()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file: File = tmpFolder.newFile("cookies.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = CookieSerializer(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `saved cookie is returned for matching url`() {
        val jar = PersistentCookieJar(store, scope)
        val cookie = Cookie.Builder()
            .name("hermes_session")
            .value("hmac-token")
            .domain("hermes.example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24))
            .build()

        jar.saveFromResponse(url, listOf(cookie))
        val loaded = jar.loadForRequest("https://hermes.example.com/api/sessions".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals("hermes_session", loaded.first().name)
        assertEquals("hmac-token", loaded.first().value)
    }

    @Test
    fun `cookies are not sent to other domains`() {
        val jar = PersistentCookieJar(store, scope)
        val cookie = Cookie.Builder()
            .name("hermes_session")
            .value("hmac-token")
            .domain("hermes.example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24))
            .build()
        jar.saveFromResponse(url, listOf(cookie))

        val loaded = jar.loadForRequest("https://evil.example.com/api/sessions".toHttpUrl())
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `cookies survive jar restart through persistence`() = runBlocking {
        val first = PersistentCookieJar(store, scope)
        val cookie = Cookie.Builder()
            .name("hermes_session")
            .value("hmac-token")
            .domain("hermes.example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24))
            .build()
        first.saveFromResponse(url, listOf(cookie))
        // Persistence write is async; poll the store until it lands (bounded).
        val deadline = System.currentTimeMillis() + 5_000
        while (store.current().isBlank() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val second = PersistentCookieJar(store, scope)
        val loaded = second.loadForRequest("https://hermes.example.com/api/sessions".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals("hermes_session", loaded.first().name)
        assertEquals("hmac-token", loaded.first().value)
    }

    @Test
    fun `clearAll drops everything`() {
        val jar = PersistentCookieJar(store, scope)
        val cookie = Cookie.Builder()
            .name("hermes_session")
            .value("hmac-token")
            .domain("hermes.example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24))
            .build()
        jar.saveFromResponse(url, listOf(cookie))

        jar.clearAll()

        assertTrue(jar.loadForRequest(url).isEmpty())
    }
}
