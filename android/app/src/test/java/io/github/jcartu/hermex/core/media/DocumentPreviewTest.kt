package io.github.jcartu.hermex.core.media

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentPreviewTest {
    @Test
    fun `MIME and extension routes cover PDF image text code and external documents`() {
        assertEquals(DocumentPreviewRoute.PDF, DocumentPreview.route("report.bin", "application/pdf"))
        assertEquals(DocumentPreviewRoute.PDF, DocumentPreview.route("report.PDF", null))
        assertEquals(DocumentPreviewRoute.IMAGE, DocumentPreview.route("photo", "image/webp"))
        assertEquals(DocumentPreviewRoute.IMAGE, DocumentPreview.route("photo.PNG", null))
        assertEquals(DocumentPreviewRoute.TEXT, DocumentPreview.route("Main.kt", null))
        assertEquals(DocumentPreviewRoute.TEXT, DocumentPreview.route("payload", "application/json; charset=utf-8"))
        assertEquals(DocumentPreviewRoute.EXTERNAL, DocumentPreview.route("proposal.docx", null))
        assertEquals(DocumentPreviewRoute.EXTERNAL, DocumentPreview.route("archive.zip", "application/zip"))
    }

    @Test
    fun `size cap accepts exactly 25 MB and rejects first byte over`() {
        assertNull(DocumentPreview.sizeRejection(DocumentPreview.MAX_BYTES))
        assertEquals(
            DocumentPreview.TOO_LARGE_MESSAGE,
            DocumentPreview.sizeRejection(DocumentPreview.MAX_BYTES + 1),
        )
    }

    @Test
    fun `bounded stream never returns bytes past cap`() {
        val source = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(source, DocumentPreview.readCapped(ByteArrayInputStream(source), 4))
        val error = assertThrows(DocumentPreviewTooLargeException::class.java) {
            DocumentPreview.readCapped(ByteArrayInputStream(source), 3)
        }
        assertEquals(3L, error.limitBytes)
        assertEquals(DocumentPreview.TOO_LARGE_MESSAGE, error.message)
    }
}
