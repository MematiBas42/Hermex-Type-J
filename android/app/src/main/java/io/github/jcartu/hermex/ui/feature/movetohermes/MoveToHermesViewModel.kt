package io.github.jcartu.hermex.ui.feature.movetohermes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.feature.onboarding.commitMoveToHermes
import io.github.jcartu.hermex.ui.feature.onboarding.nextWebuiServerToMove
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Guided screens from `MoveToHermesView.swift` (#1218). */
enum class MoveWizardStage { CHOOSE_HOST, CHECKING, REVIEW, MOVING, PAUSED, FINISHED, ERROR }

data class MoveToHermesUiState(
    val stage: MoveWizardStage = MoveWizardStage.CHOOSE_HOST,
    val source: ServerAccount? = null,
    val destination: ServerAccount? = null,
    val hostChoices: List<ServerAccount> = emptyList(),
    val checkedChats: Int = 0,
    val totalChats: Int = 0,
    val scan: MoveScanResult? = null,
    val catalog: HermesMoveProfileCatalog? = null,
    val profileChoices: List<MoveProfileChoice> = emptyList(),
    val mappings: List<MoveProfileMapping> = emptyList(),
    val record: MoveRecord? = null,
    val errorMessage: String? = null,
    val isRepeat: Boolean = false,
    val hasNextSource: Boolean = false,
) {
    val sourceName: String get() = displayName(source)
    val destinationName: String get() = displayName(destination)
    val canMove: Boolean
        get() = stage == MoveWizardStage.REVIEW && profileChoices.all { choice ->
            mappings.firstOrNull { it.sourceProfile == choice.sourceProfile }?.let {
                it.destinationProfile != null || it.keepOnWebui
            } == true
        }

    companion object {
        fun displayName(account: ServerAccount?): String {
            if (account == null) return "server"
            return account.displayName.trim().ifEmpty {
                account.urlString.substringAfter("://").substringBefore('/').substringBefore(':')
            }
        }
    }
}

/**
 * Main-thread UI owner for `MoveToHermesModel.swift`. Transport suspend calls hop to
 * `HermesActor.dispatcher`; this ViewModel receives their results back on [viewModelScope].
 */
class MoveToHermesViewModel(
    private val container: AppContainer,
    private val sourceServerId: String,
    private val initialDestinationId: String? = null,
) : ViewModel() {
    private val auth = container.authManager
    private val store = container.moveToHermesStore
    private val sourceAtOpen = auth.servers.value.firstOrNull { it.id == sourceServerId && it.kind == ServerKind.WEBUI }

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<MoveToHermesUiState> = _uiState.asStateFlow()

    private var opened = false
    private var work: Job? = null

    init {
        viewModelScope.launch {
            store.state.collectLatest {
                val current = _uiState.value
                val record = store.recordForSource(sourceServerId) ?: return@collectLatest
                if (current.stage == MoveWizardStage.MOVING || current.stage == MoveWizardStage.PAUSED || current.stage == MoveWizardStage.FINISHED) {
                    _uiState.value = current.copy(record = record)
                }
            }
        }
    }

    fun open() {
        if (opened) return
        opened = true
        val existing = store.recordForSource(sourceServerId)
        if (existing != null) {
            val destination = auth.servers.value.firstOrNull { it.id == existing.destinationServerId }
            _uiState.value = _uiState.value.copy(
                destination = destination,
                record = existing,
                stage = if (existing.stage == MoveStage.FINISHED) MoveWizardStage.FINISHED else MoveWizardStage.PAUSED,
            )
            return
        }
        initialDestinationId?.let { id ->
            auth.servers.value.firstOrNull { it.id == id && it.kind == ServerKind.HERMES }?.let(::chooseDestination)
        }
    }

    fun chooseDestination(account: ServerAccount) {
        if (account.kind != ServerKind.HERMES) return
        scan(account, repeat = store.recordForSource(sourceServerId) != null)
    }

    fun hostAdded(account: ServerAccount) = chooseDestination(account)

    fun retryScan() {
        _uiState.value.destination?.let { scan(it, _uiState.value.isRepeat) }
    }

    fun setProfileDestination(sourceProfile: String, destinationProfile: String?, keepOnWebui: Boolean = false) {
        val state = _uiState.value
        if (state.stage != MoveWizardStage.REVIEW || state.isRepeat) return
        val existing = state.mappings.associateBy { it.sourceProfile }.toMutableMap()
        existing[sourceProfile] = MoveProfileMapping(sourceProfile, destinationProfile, keepOnWebui)
        _uiState.value = state.copy(
            mappings = state.profileChoices.map { choice ->
                existing[choice.sourceProfile] ?: MoveProfileMapping(choice.sourceProfile)
            },
        )
    }

    fun startMove() {
        val state = _uiState.value
        val source = state.source ?: return
        val destination = state.destination ?: return
        val scan = state.scan ?: return
        val catalog = state.catalog ?: return
        if (!state.canMove) return
        work?.cancel()
        work = viewModelScope.launch {
            try {
                val record = auth.commitMoveToHermes(store, source, destination, state.mappings, catalog, scan)
                _uiState.value = _uiState.value.copy(stage = MoveWizardStage.MOVING, record = record, errorMessage = null)
                runRecord(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(stage = MoveWizardStage.ERROR, errorMessage = error.message)
            }
        }
    }

    fun resume() {
        val record = store.recordForSource(sourceServerId) ?: return
        work?.cancel()
        work = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(stage = MoveWizardStage.MOVING, record = record, errorMessage = null)
            runRecord(record)
        }
    }

    fun finishEarly() {
        work?.cancel()
        work = viewModelScope.launch {
            val record = store.finish(sourceServerId, finishedEarly = true)
            _uiState.value = _uiState.value.copy(stage = MoveWizardStage.FINISHED, record = record)
        }
    }

    fun copyMissingAgain() {
        val record = store.recordForSource(sourceServerId) ?: return
        val destination = auth.servers.value.firstOrNull { it.id == record.destinationServerId } ?: run {
            _uiState.value = _uiState.value.copy(
                stage = MoveWizardStage.ERROR,
                errorMessage = "The Hermes destination is no longer configured.",
            )
            return
        }
        scan(destination, repeat = true)
    }

    fun pause() {
        work?.cancel()
        work = null
    }

    fun activateDestination(onDone: () -> Unit) {
        val destination = _uiState.value.destination ?: return
        viewModelScope.launch {
            store.acknowledgeReport(sourceServerId)
            auth.switchActiveServer(destination)
            onDone()
        }
    }

    fun removeSource(onDone: () -> Unit) {
        val source = auth.servers.value.firstOrNull { it.id == sourceServerId } ?: run {
            onDone()
            return
        }
        viewModelScope.launch {
            store.acknowledgeReport(sourceServerId)
            container.cacheStore.chatDraftStore.discardDrafts(source.id)
            auth.removeServer(source)
            onDone()
        }
    }

    fun nextSourceId(): String? = auth.nextWebuiServerToMove(store, excluding = sourceServerId)?.id

    fun moveNext(onNext: (String) -> Unit) {
        val next = nextSourceId() ?: return
        viewModelScope.launch {
            store.acknowledgeReport(sourceServerId)
            onNext(next)
        }
    }

    private fun scan(destination: ServerAccount, repeat: Boolean) {
        val source = sourceAtOpen ?: auth.servers.value.firstOrNull { it.id == sourceServerId }
        if (source == null) {
            _uiState.value = _uiState.value.copy(stage = MoveWizardStage.ERROR, errorMessage = "The webui server was removed.")
            return
        }
        work?.cancel()
        work = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stage = MoveWizardStage.CHECKING,
                source = source,
                destination = destination,
                checkedChats = 0,
                totalChats = 0,
                errorMessage = null,
                isRepeat = repeat,
            )
            val webui = WebuiMoveClient.create(container, source)
            val hermes = try {
                HermesMoveClient(auth.hermesSignIn(destination))
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(stage = MoveWizardStage.ERROR, errorMessage = error.message)
                return@launch
            }
            try {
                hermes.connect()
                val catalog = hermes.profileCatalog()
                val scan = MoveToHermesScanner.scan(webui, hermes, catalog) { located, total ->
                    _uiState.value = _uiState.value.copy(checkedChats = located, totalChats = total)
                }
                if (scan.runningSessionIds.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        stage = MoveWizardStage.ERROR,
                        errorMessage = "${scan.runningSessionIds.size} chats are still running on ${MoveToHermesUiState.displayName(source)}. Wait for them to finish so no reply is lost.",
                    )
                    return@launch
                }
                val webuiProfiles = runCatching { webui.profiles() }.getOrDefault(emptyList())
                val choices = MoveToHermesPlanner.profileChoices(scan, webuiProfiles, catalog)
                val existing = store.recordForSource(sourceServerId)?.profileMappings.orEmpty()
                val mappings = MoveToHermesPlanner.initialMappings(choices, catalog, if (repeat) existing else emptyList())
                _uiState.value = _uiState.value.copy(
                    stage = MoveWizardStage.REVIEW,
                    scan = scan,
                    catalog = catalog,
                    profileChoices = choices,
                    mappings = mappings,
                    isRepeat = repeat,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(stage = MoveWizardStage.ERROR, errorMessage = error.message)
            } finally {
                runCatching { hermes.close() }
            }
        }
    }

    private suspend fun runRecord(record: MoveRecord) {
        val source = sourceAtOpen ?: auth.servers.value.firstOrNull { it.id == sourceServerId }
        val destination = auth.servers.value.firstOrNull { it.id == record.destinationServerId }
        if (source == null || destination == null) {
            val paused = store.setStage(sourceServerId, MoveStage.PAUSED)
            _uiState.value = _uiState.value.copy(
                stage = MoveWizardStage.PAUSED,
                record = paused,
                errorMessage = "A server used by this move is no longer configured.",
            )
            return
        }
        val connection = try {
            auth.hermesSignIn(destination)
        } catch (error: Throwable) {
            val paused = store.setStage(sourceServerId, MoveStage.PAUSED)
            _uiState.value = _uiState.value.copy(stage = MoveWizardStage.PAUSED, record = paused, errorMessage = error.message)
            return
        }
        val runner = MoveToHermesRunner(
            sourceServerId = sourceServerId,
            destinationServerId = destination.id,
            store = store,
            webui = WebuiMoveClient.create(container, source),
            hermes = HermesMoveClient(connection),
            drafts = container.cacheStore.chatDraftStore,
        )
        try {
            val result = runner.run()
            _uiState.value = _uiState.value.copy(
                stage = if (result.record.stage == MoveStage.FINISHED) MoveWizardStage.FINISHED else MoveWizardStage.PAUSED,
                destination = destination,
                record = result.record,
                errorMessage = result.errorMessage,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun initialState(): MoveToHermesUiState {
        val record = store.recordForSource(sourceServerId)
        val destination = record?.let { held -> auth.servers.value.firstOrNull { it.id == held.destinationServerId } }
        return MoveToHermesUiState(
            stage = when (record?.stage) {
                MoveStage.FINISHED -> MoveWizardStage.FINISHED
                MoveStage.RUNNING, MoveStage.PAUSED -> MoveWizardStage.PAUSED
                null -> MoveWizardStage.CHOOSE_HOST
            },
            source = sourceAtOpen,
            destination = destination,
            hostChoices = auth.servers.value.filter { it.kind == ServerKind.HERMES },
            record = record,
            hasNextSource = auth.nextWebuiServerToMove(store, excluding = sourceServerId) != null,
        )
    }

    override fun onCleared() {
        pause()
        super.onCleared()
    }
}
