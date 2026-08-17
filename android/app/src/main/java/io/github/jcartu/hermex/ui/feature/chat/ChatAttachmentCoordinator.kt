package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.PendingAttachment
import io.github.jcartu.hermex.net.HermesApi
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * Port of `ChatAttachmentSendPreparation` from `ChatAttachmentCoordinator.swift`.
 */
data class ChatAttachmentSendPreparation(
    val attachments: List<PendingAttachment>,
    val messageAttachments: List<MessageAttachment>,
) {
    /** The `attachments[]` payload for `POST /api/chat` — null when nothing is staged. */
    val apiPayloads: List<JsonElement>?
        get() = if (attachments.isEmpty()) null else attachments.map { it.toJSONValue().element }

    fun chatMessageText(draft: String): String =
        PendingAttachment.chatMessageText(draft = draft, attachments = attachments)
}

/**
 * Port of `ChatAttachmentCoordinator.swift` — upload/staging state for the
 * composer attachment strip.
 *
 * Instead of the iOS delegate + `@Observable` pair, the coordinator mutates
 * the shared [ChatUiState] flow directly (`pendingAttachments`,
 * `isUploadingAttachment`, `uploadAttachmentErrorMessage`) and reads the
 * session identity (`sessionId`, `isViewingCachedData`) from it.
 *
 * The iOS `localAttachmentPreviews` map (message-keyed thumbnail bytes) is not
 * ported: Android renders transcript attachments through Coil from the
 * authenticated raw-file URL, so no client-side preview store is needed.
 * Composer-provided thumbnail bytes still ride on [PendingAttachment.thumbnailData]
 * for the pre-send strip.
 */
