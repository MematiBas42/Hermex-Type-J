package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.model.ToggleSkillResponse
import io.github.jcartu.hermex.model.hermes.BotArtifactFailure
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSkillDetail
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray

/**
 * Port of `HermesSkillsClient` (`Networking/Hermes/HermesSkillsClient.swift`): the Skills
 * screens' client on a Hermes host (#1069): one Profile's skills under `/api/skills`, over the
 * sign-in, headers and cookie jar the server's Bot screens share. The host sends no tags or
 * related skills. A skill's linked files (#1070) are the other files in its SKILL.md's folder,
 * read through `/api/fs/…`: every path it is asked for is built from that folder and stays
 * inside it. The folder is an opaque handle, never shown, logged or persisted.
 */
class HermesSkillsClient(private val http: HermesConnection, val profile: String) {
    /** Each read skill's folder on the host, from its SKILL.md's path, for opening its files. */
    private val folders = HashMap<String, String>()

    suspend fun skills(): List<SkillSummary> = skills(send(HermesREST.Skills(profile)))

    /**
     * SKILL.md with its folder's other files as `linkedFiles`, or, when [file] names one of
     * them, that file's text. A [file] that would leave the folder is refused before any request.
     */
    suspend fun skillContent(name: String, file: String? = null): HermesSkillDetail {
        if (file == null) {
            val detail = skillMd(name)
            val folder = folders[name] ?: return detail
            val files = linkedFiles(folder)
            return detail.copy(linkedFiles = files.takeIf { it.isNotEmpty() })
        }
        if (!file.split("/").all { isName(it) }) throw BotArtifactFailure.Unavailable
        if (folders[name] == null) skillMd(name)
        val folder = folders[name] ?: throw BotArtifactFailure.Unavailable
        val reply = HermesCronClient.json(send(HermesREST.FsReadText("$folder/$file")))
        val isBinary = reply["binary"].flag == true
        return HermesSkillDetail(
            name = name, content = if (isBinary) null else reply["text"].text, linkedFiles = null,
            isBinary = isBinary, isTruncated = reply["truncated"].flag == true,
        )
    }

    suspend fun toggleSkill(name: String, enabled: Boolean): ToggleSkillResponse {
        val reply = HermesCronClient.json(send(HermesREST.SetSkill(name, enabled, profile)))
        return ToggleSkillResponse(ok = reply["ok"].flag, name = reply["name"].text, enabled = reply["enabled"].flag)
    }

    /** [name]'s SKILL.md, remembering its folder: the host path it returns without `/SKILL.md`. */
    private suspend fun skillMd(name: String): HermesSkillDetail {
        val body = HermesCronClient.json(send(HermesREST.SkillContent(name, profile)))
        val path = body["path"].text ?: ""
        val suffix = listOf("/SKILL.md", "\\SKILL.md").firstOrNull { path.length > it.length && path.endsWith(it) }
        if (suffix != null) folders[name] = path.dropLast(suffix.length) else folders.remove(name)
        return HermesSkillDetail(name = body["name"].text, content = body["content"].text)
    }

    /**
     * The files in [folder] and in each folder directly inside it, as sorted paths relative to
     * it, without SKILL.md or hidden files. A listing the host can't read adds none.
     */
    private suspend fun linkedFiles(folder: String): List<String> {
        val top = entries(folder)
        val nested = coroutineScope {
            top.filter { it.second }.map { (name, _) ->
                async { entries("$folder/$name").filter { !it.second }.map { "$name/${it.first}" } }
            }.awaitAll().flatten()
        }
        return (top.filter { !it.second && it.first != "SKILL.md" }.map { it.first } + nested).sorted()
    }

    /** One folder's visible entries by name, as (name, isDirectory). */
    private suspend fun entries(folder: String): List<Pair<String, Boolean>> {
        val body = try {
            send(HermesREST.FsList(folder))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return emptyList()
        }
        val rows = BotJSON.parseOrNull(body)?.get("entries")?.list ?: return emptyList()
        return rows.mapNotNull { row ->
            val name = row["name"].text ?: return@mapNotNull null
            if (!isName(name) || name.startsWith(".")) return@mapNotNull null
            name to (row["isDirectory"].flag == true)
        }
    }

    /** One request's body, or the failure the host's status means; a dropped request reads as a network failure. */
    private suspend fun send(rest: HermesREST): ByteArray = try {
        HermesCronClient.accepted(http.reply(rest))
    } catch (error: BotFailure.Network) {
        throw ApiError.Network(error.underlying)
    }

    companion object {
        /**
         * `GET /api/skills`'s bare array as the app's skills: each one `disabled` when the host
         * lists it not `enabled`, and a row without a name left out. A reply that is not an
         * array is a failed read, not an empty Profile.
         */
        fun skills(body: ByteArray): List<SkillSummary> {
            val rows = runCatching { HermesCronClient.json(body).element as? JsonArray }.getOrNull()
                ?: throw ApiError.Decoding(SerializationException("The skills list is not an array"))
            return rows.map { BotJSON(it) }.mapNotNull { row ->
                val name = row["name"].text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SkillSummary(
                    name = name, category = row["category"].text, description = row["description"].text,
                    path = null, disabled = row["enabled"].flag?.let { !it },
                )
            }
        }

        /** One file or folder name, so a path joined from names never leaves its folder. */
        private fun isName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
    }
}
