package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.hermes.BotArtifactFailure
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesWorkspaceFile
import io.github.jcartu.hermex.model.hermes.HermesWorkspaceFolderReadFailure
import io.github.jcartu.hermex.net.ApiError
import kotlinx.serialization.SerializationException

/**
 * Port of `HermesWorkspaceContext` (`Networking/Hermes/HermesWorkspaceFileClient.swift`): a
 * Hermes chat's working folder (#1112): the folder `session.info` says its runtime works in,
 * and the terminal backend it runs commands on, for the session [storedKey] names under
 * [profile]. Files, transcript file links and MEDIA references read it; the composer's `@`
 * panel (#1113) and Git (#1114) read the same context.
 */
data class HermesWorkspaceContext(
    /** The configured server's id (its address as `HermesAddress.serverID` makes it). */
    val server: String,
    val profile: String,
    val storedKey: String,
    /** The host path of the folder. Never shown, logged or persisted. */
    val cwd: String,
    /** `local`, `docker`, `ssh`, …; null while the host has not said. */
    val terminalBackend: String?,
) {
    /** Only a `local` backend's folder is on the host the dashboard reads. */
    val isLocal: Boolean get() = terminalBackend == "local"
}

/**
 * Port of `HermesWorkspaceFileClient`: a Hermes chat's workspace files (#1112), through the
 * host's `/api/fs/…` routes on the sign-in its Bot screens share. Those routes read any host
 * path a signed-in client names, so the fence here is the client's own rule: a
 * workspace-relative path becomes `cwd + "/" + path` only when it is relative and climbs
 * nowhere ([hostPath]), and a child's path is its folder's plus its name, never the listing's
 * `path`. Rows, previews and errors carry only relative paths.
 */
