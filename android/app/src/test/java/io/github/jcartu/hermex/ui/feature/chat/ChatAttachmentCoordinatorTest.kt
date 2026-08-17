package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.PendingAttachment
import io.github.jcartu.hermex.model.UploadResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatAttachmentCoordinatorTest {

    private lateinit var api: HermesApi
    private lateinit var state: MutableStateFlow<ChatUiState>
    private lateinit var coordinator: ChatAttachmentCoordinator

    @Before
    fun setUp() {
        api = mockk()
        state = MutableStateFlow(ChatUiState(sessionId = "s1"))
        coordinator = ChatAttachmentCoordinator(api = api, state = state)
    }

    private fun stubUpload(
        path: String? = "/uploads/a.png",
        mime: String? = "image/png",
        size: Int? = 3,
        isImage: Boolean? = true,
        error: String? = null,
    ) {
        coEvery { api.uploadFile(any(), any(), any()) } returns UploadResponse(
            filename = "a.png",
            path = path,
            size = size,
            mime = mime,
            isImage = isImage,
            error = error,
        )
    }

    // MARK: - Upload

    @Test
    fun `successful upload appends a pending attachment`() = runTest {
        stubUpload()

        val attachment = coordinator.uploadAttachment(byteArrayOf(1, 2, 3), "a.png")

        assertNotNull(attachment)
        val pending = state.value.pendingAttachments.single()
        assertEquals("a.png", pending.name)
        assertEquals("/uploads/a.png", pending.path)
        assertEquals("image/png", pending.mime)
        assertEquals(3, pending.size)
        assertTrue(pending.isImage)
        assertFalse(state.value.isUploadingAttachment)
        assertNull(state.value.uploadAttachmentErrorMessage)
        coVerify(exactly = 1) { api.uploadFile("s1", any(), "a.png") }
    }

    @Test
    fun `filename is normalized to its last path component`() = runTest {
        stubUpload()

        coordinator.uploadAttachment(byteArrayOf(1), "photos/2026/a.png")

        coVerify { api.uploadFile("s1", any(), "a.png") }
        assertEquals("a.png", state.value.pendingAttachments.single().name)
    }

    @Test
    fun `oversized upload is rejected client-side before the network`() = runTest {
        val oversized = ByteArray(PendingAttachment.maximumUploadBytes + 1)

        val attachment = coordinator.uploadAttachment(oversized, "big.bin")

        assertNull(attachment)
        assertEquals(
            PendingAttachment.uploadTooLargeMessage("big.bin"),
            state.value.uploadAttachmentErrorMessage,
        )
        assertTrue(state.value.pendingAttachments.isEmpty())
        coVerify(exactly = 0) { api.uploadFile(any(), any(), any()) }
    }

    @Test
    fun `upload while viewing cached data is rejected`() = runTest {
        state.value = state.value.copy(isViewingCachedData = true)

        val attachment = coordinator.uploadAttachment(byteArrayOf(1), "a.png")

        assertNull(attachment)
        assertEquals(
            "Reconnect to the server to upload attachments.",
            state.value.uploadAttachmentErrorMessage,
        )
        coVerify(exactly = 0) { api.uploadFile(any(), any(), any()) }
    }

    @Test
    fun `upload without a session id is rejected`() = runTest {
        state.value = state.value.copy(sessionId = null)

        assertNull(coordinator.uploadAttachment(byteArrayOf(1), "a.png"))
        assertEquals(
            "The server did not provide a session ID.",
            state.value.uploadAttachmentErrorMessage,
        )
    }

    @Test
    fun `server error field surfaces and nothing is staged`() = runTest {
        stubUpload(error = "disk full")

        assertNull(coordinator.uploadAttachment(byteArrayOf(1), "a.png"))
        assertEquals("disk full", state.value.uploadAttachmentErrorMessage)
        assertTrue(state.value.pendingAttachments.isEmpty())
    }

    @Test
    fun `missing path in the response surfaces an error`() = runTest {
        stubUpload(path = "")

        assertNull(coordinator.uploadAttachment(byteArrayOf(1), "a.png"))
        assertEquals(
            "The server did not return the uploaded file path.",
            state.value.uploadAttachmentErrorMessage,
        )
    }

    @Test
    fun `transport failure rolls back and surfaces the error`() = runTest {
        coEvery { api.uploadFile(any(), any(), any()) } throws ApiError.Http(500, "boom")

        assertNull(coordinator.uploadAttachment(byteArrayOf(1), "a.png"))
        assertNotNull(state.value.uploadAttachmentErrorMessage)
        assertTrue(state.value.pendingAttachments.isEmpty())
        assertFalse(state.value.isUploadingAttachment)
    }

    @Test
    fun `missing mime falls back to octet-stream and non-image drops the thumbnail`() = runTest {
        stubUpload(mime = null, isImage = false)

        coordinator.uploadAttachment(byteArrayOf(1), "notes.txt", previewData = byteArrayOf(9))

        val pending = state.value.pendingAttachments.single()
        assertEquals("application/octet-stream", pending.mime)
        assertFalse(pending.isImage)
        assertNull(pending.thumbnailData)
    }

    @Test
    fun `duplicate filename gets a uniqued upload name while display name stays`() = runTest {
        val uploadedFilenames = mutableListOf<String>()
        coEvery { api.uploadFile(any(), any(), capture(uploadedFilenames)) } answers {
            UploadResponse(
                filename = thirdArg(),
                path = "/uploads/${thirdArg<String>()}",
                size = 1,
                mime = "image/png",
                isImage = true,
            )
        }

        coordinator.uploadAttachment(byteArrayOf(1), "a.png")
        coordinator.uploadAttachment(byteArrayOf(2), "a.png")

        assertEquals(2, uploadedFilenames.size)
        assertEquals("a.png", uploadedFilenames[0])
        assertNotEquals("a.png", uploadedFilenames[1])
        assertTrue(uploadedFilenames[1].startsWith("a-"))
        assertTrue(uploadedFilenames[1].endsWith(".png"))
        // Display name stays the user-visible original for both.
        assertEquals(listOf("a.png", "a.png"), state.value.pendingAttachments.map { it.name })
    }

    // MARK: - Staging operations

    @Test
    fun `prepareForSend snapshots and clears the staged attachments`() = runTest {
        stubUpload()
        coordinator.uploadAttachment(byteArrayOf(1), "a.png")

        val preparation = coordinator.prepareForSend()

        assertTrue(state.value.pendingAttachments.isEmpty())
        assertEquals(1, preparation.attachments.size)
        val messageAttachment = preparation.messageAttachments.single()
        assertEquals("a.png", messageAttachment.name)
        assertEquals("/uploads/a.png", messageAttachment.path)
        assertEquals(true, messageAttachment.isImage)

        assertEquals("draft\n\n[Attached files: /uploads/a.png]", preparation.chatMessageText("draft"))
        assertEquals(1, preparation.apiPayloads?.size)
    }

    @Test
    fun `prepareForSend with nothing staged has null payloads and unchanged text`() {
        val preparation = coordinator.prepareForSend()

        assertNull(preparation.apiPayloads)
        assertEquals("draft", preparation.chatMessageText("draft"))
    }

    @Test
    fun `restore re-stages failed attachments at the front`() = runTest {
        stubUpload()
        coordinator.uploadAttachment(byteArrayOf(1), "a.png")
        val preparation = coordinator.prepareForSend()

        stubUpload(path = "/uploads/b.png")
        coordinator.uploadAttachment(byteArrayOf(2), "b.png")
        coordinator.restorePendingAttachments(preparation.attachments)

        assertEquals(
            listOf("/uploads/a.png", "/uploads/b.png"),
            state.value.pendingAttachments.map { it.path },
        )
    }

    @Test
    fun `consume empties the staged list and returns it`() = runTest {
        stubUpload()
        coordinator.uploadAttachment(byteArrayOf(1), "a.png")

        val consumed = coordinator.consumePendingAttachments()

        assertEquals(1, consumed.size)
        assertTrue(state.value.pendingAttachments.isEmpty())
    }

    @Test
    fun `remove and clear drop staged attachments`() = runTest {
        stubUpload()
        coordinator.uploadAttachment(byteArrayOf(1), "a.png")
        val id = state.value.pendingAttachments.single().id

        coordinator.removePendingAttachment(id)
        assertTrue(state.value.pendingAttachments.isEmpty())

        coordinator.uploadAttachment(byteArrayOf(2), "a.png")
        coordinator.setUploadAttachmentError("x")
        coordinator.clearPendingAttachments()
        assertTrue(state.value.pendingAttachments.isEmpty())
        assertNull(state.value.uploadAttachmentErrorMessage)
    }
}
