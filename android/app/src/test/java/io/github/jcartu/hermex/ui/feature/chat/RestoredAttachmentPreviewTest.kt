package io.github.jcartu.hermex.ui.feature.chat

import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoredAttachmentPreviewTest {
    @Test fun `restored text document renders a real thumbnail through composer generator`() = runBlocking {
        val bytes = "A restored document\nIts text remains visible in the composer".toByteArray()
        val preview = regenerateAttachmentPreview(bytes, "report.txt", "text/plain", "https://one", null)
        assertNotNull(preview)
        val bitmap = BitmapFactory.decodeByteArray(preview!!, 0, preview.size)
        assertNotNull(bitmap)
        assertEquals(480, bitmap.width)
        assertEquals(320, bitmap.height)
        assertTrue(preview.contentEquals(regenerateAttachmentPreview(bytes, "report.txt", "text/plain", "https://one", null)!!))
        bitmap.recycle()
    }
}
