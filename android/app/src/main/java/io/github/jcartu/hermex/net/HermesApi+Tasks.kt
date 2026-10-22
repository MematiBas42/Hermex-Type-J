package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.CronRecentCompletionsResponse
import io.github.jcartu.hermex.model.CronRunDetailResponse
import io.github.jcartu.hermex.model.CronRunHistoryResponse

/**
 * Task-feature API calls declared outside `HermesApi.kt` per the per-feature
 * extension convention (plan rule 9).
 * Port of the cron calls in `Networking/APIClient+Cron.swift`.
 */

/** Each job's most recent completion. Unordered on the wire; see `CronRecentCompletion.newestFirst`. */
suspend fun HermesApi.cronRecent(): CronRecentCompletionsResponse =
    get(cronRecentEndpoint())

/**
 * One page of a job's run history, newest first.
 *
 * Callers advance `offset` by the `limit` they passed, not by
 * `response.runs.count`: see `CronRunHistoryResponse`.
 */
suspend fun HermesApi.cronHistory(
    jobId: String,
    offset: Int,
    limit: Int,
): CronRunHistoryResponse = get(cronHistoryEndpoint(jobId, offset, limit))

/**
 * One past run's full output. The GET twin of `runCron`'s POST on the same
 * path.
 */
suspend fun HermesApi.cronRunDetail(jobId: String, filename: String): CronRunDetailResponse =
    get(cronRunDetailEndpoint(jobId, filename))
