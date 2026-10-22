package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotJSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The gateway socket against MockWebServer's WebSocket support: the handshake, id correlation,
 * event fan-out to every attached screen, and a screen reconnecting on the backoff after the
 * host closed the socket.
 */
class HermesGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var host: FakeHermesHost
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    /** The scripted host side of one gateway socket. */
    private inner class HostSocket(private val closesAfterActiveList: Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-1","heartbeat":true}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.parseToJsonElement(text).jsonObject
            val id = frame["id"]?.jsonPrimitive?.content ?: return
            when (frame["method"]?.jsonPrimitive?.content) {
                "client.capabilities" -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
                "profiles.list" -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"profiles":[{"name":"default"}]}}""")
                "session.active_list" -> {
                    webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"sessions":[]}}""")
                    if (closesAfterActiveList) webSocket.close(1001, "restart")
                }
                "session.control.read" -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}""")
                "gateway.ping" -> webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{}}""")
            }
        }
    }

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

    private fun connection(): HermesConnection = HermesConnection(
        BotConnection(name = "Hermes", address = HermesAddress.parse("http://localhost:${server.port}"), username = "josh", password = "pw"),
        OkHttpClient(),
        HermesGateway.Options(rpcDeadlineMillis = 5_000, heartbeatIntervalMillis = 60_000, silenceTimeoutMillis = 10_000),
    )

    @Test
    fun handshakeCorrelatesRepliesAndFansEventsOutToEveryScreen() = runBlocking {
        host.wsUpgrades = { MockResponse().withWebSocketUpgrade(HostSocket(closesAfterActiveList = false)) }
        val http = connection()
        val first = BotClient(http)
        val second = BotClient(http)
        val firstEvent = CompletableDeferred<BotJSON>()
        val secondEvent = CompletableDeferred<BotJSON>()
        first.onEvent = { if (it["type"].text == "sessions.changed") firstEvent.complete(it) }
        second.onEvent = { if (it["type"].text == "sessions.changed") secondEvent.complete(it) }

        withTimeout(10_000) {
            first.connect()
            second.connect()
            assertEquals("epoch-1", first.replayEpoch)
            val upgrade = host.requests.first { (it.path ?: "").startsWith("/api/ws") }
            assertEquals("hermes-gateway-v1, hermes-gateway-ticket.ticket-1", upgrade.getHeader("Sec-WebSocket-Protocol"))
            assertNull(upgrade.getHeader("Origin"))

            val profiles = first.call(HermesCall.ProfilesList(includeSessions = false))
            assertEquals("default", profiles["profiles"].list?.first()?.get("name")?.text)
            val sessions = second.call(HermesCall.SessionActiveList)
            assertEquals(0, sessions["sessions"].list?.size)

            // A -32601 marks the method unavailable on the connection, for every screen.
            try {
                first.call(HermesCall.SessionControlRead("rt", "default"))
            } catch (error: BotSettingFailure.Rejected) {
                assertEquals(-32601, error.code)
            }
            assertTrue("session.control.read" in http.unavailableMethods)

            sockets.first().send("""{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","payload":{"profile":"default"}}}""")
            assertEquals("default", firstEvent.await()["payload"]["profile"].text)
            assertEquals("default", secondEvent.await()["payload"]["profile"].text)
            first.close()
            second.close()
        }
        // One socket for both screens, one ticket.
        assertEquals(1, host.requests.count { (it.path ?: "").startsWith("/api/ws") })
    }

    @Test
    fun aClosedSocketTellsTheScreenOnceAndReconnectsOnTheBackoff() = runBlocking {
        host.wsUpgrades = { attempt ->
            when (attempt) {
                1 -> MockResponse().withWebSocketUpgrade(HostSocket(closesAfterActiveList = true))
                2 -> MockResponse().setResponseCode(503)
                else -> MockResponse().withWebSocketUpgrade(HostSocket(closesAfterActiveList = false))
            }
        }
        val http = connection()
        val client = BotClient(http)
        val dropped = CompletableDeferred<Throwable>()
        client.onDisconnect = { dropped.complete(it) }
        val delays = ArrayList<Long>()

        withTimeout(15_000) {
            client.connect()
            client.call(HermesCall.SessionActiveList)
            assertTrue(dropped.await() is io.github.jcartu.hermex.model.hermes.BotFailure.Transport)

            client.connectWithBackoff(sleep = { delays += it })
            assertEquals(listOf(1_000L), delays)
            val profiles = client.call(HermesCall.ProfilesList(includeSessions = false))
            assertEquals(1, profiles["profiles"].list?.size)
            client.close()
        }
        assertEquals(3, host.requests.count { (it.path ?: "").startsWith("/api/ws") })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), HermesReconnectBackoff.delaysMillis)
        assertEquals(30_000L, HermesReconnectBackoff.delayMillis(12))
    }
}

/** PKCE and the loopback listener (#708), without a browser. */
class HermesBrowserSignInTest {

    @Test
    fun challengeIsSha256Base64UrlOfTheVerifier() {
        // RFC 7636 appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            HermesBrowserSignIn.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val verifier = HermesBrowserSignIn.randomURLSafe(32)
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authorizeUrlPercentEncodesEveryValueAndNamesNoProvider() {
        val url = HermesBrowserSignIn.authorizeURL(
            HermesAddress.parse("https://hermes.example:9119"), challenge = "abc+/=", redirect = "http://127.0.0.1:4321/callback", state = "s t",
        )
        assertEquals("/auth/native/authorize", url.encodedPath)
        assertEquals("code_challenge=abc%2B%2F%3D&code_challenge_method=S256&redirect_uri=http%3A%2F%2F127.0.0.1%3A4321%2Fcallback&state=s%20t", url.encodedQuery)
        assertNull(url.queryParameter("provider"))
    }

    @Test
    fun loopbackListenerKeepsTheMatchingStateCodeAndBouncesEveryCallback() = runBlocking {
        val listener = HermesLoopbackListener(bounce = HermesBrowserSignIn.BOUNCE, state = "expected")
        val port = listener.start()
        try {
            assertEquals("HTTP/1.1 302 Found", request(port, "GET /callback?state=other&code=wrong HTTP/1.1").first())
            assertNull(listener.code)
            val lines = request(port, "GET /callback?state=expected&code=the-code HTTP/1.1")
            assertEquals("HTTP/1.1 302 Found", lines.first())
            assertTrue(lines.contains("Location: hermexsignin://done"))
            assertEquals("the-code", listener.code)
            // The first matching code is kept.
            request(port, "GET /callback?state=expected&code=later HTTP/1.1")
            assertEquals("the-code", listener.code)
            assertEquals("HTTP/1.1 404 Not Found", request(port, "GET /favicon.ico HTTP/1.1").first())
        } finally {
            listener.stop()
        }
    }

    private fun request(port: Int, line: String): List<String> = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write("$line\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readLines()
    }
}
