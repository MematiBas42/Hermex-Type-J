package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotConnectionAdvice
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionFenceRefusal
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.model.hermes.HermesSessionRefusal
import io.github.jcartu.hermex.model.hermes.HermesSessionSearch
import io.github.jcartu.hermex.model.hermes.HermesSessionSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Port of `BotClient` (`Networking/Bots/BotClient.swift`): one Bot screen's use of its
 * connection's shared gateway socket ([HermesGateway]). The inbox, each chat, a room, the
 * creator and the editor hold their own. [connect] attaches to the socket, opening it when no
 * other screen has; [close] ends only this screen's calls, uploads and downloads, never the
 * socket another screen is using.
 */
class BotClient(val http: HermesConnection) {
    private val gateway: HermesGateway = http.gateway
    val consumerID: Int = gateway.makeConsumerID()

    /** Bumped by [close], [connect] and a lost socket, so this screen's late HTTP results are dropped. */
    @Volatile
    private var attempt = 0
    private val localWork = CopyOnWriteArraySet<Job>()

    val replayEpoch: String? get() = gateway.replayEpoch

    /** `version` from the connection's last `/api/status` read; null when omitted. */
    val serverVersion: String? get() = http.serverVersion

    /** `install_id` from the same read; null when omitted. */
    val serverInstallID: String? get() = http.serverInstallID
    val unavailableMethods: Set<String> get() = http.unavailableMethods
    val attachedRuntimes: Set<String> get() = http.attachedRuntimes

    /** Every event and server request the socket delivers while this screen is attached (on the actor). */
    @Volatile
    var onEvent: ((BotJSON) -> Unit)? = null

    /** This screen's connection is gone: its socket was lost or retired, or a required call expired (on the actor). */
    @Volatile
    var onDisconnect: ((Throwable) -> Unit)? = null

    /**
     * Attaches to the gateway socket once its handshake is done, so no RPC can precede it. A
     * socket another screen has open is joined as it is; otherwise this signs in unless the
     * connection already is and opens one, or waits for the one already opening.
     */
    suspend fun connect() = withContext(HermesActor.dispatcher) {
        closeNow()
        val attempt = this@BotClient.attempt
        try {
            gateway.join(this@BotClient)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw if (attempt == this@BotClient.attempt) error else BotFailure.Stale
        }
        if (attempt != this@BotClient.attempt) throw BotFailure.Stale
        try {
            ensureActive()
        } catch (error: CancellationException) {
            closeNow()
            throw BotFailure.Stale
        }
    }

