package io.github.jcartu.hermex.net

/**
 * Port of `AgentRuntimeStale` (`Networking/APIError.swift`): hermes-webui's
 * `agent_runtime_stale` 409 (`api/agent_runtime.py`,
 * `agent_runtime_stale_payload`). Hermes Agent was updated under the running
 * WebUI, which refuses every local Agent action until someone restarts it on
 * the server. The app cannot restart it, so the copy says what to do there
 * (1.9.0: an explanation and a fix prompt instead of a raw HTTP 409).
 */
enum class AgentRuntimeStale {
    /** The update finished, or the server can't tell (`stale`, `unverified`, `unknown`, absent, or a later state). */
    UPDATED,

    /** `agent_update_state: "active"`: the update is still running. */
    UPDATING,

    /** `agent_update_state: "incomplete"`: the update stopped partway. */
    INCOMPLETE;

    val message: String
        get() = when (this) {
            UPDATED -> "Hermes was updated on your server. Restart Hermes WebUI there, then try again."
            UPDATING -> "Hermes is still updating on your server. Wait for it to finish, restart Hermes WebUI, then try again."
            INCOMPLETE -> "A Hermes update on your server didn't finish. Check it, restart Hermes WebUI, then try again."
        }

    /**
     * What the chat composer's "Copy fix prompt" puts on the clipboard, for the
     * user to send their agent over a messaging gateway (which `hermes update`
     * restarts, so it still works). Null while the update is running: the fix
     * then is to wait, not restart.
     */
    val fixPrompt: String?
        get() {
            if (this == UPDATING) return null
            return """
                |My Hermes WebUI is refusing chats with "Hermes Agent was updated while Hermes WebUI was running." Please do only this:
                |1. Check that no Hermes update is still running and the last one finished without errors. If it didn't, stop and tell me.
                |2. Restart the Hermes WebUI server the same way it was started (systemd, launchd, ctl.sh, Docker, or whatever runs it). Don't update, reinstall, or reconfigure anything.
                |3. Confirm it's back: its `/health` endpoint should return 200.
                |
                |If it doesn't come back up, show me the last lines of its error log and suggest a fix, but don't change config, environment variables, or Hermes source until I say so.
            """.trimMargin()
        }

    companion object {
        fun fromAgentUpdateState(agentUpdateState: String?): AgentRuntimeStale = when (agentUpdateState) {
            "active" -> UPDATING
            "incomplete" -> INCOMPLETE
            else -> UPDATED
        }
    }
}
