package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CustomHeader
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertEquals("https://server.tailnet.ts.net", AuthManager.normalizedServerUrl("server.tailnet.ts.net"))
    }

    @Test
    fun `defaults to plain http for localhost loopback and tailscale cgnat`() {
        assertEquals("http://localhost:8787", AuthManager.normalizedServerUrl("localhost:8787"))
        assertEquals("http://127.0.0.1:8787", AuthManager.normalizedServerUrl("127.0.0.1:8787"))
        assertEquals("http://100.64.0.1:8787", AuthManager.normalizedServerUrl("100.64.0.1:8787"))
        assertEquals("https://100.128.0.1", AuthManager.normalizedServerUrl("100.128.0.1"))
        assertEquals("https://192.168.1.10", AuthManager.normalizedServerUrl("192.168.1.10"))
    }

    @Test
    fun `strips path query fragment and www webui prefix`() {
        assertEquals("https://server.example.com", AuthManager.normalizedServerUrl("https://server.example.com/dash?x=1#frag"))
        assertEquals("https://webui.example.com", AuthManager.normalizedServerUrl("https://www.webui.example.com"))
        assertEquals("https://www.example.com", AuthManager.normalizedServerUrl("https://www.example.com"))
        assertEquals("https://server.example.com", AuthManager.normalizedServerUrl("  server.example.com  "))
    }

    @Test
    fun `rejects empty and unparseable input`() {
        assertThrows(ApiError.InvalidServerUrl::class.java) { AuthManager.normalizedServerUrl("   ") }
        assertThrows(ApiError.InvalidServerUrl::class.java) { AuthManager.normalizedServerUrl("ftp://server.example.com") }
        assertThrows(ApiError.InvalidServerUrl::class.java) { AuthManager.normalizedServerUrl("https://") }
    }

    @Test
    fun `unsupported sign-in message distinguishes oidc trusted-header and passkeys`() {
        assertNull(AuthManager.unsupportedSignInMessage(io.github.jcartu.hermex.model.AuthStatusResponse(authEnabled = true)))
        assertNull(
            AuthManager.unsupportedSignInMessage(
                io.github.jcartu.hermex.model.AuthStatusResponse(authEnabled = true, loggedIn = true, passwordAuthEnabled = false),
            ),
        )
        assertEquals(
            AuthManager.OIDC_ONLY_MESSAGE,
            AuthManager.unsupportedSignInMessage(
                io.github.jcartu.hermex.model.AuthStatusResponse(authEnabled = true, passwordAuthEnabled = false, oidcEnabled = true),
            ),
        )
        assertEquals(
            AuthManager.TRUSTED_AUTH_NOT_SIGNED_IN_MESSAGE,
            AuthManager.unsupportedSignInMessage(
                io.github.jcartu.hermex.model.AuthStatusResponse(authEnabled = true, passwordAuthEnabled = false, trustedAuthEnabled = true),
            ),
        )
        assertEquals(
            AuthManager.PASSKEY_ONLY_MESSAGE,
            AuthManager.unsupportedSignInMessage(
                io.github.jcartu.hermex.model.AuthStatusResponse(authEnabled = true, passwordAuthEnabled = false),
            ),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var fixture: AuthTestFixture

    private val serverA = "https://a.example.com"
    private val serverB = "https://b.example.com"

    @Before
    fun setUp() {
        fixture = AuthTestFixture(tmpFolder)
    }

    // MARK: Migration (#15)

    @Test
    fun `fresh install restores to unconfigured and registers nothing`() = runBlocking {
        val auth = fixture.manager()
        auth.restoreSavedServer()
        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertTrue(auth.servers.value.isEmpty())
        assertTrue(fixture.keychain.values.isEmpty())
    }

    @Test
    fun `legacy single server migrates losslessly into the registry and is idempotent`() = runBlocking {
        fixture.serverConfig.save("https://server.example.com/", "hunter2")
        fixture.customHeaders.replaceFor("https://server.example.com", listOf(CustomHeader("X-Api-Key", "secret")))

        val auth = fixture.manager()
        auth.restoreSavedServer()

        val state = auth.state.value
        assertTrue(state is AuthState.LoggedIn)
        val account = (state as AuthState.LoggedIn).account
        assertEquals("https://server.example.com", account.id)
        assertEquals(ServerKind.WEBUI, account.kind)
        assertEquals(listOf(account), auth.servers.value)
        assertEquals("hunter2", fixture.credentials.password(account.id))
        // The projection every per-server consumer reads still names the server.
        assertEquals("https://server.example.com", fixture.serverConfig.current().baseUrl)
        assertEquals("hunter2", fixture.serverConfig.current().password)
        // Its headers were activated before any request could be built.
        assertEquals(listOf(CustomHeader("X-Api-Key", "secret")), fixture.runtimeHeaders.snapshot())

        // A second launch (fresh registry from the same blob) changes nothing.
        val second = fixture.manager()
        second.restoreSavedServer()
        assertEquals(1, second.servers.value.size)
        assertEquals(account.id, second.servers.value.single().id)
        assertEquals(account.createdAt, second.servers.value.single().createdAt, 0.0)
        assertEquals("hunter2", fixture.credentials.password(account.id))
        assertTrue(second.state.value is AuthState.LoggedIn)
    }

    @Test
    fun `migration keeps a per-server password already written`() = runBlocking {
        fixture.serverConfig.save("https://server.example.com", "legacy")
        fixture.credentials.save("https://server.example.com", "newer")

        val auth = fixture.manager()
        auth.restoreSavedServer()

        assertEquals("newer", fixture.credentials.password("https://server.example.com"))
        assertEquals("newer", fixture.serverConfig.current().password)
    }

    // MARK: Configure / add

    @Test
    fun `configure with password logs in persists per server and activates`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = true)
        fixture.stubLogin(serverA, "hunter2", ok = true)

        val auth = fixture.manager()
        auth.configure("a.example.com", "hunter2", listOf(CustomHeader("X-Api-Key", "a-token"), CustomHeader("", "")))

        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertNull(auth.lastErrorMessage.value)
        assertEquals(serverA, auth.activeServerID)
        assertEquals("hunter2", fixture.credentials.password(serverA))
        assertEquals(serverA, fixture.serverConfig.current().baseUrl)
        // The probe client was bound to the sanitized headers (nameless rows dropped).
        assertEquals(serverA to listOf(CustomHeader("X-Api-Key", "a-token")), fixture.clientRequests.first())
        assertEquals(listOf(CustomHeader("X-Api-Key", "a-token")), fixture.customHeaders.snapshotFor(serverA))
    }

    @Test
    fun `configure failures leave nothing behind`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = true)
        val auth = fixture.manager()

        auth.configure("a.example.com", "")
        assertEquals(AuthManager.EMPTY_PASSWORD_MESSAGE, auth.lastErrorMessage.value)
        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertTrue(auth.servers.value.isEmpty())
        assertEquals("", fixture.serverConfig.current().baseUrl)

        fixture.stubLogin(serverA, "wrong", ok = false)
        auth.configure("a.example.com", "wrong")
        assertTrue(auth.state.value is AuthState.LoggedOut)
        assertEquals(serverA, (auth.state.value as AuthState.LoggedOut).account.id)
        assertEquals(ApiError.Unauthorized.message, auth.lastErrorMessage.value)
        assertTrue(auth.servers.value.isEmpty())
        assertEquals("", fixture.credentials.password(serverA))
    }

    @Test
    fun `configure skips login when the proxy already signed the client in`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = true, passwordAuthEnabled = false, loggedIn = true)
        val auth = fixture.manager()

        auth.configure("a.example.com", "")

        assertTrue(auth.state.value is AuthState.LoggedIn)
        coVerify(exactly = 0) { fixture.client(serverA).login(any()) }
    }

    @Test
    fun `addServer refuses a configured address and never disturbs the active server on failure`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = true)
        fixture.stubLogin(serverA, "pw-a", ok = true)
        val auth = fixture.manager()
        auth.configure("a.example.com", "pw-a", listOf(CustomHeader("X-Api-Key", "a-token")))

        assertEquals(AddServerOutcome.Failed, auth.addServer("https://a.example.com/", ""))
        assertEquals(AuthManager.ALREADY_CONFIGURED_MESSAGE, auth.lastErrorMessage.value)

        coEvery { fixture.client(serverB).health() } throws ApiError.Network(RuntimeException("unreachable"))
        assertEquals(AddServerOutcome.Failed, auth.addServer("b.example.com", "pw-b", listOf(CustomHeader("X-Api-Key", "b-token"))))

        // Server A is still active with its own headers live; B left nothing.
        assertEquals(serverA, auth.activeServerID)
        assertEquals(listOf(CustomHeader("X-Api-Key", "a-token")), fixture.runtimeHeaders.snapshot())
        assertEquals(1, auth.servers.value.size)
        assertEquals("", fixture.credentials.password(serverB))
    }

    @Test
    fun `addServer reveals the password field then switches with isolated credentials`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = true)
        fixture.stubLogin(serverA, "pw-a", ok = true)
        fixture.stubHealthy(serverB, authEnabled = true)
        fixture.stubLogin(serverB, "pw-b", ok = true)
        val auth = fixture.manager()
        auth.configure("a.example.com", "pw-a", listOf(CustomHeader("X-Api-Key", "a-token")))

        assertEquals(AddServerOutcome.NeedsPassword, auth.addServer("b.example.com", ""))
        val outcome = auth.addServer("b.example.com", "pw-b", listOf(CustomHeader("X-Api-Key", "b-token")))

        assertTrue(outcome is AddServerOutcome.Added)
        assertEquals(serverB, auth.activeServerID)
        assertEquals(listOf(serverA, serverB), auth.servers.value.map { it.id })
        // Per-server credential isolation: A's secrets never resolve for B and vice versa.
        assertEquals("pw-a", fixture.credentials.password(serverA))
        assertEquals("pw-b", fixture.credentials.password(serverB))
        assertEquals(listOf(CustomHeader("X-Api-Key", "a-token")), fixture.customHeaders.snapshotFor(serverA))
        assertEquals(listOf(CustomHeader("X-Api-Key", "b-token")), fixture.customHeaders.snapshotFor(serverB))
        assertEquals(listOf(CustomHeader("X-Api-Key", "b-token")), fixture.runtimeHeaders.snapshot())
        assertEquals(serverB, fixture.serverConfig.current().baseUrl)
        assertEquals("pw-b", fixture.serverConfig.current().password)
        // B's probe never carried A's headers.
        assertTrue(fixture.clientRequests.filter { it.first == serverB }.none { CustomHeader("X-Api-Key", "a-token") in it.second })
    }

    // MARK: Switch / remove / sign out

    private suspend fun twoServers(): AuthManager {
        fixture.stubHealthy(serverA, authEnabled = true)
        fixture.stubLogin(serverA, "pw-a", ok = true)
        fixture.stubHealthy(serverB, authEnabled = true)
        fixture.stubLogin(serverB, "pw-b", ok = true)
        val auth = fixture.manager()
        auth.configure("a.example.com", "pw-a", listOf(CustomHeader("X-Api-Key", "a-token")))
        auth.addServer("b.example.com", "pw-b", listOf(CustomHeader("X-Api-Key", "b-token")))
        return auth
    }

    @Test
    fun `switching the active server swaps the projection headers and identity mirror`() = runBlocking {
        val auth = twoServers()
        val accountA = auth.servers.value.first { it.id == serverA }
        auth.updateServerIdentity(accountA, "Alpha", "AL", "#FF3B30")

        auth.switchActiveServer(accountA)

        assertEquals(serverA, auth.activeServerID)
        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertEquals(serverA, fixture.serverConfig.current().baseUrl)
        assertEquals("pw-a", fixture.serverConfig.current().password)
        assertEquals(listOf(CustomHeader("X-Api-Key", "a-token")), fixture.runtimeHeaders.snapshot())
        assertEquals("Alpha", fixture.identityDefaults.values["sessionIdentity.displayName"])
        assertEquals("#FF3B30", fixture.identityDefaults.values["headerLogoColorHex"])
        // No round trip on a switch: nothing was probed.
        coVerify(exactly = 1) { fixture.client(serverA).health() }
    }

    @Test
    fun `removing a non-active server drops only its artifacts`() = runBlocking {
        val auth = twoServers()
        val accountA = auth.servers.value.first { it.id == serverA }

        auth.removeServer(accountA)

        assertEquals(listOf(serverB), auth.servers.value.map { it.id })
        assertEquals(serverB, auth.activeServerID)
        assertEquals("", fixture.credentials.password(serverA))
        assertEquals("pw-b", fixture.credentials.password(serverB))
        assertTrue(fixture.customHeaders.snapshotFor(serverA).isEmpty())
        assertEquals(listOf(CustomHeader("X-Api-Key", "b-token")), fixture.customHeaders.snapshotFor(serverB))
        assertEquals(listOf(serverA), fixture.cookies.cleared)
        coVerify(exactly = 0) { fixture.client(serverA).logout() }
    }

    @Test
    fun `signing out of the active server logs out server side and advances to the next`() = runBlocking {
        val auth = twoServers()

        auth.signOut()

        coVerify(exactly = 1) { fixture.client(serverB).logout() }
        assertEquals(listOf(serverA), auth.servers.value.map { it.id })
        assertEquals(serverA, auth.activeServerID)
        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertEquals("pw-a", fixture.serverConfig.current().password)
        assertEquals(listOf(CustomHeader("X-Api-Key", "a-token")), fixture.runtimeHeaders.snapshot())
        assertEquals(listOf(serverB), fixture.cookies.cleared)
    }

    @Test
    fun `signing out of the last server returns to onboarding without blocking on a slow server`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = false)
        coEvery { fixture.client(serverA).logout() } coAnswers {
            delay(60_000)
            io.github.jcartu.hermex.model.LoginResponse(ok = true)
        }
        val auth = fixture.manager(logoutTimeoutMillis = 50)
        auth.configure("a.example.com", "")

        auth.signOut()

        assertEquals(AuthState.Unconfigured, auth.state.value)
        assertTrue(auth.servers.value.isEmpty())
        assertEquals("", fixture.serverConfig.current().baseUrl)
        assertTrue(fixture.runtimeHeaders.snapshot().isEmpty())
    }

    // MARK: Expiry

    @Test
    fun `a 401 demotes the active server clears only its cookies and keeps its saved state`() = runBlocking {
        val auth = twoServers()

        auth.handleApiError(ApiError.Unauthorized)

        val state = auth.state.value
        assertTrue(state is AuthState.LoggedOut)
        assertEquals(serverB, (state as AuthState.LoggedOut).account.id)
        assertEquals(AuthManager.SESSION_EXPIRED_MESSAGE, auth.lastErrorMessage.value)
        assertEquals(listOf(serverB), fixture.cookies.cleared)
        assertEquals(2, auth.servers.value.size)
        assertEquals("pw-b", fixture.credentials.password(serverB))
        assertEquals(listOf(CustomHeader("X-Api-Key", "b-token")), fixture.customHeaders.snapshotFor(serverB))

        auth.handleApiError(ApiError.Http(500, null))
        assertTrue(auth.state.value is AuthState.LoggedOut)

        // Re-login is a one-field affair and re-activates the same entry.
        auth.configure(serverB, "pw-b", fixture.customHeaders.snapshotFor(serverB))
        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertEquals(2, auth.servers.value.size)
    }

    @Test
    fun `a hermes server is never signed out by a webui 401 and keeps its entry on sign out`() = runBlocking {
        val registry = fixture.registry()
        registry.activate("https://hermes.example.com:9119", kind = ServerKind.HERMES, serverVersion = "0.22.2")
        val auth = fixture.manager(registry)
        auth.restoreSavedServer()

        // No saved sign-in record yet: its sign-in form shows, with no webui call made.
        assertTrue(auth.state.value is AuthState.LoggedOut)
        assertTrue(fixture.clientRequests.isEmpty())

        auth.handleApiError(ApiError.Unauthorized)
        assertNull(auth.lastErrorMessage.value)

        auth.signOut()
        assertEquals(1, auth.servers.value.size)
        assertTrue(auth.state.value is AuthState.LoggedOut)
        assertTrue(fixture.cookies.cleared.isEmpty())
    }
}
