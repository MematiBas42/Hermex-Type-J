package io.github.jcartu.hermex.core.media

import io.github.jcartu.hermex.ui.feature.chat.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUtilitiesTest {
    @Test fun `speech normalization removes markdown URLs and code payloads`() {
        val normalized = SpeechTextNormalizer.normalize(
            """# Result
               |Read [the guide](https://example.com) and `inline code`.
               |```kotlin
               |println("secret")
               |```
               |See https://secret.example/token
            """.trimMargin(),
        )!!
        assertTrue(normalized.contains("Result"))
        assertTrue(normalized.contains("the guide"))
        assertTrue(normalized.contains("inline code"))
        assertTrue(normalized.contains("Code block"))
        assertFalse(normalized.contains("secret.example"))
        assertFalse(normalized.contains("println"))
    }

    @Test fun `speech normalization rejects empty markup`() {
        assertNull(SpeechTextNormalizer.normalize("***  ___  |||"))
    }

    @Test fun `voice note duration formats safely`() {
        assertEquals("0:00", formatDuration(-1))
        assertEquals("0:09", formatDuration(9_999))
        assertEquals("2:05", formatDuration(125_000))
    }
}
