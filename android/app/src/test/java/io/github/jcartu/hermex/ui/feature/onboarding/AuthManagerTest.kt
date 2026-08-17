package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.model.HealthResponse
import io.github.jcartu.hermex.model.LoginResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthManagerNormalizationTest {

    @Test
    fun `defaults to https for public hosts`() {
        assertEquals(
            "https://server.tailnet.ts.net",
            AuthManager.normalizedServerUrl("server.tailnet.ts.net"),
        )
    }

    @Test
    fun `defaults to plain http for localhost and loopback`() {
        assertEquals("http://localhost:8787", AuthManager.normalizedServerUrl("localhost:8787"))
        assertEquals("http://127.0.0.1:8787", AuthManager.normalizedServerUrl("127.0.0.1:8787"))
    }

    @Test
    fun `defaults to plain http for tailscale cgnat range`() {
        assertEquals("http://100.64.0.1:8787", AuthManager.normalizedServerUrl("100.64.0.1:8787"))
        assertEquals("http://100.127.9.9", AuthManager.normalizedServerUrl("100.127.9.9"))
    }

    @Test
    fun `stays https outside the cgnat range`() {
        assertEquals("https://100.128.0.1", AuthManager.normalizedServerUrl("100.128.0.1"))
        assertEquals("https://100.63.0.1", AuthManager.normalizedServerUrl("100.63.0.1"))
        assertEquals("https://192.168.1.10", AuthManager.normalizedServerUrl("192.168.1.10"))
    }

    @Test
    fun `keeps an explicit scheme`() {
        assertEquals(
            "http://server.example.com",
            AuthManager.normalizedServerUrl("http://server.example.com"),
        )
    }

    @Test
    fun `strips path query and fragment`() {
        assertEquals(
            "https://server.example.com",
            AuthManager.normalizedServerUrl("https://server.example.com/dash?x=1#frag"),
        )
    }

    @Test
    fun `drops www prefix from webui hosts`() {
        assertEquals(
            "https://webui.example.com",
            AuthManager.normalizedServerUrl("https://www.webui.example.com"),
        )
        // Only the www.webui.* pattern is rewritten.
        assertEquals(
            "https://www.example.com",
            AuthManager.normalizedServerUrl("https://www.example.com"),
        )
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(
            "https://server.example.com",
            AuthManager.normalizedServerUrl("  server.example.com  "),
        )
    }

    @Test
    fun `rejects empty and unparseable input`() {
        assertThrows(ApiError.InvalidServerUrl::class.java) {
            AuthManager.normalizedServerUrl("   ")
        }
        assertThrows(ApiError.InvalidServerUrl::class.java) {
            AuthManager.normalizedServerUrl("ftp://server.example.com")
        }
        assertThrows(ApiError.InvalidServerUrl::class.java) {
            AuthManager.normalizedServerUrl("https://")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var repository: ServerConfigRepository
    private lateinit var api: HermesApi
    private var sessionResets = 0

    private class FakeCipher : StringCipher {
        override fun encrypt(plainText: String) = "enc:$plainText"
        override fun decrypt(cipherText: String) = cipherText.removePrefix("enc:")
    }

    @Before
    fun setUp() {
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val file = tmpFolder.newFile("auth_prefs.preferences_pb")
        repository = ServerConfigRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            FakeCipher(),
        )
        api = mockk()
        sessionResets = 0
    }

    private fun manager(logoutTimeoutMillis: Long = 5_000) = AuthManager(
        api = api,
        serverConfig = repository,
        sessionResetter = { sessionResets += 1 },
        logoutTimeoutMillis = logoutTimeoutMillis,
    )

    private fun stubHealthy(
        authEnabled: Boolean?,
        passwordAuthEnabled: Boolean? = null,
    ) {
        coEvery { api.health() } returns HealthResponse(status = "ok")
        coEvery { api.authStatus() } returns AuthStatusResponse(
            authEnabled = authEnabled,
            passwordAuthEnabled = passwordAuthEnabled,
        )
    }

    @Test
    fun `restore with no saved server is unconfigured`() = runBlocking {
        val auth = manager()
        auth.restoreSavedServer()
        assertEquals(AuthState.Unconfigured, auth.state.value)
    }

    @Test
    fun `restore with saved server is optimistically logged in`() = runBlocking {
        repository.save("https://server.example.com", "pw")
        val auth = manager()
        auth.restoreSavedServer()
        val state = auth.state.value
        assertTrue(state is AuthState.LoggedIn)
        assertEquals("https://server.example.com", (state as AuthState.LoggedIn).server.baseUrl)
    }

    @Test
    fun `configure with password logs in and persists`() = runBlocking {
        stubHealthy(authEnabled = true)
        coEvery { api.login("hunter2") } returns LoginResponse(ok = true)

        val auth = manager()
        auth.configure("server.example.com", "hunter2")

        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertNull(auth.lastErrorMessage.value)
        val saved = repository.current()
        assertEquals("https://server.example.com", saved.baseUrl)
        assertEquals("hunter2", saved.password)
    }

    @Test
    fun `configure without auth skips login`() = runBlocking {
        stubHealthy(authEnabled = false)

        val auth = manager()
        auth.configure("server.example.com", "")

        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertNull(auth.lastErrorMessage.value)
        coVerify(exactly = 0) { api.login(any()) }
        assertEquals("https://server.example.com", repository.current().baseUrl)
    }

    @Test
    fun `configure requires a password when auth is enabled`() = runBlocking {
        stubHealthy(authEnabled = true)

        val auth = manager()
        auth.configure("server.example.com", "")

        assertEquals(AuthManager.EMPTY_PASSWORD_MESSAGE, auth.lastErrorMessage.value)
        assertEquals(AuthState.Unconfigured, auth.state.value)
        coVerify(exactly = 0) { api.login(any()) }
        // Nothing persisted on the failure path.
        assertEquals("", repository.current().baseUrl)
    }

    @Test
    fun `configure surfaces passkey-only servers`() = runBlocking {
        stubHealthy(authEnabled = true, passwordAuthEnabled = false)

        val auth = manager()
        auth.configure("server.example.com", "whatever")

        assertEquals(AuthManager.PASSKEY_ONLY_MESSAGE, auth.lastErrorMessage.value)
        coVerify(exactly = 0) { api.login(any()) }
        assertEquals("", repository.current().baseUrl)
    }

    @Test
    fun `configure treats a rejected login as logged out`() = runBlocking {
        stubHealthy(authEnabled = true)
        coEvery { api.login("wrong") } returns LoginResponse(ok = false)

        val auth = manager()
        auth.configure("server.example.com", "wrong")

        val state = auth.state.value
        assertTrue(state is AuthState.LoggedOut)
        assertEquals("https://server.example.com", (state as AuthState.LoggedOut).saved.baseUrl)
        assertEquals(ApiError.Unauthorized.message, auth.lastErrorMessage.value)
        // Rejected credentials are not persisted.
        assertEquals("", repository.current().baseUrl)
    }

    @Test
    fun `configure rejects an unexpected health status`() = runBlocking {
        coEvery { api.health() } returns HealthResponse(status = "degraded")

        val auth = manager()
        auth.configure("server.example.com", "pw")

        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertTrue(auth.lastErrorMessage.value.orEmpty().isNotEmpty())
        assertEquals("", repository.current().baseUrl)
    }

    @Test
    fun `configure keeps the previous server when the new one fails`() = runBlocking {
        repository.save("https://old.example.com", "oldpw")
        coEvery { api.health() } throws ApiError.Network(RuntimeException("unreachable"))

        val auth = manager()
        auth.configure("new.example.com", "newpw")

        val saved = repository.current()
        assertEquals("https://old.example.com", saved.baseUrl)
        assertEquals("oldpw", saved.password)
    }

    @Test
    fun `testConnection probes without persisting`() = runBlocking {
        stubHealthy(authEnabled = true)

        val auth = manager()
        val status = auth.testConnection("server.example.com")

        assertEquals(true, status.authEnabled)
        assertEquals("", repository.current().baseUrl)
    }

    @Test
    fun `testConnection restores the previous config on failure`() = runBlocking {
        repository.save("https://old.example.com", "oldpw")
        coEvery { api.health() } throws ApiError.Http(500, "boom")

        val auth = manager()
        assertThrows(ApiError.Http::class.java) {
            runBlocking { auth.testConnection("new.example.com") }
        }

        assertEquals("https://old.example.com", repository.current().baseUrl)
    }

    @Test
    fun `handleApiError demotes a 401 to logged out and keeps the server`() = runBlocking {
        repository.save("https://server.example.com", "pw")
        val auth = manager()
        auth.restoreSavedServer()

        auth.handleApiError(ApiError.Unauthorized)

        val state = auth.state.value
        assertTrue(state is AuthState.LoggedOut)
        assertEquals("https://server.example.com", (state as AuthState.LoggedOut).saved.baseUrl)
        assertEquals(AuthManager.SESSION_EXPIRED_MESSAGE, auth.lastErrorMessage.value)
        assertEquals(0, sessionResets)
        // The saved config survives so re-login is a one-field affair.
        assertEquals("https://server.example.com", repository.current().baseUrl)
    }

    @Test
    fun `handleApiError ignores non-401 errors`() = runBlocking {
        repository.save("https://server.example.com", "pw")
        val auth = manager()
        auth.restoreSavedServer()

        auth.handleApiError(ApiError.Http(500, null))

        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertNull(auth.lastErrorMessage.value)
    }

    @Test
    fun `signOut logs out server side and resets locally`() = runBlocking {
        repository.save("https://server.example.com", "pw")
        coEvery { api.logout() } returns LoginResponse(ok = true)

        val auth = manager()
        auth.restoreSavedServer()
        auth.signOut()

        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertEquals(1, sessionResets)
        coVerify(exactly = 1) { api.logout() }
    }

    @Test
    fun `signOut never blocks on a slow server`() = runBlocking {
        repository.save("https://server.example.com", "pw")
        coEvery { api.logout() } coAnswers {
            delay(60_000)
            LoginResponse(ok = true)
        }

        val auth = manager(logoutTimeoutMillis = 50)
        auth.restoreSavedServer()
        auth.signOut()

        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertEquals(1, sessionResets)
    }

    @Test
    fun `signOut while logged out skips the server call`() = runBlocking {
        val auth = manager()
        auth.signOut()

        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertEquals(1, sessionResets)
        coVerify(exactly = 0) { api.logout() }
        assertFalse(repository.current().isConfigured)
    }
}
