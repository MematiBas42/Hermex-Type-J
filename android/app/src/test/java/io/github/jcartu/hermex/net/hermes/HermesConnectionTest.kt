package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted Hermes dashboard: the public status, the password gate, the identity read and
 * the gated reads a sign-in proves itself with, routed by path so each test scripts only
 * what it changes.
 */
internal class FakeHermesHost : Dispatcher() {
    var status: String = PASSWORD_STATUS
    val logins = AtomicInteger(0)
    val profilesActive = ConcurrentLinkedQueue<MockResponse>()
    val requests = ConcurrentLinkedQueue<RecordedRequest>()
    var page: String = "<html><script>window.__HERMES_SESSION_TOKEN__=\"page-token-1\";</script></html>"
    var wsUpgrades: ((Int) -> MockResponse)? = null
    private val upgrades = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.path ?: "/"
        return when {
            path.startsWith("/api/status") -> json(200, status)
            path == "/" -> MockResponse().setResponseCode(200).setBody(page).addHeader("Content-Type", "text/html")
            path.startsWith("/auth/password-login") -> {
                logins.incrementAndGet()
                json(200, """{"ok":true}""").addHeader("Set-Cookie", "hermes_session=cookie-1; Path=/")
            }
            path.startsWith("/api/auth/me") -> json(200, """{"provider":"basic","user_id":"josh"}""")
            path.startsWith("/api/auth/ws-ticket") -> json(200, """{"ticket":"ticket-${logins.get()}"}""")
            path.startsWith("/api/profiles/active") -> profilesActive.poll() ?: json(200, """{"active":"default","current":"default"}""")
            path.startsWith("/api/ws") -> wsUpgrades?.invoke(upgrades.incrementAndGet()) ?: MockResponse().setResponseCode(404)
            else -> json(404, """{"detail":"No such API endpoint: $path"}""")
        }
    }

    companion object {
        const val PASSWORD_STATUS =
            """{"auth_required":true,"auth_providers":["basic"],"version":"0.22.2","install_id":"install-A","profiles":["default"]}"""

        fun json(code: Int, body: String): MockResponse =
            MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")
    }
}

class HermesConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var host: FakeHermesHost

    @Before
    fun setUp() {
        server = MockWebServer()
        host = FakeHermesHost()
        server.dispatcher = host
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun connection(username: String = "josh", password: String = "pw", installID: String? = null) =
        HermesConnection(
            BotConnection(
                name = "Hermes", address = HermesAddress.parse("http://localhost:${server.port}"),
                username = username, password = password, installID = installID,
            ),
            OkHttpClient(),
        )

    @Test
    fun statusProbeTellsPasswordBrowserAndOpenHostsApart() = runBlocking {
        val probe = HermesStatusProbe(OkHttpClient())
        val address = HermesAddress.parse("http://localhost:${server.port}")

        val password = probe.check(address).getOrThrow()
        assertEquals(HermesSignInMethod.PASSWORD, password.signInMethod)
        assertEquals("0.22.2", password.version)
        assertEquals("install-A", password.installID)

        host.status = """{"auth_required":true,"auth_flows":["native_pkce"],"auth_providers":["nous"],"version":"0.22.2"}"""
        assertEquals(HermesSignInMethod.BROWSER, probe.check(address).getOrThrow().signInMethod)

        host.status = """{"auth_required":false,"version":"0.22.2"}"""
        assertEquals(HermesSignInMethod.OPEN, probe.check(address).getOrThrow().signInMethod)

        // A webui answers its auth gate with a JSON 401: not a dashboard, and no sign-in method at all.
        assertNull(HermesSignInMethod.from(BotJSON.parse("""{"ok":false}""")))
        assertTrue(probe.check(address).isSuccess)
    }

    @Test
    fun passwordSignInPostsBasicLoginThenVerifiesIdentity() = runBlocking {
        val http = connection()
        http.signIn()
        val paths = host.requests.map { it.path ?: "" }
        assertEquals(listOf("/api/status", "/auth/password-login", "/api/auth/me"), paths)
        val login = host.requests.first { it.path == "/auth/password-login" }
        assertEquals("POST", login.method)
        val body = BotJSON.parse(login.body.readUtf8())
        assertEquals("basic", body["provider"].text)
        assertEquals("josh", body["username"].text)
        assertEquals("pw", body["password"].text)
        assertNull(login.getHeader("Origin"))
        assertNull(login.getHeader("Referer"))
        assertEquals("0.22.2", http.serverVersion)
        // Trust on first use: the live install id is kept for the next sign-in.
        assertEquals("install-A", http.connection.installID)
    }

    @Test
    fun browserOriginHeadersAreRejectedBeforeConnecting() {
        for (name in listOf("Origin", "rEfErEr")) {
            try {
                HermesHeaders.admit(listOf(io.github.jcartu.hermex.net.CustomHeader(name, "https://unrelated.example")))
                fail("Browser origin headers must not be admitted")
            } catch (error: HermesHeaders.Rejection.Reserved) {
                assertEquals(name, error.name)
            }
        }
    }

    @Test
    fun differentHostRefusesBeforeThePasswordGoesOut() = runBlocking {
        val http = connection(installID = "install-B")
        try {
            http.signIn()
            fail("expected differentHost")
        } catch (error: BotFailure.DifferentHost) {
            // expected
        }
        assertEquals(0, host.logins.get())
    }

    @Test
    fun releaseOlderThanMinimumIsRefusedAndNewerIsAccepted() = runBlocking {
        host.status = """{"auth_required":true,"auth_providers":["basic"],"version":"0.21.2"}"""
        try {
            connection().signIn()
            fail("expected outdated")
        } catch (error: BotFailure.Outdated) {
            assertEquals("0.21.2", error.version)
        }
        assertEquals(0, host.logins.get())

        host.status = """{"auth_required":true,"auth_providers":["basic"],"version":"0.22.2+canary.1"}"""
        connection().signIn()
        assertEquals(1, host.logins.get())
        assertTrue(HermesCompatibility.isSupported("0.22.2"))
        assertTrue(HermesCompatibility.isSupported(null))
    }

    @Test
    fun openDashboardScrapesPageTokenAndSendsItAsSessionTokenHeader() = runBlocking {
        host.status = """{"auth_required":false,"version":"0.22.2"}"""
        val http = connection(username = "", password = "")
        http.signIn()
        val proof = host.requests.first { (it.path ?: "").startsWith("/api/profiles/active") }
        assertEquals("page-token-1", proof.getHeader(HermesHeaders.SESSION_TOKEN_NAME))
        assertNull(proof.getHeader("Authorization"))
        assertEquals(0, host.logins.get())

        // A later signed-in read carries the same token, and the gateway upgrade presents it as ?token=.
        val upgrade = http.gatewayUpgrade()
        assertEquals("page-token-1", upgrade.url.queryParameter("token"))
        assertNull(upgrade.header("Sec-WebSocket-Protocol"))
    }

    @Test
    fun savedCredentialsMeetingAnOpenDashboardIsSignInChanged() = runBlocking {
        host.status = """{"auth_required":false,"version":"0.22.2"}"""
        var rejected: BotFailure? = null
        val http = connection().also { it.onLoginRejected = { failure -> rejected = failure } }
        try {
            http.signIn()
            fail("expected signInChanged")
        } catch (error: BotFailure.SignInChanged) {
            // expected
        }
        assertEquals(BotFailure.SignInChanged, rejected)
    }

    @Test
    fun aSignedIn401SignsInAgainOnceThenSurfaces() = runBlocking {
        host.profilesActive += FakeHermesHost.json(401, """{"detail":"Unauthorized"}""")
        host.profilesActive += FakeHermesHost.json(200, """{"active":"default","current":"work"}""")
        val http = connection()
        val data = http.data(HermesREST.ProfilesActive)
        assertEquals("work", BotJSON.parse(data)["current"].text)
        assertEquals(2, host.logins.get())

        host.profilesActive += FakeHermesHost.json(401, "{}")
        host.profilesActive += FakeHermesHost.json(401, "{}")
        try {
            http.data(HermesREST.ProfilesActive)
            fail("expected rejected(401)")
        } catch (error: BotFailure.Rejected) {
            assertEquals(401, error.code)
        }
        assertEquals(3, host.logins.get())
    }

    @Test
    fun ticketUpgradeOffersGatewaySubprotocols() = runBlocking {
        val upgrade = connection().gatewayUpgrade()
        assertEquals("hermes-gateway-v1, hermes-gateway-ticket.ticket-1", upgrade.header("Sec-WebSocket-Protocol"))
        assertEquals("/api/ws", upgrade.url.encodedPath)
    }

    @Test
    fun addressParsingReducesLinksToTheDashboardRoot() {
        assertEquals("https://example.com:9119/", HermesAddress.parse("example.com:9119/login?x=1#f").toString())
        assertEquals("http://100.64.0.2:9119/", HermesAddress.parse("100.64.0.2:9119").toString())
        assertEquals("https://localhost:9119/", HermesAddress.parse("wss://LOCALHOST:9119/api/ws").toString())
        assertEquals("http://localhost:9119/", HermesAddress.parse("LOCALHOST:9119/sessions/").toString())
        assertEquals("https://host.ts.net/", HermesAddress.parse("host.ts.net").toString())
        try {
            HermesAddress.parse("https://example.com/hermes")
            fail("expected a path refusal")
        } catch (error: io.github.jcartu.hermex.model.hermes.BotAddressError.Path) {
            assertEquals("/hermes", error.path)
        }
    }
}
