package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.InsightsResponse
import io.github.jcartu.hermex.model.ProviderQuotaResponse

/**
 * Usage-feature API calls declared outside `HermesApi.kt` per the per-feature
 * extension convention (plan rule 9).
 * Port of `insights(days:)` (`Networking/APIClient+ServerPanels.swift`) and
 * `providerQuota(provider:refresh:)` (`Networking/APIClient+ProviderQuota.swift`).
 */

/** One window's usage, from `GET /api/insights`. */
suspend fun HermesApi.insights(days: Int): InsightsResponse =
    get(insightsEndpoint(days))

/**
 * Reads one provider's quota. Pass `refresh: true` only from an explicit
 * user gesture — it bypasses the server's 45 s cache and re-probes the
 * upstream account, which can take several seconds.
 */
suspend fun HermesApi.providerQuota(provider: String, refresh: Boolean = false): ProviderQuotaResponse =
    get(providerQuotaEndpoint(provider, refresh))