    /**
     * [connect] on the reconnect schedule (hermes-server.md § Reconnect contract): after a
     * failure the user can't act on ([BotConnectionAdvice.isRetryable]) it waits 1, 2, 4, 8,
     * 16 and then at most 30 seconds and tries again, while [isActive] holds, up to
     * [maxAttempts] tries. A refusal the user has to act on is thrown at once. [sleep] is the
     * clock; tests pass a fake.
     */
    suspend fun connectWithBackoff(
        maxAttempts: Int = Int.MAX_VALUE,
        isActive: () -> Boolean = { true },
        sleep: suspend (Long) -> Unit = { delay(it) },
    ) {
        var attemptIndex = 0
        while (true) {
            try {
                connect()
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!BotConnectionAdvice.isRetryable(error) || attemptIndex + 1 >= maxAttempts || !isActive()) throw error
                sleep(HermesReconnectBackoff.delayMillis(attemptIndex))
                attemptIndex += 1
                if (!isActive()) throw error
            }
        }
    }

    /** Admits and sends one typed request; [validateDispatch] runs at the actual socket write. */
    suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)? = null): BotJSON =
        gateway.send(call, consumerID, validateDispatch)

    suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray {
        if (context.connectionID != http.connection.id || !gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val request = HermesREST.DownloadArtifact(path, context.profile, context.sessionID).request(http.connection.address)
        return tracked {
            val data = http.authorized(request, validateDispatch = { checkOwner(attempt) }) { prepared, client ->
                BotArtifactDownload.data(client, prepared, limit)
            }
            checkOwner(attempt)
            data
        }
    }

    suspend fun keepPlatformNote(profile: String) = http.keepPlatformNote(profile)

    suspend fun deleteProfile(name: String) {
        if (!gateway.isAttached(consumerID) || !BotProfileName.isValid(name)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(HermesREST.DeleteProfile(name), validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        if (BotJSON.parseOrNull(data)?.get("ok")?.flag != true) throw BotFailure.Unsupported
    }

    suspend fun currentProfile(): String {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(HermesREST.ProfilesActive, validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        // Usually `default`, which `BotProfileName` reserves for creation, so only emptiness is refused.
        return BotJSON.parseOrNull(data)?.get("current")?.text?.takeIf { it.isNotEmpty() } ?: throw BotFailure.Unsupported
    }

    /** A stored session's rows, or null for one the Profile does not have (404). */
    suspend fun sessionMessages(key: String, profile: String, offset: Int?): List<BotJSON>? {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = try {
            http.data(HermesREST.SessionMessages(key, profile, offset), validateDispatch = { checkOwner(attempt) })
        } catch (error: BotFailure.Rejected) {
            if (error.code != 404) throw error
            checkOwner(attempt)
            return null
        }
        checkOwner(attempt)
        return BotJSON.parseOrNull(data)?.get("messages")?.list ?: throw BotFailure.Unsupported
    }

    /**
     * Listen's speech (#1072), on the provisioning deadline: the host may install its TTS
     * engine on first use. Plain HTTP, so it needs no attached socket.
     */
    suspend fun speech(text: String, profile: String): ByteArray {
        val attempt = this.attempt
        val request = HermesREST.Speak(text, profile).request(http.connection.address)
        return http.authorized(request, HermesConnection.Deadline.PROVISIONING, validateDispatch = { checkOwner(attempt) }) { prepared, client ->
            speechAudio(BotArtifactDownload.data(client, prepared))
        }
    }

    suspend fun sessionPage(profile: String, offset: Int, archived: Boolean): HermesSessionPage {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(HermesREST.SessionList(profile, offset, archived), validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        return decodeOrUnsupported(HermesSessionPage.serializer(), data)
    }

    suspend fun scheduledSessionPage(profile: String, offset: Int, archived: Boolean): HermesSessionPage {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(
            HermesREST.SessionList(profile, offset, archived, scheduled = true),
            validateDispatch = { checkOwner(attempt) },
        )
        checkOwner(attempt)
        return decodeOrUnsupported(HermesSessionPage.serializer(), data)
    }

    suspend fun searchScheduledSessions(query: String, profile: String, limit: Int): List<HermesSessionSearchResult> {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(
            HermesREST.SessionSearch(query, profile, limit, scheduled = true),
            validateDispatch = { checkOwner(attempt) },
        )
        checkOwner(attempt)
        return decodeOrUnsupported(HermesSessionSearch.serializer(), data).results
    }

    suspend fun sessionScanPage(profile: String, offset: Int): HermesSessionPage {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(HermesREST.SessionScan(profile, offset), validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        return decodeOrUnsupported(HermesSessionPage.serializer(), data)
    }

    /**
     * Writes [change] and returns the title the host kept. Behind the write fence (#1300) it
     * reads the session's revision first. A 400's reason is a [HermesSessionRefusal], a fence
     * 409 a [io.github.jcartu.hermex.model.hermes.HermesSessionFenceRefused]; neither is resent.
     */
    suspend fun updateSession(change: HermesSessionChange, key: String, profile: String): String? {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val identity = writeIdentity(key, profile, attempt)
        val reply = http.reply(HermesREST.UpdateSession(key, profile, change, identity), validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        val body = reply.json
        if (reply.status != 200) {
            val detail = body?.get("detail")?.text?.trim()
            if (reply.status == 400 && !detail.isNullOrEmpty()) throw HermesSessionRefusal(detail)
            if (reply.status == 409) HermesSessionFenceRefusal.fromDetail(body?.get("detail")?.text)?.let { throw it.asException() }
            throw BotFailure.Rejected(reply.status)
        }
        return body?.get("title")?.text
    }

    /**
     * The identity a write to [key] carries, from the session's mutation snapshot. Null on a
     * host from before the fence, whose catch-all answers the route 404.
     */
    private suspend fun writeIdentity(key: String, profile: String, attempt: Int): HermesSessionWriteIdentity? {
        val reply = http.reply(HermesREST.SessionMutationSnapshot(key, profile), validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        val body = reply.json
        return when {
            reply.status == 200 -> {
                if (body?.get("exists")?.flag == false) throw BotFailure.Rejected(404)
                val revision = body?.get("runtime_revision")?.integer?.takeIf { it >= 0 } ?: throw BotFailure.Unsupported
                HermesSessionWriteIdentity(expectedRevision = revision)
            }
            reply.status == 404 && (
                body?.get("detail")?.text?.startsWith("No such API endpoint") == true ||
                    body?.get("error")?.text?.startsWith("Headless backend") == true
                ) -> null
            else -> throw BotFailure.Rejected(reply.status)
        }
    }

    suspend fun exportSession(key: String, profile: String): ByteArray {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(
            HermesREST.SessionExport(key, profile), HermesConnection.Deadline.PROVISIONING,
            validateDispatch = { checkOwner(attempt) },
        )
        checkOwner(attempt)
        return data
    }

    /** That exact session's stored row, or null when the Profile has none (404). */
    suspend fun sessionRow(key: String, profile: String): BotJSON? {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = try {
            http.data(HermesREST.SessionRow(key, profile), validateDispatch = { checkOwner(attempt) })
        } catch (error: BotFailure.Rejected) {
            if (error.code != 404) throw error
            checkOwner(attempt)
            return null
        }
        checkOwner(attempt)
        return BotJSON.parseOrNull(data)?.takeIf { it.isObject } ?: throw BotFailure.Unsupported
    }

    /**
     * Imports sessions; a refused payload's reason is the host's `detail`: its text, or the
     * first of its `errors`, each kept in the refusal's entries. A write fence 409 is a fence refusal.
     */
    suspend fun importSessions(body: ByteArray): BotJSON {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val reply = http.reply(
            HermesREST.ImportSessions(body, HermesSessionWriteIdentity(expectedRevision = 0)),
            HermesConnection.Deadline.PROVISIONING,
            validateDispatch = { checkOwner(attempt) },
        )
        checkOwner(attempt)
        val json = reply.json
        if (reply.status != 200) {
            val entries = (json?.get("detail")?.get("errors")?.list ?: emptyList()).mapNotNull { error ->
                val index = error["index"].integer ?: return@mapNotNull null
                val reason = error["error"].text ?: return@mapNotNull null
                HermesSessionRefusal.Entry(index, error["session_id"].text, reason)
            }
            val detail = (json?.get("detail")?.text ?: entries.firstOrNull()?.reason)?.trim()
            if (reply.status == 400 && !detail.isNullOrEmpty()) throw HermesSessionRefusal(detail, entries)
            if (reply.status == 409) HermesSessionFenceRefusal.fromDetail(json?.get("detail")?.text)?.let { throw it.asException() }
            throw BotFailure.Rejected(reply.status)
        }
        return json?.takeIf { it.isObject } ?: throw BotFailure.Unsupported
    }

    suspend fun searchSessions(query: String, profile: String, limit: Int): List<HermesSessionSearchResult> {
        if (!gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        val data = http.data(HermesREST.SessionSearch(query, profile, limit), validateDispatch = { checkOwner(attempt) })
        checkOwner(attempt)
        return decodeOrUnsupported(HermesSessionSearch.serializer(), data).results
    }

    suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String {
        if (context.connectionID != http.connection.id || !gateway.isAttached(consumerID)) throw BotFailure.Stale
        val attempt = this.attempt
        return tracked {
            val path = BotAttachmentUpload.image(data, filename, context.profile, http, validateDispatch = { checkOwner(attempt) })
            checkOwner(attempt)
            path
        }
    }

    /** Runs [block] as this screen's local work, cancelled by [close], a reconnect or a lost socket. */
    private suspend fun <T> tracked(block: suspend () -> T): T = coroutineScope {
        val job = coroutineContext.job
        localWork += job
        try {
            block()
        } finally {
            localWork -= job
        }
    }

    /**
     * Throws [BotFailure.Stale] once [close], a reconnect or a lost socket has ended [attempt].
     * The HTTP calls check it on their result and, through `validateDispatch`, before each send.
     */
    private fun checkOwner(attempt: Int) {
        if (attempt != this.attempt) throw BotFailure.Stale
    }

    /**
     * Ends this screen's part: its calls fail with [BotFailure.Transport], its uploads and
     * downloads stop, and nothing more reaches [onEvent]. Not a disconnect: [onDisconnect] stays quiet.
     */
    suspend fun close() = withContext(HermesActor.dispatcher) { closeNow() }

    /** [close] for a caller already on the actor. */
    internal fun closeNow() {
        endLocalWork()
        gateway.leaveNow(this)
    }

    /** The gateway's report that this client's connection is gone. The gateway has already failed its calls. */
    internal fun socketEnded(error: Throwable) {
        endLocalWork()
        onDisconnect?.invoke(error)
    }

    private fun endLocalWork() {
        attempt += 1
        for (job in localWork) job.cancel()
        localWork.clear()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** The bytes of a speak reply's `data_url`, `data:<mime>;base64,<audio>`. */
        fun speechAudio(body: ByteArray): ByteArray {
            val url = BotJSON.parseOrNull(body)?.get("data_url")?.text ?: throw BotFailure.Unsupported
            val comma = url.indexOf(',')
            if (!url.startsWith("data:") || comma < 0 || !url.substring(0, comma).endsWith(";base64")) throw BotFailure.Unsupported
            val audio = runCatching { Base64.getDecoder().decode(url.substring(comma + 1)) }.getOrNull()
            if (audio == null || audio.isEmpty()) throw BotFailure.Unsupported
            return audio
        }

        private fun <T> decodeOrUnsupported(serializer: kotlinx.serialization.DeserializationStrategy<T>, data: ByteArray): T =
            runCatching { json.decodeFromString(serializer, data.toString(Charsets.UTF_8)) }.getOrNull() ?: throw BotFailure.Unsupported
    }
}
