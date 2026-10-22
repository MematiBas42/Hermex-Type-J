package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.core.navigation.HermesNewChatDestination
import io.github.jcartu.hermex.core.navigation.HermesSessionDestination
import io.github.jcartu.hermex.core.navigation.WebuiPushDestination
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState
import io.github.jcartu.hermex.ui.feature.onboarding.account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Port of Features/Hermes/HermesSessionLookup.swift's registry/auth routing outcomes. */
sealed interface HermesSessionLinkOutcome {
    data object Ignore : HermesSessionLinkOutcome
    data class Webui(val serverId: String, val sessionId: String) : HermesSessionLinkOutcome
    data class WaitForSignIn(val destination: HermesSessionDestination) : HermesSessionLinkOutcome
    data class SwitchServer(val account: ServerAccount, val destination: HermesSessionDestination) : HermesSessionLinkOutcome
    data class Open(val destination: HermesSessionDestination) : HermesSessionLinkOutcome
}

/**
 * Port of HermesSessionLookup and HermesSessionListView's held destinations. Delivery never sends
 * a prompt. The home consumes only its own server's request, after an exact host lookup; a refused
 * sign-in holds it for the next signed-in home. Moved-webui routing belongs to the grammar/router.
 */
object HermesSessionLookup {
    private val _pending = MutableStateFlow<HermesSessionDestination?>(null)
    val pending: StateFlow<HermesSessionDestination?> = _pending.asStateFlow()
    private val _pendingNewChat = MutableStateFlow<HermesNewChatDestination?>(null)
    val pendingNewChat: StateFlow<HermesNewChatDestination?> = _pendingNewChat.asStateFlow()
    fun open(serverId: String, sessionKey: String, profile: String? = null, opensParent: Boolean = false, movedFromServerId: String? = null) =
        open(HermesSessionDestination(serverId, profile, sessionKey, opensParent, movedFromServerId))
    fun open(destination: HermesSessionDestination) { _pendingNewChat.value = null; _pending.value = destination }
    fun openNewChat(serverId: String, profile: String? = null, autoStartsVoiceInput: Boolean = false) {
        _pending.value = null
        _pendingNewChat.value = HermesNewChatDestination(serverId, profile, autoStartsVoiceInput)
    }
    fun consume(destination: HermesSessionDestination) { _pending.compareAndSet(destination, null) }
    fun consumeNewChat(destination: HermesNewChatDestination) { _pendingNewChat.compareAndSet(destination, null) }
    fun route(destination: HermesSessionDestination, state: AuthState, servers: List<ServerAccount>): HermesSessionLinkOutcome {
        val account = servers.firstOrNull { it.id == destination.serverId } ?: return HermesSessionLinkOutcome.Ignore
        if (account.kind == ServerKind.WEBUI) return HermesSessionLinkOutcome.Webui(account.id, destination.key)
        if (state.account?.id != account.id) return HermesSessionLinkOutcome.SwitchServer(account, destination)
        return if (state is AuthState.LoggedIn) HermesSessionLinkOutcome.Open(destination) else HermesSessionLinkOutcome.WaitForSignIn(destination)
    }
    /** Legacy serverless session?id= links never become Hermes sessions (#971). */
    fun serverless(sessionKey: String, servers: List<ServerAccount>): WebuiPushDestination? =
        servers.firstOrNull { it.kind == ServerKind.WEBUI }?.let { WebuiPushDestination(it.id, sessionKey) }
}