class ChatAttachmentCoordinator(
    private val api: HermesApi,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var activeUploadCount = 0
    private val reservedUploadFilenames = mutableSetOf<String>()

    /**
     * Port of `uploadAttachment(data:filename:previewData:)` +
     * `performUpload`: validates, uploads, and appends the resulting
     * [PendingAttachment] to the staged list. Failures surface via
     * [ChatUiState.uploadAttachmentErrorMessage] and return null.
     */
    suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        previewData: ByteArray? = null,
    ): PendingAttachment? {
        val attachment = performUpload(data, filename, previewData) ?: return null
        state.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
        return attachment
    }

    private suspend fun performUpload(
        data: ByteArray,
        filename: String,
        previewData: ByteArray?,
    ): PendingAttachment? {
        if (state.value.isViewingCachedData) {
            setUploadAttachmentError("Reconnect to the server to upload attachments.")
            return null
        }

        if (data.size > PendingAttachment.maximumUploadBytes) {
            setUploadAttachmentError(PendingAttachment.uploadTooLargeMessage(filename))
            return null
        }

        val sessionId = state.value.sessionId
        if (sessionId == null) {
            setUploadAttachmentError("The server did not provide a session ID.")
            return null
        }

        val displayFilename = normalizedAttachmentFilename(filename)
        val uploadFilename = reserveUploadFilename(preferredFilename = displayFilename)

        activeUploadCount += 1
        state.update { it.copy(isUploadingAttachment = true, uploadAttachmentErrorMessage = null) }
        try {
            val response = api.uploadFile(sessionId = sessionId, data = data, filename = uploadFilename)
            response.error?.let { errorMessage ->
                setUploadAttachmentError(errorMessage)
                return null
            }

            val path = response.path
            if (path.isNullOrEmpty()) {
                setUploadAttachmentError("The server did not return the uploaded file path.")
                return null
            }

            return PendingAttachment(
                name = displayFilename,
                path = path,
                mime = response.mime ?: "application/octet-stream",
                size = response.size,
                isImage = response.isImage ?: false,
                thumbnailData = if (response.isImage == true) previewData else null,
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            setUploadAttachmentError(error.message ?: "The upload failed.")
            return null
        } finally {
            releaseReservedUploadFilename(uploadFilename)
            activeUploadCount = maxOf(activeUploadCount - 1, 0)
            state.update { it.copy(isUploadingAttachment = activeUploadCount > 0) }
        }
    }

    fun clearPendingAttachments() {
        state.update { it.copy(pendingAttachments = emptyList(), uploadAttachmentErrorMessage = null) }
    }

    fun removePendingAttachment(id: String) {
        state.update { current ->
            current.copy(pendingAttachments = current.pendingAttachments.filterNot { it.id == id })
        }
    }

    fun setUploadAttachmentError(message: String?) {
        state.update { it.copy(uploadAttachmentErrorMessage = message) }
    }

    /**
     * Port of `prepareForSend(localMessageID:)`: snapshots the staged
     * attachments as [MessageAttachment]s for the optimistic bubble and clears
     * the pending list. The caller restores [ChatAttachmentSendPreparation.attachments]
     * via [restorePendingAttachments] when the send fails.
     */
    fun prepareForSend(): ChatAttachmentSendPreparation {
        val attachmentsForSend = state.value.pendingAttachments
        val messageAttachments = attachmentsForSend.map { pending ->
            MessageAttachment(
                name = pending.name,
                path = pending.path,
                mime = pending.mime,
                size = pending.size,
                isImage = pending.isImage,
            )
        }

        state.update { it.copy(pendingAttachments = emptyList()) }
        return ChatAttachmentSendPreparation(
            attachments = attachmentsForSend,
            messageAttachments = messageAttachments,
        )
    }

    /** Port of `restorePendingAttachments(_:)` — failed sends re-stage at the front. */
    fun restorePendingAttachments(attachments: List<PendingAttachment>) {
        if (attachments.isEmpty()) return
        state.update { it.copy(pendingAttachments = attachments + it.pendingAttachments) }
    }

    /** Port of `consumePendingAttachments()` — used by /queue, /steer, /interrupt. */
    fun consumePendingAttachments(): List<PendingAttachment> {
        val attachments = state.value.pendingAttachments
        state.update { it.copy(pendingAttachments = emptyList()) }
        return attachments
    }

    /** Port of `replacePendingAttachments(_:)` — used by the queued-message drain. */
    fun replacePendingAttachments(attachments: List<PendingAttachment>) {
        state.update { it.copy(pendingAttachments = attachments) }
    }

    // MARK: - Filename reservation (port of the private Swift helpers)

    private fun reserveUploadFilename(preferredFilename: String): String {
        val existingKeys = reservedUploadFilenames.toMutableSet()
        for (attachment in state.value.pendingAttachments) {
            existingKeys.add(filenameKey(attachment.name))
            val uploadedFilename = lastPathComponent(attachment.path)
            if (uploadedFilename.isNotEmpty()) {
                existingKeys.add(filenameKey(uploadedFilename))
            }
        }

        var candidate = preferredFilename
        while (existingKeys.contains(filenameKey(candidate))) {
            candidate = uniquedAttachmentFilename(preferredFilename)
        }

        reservedUploadFilenames.add(filenameKey(candidate))
        return candidate
    }

    private fun releaseReservedUploadFilename(filename: String) {
        reservedUploadFilenames.remove(filenameKey(filename))
    }

    companion object {
        /** Port of `normalizedAttachmentFilename(_:)`. */
        fun normalizedAttachmentFilename(filename: String): String {
            val trimmed = lastPathComponent(filename).trim()
            return trimmed.ifEmpty { "attachment" }
        }

        private fun filenameKey(filename: String): String =
            normalizedAttachmentFilename(filename).lowercase()

        /** Port of `uniquedAttachmentFilename(_:)` — `base-<8charuuid>.ext`. */
        fun uniquedAttachmentFilename(filename: String): String {
            val normalized = normalizedAttachmentFilename(filename)
            val dotIndex = normalized.lastIndexOf('.')
            val fileExtension = if (dotIndex > 0) normalized.substring(dotIndex + 1) else ""
            val baseName = if (dotIndex > 0) normalized.substring(0, dotIndex) else normalized
            val safeBaseName = baseName.ifEmpty { "attachment" }
            val suffix = UUID.randomUUID().toString().take(8).lowercase()

            if (fileExtension.isEmpty()) {
                return "$safeBaseName-$suffix"
            }

            return "$safeBaseName-$suffix.$fileExtension"
        }

        private fun lastPathComponent(path: String): String =
            path.trimEnd('/').substringAfterLast('/')
    }
}
