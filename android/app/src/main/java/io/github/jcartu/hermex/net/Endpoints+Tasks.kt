package io.github.jcartu.hermex.net

/**
 * Task-feature routes declared outside `Endpoints.kt` per the per-feature
 * extension convention (plan rule 9): factories returning [Endpoint.Custom]
 * so the sealed hierarchy's exhaustive `when`s stay untouched.
 * Port of the cron cases in `Networking/Endpoints.swift`.
 */

/** `GET /api/crons/recent` — every job's latest completion, for the Tasks list's recent-runs group. */
fun cronRecentEndpoint(): Endpoint = Endpoint.Custom(fixedPath = "/api/crons/recent")

/** `GET /api/crons/history?job_id=&offset=&limit=` — one page of a task's run history, newest first. */
fun cronHistoryEndpoint(jobId: String, offset: Int, limit: Int): Endpoint =
    Endpoint.Custom(
        fixedPath = "/api/crons/history",
        query = listOf(
            "job_id" to jobId,
            "offset" to offset.toString(),
            "limit" to limit.toString(),
        ),
    )

/** `GET /api/crons/run?job_id=&filename=` — one past run's full output. */
fun cronRunDetailEndpoint(jobId: String, filename: String): Endpoint =
    Endpoint.Custom(
        fixedPath = "/api/crons/run",
        query = listOf(
            "job_id" to jobId,
            "filename" to filename,
        ),
    )
