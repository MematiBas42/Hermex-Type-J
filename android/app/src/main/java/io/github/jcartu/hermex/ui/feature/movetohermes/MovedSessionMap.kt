package io.github.jcartu.hermex.ui.feature.movetohermes

/**
 * Legacy-entry routing port of Move to Hermes #1217. The record belongs to the destination,
 * not the source, so [lookup] keeps working after the old webui server is removed and stops
 * immediately when the destination is no longer configured.
 */
class MovedSessionMap(
    private val store: MoveToHermesStore,
    /** Current server id → registry creation time; a remove/re-add is a different destination. */
    private val configuredServers: () -> Map<String, Double>,
) {
    data class Move(val destinationServerId: String, val sourceServerId: String)

    sealed interface Mapping {
        data class Hermes(val profileName: String) : Mapping
        data object KeepOnWebui : Mapping
    }

    val moves: List<Move>
        get() = store.state.value.moves.filter(::isDestinationCurrent).map {
            Move(destinationServerId = it.destinationServerId, sourceServerId = it.sourceServerId)
        }

    fun destinationOf(sourceServerId: String): String? =
        store.recordForSource(sourceServerId)?.takeIf(::isDestinationCurrent)?.destinationServerId

    fun profileMapping(sourceServerId: String, profile: String): Mapping? {
        val record = store.recordForSource(sourceServerId)?.takeIf(::isDestinationCurrent) ?: return null
        val mapping = record.profileMappings.firstOrNull { it.sourceProfile == profile } ?: return null
        return when {
            mapping.destinationProfile != null -> Mapping.Hermes(mapping.destinationProfile)
            mapping.keepOnWebui -> Mapping.KeepOnWebui
            else -> null
        }
    }

    fun lookup(sourceServerId: String, sessionId: String): MovedSessionTarget? {
        val record = store.recordForSource(sourceServerId)?.takeIf(::isDestinationCurrent) ?: return null
        return record.movedSessions[sessionId]
    }

    /** Route a legacy Profile-scoped New Chat/Shortcut only when Review mapped it to Hermes. */
    fun lookupProfile(sourceServerId: String, sourceProfile: String): MovedProfileTarget? {
        val record = store.recordForSource(sourceServerId)?.takeIf(::isDestinationCurrent) ?: return null
        val destination = record.profileMappings
            .firstOrNull { it.sourceProfile == sourceProfile }
            ?.destinationProfile
            ?: return null
        return MovedProfileTarget(record.destinationServerId, destination)
    }

    private fun isDestinationCurrent(record: MoveRecord): Boolean {
        val createdAt = configuredServers()[record.destinationServerId] ?: return false
        return createdAt == record.destinationCreatedAt
    }
}
