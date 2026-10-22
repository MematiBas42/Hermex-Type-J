package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotLiveStatus

/**
 * Port of `BotProfile` (`Features/Bots/BotConnection.swift`): one `profiles.list` row.
 * Identity comes only from server fields: the Desktop title, then the core `display_name`,
 * then the Profile name (`default` reads as Hermes, as in Desktop). Description follows the
 * same Desktop-then-core order. Pinned, hidden and the user section are Desktop's roster
 * organization: Desktop stamps each filed bot with the section's id and name (`sectionId`,
 * `sectionName`); its section list and order stay in Desktop's plugin storage.
 */
data class BotProfile(
    /** The Profile name, the server's key for this bot. */
    val id: String,
    val name: String,
    val title: String?,
    val displayName: String?,
    val description: String?,
    val preview: String?,
    /** Epoch seconds of the canonical chat's last activity. */
    val lastActive: Double?,
    /**
     * The canonical chat's root (`canonical_session.id`) and the compression tip the roster
     * read resolved (`resolved_id`). Live statuses match runtimes against both.
     */
    val canonicalID: String?,
    val canonicalTipID: String?,
    val pinned: Boolean,
    val hidden: Boolean,
    /** Desktop's user section, trimmed; null when unfiled. A bot with an id but no name is treated as unfiled. */
    val sectionID: String?,
    val sectionName: String?,
    /** Desktop's `ui_meta["hermes-bots"]` object as received; sent back whole with changed fields applied. */
    val look: Map<String, BotJSON>,
    /** True when the host has an avatar asset, so the inbox fetches only rows that have one. */
    val hasAvatar: Boolean,
    /** Desktop's compare-and-swap revision for this bot's look (`ui_meta_revisions["hermes-bots"]`). */
    val lookRevision: Int?,
) {
    /** True when the look marks the asset as an uploaded photo (`imageKind`), not a copy of the face. */
    val isPhoto: Boolean get() = look["imageKind"]?.text == "photo"

    companion object {
        /** `init?(_ row:)` — null for a row without a Profile name. */
        fun fromJsonOrNull(row: BotJSON): BotProfile? {
            val profile = row["name"].text?.takeIf { it.isNotEmpty() } ?: return null
            val look = row["ui_meta"]["hermes-bots"]
            val canonical = row["canonical_session"]
            return BotProfile(
                id = profile,
                title = firstText(look["title"]),
                displayName = firstText(row["display_name"]),
                name = firstText(look["title"], row["display_name"]) ?: if (profile == "default") "Hermes" else profile,
                description = firstText(look["description"], row["description"]),
                preview = canonical["preview"].text,
                lastActive = canonical["last_active"].number?.takeIf { it.isFinite() },
                canonicalID = firstText(canonical["id"]),
                canonicalTipID = firstText(canonical["resolved_id"]),
                pinned = look["pinned"].flag == true,
                hidden = look["hidden"].flag == true,
                sectionID = firstText(look["sectionId"]),
                sectionName = firstText(look["sectionName"]),
                look = look.fields ?: emptyMap(),
                hasAvatar = row["has_avatar"].flag == true,
                lookRevision = row["ui_meta_revisions"]["hermes-bots"].integer,
            )
        }

        private fun firstText(vararg candidates: BotJSON): String? {
            for (candidate in candidates) {
                val trimmed = candidate.text?.trim().orEmpty()
                if (trimmed.isNotEmpty()) return trimmed
            }
            return null
        }
    }
}

/**
 * Port of `BotLiveStatus.statuses(_:profiles:)` (`BotConnection.swift`): maps
 * `session.active_list` items onto bots by `session_key`, which is the live compression tip
 * or, before the agent exists, the stored key; so it is matched against both the canonical
 * root and the tip the roster read. Items carry no Profile, and stored ids can repeat across
 * Profiles, so a key that names more than one bot marks none of them. Several items on one
 * bot: the most urgent wins (`WAITING` over `WORKING`).
 */
object BotLiveStatuses {
    fun statuses(items: List<BotJSON>, profiles: List<BotProfile>): Map<String, BotLiveStatus> {
        val owners = HashMap<String, MutableSet<String>>()
        for (profile in profiles) {
            for (key in setOfNotNull(profile.canonicalID, profile.canonicalTipID)) {
                owners.getOrPut(key) { LinkedHashSet() }.add(profile.id)
            }
        }
        val result = LinkedHashMap<String, BotLiveStatus>()
        for (item in items) {
            val status = BotLiveStatus.fromWire(item["status"].text) ?: continue
            val key = item["session_key"].text ?: continue
            val matched = owners[key]?.takeIf { it.size == 1 } ?: continue
            val profile = matched.first()
            val previous = result[profile]
            result[profile] = if (previous == null || status.ordinal > previous.ordinal) status else previous
        }
        return result
    }
}
