package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.net.hermes.cronRunFromJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Port of `CronJobList` as a Hermes host fills it (`HermesCronClient.swift`,
 * `CronJobList(hermesJobs:)`): one read of the Tasks list, whose running state and recent
 * runs are read off the jobs' `fire_claim`, `latest_execution` and `last_run_at`.
 */
data class HermesCronJobList(
    val jobs: List<CronJob>,
    /** The running jobs by id, each with the seconds it has been running. */
    val runningJobs: Map<String, Double>,
    /** Recent completions, which come with the list on a Hermes host. */
    val recentRuns: List<HermesCronRecentCompletion>,
)

/** Port of `CronRecentCompletion` as a Hermes host's list fills it: one job's latest finished run. */
data class HermesCronRecentCompletion(
    val jobId: String?,
    val name: String?,
    val status: String?,
    /** Epoch seconds. */
    val completedAt: Double,
)

/**
 * Port of `HermesCronRuns.Run` (`HermesCronClient.swift`): one row of `GET /api/cron/jobs/{id}/runs`
 * (#1042). Each row is the run's whole session, of which only the fields a row shows are
 * decoded. A run lasted from `started_at` to `ended_at`, epoch seconds, and is `is_active`
 * until it ends. Its cost is the provider's actual one when known, else the host's estimate.
 */
data class HermesCronRun(
    /** The run's session id, `cron_<job>_<YYYYmmdd_HHMMSS>`, which opens its output. */
    val id: String,
    val startedAt: Double? = null,
    val endedAt: Double? = null,
    val isRunning: Boolean = false,
    val model: String? = null,
    val estimatedCostUsd: Double? = null,
    val durationSeconds: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
) {
    companion object {
        /**
         * `{runs, limit}` as history rows: a row without an id can't be opened, so it is left
         * out; a page without its `runs` list is a failed read, not a Task that never ran.
         */
        @Throws(SerializationException::class)
        fun runs(page: ByteArray): List<HermesCronRun> {
            val obj = Json.parseToJsonElement(page.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw SerializationException("A runs page that is not an object")
            val rows = obj["runs"] as? JsonArray ?: throw SerializationException("A runs page without its list")
            return rows.mapNotNull { element -> (element as? JsonObject)?.let { cronRunFromJson(it) } }
        }
    }
}
