package io.github.jcartu.hermex.model

import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class ChatStartResponse(
    @SerialName("stream_id")
    val streamId: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    /**
     * Unix epoch seconds for when the server started this turn (`pending_started_at`),
     * so "Working for" counts from the server's clock. Absent on servers that do not send it.
     */
    @SerialName("pending_started_at")
    val pendingStartedAt: Double? = null,
    val error: String? = null,
) {
    /** Port of `runStartedAt(sentAt:)`: the server's start when it sent one, else the local send time. */
    fun runStartedAt(sentAt: Double): Double =
        pendingStartedAt?.takeIf { it > 0 } ?: sentAt
}

@Serializable
data class ChatCancelResponse(
    val ok: Boolean? = null,
    val cancelled: Boolean? = null,
    @SerialName("stream_id")
    val streamId: String? = null,
    val error: String? = null,
)

@Serializable
data class ChatStreamStatusResponse(
    val active: Boolean? = null,
    @SerialName("stream_id")
    val streamId: String? = null,
    @SerialName("replay_available")
    val replayAvailable: Boolean? = null,
    val journal: RunJournalStatus? = null,
)

/**
 * The server's run-journal summary, surfaced on `/api/chat/stream/status` so a
 * reconciled Live Activity can be finalized with the run's real outcome (#267).
 * Every field is optional: the `journal` block is absent when the server has no
 * summary for a stream, and [terminalState]'s vocabulary may grow upstream — so
 * decoding is tolerant and never crashes on an unknown value.
 */
@Serializable
data class RunJournalStatus(
    /**
     * Whether the server logged a genuine terminal event for the run. Decoded to
     * mirror the journal payload shape (#267 acceptance criterion named both
     * fields); outcome mapping reads [terminalState] only. Kept because it is not
     * redundant with [terminalState]: a run the server force-marks
     * `"lost-worker-bookkeeping"` reports `terminal == false`, so this stays
     * available for any future consumer that must tell a real terminal event from
     * a bookkeeping one.
     */
    val terminal: Boolean? = null,
    @SerialName("terminal_state")
    val terminalState: String? = null,
)

@Serializable
data class ChatSteerResponse(
    val accepted: Boolean? = null,
    val fallback: String? = null,
    @SerialName("stream_id")
    val streamId: String? = null,
    val error: String? = null,
)

@Serializable
data class BtwStartResponse(
    @SerialName("stream_id")
    val streamId: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("parent_session_id")
    val parentSessionId: String? = null,
    val error: String? = null,
)

@Serializable
data class BackgroundStartResponse(
    @SerialName("task_id")
    val taskId: String? = null,
    @SerialName("stream_id")
    val streamId: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    val error: String? = null,
)

@Serializable
data class BackgroundStatusResponse(
    val results: List<BackgroundResult>? = null,
)

@Serializable
data class BackgroundResult(
    @SerialName("task_id")
    val taskId: String? = null,
    val prompt: String? = null,
    val answer: String? = null,
    @SerialName("completed_at")
    val completedAt: Double? = null,
)

@Serializable
data class ModelsResponse(
    val groups: List<JSONValue>? = null,
    val models: List<JSONValue>? = null,
    @SerialName("default_model")
    val defaultModel: String? = null,
    @SerialName("active_provider")
    val activeProvider: String? = null,
) {
    val catalogGroups: List<ModelCatalogGroup>
        get() = ModelCatalogParser.parseGroups(from = this)

    fun displayName(modelID: String?): String? {
        if (modelID == null) return null
        return catalogGroups
            .flatMap { it.slashAutocompleteModels }
            .firstOrNull { it.id == modelID }
            ?.displayName
    }
}

@Serializable
data class CommandsResponse(
    val commands: List<AgentCommand>? = null,
)

