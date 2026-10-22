package io.github.jcartu.hermex.ui.feature.insights

import io.github.jcartu.hermex.model.InsightsResponse
import io.github.jcartu.hermex.model.ProviderQuotaResponse
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.insights
import io.github.jcartu.hermex.net.providerQuota
import java.time.Instant

/**
 * What the Usage screen calls: a webui server's `/api/insights` and
 * `/api/provider/quota` ([WebuiInsightsDataClient]), or a Hermes host's
 * analytics mapped by [HermesInsightsDataClient] into the same shapes (#1074).
 * Port of `Features/Insights/InsightsViewModel.swift` `InsightsDataClient`.
 */
interface InsightsDataClient {
    /** What this server's insights report, which decides what the Usage screen offers. */
    val insightsFeatures: InsightsFeatures

    suspend fun sessions(): SessionsResponse

    suspend fun insights(days: Int): InsightsResponse

    suspend fun providers(): ProvidersResponse

    suspend fun providerQuota(provider: String, refresh: Boolean): ProviderQuotaResponse
}

/** What one server's insights can report. Port of `InsightsFeatures`. */
data class InsightsFeatures(
    /** The windows the picker offers. Today is the only hourly one. */
    val timeframes: List<AnalyticsTimeframe>,
    /** Message counts cover only part of the window, so the totals mark them "≈". */
    val approximatesMessages: Boolean,
    /** A failed insights read is rebuilt from `/api/sessions`; without it, the failure is the screen's error. */
    val fallsBackToSessions: Boolean,
) {
    companion object {
        val webui = InsightsFeatures(
            timeframes = AnalyticsTimeframe.entries,
            approximatesMessages = false,
            fallsBackToSessions = true,
        )

        /** A Hermes host (#1074) reports no hours, counts messages over at most its newest 500 visible sessions. */
        val hermes = InsightsFeatures(
            timeframes = listOf(
                AnalyticsTimeframe.LAST_7_DAYS,
                AnalyticsTimeframe.LAST_30_DAYS,
                AnalyticsTimeframe.LAST_90_DAYS,
            ),
            approximatesMessages = true,
            fallsBackToSessions = false,
        )
    }
}

/**
 * The windows the Usage screen offers. Each maps to a `days` value the insights
 * handler clamps to 1-365, and to a matching local filter for the session
 * metadata fallback. Port of `AnalyticsTimeframe`.
 */
enum class AnalyticsTimeframe {
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS;

    val title: String
        get() = when (this) {
            // "Today", not "24h": the handler's days=1 means midnight-to-now.
            TODAY -> "Today"
            LAST_7_DAYS -> "7 days"
            LAST_30_DAYS -> "30 days"
            LAST_90_DAYS -> "90 days"
        }

    val serverDays: Int
        get() = when (this) {
            TODAY -> 1
            LAST_7_DAYS -> 7
            LAST_30_DAYS -> 30
            LAST_90_DAYS -> 90
        }

    /** True when the window plots hours rather than days. */
    val isHourly: Boolean
        get() = this == TODAY

    fun contains(session: SessionSummary, now: Instant = Instant.now()): Boolean {
        val timestamp = session.lastMessageAt ?: session.updatedAt ?: session.createdAt ?: return false
        val sessionTime = Instant.ofEpochSecond(timestamp.toLong())
        return when (this) {
            TODAY -> {
                val nowDay = java.time.LocalDate.now()
                val sessionDay = java.time.LocalDate.ofInstant(sessionTime, java.time.ZoneId.systemDefault())
                sessionDay == nowDay
            }
            LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS -> {
                val earliest = now.minusSeconds(serverDays * 86_400L)
                sessionTime >= earliest && sessionTime <= now
            }
        }
    }
}

/** Port of `SessionUsageAnalytics` — aggregates over the fallback's filtered sessions. */
data class SessionUsageAnalytics(
    val sessions: List<SessionSummary>,
) {
    val totalInputTokens: Int
        get() = sessions.sumOf { it.inputTokens ?: 0 }

    val totalOutputTokens: Int
        get() = sessions.sumOf { it.outputTokens ?: 0 }

    val totalTokens: Int
        get() = totalInputTokens + totalOutputTokens

    val totalMessages: Int
        get() = sessions.sumOf { it.messageCount ?: 0 }

    val estimatedCost: Double
        get() = sessions.sumOf { it.estimatedCost ?: 0.0 }

    val sessionCount: Int
        get() = sessions.size

    val topSessions: List<SessionSummary>
        get() = sessions.sortedWith { left, right ->
            val leftTotal = (left.inputTokens ?: 0) + (left.outputTokens ?: 0)
            val rightTotal = (right.inputTokens ?: 0) + (right.outputTokens ?: 0)
            rightTotal.compareTo(leftTotal)
        }
}

/** A webui server's Usage: `/api/insights` and `/api/provider/quota` on [HermesApi]. */
class WebuiInsightsDataClient(private val api: HermesApi) : InsightsDataClient {

    override val insightsFeatures: InsightsFeatures
        get() = InsightsFeatures.webui

    override suspend fun sessions(): SessionsResponse = api.sessions()

    override suspend fun insights(days: Int): InsightsResponse = api.insights(days)

    override suspend fun providers(): ProvidersResponse = api.providers()

    override suspend fun providerQuota(provider: String, refresh: Boolean): ProviderQuotaResponse =
        api.providerQuota(provider, refresh)
}
