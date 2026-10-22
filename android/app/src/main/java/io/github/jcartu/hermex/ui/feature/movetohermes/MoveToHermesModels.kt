package io.github.jcartu.hermex.ui.feature.movetohermes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Port of `Features/MoveToHermes/MoveToHermesScan.swift` (#1213). */
@Serializable
data class MoveSourceChat(
    val id: String,
    val profile: String = "default",
    val title: String? = null,
    val messageCount: Int? = null,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val workspace: String? = null,
    val isRunning: Boolean = false,
)

/** Exact-id location of one webui chat across all destination Profiles. */
@Serializable
data class MoveChatLocation(
    val kind: Kind,
    val profile: String? = null,
    val profiles: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind { MISSING, PRESENT, AMBIGUOUS, UNKNOWN }

    companion object {
        val Missing = MoveChatLocation(Kind.MISSING)
        val Unknown = MoveChatLocation(Kind.UNKNOWN)
        fun present(profile: String) = MoveChatLocation(Kind.PRESENT, profile = profile)
        fun ambiguous(profiles: List<String>) = MoveChatLocation(Kind.AMBIGUOUS, profiles = profiles)
    }
}

@Serializable
data class MoveScanEntry(
    val chat: MoveSourceChat,
    val location: MoveChatLocation,
)

@Serializable
data class MoveScanResult(
    val entries: List<MoveScanEntry>,
    val onlyProfile: String? = null,
    val runningSessionIds: List<String> = emptyList(),
)

@Serializable
data class HermesMoveProfileCatalog(
    val profiles: List<String>,
    val defaultProfile: String? = null,
)

/** One explicit Review choice. Null + [keepOnWebui] false is still unchosen. */
@Serializable
data class MoveProfileMapping(
    val sourceProfile: String,
    val destinationProfile: String? = null,
    val keepOnWebui: Boolean = false,
)

@Serializable
data class MoveProfileChoice(
    val sourceProfile: String,
    val chatCount: Int,
    val sameNameExists: Boolean,
)

/**
 * Pure Review mapping port of `MoveToHermesModel.initialMapping` and
 * `MoveToHermesReconcile.targets` (`MoveToHermesModel.swift`, #1266).
 */
object MoveToHermesPlanner {
    fun profileChoices(scan: MoveScanResult, webuiProfiles: List<String>, catalog: HermesMoveProfileCatalog): List<MoveProfileChoice> {
        val counts = scan.entries.groupingBy { it.chat.profile }.eachCount()
        return (webuiProfiles + counts.keys)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String> { if (it == "default") "" else it })
            .map { source ->
                MoveProfileChoice(
                    sourceProfile = source,
                    chatCount = counts[source] ?: 0,
                    sameNameExists = source in catalog.profiles,
                )
            }
    }

    fun initialMappings(
        choices: List<MoveProfileChoice>,
        catalog: HermesMoveProfileCatalog,
        existing: List<MoveProfileMapping> = emptyList(),
    ): List<MoveProfileMapping> {
        if (existing.isNotEmpty()) {
            val bySource = existing.associateBy { it.sourceProfile }
            return choices.map { bySource[it.sourceProfile] ?: MoveProfileMapping(it.sourceProfile) }
        }
        val default = catalog.defaultProfile?.takeIf { it in catalog.profiles }
        return choices.map { choice ->
            MoveProfileMapping(
                sourceProfile = choice.sourceProfile,
                destinationProfile = when {
                    choice.sameNameExists -> choice.sourceProfile
                    default != null -> default
                    else -> null
                },
            )
        }
    }

    fun progressItems(
        scan: MoveScanResult,
        mappings: List<MoveProfileMapping>,
    ): List<MoveItemProgress> {
        val destinations = mappings.associateBy { it.sourceProfile }
        return scan.entries.map { entry ->
            val mapping = destinations[entry.chat.profile]
            val mapped = mapping?.destinationProfile
            val (copyState, locatedProfile) = when (entry.location.kind) {
                MoveChatLocation.Kind.PRESENT -> MoveCopyState.ALREADY_PRESENT to entry.location.profile
                MoveChatLocation.Kind.AMBIGUOUS -> MoveCopyState.AMBIGUOUS to null
                MoveChatLocation.Kind.UNKNOWN -> MoveCopyState.UNCHECKED to null
                MoveChatLocation.Kind.MISSING -> when {
                    mapping?.keepOnWebui == true -> MoveCopyState.KEPT to null
                    mapped == null -> MoveCopyState.UNCHECKED to null
                    (entry.chat.messageCount ?: 0) > MoveImportCodec.MESSAGES_PER_SESSION -> MoveCopyState.OVERSIZED to null
                    else -> MoveCopyState.PENDING to mapped
                }
            }
            MoveItemProgress(
                chat = entry.chat,
                destinationProfile = mapped,
                locatedProfile = locatedProfile,
                copyState = copyState,
                titleTarget = MoveMetadata.title(entry.chat.title),
            )
        }
    }
}

