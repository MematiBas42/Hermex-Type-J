package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.feature.movetohermes.HermesMoveProfileCatalog
import io.github.jcartu.hermex.ui.feature.movetohermes.MoveProfileMapping
import io.github.jcartu.hermex.ui.feature.movetohermes.MoveRecord
import io.github.jcartu.hermex.ui.feature.movetohermes.MoveScanResult
import io.github.jcartu.hermex.ui.feature.movetohermes.MoveToHermesPlanner
import io.github.jcartu.hermex.ui.feature.movetohermes.MoveToHermesStore

/** Port of `Auth/AuthManager+MoveToHermes.swift` (#1215, #1267). */
sealed class MoveToHermesCommitException(message: String) : Exception(message) {
    data object SourceNotWebui : MoveToHermesCommitException("The source is not a configured webui server.")
    data object SameAddress : MoveToHermesCommitException("A webui server is saved at this Hermes address.")
    data object DestinationNotHermes : MoveToHermesCommitException("The destination is not a Hermes server.")
    data class SourceAlreadyMoved(val destinationServerId: String) :
        MoveToHermesCommitException("This webui server already moved to $destinationServerId.")
    data object InvalidMapping : MoveToHermesCommitException("Choose where every Profile should go.")
}

/**
 * Commit point: persists the frozen Review before any import. The destination has already been
 * added by the shared Hermes connect form with `activate = false`, so this never switches servers.
 */
suspend fun AuthManager.commitMoveToHermes(
    store: MoveToHermesStore,
    source: ServerAccount,
    destination: ServerAccount,
    mappings: List<MoveProfileMapping>,
    catalog: HermesMoveProfileCatalog,
    scan: MoveScanResult,
): MoveRecord {
    val configured = servers.value
    if (configured.none { it.id == source.id && it.kind == ServerKind.WEBUI }) {
        throw MoveToHermesCommitException.SourceNotWebui
    }
    if (source.id == destination.id) throw MoveToHermesCommitException.SameAddress
    if (configured.none { it.id == destination.id && it.kind == ServerKind.HERMES }) {
        throw MoveToHermesCommitException.DestinationNotHermes
    }
    store.state.value.moves.firstOrNull { it.sourceServerId == source.id && it.destinationServerId != destination.id }?.let {
        throw MoveToHermesCommitException.SourceAlreadyMoved(it.destinationServerId)
    }
    val sourceProfiles = MoveToHermesPlanner.profileChoices(scan, emptyList(), catalog).map { it.sourceProfile }.toSet()
    val mappingSources = mappings.map { it.sourceProfile }
    val everyChoiceIsValid = mappings.all { mapping ->
        mapping.keepOnWebui.xor(mapping.destinationProfile != null) &&
            (mapping.destinationProfile == null || mapping.destinationProfile in catalog.profiles)
    }
    if (
        mappings.isEmpty() || mappingSources.toSet().size != mappingSources.size ||
        !mappingSources.containsAll(sourceProfiles) || !everyChoiceIsValid
    ) {
        throw MoveToHermesCommitException.InvalidMapping
    }
    return store.beginMove(
        sourceServerId = source.id,
        destinationServerId = destination.id,
        destinationCreatedAt = destination.createdAt,
        profileMappings = mappings,
        hermesProfiles = catalog.profiles,
        items = MoveToHermesPlanner.progressItems(scan, mappings),
    )
}

fun AuthManager.moveRecord(store: MoveToHermesStore, sourceServerId: String): MoveRecord? =
    store.recordForSource(sourceServerId)

/** The invite is per webui server and disappears after dismissal or the first committed move. */
fun AuthManager.showsMoveToHermesInvite(store: MoveToHermesStore, sourceServerId: String): Boolean {
    val source = servers.value.firstOrNull { it.id == sourceServerId } ?: return false
    return source.kind == ServerKind.WEBUI &&
        store.promptState(sourceServerId) == io.github.jcartu.hermex.ui.feature.movetohermes.MovePromptState.ASKED &&
        store.recordForSource(sourceServerId) == null
}

fun AuthManager.nextWebuiServerToMove(store: MoveToHermesStore, excluding: String? = null): ServerAccount? =
    servers.value.firstOrNull { account ->
        account.kind == ServerKind.WEBUI && account.id != excluding && store.recordForSource(account.id) == null
    }
