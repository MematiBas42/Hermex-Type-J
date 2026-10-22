package io.github.jcartu.hermex.ui.feature.movetohermes

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable journal and routing record for `Features/MoveToHermes/MoveToHermesJournal.swift`
 * and `Auth/AuthManager+MoveToHermes.swift` (#1215, #1216).
 *
 * Every mutation replaces one compact JSON snapshot atomically before it is published. Import
 * dispatches and metadata writes are therefore marked [MoveCopyState.DISPATCHING] /
 * [MoveFieldState.DISPATCHING] on disk before the request leaves. A resumed run first settles
 * those markers by reading Hermes and never blindly repeats an unknown write.
 */
class MoveToHermesStore(
    private val file: File,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<MovePersistentState> = _state.asStateFlow()

    fun recordForSource(sourceServerId: String): MoveRecord? =
        _state.value.moves.firstOrNull { it.sourceServerId == sourceServerId }

    fun recordsForDestination(destinationServerId: String): List<MoveRecord> =
        _state.value.moves.filter { it.destinationServerId == destinationServerId }

    fun promptState(sourceServerId: String): MovePromptState? = _state.value.prompts[sourceServerId]

    /** Marks a webui server asked before its one-time question is presented. */
    suspend fun claimQuestion(sourceServerId: String): Boolean = lock.withLock {
        val current = _state.value
        if (current.prompts[sourceServerId] != null || current.moves.any { it.sourceServerId == sourceServerId }) {
            return@withLock false
        }
        commitLocked(current.copy(prompts = current.prompts + (sourceServerId to MovePromptState.ASKED)))
        true
    }

    suspend fun dismissInvite(sourceServerId: String) = mutateState { current ->
        if (current.prompts[sourceServerId] == MovePromptState.DISMISSED) current
        else current.copy(prompts = current.prompts + (sourceServerId to MovePromptState.DISMISSED))
    }

    /**
     * Commits Review before the first destination write. Repeating a completed move keeps its
     * original Profile mapping and moved-session routing entries while replacing only this pass's
     * journal, exactly like Copy Missing Chats Again.
     */
    suspend fun beginMove(
        sourceServerId: String,
        destinationServerId: String,
        destinationCreatedAt: Double,
        profileMappings: List<MoveProfileMapping>,
        hermesProfiles: List<String>,
        items: List<MoveItemProgress>,
    ): MoveRecord = lock.withLock {
        val current = _state.value
        val old = current.moves.firstOrNull { it.sourceServerId == sourceServerId }
        require(old == null || old.destinationServerId == destinationServerId) {
            "This webui server already moved to another Hermes server."
        }
        val timestamp = now()
        val mappings = old?.profileMappings ?: profileMappings
        val initialTargets = items.filter { it.isInHermes }.mapNotNull { item ->
            item.hermesProfile?.let { profile ->
                item.chat.id to MovedSessionTarget(destinationServerId, item.chat.id, profile)
            }
        }.toMap()
        val record = MoveRecord(
            sourceServerId = sourceServerId,
            destinationServerId = destinationServerId,
            destinationCreatedAt = old?.destinationCreatedAt ?: destinationCreatedAt,
            profileMappings = mappings,
            hermesProfiles = hermesProfiles.distinct(),
            items = items,
            movedSessions = old?.movedSessions.orEmpty() + initialTargets,
            firstMove = old == null,
            stage = MoveStage.PAUSED,
            createdAtEpochMillis = old?.createdAtEpochMillis ?: timestamp,
            updatedAtEpochMillis = timestamp,
        )
        val nextMoves = current.moves.filterNot { it.sourceServerId == sourceServerId } + record
        commitLocked(
            current.copy(
                prompts = current.prompts + (sourceServerId to MovePromptState.ASKED),
                moves = nextMoves,
            ),
        )
        record
    }

    suspend fun updateRecord(
        sourceServerId: String,
        transform: (MoveRecord) -> MoveRecord,
    ): MoveRecord = lock.withLock {
        val current = _state.value
        val index = current.moves.indexOfFirst { it.sourceServerId == sourceServerId }
        require(index >= 0) { "Move record is missing." }
        val previous = current.moves[index]
        var updated = transform(previous).copy(updatedAtEpochMillis = now())
        require(updated.sourceServerId == previous.sourceServerId && updated.destinationServerId == previous.destinationServerId) {
            "A move cannot change servers."
        }
        val targets = updated.items.filter { it.isInHermes }.mapNotNull { item ->
            item.hermesProfile?.let { profile ->
                item.chat.id to MovedSessionTarget(updated.destinationServerId, item.chat.id, profile)
            }
        }.toMap()
        if (targets.isNotEmpty()) updated = updated.copy(movedSessions = updated.movedSessions + targets)
        val moves = current.moves.toMutableList().also { it[index] = updated }
        commitLocked(current.copy(moves = moves))
        updated
    }

    suspend fun updateItem(
        sourceServerId: String,
        sessionId: String,
        transform: (MoveItemProgress) -> MoveItemProgress,
    ): MoveRecord = updateRecord(sourceServerId) { record ->
        val index = record.items.indexOfFirst { it.chat.id == sessionId }
        require(index >= 0) { "Move item is missing." }
        record.copy(items = record.items.toMutableList().also { it[index] = transform(it[index]) })
    }

    suspend fun setStage(sourceServerId: String, stage: MoveStage): MoveRecord =
        updateRecord(sourceServerId) { it.copy(stage = stage) }

    suspend fun finish(sourceServerId: String, finishedEarly: Boolean): MoveRecord =
        updateRecord(sourceServerId) { record ->
            val report = MoveReportBuilder.build(record, finishedEarly)
            record.copy(
                stage = MoveStage.FINISHED,
                completedAtEpochMillis = now(),
                report = report,
                reportAcknowledged = false,
            )
        }


    suspend fun acknowledgeReport(sourceServerId: String): MoveRecord =
        updateRecord(sourceServerId) { it.copy(reportAcknowledged = true) }
    /** Removing a destination invalidates every legacy route it owned. */
    suspend fun removeDestination(destinationServerId: String) = mutateState { current ->
        current.copy(moves = current.moves.filterNot { it.destinationServerId == destinationServerId })
    }

    private suspend fun mutateState(transform: (MovePersistentState) -> MovePersistentState) {
        lock.withLock {
            val current = _state.value
            val updated = transform(current)
            if (updated != current) commitLocked(updated)
        }
    }

    private suspend fun commitLocked(value: MovePersistentState) {
        val encoded = json.encodeToString(MovePersistentState.serializer(), value)
        withContext(ioDispatcher) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(encoded, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        _state.value = value
    }

    private fun load(): MovePersistentState {
        if (!file.isFile) return MovePersistentState()
        return runCatching {
            json.decodeFromString(MovePersistentState.serializer(), file.readText(Charsets.UTF_8))
        }.getOrDefault(MovePersistentState())
    }
}
