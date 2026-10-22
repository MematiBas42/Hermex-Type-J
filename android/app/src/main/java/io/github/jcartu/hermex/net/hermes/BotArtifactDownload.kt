package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactFailure
import io.github.jcartu.hermex.model.hermes.BotAttachmentFailure
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Port of `BotArtifactReference` (`Features/Bots/BotArtifact.swift`): paths stay server-side.
 * Relative paths and `~` are resolved by `fs/download` using the originating session and
 * Profile, never on the phone.
 */
object BotArtifactReference {
    private val DOWNLOAD_ROUTES = setOf("/api/fs/download", "/api/files/download", "/api/media", "/api/fs/read-data-url")

    @Throws(BotArtifactFailure::class)
    fun path(raw: String, address: HttpUrl): String {
        val value = raw.trim()
        if (value.isEmpty() || value.contains('\u0000') || value.startsWith("//")) throw BotArtifactFailure.InvalidReference
        val schemeEnd = value.indexOf(':')
        val scheme = if (schemeEnd > 0 && value.substring(0, schemeEnd).all { it.isLetterOrDigit() || it in "+-." } &&
            value.substring(0, schemeEnd).first().isLetter()
        ) value.substring(0, schemeEnd).lowercase() else null
        if (scheme == null) return value
        if (scheme == "file") {
            val rest = value.substring(schemeEnd + 1)
            val path = when {
                rest.startsWith("//localhost/") -> rest.removePrefix("//localhost")
                rest.startsWith("///") -> rest.substring(2)
                rest.startsWith("//") -> throw BotArtifactFailure.InvalidReference
                else -> rest
            }
            return java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        }
        if (scheme == "http" || scheme == "https") {
            // Desktop may emit an authenticated download URL. Extract only its path; never
            // carry its token, Profile override, or session override forward.
            val url = value.toHttpUrlOrNull() ?: throw BotArtifactFailure.InvalidReference
            val sameOrigin = url.scheme == address.scheme && url.host == address.host && url.port == address.port
            val path = url.queryParameter("path")
            if (sameOrigin && url.encodedPath in DOWNLOAD_ROUTES && !path.isNullOrEmpty() && !path.contains('\u0000')) {
                return path
            }
        }
        throw BotArtifactFailure.InvalidReference
    }
}

/**
 * Port of `BotArtifactDownload` (`Networking/Bots/BotArtifactDownload.swift`): reads one
 * artifact from a [HermesREST.DownloadArtifact] request, or one [HermesREST.Speak] reply, on
 * the client `HermesConnection.authorized` passes in. `limit` caps a preview's bytes; null
 * reads the whole file, as an export does (#1112). An empty body is a failed read unless
 * `allowsEmpty`. Redirects are refused: a redirect can point at a login page or another host.
 */
object BotArtifactDownload {
    /** `BotArtifactBuffer.maximumBytes` — native preview downloads are bounded. */
    const val MAXIMUM_BYTES = 25 * 1024 * 1024

    suspend fun data(
        client: OkHttpClient,
        request: Request,
        limit: Int? = MAXIMUM_BYTES,
        allowsEmpty: Boolean = false,
    ): ByteArray {
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val response = noRedirects.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                if (it.code != 200) {
                    if (it.code == 401 || it.code == 403) throw BotFailure.Rejected(it.code)
                    if (it.code == 400 && isFolderRefusal(it.body.byteStream().readUpTo(1024))) throw BotArtifactFailure.Folder
                    throw BotArtifactFailure.Unavailable
                }
                val expected = it.body.contentLength()
                if (limit != null && expected > limit.toLong()) throw BotArtifactFailure.TooLarge
                val buffer = ByteArrayOutputStream(if (expected in 1..(1L shl 20)) expected.toInt() else 64 * 1024)
                val chunk = ByteArray(64 * 1024)
                val stream = it.body.byteStream()
                while (true) {
                    ensureActive()
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (limit != null && buffer.size() + read > limit) throw BotArtifactFailure.TooLarge
                    buffer.write(chunk, 0, read)
                }
                val bytes = buffer.toByteArray()
                if (!allowsEmpty && bytes.isEmpty()) throw BotArtifactFailure.Unavailable
                bytes
            }
        }
    }

    /**
     * Whether a 400's body is `fs_download`'s refusal of a folder (`_fs_regular_file` in
     * `hermes_cli/web_routers/files.py` at the pin). Reads at most 1 KiB.
     */
    private fun isFolderRefusal(body: ByteArray): Boolean =
        BotJSON.parseOrNull(body)?.get("detail")?.text == "Path points to a directory"
}

