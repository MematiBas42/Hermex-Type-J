package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDeliveryOptionsResponse
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.CronOutputResponse
import io.github.jcartu.hermex.model.CronRecentCompletion
import io.github.jcartu.hermex.model.CronRecentCompletionsResponse
import io.github.jcartu.hermex.model.CronRunDetailResponse
import io.github.jcartu.hermex.model.CronRunHistoryResponse
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.cronHistory
import io.github.jcartu.hermex.net.cronRecent
import io.github.jcartu.hermex.net.cronRunDetail
import java.time.Duration
import java.time.Instant

/**
 * What the Tasks screens call, so one set of screens runs on either kind of
 * server: a webui server's `/api/crons` routes ([WebuiCronDataClient]) or a Hermes
 * host's `/api/cron` routes ([HermesCronDataClient], #1040). Mutations and run
 * reads name the job's Profile, which a Hermes host routes by and webui ignores;
 * [CronFeatures] says which parts of the screens the server backs.
 * Port of `Networking/APIClient+Cron.swift` `CronDataClient`.
 */
interface CronDataClient {
    val cronFeatures: CronFeatures

    /** The Tasks list and the running jobs, read together. */
    suspend fun cronJobs(): CronJobList

    /** Every job's latest finished run. */
    suspend fun cronRecent(): CronRecentCompletionsResponse

    /** Delivery targets, for one Profile where the server scopes them. */
    suspend fun cronDeliveryOptions(profile: String?): CronDeliveryOptionsResponse

    suspend fun createCron(
        prompt: String,
        schedule: String,
        name: String?,
        deliver: String?,
        skills: List<String>,
        model: String?,
        provider: String?,
        profile: String?,
        toastNotifications: Boolean,
    ): CronMutationResponse

    suspend fun updateCron(
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
    ): CronMutationResponse

    suspend fun runCron(jobId: String, profile: String?): CronMutationResponse

    suspend fun pauseCron(jobId: String, profile: String?, reason: String?): CronMutationResponse

    suspend fun resumeCron(jobId: String, profile: String?): CronMutationResponse

    suspend fun deleteCron(jobId: String, profile: String?): CronMutationResponse

    /**
     * The latest runs' output, newest first. Only webui is asked: a Hermes host's
     * latest output is its newest finished run's reply (`cronRunDetail`, #1042).
     */
    suspend fun cronOutput(jobId: String, limit: Int?): CronOutputResponse

    /**
     * One page of the Task's runs, newest first. A server with a single page
     * (a Hermes host's newest 100) answers it for any `limit`, with no `total`,
     * so there is no next page.
     */
    suspend fun cronHistory(jobId: String, profile: String?, offset: Int, limit: Int): CronRunHistoryResponse

    /** One run's full output; `filename` is the run's `CronRunHistoryItem.filename`. */
    suspend fun cronRunDetail(jobId: String, profile: String?, filename: String): CronRunDetailResponse

    /**
     * The Task editor's sources. `profile` scopes the models and skills where
     * the server keeps them per Profile.
     */
    suspend fun cronModelGroups(profile: String?): List<ModelCatalogGroup>

    suspend fun cronProfiles(): List<ProfileSummary>

    suspend fun cronSkills(profile: String?): List<SkillSummary>
}

/**
 * The parts of Tasks one server backs. A webui server backs all of them. A
 * Hermes host (#1040) keeps every Task in a Profile, reports running state and
 * recent runs in its list, runs a Task on demand only before it replies
 * (#1041), keeps each run as a session (#1042), and has no toast setting.
 * Port of `Networking/APIClient+Cron.swift` `CronFeatures`.
 */
data class CronFeatures(
    /** Each Task belongs to one Profile: rows name it, the editor's sources follow it. */
    val isProfileScoped: Boolean,
    val hasToastNotifications: Boolean,
    /** `/api/crons/recent`. Without it, recent runs come with the list. */
    val hasRecentRunsFeed: Boolean,
    /**
     * Run Now answers once the run has finished rather than when it starts: a
     * Hermes host's trigger (#1041). The screens follow the run on the list
     * until the host's outcome.
     */
    val runNowWaitsForRun: Boolean,
    /** Each run is the session it runs in, listed from the moment it starts (a Hermes host, #1042). */
    val runsAreSessions: Boolean,
) {
    /** Whether the screens offer Run Now for [job]: everywhere but a completed Task on a Hermes host. */
    fun offersRunNow(job: CronJob): Boolean =
        !(runNowWaitsForRun && job.state == "completed")

    /** Whether Run Now also resumes [job], which the screens ask about first. */
    fun runNowResumes(job: CronJob): Boolean =
        runNowWaitsForRun && job.enabled == false

    companion object {
        val webui = CronFeatures(
            isProfileScoped = false,
            hasToastNotifications = true,
            hasRecentRunsFeed = true,
            runNowWaitsForRun = false,
            runsAreSessions = false,
        )

        val hermes = CronFeatures(
            isProfileScoped = true,
            hasToastNotifications = false,
            hasRecentRunsFeed = false,
            runNowWaitsForRun = true,
            runsAreSessions = true,
        )
    }
}