@Serializable(with = AgentCommandSerializer::class)
data class AgentCommand(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val aliases: List<String>? = null,
    val argsHint: String? = null,
    val subcommands: List<String>? = null,
    val cliOnly: Boolean? = null,
    val gatewayOnly: Boolean? = null,
) {
    val id: String
        get() = name ?: UUID.randomUUID().toString()
}

internal object AgentCommandSerializer : TolerantObjectSerializer<AgentCommand>("AgentCommand") {
    override fun fromJson(json: Json, obj: JsonObject): AgentCommand = AgentCommand(
        name = obj.lossyString("name"),
        description = obj.lossyString("description"),
        category = obj.lossyString("category"),
        aliases = obj.presentElement("aliases")?.let { element ->
            runCatching {
                json.decodeFromJsonElement(ListSerializer(String.serializer()), element)
            }.getOrNull()
        },
        argsHint = obj.lossyString("args_hint", "argsHint"),
        subcommands = obj.presentElement("subcommands")?.let { element ->
            runCatching {
                json.decodeFromJsonElement(ListSerializer(String.serializer()), element)
            }.getOrNull()
        },
        cliOnly = obj.lossyBool("cli_only", "cliOnly"),
        gatewayOnly = obj.lossyBool("gateway_only", "gatewayOnly"),
    )

    override fun toJson(json: Json, value: AgentCommand): JsonObject = buildJsonObject {
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.description?.let { put("description", JsonPrimitive(it)) }
        value.category?.let { put("category", JsonPrimitive(it)) }
        value.aliases?.let { aliases ->
            put("aliases", JsonArray(aliases.map { JsonPrimitive(it) }))
        }
        value.argsHint?.let { put("args_hint", JsonPrimitive(it)) }
        value.subcommands?.let { subcommands ->
            put("subcommands", JsonArray(subcommands.map { JsonPrimitive(it) }))
        }
        value.cliOnly?.let { put("cli_only", JsonPrimitive(it)) }
        value.gatewayOnly?.let { put("gateway_only", JsonPrimitive(it)) }
    }
}

/**
 * `GET /api/providers` — read-only provider status (#26). Shape verified against
 * the live server (2026-07-02) and upstream `api/providers.py::get_providers()`
 * @ `312d3fab`: standard entries carry the full field set, while entries derived
 * from `custom_providers` in config.yaml (`is_custom == true`) omit `is_oauth`,
 * `auth_error`, `is_self_hosted`, `base_url`, and `is_plugin_provider` — so every
 * field stays optional and decoding never fails on a partial entry.
 */
@Serializable(with = ProvidersResponseSerializer::class)
data class ProvidersResponse(
    val providers: List<ProviderSummary>? = null,
    val activeProvider: String? = null,
)

internal object ProvidersResponseSerializer :
    TolerantObjectSerializer<ProvidersResponse>("ProvidersResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ProvidersResponse = ProvidersResponse(
        providers = obj.presentElement("providers")?.let { element ->
            runCatching {
                json.decodeFromJsonElement(ListSerializer(ProviderSummary.serializer()), element)
            }.getOrNull()
        },
        activeProvider = obj.lossyString("active_provider", "activeProvider"),
    )

    override fun toJson(json: Json, value: ProvidersResponse): JsonObject = buildJsonObject {
        value.providers?.let { providers ->
            put(
                "providers",
                JsonArray(providers.map { json.encodeToJsonElement(ProviderSummary.serializer(), it) }),
            )
        }
        value.activeProvider?.let { put("active_provider", JsonPrimitive(it)) }
    }
}

/**
 * One provider entry from `GET /api/providers`. [keySource] vocabulary upstream:
 * `env_file`, `env_var`, `config_yaml`, `oauth`, `none` — plus `env`, `config`,
 * and `token` from the live-auth fallback probe. Unknown values are kept verbatim.
 */
