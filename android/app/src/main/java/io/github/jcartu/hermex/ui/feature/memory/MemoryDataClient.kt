package io.github.jcartu.hermex.ui.feature.memory

import io.github.jcartu.hermex.model.MemoryResponse
import io.github.jcartu.hermex.model.MemorySection
import io.github.jcartu.hermex.model.MemoryWriteResponse
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesREST

/**
 * Kind-neutral Memory surface. Port of `Networking/APIClient+Memory.swift`
 * `MemoryDataClient`; direct Hermes reads one Profile's memory files and soul.
 */
interface MemoryDataClient {
    val features: MemoryFeatures

    suspend fun memory(): MemorySnapshot

    suspend fun saveMemory(
        section: MemorySection,
        content: String,
        loaded: String,
    ): MemoryWriteResponse
}

/** Server capabilities that change the shared Memory editor. */
data class MemoryFeatures(
    val canEdit: Boolean,
    val editsApplyNextSession: Boolean,
) {
    companion object {
        val webui = MemoryFeatures(canEdit = false, editsApplyNextSession = false)
        val hermes = MemoryFeatures(canEdit = true, editsApplyNextSession = true)
    }
}

/** A Memory response plus direct-Hermes file policy that webui does not send. */
data class MemorySnapshot(
    val response: MemoryResponse,
    val hiddenSections: Set<MemorySection> = emptySet(),
    val characterLimits: Map<MemorySection, Int> = emptyMap(),
    val readOnlySections: Set<MemorySection> = emptySet(),
)

/** Legacy adapter. The pre-existing Android webui surface remains read-only. */
class WebuiMemoryDataClient(private val api: HermesApi) : MemoryDataClient {
    override val features: MemoryFeatures = MemoryFeatures.webui

    override suspend fun memory(): MemorySnapshot = MemorySnapshot(api.memory())

    override suspend fun saveMemory(
        section: MemorySection,
        content: String,
        loaded: String,
    ): MemoryWriteResponse = throw MemoryReadOnly()
}

/**
 * One Hermes Profile's `memories/MEMORY.md`, `USER.md`, and SOUL.md (#1073).
 * Port of `HermesMemoryClient` in `Networking/APIClient+Memory.swift`.
 */
