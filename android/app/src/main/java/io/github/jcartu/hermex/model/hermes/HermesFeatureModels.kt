package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.lossyBool
import io.github.jcartu.hermex.model.lossyDouble
import io.github.jcartu.hermex.model.lossyString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `SkillDetailResponse` as a Hermes host fills it (`HermesSkillsClient.swift`): SKILL.md with
 * its folder's other files, or one of those files, which can be binary or truncated.
 */
data class HermesSkillDetail(
    val name: String? = null,
    val content: String? = null,
    val linkedFiles: List<String>? = null,
    val isBinary: Boolean = false,
    val isTruncated: Boolean = false,
)

/**
 * `FileResponse` as a Hermes host fills it (`HermesWorkspaceFileClient.swift`): the file's
 * first 512 KiB as text, or none for a binary file; always a preview (`isPreviewOnly`).
 */
data class HermesWorkspaceFile(
    val content: String?,
    /** The workspace-relative path that was asked for. */
    val path: String,
    val name: String?,
    val language: String? = null,
    val size: Int? = null,
    /** Lines as an editor numbers them; null for a truncated file. */
    val lines: Int? = null,
    val isBinary: Boolean = false,
    val isTruncated: Boolean = false,
    val isPreviewOnly: Boolean = true,
)

/** Port of `WorkspaceFolderReadFailure`: a folder the host answered 200 `{entries: [], error}` for. */
class HermesWorkspaceFolderReadFailure(val code: String) : Exception() {
    override val message: String get() = "This folder could not be read ($code)."
}

/**
 * Port of `InsightsResponse` as a Hermes host's window fills it (`HermesInsightsClient.swift`,
 * `init(hermesUsage:models:counts:now:)`, #1074).
 */
data class HermesInsightsWindow(
    val periodDays: Int?,
    val totalSessions: Int,
    /** From `insights.get`, which counts only visible sessions among the newest 500, so the screen marks it approximate. */
    val totalMessages: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalTokens: Int,
    /** The host's estimate, in dollars. */
    val totalCost: Double,
    val totalCacheReadTokens: Int,
    val totalCacheHitPercent: Double?,
    val models: List<HermesInsightsModelBreakdown>,
    val dailyTokens: List<HermesInsightsDailyToken>,
)

/** Port of `InsightsModelBreakdown` for one model and billing provider row. */
data class HermesInsightsModelBreakdown(
    val model: String?,
    val provider: String?,
    val sessions: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val totalTokens: Int,
    val cost: Double,
    val cacheHitPercent: Double?,
    /** Whole percents of the rows' own sums. */
    val sessionShare: Int,
    val tokenShare: Int,
    val costShare: Int,
)

/** Port of `InsightsDailyToken`: one UTC day of the window's chart. */
data class HermesInsightsDailyToken(
    /** `yyyy-MM-dd`, UTC. */
    val date: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val sessions: Int,
    val cost: Double,
)

/**
 * Port of `ProviderQuotaResponse` (`Networking/APIClient+ProviderQuota.swift`), as hermex-push's
 * `GET /provider-quota` answers it in webui's envelope (#1237). Every field is optional so a
 * partial or future payload decodes instead of throwing.
 */
