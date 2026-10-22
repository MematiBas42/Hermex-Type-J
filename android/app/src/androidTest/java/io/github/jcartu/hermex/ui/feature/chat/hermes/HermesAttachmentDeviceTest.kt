package io.github.jcartu.hermex.ui.feature.chat.hermes

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** PdfRenderer owns Android file descriptors; exercise it on Android, not the host JVM. */
@RunWith(AndroidJUnit4::class)
class HermesAttachmentDeviceTest {
    @Test
    fun pdfFirstPageRendersWithinComposerPreviewBounds() = runBlocking {
        val document = PdfDocument()
        val bytes = try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            document.finishPage(page)
            ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
        val preview = HermesAttachmentPreparer.thumbnail(bytes, "report.pdf")
        assertNotNull("The first PDF page must produce a composer thumbnail", preview)
        val bitmap = BitmapFactory.decodeByteArray(preview!!, 0, preview.size)
        assertNotNull("The PDF preview must be a decodable image", bitmap)
        try {
            assertEquals(360, bitmap.width)
            assertEquals(480, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