/**
 * Port of the transport half of `BotAttachmentUpload` (`Networking/Bots/BotAttachmentUpload.swift`):
 * uploading stores bytes only; the returned reference travels in one prompt. Image re-encoding
 * (`prepare`) is the chat screen's, with Android's bitmap APIs.
 */
object BotAttachmentUpload {
    const val MAXIMUM_FILE_BYTES = 25 * 1024 * 1024
    const val MAXIMUM_TOTAL_BYTES = 50 * 1024 * 1024

    /** Stores one image over [http]'s signed-in session; `validateDispatch` is as in `HermesConnection.authorized`. */
    suspend fun image(
        data: ByteArray,
        filename: String,
        profile: String,
        http: HermesConnection,
        validateDispatch: (() -> Unit)? = null,
    ): String {
        val request = request(data, filename, profile, http.connection.address)
        return http.authorized(request, validateDispatch = validateDispatch) { prepared, client -> send(prepared, client) }
    }

    @Throws(BotAttachmentFailure::class)
    fun request(data: ByteArray, filename: String, profile: String, base: HttpUrl): Request {
        if (data.isEmpty() || data.size > MAXIMUM_FILE_BYTES) throw BotAttachmentFailure.Limit
        val mime = if (filename.substringAfterLast('.', "").lowercase() == "png") "image/png" else "image/jpeg"
        return HermesREST.UploadImage(
            profile = profile, filename = filename,
            dataURL = "data:$mime;base64," + Base64.getEncoder().encodeToString(data),
        ).request(base)
    }

    /** Sends one upload and returns the verified stored path. Redirects are refused. */
    suspend fun send(request: Request, client: OkHttpClient): String {
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val response = noRedirects.newCall(request).await()
        val body = withContext(Dispatchers.IO) {
            response.use {
                if (it.code != 200) throw BotFailure.Rejected(it.code)
                val bytes = it.body.byteStream().readUpTo(64 * 1024 + 1)
                if (bytes.size > 64 * 1024) throw BotFailure.Unsupported
                bytes
            }
        }
        val reply = BotJSON.parseOrNull(body) ?: throw BotFailure.Unsupported
        if (reply["ok"].flag != true) throw BotFailure.Unsupported
        return verifiedPath(reply["path"].text)
    }

    /** Builds `file.attach`; base64 of a large file is slow, so callers run it off the main thread. */
    fun fileAttach(data: ByteArray, runtime: String, filename: String, mime: String): HermesCall =
        HermesCall.FileAttach(
            sessionID = runtime, name = UUID.randomUUID().toString().uppercase() + "-" + filename,
            dataURL = "data:$mime;base64," + Base64.getEncoder().encodeToString(data),
        )

    @Throws(BotFailure::class)
    fun verifiedPath(value: String?): String {
        if (value == null || !value.startsWith("/") || value.contains('\n') || value.contains('\r') || value.contains('\u0000')) {
            throw BotFailure.Unsupported
        }
        return value
    }

    /** Matches Hermes's text-mode image routing: let the agent call its vision tool. */
    fun imageReference(path: String): String =
        "[The user attached an image: ${path.trimEnd('/').substringAfterLast('/')}]\n" +
            "[Examine it with the vision_analyze tool using image_url: $path]"

    private val IMAGE_BLOCK = Regex(
        "\\[The user attached an image: [^\\n]*\\]\\n\\[Examine it with the vision_analyze tool using image_url: /[^\\n]*\\]",
    )
    private val FILE_BLOCK = Regex(
        "@file:[`\"']?(?:[^\\n]*/)?[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}-[^\\n/]+",
    )

    /**
     * A sent prompt without the attachment references a Hermes chat's send appended to it,
     * one `\n\n` block per file: [imageReference], or `file.attach`'s one-line `@file:` ref,
     * whose file name keeps the UUID prefix [fileAttach] gave it.
     */
    fun typedText(prompt: String): String {
        val blocks = prompt.split("\n\n").toMutableList()
        while (blocks.isNotEmpty()) {
            val last = blocks.last().trim()
            if (FILE_BLOCK.matches(last) || IMAGE_BLOCK.matches(last)) blocks.removeAt(blocks.lastIndex) else break
        }
        return blocks.joinToString("\n\n")
    }
}

/** At most [limit] bytes from this stream, fewer at its end. */
internal fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(minOf(limit, 8 * 1024).coerceAtLeast(1))
    while (buffer.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
