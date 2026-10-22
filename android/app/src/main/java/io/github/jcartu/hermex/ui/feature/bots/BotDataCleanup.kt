package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Port of `BotConnectionStore.remove` / `BotInbox.forget` cleanup (bots.md § Local search):
 * remove only this server and connection's phone-local bot state. Call after committing a
 * sign-out, removal or identity replacement, never before the saved credential write succeeds.
 * Cache writer revocation prevents late room reads restoring the removed connection's history.
 */
suspend fun AppContainer.discardBotData(server: String, connectionID: String?) {
    if (connectionID != null) {
        botAvatars.removeAll(connectionID)
        botUnreadStore.remove(connectionID)
        botSectionOrderStore.remove(server, connectionID)
        botRoomOrganizeStore.remove(connectionID)
        botHistoryCache.removeConnection(server, connectionID)
        withContext(Dispatchers.IO) { hermesCacheStore.removeHermesBotChats(server, connectionID) }
    } else {
        botHistoryCache.removeServer(server)
    }
    cacheStore.chatDraftStore.discardHermesDrafts(server, connectionID)
}

/** The confirmed Profile-delete path; every other bot and connection keeps its drafts/cache. */
suspend fun AppContainer.discardBotProfileData(server: String, connectionID: String, profile: String) {
    withContext(Dispatchers.IO) { hermesCacheStore.removeHermesBotChats(server, connectionID, profile) }
    cacheStore.chatDraftStore.discardHermesDrafts(server, connectionID, profile)
}
