package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.TranscribeResponse
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesUpdateCheck
import io.github.jcartu.hermex.model.hermes.HermesUpdateReceipt
import io.github.jcartu.hermex.model.hermes.HermesUpdateStart
import io.github.jcartu.hermex.model.hermes.HermesUpdateStatus
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Base64

/**
 * Port of `HermesUpdateClient` (`Networking/Hermes/HermesUpdateClient.swift`): Settings'
 * update rows on a Hermes host (#1075): the host's update check, `hermes update` started from
 * the phone, and the reads that follow it through the dashboard's restart. The signed-in reads
 * go over the sign-in the server's Bot screens share, which signs in again on the restarted
 * dashboard's first 401; [health] is public, so it answers while no sign-in can.
 */
class HermesUpdateClient(private val http: HermesConnection) {

    suspend fun check(force: Boolean): HermesUpdateCheck =
        HermesUpdateCheck.from(BotJSON.parse(http.data(HermesREST.UpdateCheck(force))))

    /**
     * Starts the update, or joins the one already running. The host refuses an install that
     * can't update itself in place with 200 and `ok: false`; any other status throws.
     */
    suspend fun start(): HermesUpdateStart {
        val reply = BotJSON.parse(http.data(HermesREST.UpdateApply))
        if (reply["ok"].flag != true) {
            return HermesUpdateStart.Refused(
                error = reply["error"].text, message = reply["message"].text, command = reply["update_command"].text,
            )
        }
        return HermesUpdateStart.Started(pid = reply["pid"].integer)
    }

    /** The update action's status. A host without the action answers 404, thrown as `Rejected(404)`. */
    suspend fun status(): HermesUpdateStatus = HermesUpdateStatus.from(BotJSON.parse(http.data(HermesREST.UpdateStatus)))

    /** The latest receipt's summary, or null when no update has run (404). */
    suspend fun receipt(): HermesUpdateReceipt? = try {
        val reply = BotJSON.parse(http.data(HermesREST.UpdateReceipt))
        HermesUpdateReceipt.from(reply["summary"]) ?: HermesUpdateReceipt.from(reply["receipt"])
    } catch (error: BotFailure.Rejected) {
        if (error.code == 404) null else throw error
    }

    /** The release the running dashboard reports, read without signing in; null when it answers without one. */
    suspend fun health(): String? = BotJSON.parse(http.publicData(HermesREST.Health))["version"].text
}

/**
 * Port of `Networking/Hermes/HermesTranscription.swift`: dictation's speech-to-text on a
 * Hermes host (#1071): one `POST /api/audio/transcribe` for the chat's Profile, on the sign-in
 * the server's other Hermes screens share, read as the webui's [TranscribeResponse] so the
 * composer treats both servers alike. It gets the long deadline.
 */
object HermesTranscription {
    /** The composer records AAC in an m4a container, which the host keeps as `.m4a`. */
    const val MIME_TYPE = "audio/m4a"

    /** Transcribes one recording for [profile] on [http]. */
    suspend fun transcribe(audio: ByteArray, profile: String, http: HermesConnection): TranscribeResponse {
        val upload = request(audio, profile, http)
        return response(http.reply(upload, HermesConnection.Deadline.PROVISIONING))
    }

    /** Built off the actor: a long recording's base64 runs to megabytes. */
    private suspend fun request(audio: ByteArray, profile: String, http: HermesConnection): Request = withContext(Dispatchers.Default) {
        HermesREST.Transcribe(
            profile = profile, dataURL = "data:$MIME_TYPE;base64," + Base64.getEncoder().encodeToString(audio), mimeType = MIME_TYPE,
        ).request(http.connection.address)
    }

    /**
     * `{ok, transcript}` as it is, so silence stays an empty success. A refusal or provider
     * failure the host explains in `detail` is the reply's `error`. Hermes never answers 403,
     * 502-504 or 520-530 itself, so those get the Hermes connection's copy.
     */
    fun response(reply: HermesConnection.Reply): TranscribeResponse {
        val body = reply.json
        return when (reply.status) {
            in 200..299 -> TranscribeResponse(ok = body?.get("ok")?.flag, transcript = body?.get("transcript")?.text, error = null)
            403, in 502..504, in 520..530 -> throw BotFailure.Rejected(reply.status)
            else -> {
                val detail = body?.get("detail")?.text?.trim()
                if (!detail.isNullOrEmpty()) return TranscribeResponse(ok = false, transcript = null, error = detail)
                throw ApiError.Http(reply.status, reply.body.toString(Charsets.UTF_8))
            }
        }
    }
}
