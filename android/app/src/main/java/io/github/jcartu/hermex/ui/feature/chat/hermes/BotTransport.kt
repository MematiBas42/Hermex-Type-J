package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesCall

/** Port of `Networking/Bots/BotProtocol.swift` BotTransport: one screen's handle on the shared gateway. */
interface BotTransport {
    val replayEpoch: String?
    val serverVersion: String?
    val unavailableMethods: Set<String>
    var onEvent: ((BotJSON) -> Unit)?
    var onDisconnect: ((Throwable) -> Unit)?
    suspend fun connect()
    suspend fun close()
    suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)? = null): BotJSON
    suspend fun keepPlatformNote(profile: String)
    suspend fun currentProfile(): String
    suspend fun sessionMessages(key: String, profile: String, offset: Int? = null): List<BotJSON>?
    suspend fun sessionRow(key: String, profile: String): BotJSON?
    suspend fun sessionPage(profile: String, offset: Int, archived: Boolean): HermesSessionPage
    suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray
    suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String
    suspend fun speech(text: String, profile: String): ByteArray
}

/** Production adapter; the conversation hops the transport's HermesActor callbacks to Main. */
class BotClientTransport(val client: BotClient) : BotTransport {
    override val replayEpoch get() = client.replayEpoch
    override val serverVersion get() = client.serverVersion
    override val unavailableMethods get() = client.unavailableMethods
    override var onEvent: ((BotJSON) -> Unit)?
        get() = client.onEvent
        set(value) { client.onEvent = value }
    override var onDisconnect: ((Throwable) -> Unit)?
        get() = client.onDisconnect
        set(value) { client.onDisconnect = value }
    override suspend fun connect() = client.connect()
    override suspend fun close() = client.close()
    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?) = client.call(call, validateDispatch)
    override suspend fun keepPlatformNote(profile: String) = client.keepPlatformNote(profile)
    override suspend fun currentProfile() = client.currentProfile()
    override suspend fun sessionMessages(key: String, profile: String, offset: Int?) = client.sessionMessages(key, profile, offset)
    override suspend fun sessionRow(key: String, profile: String) = client.sessionRow(key, profile)
    override suspend fun sessionPage(profile: String, offset: Int, archived: Boolean) = client.sessionPage(profile, offset, archived)
    override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?) = client.artifactData(path, context, limit)
    override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext) = client.uploadImage(data, filename, context)
    override suspend fun speech(text: String, profile: String) = client.speech(text, profile)
}