@Serializable
enum class MoveCopyState {
    PENDING,
    DISPATCHING,
    IMPORTED,
    ALREADY_PRESENT,
    PRESENT_UNCONFIRMED,
    OVERSIZED,
    REJECTED,
    UNREADABLE,
    UNCHECKED,
    AMBIGUOUS,
    KEPT,
}

@Serializable
enum class MoveFieldState { PENDING, DISPATCHING, DONE, SKIPPED, REJECTED, UNCONFIRMED }

@Serializable
enum class MoveDraftState { PENDING, MOVED, ALREADY_MOVED, NOTHING_TO_MOVE, CONFLICT, UNVERIFIED, MISSING, FAILED }

@Serializable
data class MoveItemProgress(
    val chat: MoveSourceChat,
    val destinationProfile: String? = null,
    /** The exact Profile where the chat was confirmed, which may differ from Review's mapping. */
    val locatedProfile: String? = null,
    val copyState: MoveCopyState = MoveCopyState.PENDING,
    val rejectionReason: String? = null,
    val titleTarget: String? = null,
    val titleState: MoveFieldState = MoveFieldState.PENDING,
    val pinnedState: MoveFieldState = MoveFieldState.PENDING,
    val folderState: MoveFieldState = MoveFieldState.PENDING,
    val draftState: MoveDraftState = MoveDraftState.PENDING,
) {
    val isInHermes: Boolean
        get() = copyState == MoveCopyState.IMPORTED ||
            copyState == MoveCopyState.ALREADY_PRESENT ||
            copyState == MoveCopyState.PRESENT_UNCONFIRMED

    val hermesProfile: String?
        get() = locatedProfile ?: destinationProfile
}

@Serializable
enum class MoveStage { PAUSED, RUNNING, FINISHED }

/** Public target used by links, notifications, shortcuts, and the Sessions home. */
@Serializable
data class MovedSessionTarget(
    val destinationServerId: String,
    val sessionKey: String,
    val profile: String,
)

/** Public target for a legacy new-chat/profile entry point. */
@Serializable
data class MovedProfileTarget(
    val destinationServerId: String,
    val profile: String,
)

/** Persisted report port of `MoveToHermesReport` (`MoveToHermesRunner.swift`, #1216). */
@Serializable
data class MoveToHermesReport(
    val finishedEarly: Boolean = false,
    val copied: Int = 0,
    val alreadyThere: Int = 0,
    val presentUnconfirmed: Int = 0,
    val remaining: Int = 0,
    val unchecked: Int = 0,
    val oversized: Int = 0,
    val rejected: Int = 0,
    val keptOnWebui: Int = 0,
    val draftsMoved: Int = 0,
    val draftConflicts: Int = 0,
    val draftsKept: Int = 0,
    val changesApplied: Int = 0,
    val changesUnconfirmed: Int = 0,
) {
    val chatsInHermes: Int get() = copied + alreadyThere + presentUnconfirmed
}

@Serializable
data class MoveRecord(
    val sourceServerId: String,
    val destinationServerId: String,
    /** Guards against a removed destination later being re-added at the same URL. */
    val destinationCreatedAt: Double = 0.0,
    val profileMappings: List<MoveProfileMapping>,
    val hermesProfiles: List<String>,
    val items: List<MoveItemProgress>,
    val movedSessions: Map<String, MovedSessionTarget> = emptyMap(),
    val firstMove: Boolean = true,
    val stage: MoveStage = MoveStage.PAUSED,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
    val completedAtEpochMillis: Long? = null,
    val report: MoveToHermesReport? = null,
    val reportAcknowledged: Boolean = false,
)

