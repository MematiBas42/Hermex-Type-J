package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.ChatSteerResponse

/**
 * Port of `ChatSteerOutcome` (`Models/ServerCatalog.swift` /
 * `APIClient+Chat.swift`): what became of a steering hint sent to the
 * session's active run. No outcome stops the run — the server's steer
 * contract leaves Queue and Stop & send to the user (1.8.0).
 */
sealed class ChatSteerOutcome {
    /** `accepted: true` — the hint reached the running turn. */
    object Delivered : ChatSteerOutcome()

    /**
     * The server refused the hint (no fallback it could name) or the request
     * never produced an answer ([transportError] non-null).
     */
    data class Refused(val transportError: ApiError?) : ChatSteerOutcome()

    /** `fallback: "gateway_steer_queued"` — the server queued it for the next turn. */
    object ServerQueued : ChatSteerOutcome()

    /** `fallback: "not_running"` / `"stream_dead"` — the run is already over. */
    object RunEnded : ChatSteerOutcome()

    companion object {
        /** Fallback codes from `_handle_chat_steer` (api/streaming.py). */
        fun classify(response: ChatSteerResponse): ChatSteerOutcome {
            if (response.accepted == true) return Delivered
            return when (response.fallback) {
                "gateway_steer_queued" -> ServerQueued
                "not_running", "stream_dead" -> RunEnded
                else -> Refused(transportError = null)
            }
        }
    }
}
