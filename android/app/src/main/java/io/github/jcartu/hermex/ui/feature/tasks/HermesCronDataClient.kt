package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDeliveryOptionsResponse
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.CronOutputResponse
import io.github.jcartu.hermex.model.CronRecentCompletion
import io.github.jcartu.hermex.model.CronRecentCompletionsResponse
import io.github.jcartu.hermex.model.CronRunDetailResponse
import io.github.jcartu.hermex.model.CronRunHistoryItem
import io.github.jcartu.hermex.model.CronRunHistoryResponse
import io.github.jcartu.hermex.model.CronRunUsage
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.HermesCronRun
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesCronClient
import io.github.jcartu.hermex.net.hermes.HermesCronRefusal as TransportHermesCronRefusal
import java.time.Instant
import kotlin.math.roundToLong

/**
 * The Tasks data source for a Hermes server. This is the Android route-level
 * adapter over [HermesCronClient]: the transport owns the exact `/api/cron/…`
 * wire shapes, while this class maps its Hermes-specific list and run models
 * onto the shared Tasks composables' [CronDataClient] contract.
 *
 * [profile] is the Profile selected by the Hermes entry point. A job's explicit
 * Profile always wins; the selected Profile is used when a create/editor call
 * does not name one.
 *
 * Port of `Networking/Hermes/HermesCronClient.swift` and its consumers in
 * `Features/Tasks/TasksViewModel.swift` / `TaskDetailViewModel.swift` (#1040,
 * #1041, #1042).
 */
