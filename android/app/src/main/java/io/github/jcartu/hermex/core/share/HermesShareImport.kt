package io.github.jcartu.hermex.core.share

import io.github.jcartu.hermex.data.ChatDraftKey
import io.github.jcartu.hermex.data.ChatDraftShareFile
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesAttachmentPreparer
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
    ): Boolean = transfer(
        sharedImports = container.sharedImports,
        draftStore = container.cacheStore.chatDraftStore,
        serverId = serverId,
        connectionID = connectionID,
        profile = profile,
        shareId = shareId,
    )

    /** The transfer over its two stores, so unit tests can drive it without a container. */
    internal suspend fun transfer(
        sharedImports: SharedImportRepository,
        draftStore: io.github.jcartu.hermex.data.ChatDraftStore,
        serverId: String,
        connectionID: String,
        profile: String,
        shareId: String,
    ): Boolean {
        synchronized(claimLock) { if (!claimed.add(shareId)) return false }
        try {
            val import = sharedImports.pending.value?.takeIf { it.id == shareId }
                ?: return false
            val files = import.attachments.map { attachment ->
                // Every file is prepared exactly like a composer-staged
                // upload (`BotAttachmentUpload.prepare`): orientation-corrected
                // images bounded to 4096px re-encoded PNG/JPEG90, documents
                // byte-exact, 25 MiB per file. A refusal throws and keeps the
                // inbox queued.
                val raw = withContext(Dispatchers.IO) { attachment.file.readBytes() }
                val prepared = HermesAttachmentPreparer.prepare(raw, attachment.filename)
                ChatDraftShareFile(
                    data = prepared.data,
                    name = prepared.name,
                    mime = prepared.mime,
                    isImage = prepared.image,
                )
            }
            draftStore.appendShare(
                shareId = import.id,
                text = import.text,
                files = files,
                key = ChatDraftKey.hermesSession(serverId, connectionID, profile, null),
                maximumFileBytes = HermesAttachmentPreparer.MAXIMUM_FILE_BYTES.toInt(),
            )
            sharedImports.acknowledge(import.id)
            return true
        } finally {
            synchronized(claimLock) { claimed.remove(shareId) }
        }
    }
}
