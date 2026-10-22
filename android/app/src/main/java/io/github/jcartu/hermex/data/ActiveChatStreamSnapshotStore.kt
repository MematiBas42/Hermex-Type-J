package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.feature.chat.ReasoningGroup
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Port of `ActiveChatStreamSnapshot` (`ChatViewModel.swift`): everything a
 * chat needs to show a run in progress again — the transcript window with the
 * streaming assistant row, the live reasoning and tool cards, the settled
 * groups, the anchors that place them, and the SSE resume cursor. iOS keeps
 * these in memory because its view model is rebuilt per session; the port
 * also writes them to disk, so a partial stream survives the process being
 * killed in the background and resumes through journal replay instead of a
 * cursorless re-attach that duplicates text (#599).
 */
data class ActiveChatStreamSnapshot(
    val messages: List<ChatMessage>,
    val messagesOffset: Int,
    val displayTitle: String,
    val completedToolCallGroups: List<ToolCallGroup>,
    val completedReasoningGroups: List<ReasoningGroup>,
    val liveToolCalls: List<ToolCall>,
    val liveReasoningText: String,
    val activeStreamLastEventId: String?,
    val streamingAssistantMessageId: String?,
    val toolCallAnchorMessageId: String?,
    val reasoningAnchorMessageId: String?,
    val contextWindowSnapshot: ContextWindowSnapshot?,
    val pinnedLocalNotices: List<String>,
)

/** `ActiveChatStreamSnapshotKey`: one snapshot per server, session and run. */
data class ActiveChatStreamSnapshotKey(
    val server: String,
    val sessionId: String,
    val streamId: String,
)

@Serializable
internal data class PersistedLiveToolCall(
    val id: String,
    val presentationID: String = id,
    val name: String? = null,
    val preview: String? = null,
    val args: Map<String, JSONValue>? = null,
    val duration: Double? = null,
    val isError: Boolean? = null,
    val isCompleted: Boolean = false,
    val startedAt: Double = 0.0,
) {
    fun toToolCall(): ToolCall = ToolCall(
        id = id,
        presentationID = presentationID,
        name = name,
        preview = preview,
        args = args,
        duration = duration,
        isError = isError,
        isCompleted = isCompleted,
        startedAt = startedAt,
    )

    companion object {
        fun from(toolCall: ToolCall): PersistedLiveToolCall = PersistedLiveToolCall(
            id = toolCall.id,
            presentationID = toolCall.presentationID,
            name = toolCall.name,
            preview = toolCall.preview,
            args = toolCall.args,
            duration = toolCall.duration,
            isError = toolCall.isError,
            isCompleted = toolCall.isCompleted,
            startedAt = toolCall.startedAt,
        )
    }
}

@Serializable
internal data class PersistedToolCallGroup(
    val id: String,
    val anchorMessageID: String? = null,
    val toolCalls: List<PersistedLiveToolCall> = emptyList(),
) {
    fun toToolCallGroup(): ToolCallGroup =
        ToolCallGroup(id = id, anchorMessageID = anchorMessageID, toolCalls = toolCalls.map { it.toToolCall() })

    companion object {
        fun from(group: ToolCallGroup): PersistedToolCallGroup = PersistedToolCallGroup(
            id = group.id,
            anchorMessageID = group.anchorMessageID,
            toolCalls = group.toolCalls.map(PersistedLiveToolCall::from),
        )
    }
}

@Serializable
internal data class PersistedReasoningGroup(
    val id: String,
    val anchorMessageID: String? = null,
    val text: String = "",
) {
    fun toReasoningGroup(): ReasoningGroup = ReasoningGroup(id = id, anchorMessageID = anchorMessageID, text = text)

    companion object {
        fun from(group: ReasoningGroup): PersistedReasoningGroup =
            PersistedReasoningGroup(id = group.id, anchorMessageID = group.anchorMessageID, text = group.text)
    }
}

/** One on-disk row: the key plus the snapshot. Every field past the key has a default, so an older row still loads. */
@Serializable
internal data class PersistedActiveChatStreamSnapshot(
    val server: String,
    val sessionId: String,
    val streamId: String,
    val messages: List<ChatMessage> = emptyList(),
    val messagesOffset: Int = 0,
    val displayTitle: String = "",
    val completedToolCallGroups: List<PersistedToolCallGroup> = emptyList(),
    val completedReasoningGroups: List<PersistedReasoningGroup> = emptyList(),
    val liveToolCalls: List<PersistedLiveToolCall> = emptyList(),
    val liveReasoningText: String = "",
    val activeStreamLastEventId: String? = null,
    val streamingAssistantMessageId: String? = null,
    val toolCallAnchorMessageId: String? = null,
    val reasoningAnchorMessageId: String? = null,
    val contextWindowSnapshot: ContextWindowSnapshot? = null,
    val pinnedLocalNotices: List<String> = emptyList(),
) {
    val key: ActiveChatStreamSnapshotKey get() = ActiveChatStreamSnapshotKey(server, sessionId, streamId)

    fun toSnapshot(): ActiveChatStreamSnapshot = ActiveChatStreamSnapshot(
        messages = messages,
        messagesOffset = messagesOffset,
        displayTitle = displayTitle,
        completedToolCallGroups = completedToolCallGroups.map { it.toToolCallGroup() },
        completedReasoningGroups = completedReasoningGroups.map { it.toReasoningGroup() },
        liveToolCalls = liveToolCalls.map { it.toToolCall() },
        liveReasoningText = liveReasoningText,
        activeStreamLastEventId = activeStreamLastEventId,
        streamingAssistantMessageId = streamingAssistantMessageId,
        toolCallAnchorMessageId = toolCallAnchorMessageId,
        reasoningAnchorMessageId = reasoningAnchorMessageId,
        contextWindowSnapshot = contextWindowSnapshot,
        pinnedLocalNotices = pinnedLocalNotices,
    )

    companion object {
        fun from(key: ActiveChatStreamSnapshotKey, snapshot: ActiveChatStreamSnapshot) =
            PersistedActiveChatStreamSnapshot(
                server = key.server,
                sessionId = key.sessionId,
                streamId = key.streamId,
                messages = snapshot.messages,
                messagesOffset = snapshot.messagesOffset,
                displayTitle = snapshot.displayTitle,
                completedToolCallGroups = snapshot.completedToolCallGroups.map(PersistedToolCallGroup::from),
                completedReasoningGroups = snapshot.completedReasoningGroups.map(PersistedReasoningGroup::from),
                liveToolCalls = snapshot.liveToolCalls.map(PersistedLiveToolCall::from),
                liveReasoningText = snapshot.liveReasoningText,
                activeStreamLastEventId = snapshot.activeStreamLastEventId,
                streamingAssistantMessageId = snapshot.streamingAssistantMessageId,
                toolCallAnchorMessageId = snapshot.toolCallAnchorMessageId,
                reasoningAnchorMessageId = snapshot.reasoningAnchorMessageId,
                contextWindowSnapshot = snapshot.contextWindowSnapshot,
                pinnedLocalNotices = snapshot.pinnedLocalNotices,
            )
    }
}

/**
 * Port of `ActiveChatStreamSnapshotStore` (`ChatViewModel.swift`), made
 * durable: reads and writes are instant against an in-memory map (the chat
 * calls them synchronously at lifecycle boundaries, as iOS does), and every
 * change is written to `active-stream-snapshots.json` on [scope] so the
 * snapshot is still there after the process dies. Rows decode one at a time
 * and tolerantly: a malformed row never discards the others, and a missing or
 * unreadable file is an empty store. Only authoritative run completion or a
 * replacement stream removes a snapshot; wall-clock age cannot prove a run ended.
 */
class ActiveChatStreamSnapshotStore(
    directory: File,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val file = File(directory, "active-stream-snapshots.json")
    private val lock = Any()
    private var snapshots: LinkedHashMap<ActiveChatStreamSnapshotKey, PersistedActiveChatStreamSnapshot>? = null
    private var writeSequence = 0L
    private var writeJob: Job? = null

    /** True once the file has been read (or found absent); [loadIfNeeded] on an IO thread does it ahead of time. */
    val isLoaded: Boolean get() = synchronized(lock) { snapshots != null }

    /** Reads the file once. Blocking; call it from an IO dispatcher before the chat's first synchronous read. */
    fun loadIfNeeded() {
        synchronized(lock) { loaded() }
    }

    fun save(snapshot: ActiveChatStreamSnapshot, server: String, sessionId: String, streamId: String) {
        val key = ActiveChatStreamSnapshotKey(server, sessionId, streamId)
        synchronized(lock) {
            val store = loaded()
            // One run per chat: a newer stream for the same session replaces the stale one.
            store.values.removeAll { it.server == server && it.sessionId == sessionId && it.streamId != streamId }
            store[key] = PersistedActiveChatStreamSnapshot.from(key, snapshot)
            schedulePersist()
        }
    }

    fun snapshot(server: String, sessionId: String, streamId: String): ActiveChatStreamSnapshot? = synchronized(lock) {
        loaded()[ActiveChatStreamSnapshotKey(server, sessionId, streamId)]?.toSnapshot()
    }

    fun remove(server: String, sessionId: String, streamId: String) {
        synchronized(lock) {
            if (loaded().remove(ActiveChatStreamSnapshotKey(server, sessionId, streamId)) != null) schedulePersist()
        }
    }

    fun removeAll() {
        synchronized(lock) {
            if (loaded().isEmpty()) return
            loaded().clear()
            schedulePersist()
        }
    }

    /** Waits for the latest scheduled write. */
    suspend fun flush() {
        val job = synchronized(lock) { writeJob }
        job?.join()
    }

    private fun loaded(): LinkedHashMap<ActiveChatStreamSnapshotKey, PersistedActiveChatStreamSnapshot> =
        snapshots ?: readFile().also { snapshots = it }

    private fun readFile(): LinkedHashMap<ActiveChatStreamSnapshotKey, PersistedActiveChatStreamSnapshot> {
        val result = LinkedHashMap<ActiveChatStreamSnapshotKey, PersistedActiveChatStreamSnapshot>()
        if (!file.isFile) return result
        val rows = runCatching { json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)) as? JsonArray }
            .getOrNull() ?: return result
        for (row in rows) {
            val decoded = runCatching {
                json.decodeFromJsonElement(PersistedActiveChatStreamSnapshot.serializer(), row as? JsonObject ?: return@runCatching null)
            }.getOrNull() ?: continue
            result[decoded.key] = decoded
        }
        return result
    }

    /** Serialises the rows under the lock; the file write itself runs on [scope]. A newer write always wins. */
    private fun schedulePersist() {
        val rows = loaded().values.toList()
        val sequence = ++writeSequence
        writeJob = scope.launch {
            val encoded = json.encodeToString(ListSerializer(PersistedActiveChatStreamSnapshot.serializer()), rows)
            synchronized(lock) {
                if (sequence != writeSequence) return@launch
                runCatching { writeAtomically(encoded) }
            }
        }
    }

    private fun writeAtomically(content: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(content, StandardCharsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

}
