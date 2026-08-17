package io.github.jcartu.hermex.ui.feature.insights

import io.github.jcartu.hermex.model.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure aggregation logic for the Insights screen, ported from
 * `Features/Insights/InsightsViewModel.swift` (AnalyticsTimeframe +
 * SessionUsageAnalytics). The Android surface computes analytics from local
 * session metadata (the Swift `.local`/`.localFallback` data source) — the
 * server `/api/insights` endpoint is not part of the ported API surface.
 */

/** Port of AnalyticsTimeframe. */
enum class AnalyticsTimeframe(val title: String) {
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
    ALL_TIME("All Time"),
    ;

    /** Port of AnalyticsTimeframe.contains(_:now:calendar:). */
    fun contains(session: SessionSummary, nowMillis: Long, zone: ZoneId): Boolean {
        if (this == ALL_TIME) return true
        val timestamp = session.analyticsTimestamp ?: return false
        val sessionInstant = Instant.ofEpochMilli((timestamp * 1000).toLong())
        val now = Instant.ofEpochMilli(nowMillis)
        return when (this) {
            TODAY ->
                sessionInstant.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
            LAST_7_DAYS ->
                !sessionInstant.isBefore(now.minus(7, ChronoUnit.DAYS)) && !sessionInstant.isAfter(now)
            LAST_30_DAYS ->
                !sessionInstant.isBefore(now.minus(30, ChronoUnit.DAYS)) && !sessionInstant.isAfter(now)
            ALL_TIME -> true
        }
    }
}

/** Port of `SessionSummary.analyticsTimestamp` (epoch seconds). */
val SessionSummary.analyticsTimestamp: Double?
    get() = lastMessageAt ?: updatedAt ?: createdAt

/** Port of SessionUsageAnalytics: aggregates over already-filtered sessions. */
data class SessionUsageAnalytics(val sessions: List<SessionSummary>) {
    val totalInputTokens: Long = sessions.sumOf { (it.inputTokens ?: 0).toLong() }
    val totalOutputTokens: Long = sessions.sumOf { (it.outputTokens ?: 0).toLong() }
    val totalTokens: Long = totalInputTokens + totalOutputTokens
    val totalMessages: Long = sessions.sumOf { (it.messageCount ?: 0).toLong() }
    val estimatedCost: Double = sessions.sumOf { it.estimatedCost ?: 0.0 }
    val sessionCount: Int = sessions.size

    /** Sessions by total tokens, descending. */
    val topSessions: List<SessionSummary> =
        sessions.sortedByDescending { (it.inputTokens ?: 0).toLong() + (it.outputTokens ?: 0).toLong() }
}

/** Per-model usage aggregated from session metadata. */
data class ModelUsage(
    val model: String,
    val sessionCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cost: Double,
    /** 0–100 share of all tokens in the filtered set. */
    val tokenSharePercent: Int,
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens
}

/** Groups sessions by model, sorted by total tokens descending. */
fun modelUsageBreakdown(sessions: List<SessionSummary>): List<ModelUsage> {
    if (sessions.isEmpty()) return emptyList()
    val groups = sessions.groupBy { it.model?.takeIf { m -> m.isNotBlank() } ?: "Unknown Model" }
    val grandTotal = sessions.sumOf { (it.inputTokens ?: 0).toLong() + (it.outputTokens ?: 0).toLong() }
    return groups
        .map { (model, group) ->
            val input = group.sumOf { (it.inputTokens ?: 0).toLong() }
            val output = group.sumOf { (it.outputTokens ?: 0).toLong() }
            ModelUsage(
                model = model,
                sessionCount = group.size,
                inputTokens = input,
                outputTokens = output,
                cost = group.sumOf { it.estimatedCost ?: 0.0 },
                tokenSharePercent = if (grandTotal <= 0) {
                    0
                } else {
                    Math.round((input + output) * 100.0 / grandTotal).toInt()
                },
            )
        }
        .sortedByDescending { it.totalTokens }
}

/** One bar of the daily-tokens chart. */
data class DailyUsage(
    val date: LocalDate,
    val totalTokens: Long,
    val sessionCount: Int,
)

/**
 * Buckets session token usage by calendar day for the trailing [days] days
 * (oldest first, ending today, zero-filled) — the Canvas bar chart's data.
 */
fun dailyUsage(
    sessions: List<SessionSummary>,
    days: Int,
    nowMillis: Long,
    zone: ZoneId,
): List<DailyUsage> {
    require(days >= 1) { "days must be >= 1" }
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val firstDay = today.minusDays((days - 1).toLong())
    val tokensByDay = HashMap<LocalDate, Long>()
    val sessionsByDay = HashMap<LocalDate, Int>()
    for (session in sessions) {
        val timestamp = session.analyticsTimestamp ?: continue
        val day = Instant.ofEpochMilli((timestamp * 1000).toLong()).atZone(zone).toLocalDate()
        if (day.isBefore(firstDay) || day.isAfter(today)) continue
        val tokens = (session.inputTokens ?: 0).toLong() + (session.outputTokens ?: 0).toLong()
        tokensByDay[day] = (tokensByDay[day] ?: 0L) + tokens
        sessionsByDay[day] = (sessionsByDay[day] ?: 0) + 1
    }
    return (0 until days).map { offset ->
        val day = firstDay.plusDays(offset.toLong())
        DailyUsage(
            date = day,
            totalTokens = tokensByDay[day] ?: 0L,
            sessionCount = sessionsByDay[day] ?: 0,
        )
    }
}

/** Thousands-separated token count ("12,345"), like the iOS NumberFormatter rows. */
fun formatTokenCount(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

/** Compact token count for chart captions ("1.2M", "45K"). */
fun formatCompactTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
