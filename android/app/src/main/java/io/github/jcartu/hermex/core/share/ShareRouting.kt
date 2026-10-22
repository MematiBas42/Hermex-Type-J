package io.github.jcartu.hermex.core.share

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind

/**
 * Share-target routing by the active server's kind (#1181): a share
 * (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` / `ACTION_PROCESS_TEXT`, the
 * durable [SharedImportRepository] inbox, or the `share` deep link) opens a
 * new chat on the active server — a Hermes new chat when the active server is
 * Hermes, the webui New Chat composer otherwise.
 *
 * Port of the share half of `ContentView.handleOpenURL` /
 * `SharedImportGate` (`HermesMobile/ContentView.swift`,
 * `Features/Share/SharedDraftStore.swift`).
 */
object ShareRouting {

    /** Where one shared import goes on the active (signed-in) server. */
    sealed interface Route {
        /** The webui New Chat composer; `ChatViewModel` takes the pending import. */
        data object WebuiNewChat : Route

        /**
         * A new chat on the active Hermes server: `HermesSessionLookup.openNewChat`
         * opens it and the share moves into that new-session draft
         * (`ChatDraftStore.appendShare`) before the durable inbox clears.
         */
        data class HermesNewChat(val serverId: String) : Route
    }

    /** "New chats (#1180) and shares (#1181) stay on it" (hermes-server.md). */
    fun route(active: ServerAccount): Route =
        if (active.kind == ServerKind.HERMES) Route.HermesNewChat(active.id) else Route.WebuiNewChat
}

/**
 * Port of `SharedImportGate` (`ContentView.swift`, #1181): whether the next
 * waiting share opens now. A webui session list opens the first and offers
 * each later one in its Open Next banner, so a draft in progress is never
 * replaced; a Hermes home has no banner and opens each as it arrives.
 */
object SharedImportGate {
    fun opensNext(afterRouting: Boolean, activeServerId: String?, servers: List<ServerAccount>): Boolean =
        !afterRouting || servers.firstOrNull { it.id == activeServerId }?.kind == ServerKind.HERMES
}
