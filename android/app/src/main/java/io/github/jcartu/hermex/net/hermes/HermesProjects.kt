package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesProject
import io.github.jcartu.hermex.model.hermes.HermesSessionRow

/**
 * Port of `HermesProjectTree` (`Networking/Hermes/HermesProjects.swift`): a Hermes Profile's
 * project lanes from one `projects.tree` reply (#1052). A Hermes project is a set of host
 * folders, and a session belongs to the project with the deepest folder its working folder
 * sits in; the host derives that, so the list only reads it. Lanes are the user's projects,
 * then the automatic per-repository ones the host groups the rest into, in the host's order.
 * The "No project" bucket is not a lane. A node without an id is skipped.
 */
class HermesProjectTree(reply: BotJSON) {
    val projects: List<HermesProject>

    /** The lane that claims each session, by the session's `id` (a compression chain's tip). */
    val owners: Map<String, String>

    init {
        val user = mutableListOf<HermesProject>()
        val automatic = mutableListOf<HermesProject>()
        val owners = LinkedHashMap<String, String>()
        for (node in reply["projects"].list ?: emptyList()) {
            val id = node["id"].text?.takeIf { it.isNotEmpty() } ?: continue
            if (node["isNoProject"].flag == true || id == NO_PROJECT_ID) continue
            val sessionIDs = (node["sessionIds"].list ?: emptyList()).mapNotNull { it.text }.filter { it.isNotEmpty() }
            for (sessionID in sessionIDs) if (owners[sessionID] == null) owners[sessionID] = id
            val isAutomatic = node["isAuto"].flag == true
            val project = HermesProject(
                projectId = id,
                name = node["label"].text?.takeIf { it.isNotEmpty() } ?: id,
                color = node["color"].text?.takeIf { it.isNotEmpty() },
                folder = node["path"].text?.takeIf { it.isNotEmpty() },
                isAutomatic = isAutomatic,
                sessionCount = node["sessionCount"].integer ?: sessionIDs.size,
                claimedCount = sessionIDs.size,
            )
            if (isAutomatic) automatic += project else user += project
        }
        projects = user + automatic
        this.owners = owners
    }

    companion object {
        const val NO_PROJECT_ID = "__no_project__"
    }
}

/**
 * Port of `HermesFolderCompletion`: the host folders that complete a typed path in the Hermes
 * create sheet's folder field (#1052), from `complete.path` outside any session.
 */
object HermesFolderCompletion {
    /** The most entries `complete.path` lists for one folder (`_dir_listing_items` at the pin). */
    const val HOST_LISTING_LIMIT = 30

    /** What the folder field offers for one typed path. */
    data class Suggestions(
        /** Each as the typed parent plus the folder's name and `/`, so the user can keep typing. */
        val folders: List<String> = emptyList(),
        /** The host's listing stopped at its limit before it reached a folder to offer. */
        val needsMoreTyping: Boolean = false,
    )

    /** Whether [word] can be completed: a path from the host's root or home. */
    fun completes(word: String): Boolean =
        (word.startsWith("/") || word.startsWith("~/")) && word.none { it == '\n' || it == '\r' }

    /**
     * The folders under the typed path's parent whose names start with its last part. Hidden
     * folders show only once their leading `.` is typed.
     */
    fun suggestions(reply: BotJSON, typed: String): Suggestions {
        val slash = typed.lastIndexOf('/')
        if (slash < 0) return Suggestions()
        val parent = typed.substring(0, slash + 1)
        val showsHidden = typed.substring(slash + 1).startsWith(".")
        val items = reply["items"].list ?: emptyList()
        val folders = items.mapNotNull { item ->
            if (item["meta"].text != "dir") return@mapNotNull null
            var name = item["display"].text ?: item["text"].text ?: return@mapNotNull null
            while (name.endsWith("/")) name = name.dropLast(1)
            if (name.isEmpty() || name.contains("/") || (!showsHidden && name.startsWith("."))) return@mapNotNull null
            parent + name + "/"
        }
        return Suggestions(folders = folders, needsMoreTyping = folders.isEmpty() && items.size >= HOST_LISTING_LIMIT)
    }

    /**
     * The folders a Hermes chat's folder picker offers before anything is typed (#1117): the
     * chat's [current] folder, the user's project folders from [projects], then the folders the
     * Profile's recent [sessions] worked in, each once. Automatic lanes are left out, as are
     * rows that name another Profile.
     */
    fun choices(current: String?, projects: HermesProjectTree?, sessions: List<HermesSessionRow>, profile: String): List<String> {
        val projectFolders = projects?.projects.orEmpty().mapNotNull { if (!it.isAutomatic) it.folder else null }
        val recentFolders = sessions.mapNotNull { row ->
            val rowProfile = row.profile
            if (rowProfile != null && rowProfile.isNotEmpty() && rowProfile != profile) return@mapNotNull null
            row.cwd
        }
        val seen = HashSet<String>()
        return (listOfNotNull(current) + projectFolders + recentFolders).filter { it.isNotEmpty() && seen.add(it) }
    }
}
