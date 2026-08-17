package io.github.jcartu.hermex.ui.feature.sessions

import io.github.jcartu.hermex.model.SessionMutationResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi

/** Port of `SessionDuplicateResult` from `SessionMutator.swift`. */
data class SessionDuplicateResult(
    val session: SessionSummary?,
    val errorMessage: String?,
)

/**
 * Port of `SessionMoveWhileStreamingError` from `SessionMutator.swift`.
 *
 * The server refuses `/api/session/move` with a 503 while the session is
 * streaming (it holds the per-session agent lock). Surface that as a specific,
 * actionable message instead of the generic "server unavailable" copy (issue #25).
 */
class SessionMoveWhileStreamingException : Exception(
    "This session is still responding, so it can't be moved yet. Try again when it finishes.",
)

/** Port of `SessionMutator` from `SessionMutator.swift`. */
class SessionMutator(private val api: HermesApi) {

    suspend fun setPinned(pinned: Boolean, sessionId: String) {
        api.pinSession(id = sessionId, pinned = pinned)
    }

    suspend fun archive(sessionId: String) {
        api.archiveSession(id = sessionId, archived = true)
    }

    suspend fun delete(sessionId: String) {
        api.deleteSession(id = sessionId)
    }

    suspend fun rename(sessionId: String, title: String): SessionMutationResponse =
        api.renameSession(id = sessionId, title = title)

    suspend fun move(sessionId: String, projectId: String?) {
        try {
            api.moveSession(id = sessionId, projectId = projectId)
        } catch (error: ApiError) {
            // Only a 503 carrying the server's JSON error payload is the documented
            // "session is busy (streaming)" refusal; a proxy/tunnel 503 has no JSON
            // body and keeps the generic connectivity message.
            if (error is ApiError.Http && error.statusCode == 503 && error.serverMessage != null) {
                throw SessionMoveWhileStreamingException()
            }
            throw error
        }
    }

    /**
     * Port of `duplicate(sessionID:title:)`: branches the session with the full
     * history kept (`keep_count` omitted — the list-level duplicate copies every
     * message) and fetches the new session's summary.
     */
    suspend fun duplicate(sessionId: String, title: String): SessionDuplicateResult {
        val response = api.branchSession(id = sessionId, keepCount = null, title = title)

        val duplicatedSessionId = response.sessionId
            ?: return SessionDuplicateResult(
                session = null,
                errorMessage = response.error
                    ?: "The server did not return the duplicated session ID.",
            )

        val duplicatedResponse = api.session(
            id = duplicatedSessionId,
            includeMessages = false,
            messageLimit = null,
        )

        val duplicatedSessionDetail = duplicatedResponse.session
            ?: return SessionDuplicateResult(
                session = null,
                errorMessage = "The server did not return the duplicated session.",
            )

        return SessionDuplicateResult(
            session = SessionSummary(duplicatedSessionDetail),
            errorMessage = null,
        )
    }
}
