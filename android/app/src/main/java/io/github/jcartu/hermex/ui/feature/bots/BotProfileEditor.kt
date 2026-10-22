package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance
import io.github.jcartu.hermex.model.hermes.BotProfileCapability
import io.github.jcartu.hermex.model.hermes.BotProfileDetails
import io.github.jcartu.hermex.net.hermes.BotAttachmentUpload
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * One Profile editor, permanently scoped to a configured server, connection UUID and Profile.
 * Port of `Features/Bots/BotProfileEditor.swift`.
 *
 * One explicit Save sends only dirty sections. Each acknowledged section advances its own
 * baseline; model confirmation, revision conflicts and failures remain dirty. Avatar bytes are
 * sent only after the appearance metadata that describes them was acknowledged. Writes are
 * never retried automatically.
 */
class BotProfileEditor(
    val server: String,
    val connection: BotConnection,
    profile: BotProfile,
    avatar: Bitmap?,
    private val records: BotInbox.ConnectionRecords,
    private val avatarStore: BotAvatarStore,
    private val makeWire: (BotConnection) -> BotTransport,
    private val scope: CoroutineScope,
    private val photoDecoder: BotProfilePhotoDecoder = BotProfilePhotoDecoder.Android,
    private val onSaved: () -> Unit = {},
) {
    sealed class LoadState {
        object Idle : LoadState()
        object Loading : LoadState()
        object Loaded : LoadState()
        data class Failed(val message: String) : LoadState()
    }

    enum class Field(val title: String) {
        APPEARANCE("Appearance"),
        DESCRIPTION("Description"),
        INSTRUCTIONS("Instructions"),
        MODEL("Default Model"),
        SKILLS("Skills"),
        TOOLSETS("Toolsets"),
        MCP_SERVERS("MCP servers"),
        AVATAR("Avatar"),
    }

    sealed class Outcome {
        object Saved : Outcome()
        data class Failed(val message: String) : Outcome()
        object Conflict : Outcome()
        object ConfirmationRequired : Outcome()
    }

    data class Draft(
        val appearance: BotProfileAppearance,
        val description: String = "",
        val instructions: String = "",
        val model: ModelCatalogOption? = null,
        val skills: List<BotProfileCapability> = emptyList(),
        val toolsets: List<BotProfileCapability> = emptyList(),
        val mcpServers: List<BotProfileCapability> = emptyList(),
    )

    data class ModelConfirmation(
        val message: String,
        val id: String = UUID.randomUUID().toString(),
    )

    data class State(
        val profile: BotProfile,
        val loadState: LoadState,
        val draft: Draft,
        val baseline: Draft = draft,
        val modelGroups: List<ModelCatalogGroup> = emptyList(),
        val outcomes: Map<Field, Outcome> = emptyMap(),
        val confirmation: ModelConfirmation? = null,
        val isSaving: Boolean = false,
        val avatar: Bitmap? = null,
        val avatarChanged: Boolean = false,
        val holdsConnection: Boolean = false,
    ) {
        val dirtyFields: Set<Field> = buildSet {
            if (draft.appearance != baseline.appearance) add(Field.APPEARANCE)
            if (draft.description != baseline.description) add(Field.DESCRIPTION)
            if (draft.instructions != baseline.instructions) add(Field.INSTRUCTIONS)
            if (draft.model != baseline.model) add(Field.MODEL)
            if (draft.skills != baseline.skills) add(Field.SKILLS)
            if (draft.toolsets != baseline.toolsets) add(Field.TOOLSETS)
            if (draft.mcpServers != baseline.mcpServers) add(Field.MCP_SERVERS)
            if (avatarChanged) add(Field.AVATAR)
        }

        val canSave: Boolean = loadState is LoadState.Loaded && !isSaving && dirtyFields.isNotEmpty()
    }

    private sealed class AvatarChange {
        object Unchanged : AvatarChange()
        data class Replace(val dataURL: String) : AvatarChange()
        object Remove : AvatarChange()
    }

    private var receivedLook: Map<String, BotJSON> = profile.look
    private var lookRevision: Int? = profile.lookRevision
    private var avatarChange: AvatarChange = AvatarChange.Unchanged
    @Volatile private var generation = 0
    @Volatile private var wire: BotTransport? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    private val emptyDraft = Draft(appearance = BotProfileAppearance.of(profile))
    private val _state = MutableStateFlow(
        State(
            profile = profile,
            loadState = LoadState.Idle,
            draft = emptyDraft,
            avatar = avatar,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private inline fun update(block: (State) -> State) = _state.update(block)

    /** Connects and reads `profiles.describe`; a failed model inventory is an empty picker. */
    suspend fun load() {
        if (current.loadState is LoadState.Loaded && current.dirtyFields.isNotEmpty()) return
        generation += 1
        val owner = generation
        val previous = wire
        wire = null
        previous?.close()
        val client = makeWire(connection)
        wire = client
        update {
            it.copy(
                loadState = LoadState.Loading,
                outcomes = emptyMap(),
                confirmation = null,
                holdsConnection = true,
            )
        }
        watchDisconnect(client, owner)
        try {
            ensureCurrentConnection()
            client.connect()
            ensureOwner(owner, client)
            val payload = client.call(HermesCall.ProfilesDescribe(current.profile.id), validateDispatch(owner))
            ensureOwner(owner, client)
            val details = BotProfileDetails.from(payload, expectedName = current.profile.id)
            if (details.name != current.profile.id) throw BotFailure.WrongIdentity
            val groups = try {
                val reply = client.call(HermesCall.ConfiguredModelOptions, validateDispatch(owner))
                ensureOwner(owner, client)
                HermesModelCatalog(reply).groups
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ensureOwner(owner, client)
                emptyList()
            }
            val loaded = Draft(
                appearance = BotProfileAppearance.fromLook(receivedLook, current.profile.name),
                description = details.description,
                instructions = details.instructions,
                model = details.model,
                skills = details.skills,
                toolsets = details.toolsets,
                mcpServers = details.mcpServers,
            )
            avatarChange = AvatarChange.Unchanged
            update {
                it.copy(
                    loadState = LoadState.Loaded,
                    draft = loaded,
                    baseline = loaded,
                    modelGroups = groups,
                    avatarChanged = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation == owner && wire === client) {
                wire = null
                update {
                    it.copy(
                        loadState = LoadState.Failed(error.userMessage()),
                        holdsConnection = false,
                    )
                }
                client.close()
            }
        }
    }

    fun loadAsync(): Job {
        loadJob?.cancel()
        return scope.launch { load() }.also { loadJob = it }
    }

    /** Invalidates late replies without changing any draft field. */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        saveJob?.cancel()
        saveJob = null
        generation += 1
        val client = wire
        wire = null
        update {
            it.copy(
                loadState = if (it.loadState is LoadState.Loading) LoadState.Idle else it.loadState,
                isSaving = false,
                confirmation = null,
                holdsConnection = false,
            )
        }
        if (client != null) scope.launch(NonCancellable) { runCatching { client.close() } }
    }

    /**
     * Backgrounding preserves every draft. Dirty sections whose write was in flight become
     * visibly uncertain; foregrounding never resends them.
     */
    fun suspendForBackground() {
        loadJob?.cancel()
        loadJob = null
        saveJob?.cancel()
        saveJob = null
        val fields = current.dirtyFields
        val wasSaving = current.isSaving
        generation += 1
        val client = wire
        wire = null
        update { value ->
            var outcomes = value.outcomes
            if (wasSaving) {
                val mutable = outcomes.toMutableMap()
                for (field in fields) mutable.putIfAbsent(field, Outcome.Failed(OUTCOME_UNCERTAIN))
                outcomes = mutable
            }
            value.copy(
                loadState = if (value.loadState is LoadState.Loading) LoadState.Idle else value.loadState,
                isSaving = false,
                confirmation = null,
                outcomes = outcomes,
                holdsConnection = false,
            )
        }
        if (client != null) scope.launch(NonCancellable) { runCatching { client.close() } }
    }

    private fun edit(field: Field, block: (Draft) -> Draft) {
        update { value ->
            if (value.loadState !is LoadState.Loaded || value.isSaving) return@update value
            value.copy(
                draft = block(value.draft),
                outcomes = value.outcomes - field,
                confirmation = if (field == Field.MODEL) null else value.confirmation,
            )
        }
    }

    fun setTitle(value: String) = edit(Field.APPEARANCE) {
        it.copy(appearance = it.appearance.copy(title = value))
    }

    fun setDescription(value: String) = edit(Field.DESCRIPTION) { it.copy(description = value) }
    fun setInstructions(value: String) = edit(Field.INSTRUCTIONS) { it.copy(instructions = value) }
    fun setModel(value: ModelCatalogOption) = edit(Field.MODEL) { it.copy(model = value) }

    fun setShape(shape: BotAvatarShape) {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        avatarChange = AvatarChange.Remove
        update { value ->
            value.copy(
                draft = value.draft.copy(
                    appearance = value.draft.appearance.copy(
                        shape = shape.rawValue,
                        custom = true,
                        imageKind = "shape",
                    ),
                ),
                avatar = null,
                avatarChanged = true,
                outcomes = value.outcomes - setOf(Field.APPEARANCE, Field.AVATAR),
            )
        }
    }

    fun setColor(hex: String) {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        avatarChange = AvatarChange.Remove
        update { value ->
            val shape = BotAvatarShape.fromRawValue(value.draft.appearance.shape)?.rawValue
                ?: BotAvatarShape.CIRCLE.rawValue
            value.copy(
                draft = value.draft.copy(
                    appearance = value.draft.appearance.copy(
                        shape = shape,
                        color = hex,
                        custom = true,
                        imageKind = "shape",
                    ),
                ),
                avatar = null,
                avatarChanged = true,
                outcomes = value.outcomes - setOf(Field.APPEARANCE, Field.AVATAR),
            )
        }
    }

    /** Changes only the drawn eyes; a selected photo remains selected. */
    fun setExpression(expression: BotAvatarExpression) = edit(Field.APPEARANCE) {
        it.copy(
            appearance = it.appearance.copy(
                expression = expression.takeUnless { value -> value == BotAvatarExpression.NEUTRAL }?.rawValue,
            ),
        )
    }

    fun resetAppearance() {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        avatarChange = AvatarChange.Remove
        update { value ->
            value.copy(
                draft = value.draft.copy(
                    appearance = value.draft.appearance.copy(
                        shape = null,
                        color = null,
                        expression = null,
                        custom = false,
                        imageKind = null,
                    ),
                ),
                avatar = null,
                avatarChanged = true,
                outcomes = value.outcomes - setOf(Field.APPEARANCE, Field.AVATAR),
            )
        }
    }

    /** Decodes off Main, normalizes orientation and dimensions, and bounds the decoded asset to 2 MB. */
    suspend fun selectAvatar(data: ByteArray) {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        if (data.isEmpty() || data.size > MAX_PHOTO_SOURCE_BYTES) throw BotProfileEditorImageException()
        val photo = withContext(Dispatchers.Default) { photoDecoder.normalize(data) }
            ?: throw BotProfileEditorImageException()
        if (photo.bytes.isEmpty() || photo.bytes.size > MAX_PHOTO_ASSET_BYTES ||
            photo.mimeType !in setOf("image/png", "image/jpeg")
        ) {
            throw BotProfileEditorImageException()
        }
        val dataURL = "data:${photo.mimeType};base64," + Base64.getEncoder().encodeToString(photo.bytes)
        avatarChange = AvatarChange.Replace(dataURL)
        update { value ->
            value.copy(
                draft = value.draft.copy(
                    appearance = value.draft.appearance.copy(custom = true, imageKind = "photo"),
                ),
                avatar = photo.bitmap,
                avatarChanged = true,
                outcomes = value.outcomes - setOf(Field.APPEARANCE, Field.AVATAR),
            )
        }
    }

    fun removeAvatar() {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        avatarChange = AvatarChange.Remove
        update { value ->
            value.copy(
                draft = value.draft.copy(
                    appearance = value.draft.appearance.copy(custom = true, imageKind = "shape"),
                ),
                avatar = null,
                avatarChanged = true,
                outcomes = value.outcomes - setOf(Field.APPEARANCE, Field.AVATAR),
            )
        }
    }

    fun setEnabled(enabled: Boolean, field: Field, id: String) {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        update { value ->
            val source = when (field) {
                Field.SKILLS -> value.draft.skills
                Field.TOOLSETS -> value.draft.toolsets
                Field.MCP_SERVERS -> value.draft.mcpServers
                else -> return@update value
            }
            if (field == Field.TOOLSETS && !enabled && source.count { it.enabled } <= 1) return@update value
            val index = source.indexOfFirst { it.id == id }
            if (index < 0 || source[index].enabled == enabled) return@update value
            val changed = source.toMutableList().also { list -> list[index] = list[index].copy(enabled = enabled) }
            val draft = when (field) {
                Field.SKILLS -> value.draft.copy(skills = changed)
                Field.TOOLSETS -> value.draft.copy(toolsets = changed)
                Field.MCP_SERVERS -> value.draft.copy(mcpServers = changed)
                else -> value.draft
            }
            value.copy(draft = draft, outcomes = value.outcomes - field)
        }
    }

    suspend fun save() = save(current.dirtyFields, confirmedModel = false)

    fun saveAsync(): Job {
        if (saveJob?.isActive == true) return saveJob!!
        return scope.launch { save() }.also { saveJob = it }
    }

    suspend fun confirmModelChange() {
        update { it.copy(confirmation = null) }
        save(setOf(Field.MODEL), confirmedModel = true)
    }

    fun confirmModelChangeAsync(): Job = scope.launch { confirmModelChange() }

    fun declineModelChange() {
        update {
            it.copy(
                confirmation = null,
                outcomes = it.outcomes +
                    (Field.MODEL to Outcome.Failed("Model confirmation was declined. Your selection is still unsaved.")),
            )
        }
    }

    /** Replaces only appearance after a CAS conflict; every other unsaved section survives. */
    suspend fun reloadAppearance() {
        if (current.loadState !is LoadState.Loaded || current.isSaving) return
        generation += 1
        val owner = generation
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
            val roster = client.call(HermesCall.ProfilesList(includeSessions = false), validateDispatch(owner))
            ensureOwner(owner, client)
            val row = roster["profiles"].list?.firstOrNull { it["name"].text == current.profile.id }
                ?: throw BotFailure.WrongIdentity
            val fresh = BotProfile.fromJsonOrNull(row) ?: throw BotFailure.WrongIdentity
            receivedLook = fresh.look
            lookRevision = fresh.lookRevision
            val appearance = BotProfileAppearance.of(fresh)
            avatarChange = AvatarChange.Unchanged
            update { value ->
                value.copy(
                    profile = fresh,
                    draft = value.draft.copy(appearance = appearance),
                    baseline = value.baseline.copy(appearance = appearance),
                    avatarChanged = false,
                    outcomes = value.outcomes - setOf(Field.APPEARANCE, Field.AVATAR),
                )
            }
            val image = if (fresh.hasAvatar) {
                val reply = client.call(HermesCall.ProfilesGetAsset(fresh.id), validateDispatch(owner))
                ensureOwner(owner, client)
                withContext(Dispatchers.Default) { BotAvatarStore.decode(reply, photo = fresh.isPhoto) }
            } else {
                null
            }
            ensureOwner(owner, client)
            avatarStore.setImage(image, connection.id, fresh.id, fresh.lookRevision)
            update { it.copy(avatar = image) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation == owner && wire === client) {
                update { it.copy(outcomes = it.outcomes + (Field.APPEARANCE to Outcome.Failed(error.userMessage()))) }
                if (needsConnect) {
                    wire = null
                    update { it.copy(holdsConnection = false) }
                    client.close()
                }
            }
        }
    }

    fun reloadAppearanceAsync(): Job = scope.launch { reloadAppearance() }

    private suspend fun save(fields: Set<Field>, confirmedModel: Boolean) {
        val before = current
        if (before.loadState !is LoadState.Loaded || before.isSaving || fields.isEmpty()) return
        generation += 1
        val owner = generation
        val sentDraft = before.draft
        val needsConnect = wire == null
        val client = wire ?: makeWire(connection)
        wire = client
        val configured = fields - Field.AVATAR
        update {
            it.copy(
                isSaving = true,
                outcomes = it.outcomes - fields,
                holdsConnection = true,
            )
        }
        watchDisconnect(client, owner)
        try {
            ensureCurrentConnection()
            if (needsConnect) {
                client.connect()
                ensureOwner(owner, client)
            }
            if (configured.isNotEmpty()) {
                val changes = changesFor(configured, confirmedModel, sentDraft)
                val reply = client.call(HermesCall.ProfilesConfigure(changes), validateDispatch(owner))
                ensureOwner(owner, client)
                apply(reply, configured, sentDraft)
            }
            val appearanceApplied = Field.APPEARANCE !in configured || current.outcomes[Field.APPEARANCE] is Outcome.Saved
            if (Field.AVATAR in fields && appearanceApplied) {
                saveAvatar(client, owner)
            } else if (Field.AVATAR in fields) {
                update { it.copy(outcomes = it.outcomes + (Field.AVATAR to Outcome.Failed("Needs Attention"))) }
            }
            if (fields.any { current.outcomes[it] is Outcome.Saved }) onSaved()
        } catch (cancelled: CancellationException) {
            if (generation == owner) markFailed(fields, OUTCOME_UNCERTAIN)
            throw cancelled
        } catch (error: Throwable) {
            if (generation == owner && wire === client) {
                markFailed(fields, error.userMessage())
                wire = null
                update { it.copy(holdsConnection = false) }
                client.close()
            }
        } finally {
            if (generation == owner) update { it.copy(isSaving = false) }
        }
    }

    private fun changesFor(fields: Set<Field>, confirmedModel: Boolean, draft: Draft): HermesCall.ProfileChanges {
        val model = if (Field.MODEL in fields) {
            val value = draft.model
            val provider = value?.providerID
            if (value != null && !provider.isNullOrEmpty()) HermesCall.Model(value.id, provider) else null
        } else {
            null
        }
        return HermesCall.ProfileChanges(
            name = current.profile.id,
            description = draft.description.takeIf { Field.DESCRIPTION in fields },
            soul = draft.instructions.takeIf { Field.INSTRUCTIONS in fields },
            model = model,
            confirmExpensiveModel = confirmedModel,
            disabledSkills = if (Field.SKILLS in fields) draft.skills.filterNot { it.enabled }.map { it.id } else null,
            enabledToolsets = if (Field.TOOLSETS in fields) {
                val enabled = draft.toolsets.filter { it.enabled }
                if (enabled.size == draft.toolsets.size) emptyList() else enabled.map { it.id }
            } else {
                null
            },
            enabledMCPServers = if (Field.MCP_SERVERS in fields) draft.mcpServers.filter { it.enabled }.map { it.id } else null,
            look = if (Field.APPEARANCE in fields) {
                HermesCall.Look(draft.appearance.merging(receivedLook), lookRevision ?: 0)
            } else {
                null
            },
        )
    }

    private fun apply(reply: BotJSON, fields: Set<Field>, sentDraft: Draft) {
        var next = current
        for (field in fields) {
            val key = when (field) {
                Field.APPEARANCE -> "ui_meta"
                Field.DESCRIPTION -> "description"
                Field.INSTRUCTIONS -> "soul"
                Field.MODEL -> "model"
                Field.SKILLS -> "skills"
                Field.TOOLSETS -> "toolsets"
                Field.MCP_SERVERS -> "mcp_servers"
                Field.AVATAR -> continue
            }
            if (reply["applied"][key].flag == true) {
                next = advanceBaseline(next, field, sentDraft, reply)
                next = next.copy(outcomes = next.outcomes + (field to Outcome.Saved))
            } else if (field == Field.MODEL && reply["confirm_required"].flag == true) {
                next = next.copy(
                    outcomes = next.outcomes + (field to Outcome.ConfirmationRequired),
                    confirmation = ModelConfirmation(reply["confirm_message"].text ?: "Confirm this model change?"),
                )
            } else if (field == Field.APPEARANCE && !reply["applied"]["ui_meta_conflicts"].isNull) {
                next = next.copy(outcomes = next.outcomes + (field to Outcome.Conflict))
            } else {
                next = next.copy(outcomes = next.outcomes + (field to Outcome.Failed("Failed")))
            }
        }
        _state.value = next
    }

    private fun advanceBaseline(state: State, field: Field, sentDraft: Draft, reply: BotJSON): State {
        var draft = state.draft
        var baseline = state.baseline
        when (field) {
            Field.APPEARANCE -> {
                val appearance = sentDraft.appearance.copy(title = sentDraft.appearance.title.trim())
                draft = draft.copy(appearance = appearance)
                baseline = baseline.copy(appearance = appearance)
                receivedLook = appearance.merging(receivedLook)
                lookRevision = reply["applied"]["ui_meta_revisions"]["hermes-bots"].integer ?: lookRevision
            }
            Field.DESCRIPTION -> {
                val description = sentDraft.description.trim()
                draft = draft.copy(description = description)
                baseline = baseline.copy(description = description)
            }
            Field.INSTRUCTIONS -> baseline = baseline.copy(instructions = sentDraft.instructions)
            Field.MODEL -> baseline = baseline.copy(model = sentDraft.model)
            Field.SKILLS -> baseline = baseline.copy(skills = sentDraft.skills)
            Field.TOOLSETS -> baseline = baseline.copy(toolsets = sentDraft.toolsets)
            Field.MCP_SERVERS -> baseline = baseline.copy(mcpServers = sentDraft.mcpServers)
            Field.AVATAR -> Unit
        }
        return state.copy(draft = draft, baseline = baseline)
    }

    private suspend fun saveAvatar(client: BotTransport, owner: Int) {
        val change = when (val value = avatarChange) {
            AvatarChange.Unchanged -> return
            is AvatarChange.Replace -> HermesCall.AvatarChange.Replace(value.dataURL)
            AvatarChange.Remove -> HermesCall.AvatarChange.Clear
        }
        val reply = client.call(HermesCall.ProfilesSetAsset(current.profile.id, change), validateDispatch(owner))
        ensureOwner(owner, client)
        if (reply["ok"].flag != true || reply["asset"].text != "avatar") throw BotFailure.Unsupported
        val thumbnail = current.avatar?.let { image ->
            withContext(Dispatchers.Default) { BotAvatarDecoder.thumbnail(image) }
        }
        ensureOwner(owner, client)
        avatarStore.setImage(thumbnail, connection.id, current.profile.id, lookRevision)
        avatarChange = AvatarChange.Unchanged
        update {
            it.copy(
                avatarChanged = false,
                outcomes = it.outcomes + (Field.AVATAR to Outcome.Saved),
            )
        }
    }

    private fun markFailed(fields: Set<Field>, message: String) {
        update { value ->
            val outcomes = value.outcomes.toMutableMap()
            for (field in fields) outcomes.putIfAbsent(field, Outcome.Failed(message))
            value.copy(outcomes = outcomes)
        }
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

    /** Transport callbacks arrive on HermesActor; all state work hops to the injected Main scope. */
    private fun watchDisconnect(client: BotTransport, owner: Int) {
        client.onDisconnect = { error ->
            scope.launch {
                if (generation != owner || wire !== client) return@launch
                wire = null
                val message = error.userMessage()
                update { value ->
                    val outcomes = value.outcomes.toMutableMap()
                    for (field in value.dirtyFields) outcomes.putIfAbsent(field, Outcome.Failed(message))
                    value.copy(outcomes = outcomes, holdsConnection = false)
                }
            }
        }
    }

    companion object {
        /** Safety bound for the picked original; normalization, not this limit, enforces the host's 2 MB asset cap. */
        const val MAX_PHOTO_SOURCE_BYTES = BotAttachmentUpload.MAXIMUM_FILE_BYTES
        const val MAX_PHOTO_ASSET_BYTES = 2_000_000
        const val MAX_PHOTO_PIXEL_SIZE = 1_024
        private const val OUTCOME_UNCERTAIN = "Outcome Uncertain"
    }
}

/** A normalized photo ready for `profiles.set_asset`. */
data class BotProfilePhoto(
    val bitmap: Bitmap,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Android counterpart of `BotProfileEditor.avatarDataURL`: decode and orient once, bound the
 * longest edge to 1024 px, prefer PNG, then JPEG qualities 0.88/0.72/0.56/0.40, all under the
 * host's 2 MB decoded-asset limit.
 */
fun interface BotProfilePhotoDecoder {
    fun normalize(data: ByteArray): BotProfilePhoto?

    companion object {
        val Android = BotProfilePhotoDecoder { data -> normalizeAndroidPhoto(data) }
    }
}

class BotProfileEditorImageException : IllegalArgumentException("Could not decode this image.")

private fun normalizeAndroidPhoto(data: ByteArray): BotProfilePhoto? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= BotProfileEditor.MAX_PHOTO_PIXEL_SIZE) {
        sample *= 2
    }
    var bitmap = BitmapFactory.decodeByteArray(
        data,
        0,
        data.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return@runCatching null

    val oriented = orientBitmap(bitmap, data)
    if (oriented !== bitmap) bitmap.recycle()
    bitmap = oriented
    val bounded = BotAvatarDecoder.thumbnail(bitmap, BotProfileEditor.MAX_PHOTO_PIXEL_SIZE)
    if (bounded !== bitmap) bitmap.recycle()
    bitmap = bounded

    encode(bitmap, Bitmap.CompressFormat.PNG, 100)?.takeIf { it.size <= BotProfileEditor.MAX_PHOTO_ASSET_BYTES }?.let {
        return@runCatching BotProfilePhoto(bitmap, "image/png", it)
    }
    for (quality in intArrayOf(88, 72, 56, 40)) {
        val bytes = encode(bitmap, Bitmap.CompressFormat.JPEG, quality) ?: continue
        if (bytes.size <= BotProfileEditor.MAX_PHOTO_ASSET_BYTES) {
            return@runCatching BotProfilePhoto(bitmap, "image/jpeg", bytes)
        }
    }
    bitmap.recycle()
    null
}.getOrNull()

private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    if (!bitmap.compress(format, quality, output)) return null
    return output.toByteArray()
}

private fun orientBitmap(bitmap: Bitmap, data: ByteArray): Bitmap {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(data)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "Failed"