@Serializable(with = ProviderSummarySerializer::class)
data class ProviderSummary(
    val id: String? = null,
    val displayName: String? = null,
    val hasKey: Boolean? = null,
    val configurable: Boolean? = null,
    val isSelfHosted: Boolean? = null,
    val baseUrl: String? = null,
    val isPluginProvider: Boolean? = null,
    val isOauth: Boolean? = null,
    val isCustom: Boolean? = null,
    val keySource: String? = null,
    val authError: String? = null,
    val models: List<ProviderModel>? = null,
    /**
     * Size of the provider's complete catalog. May exceed `models.size` when the
     * server trims the list to a featured subset (e.g. large Nous Portal accounts).
     */
    val modelsTotal: Int? = null,
)

internal object ProviderSummarySerializer :
    TolerantObjectSerializer<ProviderSummary>("ProviderSummary") {

    override fun fromJson(json: Json, obj: JsonObject): ProviderSummary = ProviderSummary(
        id = obj.lossyString("id"),
        displayName = obj.lossyString("display_name", "displayName"),
        hasKey = obj.lossyBool("has_key", "hasKey"),
        configurable = obj.lossyBool("configurable"),
        isSelfHosted = obj.lossyBool("is_self_hosted", "isSelfHosted"),
        baseUrl = obj.lossyString("base_url", "baseUrl"),
        isPluginProvider = obj.lossyBool("is_plugin_provider", "isPluginProvider"),
        isOauth = obj.lossyBool("is_oauth", "isOauth"),
        isCustom = obj.lossyBool("is_custom", "isCustom"),
        keySource = obj.lossyString("key_source", "keySource"),
        authError = obj.lossyString("auth_error", "authError"),
        models = obj.presentElement("models")?.let { element ->
            runCatching {
                json.decodeFromJsonElement(ListSerializer(ProviderModel.serializer()), element)
            }.getOrNull()
        },
        modelsTotal = obj.lossyInt("models_total", "modelsTotal"),
    )

    override fun toJson(json: Json, value: ProviderSummary): JsonObject = buildJsonObject {
        value.id?.let { put("id", JsonPrimitive(it)) }
        value.displayName?.let { put("display_name", JsonPrimitive(it)) }
        value.hasKey?.let { put("has_key", JsonPrimitive(it)) }
        value.configurable?.let { put("configurable", JsonPrimitive(it)) }
        value.isSelfHosted?.let { put("is_self_hosted", JsonPrimitive(it)) }
        value.baseUrl?.let { put("base_url", JsonPrimitive(it)) }
        value.isPluginProvider?.let { put("is_plugin_provider", JsonPrimitive(it)) }
        value.isOauth?.let { put("is_oauth", JsonPrimitive(it)) }
        value.isCustom?.let { put("is_custom", JsonPrimitive(it)) }
        value.keySource?.let { put("key_source", JsonPrimitive(it)) }
        value.authError?.let { put("auth_error", JsonPrimitive(it)) }
        value.models?.let { models ->
            put(
                "models",
                JsonArray(models.map { json.encodeToJsonElement(ProviderModel.serializer(), it) }),
            )
        }
        value.modelsTotal?.let { put("models_total", JsonPrimitive(it)) }
    }
}

/**
 * A model entry inside a provider's `models` list. Upstream normally emits
 * `{ "id": …, "label": … }` objects, but the docs historically described bare
 * model-ID strings — both shapes decode.
 */
@Serializable(with = ProviderModelSerializer::class)
data class ProviderModel(
    val id: String? = null,
    val label: String? = null,
)

/**
 * Unlike the other custom decoders in this file, [ProviderModel] accepts either
 * a keyed object or a bare string, so it cannot use [TolerantObjectSerializer],
 * whose non-object rejection is otherwise the exact Swift behavior.
 */
