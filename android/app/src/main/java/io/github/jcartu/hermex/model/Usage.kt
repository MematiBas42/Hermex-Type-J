package io.github.jcartu.hermex.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The figure the Usage hero and chart plot over a multi-day window.
 *
 * The 24-hour window has neither: `activity_by_hour` reports session counts and
 * nothing else, so that window ignores the metric entirely.
 * Port of `Features/Insights/UsageChartData.swift`.
 */
enum class UsageMetric {
    COST,
    TOKENS;

    val title: String
        get() = when (this) {
            COST -> "Cost"
            TOKENS -> "Tokens"
        }
}

/**
 * What one chart bar knows about itself.
 *
 * A `day` bucket carries a full `daily_tokens` row. An `hour` bucket comes from
 * `activity_by_hour`, which reports session counts only — there is deliberately
 * nowhere to put tokens or cost on it.
 */
sealed interface UsageBucketDetail {
    data class Day(
        val input: Int,
        val output: Int,
        val cacheRead: Int,
        val sessions: Int,
        val cost: Double,
    ) : UsageBucketDetail

    data class Hour(val sessions: Int) : UsageBucketDetail
}

/** One bar in the Usage chart, in window order. */
data class UsageBucket(
    /**
     * Position in the window. Stable for as long as a window is on screen, which
     * keeps chart identity from churning while values refresh.
     */
    val id: Int,
    /** Short axis label, localized ("Aug 28", "9 AM"). */
    val label: String,
    val detail: UsageBucketDetail,
) {
    val sessions: Int
        get() = when (detail) {
            is UsageBucketDetail.Day -> detail.sessions
            is UsageBucketDetail.Hour -> detail.sessions
        }

    /**
     * Input plus output, matching the server's `total_tokens`. Cache reads are
     * deliberately not folded in: the server keeps them out of that field, and
     * the chart stacks them as their own layer, so the two must not be conflated.
     */
    val processedTokens: Int
        get() = when (detail) {
            is UsageBucketDetail.Day -> detail.input + detail.output
            is UsageBucketDetail.Hour -> 0
        }

    val cacheReadTokens: Int
        get() = when (detail) {
            is UsageBucketDetail.Day -> detail.cacheRead
            is UsageBucketDetail.Hour -> 0
        }

    val cost: Double
        get() = when (detail) {
            is UsageBucketDetail.Day -> detail.cost
            is UsageBucketDetail.Hour -> 0.0
        }

    /**
     * A bucket counts as active when it moved tokens or ran sessions. Cost is
     * excluded on purpose: a server that prices everything at zero still has
     * activity worth charting.
     */
    val hasActivity: Boolean
        get() = processedTokens > 0 || cacheReadTokens > 0 || sessions > 0
}

/** A stack layer, or a whole bar when the metric has nothing to stack. */
enum class UsageChartSegment {
    INPUT,
    OUTPUT,
    CACHE_READ,
    COST,
    SESSIONS;

    val title: String
        get() = when (this) {
            INPUT -> "Input"
            OUTPUT -> "Output"
            CACHE_READ -> "Cache read"
            COST -> "Cost"
            SESSIONS -> "Sessions"
        }
}

