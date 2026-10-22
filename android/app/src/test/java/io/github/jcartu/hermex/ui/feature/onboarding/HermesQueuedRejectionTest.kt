package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.hermes.HermesConnections
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Delays delivery exactly where AppContainer hops from HermesActor to Main. */
class HermesQueuedRejectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `old queued rejection cannot sign out successfully resaved identical credentials`() = runBlocking {
        val fixture = AuthTestFixture(temp)
        val connections = HermesConnections()
        val auth = fixture.manager(hermesConnections = connections)
        val record = BotConnection("stable-uuid", "Host", "https://hermes.example".toHttpUrl(), "user", "password")
        val account = auth.addHermesServer(record)
        val deliveries = mutableListOf<() -> Unit>()
        connections.onSignInRejected = { server, failure, isCurrent ->
            deliveries += { if (isCurrent()) auth.hermesSignInRejected(server, failure) }
        }
        val old = connections.connection(record, account.id)
        old.onLoginRejected?.invoke(BotFailure.Rejected(401))
        assertEquals(1, deliveries.size)
        // A separate form verified and saved the same account/UUID/password while Main was busy.
        fixture.botConnections.save(record, account.id)
        auth.hermesSignInSaved(account.id)
        assertTrue(old.isRetired)
        deliveries.removeAt(0).invoke()
        assertTrue(auth.state.value is AuthState.LoggedIn)
        // A failure from the replacement still signs this server out, not a permanent blanket ignore.
        val current = connections.connection(record, account.id)
        current.onLoginRejected?.invoke(BotFailure.SignInExpired)
        deliveries.removeAt(0).invoke()
        assertTrue(auth.state.value is AuthState.LoggedOut)
        connections.retire(account.id)
    }

    @Test fun `old queued failure cannot follow switching away and back to same server id`() = runBlocking {
        val fixture = AuthTestFixture(temp)
        val connections = HermesConnections()
        val auth = fixture.manager(hermesConnections = connections)
        val record = BotConnection("uuid", "Host", "https://hermes.example".toHttpUrl(), "user", "password")
        val account = auth.addHermesServer(record)
        var deliver: (() -> Unit)? = null
        connections.onSignInRejected = { server, failure, isCurrent ->
            deliver = { if (isCurrent()) auth.hermesSignInRejected(server, failure) }
        }
        val old = connections.connection(record, account.id)
        old.onLoginRejected?.invoke(BotFailure.Rejected(401))
        auth.addHermesServer(record.copy(id = "other", address = "https://other.example".toHttpUrl()))
        auth.switchActiveServer(account)
        connections.connection(record, account.id)
        deliver!!.invoke()
        assertTrue(auth.state.value is AuthState.LoggedIn)
        assertEquals(account.id, auth.activeServerID)
        connections.retire(account.id)
    }
}