internal object ProviderModelSerializer : KSerializer<ProviderModel> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ProviderModel")

    override fun deserialize(decoder: Decoder): ProviderModel {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("ProviderModel supports JSON decoding only")
        val element = input.decodeJsonElement()

        if (element is JsonPrimitive && element.isString) {
            return ProviderModel(id = element.content, label = element.content)
        }

        val obj = element as? JsonObject
            ?: throw SerializationException("Expected JSON object or string for ProviderModel")
        return ProviderModel(
            id = obj.lossyString("id"),
            label = obj.lossyString("label"),
        )
    }

    override fun serialize(encoder: Encoder, value: ProviderModel) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("ProviderModel supports JSON encoding only")
        output.encodeJsonElement(
            buildJsonObject {
                value.id?.let { put("id", JsonPrimitive(it)) }
                value.label?.let { put("label", JsonPrimitive(it)) }
            },
        )
    }
}

/**
 * `GET /api/settings` (the saved-settings body `POST /api/settings` echoes the
 * same shape back). The server returns ~75 keys; only the ones with a consumer
 * or near-term use (#19) are decoded. Every field is optional and lossy-decoded
 * — servers omit keys freely and an unexpected shape never crashes decoding.
 */
@Serializable(with = SettingsResponseSerializer::class)
data class SettingsResponse(
    val botName: String? = null,
    val webuiVersion: String? = null,
    val agentVersion: String? = null,
    val theme: String? = null,
    val checkForUpdates: Boolean? = null,
    val showCliSessions: Boolean? = null,
    val showClaudeCodeSessions: Boolean? = null,
    val maxTokens: Int? = null,
    val maxTokensEffective: Int? = null,
    val authEnabled: Boolean? = null,
    val passwordAuthEnabled: Boolean? = null,
    val passkeysEnabled: Boolean? = null,
    val passwordlessEnabled: Boolean? = null,
)

internal object SettingsResponseSerializer :
    TolerantObjectSerializer<SettingsResponse>("SettingsResponse") {

    override fun fromJson(json: Json, obj: JsonObject): SettingsResponse = SettingsResponse(
        botName = obj.lossyString("bot_name", "botName"),
        webuiVersion = obj.lossyString("webui_version", "webuiVersion"),
        agentVersion = obj.lossyString("agent_version", "agentVersion"),
        theme = obj.lossyString("theme"),
        checkForUpdates = obj.lossyBool("check_for_updates", "checkForUpdates"),
        showCliSessions = obj.lossyBool("show_cli_sessions", "showCliSessions"),
        showClaudeCodeSessions = obj.lossyBool(
            "show_claude_code_sessions",
            "showClaudeCodeSessions",
        ),
        maxTokens = obj.lossyInt("max_tokens", "maxTokens"),
        maxTokensEffective = obj.lossyInt("max_tokens_effective", "maxTokensEffective"),
        authEnabled = obj.lossyBool("auth_enabled", "authEnabled"),
        passwordAuthEnabled = obj.lossyBool("password_auth_enabled", "passwordAuthEnabled"),
        passkeysEnabled = obj.lossyBool("passkeys_enabled", "passkeysEnabled"),
        passwordlessEnabled = obj.lossyBool("passwordless_enabled", "passwordlessEnabled"),
    )

    override fun toJson(json: Json, value: SettingsResponse): JsonObject = buildJsonObject {
        value.botName?.let { put("bot_name", JsonPrimitive(it)) }
        value.webuiVersion?.let { put("webui_version", JsonPrimitive(it)) }
        value.agentVersion?.let { put("agent_version", JsonPrimitive(it)) }
        value.theme?.let { put("theme", JsonPrimitive(it)) }
        value.checkForUpdates?.let { put("check_for_updates", JsonPrimitive(it)) }
        value.showCliSessions?.let { put("show_cli_sessions", JsonPrimitive(it)) }
        value.showClaudeCodeSessions?.let {
            put("show_claude_code_sessions", JsonPrimitive(it))
        }
        value.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        value.maxTokensEffective?.let { put("max_tokens_effective", JsonPrimitive(it)) }
        value.authEnabled?.let { put("auth_enabled", JsonPrimitive(it)) }
        value.passwordAuthEnabled?.let { put("password_auth_enabled", JsonPrimitive(it)) }
        value.passkeysEnabled?.let { put("passkeys_enabled", JsonPrimitive(it)) }
        value.passwordlessEnabled?.let { put("passwordless_enabled", JsonPrimitive(it)) }
    }
}