/** The three lines of the hero figure above the chart. */
data class UsageHeroFigure(
    val label: String,
    val value: String,
    val caption: String,
) {
    /** The hero while the user drags across the chart: the selected bucket's own figures. */
    companion object {
        fun selection(bucket: UsageBucket, metric: UsageMetric): UsageHeroFigure =
            when (val detail = bucket.detail) {
                is UsageBucketDetail.Day -> {
                    // A token bar stacks cache reads on top of processed tokens, so the
                    // readout names both. Letting the bar's height stand for one figure
                    // that excludes the other is how a chart starts lying.
                    val sessionsText = "${detail.sessions} sessions"
                    val cachedText = if (detail.cacheRead > 0) "${usageFormattedTokens(detail.cacheRead)} cached" else null

                    when (metric) {
                        UsageMetric.TOKENS -> UsageHeroFigure(
                            label = bucket.label,
                            value = usageFormattedTokens(bucket.processedTokens),
                            caption = listOfNotNull(
                                detail.cost.takeIf { it > 0 }?.let { usageFormattedCost(it) },
                                cachedText,
                                sessionsText,
                            ).joinToString(" · "),
                        )
                        UsageMetric.COST -> {
                            val tokensText = "${usageFormattedTokens(bucket.processedTokens)} tokens"
                            UsageHeroFigure(
                                label = bucket.label,
                                value = usageFormattedCost(detail.cost),
                                caption = listOfNotNull(tokensText, cachedText, sessionsText)
                                    .joinToString(" · "),
                            )
                        }
                    }
                }
                is UsageBucketDetail.Hour -> UsageHeroFigure(
                    label = bucket.label,
                    value = detail.sessions.toString(),
                    caption = "sessions started or updated in this hour",
                )
            }
    }

    /** What a screen reader reads for the bar, since the visual hero only appears while dragging. */
    fun accessibilityValue(bucket: UsageBucket): String =
        when (val detail = bucket.detail) {
            is UsageBucketDetail.Day -> {
                val parts = mutableListOf<String>()
                parts += "${usageFormattedTokens(bucket.processedTokens)} tokens"
                if (detail.cacheRead > 0) parts += "${usageFormattedTokens(detail.cacheRead)} cached"
                parts += "${detail.sessions} sessions"
                if (detail.cost > 0) parts += usageFormattedCost(detail.cost)
                parts.joinToString(", ")
            }
            is UsageBucketDetail.Hour -> "${detail.sessions} sessions"
        }
}

object UsageBuckets {
    /**
     * Daily buckets for the 7 / 30 / 90-day windows.
     *
     * The server already returns one row per calendar day, but gaps are filled
     * between the first and last returned date and the front is padded up to
     * [windowDays] so the x-axis always spans the whole window. Every date is
     * derived from the server's own strings, so the app never has to guess which
     * calendar the server keeps.
     */
    fun daily(
        rows: List<InsightsDailyToken>,
        windowDays: Int,
    ): List<UsageBucket> {
        val parsed: List<Pair<LocalDate, InsightsDailyToken>> = rows.mapNotNull { row ->
            usageDate(row.date)?.let { date -> date to row }
        }

        val first = parsed.minOfOrNull { it.first } ?: return emptyList()
        val last = parsed.maxOfOrNull { it.first } ?: return emptyList()

        val byDay: Map<LocalDate, InsightsDailyToken> = parsed
            .associate { (date, row) -> date to row }

        val dates = mutableListOf<LocalDate>()
        var cursor = first
        while (cursor <= last) {
            dates += cursor
            cursor = cursor.plusDays(1)
        }

        // Pad the leading edge so a short series still spans the requested window.
        while (dates.size < windowDays) {
            dates.add(0, dates.first().minusDays(1))
        }

        return dates.mapIndexed { index, date ->
            val row = byDay[date]
            UsageBucket(
                id = index,
                label = usageDayLabel(date),
                detail = UsageBucketDetail.Day(
                    input = row?.inputTokens ?: 0,
                    output = row?.outputTokens ?: 0,
                    cacheRead = row?.cacheReadTokens ?: 0,
                    sessions = row?.sessions ?: 0,
                    cost = row?.cost ?: 0.0,
                ),
            )
        }
    }

    /**
     * Hourly buckets for the 24-hour window: hours 0–23 in order, session counts
     * only, zero-filled for hours the server omits.
     */
    fun hourly(rows: List<InsightsActivityByHour>): List<UsageBucket> {
        val sessionsByHour: Map<Int, Int> = rows.mapNotNull { row ->
            val hour = row.hour
            if (hour == null || hour !in 0..23) return@mapNotNull null
            hour to (row.sessions ?: 0)
        }.associate { it.first to it.second }

        if (sessionsByHour.isEmpty()) return emptyList()

        return (0..23).map { hour ->
            UsageBucket(
                id = hour,
                label = usageHourLabel(hour),
                detail = UsageBucketDetail.Hour(sessions = sessionsByHour[hour] ?: 0),
            )
        }
    }
}

