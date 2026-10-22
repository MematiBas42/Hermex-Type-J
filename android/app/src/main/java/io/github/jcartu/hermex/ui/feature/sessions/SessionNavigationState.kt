package io.github.jcartu.hermex.ui.feature.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.model.SessionSummary
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session-list navigation and unread bookkeeping. Ports of the value types in
 * `Features/SessionList/SessionNavigationState.swift` that the Android list
 * needs; shell-level selection restore lives in `AppNavigationViewModel`
 * (SavedStateHandle), which already outlives process recreation — stronger
 * than upstream's `SessionNavigationPersistence` UserDefaults store.
 */

/**
 * Resolves the hardware-keyboard chat shortcuts (Ctrl+1–Ctrl+9, Next and
 * Previous Chat) against the ordinary chat rows in on-screen order.
 *
 * Port of `ChatShortcutNavigation` from `SessionNavigationState.swift`.
 */
object ChatShortcutNavigation {
    /** The chat at a 1-based list position, or null when the list is shorter. */
    fun chatAtPosition(position: Int, chats: List<SessionSummary>): SessionSummary? =
        chats.getOrNull(position - 1)

    /**
     * The chat [offset] rows from the selection, wrapping at the ends. Without
     * a selection in the list, next starts at the first chat and previous at
     * the last.
     */
    fun adjacentChat(
        offset: Int,
        selectedSessionId: String?,
        chats: List<SessionSummary>,
    ): SessionSummary? {
        if (chats.isEmpty()) return null
        val selectedIndex = selectedSessionId?.let { id ->
            chats.indexOfFirst { it.sessionId == id }
        } ?: -1
        if (selectedIndex < 0) return if (offset > 0) chats.first() else chats.last()

        val count = chats.size
        return chats[Math.floorMod(selectedIndex + offset, count)]
    }
}

/**
 * Device-local last-seen server timestamps for session rows, per server.
 *
 * Port of `SessionUnreadStore` from `SessionNavigationState.swift` (1.7.0
 * "Session rows show unread replies"), backed by the feature's Preferences
 * DataStore instead of UserDefaults: one JSON object per server holding
 * `sessionId -> lastMessageAt`. Tolerant: a missing or corrupt entry reads as
 * empty (every row simply looks new until the next mark).
 */
class SessionUnreadStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json,
) {
    private fun key(serverUrl: String) = stringPreferencesKey("session-inbox-seen|$serverUrl")

    suspend fun load(serverUrl: String): Map<String, Double> {
        val raw = runCatching { dataStore.data.first()[key(serverUrl)] }.getOrNull()
            ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapNotNull { (id, value) ->
                value.jsonPrimitive.doubleOrNull?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    suspend fun save(seen: Map<String, Double>, serverUrl: String) {
        val encoded = kotlinx.serialization.json.buildJsonObject {
            seen.forEach { (id, timestamp) -> put(id, kotlinx.serialization.json.JsonPrimitive(timestamp)) }
        }.toString()
        runCatching { dataStore.edit { it[key(serverUrl)] = encoded } }
    }
}

/**
 * What the list's New Chat action carries into the chat pane.
 *
 * Port of the in-app subset of `PendingNewChatRoute`
 * (`Features/SessionList/SessionListView.swift`): the route snapshots the
 * project filter at tap time so the new chat joins the project the user sees
 * (#875, 1.8.0), and the profile name so the new chat uses the list's selected
 * profile (1.8.0). System entry points (deep links, shares) never inherit it —
 * they call the callback with the defaults.
 */
data class PendingNewChatRequest(
    /** When set, the new session is created in this project (#875). */
    val projectId: String? = null,
    /** When set, the new session is created pinned to this profile. */
    val profileName: String? = null,
)

/**
 * Refreshes the session list when the app returns from the background, so
 * sessions started or finished elsewhere show without a pull. A return that
 * lands while a load is running, or before the initial load has finished, is
 * remembered and refreshed once the list is idle: that load may have started
 * before backgrounding and would otherwise leave stale rows. Repeated returns
 * coalesce to one refresh.
 *
 * Port of `SessionListForegroundRefresh` from
 * `Features/SessionList/SessionListView.swift` (1.8.0 "Sessions refresh on
 * foregrounding").
 */
class SessionListForegroundRefresh {
    var isPending = false
        private set

    /** Called on a real background-to-active return. */
    fun appReturned(didCompleteInitialLoad: Boolean, isLoading: Boolean): Boolean {
        isPending = true
        return consumeIfReady(didCompleteInitialLoad, isLoading)
    }

    /**
     * Called when loading stops or the initial load completes. Returns true
     * once for a remembered return, as soon as the list is free to refresh.
     */
    fun consumeIfReady(didCompleteInitialLoad: Boolean, isLoading: Boolean): Boolean {
        if (!isPending || !didCompleteInitialLoad || isLoading) return false
        isPending = false
        return true
    }
}

/**
 * The list's idle minute ticker (1.8.0 "Session times update while the list
 * is idle"): the Compose `TimelineView(.everyMinute)` analogue. The pane runs
 * [run] inside `repeatOnLifecycle(RESUMED)`, so ticks happen only while the
 * list is resumed; the minute-aligned delay mirrors the wall-minute trigger
 * (`context.date` is the minute's start, so a mid-minute render reads fresh).
 */
object SessionListIdleTicker {
    const val MINUTE_MILLIS = 60_000L

    /** Wall-clock distance from [nowMillis] to the next minute boundary. */
    fun millisUntilNextMinute(nowMillis: Long): Long =
        MINUTE_MILLIS - (nowMillis % MINUTE_MILLIS)

    /**
     * Emits `nowEpochSeconds()` immediately, then once per wall minute until
     * the coroutine is cancelled (on pause, via the caller's
     * `repeatOnLifecycle`). `nowMillis` drives the minute alignment and is
     * injectable for tests.
     */
    suspend fun run(
        nowEpochSeconds: () -> Long,
        nowMillis: () -> Long = System::currentTimeMillis,
        emit: (Long) -> Unit,
    ) {
        while (true) {
            emit(nowEpochSeconds())
            kotlinx.coroutines.delay(millisUntilNextMinute(nowMillis()))
        }
    }
}

/**
 * Identity for the session list's active-row poll, port of
 * `ActiveSessionMonitorTaskID` (`Features/SessionList/SessionListView.swift`).
 * Compose restarts the poll whenever this changes, and the poll runs only
 * while [shouldPoll] holds. On compact width a pushed chat replaces the list
 * in composition, so the poll cancels until the user returns; the list's
 * recomposition then reloads the rows, matching `SessionListReturnRefresh`.
 */
data class ActiveSessionMonitorTaskId(
    val streamIds: List<String>,
    val hasActiveRows: Boolean,
    val isViewingCachedData: Boolean,
    /** The fork's row-states toggle gates the poll along with the labels. */
    val showsRowStates: Boolean,
) {
    val shouldPoll: Boolean
        get() = hasActiveRows && !isViewingCachedData && showsRowStates

    companion object {
        /**
         * `ActiveSessionMonitorTaskID.pollInterval` (3 s): the open chat watches
         * its own run over SSE, so the list only needs badges and the
         * Working-to-done switch reasonably fresh.
         */
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