@Serializable
enum class MovePromptState { ASKED, DISMISSED }

@Serializable
data class MovePersistentState(
    val version: Int = 1,
    val prompts: Map<String, MovePromptState> = emptyMap(),
    val moves: List<MoveRecord> = emptyList(),
)

/** Stateless progress/report folding so persisted journals remain the only source of truth. */
object MoveReportBuilder {
    fun build(record: MoveRecord, finishedEarly: Boolean = false): MoveToHermesReport {
        val items = record.items
        val copied = items.count { it.copyState == MoveCopyState.IMPORTED }
        val already = items.count { it.copyState == MoveCopyState.ALREADY_PRESENT }
        val presentUnconfirmed = items.count { it.copyState == MoveCopyState.PRESENT_UNCONFIRMED }
        val oversized = items.count { it.copyState == MoveCopyState.OVERSIZED }
        val rejected = items.count { it.copyState == MoveCopyState.REJECTED }
        val kept = items.count { it.copyState == MoveCopyState.KEPT }
        val unchecked = items.count {
            it.copyState == MoveCopyState.UNCHECKED || it.copyState == MoveCopyState.AMBIGUOUS ||
                it.copyState == MoveCopyState.DISPATCHING
        }
        val remaining = items.count {
            it.copyState == MoveCopyState.PENDING || it.copyState == MoveCopyState.UNREADABLE ||
                it.copyState == MoveCopyState.OVERSIZED || it.copyState == MoveCopyState.REJECTED ||
                it.copyState == MoveCopyState.KEPT
        }
        val fieldStates = items.flatMap { listOf(it.titleState, it.pinnedState, it.folderState) }
        return MoveToHermesReport(
            finishedEarly = finishedEarly,
            copied = copied,
            alreadyThere = already,
            presentUnconfirmed = presentUnconfirmed,
            remaining = remaining,
            unchecked = unchecked,
            oversized = oversized,
            rejected = rejected,
            keptOnWebui = kept,
            draftsMoved = items.count { it.draftState == MoveDraftState.MOVED || it.draftState == MoveDraftState.ALREADY_MOVED },
            draftConflicts = items.count { it.draftState == MoveDraftState.CONFLICT },
            draftsKept = items.count {
                it.draftState == MoveDraftState.CONFLICT || it.draftState == MoveDraftState.UNVERIFIED ||
                    it.draftState == MoveDraftState.MISSING || it.draftState == MoveDraftState.FAILED
            },
            changesApplied = fieldStates.count { it == MoveFieldState.DONE },
            changesUnconfirmed = fieldStates.count { it == MoveFieldState.UNCONFIRMED },
        )
    }
}

/** Host-compatible title cleaning from `MoveToHermesReconcile.fillTitle` (#1214). */
object MoveMetadata {
    private const val TITLE_LIMIT = 100

    fun title(raw: String?): String? {
        val source = raw ?: return null
        val cleaned = buildString(source.length) {
            var pendingSpace = false
            source.forEach { character ->
                val code = character.code
                val dropped = code in 0x00..0x08 || code == 0x0B || code == 0x0C ||
                    code in 0x0E..0x1F || code == 0x7F || code in 0x200B..0x200F ||
                    code in 0x2028..0x202E || code in 0x2060..0x2069 || code == 0xFEFF ||
                    code in 0xFFF9..0xFFFC
                if (!dropped) {
                    if (character.isWhitespace()) {
                        pendingSpace = isNotEmpty()
                    } else {
                        if (pendingSpace) append(' ')
                        pendingSpace = false
                        append(character)
                    }
                }
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned == "Untitled") return null
        return cleaned.codePoints().toArray().take(TITLE_LIMIT).toIntArray()
            .let { String(it, 0, it.size) }
    }

    fun suffixed(title: String, sessionId: String): String {
        val suffix = " (${sessionId.takeLast(6)})"
        val keep = (TITLE_LIMIT - suffix.codePointCount(0, suffix.length)).coerceAtLeast(0)
        val points = title.codePoints().toArray().take(keep).toIntArray()
        return String(points, 0, points.size).trimEnd() + suffix
    }
}