/**
 * The stack layers this bucket draws for [metric], in bottom-to-top order.
 * Token windows stack input, output, and cache reads; cost windows and the
 * 24-hour window get a single layer, because the server reports one scalar.
 */
fun UsageBucket.segments(metric: UsageMetric): List<Pair<UsageChartSegment, Double>> =
    when (val detail = detail) {
        is UsageBucketDetail.Day -> when (metric) {
            UsageMetric.TOKENS -> listOf(
                UsageChartSegment.INPUT to detail.input.toDouble(),
                UsageChartSegment.OUTPUT to detail.output.toDouble(),
                UsageChartSegment.CACHE_READ to detail.cacheRead.toDouble(),
            )
            UsageMetric.COST -> listOf(UsageChartSegment.COST to detail.cost)
        }
        is UsageBucketDetail.Hour -> listOf(UsageChartSegment.SESSIONS to detail.sessions.toDouble())
    }

/**
 * The segments that earn a legend. A single-colour bar does not need one.
 * Port of `usageChartLegendSegments(buckets:metric:)`.
 */
fun usageChartLegendSegments(buckets: List<UsageBucket>, metric: UsageMetric): List<UsageChartSegment> {
    val hasDayBucket = buckets.any { it.detail is UsageBucketDetail.Day }
    if (metric != UsageMetric.TOKENS || !hasDayBucket) return emptyList()
    return listOf(UsageChartSegment.INPUT, UsageChartSegment.OUTPUT, UsageChartSegment.CACHE_READ)
}

// MARK: - Formatting

/**
 * The calendar the Usage screen reads server day strings with. The server sends
 * bare `YYYY-MM-DD` in its own local time, so days are treated as wall-clock
 * dates rather than instants.
 */
private val usageZone: ZoneId = ZoneId.of("UTC")

private val usageServerDayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(usageZone)

/**
 * Parses a server `daily_tokens[].date` string. Returns null for anything else,
 * so a shape change drops the bar instead of inventing a date.
 * Port of `usageDate(fromServerDay:calendar:)`.
 */
fun usageDate(value: String?): LocalDate? {
    if (value.isNullOrEmpty()) return null
    return runCatching { LocalDate.parse(value, usageServerDayFormatter) }.getOrNull()
}

/** Port of `usageDayLabel(_:calendar:locale:)` — "Aug 28". */
fun usageDayLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String {
    val month = date.month.getDisplayName(TextStyle.SHORT, locale)
    return "$month ${date.dayOfMonth}"
}

/** Port of `usageHourLabel(_:calendar:locale:)` — "9 AM" / "4 PM". */
fun usageHourLabel(hour: Int, locale: Locale = Locale.getDefault()): String {
    val instant = Instant.EPOCH.plusSeconds(hour * 3600L)
    val localTime = java.time.ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime()
    val formatter = DateTimeFormatter.ofPattern("h a", locale)
    return localTime.format(formatter)
}

/** Locale-aware grouped token count ("1,234,567"). Port of `usageFormattedTokens`. */
fun usageFormattedTokens(value: Int, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%,d", value)

/**
 * Formats a server-reported amount. The server prices in USD whatever the
 * user's locale is, so the currency code is fixed and only the presentation
 * localizes. Amounts under a cent keep four digits rather than rounding away to
 * nothing. Port of `usageFormattedCost(_:locale:)`.
 */
fun usageFormattedCost(value: Double, locale: Locale = Locale.getDefault()): String {
    val fractionDigits = if (value > 0 && value < 0.01) 4 else 2
    val rounded = String.format(locale, "%.${fractionDigits}f", value)
    return "$$rounded"
}
