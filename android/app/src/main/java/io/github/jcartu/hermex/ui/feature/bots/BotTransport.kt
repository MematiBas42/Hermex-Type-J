package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionSearchResult
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesREST

/**
 * Port of the `BotTransport` protocol (`Networking/Bots/BotProtocol.swift`): one Bot screen's
 * connection to the gateway. [BotClientTransport] over a real [BotClient] is the production
 * conformer; tests script their own. Callbacks arrive on `HermesActor.dispatcher`.
 */
interface BotTransport {
    val replayEpoch: String? get() = null
    val serverVersion: String? get() = null

    /** `install_id` from `/api/status` at the last connect; null when the host omits it. */
    val serverInstallID: String? get() = null

    /** Gateway methods the host answered -32601 on this connection; a new connection starts empty. */
    val unavailableMethods: Set<String> get() = emptySet()

    /** Every event and server request the socket delivers while this screen is attached: admit only your own. */
    var onEvent: ((BotJSON) -> Unit)?

    /** The socket was lost or its connection replaced; once each time, never for [close]. */
    var onDisconnect: ((Throwable) -> Unit)?

    suspend fun connect()

    /** Sends one typed request; [validateDispatch] runs immediately before the socket write. */
    suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)? = null): BotJSON

    suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String =
        throw io.github.jcartu.hermex.model.hermes.BotFailure.Unsupported

    /** One file the host serves by `path`; `limit` caps a preview's bytes, null reads it whole. */
    suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray =
        throw io.github.jcartu.hermex.model.hermes.BotArtifactFailure.Unavailable

    /** Never throws: a chat opens without the note. */
    suspend fun keepPlatformNote(profile: String) = Unit

    /** Removes a Profile on the host; only a 200 with `ok` counts. */
    suspend fun deleteProfile(name: String): Unit = throw io.github.jcartu.hermex.model.hermes.BotFailure.Unsupported

    suspend fun currentProfile(): String = throw io.github.jcartu.hermex.model.hermes.BotFailure.Unsupported

    /** A stored session's rows under `profile`; null when the host has no such session (404). */
    suspend fun sessionMessages(key: String, profile: String, offset: Int? = null): List<BotJSON>? =
        throw io.github.jcartu.hermex.model.hermes.BotFailure.Unsupported

    /** `profile`'s sessions matching `query`, in the host's order, at most `limit` (#1053). */
    suspend fun searchSessions(query: String, profile: String, limit: Int = HermesREST.SESSION_SEARCH_LIMIT): List<HermesSessionSearchResult> =
        throw io.github.jcartu.hermex.model.hermes.BotFailure.Unsupported

    /** The runtimes this phone's screens attached on the connection and have not closed. */
    val attachedRuntimes: Set<String> get() = emptySet()

    /** Ends this screen's calls, uploads and downloads; the shared socket stays for others. */
    suspend fun close()
}

/** The production [BotTransport]: one screen's [BotClient] on the connection's shared socket. */
class BotClientTransport(val client: BotClient) : BotTransport {
    override val replayEpoch: String? get() = client.replayEpoch
    override val serverVersion: String? get() = client.serverVersion
    override val serverInstallID: String? get() = client.serverInstallID
    override val unavailableMethods: Set<String> get() = client.unavailableMethods
    override val attachedRuntimes: Set<String> get() = client.attachedRuntimes

    override var onEvent: ((BotJSON) -> Unit)?
        get() = client.onEvent
        set(value) { client.onEvent = value }

    override var onDisconnect: ((Throwable) -> Unit)?
        get() = client.onDisconnect
        set(value) { client.onDisconnect = value }

    override suspend fun connect() = client.connect()

    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON = client.call(call, validateDispatch)

    override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String =
        client.uploadImage(data, filename, context)

    override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray =
        client.artifactData(path, context, limit)

    override suspend fun keepPlatformNote(profile: String) {
        runCatching { client.keepPlatformNote(profile) }
    }

    override suspend fun deleteProfile(name: String) = client.deleteProfile(name)

    override suspend fun currentProfile(): String = client.currentProfile()

    override suspend fun sessionMessages(key: String, profile: String, offset: Int?): List<BotJSON>? =
        client.sessionMessages(key, profile, offset)

    override suspend fun searchSessions(query: String, profile: String, limit: Int): List<HermesSessionSearchResult> =
        client.searchSessions(query, profile, limit)

    override suspend fun close() = client.close()
}
