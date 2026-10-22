package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance
import io.github.jcartu.hermex.net.hermes.BotProfileName
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One create or duplicate operation, bound for its lifetime to one configured server and one
 * Bot connection. Port of `Features/Bots/BotCreator.swift`.
 *
 * The first deliberate create freezes every value. Profile, look and canonical-chat writes are
 * then independently tracked; an uncertain step is reconciled from the host before a deliberate
 * retry. Nothing is retried because the socket dropped or the app entered the background.
 */
class BotCreator(
    val server: String,
    val connection: BotConnection,
    roster: List<BotProfile>,
    private val records: BotInbox.ConnectionRecords,
    private val makeWire: (BotConnection) -> BotTransport,
    private val scope: CoroutineScope,
    val source: BotProfile? = null,
    private val onCreated: (String) -> Unit = {},
) {
    enum class Step(val title: String) {
        PROFILE("Profile"),
        LOOK("Look"),
        CHAT("Bot Chat"),
    }

    sealed class Outcome {
        object Done : Outcome()
        data class Failed(val message: String) : Outcome()
        object Uncertain : Outcome()
    }

    enum class Phase { EDITING, CREATING, CREATED }

    data class Draft(
        val title: String = "",
        val role: String = "",
        val appearance: BotProfileAppearance = BotProfileAppearance(title = ""),
        val model: ModelCatalogOption? = null,
        val sharesCredentials: Boolean = true,
        val instructions: String = "",
        val skipsBundledSkills: Boolean = false,
    )

    data class State(
        val draft: Draft,
        val name: String,
        val source: BotProfile? = null,
        val phase: Phase = Phase.EDITING,
        val isLoading: Boolean = false,
        val outcomes: Map<Step, Outcome> = emptyMap(),
        val modelGroups: List<ModelCatalogGroup> = emptyList(),
        val note: String? = null,
        val nameIsTaken: Boolean = false,
        val hasStarted: Boolean = false,
        val holdsConnection: Boolean = false,
    ) {
        val isDuplicate: Boolean = source != null

        val nameProblem: String? = when {
            hasStarted || draft.title.isBlank() -> null
            name.isEmpty() || !BotProfileName.isValid(name) -> {
                val shown = if (name.isEmpty()) draft.title else name
                "Use letters or numbers; “$shown” is not allowed as a Profile name."
            }
            nameIsTaken -> "A bot named “$name” already exists on this Hermes."
            else -> null
        }

        val canCreate: Boolean = phase == Phase.EDITING && !isLoading && BotProfileName.isValid(name) &&
            (hasStarted || !nameIsTaken)

        val needsAttention: Boolean = note != null || outcomes.values.any { it !is Outcome.Done }
    }

    private data class FrozenDraft(
        val name: String,
        val title: String,
        val role: String,
        val appearance: BotProfileAppearance,
        val model: ModelCatalogOption?,
        val sharesCredentials: Boolean,
        val instructions: String,
        val skipsBundledSkills: Boolean,
        val sourceID: String?,
    )

    private val taken = roster.mapTo(HashSet()) { it.id }
    private var frozen: FrozenDraft? = null
    @Volatile private var generation = 0
    @Volatile private var wire: BotTransport? = null
    private var loadJob: Job? = null
    private var createJob: Job? = null

    private val initialDraft: Draft = if (source != null) {
        val title = "${source.name} copy"
        Draft(
            title = title,
            role = source.description.orEmpty(),
            appearance = BotProfileAppearance.of(source).copy(title = title, imageKind = "shape"),
        )
    } else {
        Draft(
            appearance = BotProfileAppearance(
                title = "",
                shape = BotAvatarShape.CIRCLE.rawValue,
                color = "#38bdf8",
                custom = true,
                imageKind = "shape",
            ),
        )
    }

    private val _state = MutableStateFlow(
        State(
            draft = initialDraft,
            name = BotProfileName.slug(initialDraft.title),
            source = source,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private inline fun update(block: (State) -> State) {
        _state.update { decorate(block(it)) }
    }

    private fun decorate(value: State): State {
        val name = frozen?.name ?: BotProfileName.slug(value.draft.title)
        val started = frozen != null || value.outcomes.isNotEmpty()
        return value.copy(
            name = name,
            source = source,
            nameIsTaken = name in taken,
            hasStarted = started,
        )
    }

    private fun edit(block: (Draft) -> Draft) {
        update { value ->
            if (value.phase != Phase.EDITING || value.hasStarted) value else value.copy(draft = block(value.draft))
        }
    }

    fun setTitle(value: String) = edit { it.copy(title = value, appearance = it.appearance.copy(title = value)) }
    fun setRole(value: String) = edit { it.copy(role = value) }
    fun setModel(value: ModelCatalogOption?) = edit { it.copy(model = value) }
    fun setSharesCredentials(value: Boolean) = edit { it.copy(sharesCredentials = value) }
    fun setInstructions(value: String) = edit { it.copy(instructions = value) }
    fun setSkipsBundledSkills(value: Boolean) = edit { it.copy(skipsBundledSkills = value) }

    fun setShape(shape: BotAvatarShape) = edit {
        it.copy(
            appearance = it.appearance.copy(
                shape = shape.rawValue,
                custom = true,
                imageKind = "shape",
            ),
        )
    }

    fun setColor(hex: String) = edit {
        val shape = BotAvatarShape.fromRawValue(it.appearance.shape)?.rawValue ?: BotAvatarShape.CIRCLE.rawValue
        it.copy(
            appearance = it.appearance.copy(
                shape = shape,
                color = hex,
                custom = true,
                imageKind = "shape",
            ),
        )
    }

    fun setExpression(expression: BotAvatarExpression) = edit {
        it.copy(
            appearance = it.appearance.copy(
                expression = expression.takeUnless { value -> value == BotAvatarExpression.NEUTRAL }?.rawValue,
            ),
        )
    }

    /** Opens the socket and reads the direct-Hermes model inventory. Draft fields never reload. */
    suspend fun load() {
        generation += 1
        val owner = generation
        val previous = wire
        wire = null
        previous?.close()
        val client = makeWire(connection)
        wire = client
        update { it.copy(isLoading = true, holdsConnection = true) }
        watchDisconnect(client, owner)
        try {
            ensureCurrentConnection()
            client.connect()
            ensureOwner(owner, client)
            val groups = runCatching {
                val reply = client.call(HermesCall.ConfiguredModelOptions, validateDispatch(owner))
                ensureOwner(owner, client)
                HermesModelCatalog(reply).groups
            }.getOrDefault(emptyList())
            ensureOwner(owner, client)
            update { it.copy(modelGroups = groups) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (generation == owner && wire === client) {
                wire = null
                update { it.copy(holdsConnection = false) }
                client.close()
            }
        } finally {
            if (generation == owner) update { it.copy(isLoading = false) }
        }
    }

    fun loadAsync(): Job {
        loadJob?.cancel()
        return scope.launch { load() }.also { loadJob = it }
    }

    /**
     * Invalidates every late completion. If a write was in flight, each step without a known
     * result becomes uncertain; returning to the app does not resume it.
     */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        createJob?.cancel()
        createJob = null
        generation += 1
        val client = wire
        wire = null
        update { value ->
            if (value.phase != Phase.CREATING) {
                value.copy(isLoading = false, holdsConnection = false)
            } else {
                val outcomes = value.outcomes.toMutableMap()
                for (step in Step.entries) outcomes.putIfAbsent(step, Outcome.Uncertain)
                value.copy(
                    phase = Phase.EDITING,
                    isLoading = false,
                    outcomes = outcomes,
                    holdsConnection = false,
                )
            }
        }
        if (client != null) scope.launch(NonCancellable) { runCatching { client.close() } }
    }

    /** Runs only unfinished steps. A second invocation cannot overlap the first. */
    suspend fun create() {
        if (!current.canCreate) return
        val snapshot = frozen ?: current.draft.let { draft ->
            FrozenDraft(
                name = BotProfileName.slug(draft.title),
                title = draft.title,
                role = draft.role,
                appearance = draft.appearance.copy(title = draft.title),
                model = draft.model,
                sharesCredentials = draft.sharesCredentials,
                instructions = draft.instructions,
                skipsBundledSkills = draft.skipsBundledSkills,
                sourceID = source?.id,
            )
        }.also { frozen = it }
        update { value ->
            value.copy(
                phase = Phase.CREATING,
                note = null,
                outcomes = value.outcomes.filterValues { it is Outcome.Done || it is Outcome.Uncertain },
            )
        }

        generation += 1
        val owner = generation
        var activeStep = Step.PROFILE
        val needsConnect = wire == null
        val client = wire ?: makeWire(connection)
        wire = client
        update { it.copy(holdsConnection = true) }
        watchDisconnect(client, owner)
        try {
            ensureCurrentConnection()
            if (needsConnect) {
                client.connect()
                ensureOwner(owner, client)
            }
            createProfile(client, owner, snapshot)
            activeStep = Step.LOOK
            configureLook(client, owner, snapshot)
            activeStep = Step.CHAT
            ensureChat(client, owner, snapshot.name)
            ensureOwner(owner, client)
            update { it.copy(phase = Phase.CREATED) }
            onCreated(snapshot.name)
        } catch (cancelled: CancellationException) {
            if (generation == owner) {
                markUnknown(activeStep, cancelled)
                update { it.copy(phase = Phase.EDITING) }
            }
            throw cancelled
        } catch (_: CreatorStop) {
            if (generation == owner) update { it.copy(phase = Phase.EDITING) }
        } catch (error: Throwable) {
            if (generation != owner) return
            markUnknown(activeStep, error)
            update { it.copy(phase = Phase.EDITING, holdsConnection = false) }
            wire = null
            client.close()
        }
    }

    fun createAsync(): Job {
        if (createJob?.isActive == true) return createJob!!
        return scope.launch { create() }.also { createJob = it }
    }

    private fun markUnknown(step: Step, error: Throwable) {
        update { value ->
            if (value.outcomes[step] is Outcome.Done || value.outcomes[step] is Outcome.Failed) return@update value
            val outcome = if (error === BotFailure.Transport || error is CancellationException) {
                Outcome.Uncertain
            } else {
                Outcome.Failed(error.message ?: "Failed")
            }
            value.copy(outcomes = value.outcomes + (step to outcome))
        }
    }

    private suspend fun createProfile(client: BotTransport, owner: Int, draft: FrozenDraft) {
        when (current.outcomes[Step.PROFILE]) {
            is Outcome.Done -> return
            is Outcome.Uncertain -> {
                val roster = client.call(HermesCall.ProfilesList(includeSessions = false), validateDispatch(owner))
                ensureOwner(owner, client)
                val rows = roster["profiles"].list ?: throw BotFailure.Unsupported
                if (rows.any { it["name"].text == draft.name }) {
                    taken += draft.name
                    update { it.copy(outcomes = it.outcomes + (Step.PROFILE to Outcome.Done)) }
                    return
                }
            }
            else -> Unit
        }

        val role = draft.role.trim().takeIf(String::isNotEmpty)
        val instructions = draft.instructions.takeIf { it.isNotBlank() && draft.sourceID == null }
        val model = draft.model?.providerID?.takeIf(String::isNotEmpty)?.let { provider ->
            HermesCall.Model(id = draft.model.id, provider = provider)
        }
        val profile = HermesCall.NewProfile(
            name = draft.name,
            description = role,
            cloneFrom = draft.sourceID,
            soul = instructions,
            model = model,
            skipsBundledSkills = draft.sourceID == null && draft.skipsBundledSkills,
            sharesCredentials = draft.sharesCredentials,
        )
        try {
            val reply = client.call(HermesCall.ProfilesCreate(profile), validateDispatch(owner))
            ensureOwner(owner, client)
            if (reply["ok"].flag != true) throw BotFailure.Unsupported
            taken += draft.name
            update { value ->
                val hasModel = reply["model_set"].flag == true || reply["mirrored"]["model_inherited"].flag == true
                value.copy(
                    outcomes = value.outcomes + (Step.PROFILE to Outcome.Done),
                    note = if (hasModel) value.note else NO_MODEL_NOTE,
                )
            }
        } catch (rejected: BotFailure.Rejected) {
            if (rejected.code != 4062) throw rejected
            update {
                it.copy(
                    outcomes = it.outcomes +
                        (Step.PROFILE to Outcome.Failed("Hermes refused this name. It may already exist on the host.")),
                )
            }
            throw CreatorStop()
        }
    }

    /** A cosmetic failure is retained but never prevents canonical-chat setup. */
    private suspend fun configureLook(client: BotTransport, owner: Int, draft: FrozenDraft) {
        if (current.outcomes[Step.LOOK] is Outcome.Done) return
        val fields = draft.appearance.copy(title = draft.title).merging(emptyMap())
        try {
            if (current.outcomes[Step.LOOK] is Outcome.Uncertain && lookAlreadyApplied(client, owner, draft.name, fields)) {
                update { it.copy(outcomes = it.outcomes + (Step.LOOK to Outcome.Done)) }
                return
            }
            val changes = HermesCall.ProfileChanges(
                name = draft.name,
                look = HermesCall.Look(fields = fields, revision = 0),
            )
            val reply = client.call(HermesCall.ProfilesConfigure(changes), validateDispatch(owner))
            ensureOwner(owner, client)
            val outcome = if (reply["applied"]["ui_meta"].flag == true) {
                Outcome.Done
            } else {
                Outcome.Failed(LOOK_FAILURE)
            }
            update { it.copy(outcomes = it.outcomes + (Step.LOOK to outcome)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation != owner) return
            val outcome = if (error === BotFailure.Transport) Outcome.Uncertain else Outcome.Failed(LOOK_FAILURE)
            update { it.copy(outcomes = it.outcomes + (Step.LOOK to outcome)) }
        }
    }

    private suspend fun lookAlreadyApplied(
        client: BotTransport,
        owner: Int,
        name: String,
        expected: Map<String, BotJSON>,
    ): Boolean {
        val roster = client.call(HermesCall.ProfilesList(includeSessions = false), validateDispatch(owner))
        ensureOwner(owner, client)
        val row = roster["profiles"].list?.firstOrNull { it["name"].text == name } ?: return false
        val actual = row["ui_meta"]["hermes-bots"].fields ?: emptyMap()
        return APPEARANCE_KEYS.all { key -> actual[key] == expected[key] }
    }

    /** Exact-title lookup first; only a confirmed empty result is allowed to mint. */
    private suspend fun ensureChat(client: BotTransport, owner: Int, profile: String) {
        if (current.outcomes[Step.CHAT] is Outcome.Done) return
        if (findChat(client, owner, profile)) {
            update { it.copy(outcomes = it.outcomes + (Step.CHAT to Outcome.Done)) }
            return
        }
        client.keepPlatformNote(profile)
        val created = client.call(HermesCall.SessionCreate(profile), validateDispatch(owner))
        ensureOwner(owner, client)
        val runtime = created["session_id"].text?.takeIf(String::isNotEmpty) ?: throw BotFailure.Unsupported
        try {
            client.call(HermesCall.SessionTitle(runtime), validateDispatch(owner))
            ensureOwner(owner, client)
        } catch (rejected: BotFailure.Rejected) {
            if (rejected.code != 4022) throw rejected
            ensureOwner(owner, client)
            if (!findChat(client, owner, profile)) throw BotFailure.Unsupported
        }
        update { it.copy(outcomes = it.outcomes + (Step.CHAT to Outcome.Done)) }
    }

    private suspend fun findChat(client: BotTransport, owner: Int, profile: String): Boolean {
        val reply = client.call(HermesCall.SessionList(profile), validateDispatch(owner))
        ensureOwner(owner, client)
        val sessions = reply["sessions"].list ?: throw BotFailure.Unsupported
        return sessions.isNotEmpty()
    }

    private fun validateDispatch(owner: Int): () -> Unit = {
        if (generation != owner) throw BotFailure.Stale
        ensureCurrentConnection()
    }

    private suspend fun ensureOwner(owner: Int, client: BotTransport) {
        currentCoroutineContext().ensureActive()
        if (generation != owner || wire !== client) throw BotFailure.Stale
        ensureCurrentConnection()
    }

    private fun ensureCurrentConnection() {
        if (records.load(server)?.id != connection.id) throw BotFailure.Stale
    }

    /** Transport callbacks arrive on HermesActor; state mutation always hops to the injected Main scope. */
    private fun watchDisconnect(client: BotTransport, owner: Int) {
        client.onDisconnect = { _ ->
            scope.launch {
                if (generation == owner && wire === client) {
                    wire = null
                    update { it.copy(holdsConnection = false) }
                }
            }
        }
    }

    private class CreatorStop : Exception()

    companion object {
        private const val LOOK_FAILURE = "The look was not saved. Edit the bot to set it."
        private const val NO_MODEL_NOTE =
            "No model is set for this bot yet. Pick one in Edit or in Hermes Desktop before chatting."
        private val APPEARANCE_KEYS = setOf("title", "shape", "color", "custom", "imageKind", "expression")
    }
}