class HermesMemoryDataClient(
    private val http: HermesConnection,
    val profile: String,
    private val resolveMemoriesFolder: (suspend () -> String)? = null,
) : MemoryDataClient {
    override val features: MemoryFeatures = MemoryFeatures.hermes

    override suspend fun memory(): MemorySnapshot {
        val folder = memoriesFolder()
        val config = memoryConfig()
        val soul = soul()
        val notes = if (config.memoryEnabled) read("$folder/MEMORY.md") else null
        val user = if (config.userProfileEnabled) read("$folder/USER.md") else null
        return MemorySnapshot(
            response = MemoryResponse(
                memory = notes?.text,
                user = user?.text,
                soul = soul,
            ),
            hiddenSections = buildSet {
                if (!config.memoryEnabled) add(MemorySection.MEMORY)
                if (!config.userProfileEnabled) add(MemorySection.USER)
            },
            characterLimits = mapOf(
                MemorySection.MEMORY to config.memoryLimit,
                MemorySection.USER to config.userLimit,
            ),
            readOnlySections = buildSet {
                if (notes?.isEditable == false) add(MemorySection.MEMORY)
                if (user?.isEditable == false) add(MemorySection.USER)
            },
        )
    }

    override suspend fun saveMemory(
        section: MemorySection,
        content: String,
        loaded: String,
    ): MemoryWriteResponse {
        when (section) {
            MemorySection.SOUL -> {
                val current = soul()
                if (current != loaded && current != content) throw MemoryConflict()
                accepted(http.reply(HermesREST.SetProfileSoul(profile, content)))
            }

            MemorySection.MEMORY,
            MemorySection.USER,
            -> {
                val folder = memoriesFolder()
                val filename = if (section == MemorySection.USER) "USER.md" else "MEMORY.md"
                val path = "$folder/$filename"
                val canonical = MemoryCanonicalizer.canonical(content)
                val current = read(path)
                if (!current.isEditable || (current.text != loaded && current.text != canonical)) {
                    throw MemoryConflict()
                }
                var written = http.reply(HermesREST.FsWriteText(path, canonical))
                if (written.status == 400 && detail(written.body) == "Parent directory does not exist") {
                    accepted(http.reply(HermesREST.FilesMkdir(folder)))
                    written = http.reply(HermesREST.FsWriteText(path, canonical))
                }
                accepted(written)
                if (read(path).text != canonical) throw MemoryConflict()
            }
        }
        return MemoryWriteResponse(ok = true, section = section)
    }

    private suspend fun memoriesFolder(): String {
        val resolver = resolveMemoriesFolder
        if (resolver != null) return resolver()
        val client = BotClient(http)
        return try {
            client.connect()
            val rows = client.call(
                io.github.jcartu.hermex.net.hermes.HermesCall.ProfilesList(includeSessions = false),
            )["profiles"].list.orEmpty()
            memoriesFolder(rows, profile) ?: throw HermesMemoryProfileMissing()
        } finally {
            client.close()
        }
    }

    private suspend fun memoryConfig(): Config {
        val json = BotJSON.parse(accepted(http.reply(HermesREST.Config(profile))))
        val memory = json["memory"]
        if (!memory.isObject) return Config()
        return Config(
            memoryEnabled = truthy(memory["memory_enabled"]),
            userProfileEnabled = truthy(memory["user_profile_enabled"]),
            memoryLimit = memory["memory_char_limit"].integer ?: 2_200,
            userLimit = memory["user_char_limit"].integer ?: 1_375,
        )
    }

    private suspend fun soul(): String {
        val body = BotJSON.parse(accepted(http.reply(HermesREST.ProfileSoul(profile))))
        return body["content"].text ?: throw HermesMemoryUnreadable()
    }

    private suspend fun read(path: String): MemoryFile {
        val reply = http.reply(HermesREST.FsReadText(path))
        if (reply.status == 404) return MemoryFile(text = "", isEditable = true)
        val body = BotJSON.parse(accepted(reply))
        val text = body["text"].text ?: throw HermesMemoryUnreadable()
        return MemoryFile(
            text = text,
            isEditable = body["truncated"].flag != true && body["binary"].flag != true,
        )
    }

    private fun accepted(reply: HermesConnection.Reply): ByteArray {
        if (reply.status in 200..299) return reply.body
        if (reply.status in 400..499) {
            val detail = detail(reply.body)
            if (detail != null && '/' !in detail && '\\' !in detail) throw HermesMemoryRefusal(detail)
            if (reply.status == 403) throw BotFailure.Rejected(403)
        }
        if (reply.status in 502..504 || reply.status in 520..530) throw BotFailure.Rejected(reply.status)
        throw BotFailure.Rejected(reply.status)
    }

    private data class Config(
        val memoryEnabled: Boolean = true,
        val userProfileEnabled: Boolean = true,
        val memoryLimit: Int = 2_200,
        val userLimit: Int = 1_375,
    )

    private data class MemoryFile(val text: String, val isEditable: Boolean)

    companion object {
        /** Profile home + `/memories`, never shown, logged, or persisted. */
        fun memoriesFolder(rows: List<BotJSON>, profile: String): String? {
            val path = rows.firstOrNull { it["name"].text == profile }
                ?.get("path")?.text
                ?.takeIf { it.isNotEmpty() && '\u0000' !in it }
                ?: return null
            return path.trimEnd('/') + "/memories"
        }

        private fun truthy(value: BotJSON): Boolean {
            value.flag?.let { return it }
            value.number?.let { return it != 0.0 }
            value.text?.let { return it.trim().lowercase() in setOf("1", "true", "yes", "on") }
            return true
        }

        private fun detail(body: ByteArray): String? = BotJSON.parseOrNull(body)
            ?.get("detail")?.text?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** Agent memory-file canonicalization and host character count. Port of `Models/Memory.swift`. */
object MemoryCanonicalizer {
    const val SEPARATOR = "\n§\n"

    fun canonical(text: String): String {
        val withoutBom = text.removePrefix("\uFEFF")
        val normalized = withoutBom.replace("\r\n", "\n").replace('\r', '\n')
        val entries = ArrayList<String>()
        val current = StringBuilder()
        for (line in normalized.split('\n')) {
            if (pythonTrim(line) == "§") {
                entries += current.toString()
                current.setLength(0)
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        entries += current.toString()
        val seen = LinkedHashSet<String>()
        for (entry in entries) {
            val kept = pythonTrim(entry)
            if (kept.isNotEmpty()) seen += kept
        }
        return seen.joinToString(SEPARATOR)
    }

    fun scalarCount(text: String): Int = text.codePointCount(0, text.length)

    private fun pythonTrim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end) {
            val codePoint = value.codePointAt(start)
            if (!isPythonSpace(codePoint)) break
            start += Character.charCount(codePoint)
        }
        while (end > start) {
            val codePoint = value.codePointBefore(end)
            if (!isPythonSpace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return value.substring(start, end)
    }

    private fun isPythonSpace(codePoint: Int): Boolean =
        codePoint in 0x09..0x0D || codePoint in 0x1C..0x20 || codePoint == 0x85 ||
            codePoint == 0xA0 || codePoint == 0x1680 || codePoint in 0x2000..0x200A ||
            codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
            codePoint == 0x205F || codePoint == 0x3000
}

class MemoryConflict : Exception("Memory changed on the host.")

class MemoryReadOnly : Exception("Memory editing is not available on this server.")

class HermesMemoryRefusal(detail: String) : Exception("The server rejected the request: $detail")

class HermesMemoryProfileMissing : Exception("This Profile is no longer on the server.")

private class HermesMemoryUnreadable : Exception("Hermes returned an unreadable memory file.")
