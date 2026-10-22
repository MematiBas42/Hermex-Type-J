package io.github.jcartu.hermex.ui.feature.sessions

import io.github.jcartu.hermex.model.SessionSummary

/**
 * Where a forked chat came from, shown as the session row's "Forked from"
 * subtitle (1.8.0 "forked chats link back to their parent session").
 *
 * Port of `Features/Chat/ForkOrigin.swift`. Only chats made by Fork From Here
 * or `/branch` count: upstream stamps those `session_source: "fork"` with a
 * `parent_session_id`. Agent child sessions (subagents, cron, CLI `/new`)
 * share `parent_session_id` but are not forks, so they get no subtitle.
 */
data class ForkOrigin(
    val parentSessionId: String,
    /** The parent as the session list holds it; null means no tap target. */
    val parent: SessionSummary?,
) {
    /** The subtitle: the parent's list title, or a fallback when the parent is not listed. */
    val title: String
        get() {
            val parentTitle = parent?.let { sessionDisplayTitle(it) } ?: return "Forked from another chat"
            return "Forked from $parentTitle"
        }

    companion object {
        /**
         * Port of `ForkOrigin.parentSessionID(of:)`: the parent to look up, or
         * null when `session` is not a fork. Matches the webui's own test:
         * `session_source`, trimmed and lowercased, is "fork".
         */
        fun parentSessionIdOf(session: SessionSummary): String? {
            val source = session.sessionSource?.trim()?.lowercase()
            if (source != "fork") return null
            return session.parentSessionId?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** Port of `ForkOrigin.resolve(session:parent:)`. */
        fun resolve(session: SessionSummary, parent: SessionSummary?): ForkOrigin? {
            val parentId = parentSessionIdOf(session) ?: return null
            return ForkOrigin(parentSessionId = parentId, parent = parent)
        }

        /** Resolves against the sessions the list currently holds. */
        fun resolveIn(session: SessionSummary, sessions: List<SessionSummary>): ForkOrigin? {
            val parentId = parentSessionIdOf(session) ?: return null
            val parent = sessions.firstOrNull { it.sessionId == parentId }
            return ForkOrigin(parentSessionId = parentId, parent = parent)
        }
    }
}
