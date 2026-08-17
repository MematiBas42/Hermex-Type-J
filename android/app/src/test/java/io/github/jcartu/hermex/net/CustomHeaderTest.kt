package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomHeaderTest {

    @Test
    fun applicabilityRequiresTokenNameAndInjectionFreeValue() {
        assertTrue(CustomHeader("X-Api-Key", "secret").isApplicable)
        assertTrue(CustomHeader("  X-Api-Key  ", "Bearer a b c").isApplicable)
        assertFalse("empty name", CustomHeader("", "v").isApplicable)
        assertFalse("blank name", CustomHeader("   ", "v").isApplicable)
        assertFalse("space in name", CustomHeader("X Api Key", "v").isApplicable)
        assertFalse("colon in name", CustomHeader("X-Api-Key:", "v").isApplicable)
        assertFalse("newline in value", CustomHeader("X-Api-Key", "a\nb").isApplicable)
        assertFalse("carriage return in value", CustomHeader("X-Api-Key", "a\rb").isApplicable)
    }

    @Test
    fun mergedUnderLetsBuiltInsWin() {
        val merged = listOf(
            CustomHeader("X-Api-Key", "secret"),
            CustomHeader("Accept", "text/plain"),
            CustomHeader("", "skipped"),
        ).mergedUnder(mapOf("Accept" to "text/event-stream"))
        assertEquals(mapOf("X-Api-Key" to "secret", "Accept" to "text/event-stream"), merged)
    }

    @Test
    fun storageCodecRoundTripsAndToleratesGarbage() {
        val headers = listOf(CustomHeader("X-Api-Key", "secret"), CustomHeader("X-Other", ""))
        val encoded = headers.encodedForStorage()
        assertEquals(headers, CustomHeader.decodeFromStorage(encoded))

        assertNull(emptyList<CustomHeader>().encodedForStorage())
        assertEquals(emptyList<CustomHeader>(), CustomHeader.decodeFromStorage(null))
        assertEquals(emptyList<CustomHeader>(), CustomHeader.decodeFromStorage("not json"))
        // Malformed entries decode to empty fields instead of failing the list.
        assertEquals(
            listOf(CustomHeader("a", ""), CustomHeader("", "")),
            CustomHeader.decodeFromStorage("""[{"name":"a"},{}]"""),
        )
    }

    @Test
    fun sanitizedForStorageDropsBlankRows() {
        assertEquals(
            listOf(CustomHeader("X-Api-Key", "secret")),
            listOf(CustomHeader("X-Api-Key", "secret"), CustomHeader("  ", "half-typed")).sanitizedForStorage(),
        )
    }

    @Test
    fun crossOriginRedirectStripsCustomHeadersButSameOriginKeepsThem() {
        val origin = MockWebServer().also { it.start() }
        val elsewhere = MockWebServer().also { it.start() }
        try {
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
                explicitNulls = false
            }
            val api = HermesApi(
                httpClient = OkHttpClient(),
                json = json,
                configProvider = { ServerConfig(origin.url("/").toString(), "pw") },
                customHeaderProvider = { listOf(CustomHeader("X-Api-Key", "secret")) },
            )

            // Same-origin redirect: the header survives the second hop.
            origin.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", origin.url("/api/memory-moved").toString()),
            )
            origin.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            runBlocking { api.memory() }
            assertEquals("secret", origin.takeRequest().getHeader("X-Api-Key"))
            assertEquals("secret", origin.takeRequest().getHeader("X-Api-Key"))

            // Cross-origin redirect: the header is stripped off-origin (#277).
            origin.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", elsewhere.url("/api/memory").toString()),
            )
            elsewhere.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            runBlocking { api.memory() }
            assertEquals("secret", origin.takeRequest().getHeader("X-Api-Key"))
            assertNull(
                "custom header must not follow a cross-origin redirect",
                elsewhere.takeRequest().getHeader("X-Api-Key"),
            )
        } finally {
            origin.shutdown()
            elsewhere.shutdown()
        }
    }
}
