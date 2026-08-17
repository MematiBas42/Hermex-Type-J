package io.github.jcartu.hermex

import android.net.Uri
import io.github.jcartu.hermex.core.media.SpeechTextNormalizer
import io.github.jcartu.hermex.core.navigation.DeepLinkParser
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.Endpoint
import io.github.jcartu.hermex.service.BackgroundRunState
import io.github.jcartu.hermex.ui.adaptive.computePaneWidths
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RobustnessFuzzTest {
    private val random = Random(0x545950454aL)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test fun `hinge geometry always returns finite nonnegative exact width panes`() {
        repeat(2_000) {
            val width = 100f + random.nextFloat() * 5_000f
            val start = random.nextFloat() * 500f
            val panes = 1 + random.nextInt(3)
            val hinges = List(random.nextInt(5)) { start - 300f + random.nextFloat() * (width + 600f) }
            val result = computePaneWidths(start, width, hinges, panes)
            assertEquals(panes, result.size)
            assertTrue(result.all { it.isFinite() && it >= 0f })
            assertTrue(abs(result.sum() - width) < 1f)
        }
    }

    @Test fun `dynamic Kanban path is always one encoded segment`() {
        val base = "https://example.com/proxy/".toHttpUrl()
        val alphabet = "abcXYZ09/.. %?#\\λ"
        assertThrows(IllegalArgumentException::class.java) { Endpoint.KanbanEditBoard(".").url(base) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint.KanbanEditBoard("..").url(base) }
        repeat(1_000) {
            val slug = buildString { repeat(1 + random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            if (slug.isBlank() || slug == "." || slug == "..") {
                assertThrows(IllegalArgumentException::class.java) { Endpoint.KanbanEditBoard(slug).url(base) }
            } else {
                val url = Endpoint.KanbanEditBoard(slug).url(base)
                assertEquals(listOf("proxy", "api", "kanban", "boards", slug), url.pathSegments)
                assertEquals("example.com", url.host)
            }
        }
    }

    @Test fun `Git diff path stays query data and cannot alter route`() {
        val base = "https://example.com/".toHttpUrl()
        repeat(1_000) {
            val path = "../src/${random.nextLong()} /File.kt?token=#x"
            val url = Endpoint.GitDiff("s1", path, "unstaged").url(base)
            assertEquals("/api/git/diff", url.encodedPath)
            assertEquals(path, url.queryParameter("path"))
            assertEquals("s1", url.queryParameter("session_id"))
        }
    }

    @Test fun `tolerant Kanban card decoder survives malformed optional scalars`() {
        val values = listOf("null", "true", "false", "0", "1.8", "\"text\"", "[]", "{}")
        repeat(1_000) {
            val priority = values[random.nextInt(values.size)]
            val age = values[random.nextInt(values.size)]
            val extra = random.nextLong()
            val card = json.decodeFromString<KanbanCard>(
                """{"id":"c$it","title":42,"status":"future-$it","priority":$priority,"age_seconds":$age,"extra_$extra":{"nested":true}}""",
            )
            assertEquals("c$it", card.cardId)
            assertEquals("42", card.title)
            assertNotNull(card.status)
        }
    }

    @Test fun `speech normalizer never leaks fenced code or exceeds cap`() {
        repeat(500) {
            val secret = "secret-${random.nextLong()}"
            val text = "# Heading [link](https://example.com) ```kotlin $secret ``` " + "word ".repeat(random.nextInt(6_000))
            val normalized = SpeechTextNormalizer.normalize(text)!!
            assertFalse(normalized.contains(secret))
            assertFalse(normalized.contains("https://"))
            assertTrue(normalized.length <= 20_000)
        }
    }

    @Test fun `background reducer bounds preview and terminal remains terminal`() {
        var state = BackgroundRunState("Run")
        repeat(2_000) { state = state.reduce(ChatStreamEvent.Token("${random.nextInt()} ")) }
        assertTrue(state.preview.length <= 240)
        state = state.reduce(ChatStreamEvent.StreamEnd)
        val terminal = state.terminal
        repeat(100) { state = state.reduce(ChatStreamEvent.Token("late")) }
        assertEquals(terminal, state.terminal)
    }

    @Test fun `deep link parser is total over random encoded inputs`() {
        val hosts = listOf("new-chat", "session", "kanban", "git", "unknown")
        repeat(1_000) {
            val raw = "hermex-type-j://${hosts[random.nextInt(hosts.size)]}/${random.nextLong()}%20${random.nextInt()}"
            runCatching { DeepLinkParser.parse(Uri.parse(raw)) }.getOrElse { throw AssertionError("Parser threw for $raw", it) }
        }
    }
}
