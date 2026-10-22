package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.data.ChatDraft
import io.github.jcartu.hermex.data.ChatDraftKey
import io.github.jcartu.hermex.data.ChatDraftStore
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionFenceRefused
import io.github.jcartu.hermex.model.hermes.HermesSessionRefusal
import io.github.jcartu.hermex.net.hermes.BotSettingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Result of one resumable pass; a paused result keeps every persisted marker for the next pass. */
data class MoveRunResult(val record: MoveRecord, val errorMessage: String? = null)

/**
 * Android port of `MoveToHermesRunner.swift` (#1216). The journal in [MoveToHermesStore] is the
 * only progress authority. Imports are idempotent: a resumed DISPATCHING item is located first;
 * if Hermes has it the import is skipped, otherwise the same id is safe to send again. Metadata
 * writes with an unknown outcome are only settled by reading and are never resent.
 */
class MoveToHermesRunner(
    private val sourceServerId: String,
    private val destinationServerId: String,
    private val store: MoveToHermesStore,
    private val webui: MoveWebuiClient,
    private val hermes: MoveHermesClient,
    private val drafts: ChatDraftStore,
) {
    suspend fun run(): MoveRunResult {
        store.setStage(sourceServerId, MoveStage.RUNNING)
        try {
            hermes.connect()
            settleDispatchedImports()
            copyMissing()
            reconcileMetadata()
            moveDrafts()
            val current = store.recordForSource(sourceServerId) ?: error("Move record is missing.")
            return if (hasWorkLeft(current)) {
                MoveRunResult(store.setStage(sourceServerId, MoveStage.PAUSED))
            } else {
                MoveRunResult(store.finish(sourceServerId, finishedEarly = false))
            }
        } catch (cancelled: CancellationException) {
            val record = withContext(NonCancellable) { store.setStage(sourceServerId, MoveStage.PAUSED) }
            throw MovePassCancelled(record, cancelled)
        } catch (error: Throwable) {
            val record = withContext(NonCancellable) { store.setStage(sourceServerId, MoveStage.PAUSED) }
            return MoveRunResult(record, error.message ?: "The move paused before it finished.")
        } finally {
            withContext(NonCancellable) { runCatching { hermes.close() } }
        }
    }

    suspend fun finishEarly(): MoveRecord = store.finish(sourceServerId, finishedEarly = true)

    private suspend fun settleDispatchedImports() {
        val record = store.recordForSource(sourceServerId) ?: error("Move record is missing.")
        for (item in record.items.filter { it.copyState == MoveCopyState.DISPATCHING }) {
            coroutineContext.ensureActive()
            when (val location = MoveToHermesScanner.locate(item.chat.id, record.hermesProfiles, hermes)) {
                is MoveChatLocation -> when (location.kind) {
                    MoveChatLocation.Kind.PRESENT -> updateItem(item.chat.id) {
                        it.copy(copyState = MoveCopyState.PRESENT_UNCONFIRMED, locatedProfile = location.profile)
                    }
                    MoveChatLocation.Kind.MISSING -> updateItem(item.chat.id) {
                        it.copy(copyState = MoveCopyState.PENDING, locatedProfile = null)
                    }
                    MoveChatLocation.Kind.AMBIGUOUS -> updateItem(item.chat.id) {
                        it.copy(copyState = MoveCopyState.AMBIGUOUS, locatedProfile = null)
                    }
                    MoveChatLocation.Kind.UNKNOWN -> Unit
                }
            }
        }
    }

    private suspend fun copyMissing() {
        var record = store.recordForSource(sourceServerId) ?: error("Move record is missing.")
        val groups = record.items.filter { it.copyState == MoveCopyState.PENDING }.groupBy { it.chat.profile }
        for ((sourceProfile, originalItems) in groups) {
            coroutineContext.ensureActive()
            if (!webui.switchProfile(sourceProfile)) {
                val ids = originalItems.mapTo(HashSet()) { it.chat.id }
                store.updateRecord(sourceServerId) { held ->
                    held.copy(items = held.items.map { item ->
                        if (item.chat.id in ids && item.copyState == MoveCopyState.PENDING) {
                            item.copy(copyState = MoveCopyState.UNREADABLE)
                        } else item
                    })
                }
                continue
            }

            val readyByDestination = linkedMapOf<String, MutableList<MoveImportCodec.Prepared>>()
            for (original in originalItems) {
                coroutineContext.ensureActive()
                val current = store.recordForSource(sourceServerId)?.items?.firstOrNull { it.chat.id == original.chat.id }
                    ?: continue
                if (current.copyState != MoveCopyState.PENDING) continue
                when (val location = MoveToHermesScanner.locate(current.chat.id, record.hermesProfiles, hermes)) {
                    is MoveChatLocation -> when (location.kind) {
                        MoveChatLocation.Kind.PRESENT -> {
                            updateItem(current.chat.id) {
                                it.copy(copyState = MoveCopyState.ALREADY_PRESENT, locatedProfile = location.profile)
                            }
                            continue
                        }
                        MoveChatLocation.Kind.AMBIGUOUS -> {
                            updateItem(current.chat.id) { it.copy(copyState = MoveCopyState.AMBIGUOUS) }
                            continue
                        }
                        MoveChatLocation.Kind.UNKNOWN -> {
                            updateItem(current.chat.id) { it.copy(copyState = MoveCopyState.UNCHECKED) }
                            continue
                        }
                        MoveChatLocation.Kind.MISSING -> Unit
                    }
                }
                val destination = current.destinationProfile ?: run {
                    updateItem(current.chat.id) { it.copy(copyState = MoveCopyState.KEPT) }
                    continue
                }
                val preparation = try {
                    MoveImportCodec.prepare(webui.exportSession(current.chat.id), current.chat.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    MoveImportCodec.Preparation.Unreadable
                }
                when (preparation) {
                    MoveImportCodec.Preparation.Unreadable -> updateItem(current.chat.id) {
                        it.copy(copyState = MoveCopyState.UNREADABLE)
                    }
                    MoveImportCodec.Preparation.Oversized -> updateItem(current.chat.id) {
                        it.copy(copyState = MoveCopyState.OVERSIZED)
                    }
                    is MoveImportCodec.Preparation.Ready ->
                        readyByDestination.getOrPut(destination) { mutableListOf() } += preparation.prepared
                }
            }

            for ((destination, prepared) in readyByDestination) {
                val outcomes = MoveImportBatcher.run(
                    prepared = prepared,
                    destinationProfile = destination,
                    hermes = hermes,
                    willDispatch = { ids ->
                        val set = ids.toHashSet()
                        store.updateRecord(sourceServerId) { held ->
                            held.copy(items = held.items.map { item ->
                                if (item.chat.id in set && item.copyState == MoveCopyState.PENDING) {
                                    item.copy(copyState = MoveCopyState.DISPATCHING, locatedProfile = destination)
                                } else item
                            })
                        }
                    },
                )
                if (outcomes.isNotEmpty()) {
                    store.updateRecord(sourceServerId) { held ->
                        held.copy(items = held.items.map { item ->
                            val outcome = outcomes[item.chat.id] ?: return@map item
                            item.copy(
                                copyState = outcome.state,
                                locatedProfile = outcome.profile,
                                rejectionReason = outcome.reason,
                            )
                        })
                    }
                }
            }
            record = store.recordForSource(sourceServerId) ?: record
        }
    }

    private suspend fun reconcileMetadata() {
        val record = store.recordForSource(sourceServerId) ?: error("Move record is missing.")
        for (snapshot in record.items.filter { it.isInHermes }) {
            coroutineContext.ensureActive()
            var item = currentItem(snapshot.chat.id) ?: continue
            val profile = item.hermesProfile ?: continue
            val row = hermes.sessionRow(item.chat.id, profile)
            if (row == null || row["id"].text != item.chat.id || flag(row["hidden"])) {
                updateItem(item.chat.id) {
                    it.copy(
                        titleState = if (it.titleState == MoveFieldState.PENDING) MoveFieldState.SKIPPED else it.titleState,
                        pinnedState = if (it.pinnedState == MoveFieldState.PENDING) MoveFieldState.SKIPPED else it.pinnedState,
                        folderState = if (it.folderState == MoveFieldState.PENDING) MoveFieldState.SKIPPED else it.folderState,
                    )
                }
                continue
            }

            item = settleUnknownFields(item, row)
            item = reconcileTitle(item, row, profile)
            item = reconcilePinned(item, row, profile)
            reconcileFolder(item, row, profile, record.firstMove)
        }
    }

    private suspend fun settleUnknownFields(item: MoveItemProgress, row: BotJSON): MoveItemProgress {
        var updated = item
        if (updated.titleState == MoveFieldState.DISPATCHING) {
            updated = updated.copy(
                titleState = if (nonEmpty(row["title"].text) == updated.titleTarget) MoveFieldState.DONE else MoveFieldState.UNCONFIRMED,
            )
        }
        if (updated.pinnedState == MoveFieldState.DISPATCHING) {
            updated = updated.copy(pinnedState = if (flag(row["pinned"])) MoveFieldState.DONE else MoveFieldState.UNCONFIRMED)
        }
        if (updated.folderState == MoveFieldState.DISPATCHING) {
            updated = updated.copy(
                folderState = if (samePath(nonEmpty(row["cwd"].text), nonEmpty(updated.chat.workspace))) {
                    MoveFieldState.DONE
                } else MoveFieldState.UNCONFIRMED,
            )
        }
        if (updated != item) updateItem(item.chat.id) { updated }
        return updated
    }

    private suspend fun reconcileTitle(item: MoveItemProgress, row: BotJSON, profile: String): MoveItemProgress {
        if (item.titleState != MoveFieldState.PENDING) return item
        val target = item.titleTarget
        val current = nonEmpty(row["title"].text)
        if (target == null || (item.copyState != MoveCopyState.IMPORTED && !recordIsFirstMove())) {
            return updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.SKIPPED) }
        }
        if (current == target) return updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.DONE) }
        if (current != null) return updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.SKIPPED) }
        updateItem(item.chat.id) { it.copy(titleState = MoveFieldState.DISPATCHING) }
        return try {
            hermes.updateTitle(item.chat.id, profile, target)
            updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.DONE) }
        } catch (refusal: HermesSessionRefusal) {
            if (refusal.message.contains("already in use")) {
                val suffixed = MoveMetadata.suffixed(target, item.chat.id)
                updateItem(item.chat.id) { it.copy(titleTarget = suffixed, titleState = MoveFieldState.DISPATCHING) }
                try {
                    hermes.updateTitle(item.chat.id, profile, suffixed)
                    updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.DONE) }
                } catch (fence: HermesSessionFenceRefused) {
                    updateItem(item.chat.id) { it.copy(titleState = MoveFieldState.PENDING) }
                    throw fence
                } catch (_: HermesSessionRefusal) {
                    updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.REJECTED) }
                } catch (_: BotFailure.Rejected) {
                    updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.REJECTED) }
                }
            } else {
                updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.REJECTED) }
            }
        } catch (fence: HermesSessionFenceRefused) {
            updateItem(item.chat.id) { it.copy(titleState = MoveFieldState.PENDING) }
            throw fence
        } catch (failure: BotFailure.Rejected) {
            updateAndRead(item.chat.id) { it.copy(titleState = MoveFieldState.REJECTED) }
        }
    }

    private suspend fun reconcilePinned(item: MoveItemProgress, row: BotJSON, profile: String): MoveItemProgress {
        if (item.pinnedState != MoveFieldState.PENDING) return item
        if (item.copyState != MoveCopyState.IMPORTED || !item.chat.pinned) {
            return updateAndRead(item.chat.id) { it.copy(pinnedState = MoveFieldState.SKIPPED) }
        }
        if (flag(row["pinned"])) return updateAndRead(item.chat.id) { it.copy(pinnedState = MoveFieldState.DONE) }
        updateItem(item.chat.id) { it.copy(pinnedState = MoveFieldState.DISPATCHING) }
        return try {
            hermes.updatePinned(item.chat.id, profile, true)
            updateAndRead(item.chat.id) { it.copy(pinnedState = MoveFieldState.DONE) }
        } catch (fence: HermesSessionFenceRefused) {
            updateItem(item.chat.id) { it.copy(pinnedState = MoveFieldState.PENDING) }
            throw fence
        } catch (_: HermesSessionRefusal) {
            updateAndRead(item.chat.id) { it.copy(pinnedState = MoveFieldState.REJECTED) }
        } catch (_: BotFailure.Rejected) {
            updateAndRead(item.chat.id) { it.copy(pinnedState = MoveFieldState.REJECTED) }
        }
    }

    private suspend fun reconcileFolder(
        item: MoveItemProgress,
        row: BotJSON,
        profile: String,
        firstMove: Boolean,
    ): MoveItemProgress {
        if (item.folderState != MoveFieldState.PENDING) return item
        val target = nonEmpty(item.chat.workspace)
        if (target == null || (item.copyState != MoveCopyState.IMPORTED && !firstMove)) {
            return updateAndRead(item.chat.id) { it.copy(folderState = MoveFieldState.SKIPPED) }
        }
        val current = nonEmpty(row["cwd"].text)
        if (samePath(current, target)) return updateAndRead(item.chat.id) { it.copy(folderState = MoveFieldState.DONE) }
        if (current != null) return updateAndRead(item.chat.id) { it.copy(folderState = MoveFieldState.SKIPPED) }
        updateItem(item.chat.id) { it.copy(folderState = MoveFieldState.DISPATCHING) }
        return try {
            hermes.updateFolder(item.chat.id, profile, target)
            updateAndRead(item.chat.id) { it.copy(folderState = MoveFieldState.DONE) }
        } catch (failure: BotSettingFailure.Rejected) {
            updateAndRead(item.chat.id) { it.copy(folderState = MoveFieldState.REJECTED) }
        } catch (_: BotFailure.Rejected) {
            updateAndRead(item.chat.id) { it.copy(folderState = MoveFieldState.REJECTED) }
        }
    }

    private suspend fun moveDrafts() {
        val record = store.recordForSource(sourceServerId) ?: error("Move record is missing.")
        for (
            snapshot in record.items.filter {
                it.isInHermes && (record.firstMove || it.copyState == MoveCopyState.IMPORTED) &&
                    (it.draftState == MoveDraftState.PENDING || it.draftState == MoveDraftState.FAILED)
            }
        ) {
            coroutineContext.ensureActive()
            val item = currentItem(snapshot.chat.id) ?: continue
            val profile = item.hermesProfile ?: continue
            val row = try {
                hermes.sessionRow(item.chat.id, profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateItem(item.chat.id) { it.copy(draftState = MoveDraftState.FAILED) }
                continue
            }
            val state = when {
                row == null || row["id"].text != item.chat.id -> MoveDraftState.MISSING
                flag(row["hidden"]) || !row["parent_session_id"].text.isNullOrBlank() -> MoveDraftState.UNVERIFIED
                else -> MoveDraftMigrator.move(
                    drafts,
                    ChatDraftKey.session(sourceServerId, item.chat.id),
                    ChatDraftKey.session(destinationServerId, item.chat.id),
                )
            }
            updateItem(item.chat.id) { it.copy(draftState = state) }
        }
    }

    private fun hasWorkLeft(record: MoveRecord): Boolean = record.items.any { item ->
        item.copyState == MoveCopyState.PENDING || item.copyState == MoveCopyState.DISPATCHING ||
            item.copyState == MoveCopyState.UNREADABLE ||
            (item.isInHermes && (
                item.titleState == MoveFieldState.PENDING || item.titleState == MoveFieldState.DISPATCHING ||
                    item.pinnedState == MoveFieldState.PENDING || item.pinnedState == MoveFieldState.DISPATCHING ||
                    item.folderState == MoveFieldState.PENDING || item.folderState == MoveFieldState.DISPATCHING ||
                    ((record.firstMove || item.copyState == MoveCopyState.IMPORTED) &&
                        (item.draftState == MoveDraftState.PENDING || item.draftState == MoveDraftState.FAILED))
                ))
    }

    private fun recordIsFirstMove(): Boolean = store.recordForSource(sourceServerId)?.firstMove == true

    private suspend fun updateItem(id: String, transform: (MoveItemProgress) -> MoveItemProgress) {
        store.updateItem(sourceServerId, id, transform)
    }

    private fun currentItem(id: String): MoveItemProgress? =
        store.recordForSource(sourceServerId)?.items?.firstOrNull { it.chat.id == id }

    private suspend fun updateAndRead(id: String, transform: (MoveItemProgress) -> MoveItemProgress): MoveItemProgress {
        val record = store.updateItem(sourceServerId, id, transform)
        return record.items.first { it.chat.id == id }
    }

    private fun flag(value: BotJSON): Boolean = value.flag ?: (value.integer?.let { it != 0 } ?: false)

    private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun samePath(first: String?, second: String?): Boolean =
        first?.trimEnd('/') == second?.trimEnd('/')

    private class MovePassCancelled(val record: MoveRecord, cause: CancellationException) : CancellationException(cause.message)
}

/** Draft-key migration from `MoveToHermesReconcile.moveDrafts` (#1214). */
object MoveDraftMigrator {
    suspend fun move(store: ChatDraftStore, source: ChatDraftKey, destination: ChatDraftKey): MoveDraftState {
        val sourceDraft = store.draft(source)?.takeUnless(ChatDraft::isEmpty)
        val destinationDraft = store.draft(destination)?.takeUnless(ChatDraft::isEmpty)
        if (sourceDraft == null) {
            return if (destinationDraft == null) MoveDraftState.NOTHING_TO_MOVE else MoveDraftState.ALREADY_MOVED
        }
        if (destinationDraft != null) return MoveDraftState.CONFLICT
        store.moveDraft(source, destination)
        store.flush()
        val sourceAfter = store.draft(source)?.takeUnless(ChatDraft::isEmpty)
        val destinationAfter = store.draft(destination)?.takeUnless(ChatDraft::isEmpty)
        return if (sourceAfter == null && destinationAfter != null) MoveDraftState.MOVED else MoveDraftState.FAILED
    }
}
