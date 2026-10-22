package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.CustomHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HermesTransportIsolationTest {
    @Test
    fun credentialPostNeverFollowsACrossOriginRedirect() = runBlocking {
        val origin = MockWebServer()
        val otherOrigin = MockWebServer()
        origin.start()
        otherOrigin.start()
        try {
            otherOrigin.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val host = FakeHermesHost()
            origin.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/auth/password-login") {
                        MockResponse().setResponseCode(307)
                            .setHeader("Location", otherOrigin.url("/capture"))
                    } else host.dispatch(request)
            }
            val connection = connection(origin)
            try {
                connection.signIn()
                fail("A credential redirect must be refused")
            } catch (_: BotFailure.Blocked) {
                // The destination must receive no credential-bearing request.
            }
            assertEquals(0, otherOrigin.requestCount)
        } finally {
            origin.shutdown()
            otherOrigin.shutdown()
        }
    }

    @Test
    fun websocketUpgradeCannotRedirectCustomSecretsOrTickets() = runBlocking {
        val origin = MockWebServer()
        val otherOrigin = MockWebServer()
        origin.start()
        otherOrigin.start()
        try {
            otherOrigin.enqueue(MockResponse().setResponseCode(400))
            val host = FakeHermesHost().apply {
                wsUpgrades = {
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", otherOrigin.url("/capture"))
                }
            }
            origin.dispatcher = host
            val connection = connection(origin)
            val failed = CompletableDeferred<Throwable>()
            val socket = connection.socketClient.newWebSocket(connection.gatewayUpgrade(), object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failed.complete(t)
                }
            })
            try {
                withTimeout(5_000) { failed.await() }
                assertEquals(1, host.requests.count { it.path?.startsWith("/api/ws") == true })
                assertEquals(0, otherOrigin.requestCount)
            } finally {
                socket.cancel()
            }
        } finally {
            origin.shutdown()
            otherOrigin.shutdown()
        }
    }

    @Test
    fun lostMutationResponseIsNotAutomaticallyReplayed() {
        val origin = MockWebServer()
        origin.start()
        try {
            origin.enqueue(MockResponse().setBody("ready"))
            origin.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            origin.enqueue(MockResponse().setBody("duplicate operation"))
            val connection = connection(origin)
            connection.client.newCall(Request.Builder().url(origin.url("/warm")).build()).execute().use {
                assertEquals("ready", it.body.string())
            }
            val mutation = Request.Builder().url(origin.url("/trigger"))
                .post("perform operation".toRequestBody()).build()
            assertThrows(IOException::class.java) {
                connection.client.newCall(mutation).execute().use {
                    fail("An unknown mutation outcome must not be silently retried")
                }
            }
            assertEquals(2, origin.requestCount)
        } finally {
            origin.shutdown()
        }
    }

    @Test
    fun safeStatusReadRecoversAfterAPooledSocketDrops() = runBlocking {
        val origin = MockWebServer()
        origin.start()
        try {
            val status = "{\"auth_required\":true,\"version\":\"0.21.5\"}"
            origin.enqueue(MockResponse().setBody(status))
            origin.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            origin.enqueue(MockResponse().setBody(status))
            val connection = connection(origin)
            connection.status()
            assertEquals("0.21.5", connection.status()["version"].text)
            assertEquals(3, origin.requestCount)
        } finally {
            origin.shutdown()
        }
    }

    @Test
    fun mutationSucceedsWithoutDependingOnAnIdleReadConnection() = runBlocking {
        val origin = MockWebServer()
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/status" -> MockResponse().setBody("{\"auth_required\":true}")
                request.sequenceNumber > 0 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else -> MockResponse().setBody("accepted once")
            }
        }
        origin.start()
        try {
            val connection = connection(origin)
            connection.status()
            val mutation = Request.Builder().url(origin.url("/trigger")).post("operation".toRequestBody()).build()
            connection.client.newCall(mutation).execute().use {
                assertEquals("accepted once", it.body.string())
            }
            assertEquals(2, origin.requestCount)
        } finally {
            origin.shutdown()
        }
    }

    private fun connection(server: MockWebServer) = HermesConnection(
        BotConnection(
            name = "Fixture",
            address = server.url("/"),
            username = "user",
            password = "fixture-password",
            headers = listOf(CustomHeader("X-Api-Key", "fixture-secret")),
        ),
        OkHttpClient(),
    )
}