class HermesWorkspaceFileClient(
    val context: HermesWorkspaceContext,
    private val http: HermesConnection,
    /** The most a preview download reads ([BotArtifactDownload.MAXIMUM_BYTES]); tests pass a few bytes. */
    private val previewLimit: Int = BotArtifactDownload.MAXIMUM_BYTES,
) {
    /** The cache scope this client's rows and previews key by. */
    val scope: String = "${context.server}|hermes|${context.profile}|${context.storedKey}|${context.cwd}"

    val opensTopLevelFolders: Boolean = false

    /**
     * One folder, not recursive, folders first as the host sorts them. A folder it can't read
     * answers 200 `{entries: [], error}`, which throws [HermesWorkspaceFolderReadFailure].
     */
    suspend fun directoryList(path: String): List<WorkspaceEntry> {
        val reply = json(send(HermesREST.FsList(hostPath(path, context.cwd))))
        reply["error"].text?.takeIf { it.isNotEmpty() }?.let { throw HermesWorkspaceFolderReadFailure(it) }
        val rows = reply["entries"].list ?: throw ApiError.Decoding(BotFailure.Unsupported)
        return rows.mapNotNull { row ->
            val name = row["name"].text ?: return@mapNotNull null
            if (!isName(name)) return@mapNotNull null
            val isDirectory = row["isDirectory"].flag == true
            WorkspaceEntry(
                name = name, path = if (path == ROOT_PATH) name else "$path/$name",
                type = if (isDirectory) "dir" else "file", isDirectory = isDirectory,
            )
        }
    }

    /**
     * The file's first 512 KiB as text, or none for a binary file. A symlinked folder, which
     * the listing shows as a file, throws [BotArtifactFailure.Folder].
     */
    suspend fun file(path: String): HermesWorkspaceFile {
        val name = path.split("/").lastOrNull { it.isNotEmpty() }
        val body = try {
            send(HermesREST.FsReadText(hostPath(path, context.cwd)))
        } catch (refusal: HermesCronRefusal) {
            if (refusal.detail == "Path points to a directory") throw BotArtifactFailure.Folder
            throw refusal
        }
        val reply = json(body)
        val isBinary = reply["binary"].flag == true
        val isTruncated = reply["truncated"].flag == true
        val text = if (isBinary) null else reply["text"].text
        return HermesWorkspaceFile(
            content = text, path = path, name = name,
            language = reply["language"].text, size = reply["byteSize"].integer,
            lines = if (isTruncated) null else text?.let { lineCount(it) },
            isBinary = isBinary, isTruncated = isTruncated, isPreviewOnly = true,
        )
    }

    /** The whole file, for Save to Files and Share: no size cap, and an empty file is the file. */
    suspend fun rawFileData(path: String): ByteArray =
        download(hostPath(path, context.cwd), inSession = false, limit = null, allowsEmpty = true)

    /** At most 25 MB, for a preview. */
    suspend fun rawFilePreviewData(path: String): ByteArray =
        download(hostPath(path, context.cwd), inSession = false, limit = previewLimit)

    /** An image preview keeps the 25 MB cap of every Hermes preview download. */
    suspend fun imagePreviewData(path: String): ByteArray = rawFilePreviewData(path)

    /** A MEDIA path is the reply's own, often outside the folder, so it is sent as written. At most 25 MB. */
    suspend fun mediaData(path: String): ByteArray = download(path, inSession = true, limit = previewLimit)

    /** [mediaData] for Save and Share: the whole file. */
    suspend fun mediaExportData(path: String): ByteArray = download(path, inSession = true, limit = null, allowsEmpty = true)

    /** `inSession` sends the stored key, for a path the host must resolve against the session. */
    private suspend fun download(path: String, inSession: Boolean, limit: Int?, allowsEmpty: Boolean = false): ByteArray {
        val request = HermesREST.DownloadArtifact(path, context.profile, if (inSession) context.storedKey else null)
            .request(http.connection.address)
        return http.authorized(request) { prepared, client ->
            BotArtifactDownload.data(client, prepared, limit, allowsEmpty)
        }
    }

    /**
     * One request's body, or the failure its status means. A reason that names a path reads
     * as the generic failure instead, so no host path reaches the screen.
     */
    private suspend fun send(rest: HermesREST): ByteArray {
        val reply = try {
            http.reply(rest)
        } catch (error: BotFailure.Network) {
            throw ApiError.Network(error.underlying)
        }
        return try {
            HermesCronClient.accepted(reply)
        } catch (refusal: HermesCronRefusal) {
            if (refusal.detail.any { it == '/' || it == '\\' }) throw BotArtifactFailure.Unavailable
            throw refusal
        }
    }

    private fun json(body: ByteArray): BotJSON = try {
        BotJSON.parse(body)
    } catch (error: SerializationException) {
        throw ApiError.Decoding(error)
    } catch (error: IllegalArgumentException) {
        throw ApiError.Decoding(error)
    }

    companion object {
        /** `FileTree.rootPath` — the workspace root as the iOS file tree names it. */
        const val ROOT_PATH = "."

        /**
         * `hostPath(_:in:)` — the host path of [path], a workspace-relative path: the folder
         * itself for [ROOT_PATH], else `cwd + "/" + path`. Refuses an absolute path, a `..`
         * component and a path with nothing past the root, before any request.
         */
        @Throws(BotArtifactFailure::class)
        fun hostPath(path: String, cwd: String): String {
            val root = if (cwd.length > 1 && cwd.endsWith("/")) cwd.dropLast(1) else cwd
            if (path == ROOT_PATH) return root
            val parts = path.split("/")
            if (path.startsWith("/") || path.startsWith("~") || path.contains('\u0000') || parts.contains("..") ||
                parts.none { it.isNotEmpty() && it != "." }
            ) throw BotArtifactFailure.InvalidReference
            return (if (root == "/") "" else root) + "/" + path
        }

        /** Lines as an editor numbers them: a final newline ends the last line, and an empty file has none. */
        fun lineCount(text: String): Int {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val newlines = bytes.count { it == 0x0A.toByte() }
            return if (bytes.isEmpty() || bytes.last() == 0x0A.toByte()) newlines else newlines + 1
        }

        private fun isName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
    }
}
