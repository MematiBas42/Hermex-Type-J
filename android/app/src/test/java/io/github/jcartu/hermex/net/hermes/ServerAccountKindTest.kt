package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ServerAccount.kind` (#899, Contract A): a record saved before the field existed,
 * one from a newer build with a kind this build doesn't know, and one of the wrong
 * type all read as webui; a Hermes record keeps its kind and release across a round trip.
 */
class ServerAccountKindTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun recordWithoutKindReadsAsWebui() {
        val account = json.decodeFromString(
            ServerAccount.serializer(),
            """{"id":"https://webui.example","url_string":"https://webui.example","created_at":1.0}""",
        )
        assertEquals(ServerKind.WEBUI, account.kind)
        assertNull(account.serverVersion)
    }

    @Test
    fun hermesKindAndVersionRoundTrip() {
        val original = ServerAccount(
            id = "http://100.64.0.2:9119",
            urlString = "http://100.64.0.2:9119",
            kind = ServerKind.HERMES,
            serverVersion = "0.22.2",
        )
        val encoded = json.encodeToJsonElement(ServerAccount.serializer(), original).jsonObject
        assertEquals("hermes", encoded["kind"]?.jsonPrimitive?.content)
        assertEquals("0.22.2", encoded["server_version"]?.jsonPrimitive?.content)

        val decoded = json.decodeFromJsonElement(ServerAccount.serializer(), encoded)
        assertEquals(ServerKind.HERMES, decoded.kind)
        assertEquals("0.22.2", decoded.serverVersion)
    }

    @Test
    fun iosCamelCaseKeysDecode() {
        val account = json.decodeFromString(
            ServerAccount.serializer(),
            """{"id":"http://host:9119","urlString":"http://host:9119","kind":"hermes","serverVersion":"0.21.5"}""",
        )
        assertEquals(ServerKind.HERMES, account.kind)
        assertEquals("0.21.5", account.serverVersion)
    }

    @Test
    fun unknownOrMistypedKindFallsBackToWebui() {
        val unknown = json.decodeFromString(
            ServerAccount.serializer(),
            """{"id":"https://a","kind":"future-kind"}""",
        )
        assertEquals(ServerKind.WEBUI, unknown.kind)

        val mistyped = json.decodeFromString(
            ServerAccount.serializer(),
            """{"id":"https://a","kind":{"nested":true}}""",
        )
        assertEquals(ServerKind.WEBUI, mistyped.kind)
    }
}
