package io.github.jcartu.hermex.ui.feature.insights

import io.github.jcartu.hermex.model.InsightsDailyToken
import io.github.jcartu.hermex.model.InsightsModelBreakdown
import io.github.jcartu.hermex.model.InsightsResponse
import io.github.jcartu.hermex.model.ProviderAccountLimits
import io.github.jcartu.hermex.model.ProviderQuotaCredits
import io.github.jcartu.hermex.model.ProviderQuotaResponse
import io.github.jcartu.hermex.model.ProviderQuotaWindow
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.HermesInsightsWindow
import io.github.jcartu.hermex.model.hermes.HermesProviderQuota
import io.github.jcartu.hermex.net.hermes.HermesInsightsClient

/**
 * Direct-Hermes Usage adapter (#1074/#1237). Port of
 * `Networking/Hermes/HermesInsightsClient.swift`: maps the transport's typed
 * Hermes window/quota models into the Wave 1 Usage screen's tolerant models.
 */
class HermesInsightsDataClient(
    private val client: HermesInsightsClient,
) : InsightsDataClient {
    override val insightsFeatures: InsightsFeatures = InsightsFeatures.hermes

    override suspend fun sessions(): SessionsResponse = throw BotFailure.Unsupported

    override suspend fun insights(days: Int): InsightsResponse = client.insights(days).toInsightsResponse()

    override suspend fun providers(): ProvidersResponse = client.providers()

    override suspend fun providerQuota(provider: String, refresh: Boolean): ProviderQuotaResponse =
        client.providerQuota(provider, refresh).toProviderQuotaResponse()
}

/** Exact field mapping used by the shared charts and totals. */
internal fun HermesInsightsWindow.toInsightsResponse(): InsightsResponse = InsightsResponse(
    periodDays = periodDays,
    totalSessions = totalSessions,
    totalMessages = totalMessages,
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    totalTokens = totalTokens,
    totalCost = totalCost,
    totalCacheReadTokens = totalCacheReadTokens,
    totalCacheHitPercent = totalCacheHitPercent,
    models = models.map { row ->
        InsightsModelBreakdown(
            model = row.model,
            provider = row.provider,
            sessions = row.sessions,
            inputTokens = row.inputTokens,
            outputTokens = row.outputTokens,
            cacheReadTokens = row.cacheReadTokens,
            totalTokens = row.totalTokens,
            cost = row.cost,
            cacheHitPercent = row.cacheHitPercent,
            sessionShare = row.sessionShare,
            tokenShare = row.tokenShare,
            costShare = row.costShare,
        )
    },
    dailyTokens = dailyTokens.map { day ->
        InsightsDailyToken(
            date = day.date,
            inputTokens = day.inputTokens,
            outputTokens = day.outputTokens,
            cacheReadTokens = day.cacheReadTokens,
            sessions = day.sessions,
            cost = day.cost,
        )
    },
    activityByDay = emptyList(),
    activityByHour = emptyList(),
)

/** Companion `/provider-quota` uses the same envelope as webui's quota route. */
internal fun HermesProviderQuota.toProviderQuotaResponse(): ProviderQuotaResponse = ProviderQuotaResponse(
    ok = ok,
    provider = provider,
    displayName = displayName,
    supported = supported,
    status = status,
    label = label,
    message = message,
    quota = quota?.let {
        ProviderQuotaCredits(
            limitRemaining = it.limitRemaining,
            usage = it.usage,
            limit = it.limit,
        )
    },
    accountLimits = accountLimits?.let { limits ->
        ProviderAccountLimits(
            plan = limits.plan,
            title = limits.title,
            available = limits.available,
            unavailableReason = limits.unavailableReason,
            fetchedAt = limits.fetchedAt,
            windows = limits.windows?.map { window ->
                ProviderQuotaWindow(
                    label = window.label,
                    usedPercent = window.usedPercent,
                    remainingPercent = window.remainingPercent,
                    resetAt = window.resetAt,
                    detail = window.detail,
                )
            },
            details = limits.details,
        )
    },
)
