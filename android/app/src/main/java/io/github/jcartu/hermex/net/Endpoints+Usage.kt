package io.github.jcartu.hermex.net

/**
 * Usage-feature routes declared outside `Endpoints.kt` per the per-feature
 * extension convention (plan rule 9): factories returning [Endpoint.Custom].
 * Port of the `insights` and `providerQuota` cases in `Networking/Endpoints.swift`.
 */

/** `GET /api/insights?days=<n>` — one window's usage. */
fun insightsEndpoint(days: Int): Endpoint =
    Endpoint.Custom(
        fixedPath = "/api/insights",
        query = listOf("days" to days.toString()),
    )

/**
 * `GET /api/provider/quota?provider=<id>` — subscription limits or credits for
 * one provider. `refresh` bypasses the server's 45 s probe cache.
 */
fun providerQuotaEndpoint(provider: String, refresh: Boolean = false): Endpoint =
    Endpoint.Custom(
        fixedPath = "/api/provider/quota",
        query = buildList {
            add("provider" to provider)
            if (refresh) add("refresh" to "1")
        },
    )
