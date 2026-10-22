package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.hermes.HermesBrowserSignIn
import io.github.jcartu.hermex.net.hermes.HermesSignInMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HermesConnectViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var fixture: AuthTestFixture
    private lateinit var auth: AuthManager
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()); fixture = AuthTestFixture(temp); auth = fixture.manager() }
    @After fun after() { Dispatchers.resetMain() }
    private fun status(method: HermesSignInMethod) = BotJSON.parse(when (method) {
        HermesSignInMethod.PASSWORD -> """{"auth_required":true,"auth_providers":["basic"],"version":"0.22.2","install_id":"i"}"""
        HermesSignInMethod.BROWSER -> """{"auth_required":true,"auth_providers":["nous"],"auth_flows":["native_pkce"],"version":"0.22.2","install_id":"i"}"""
        HermesSignInMethod.OPEN -> """{"auth_required":false,"version":"0.22.2","install_id":"i"}"""
    })
    private fun form(method: HermesSignInMethod) = OnboardingViewModel(auth, entry = ConnectFormEntry.ADD_SERVER,
        readHermesStatus = { _, _ -> status(method) }).apply { onServerUrlChange("https://hermes.example") }

    @Test fun `first Connect detects each method without sending credentials or calling webui`() {
        for (method in HermesSignInMethod.entries) {
            val form = form(method)
            form.connect()
            val state = form.uiState.value
            assertEquals(ServerKind.HERMES, state.detectedKind)
            assertEquals(method, state.signInMethod)
            assertEquals(method == HermesSignInMethod.PASSWORD, state.showsPasswordField)
            assertEquals(method != HermesSignInMethod.PASSWORD, state.canSubmit)
            assertNull(state.addedAccount)
            assertTrue(fixture.clientRequests.isEmpty())
        }
    }

    @Test fun `Blocked and Host-header 400 stop with advice and mode switch preserves it`() {
        for (failure in listOf(BotFailure.Blocked, BotFailure.Rejected(400))) {
            val form = OnboardingViewModel(auth, readHermesStatus = { _, _ -> throw failure })
            form.onServerUrlChange("https://host.example")
            form.connect()
            val message = form.uiState.value.errorMessage
            assertFalse(message.isNullOrBlank())
            form.setConnectionMode(ConnectionMode.CLOUDFLARE_TUNNEL)
            assertEquals(message, form.uiState.value.errorMessage)
            assertTrue(fixture.clientRequests.isEmpty())
        }
    }

    @Test fun `non dashboard answer falls through unchanged with only filled headers`() {
        fixture.stubHealthy("https://host.example", false)
        var probeHeaders: List<CustomHeader>? = null
        val form = OnboardingViewModel(auth, readHermesStatus = { _, headers -> probeHeaders = headers.values; BotJSON.parse("{}") })
        form.onServerUrlChange("https://host.example")
        form.onCustomHeadersChange(listOf(CustomHeader("X-Proxy", "secret"), CustomHeader("CF-Access-Client-Id", "")))
        form.connect()
        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertEquals(ServerKind.WEBUI, auth.activeServer?.kind)
        assertEquals(listOf(CustomHeader("X-Proxy", "secret")), probeHeaders)
        assertEquals(probeHeaders, fixture.clientRequests.first().second)
    }

    @Test fun `saved webui address offers replacement without a login until confirmed`() = runBlocking {
        fixture.stubHealthy("https://hermes.example", false)
        auth.configure("https://hermes.example", "")
        val form = form(HermesSignInMethod.PASSWORD)
        form.connect()
        form.onUsernameChange("user"); form.onPasswordChange("password")
        form.connect()
        assertTrue(form.uiState.value.offersWebuiReplace)
        assertEquals(AuthManager.WEBUI_SAVED_AT_ADDRESS_MESSAGE, form.uiState.value.errorMessage)
        assertEquals(ServerKind.WEBUI, auth.activeServer?.kind)
    }

    @Test fun `saved copied secrets leave on scheme change but user edits stay`() = runBlocking {
        fixture.stubHealthy("https://webui.example", false)
        auth.configure("https://webui.example", "")
        fixture.botRecords[auth.activeServerID!!] = BotConnection(name = "Host", address = "https://hermes.example".toHttpUrl(),
            username = "saved-user", password = "saved-password", installID = "i", headers = listOf(CustomHeader("X-Proxy", "copied-secret")))
        val form = form(HermesSignInMethod.PASSWORD)
        form.connect()
        form.useSavedSignIn(form.uiState.value.savedSignIns.single())
        form.onUsernameChange("edited-user")
        form.onCustomHeadersChange(listOf(CustomHeader("X-Renamed", "copied-secret")))
        form.onServerUrlChange("http://hermes.example")
        assertEquals("edited-user", form.uiState.value.username)
        assertEquals("", form.uiState.value.password)
        assertTrue(form.uiState.value.customHeaders.isEmpty())
        assertNull(form.uiState.value.reusedSignIn)
    }

    @Test fun `password open and browser second Connect save actual transport sign ins`() = runBlocking {
        for (method in HermesSignInMethod.entries) {
            val server = MockWebServer()
            var loginCount = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/api/status" -> MockResponse().setBody(status(method).encoded)
                    "/auth/password-login" -> { loginCount++; MockResponse().setBody("{}") }
                    "/api/auth/me" -> MockResponse().setBody(if (method == HermesSignInMethod.BROWSER)
                        """{"provider":"nous","user_id":"user"}""" else """{"provider":"basic"}""")
                    "/auth/native/token" -> MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","provider":"nous","user_id":"user"}""")
                    "/" -> MockResponse().setBody("<script>window.__HERMES_SESSION_TOKEN__ = \"page-token\";</script>")
                    "/api/profiles/active" -> MockResponse().setBody("""{"current":"default"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val client = OkHttpClient()
            val callbackClient = OkHttpClient.Builder().followRedirects(false).build()
            val form = OnboardingViewModel(auth, entry = ConnectFormEntry.ADD_SERVER, hermesHttpClient = client,
                readHermesStatus = { _, _ -> status(method) })
            try {
                form.onServerUrlChange(server.url("/").toString())
                form.connect()
                if (method == HermesSignInMethod.PASSWORD) { form.onUsernameChange("user"); form.onPasswordChange("password") }
                val presenter = HermesBrowserSignIn.Presenter { url ->
                    val callback = url.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
                        .addQueryParameter("state", url.queryParameter("state"))
                        .addQueryParameter("code", "single-use-code").build()
                    callbackClient.newCall(Request.Builder().url(callback).build()).execute().use { assertEquals(302, it.code) }
                }
                form.connect(browser = presenter)
                withTimeout(10_000) { form.uiState.first { !it.isWorking } }
                assertNull(form.uiState.value.errorMessage)
                val added = form.uiState.value.addedAccount!!
                assertEquals(ServerKind.HERMES, added.kind)
                val saved = fixture.botRecords[added.id]!!
                assertEquals("i", saved.installID)
                assertEquals(method == HermesSignInMethod.OPEN, saved.hasNoCredentials)
                assertEquals(method == HermesSignInMethod.BROWSER, saved.oauth != null)
                assertEquals(if (method == HermesSignInMethod.PASSWORD) 1 else 0, loginCount)
            } finally {
                form.cancel()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                callbackClient.connectionPool.evictAll(); callbackClient.dispatcher.executorService.shutdown()
                server.shutdown()
            }
        }
    }
}
