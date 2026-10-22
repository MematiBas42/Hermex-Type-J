package io.github.jcartu.hermex.ui.feature.chat.hermes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.jcartu.hermex.model.hermes.BotAttachmentFailure
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Android bitmap coverage ported from `BotAttachmentSendingTests.swift`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HermesAttachmentPreparerTest {
    @Test
    fun `transparent image stays PNG and longest edge is bounded to 4096`() = runTest {
        val source = bitmapBytes(width = 4_200, height = 4, alpha = true)
        val prepared = HermesAttachmentPreparer.prepare(source, "/picked/overlay.png")

        assertEquals("overlay.png", prepared.name)
        assertEquals("image/png", prepared.mime)
        assertTrue(prepared.image)
        assertTrue(prepared.data.take(4).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(prepared.data, 0, prepared.data.size))
        assertEquals(4_096, max(decoded.width, decoded.height))
        assertTrue(decoded.hasAlpha())
        decoded.recycle()
    }

    @Test
    fun `opaque image becomes quality JPEG with its dimensions intact`() = runTest {
        val source = bitmapBytes(width = 40, height = 20, alpha = false)
        val prepared = HermesAttachmentPreparer.prepare(source, "photo.heic")

        assertEquals("photo.jpg", prepared.name)
        assertEquals("image/jpeg", prepared.mime)
        assertTrue(prepared.image)
        assertEquals(0xff.toByte(), prepared.data[0])
        assertEquals(0xd8.toByte(), prepared.data[1])
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(prepared.data, 0, prepared.data.size))
        assertEquals(40, decoded.width)
        assertEquals(20, decoded.height)
        assertFalse(decoded.hasAlpha())
        decoded.recycle()
    }

    @Test
    fun `allowed documents preserve bytes filename and preferred type`() = runTest {
        val bytes = "notes\nverbatim\u0000bytes".toByteArray()
        val prepared = HermesAttachmentPreparer.prepare(bytes, "/picked/notes.md")

        assertArrayEquals(bytes, prepared.data)
        assertSame(bytes, prepared.data)
        assertEquals("notes.md", prepared.name)
        assertEquals("text/markdown", prepared.mime)
        assertFalse(prepared.image)

        val archive = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val zip = HermesAttachmentPreparer.prepare(archive, "bundle.zip")
        assertArrayEquals(archive, zip.data)
        assertEquals("application/zip", zip.mime)
    }

    @Test
    fun `thumbnail renders bounded image and text previews without preparing bytes`() = runTest {
        val image = requireNotNull(
            HermesAttachmentPreparer.thumbnail(
                bitmapBytes(width = 1_200, height = 600, alpha = false),
                "photo.jpg",
            ),
        )
        assertTrue(image.take(4).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        BitmapFactory.decodeByteArray(image, 0, image.size).also { bitmap ->
            requireNotNull(bitmap)
            assertEquals(512, max(bitmap.width, bitmap.height))
            bitmap.recycle()
        }

        val text = requireNotNull(
            HermesAttachmentPreparer.thumbnail(
                "first line\nsecond line".toByteArray(),
                "notes.md",
            ),
        )
        BitmapFactory.decodeByteArray(text, 0, text.size).also { bitmap ->
            requireNotNull(bitmap)
            assertEquals(480, bitmap.width)
            assertEquals(320, bitmap.height)
            bitmap.recycle()
        }


        assertNull(HermesAttachmentPreparer.thumbnail(byteArrayOf(1, 2, 3), "archive.zip"))
    }

    @Test
    fun `unsupported type and unreadable image are distinct failures`() = runTest {
        assertSame(
            BotAttachmentFailure.Type,
            runCatching {
                HermesAttachmentPreparer.prepare(byteArrayOf(1), "tool.exe")
            }.exceptionOrNull(),
        )
        assertSame(
            BotAttachmentFailure.Unreadable,
            runCatching {
                HermesAttachmentPreparer.prepare(byteArrayOf(1, 2, 3), "broken.png")
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `admission allows exact limits and rejects count file and total overflow`() {
        HermesAttachmentPreparer.admit(
            existingCount = 7,
            existingBytes = HermesAttachmentPreparer.MAXIMUM_FILE_BYTES,
            nextBytes = HermesAttachmentPreparer.MAXIMUM_FILE_BYTES,
        )

        assertLimit {
            HermesAttachmentPreparer.admit(
                existingCount = 8,
                existingBytes = 0,
                nextBytes = 1,
            )
        }
        assertLimit {
            HermesAttachmentPreparer.admit(
                existingCount = 0,
                existingBytes = 0,
                nextBytes = HermesAttachmentPreparer.MAXIMUM_FILE_BYTES + 1,
            )
        }
        assertLimit {
            HermesAttachmentPreparer.admit(
                existingCount = 1,
                existingBytes = HermesAttachmentPreparer.MAXIMUM_TOTAL_BYTES,
                nextBytes = 1,
            )
        }
        assertLimit {
            HermesAttachmentPreparer.admit(existingCount = 0, existingBytes = 0, nextBytes = 0)
        }
    }


    private fun assertLimit(block: () -> Unit) {
        val failure = assertThrows(BotAttachmentFailure::class.java) { block() }
        assertSame(BotAttachmentFailure.Limit, failure)
    }

    private fun bitmapBytes(width: Int, height: Int, alpha: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(alpha)
        bitmap.eraseColor(if (alpha) Color.argb(128, 220, 30, 30) else Color.rgb(220, 30, 30))
        return try {
            ByteArrayOutputStream().use { output ->
                val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                assertTrue(bitmap.compress(format, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