data class HermesProviderQuota(
    val ok: Boolean? = null,
    val provider: String? = null,
    val displayName: String? = null,
    val supported: Boolean? = null,
    /** `available`, `unsupported`, `unavailable`, `no_key` or `invalid_key`. */
    val status: String? = null,
    val label: String? = null,
    val message: String? = null,
    val quota: Credits? = null,
    val accountLimits: AccountLimits? = null,
) {
    /** The OpenRouter credits family. All three are null when the key has no spending cap. */
    data class Credits(val limitRemaining: Double? = null, val usage: Double? = null, val limit: Double? = null)

    /** The account-limits family (`openai-codex`, `anthropic`). */
    data class AccountLimits(
        val plan: String? = null,
        val title: String? = null,
        val available: Boolean? = null,
        val unavailableReason: String? = null,
        val fetchedAt: String? = null,
        val windows: List<Window>? = null,
        val details: List<String>? = null,
    )

    /** One rate-limit window; `remaining_percent` is null whenever upstream could not read `used_percent`. */
    data class Window(
        val label: String? = null,
        val usedPercent: Double? = null,
        val remainingPercent: Double? = null,
        val resetAt: String? = null,
        val detail: String? = null,
    )

    companion object {
        /** The envelope's snake_case keys, read as `APIClient` reads webui's. */
        fun from(obj: JsonObject): HermesProviderQuota = HermesProviderQuota(
            ok = lossyBool(obj["ok"]),
            provider = lossyString(obj["provider"]),
            displayName = lossyString(obj["display_name"]),
            supported = lossyBool(obj["supported"]),
            status = lossyString(obj["status"]),
            label = lossyString(obj["label"]),
            message = lossyString(obj["message"]),
            quota = (obj["quota"] as? JsonObject)?.let { quota ->
                Credits(
                    limitRemaining = lossyDouble(quota["limit_remaining"]),
                    usage = lossyDouble(quota["usage"]),
                    limit = lossyDouble(quota["limit"]),
                )
            },
            accountLimits = (obj["account_limits"] as? JsonObject)?.let { limits ->
                AccountLimits(
                    plan = lossyString(limits["plan"]),
                    title = lossyString(limits["title"]),
                    available = lossyBool(limits["available"]),
                    unavailableReason = lossyString(limits["unavailable_reason"]),
                    fetchedAt = lossyString(limits["fetched_at"]),
                    windows = (limits["windows"] as? JsonArray)?.mapNotNull { element ->
                        (element as? JsonObject)?.let { window ->
                            Window(
                                label = lossyString(window["label"]),
                                usedPercent = lossyDouble(window["used_percent"]),
                                remainingPercent = lossyDouble(window["remaining_percent"]),
                                resetAt = lossyString(window["reset_at"]),
                                detail = lossyString(window["detail"]),
                            )
                        }
                    },
                    details = (limits["details"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                )
            },
        )
    }
}

/**
 * Port of `HermesUpdateCheck` (`HermesUpdateClient.swift`): `GET /api/hermes/update/check`,
 * whether the install is behind, and whether the dashboard can update it in place
 * (`can_apply`, git installs only). A containerised host answers `managed-runtime`, whose
 * `update_command` is a sentence rather than a command.
 */
data class HermesUpdateCheck(
    val installMethod: String? = "git",
    /** The host's release with any build suffix, such as `0.21.5+6146.g46904a3`. */
    val currentVersion: String? = null,
    /** Commits behind: 0 when current, -1 for an unknown count, null when the check couldn't run. */
    val behind: Int?,
    val updateAvailable: Boolean,
    val canApply: Boolean = true,
    val updateCommand: String? = "hermes update",
    /** The host's guidance, such as why the check couldn't run. */
    val message: String? = null,
) {
    /** The command for the user to run on the host, or null when there is none to copy. */
    val hostCommand: String?
        get() = if (installMethod == "managed-runtime") null else updateCommand?.takeIf { it.isNotEmpty() }

    companion object {
        fun from(json: BotJSON): HermesUpdateCheck = HermesUpdateCheck(
            installMethod = json["install_method"].text, currentVersion = json["current_version"].text,
            behind = json["behind"].integer, updateAvailable = json["update_available"].flag ?: false,
            canApply = json["can_apply"].flag ?: false, updateCommand = json["update_command"].text,
            message = json["message"].text,
        )
    }
}

/** Port of `HermesUpdateStart`: `POST /api/hermes/update`'s answer. */
sealed class HermesUpdateStart {
    /** The update started, or was already running; `pid` is its process on the host. */
    data class Started(val pid: Int?) : HermesUpdateStart()

    /** The host can't update this install in place: its reason and the command to run there. */
    data class Refused(val error: String?, val message: String?, val command: String?) : HermesUpdateStart()

    /** The refused command for the user to copy, or null when there is none: a containerised host's is a sentence. */
    val hostCommand: String?
        get() = (this as? Refused)?.takeIf { it.error != "dashboard_update_managed_externally" }
            ?.command?.takeIf { it.isNotEmpty() }
}

/**
 * Port of `HermesUpdateReceipt`: the summary of an update receipt, the host's durable record
 * of its latest `hermes update`, written when the run finishes, including refused and failed runs.
 */
data class HermesUpdateReceipt(
    val outcome: Outcome?,
    /** When the run started, by the host's clock; tells one run's receipt from the next. */
    val startedAt: String?,
    /** The release the update installed; null when the host couldn't read it. */
    val postVersion: String? = null,
) {
    sealed class Outcome {
        object Running : Outcome()
        object Success : Outcome()
        object Partial : Outcome()
        object Failed : Outcome()
        object Refused : Outcome()

        /** A value this build doesn't know, read as a failure. */
        data class Other(val value: String) : Outcome()

        companion object {
            fun from(value: String): Outcome = when (value) {
                "running" -> Running
                "success" -> Success
                "partial" -> Partial
                "failed" -> Failed
                "refused" -> Refused
                else -> Other(value)
            }
        }
    }

    companion object {
        /** Reads the compact summary (`post_version`) or the full receipt (`post_update.version`); null for anything that isn't an object. */
        fun from(json: BotJSON): HermesUpdateReceipt? {
            if (!json.isObject) return null
            return HermesUpdateReceipt(
                outcome = json["outcome"].text?.let { Outcome.from(it) },
                startedAt = json["started_at"].text,
                postVersion = json["post_version"].text ?: json["post_update"]["version"].text,
            )
        }
    }
}

/**
 * Port of `HermesUpdateStatus`: `GET /api/actions/hermes-update/status`. After the dashboard
 * restarts it no longer tracks the process: `running` is false, `pid` is null, and `exit_code`
 * comes from the log's completion marker or from the latest receipt.
 */
data class HermesUpdateStatus(
    val running: Boolean,
    val exitCode: Int? = null,
    /** The update process while this dashboard still tracks it; null after a restart. */
    val pid: Int? = null,
    /** The tail of the host's update log. */
    val lines: List<String> = emptyList(),
    val receipt: HermesUpdateReceipt? = null,
) {
    companion object {
        fun from(json: BotJSON): HermesUpdateStatus = HermesUpdateStatus(
            running = json["running"].flag ?: false, exitCode = json["exit_code"].integer, pid = json["pid"].integer,
            lines = json["lines"].list?.mapNotNull { it.text } ?: emptyList(), receipt = HermesUpdateReceipt.from(json["receipt"]),
        )
    }
}

/** Port of `HermesInsightsUnavailable`: the host could not read the Profile's session store (#1074). */
class HermesInsightsUnavailable : Exception() {
    override val message: String
        get() = "Hermes couldn’t read this Profile’s session history. Run hermes doctor on the host, then try again."
}