@Serializable
data class DefaultModelResponse(
    val ok: Boolean? = null,
    val model: String? = null,
)

/**
 * `GET /api/updates/check`. Every field is optional: older servers, the
 * `{ "disabled": true }` opt-out payload, and failed/`stale_check` responses
 * all omit different keys, and decoding never crashes on an unexpected shape.
 */
@Serializable
data class UpdatesCheckResponse(
    val webui: UpdateTargetInfo? = null,
    val agent: UpdateTargetInfo? = null,
    @SerialName("checked_at")
    val checkedAt: Double? = null,
    val disabled: Boolean? = null,
) {
    /**
     * What the Settings screen should show for the webui repo. [Unavailable]
     * means "show the version only, no indicator" — the server turned the check
     * off, errored, returned a stale result, or omitted the webui block.
     */
    sealed interface WebUIUpdateState {
        object UpToDate : WebUIUpdateState
        data class UpdateAvailable(val behind: Int) : WebUIUpdateState
        object Unavailable : WebUIUpdateState
    }

    /**
     * The fully-distinguished result of a *manual* (forced) update check (#308).
     * Unlike [webuiUpdateState], this keeps [Disabled] and [Error] apart so the
     * "Check for updates" popup can word each case for the user — the passive
     * inline note treats both as "no indicator" and collapses them together.
     */
    sealed interface ForcedCheckOutcome {
        data class UpdateAvailable(val behind: Int) : ForcedCheckOutcome
        object UpToDate : ForcedCheckOutcome

        /** Update checks are turned off on this server (`{ "disabled": true }`). */
        object Disabled : ForcedCheckOutcome

        /** The check failed, returned a stale result, or omitted the webui block. */
        object Error : ForcedCheckOutcome
    }

    val forcedCheckOutcome: ForcedCheckOutcome
        get() {
            if (disabled == true) return ForcedCheckOutcome.Disabled

            val webui = webui ?: return ForcedCheckOutcome.Error
            if (webui.error != null || webui.staleCheck == true) {
                return ForcedCheckOutcome.Error
            }

            val behind = webui.behind
            if (behind != null && behind > 0) {
                return ForcedCheckOutcome.UpdateAvailable(behind)
            }

            return ForcedCheckOutcome.UpToDate
        }

    /**
     * The passive inline indicator's coarser view of the same check. Derived from
     * [forcedCheckOutcome] so the two never drift: both "off" and "errored"
     * collapse to [WebUIUpdateState.Unavailable] (show the version only, with no indicator).
     */
    val webuiUpdateState: WebUIUpdateState
        get() = when (val outcome = forcedCheckOutcome) {
            is ForcedCheckOutcome.UpdateAvailable ->
                WebUIUpdateState.UpdateAvailable(outcome.behind)
            ForcedCheckOutcome.UpToDate -> WebUIUpdateState.UpToDate
            ForcedCheckOutcome.Disabled,
            ForcedCheckOutcome.Error,
            -> WebUIUpdateState.Unavailable
        }
}

@Serializable
data class UpdateTargetInfo(
    val name: String? = null,
    val behind: Int? = null,
    @SerialName("current_sha")
    val currentSha: String? = null,
    @SerialName("latest_sha")
    val latestSha: String? = null,
    val branch: String? = null,
    @SerialName("repo_url")
    val repoUrl: String? = null,
    @SerialName("compare_url")
    val compareUrl: String? = null,
    val error: String? = null,
    @SerialName("stale_check")
    val staleCheck: Boolean? = null,
)

