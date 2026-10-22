package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.HermesCall

/**
 * Port of `BotPromptMode` (`Features/Bots/BotPromptMode.swift`): how a prompt reaches a
 * working Hermes session. Send is the only choice while the session is idle; the other three
 * are offered when a send lands on a working one. Queue explicitly bypasses the host's
 * configurable busy input behavior, which could otherwise interrupt work started by Desktop.
 */
enum class BotPromptMode {
    SEND, STEER, QUEUE, REDIRECT;

    val title: String
        get() = when (this) {
            SEND -> "Send"
            STEER -> "Steer"
            QUEUE -> "Queue"
            REDIRECT -> "Interrupt"
        }

    /** Whether this mode starts a fresh turn: only there will the host expand a skill invocation. */
    val startsTurn: Boolean get() = this == SEND || this == QUEUE

    /** Send and Queue both submit a queued prompt; see `HermesCall.PromptSubmit`. */
    fun call(runtime: String, text: String): HermesCall = when (this) {
        SEND, QUEUE -> HermesCall.PromptSubmit(sessionID = runtime, text = text)
        STEER -> HermesCall.SessionSteer(sessionID = runtime, text = text)
        REDIRECT -> HermesCall.SessionRedirect(sessionID = runtime, text = text)
    }

    fun outcome(reply: BotJSON): BotPromptOutcome {
        val status = reply["status"].text
        return when {
            this == STEER && status == "queued" -> BotPromptOutcome.GUIDANCE_QUEUED
            this == REDIRECT && status == "redirected" -> BotPromptOutcome.REDIRECTED
            this == REDIRECT && status == "queued" -> BotPromptOutcome.REDIRECT_QUEUED
            startsTurn && status == "queued" -> BotPromptOutcome.FOLLOW_UP_QUEUED
            startsTurn && status == "streaming" -> BotPromptOutcome.STARTED
            (this == STEER || this == REDIRECT) && status == "rejected" -> BotPromptOutcome.REJECTED
            // The installed submit handler recognizes voice-stop phrases before starting a
            // turn. That acknowledgment must not claim a prompt ran.
            startsTurn && reply["voice_stopped"].flag == true -> BotPromptOutcome.VOICE_STOPPED
            else -> BotPromptOutcome.UNKNOWN
        }
    }

    fun definitelyRejected(error: Throwable): Boolean {
        val code = (error as? BotFailure.Rejected)?.code ?: return false
        if (code in setOf(401, 403, 4001, 4090, -32601, -32602)) return true
        return (this == STEER || this == REDIRECT) && code in setOf(4002, 4010)
    }
}

/**
 * Port of `BotPromptOutcome`: what the host said about a prompt. Only [REJECTED] and
 * [UNKNOWN] change what the app does; the rest confirm admission and clear the draft.
 */
enum class BotPromptOutcome {
    GUIDANCE_QUEUED, REDIRECTED, REDIRECT_QUEUED, FOLLOW_UP_QUEUED, STARTED, VOICE_STOPPED, REJECTED, UNKNOWN
}