class HermesCronDataClient internal constructor(
    private val client: HermesCronClient,
    private val profile: String?,
) : CronDataClient {

    constructor(connection: HermesConnection, profile: String? = null) :
        this(HermesCronClient(connection), profile)

    override val cronFeatures: CronFeatures
        get() = CronFeatures.hermes

    override suspend fun cronJobs(): CronJobList = translatedRefusal {
        val list = client.cronJobs()
        CronJobList(
            jobs = list.jobs,
            runningJobs = list.runningJobs,
            recentRuns = list.recentRuns.mapNotNull { completion ->
                completion.completedAt.toInstantOrNull()?.let { completedAt ->
                    CronRecentCompletion(
                        jobId = completion.jobId,
                        name = completion.name,
                        status = completion.status,
                        completedAt = completedAt,
                    )
                }
            },
        )
    }

    /** Hermes carries recent completions in [cronJobs], so its screens never call this feed. */
    override suspend fun cronRecent(): CronRecentCompletionsResponse = throw BotFailure.Unsupported

    override suspend fun cronDeliveryOptions(profile: String?): CronDeliveryOptionsResponse = translatedRefusal {
        client.cronDeliveryOptions(routedProfile(profile))
    }

    override suspend fun createCron(
        prompt: String,
        schedule: String,
        name: String?,
        deliver: String?,
        skills: List<String>,
        model: String?,
        provider: String?,
        profile: String?,
        toastNotifications: Boolean,
    ): CronMutationResponse = translatedRefusal {
        // A Hermes host has no toast-notification setting; deliberately do not
        // forward [toastNotifications].
        val result = client.createCron(
            prompt = prompt,
            schedule = schedule,
            name = name,
            deliver = deliver,
            skills = skills,
            model = model,
            provider = provider,
            profile = routedProfile(profile),
        )
        result.response.copy(warning = result.warning)
    }

    override suspend fun updateCron(
        jobId: String,
        prompt: String?,
        schedule: String?,
        name: String?,
        deliver: String?,
        skills: List<String>?,
        model: String?,
        provider: String?,
        profile: String?,
        toastNotifications: Boolean?,
    ): CronMutationResponse = translatedRefusal {
        client.updateCron(
            jobID = jobId,
            prompt = prompt,
            schedule = schedule,
            name = name,
            deliver = deliver,
            skills = skills,
            model = model,
            provider = provider,
            profile = routedProfile(profile),
        )
    }

    override suspend fun runCron(jobId: String, profile: String?): CronMutationResponse = translatedRefusal {
        client.runCron(jobId, routedProfile(profile))
    }

    override suspend fun pauseCron(
        jobId: String,
        profile: String?,
        reason: String?,
    ): CronMutationResponse = translatedRefusal {
        // Hermes pause has no reason field.
        client.pauseCron(jobId, routedProfile(profile))
    }

    override suspend fun resumeCron(jobId: String, profile: String?): CronMutationResponse = translatedRefusal {
        client.resumeCron(jobId, routedProfile(profile))
    }

    override suspend fun deleteCron(jobId: String, profile: String?): CronMutationResponse = translatedRefusal {
        client.deleteCron(jobId, routedProfile(profile))
    }

    /** Hermes run output is the run session's final reply, not a cron output file. */
    override suspend fun cronOutput(jobId: String, limit: Int?): CronOutputResponse = throw BotFailure.Unsupported

    /**
     * A Hermes host exposes one page: its newest 100 runs. It accepts every UI
     * page-size request, ignores [limit], and has no page after offset zero.
     */
    override suspend fun cronHistory(
        jobId: String,
        profile: String?,
        offset: Int,
        limit: Int,
    ): CronRunHistoryResponse {
        if (offset != 0) {
            return CronRunHistoryResponse(jobId = jobId, runs = emptyList(), total = null, offset = offset)
        }
        return translatedRefusal {
            CronRunHistoryResponse(
                jobId = jobId,
                runs = client.cronHistory(jobId, routedProfile(profile), offset).map(::historyItem),
                total = null,
                offset = offset,
            )
        }
    }

    override suspend fun cronRunDetail(
        jobId: String,
        profile: String?,
        filename: String,
    ): CronRunDetailResponse = translatedRefusal {
        CronRunDetailResponse(
            jobId = jobId,
            filename = filename,
            content = client.cronRunDetail(routedProfile(profile), filename),
            snippet = null,
        )
    }

    override suspend fun cronModelGroups(profile: String?): List<ModelCatalogGroup> = translatedRefusal {
        client.cronModelGroups(routedProfile(profile))
    }

    override suspend fun cronProfiles(): List<ProfileSummary> = translatedRefusal {
        client.cronProfiles()
    }

    override suspend fun cronSkills(profile: String?): List<SkillSummary> = translatedRefusal {
        client.cronSkills(routedProfile(profile))
    }

    private fun routedProfile(requested: String?): String? = requested ?: profile

    /**
     * Keep the Tasks layer's refusal type stable. `TaskDetailViewModel` uses it
     * to distinguish a definitive Run Now refusal from an unanswered trigger.
     */
    private suspend inline fun <T> translatedRefusal(crossinline operation: suspend () -> T): T = try {
        operation()
    } catch (error: TransportHermesCronRefusal) {
        throw HermesCronRefusal(error.detail)
    }

    private fun historyItem(run: HermesCronRun): CronRunHistoryItem = CronRunHistoryItem(
        filename = run.id,
        modified = run.endedAt?.toInstantOrNull(),
        startedAt = run.startedAt?.toInstantOrNull(),
        isRunning = run.isRunning,
        usage = CronRunUsage(
            model = run.model,
            estimatedCostUsd = run.estimatedCostUsd,
            durationSeconds = run.durationSeconds,
            inputTokens = run.inputTokens,
            outputTokens = run.outputTokens,
            totalTokens = run.totalTokens,
        ),
    )

    private fun Double.toInstantOrNull(): Instant? {
        if (!isFinite()) return null
        val wholeSeconds = toLong()
        val nanos = ((this - wholeSeconds) * 1_000_000_000.0).roundToLong()
        return runCatching { Instant.ofEpochSecond(wholeSeconds, nanos) }.getOrNull()
    }
}
