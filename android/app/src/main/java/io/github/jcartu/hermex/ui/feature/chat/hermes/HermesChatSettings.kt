package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.hermes.BotSettingFailure
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive


/**
 * A model change paused on the expensive-model confirmation from
 * `Features/Bots/BotChatControls.swift`.
 */
data class HermesModelConfirmation internal constructor(
    val option: ModelCatalogOption,
    val message: String,
    internal val connectionID: String,
    internal val profile: String,
    internal val runtime: String,
    internal val generation: Int,
    internal val expectedModel: ModelCatalogOption?,
)

/**
 * Port of `Features/Chat/HermesChatSettings.swift` and the model/reasoning/Fast
 * portion of `Features/Bots/BotChatControls.swift`.
 *
 * Every write is explicitly session-scoped except a confirmed personality,
 * which is deliberately Profile-wide. Snapshot reads never dispatch writes.
 * Mutable state is owned by the conversation's Main [HermesConversation.scope].
 */
class HermesChatSettings(
    private val engine: HermesConversation,
) {
    private data class Context(
        val connectionID: String,
        val profile: String,
        val runtime: String,
        val generation: Int,
    )

    private data class ReportedEffort(val requested: String, val sent: String)

    val profile: String = engine.target.profile

    var catalog: HermesModelCatalog = HermesModelCatalog(BotJSON.NULL)
        private set
    var effort: String? = null
        private set
    private var reportedFast: Boolean? = null
    var profiles: List<String> = emptyList()
        private set
    /** The unprojected rows from the latest `profiles.list`, for Bot Chat consumers. */
    var roster: List<BotJSON> = emptyList()
        private set
    var pendingModel: ModelCatalogOption? = null
        private set
    var confirmation: HermesModelConfirmation? = null
        private set
    var pendingPersonality: String? = null
        private set
    var errorMessage: String? = null
        private set
    var isApplying: Boolean = false
        private set
    var isLoading: Boolean = false
        private set

    var onChange: () -> Unit = {}

    private var context: Context? = null
    private var lastContext: Context? = null
    private var refused: MutableSet<String> = mutableSetOf()
    private var latestInfo: BotJSON? = null
    private var reportedEffort: ReportedEffort? = null
    private var readJob: Job? = null
    private var readAgain = false
    private var readRevision = 0
    private var writeRevision = 0L
    private var activeWrite: Long? = null
    private var activeWriteWasDispatched = false

    /** A deferred choice while a response runs, otherwise the live catalog model. */
    val selectedModel: ModelCatalogOption?
        get() = pendingModel ?: catalog.active

    var profileOptions: List<ProfileSummary> = emptyList()
        private set

    val supportsEffort: Boolean
        get() {
            val active = catalog.active ?: return false
            return catalog.capabilities[active.favoriteKey()]?.get("reasoning")?.flag != false
        }

    val showsEffort: Boolean get() = supportsEffort

    /** The host ladder, omitting `none` when the active route cannot disable reasoning. */
    val effortLevels: List<String>
        get() {
            val active = catalog.active ?: return HermesModelCatalog.EFFORT_LEVELS
            val canDisable = catalog.capabilities[active.favoriteKey()]?.get("can_disable_reasoning")?.flag
            return if (canDisable == false) {
                HermesModelCatalog.EFFORT_LEVELS.filterNot { it == "none" }
            } else {
                HermesModelCatalog.EFFORT_LEVELS
            }
        }

    /** A lower wire effort reported by `session.info`, or null when it matches the pick. */
    val sentEffort: String?
        get() {
            val report = reportedEffort ?: return null
            return report.sent.takeIf {
                report.requested == effort && it.isNotEmpty() && it != report.requested
            }
        }

    private val showsFast: Boolean
        get() {
            val value = reportedFast ?: return false
            val active = catalog.active ?: return false
            return value || catalog.capabilities[active.favoriteKey()]?.get("fast")?.flag != false
        }

    /** The displayed Fast mode; null hides the chip. */
    val fast: Boolean?
        get() = reportedFast?.takeIf { showsFast }

    private fun isMethodUnavailable(method: String): Boolean =
        method in refused || method in engine.wire.unavailableMethods

    val mayChangeModel: Boolean
        get() = context != null && !isApplying && !isLoading &&
            !isMethodUnavailable("model.options") && !isMethodUnavailable("config.set") && catalog.active != null
    val mayChangeEffort: Boolean get() = mayChangeModel && supportsEffort
    val mayChangeFast: Boolean get() = mayChangeModel && showsFast

    /**
     * Reads `model.options` and `profiles.list` for this attach. A stale attach
     * cannot land either reply, and a failed roster read keeps the prior roster.
     */
    suspend fun connect(runtime: String, attempt: Int) {
        if (engine.generation != attempt || engine.connectionState != HermesConversation.ConnectionState.CONNECTED) return

        disconnect(notify = false)
        val next = Context(
            connectionID = engine.connection.id,
            profile = profile,
            runtime = runtime,
            generation = attempt,
        )
        if (lastContext?.connectionID != next.connectionID || lastContext?.profile != next.profile) {
            pendingModel = null
            refused.clear()
            errorMessage = null
        }
        if (lastContext?.runtime != next.runtime) pendingModel = null
        lastContext = next
        catalog = HermesModelCatalog(BotJSON.NULL)
        effort = null
        reportedFast = null
        context = next
        onChange()

        reload()
        if (context != next || engine.generation != attempt) return
        latestInfo?.let {
            snapshot(it)
            onChange()
        }
        readProfiles(attempt)
    }

    /** Re-reads the host roster without clearing a previously successful read. */
    suspend fun refreshProfiles() {
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED) return
        readProfiles(engine.generation)
    }

    private suspend fun readProfiles(attempt: Int) {
        val reply = try {
            engine.request(HermesCall.ProfilesList(includeSessions = false), attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return
        }
        if (engine.generation != attempt) return
        val rows = reply["profiles"].list ?: return
        val seen = HashSet<String>()
        profiles = rows.mapNotNull { row ->
            row["name"].text?.takeIf { it.isNotEmpty() && seen.add(it) }
        }
        profileOptions = profiles.map { name -> ProfileSummary(name = name) }
        roster = rows
        onChange()
    }

    fun disconnect() = disconnect(notify = true)

    private fun disconnect(notify: Boolean) {
        if (activeWrite != null && activeWriteWasDispatched) {
            errorMessage = BotSettingFailure.UnknownOutcome.message
        }
        context = null
        readJob?.cancel()
        readJob = null
        readAgain = false
        readRevision += 1
        confirmation = null
        activeWrite = null
        activeWriteWasDispatched = false
        isApplying = false
        isLoading = false
        if (notify) onChange()
    }

    /**
     * Applies `session.info` locally. This method never calls `config.set`; it
     * only schedules a catalog refresh when a deferred model has settled idle.
     */
    fun apply(info: BotJSON, idle: Boolean) {
        latestInfo = info
        snapshot(info)
        reportedEffort = info["reasoning_effort"].text?.let { requested ->
            ReportedEffort(requested = requested, sent = info["reasoning_effort_wire"].text.orEmpty())
        }
        onChange()
        if (idle && pendingModel != null) refresh()
    }

    private fun snapshot(info: BotJSON) {
        effort = info["reasoning_effort"].text
        reportedFast = info["fast"].flag
        val queued = pendingModel
        val reportedModel = info["model"].text
        if (queued != null && reportedModel != null && reportedModel != queued.id) pendingModel = null
    }

    /** Coalesces catalog refreshes while one read is in flight. */
    fun refresh() {
        if (context == null) return
        if (readJob != null) {
            readAgain = true
            return
        }
        lateinit var launched: Job
        launched = engine.scope.launch(start = CoroutineStart.LAZY) {
            do {
                readAgain = false
                reload()
            } while (readAgain && currentCoroutineContext().isActive)
            if (readJob === launched) readJob = null
        }
        readJob = launched
        launched.start()
    }

    suspend fun reload() {
        val owner = context ?: return
        readRevision += 1
        val revision = readRevision
        isLoading = true
        onChange()
        try {
            val call = HermesCall.ModelOptions(sessionID = owner.runtime, profile = owner.profile)
            if (isMethodUnavailable(call.method)) return
            val result = engine.request(call, owner.generation)
            currentCoroutineContext().ensureActive()
            if (context != owner || revision != readRevision) return
            if (result["providers"].list == null) throw BotFailure.Unsupported
            catalog = HermesModelCatalog(result)
            val queued = pendingModel
            if (queued != null && catalog.active?.matchesSelection(queued.id, queued.providerID) == true) {
                pendingModel = null
            }
            onChange()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (context != owner || revision != readRevision) return
            val method = "model.options"
            if (isRefused(error)) refused.add(method)
            else if (!isUnsupported(error)) errorMessage = error.displayMessage()
            onChange()
        } finally {
            if (context == owner && revision == readRevision) {
                isLoading = false
                onChange()
            }
        }
    }

    /** Selects one model with an explicit `--session` value. */
    suspend fun select(option: ModelCatalogOption): Boolean {
        if (option.matchesSelection(selectedModel?.id, selectedModel?.providerID)) return false
        val owner = context ?: return false
        if (!mayChangeModel) return false
        return applyModel(option, owner, expectedModel = catalog.active, confirmed = false)
    }

    /** Exact id first, then the first id or display name containing the query. */
    fun model(query: String): ModelCatalogOption? {
        for (group in catalog.groups) {
            for (option in group.models) if (option.id.equals(query, ignoreCase = true)) return option
            for (option in group.extraModels) if (option.id.equals(query, ignoreCase = true)) return option
        }
        for (group in catalog.groups) {
            for (option in group.models) {
                if (option.id.contains(query, ignoreCase = true) ||
                    option.displayName.contains(query, ignoreCase = true)
                ) return option
            }
            for (option in group.extraModels) {
                if (option.id.contains(query, ignoreCase = true) ||
                    option.displayName.contains(query, ignoreCase = true)
                ) return option
            }
        }
        return null
    }

    fun cancelConfirmation() {
        if (confirmation == null) return
        confirmation = null
        onChange()
    }

    /** Resends the held expensive-model pick exactly once, now confirmed. */
    suspend fun confirm(): Boolean {
        val pending = confirmation ?: return false
        confirmation = null
        onChange()
        val owner = context ?: return false
        if (
            pending.connectionID != owner.connectionID || pending.profile != owner.profile ||
            pending.runtime != owner.runtime || pending.generation != owner.generation ||
            pending.expectedModel != catalog.active || !mayChangeModel
        ) {
            errorMessage = BotFailure.Stale.message
            onChange()
            return false
        }
        return applyModel(
            option = pending.option,
            owner = owner,
            expectedModel = pending.expectedModel,
            confirmed = true,
        )
    }

    private suspend fun applyModel(
        option: ModelCatalogOption,
        owner: Context,
        expectedModel: ModelCatalogOption?,
        confirmed: Boolean,
    ): Boolean {
        val value = HermesModelCatalog.sessionModelValue(option)
        if (value == null) {
            errorMessage = "This model identifier cannot be safely sent to this host."
            onChange()
            return false
        }
        val result = applySetting(
            owner = owner,
            expectedModel = expectedModel,
            call = HermesCall.ConfigSet(
                sessionID = owner.runtime,
                profile = owner.profile,
                setting = HermesCall.SessionSetting.Model(value = value, confirmExpensive = confirmed),
            ),
        ) ?: return false

        if (result["key"].text != "model" || result["scope"].text != "session" || result["value"].text == null) {
            settingFailed(BotSettingFailure.UnknownOutcome)
            return false
        }
        when (result["confirm_required"].flag) {
            true -> {
                confirmation = HermesModelConfirmation(
                    option = option,
                    message = result["confirm_message"].text
                        ?: result["warning"].text
                        ?: "Confirm this model change?",
                    connectionID = owner.connectionID,
                    profile = owner.profile,
                    runtime = owner.runtime,
                    generation = owner.generation,
                    expectedModel = expectedModel,
                )
                onChange()
                return false
            }
            false -> {
                pendingModel = option.takeIf { result["deferred"].flag == true }
                reportedEffort = null
                onChange()
                reload()
                return errorMessage == null && confirmation == null
            }
            null -> {
                settingFailed(BotSettingFailure.UnknownOutcome)
                return false
            }
        }
    }

    /** Selects a reasoning level from the active model's permitted ladder. */
    suspend fun selectEffort(effort: String): Boolean {
        if (effort !in effortLevels || effort == this.effort || !mayChangeEffort) return false
        val owner = context ?: return false
        val result = applySetting(
            owner = owner,
            expectedModel = catalog.active,
            call = HermesCall.ConfigSet(
                sessionID = owner.runtime,
                profile = owner.profile,
                setting = HermesCall.SessionSetting.Reasoning(effort),
            ),
        ) ?: return false
        if (result["key"].text != "reasoning" || result["value"].text != effort) {
            settingFailed(BotSettingFailure.UnknownOutcome)
            return false
        }
        this.effort = effort
        onChange()
        return errorMessage == null
    }

    /** Selects Fast/normal and adopts only a reply that names the requested mode. */
    suspend fun selectFast(fast: Boolean): Boolean {
        if (fast == reportedFast || !mayChangeFast) return false
        val owner = context ?: return false
        val result = applySetting(
            owner = owner,
            expectedModel = catalog.active,
            call = HermesCall.ConfigSet(
                sessionID = owner.runtime,
                profile = owner.profile,
                setting = HermesCall.SessionSetting.Fast(fast),
            ),
        ) ?: return false
        val expected = if (fast) "fast" else "normal"
        if (result["key"].text != "fast" || result["value"].text != expected) {
            settingFailed(BotSettingFailure.UnknownOutcome)
            return false
        }
        reportedFast = fast
        onChange()
        return errorMessage == null
    }

    private suspend fun applySetting(
        owner: Context,
        expectedModel: ModelCatalogOption?,
        call: HermesCall.ConfigSet,
    ): BotJSON? {
        if (context != owner || isApplying || isLoading || isMethodUnavailable(call.method)) return null
        writeRevision += 1
        val revision = writeRevision
        activeWrite = revision
        activeWriteWasDispatched = false
        errorMessage = null
        confirmation = null
        isApplying = true
        readRevision += 1
        isLoading = false
        onChange()

        var dispatched = false
        try {
            val result = engine.write(call, owner.generation, owner.runtime) {
                if (context != owner || activeWrite != revision || catalog.active != expectedModel) {
                    throw BotFailure.Stale
                }
                dispatched = true
                activeWriteWasDispatched = true
            }
            currentCoroutineContext().ensureActive()
            if (context != owner || activeWrite != revision) return null
            return result
        } catch (cancelled: CancellationException) {
            if (context == owner && activeWrite == revision && dispatched) {
                errorMessage = BotSettingFailure.UnknownOutcome.message
                onChange()
            }
            throw cancelled
        } catch (error: Throwable) {
            if (context != owner || activeWrite != revision) return null
            if (isRefused(error)) refused.add(call.method)
            errorMessage = when {
                error is BotSettingFailure.Rejected -> error.message
                !dispatched || isUnsupported(error) -> error.displayMessage()
                else -> BotSettingFailure.UnknownOutcome.message
            }
            onChange()
            return null
        } finally {
            if (activeWrite == revision) {
                activeWrite = null
                activeWriteWasDispatched = false
                isApplying = false
                onChange()
            }
        }
    }

    private fun settingFailed(error: BotSettingFailure) {
        errorMessage = error.message
        onChange()
    }

    /** The host personalities, excluding its special `none` row. */
    suspend fun personalities(): List<Pair<String, String>> {
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED) throw BotFailure.Stale
        val runtime = engine.runtime ?: throw BotFailure.Stale
        val reply = engine.request(
            HermesCall.CompleteSlash(text = "/personality ", sessionID = runtime),
            engine.generation,
        )
        val items = reply["items"].list ?: throw BotFailure.Unsupported
        return items.mapNotNull { item ->
            val name = item["text"].text?.trim()?.takeIf { it.isNotEmpty() && it != "none" }
                ?: return@mapNotNull null
            name to item["meta"].text.orEmpty()
        }
    }

    /** Holds a Profile-wide personality change until the user confirms it. */
    fun askPersonality(name: String) {
        pendingPersonality = name
        onChange()
    }

    fun cancelPersonality() {
        if (pendingPersonality == null) return
        pendingPersonality = null
        onChange()
    }

    /** Writes a confirmed personality Profile-wide and never retries a lost reply. */
    suspend fun setPersonality(name: String) {
        pendingPersonality = null
        onChange()
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED) throw BotFailure.Stale
        val runtime = engine.runtime ?: throw BotFailure.Stale
        var dispatched = false
        try {
            val reply = engine.write(
                HermesCall.ConfigSet(
                    sessionID = runtime,
                    profile = profile,
                    setting = HermesCall.SessionSetting.Personality(name),
                ),
                engine.generation,
                runtime,
            ) { dispatched = true }
            if (reply["key"].text != "personality") throw BotSettingFailure.UnknownOutcome
        } catch (failure: BotSettingFailure) {
            throw failure
        } catch (error: Throwable) {
            if (dispatched) throw BotSettingFailure.UnknownOutcome
            throw error
        }
    }

    private fun ModelCatalogOption.favoriteKey(): ModelFavoriteKey =
        ModelFavoriteKey(modelID = id, providerID = providerID)

    private fun isUnsupported(error: Throwable): Boolean {
        if (isRefused(error)) return true
        if (error is BotFailure.Rejected && error.code == -32601) return true
        return error is BotSettingFailure.Rejected && error.code == -32601
    }

    private fun isRefused(error: Throwable): Boolean =
        error is BotFailure.Unsupported || error is BotSettingFailure.Rejected && error.code == 403

    private fun Throwable.displayMessage(): String = message ?: "Hermes did not complete the change."
}