/**
 * `POST /api/updates/apply`. Tolerant: every field is optional because the
 * server returns a different mix of keys per outcome — success (`ok`,
 * `restart_scheduled`), restart-blocked (`restart_blocked` + active counts),
 * merge conflict (`conflict`), diverged history (`diverged`), or a generic
 * failure — and may add more over time. Decoding never crashes on an unexpected shape.
 */
@Serializable
data class UpdatesApplyResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val target: String? = null,
    val conflict: Boolean? = null,
    val diverged: Boolean? = null,
    @SerialName("restart_blocked")
    val restartBlocked: Boolean? = null,
    @SerialName("restart_scheduled")
    val restartScheduled: Boolean? = null,
    @SerialName("stash_conflict")
    val stashConflict: Boolean? = null,
    @SerialName("active_streams")
    val activeStreams: Int? = null,
    @SerialName("active_runs")
    val activeRuns: Int? = null,
) {
    /** How the Settings screen should react to an apply attempt. */
    sealed interface Outcome {
        /** Server accepted the update and is restarting; poll until it returns. */
        object Applying : Outcome

        /**
         * Active chat/agent work blocked the restart. Not a failure — surface
         * the server's message and let the user retry once work finishes.
         */
        object RestartBlocked : Outcome

        /**
         * The update could not be applied (merge conflict, diverged history,
         * unreachable remote, or a generic `ok: false`).
         */
        object Failed : Outcome
    }

    val outcome: Outcome
        get() {
            // A restart-blocked response always carries `ok: false`, so check the
            // blocked flag first to avoid mislabelling it as a hard failure.
            if (restartBlocked == true) return Outcome.RestartBlocked
            if (ok == true) return Outcome.Applying
            return Outcome.Failed
        }

    /** The server's human-readable message, or [fallback] when it omitted one. */
    fun displayMessage(fallback: String): String {
        val trimmed = message?.trim()
        return if (trimmed.isNullOrEmpty()) fallback else trimmed
    }
}

@Serializable
data class ReasoningStatusResponse(
    val ok: Boolean? = null,
    @SerialName("show_reasoning")
    val showReasoning: Boolean? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    val effort: String? = null,
    /**
     * Model-aware effort vocabulary from `GET /api/reasoning` (`supported_efforts`).
     * `null` on older servers that don't send the field — callers must fall back
     * to the static effort list (issue #18).
     */
    @SerialName("supported_efforts")
    val supportedEfforts: List<String>? = null,
    /**
     * `supports_reasoning_effort` — `false` means the resolved model has no
     * effort control at all (hide the picker). `null` on older servers.
     */
    @SerialName("supports_reasoning_effort")
    val supportsReasoningEffort: Boolean? = null,
    val error: String? = null,
) {
    val effectiveEffort: String?
        get() = reasoningEffort ?: effort

    /**
     * [supportedEfforts] trimmed, lowercased, de-duplicated, order preserved.
     * Stays `null` when the server omitted the field (legacy fallback signal).
     */
    val normalizedSupportedEfforts: List<String>?
        get() {
            val efforts = supportedEfforts ?: return null
            val seen = mutableSetOf<String>()
            return efforts
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && seen.add(it) }
        }
}

@Serializable
data class PersonalitiesResponse(
    val personalities: List<PersonalitySummary>? = null,
) {
    val slashAutocompleteNames: List<String>
        get() {
            val names = mutableListOf("none")
            personalities.orEmpty().forEach { personality ->
                personality.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(names::add)
            }

            val seen = mutableSetOf<String>()
            return names.filter { seen.add(it) }
        }
}

@Serializable
data class PersonalitySummary(
    val name: String? = null,
    val description: String? = null,
) {
    val id: String
        get() = name ?: UUID.randomUUID().toString()
}

@Serializable
data class PersonalitySetResponse(
    val ok: Boolean? = null,
    val personality: String? = null,
    val prompt: String? = null,
    val error: String? = null,
)

