package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HermesAuthManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record(host: String = "https://hermes.example") = BotConnection(name = "Host", address = host.toHttpUrl(), username = "u", password = "p", hermesVersion = "0.22.2")

    @Test fun `adding defaults to Hermes and refuses either kind at same address`() = runBlocking {
        val fixture = AuthTestFixture(temp)
        val auth = fixture.manager()
        val first = auth.addHermesServer(record())
        assertEquals(ServerKind.HERMES, first.kind)
        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertEquals(first.id, auth.activeServerID)
        assertTrue(fixture.clientRequests.isEmpty())
        assertTrue(runCatching { auth.addHermesServer(record()) }.exceptionOrNull() is AuthManager.ServerAlreadyConfigured)
        val second = auth.addHermesServer(record("https://second.example"), activate = false)
        assertEquals(first.id, auth.activeServerID)
        assertEquals(ServerKind.HERMES, second.kind)
        assertEquals(2, auth.servers.value.size)
    }

    @Test fun `rejection signs out only the active Hermes account and webui 401 never does`() = runBlocking {
        val fixture = AuthTestFixture(temp)
        val auth = fixture.manager()
        val first = auth.addHermesServer(record())
        val second = auth.addHermesServer(record("https://second.example"))
        auth.hermesSignInRejected(first.id)
        assertEquals(second.id, (auth.state.value as AuthState.LoggedIn).account.id)
        auth.handleApiError(ApiError.Unauthorized)
        assertTrue(auth.state.value is AuthState.LoggedIn)
        auth.hermesSignInRejected(second.id, BotFailure.SignInExpired)
        assertEquals(second.id, (auth.state.value as AuthState.LoggedOut).account.id)
        assertNotNull(fixture.botRecords[second.id])
        auth.hermesSignInSaved(second.id)
        assertTrue(auth.state.value is AuthState.LoggedIn)
        auth.signOut()
        assertNull(fixture.botRecords[second.id])
        assertEquals(2, auth.servers.value.size)
        assertEquals(second.id, (auth.state.value as AuthState.LoggedOut).account.id)
        assertTrue(fixture.clientRequests.isEmpty())
        assertTrue(fixture.cookies.cleared.isEmpty())
        auth.removeServer(second)
        assertEquals(first.id, auth.activeServerID)
        assertEquals(1, auth.servers.value.size)
        assertEquals(2, fixture.discardedHermesData.size)
    }

    @Test fun `replace preserves identity and exact address saved sign ins never match by install id`() = runBlocking {
        val fixture = AuthTestFixture(temp)
        fixture.stubHealthy("https://hermes.example", false)
        val auth = fixture.manager()
        auth.configure("https://hermes.example", "")
        val webui = auth.activeServer!!
        auth.updateServerIdentity(webui, "Office", "OF", "#123456")
        fixture.botRecords[webui.id] = record().copy(installID = "install-a")
        assertEquals(1, auth.savedHermesSignIns("https://hermes.example".toHttpUrl()).size)
        assertTrue(auth.savedHermesSignIns("http://hermes.example".toHttpUrl()).isEmpty())
        assertTrue(runCatching { auth.addHermesServer(record()) }.exceptionOrNull() is AuthManager.ServerAlreadyConfigured)
        val replacement = auth.replaceWebuiServer(record())
        assertEquals(ServerKind.HERMES, replacement.kind)
        assertEquals("Office", replacement.displayName)
        assertEquals("OF", replacement.initials)
        assertEquals("#123456", replacement.headerLogoColorHex)
    }
}
