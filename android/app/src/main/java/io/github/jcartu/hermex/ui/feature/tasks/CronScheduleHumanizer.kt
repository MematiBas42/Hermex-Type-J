package io.github.jcartu.hermex.ui.feature.tasks

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A cron expression rendered for people.
 *
 * `recurrence` is the period on its own ("Every 15 minutes", "Weekdays"), for
 * a row that already shows the next run time beside it. `sentence` folds the
 * time of day back in ("Weekdays at 9:25 AM"), for rows with no next run and
 * for Task Detail.
 * Port of `Features/Tasks/CronScheduleHumanizer.swift`.
 */
data class CronScheduleDescription(
    val recurrence: String,
    val sentence: String,
)

/**
 * Turns the standard five-field cron expressions the server sends into English.
 *
 * Deliberately narrow: it recognises the shapes people actually schedule and
 * returns `null` for everything else, so callers fall back to the server's own
 * string. A wrong guess about when a job runs is worse than a raw expression.
 */
object CronScheduleHumanizer {
    /**
     * Describes [expression], or null when it is not a five-field cron
     * expression in one of the recognised shapes. English-only (v1).
     */
    fun describe(expression: String?, locale: Locale = Locale.US): CronScheduleDescription? {
        val raw = expression ?: return null
        val fields = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size != 5) return null

        val minuteField = fields[0]
        val hourField = fields[1]
        val dayOfMonthField = fields[2]
        val monthField = fields[3]
        val dayOfWeekField = fields[4]

        // Anything month-scoped beyond "every month" is rare enough that the
        // raw expression is the honest answer.
        if (monthField != "*") return null

        intervalRecurrence(minuteField, hourField, dayOfMonthField, dayOfWeekField)?.let { recurrence ->
            // An interval has no single time of day to append.
            return CronScheduleDescription(recurrence = recurrence, sentence = recurrence)
        }

        val minute = fixedValue(minuteField, 0..59) ?: return null
        val hour = fixedValue(hourField, 0..23) ?: return null
        val recurrence = calendarRecurrence(dayOfMonthField, dayOfWeekField, locale) ?: return null

        val time = timeText(hour, minute, locale)
        return CronScheduleDescription(
            recurrence = recurrence,
            sentence = "$recurrence at $time",
        )
    }

    // MARK: - Shapes

    /**
     * A step over an otherwise unrestricted field: "every N minutes" from a
     * minute field of `*` / `N`, and "every N hours" from a minute field of `0`
     * with an hour field of `*` / `N`. The hourly form requires minute zero so
     * the phrase cannot imply an offset it is not describing.
     */
    private fun intervalRecurrence(
        minuteField: String,
        hourField: String,
        dayOfMonthField: String,
        dayOfWeekField: String,
    ): String? {
        if (dayOfMonthField != "*" || dayOfWeekField != "*") return null

        if (hourField == "*") {
            if (minuteField == "*") return "Every minute"
            val step = stepValue(minuteField, 1..59) ?: return null
            if (step <= 1) return "Every minute"
            return "Every ${durationTextMinutes(step)}"
        }

        if (minuteField != "0") return null
        val step = stepValue(hourField, 1..23) ?: return null
        if (step <= 1) return null // `0 */1 * * *` is just hourly; let it fall through.
        return "Every ${durationTextHours(step)}"
    }

    /** The day-of-week and day-of-month shapes that pair with a fixed time. */
    private fun calendarRecurrence(
        dayOfMonthField: String,
        dayOfWeekField: String,
        locale: Locale,
    ): String? {
        if (dayOfMonthField == "*") {
            if (dayOfWeekField == "*") return "Daily"

            val days = dayOfWeekSet(dayOfWeekField) ?: return null
            if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays"
            if (days == setOf(0, 6)) return "Weekends"
            if (days.size != 1) return null
            return "Weekly on ${weekdayName(days.first(), locale)}"
        }

        // A day-of-month and a day-of-week together mean different things on
        // different cron implementations, so only the unambiguous form counts.
        if (dayOfWeekField != "*") return null
        val day = fixedValue(dayOfMonthField, 1..31) ?: return null
        val ordinal = ordinalText(day, locale) ?: return null
        return "Monthly on the $ordinal"
    }

    // MARK: - Field parsing

    /** A single literal number, e.g. `25`. Lists, ranges and steps are not fixed values. */
    private fun fixedValue(field: String, range: ClosedRange<Int>): Int? {
        val value = field.toIntOrNull() ?: return null
        return value.takeIf { it in range }
    }

    /** The `N` of a `*` / `N` step, valid only when the rest of the field is unrestricted. */
    private fun stepValue(field: String, range: ClosedRange<Int>): Int? {
        if (!field.startsWith("*/")) return null
        val step = field.removePrefix("*/").toIntOrNull() ?: return null
        return step.takeIf { it in range }
    }

    /** Day-of-week as Sunday-zero indices. Accepts `3`, `1-5` and `0,6`; cron's `7` is Sunday. */
    private fun dayOfWeekSet(field: String): Set<Int>? {
        fun normalized(token: String): Int? {
            val value = token.toIntOrNull() ?: return null
            if (value !in 0..7) return null
            return value % 7
        }

        if (field.contains('-')) {
            val bounds = field.split('-')
            if (bounds.size != 2) return null
            val lower = normalized(bounds[0]) ?: return null
            val upper = normalized(bounds[1]) ?: return null
            if (lower > upper) return null
            return (lower..upper).toSet()
        }

        val tokens = field.split(',')
        if (tokens.isEmpty()) return null

        val days = mutableSetOf<Int>()
        for (token in tokens) {
            val day = normalized(token) ?: return null
            days.add(day)
        }
        return days
    }

    // MARK: - Locale-aware pieces

    private fun timeText(hour: Int, minute: Int, locale: Locale): String {
        val time = LocalTime.of(hour, minute)
        return try {
            DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
                .withLocale(locale)
                .format(time)
        } catch (_: Exception) {
            String.format(locale, "%02d:%02d", hour, minute)
        }
    }

    /**
     * The wait until [date] as one compact figure — "in 6m", "in 12h 21m",
     * "in 3d 4h" — for the number Task Detail leads with.
     *
     * Deliberately computed on demand instead of ticked by a timer: a
     * self-updating label repaints its whole card every second for a figure
     * nobody watches count down, and Refresh already brings it current.
     */
    fun countdown(to: Instant, from: Instant = Instant.now()): String {
        val interval = Duration.between(from, to).seconds
        if (interval < 60) {
            return if (interval > 0) "Due now" else "Overdue"
        }

        val text = when {
            interval < 3_600 -> "${interval / 60}m"
            interval < 86_400 -> "${interval / 3600}h ${(interval % 3600) / 60}m"
            else -> "${interval / 86_400}d ${(interval % 86_400) / 3600}h"
        }
        return "in $text"
    }

    private fun durationTextMinutes(minutes: Int): String = "$minutes minutes"

    private fun durationTextHours(hours: Int): String = "$hours hours"

    /** Sunday-zero index to a standalone weekday name in [locale]. */
    private fun weekdayName(day: Int, locale: Locale): String {
        val weekday = java.time.DayOfWeek.of(if (day == 0) 7 else day)
        return weekday.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    }

    private fun ordinalText(value: Int, locale: Locale): String =
        when (value) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            21 -> "21st"
            22 -> "22nd"
            23 -> "23rd"
            31 -> "31st"
            else -> "${value}th"
        }
}