@Serializable
data class ProfilesResponse(
    val profiles: List<ProfileSummary>? = null,
    val active: String? = null,
    @SerialName("single_profile_mode")
    val singleProfileMode: Boolean? = null,
) {
    val effectiveDefaultProfileName: String?
        get() {
            normalizedProfileName(active)?.let { return it }

            profiles
                ?.firstOrNull { it.isActive == true }
                ?.normalizedName
                ?.let { return it }

            profiles
                ?.firstOrNull { it.isDefault == true }
                ?.normalizedName
                ?.let { return it }

            return profiles
                ?.asSequence()
                ?.mapNotNull { it.normalizedName }
                ?.firstOrNull()
        }

    fun displayName(profileName: String?): String? {
        val normalizedName = normalizedProfileName(profileName) ?: return null
        return profile(matching = normalizedName)?.displayName
            ?: if (normalizedName == "default") "Default" else normalizedName
    }

    fun profile(matching: String?): ProfileSummary? {
        val normalizedName = normalizedProfileName(matching) ?: return null
        return profiles?.firstOrNull { it.normalizedName == normalizedName }
    }

    private fun normalizedProfileName(profileName: String?): String? =
        profileName?.trim()?.takeIf { it.isNotEmpty() }
}

@Serializable
data class ProfileCreateResponse(
    val ok: Boolean? = null,
    val profile: ProfileSummary? = null,
    val error: String? = null,
)

/**
 * Mirrors the upstream profile-name rule (`^[a-z0-9][a-z0-9_-]{0,63}$`) so the
 * create form can validate before hitting the server.
 */
object ProfileNameRules {
    fun isValid(name: String): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        if (!isLowercaseAlphanumeric(name.first())) return false
        return name.all { isLowercaseAlphanumeric(it) || it == '-' || it == '_' }
    }

    private fun isLowercaseAlphanumeric(character: Char): Boolean =
        character in 'a'..'z' || character in '0'..'9'

    /**
     * Mirrors the upstream base-URL rule for profile creation: when provided,
     * the value must start with `http://` or `https://` (server 400s otherwise).
     */
    fun isValidBaseURL(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")
}

@Serializable
data class ProfileSwitchResponse(
    val profiles: List<ProfileSummary>? = null,
    val active: String? = null,
    @SerialName("default_model")
    val defaultModel: String? = null,
    @SerialName("default_workspace")
    val defaultWorkspace: String? = null,
    val error: String? = null,
)

@Serializable
data class ProfileSummary(
    val name: String? = null,
    val path: String? = null,
    @SerialName("is_default")
    val isDefault: Boolean? = null,
    @SerialName("is_active")
    val isActive: Boolean? = null,
    @SerialName("gateway_running")
    val gatewayRunning: Boolean? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("has_env")
    val hasEnv: Boolean? = null,
    @SerialName("skill_count")
    val skillCount: Int? = null,
) {
    val id: String
        get() = name ?: path ?: UUID.randomUUID().toString()

    val displayName: String
        get() {
            if (name.isNullOrEmpty()) return "Profile"
            return if (name == "default") "Default" else name
        }

    val normalizedName: String?
        get() = name?.trim()?.takeIf { it.isNotEmpty() }
}

data class ModelCatalogGroup(
    val id: String,
    val name: String,
    val providerID: String?,
    val models: List<ModelCatalogOption>,
    val extraModels: List<ModelCatalogOption> = emptyList(),
) {
    val slashAutocompleteModels: List<ModelCatalogOption>
        get() {
            val seen = mutableSetOf<String>()
            return (models + extraModels).filter { seen.add(it.id) }
        }
}

data class ModelCatalogOption(
    val id: String,
    val displayName: String,
    val providerID: String?,
) {
    fun matchesSelection(modelID: String?, providerID: String?): Boolean {
        if (id != modelID) return false
        if (providerID == null) return true
        return this.providerID == providerID
    }
}

