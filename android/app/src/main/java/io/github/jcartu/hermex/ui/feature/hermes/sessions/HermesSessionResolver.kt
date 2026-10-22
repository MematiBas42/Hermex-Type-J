package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionRow
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.modelConfigText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** Port of Features/Hermes/HermesSessionLookup.swift's strict host lookup, separated from link delivery. */
object HermesSessionResolver {
    const val LINEAGE_HOP_LIMIT = 8
    sealed interface Outcome {
        data class Found(val session: HermesListedSession) : Outcome
        data object Gone : Outcome
        data object Ambiguous : Outcome
        data object Room : Outcome
    }

    /** Null Profile searches every Profile, empty means is_default, named means exactly one store. */
    suspend fun resolve(key: String, profile: String?, wire: HermesSessionWire, opensParent: Boolean = false): Outcome {
        val profiles = when {
            profile == null -> profiles(wire).map { it.first }
            profile.isEmpty() -> listOf(profiles(wire).firstOrNull { it.second }?.first ?: throw BotFailure.Unsupported)
            else -> listOf(profile)
        }
        // Any failed read fails the whole lookup: an unreadable store cannot prove uniqueness.
        val hits = coroutineScope { profiles.map { name -> async { exactRow(key, name, wire)?.let { name to it } } }.awaitAll().filterNotNull() }
        if (hits.isEmpty()) return Outcome.Gone
        if (hits.size != 1) return Outcome.Ambiguous
        val (name, found) = hits.single()
        var hit = found
        if (opensParent) {
            val delegator = hit.modelConfigText("_delegate_from")?.takeIf(String::isNotEmpty)
            val parent = hit["parent_session_id"].text?.takeIf(String::isNotEmpty)
            if (delegator != null) hit = exactRow(delegator, name, wire) ?: return Outcome.Gone
            else if (parent != null) {
                hit = exactRow(parent, name, wire)?.takeIf { it["end_reason"].text != "compression" } ?: return Outcome.Gone
            }
        }
        val row = listRow(hit, name, wire)
        return if (row.isRoomSession) Outcome.Room else Outcome.Found(HermesListedSession(row.profile?.takeIf(String::isNotEmpty) ?: name, row))
    }
    private suspend fun profiles(wire: HermesSessionWire): List<Pair<String, Boolean>> {
        val rows = wire.call(HermesCall.ProfilesList(false))["profiles"].list ?: throw BotFailure.Unsupported
        return rows.mapNotNull { row -> row["name"].text?.takeIf(String::isNotEmpty)?.let { it to (row["is_default"].flag == true) } }
    }
    private suspend fun exactRow(key: String, profile: String, wire: HermesSessionWire): BotJSON? =
        wire.row(key, profile)?.takeIf { it["id"].text == key }

    private suspend fun listRow(found: BotJSON, profile: String, wire: HermesSessionWire): HermesSessionRow {
        val own = (found.element as? JsonObject)?.let(HermesSessionRow::fromJsonOrNull) ?: throw BotFailure.Unsupported
        var top = found
        var hops = 0
        val visited = mutableSetOf(own.id)
        while (true) {
            val parent = top["parent_session_id"].text?.takeIf(String::isNotEmpty) ?: break
            val row = exactRow(parent, profile, wire) ?: break
            if (row["end_reason"].text != "compression") break
            // A cycle or a walk longer than eight cannot prove a root; keep the found key's identity.
            if (hops >= LINEAGE_HOP_LIMIT || !visited.add(parent)) return own
            top = row
            hops++
        }
        if (top == found) return own
        val root = (top.element as? JsonObject)?.let(HermesSessionRow::fromJsonOrNull) ?: throw BotFailure.Unsupported
        return own.copy(title = own.title ?: root.title, startedAt = root.startedAt ?: own.startedAt,
            hidden = root.hidden ?: own.hidden, lineageRootID = root.id)
    }
}
