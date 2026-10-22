package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.HermesOAuth
import io.github.jcartu.hermex.net.hermes.HermesBrowserSignIn
import io.github.jcartu.hermex.net.hermes.HermesSignInMethod
import io.github.jcartu.hermex.net.hermes.HermesStatus
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesConnectionViewModel
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HermesConnectionIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `different host refuses before password and confirmed replacement drops old UUID data`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = MockWebServer()
        var install = "old-install"
        var logins = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/status" -> MockResponse().setBody("""{"auth_required":true,"auth_providers":["basic"],"version":"0.22.2","install_id":"$install"}""")
                "/auth/password-login" -> { logins++; MockResponse().setBody("{}") }
                "/api/auth/me" -> MockResponse().setBody("""{"provider":"basic"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val client = OkHttpClient()
        val fixture = AuthTestFixture(temp)
        val auth = fixture.manager()
        val old = BotConnection("stable-id", "Host", server.url("/"), "user", "pw", installID = install)
        val account = auth.addHermesServer(old)
        val discarded = mutableListOf<String>()
        val model = HermesConnectionViewModel(account, fixture.botConnections, auth, client,
            probe = { _, _ -> Result.success(HermesStatus(authRequired = true)) }, verifyProfiles = {}, discard = { discarded += it.id })
        try {
            model.setPassword("new-pw")
            model.connect(HermesBrowserSignIn.Presenter.unavailable)
            withTimeout(10_000) { model.state.first { !it.isConnecting } }
            assertEquals("stable-id", fixture.botRecords[account.id]?.id)
            assertTrue(discarded.isEmpty())
            install = "replacement-install"
            model.connect(HermesBrowserSignIn.Presenter.unavailable)
            withTimeout(10_000) { model.state.first { !it.isConnecting } }
            assertTrue(model.state.value.offersHostReplacement)
            assertEquals(1, logins)
            model.connect(HermesBrowserSignIn.Presenter.unavailable, replacingHost = true)
            withTimeout(10_000) { model.state.first { !it.isConnecting } }
            assertNotEquals("stable-id", fixture.botRecords[account.id]?.id)
            assertEquals("replacement-install", fixture.botRecords[account.id]?.installID)
            assertEquals(listOf("stable-id"), discarded)
            assertEquals(2, logins)
        } finally {
            model.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown()
            Dispatchers.resetMain()
        }
    }

    @Test fun `same browser account keeps UUID and closing without code shows failed copy`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/status" -> MockResponse().setBody("""{"auth_required":true,"auth_providers":["nous"],"auth_flows":["native_pkce"],"version":"0.22.2"}""")
                "/auth/native/token" -> MockResponse().setBody("""{"access_token":"access","refresh_token":"rotated","provider":"nous","user_id":"user"}""")
                "/api/auth/me" -> MockResponse().setBody("""{"provider":"nous","user_id":"user"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val fixture = AuthTestFixture(temp)
        val auth = fixture.manager()
        val old = BotConnection("stable-browser", "Host", server.url("/"), "user", "", oauth = HermesOAuth("nous", "user", "old"))
        val account = auth.addHermesServer(old)
        val discarded = mutableListOf<String>()
        val model = HermesConnectionViewModel(account, fixture.botConnections, auth, client,
            probe = { _, _ -> Result.success(HermesStatus(authRequired = true, signInMethod = HermesSignInMethod.BROWSER)) },
            discard = { discarded += it.id })
        try {
            model.connect(HermesBrowserSignIn.Presenter { })
            withTimeout(10_000) { model.state.first { !it.isConnecting } }
            assertEquals("The browser sign-in didn't finish. Try again.", model.state.value.errorMessage)
            model.connect(HermesBrowserSignIn.Presenter { url ->
                val callback = url.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
                    .addQueryParameter("state", url.queryParameter("state")).addQueryParameter("code", "code").build()
                client.newCall(Request.Builder().url(callback).build()).execute().close()
            })
            withTimeout(10_000) { model.state.first { !it.isConnecting } }
            assertNull(model.state.value.errorMessage)
            assertEquals("stable-browser", fixture.botRecords[account.id]?.id)
            assertEquals("rotated", fixture.botRecords[account.id]?.oauth?.refreshToken)
            assertTrue(discarded.isEmpty())
        } finally {
            model.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown()
            Dispatchers.resetMain()
        }
    }
}