fun Collection<ModelCatalogOption>.firstMatchingSelection(
    modelID: String?,
    providerID: String?,
): ModelCatalogOption? {
    if (modelID.isNullOrEmpty()) return null

    if (providerID != null) {
        return firstOrNull { it.id == modelID && it.providerID == providerID }
    }

    return firstOrNull { it.id == modelID }
}

/**
 * Response of `GET /api/models/live`: the uncached model list for one provider.
 * Shape: `{"provider": "<id>", "models": [{"id", "label"}], "count": <int>}`;
 * the server echoes back the provider it resolved when none was requested.
 */
@Serializable
data class ModelsLiveResponse(
    val provider: String? = null,
    val models: List<JSONValue>? = null,
    val count: Int? = null,
) {
    /** Provider id with whitespace-only values normalized away. */
    val normalizedProvider: String?
        get() = provider?.trim()?.takeIf { it.isNotEmpty() }

    /** Models parsed from the live payload, attributed to the echoed provider. */
    val liveOptions: List<ModelCatalogOption>
        get() {
            val models = models ?: return emptyList()
            return ModelCatalogParser.parseModelOptions(
                from = JSONValue.array(models),
                providerID = normalizedProvider,
            )
        }
}

/**
 * Replaces the matching provider group's models with the live list (live is
 * authoritative for that provider, covering both additions and removals).
 * Returns the receiver unchanged when the provider matches no group or the live
 * list is empty, so an odd live response can never blank out the cached picker.
 */
fun List<ModelCatalogGroup>.mergingLiveModels(
    response: ModelsLiveResponse,
): List<ModelCatalogGroup> {
    val provider = response.normalizedProvider ?: return this
    val liveModels = response.liveOptions
    if (liveModels.isEmpty()) return this

    return map { group ->
        if (group.providerID != provider) {
            group
        } else {
            ModelCatalogGroup(
                id = group.id,
                name = group.name,
                providerID = group.providerID,
                models = liveModels,
                extraModels = group.extraModels,
            )
        }
    }
}

private object ModelCatalogParser {
    fun parseGroups(from: ModelsResponse): List<ModelCatalogGroup> {
        val groupValues = from.groups ?: return emptyList()

        return groupValues.mapIndexedNotNull { index, groupValue ->
            val groupDict = groupValue.objectValue ?: return@mapIndexedNotNull null

            val providerID = stringValue(from = groupDict["provider_id"])
            val name = stringValue(from = groupDict["name"]) ?: providerID ?: "Models"
            val models = parseModelOptions(
                from = groupDict["models"],
                providerID = providerID,
            )
            val extraModels = parseModelOptions(
                from = groupDict["extra_models"],
                providerID = providerID,
            )
            if (models.isEmpty()) return@mapIndexedNotNull null

            ModelCatalogGroup(
                id = providerID ?: "$name-$index",
                name = name,
                providerID = providerID,
                models = models,
                extraModels = extraModels,
            )
        }
    }

    fun parseModelOptions(
        from: JSONValue?,
        providerID: String?,
    ): List<ModelCatalogOption> {
        val items = from?.arrayValue ?: return emptyList()

        return items.mapNotNull { item ->
            val dict = item.objectValue ?: return@mapNotNull null

            val id = stringValue(from = dict["id"]).orEmpty()
            if (id.isEmpty()) return@mapNotNull null

            val displayName = stringValue(from = dict["name"])
                ?: stringValue(from = dict["label"])
                ?: id
            val optionProviderID = stringValue(from = dict["provider_id"]) ?: providerID

            ModelCatalogOption(
                id = id,
                displayName = displayName,
                providerID = optionProviderID,
            )
        }
    }

    private fun stringValue(from: JSONValue?): String? =
        from?.string?.trim()?.takeIf { it.isNotEmpty() }
}
