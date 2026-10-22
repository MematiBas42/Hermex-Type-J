package io.github.jcartu.hermex.core.share

import io.github.jcartu.hermex.data.ChatDraftKey
import io.github.jcartu.hermex.data.ChatDraftShareFile
import io.github.jcartu.hermex.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The #1181 share transfer for a Hermes server — port of the Hermes session
 * list's share claim (`Features/SessionList/HermesSessionListView.swift`:
 * "the list claims it as it starts moving it into its new session's draft and
 * routes it once the draft holds it"; a failed move stays queued, #1196).
 *
 * The Hermes home calls [transfer] when a new-chat request is live, before
 * opening the composer: it owns the Profile resolution (listed Profile or the
 * request's), this layer owns the inbox read, file preparation, the draft
 * write and the inbox acknowledgement.
 */
object HermesShareImport {

    /** Claim guard: one transfer per share at a time (Swift's `claimShare`). */
    private val claimLock = Any()
    private val claimed = mutableSetOf<String>()

    /**
     * Moves the pending shared import [shareId] into the new-chat draft of
     * [serverId]'s Hermes server (`ChatDraftKey.hermesSession(…, key = null)`
     * — the same key the new-session composer restores).
     *
     * Returns false when the pending import no longer matches [shareId] (the
     * user discarded it, or another transfer owns it). Returns true only after
     * `ChatDraftStore.appendShare` committed the share durably AND the inbox
     * acknowledged it. Any failure (unreadable file, storage error) throws and
     * leaves the inbox queued, so the share is offered again — the durable
     * inbox is never cleared before the draft holds the share.
     */
    suspend fun transfer(
        container: AppContainer,
        serverId: String,
        connectionID: String,
        profile: String,
        shareId: String,
    ): Boolean {
        synchronized(claimLock) { if (!claimed.add(shareId)) return false }
        try {
            val import = container.sharedImports.pending.value?.takeIf { it.id == shareId }
                ?: return false
            val files = withContext(Dispatchers.IO) {
                import.attachments.map { attachment ->
                    ChatDraftShareFile(
                        data = attachment.file.readBytes(),
                        name = attachment.filename,
                        mime = attachment.mimeType ?: "application/octet-stream",
                        isImage = attachment.mimeType?.startsWith("image/") == true,
                    )
                }
            }
            container.cacheStore.chatDraftStore.appendShare(
                shareId = import.id,
                text = import.text,
                files = files,
                key = ChatDraftKey.hermesSession(serverId, connectionID, profile, null),
                maximumFileBytes = SharedImportRepository.MAX_ATTACHMENT_BYTES.toInt(),
            )
            container.sharedImports.acknowledge(import.id)
            return true
        } finally {
            synchronized(claimLock) { claimed.remove(shareId) }
        }
    }
}