/** One read of the Tasks list. Port of `Networking/APIClient+Cron.swift` `CronJobList`. */
data class CronJobList(
    val jobs: List<CronJob>,
    /** The running jobs by id, each with the seconds it has been running. */
    val runningJobs: Map<String, Double>,
    /** Recent completions, when they come with the list (a Hermes host); null when the server has its own feed. */
    val recentRuns: List<CronRecentCompletion>? = null,
) {
    companion object {
        /**
         * A Hermes host's list (#1040), whose running state and recent runs are
         * read off the jobs. A job is running while it holds a fire claim or its
         * latest execution is still claimed or running; it has run for the time
         * since its claim, or 0 without one. Each job that has run contributes
         * its last run, as webui's recent feed does.
         * Port of `CronJobList(hermesJobs:now:)` in `HermesCronClient.swift`.
         */
        fun hermesJobs(jobs: List<CronJob>, now: Instant = Instant.now()): CronJobList {
            val runningJobs = buildMap {
                for (job in jobs) {
                    val executing = job.latestExecutionStatus
                        ?.let { it == "claimed" || it == "running" }
                        ?: false
                    val id = job.jobId
                    if (id == null || (job.fireClaim == null && !executing)) continue
                    val claimedAt = job.fireClaim?.at
                    put(id, claimedAt?.let { maxOf(0.0, Duration.between(it, now).toMillis() / 1000.0) } ?: 0.0)
                }
            }
            val recentRuns = jobs.mapNotNull { job ->
                job.lastRunAt?.date?.let { completedAt ->
                    CronRecentCompletion(
                        jobId = job.jobId,
                        name = job.name,
                        status = job.lastStatus,
                        completedAt = completedAt,
                    )
                }
            }
            return CronJobList(jobs = jobs, runningJobs = runningJobs, recentRuns = recentRuns)
        }
    }
}

/**
 * A Tasks or Skills request a Hermes host refused with its reason (`{detail}`).
 * Port of `Networking/Hermes/HermesCronClient.swift` `HermesCronRefusal`; the
 * Wave-2 Hermes cron client reports its refusals as this type.
 */
class HermesCronRefusal(val detail: String) : Exception("The server rejected the request: $detail")

/**
 * A webui server's Tasks: `/api/crons` routes on [HermesApi]. Port of
 * `extension APIClient: CronDataClient` in `Networking/APIClient+Cron.swift`.
 */
class WebuiCronDataClient(private val api: HermesApi) : CronDataClient {

    override val cronFeatures: CronFeatures
        get() = CronFeatures.webui

    override suspend fun cronJobs(): CronJobList {
        val jobs = api.crons()
        val status = api.cronStatus()
        return CronJobList(
            jobs = jobs.jobs ?: emptyList(),
            runningJobs = status.runningJobs ?: emptyMap(),
            recentRuns = null,
        )
    }

    // webui lists the same delivery targets, models and skills for every Profile,
    // and finds a job by its id alone.

    override suspend fun cronRecent(): CronRecentCompletionsResponse = api.cronRecent()

    override suspend fun cronDeliveryOptions(profile: String?): CronDeliveryOptionsResponse =
        api.cronDeliveryOptions()

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
    ): CronMutationResponse = api.createCron(
        prompt = prompt,
        schedule = schedule,
        name = name,
        deliver = deliver,
        skills = skills,
        model = model,
        provider = provider,
        profile = profile,
        toastNotifications = toastNotifications,
    )

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
    ): CronMutationResponse = api.updateCron(
        jobId = jobId,
        prompt = prompt,
        schedule = schedule,
        name = name,
        deliver = deliver,
        skills = skills,
        model = model,
        provider = provider,
        profile = profile,
        toastNotifications = toastNotifications,
    )

    override suspend fun runCron(jobId: String, profile: String?): CronMutationResponse =
        api.runCron(jobId)

    override suspend fun pauseCron(jobId: String, profile: String?, reason: String?): CronMutationResponse =
        api.pauseCron(jobId, reason)

    override suspend fun resumeCron(jobId: String, profile: String?): CronMutationResponse =
        api.resumeCron(jobId)

    override suspend fun deleteCron(jobId: String, profile: String?): CronMutationResponse =
        api.deleteCron(jobId)

    override suspend fun cronOutput(jobId: String, limit: Int?): CronOutputResponse =
        api.cronOutput(jobId, limit)

    override suspend fun cronHistory(jobId: String, profile: String?, offset: Int, limit: Int): CronRunHistoryResponse =
        api.cronHistory(jobId, offset, limit)

    override suspend fun cronRunDetail(jobId: String, profile: String?, filename: String): CronRunDetailResponse =
        api.cronRunDetail(jobId, filename)

    override suspend fun cronModelGroups(profile: String?): List<ModelCatalogGroup> =
        api.models().catalogGroups

    override suspend fun cronProfiles(): List<ProfileSummary> =
        api.profiles().profiles ?: emptyList()

    override suspend fun cronSkills(profile: String?): List<SkillSummary> =
        api.skills().skills ?: emptyList()
}
