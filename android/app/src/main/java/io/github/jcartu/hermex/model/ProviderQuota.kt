package io.github.jcartu.hermex.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `GET /api/provider/quota?provider=<id>` — one provider's subscription limits
 * or prepaid credits (#415). Shape verified against the live server on
 * 2026-09-05 and upstream `api/providers.py` (`get_provider_quota`,
 * `_serialize_account_usage_snapshot`, `_sanitize_openrouter_quota`).
 *
 * The handler answers with one of two payload families and never fails the
 * request: OAuth-backed providers carry `account_limits` (Codex, Anthropic),
 * OpenRouter carries `quota`, and everything else answers with a non-`available`
 * `status` and both null. `status` values seen live: `available`, `unsupported`,
 * `unavailable`, `no_key`, `invalid_key`. Every field is optional so a partial
 * or future payload decodes instead of throwing, and `pool` — the per-credential
 * breakdown Hermex deliberately does not show — is ignored.
 * Port of `Networking/APIClient+ProviderQuota.swift`.
 */
@Serializable(with = ProviderQuotaResponseSerializer::class)
data class ProviderQuotaResponse(
    val ok: Boolean? = null,
    val provider: String? = null,
    val displayName: String? = null,
    val supported: Boolean? = null,
    val status: String? = null,
    val label: String? = null,
    val message: String? = null,
    val quota: ProviderQuotaCredits? = null,
    val accountLimits: ProviderAccountLimits? = null,
)

internal object ProviderQuotaResponseSerializer :
    TolerantObjectSerializer<ProviderQuotaResponse>("ProviderQuotaResponse") {

    override fun fromJson(json: Json, obj: JsonObject): ProviderQuotaResponse =
        ProviderQuotaResponse(
            ok = obj.lossyBool("ok"),
            provider = obj.lossyString("provider"),
            displayName = obj.lossyString("display_name", "displayName"),
            supported = obj.lossyBool("supported"),
            status = obj.lossyString("status"),
            label = obj.lossyString("label"),
            message = obj.lossyString("message"),
            quota = obj.decodeObject(json, "quota", ProviderQuotaCredits.serializer()),
            accountLimits = obj.decodeObject(json, "account_limits", ProviderAccountLimits.serializer()),
        )

    override fun toJson(json: Json, value: ProviderQuotaResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.provider?.let { put("provider", JsonPrimitive(it)) }
        value.displayName?.let { put("display_name", JsonPrimitive(it)) }
        value.supported?.let { put("supported", JsonPrimitive(it)) }
        value.status?.let { put("status", JsonPrimitive(it)) }
        value.label?.let { put("label", JsonPrimitive(it)) }
        value.message?.let { put("message", JsonPrimitive(it)) }
        value.quota?.let { put("quota", json.encodeToJsonElement(ProviderQuotaCredits.serializer(), it)) }
        value.accountLimits?.let { put("account_limits", json.encodeToJsonElement(ProviderAccountLimits.serializer(), it)) }
    }
}

/** Decodes one object field without throwing: a missing or malformed value is null. */
internal fun <T> JsonObject.decodeObject(
    json: Json,
    key: String,
    serializer: kotlinx.serialization.DeserializationStrategy<T>,
): T? {
    val element = presentElement(key) ?: return null
    return runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
}

/**
 * The OpenRouter credits family. All three numbers are null when the key has no
 * spending cap, so a card renders from whichever ones arrived.
 */
@Serializable(with = ProviderQuotaCreditsSerializer::class)
data class ProviderQuotaCredits(
    val limitRemaining: Double? = null,
    val usage: Double? = null,
    val limit: Double? = null,
)

internal object ProviderQuotaCreditsSerializer :
    TolerantObjectSerializer<ProviderQuotaCredits>("ProviderQuotaCredits") {

    override fun fromJson(json: Json, obj: JsonObject): ProviderQuotaCredits =
        ProviderQuotaCredits(
            limitRemaining = obj.lossyDouble("limit_remaining", "limitRemaining"),
            usage = obj.lossyDouble("usage"),
            limit = obj.lossyDouble("limit"),
        )

    override fun toJson(json: Json, value: ProviderQuotaCredits): JsonObject = buildJsonObject {
        value.limitRemaining?.let { put("limit_remaining", JsonPrimitive(it)) }
        value.usage?.let { put("usage", JsonPrimitive(it)) }
        value.limit?.let { put("limit", JsonPrimitive(it)) }
    }
}

/**
 * The account-limits family (`openai-codex`, `anthropic`). `available` is false
 * whenever upstream attached an `unavailable_reason`, and `windows` is empty
 * when the probe found nothing to report — both hide the card.
 */
@Serializable(with = ProviderAccountLimitsSerializer::class)
data class ProviderAccountLimits(
    val plan: String? = null,
    val title: String? = null,
    val available: Boolean? = null,
    val unavailableReason: String? = null,
    val fetchedAt: String? = null,
    val windows: List<ProviderQuotaWindow>? = null,
    val details: List<String>? = null,
)

internal object ProviderAccountLimitsSerializer :
    TolerantObjectSerializer<ProviderAccountLimits>("ProviderAccountLimits") {

    override fun fromJson(json: Json, obj: JsonObject): ProviderAccountLimits =
        ProviderAccountLimits(
            plan = obj.lossyString("plan"),
            title = obj.lossyString("title"),
            available = obj.lossyBool("available"),
            unavailableReason = obj.lossyString("unavailable_reason", "unavailableReason"),
            fetchedAt = obj.lossyString("fetched_at", "fetchedAt"),
            windows = obj.decodeLossyList(json, "windows", ProviderQuotaWindow.serializer()),
            details = obj.decodeLossyList(json, "details", kotlinx.serialization.builtins.serializer()),
        )

    override fun toJson(json: Json, value: ProviderAccountLimits): JsonObject = buildJsonObject {
        value.plan?.let { put("plan", JsonPrimitive(it)) }
        value.title?.let { put("title", JsonPrimitive(it)) }
        value.available?.let { put("available", JsonPrimitive(it)) }
        value.unavailableReason?.let { put("unavailable_reason", JsonPrimitive(it)) }
        value.fetchedAt?.let { put("fetched_at", JsonPrimitive(it)) }
        value.windows?.let { put("windows", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ProviderQuotaWindow.serializer()), it)) }
        value.details?.let { put("details", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()), it)) }
    }
}

/**
 * One rate-limit window. `remaining_percent` is null whenever upstream could not
 * read `used_percent`, which is why the bar is optional and the row still shows
 * its label and reset time.
 */
@Serializable(with = ProviderQuotaWindowSerializer::class)
data class ProviderQuotaWindow(
    val label: String? = null,
    val usedPercent: Double? = null,
    val remainingPercent: Double? = null,
    val resetAt: String? = null,
    val detail: String? = null,
)

internal object ProviderQuotaWindowSerializer :
    TolerantObjectSerializer<ProviderQuotaWindow>("ProviderQuotaWindow") {

    override fun fromJson(json: Json, obj: JsonObject): ProviderQuotaWindow =
        ProviderQuotaWindow(
            label = obj.lossyString("label"),
            usedPercent = obj.lossyDouble("used_percent", "usedPercent"),
            remainingPercent = obj.lossyDouble("remaining_percent", "remainingPercent"),
            resetAt = obj.lossyString("reset_at", "resetAt"),
            detail = obj.lossyString("detail"),
        )

    override fun toJson(json: Json, value: ProviderQuotaWindow): JsonObject = buildJsonObject {
        value.label?.let { put("label", JsonPrimitive(it)) }
        value.usedPercent?.let { put("used_percent", JsonPrimitive(it)) }
        value.remainingPercent?.let { put("remaining_percent", JsonPrimitive(it)) }
        value.resetAt?.let { put("reset_at", JsonPrimitive(it)) }
        value.detail?.let { put("detail", JsonPrimitive(it)) }
    }
}

// MARK: - Provider limit cards (#415)

/**
 * One provider's quota card on the Usage screen. Built from
 * `GET /api/provider/quota`; nothing about it is persisted, so switching
 * servers simply rebuilds the screen with that server's providers.
 */
data class ProviderLimitCard(
    /** The provider slug, which is also the card's stable list identity. */
    val id: String,
    /** The server's `display_name`, shown verbatim. */
    val title: String,
    /** The subscription plan capsule ("Plus"). Absent on credits cards. */
    val plan: String?,
    val rows: List<ProviderLimitRow>,
    /**
     * `account_limits.fetched_at` for account cards, or the client's fetch time
     * for credits cards, whose payload carries no timestamp.
     */
    val updatedAt: Instant?,
) {
    /**
     * Builds the card for one `GET /api/provider/quota` response, or null when
     * there is nothing worth a card. Only `status == "available"` with either a
     * non-empty set of windows or a usable credits object renders; every other
     * status (`unsupported`, `unavailable`, `no_key`, `invalid_key`) and every
     * empty payload hides silently.
     */
    companion object {
        fun build(provider: String, response: ProviderQuotaResponse, fetchedAt: Instant): ProviderLimitCard? {
            if (response.status?.trim()?.lowercase() != "available") return null

            val title = response.displayName?.trim()
            val resolvedTitle = title?.takeIf { it.isNotEmpty() } ?: provider

            val limits = response.accountLimits
            if (limits != null && limits.available != false) {
                val rows = (limits.windows ?: emptyList()).mapIndexedNotNull { index, window ->
                    val label = window.label?.trim()
                    if (label.isNullOrEmpty()) return@mapIndexedNotNull null

                    ProviderLimitRow(
                        id = "$provider-$index-$label",
                        label = label,
                        amount = window.remainingPercent?.let(::providerLimitPercentLeftText),
                        fraction = window.remainingPercent?.let(::providerLimitFraction),
                        remainingPercent = window.remainingPercent,
                        resetAt = providerQuotaDate(window.resetAt),
                        note = null,
                    )
                }

                if (rows.isEmpty()) return null

                val plan = limits.plan?.trim()
                return ProviderLimitCard(
                    id = provider,
                    title = resolvedTitle,
                    plan = plan?.takeIf { it.isNotEmpty() },
                    rows = rows,
                    updatedAt = providerQuotaDate(limits.fetchedAt),
                )
            }

            // Credits. A quota object whose every number is null describes nothing,
            // so it hides like any other unusable payload.
            val quota = response.quota
            if (quota == null || (quota.limitRemaining == null && quota.usage == null)) return null

            // Only a positive cap makes a meaningful bar; an uncapped key shows the
            // balance and what it has spent, with nothing to fill.
            val fraction: Double? = run {
                val remaining = quota.limitRemaining ?: return@run null
                val limit = quota.limit ?: return@run null
                if (limit <= 0) return@run null
                minOf(maxOf(remaining / limit, 0.0), 1.0)
            }

            val note: String? = run {
                val usage = quota.usage ?: return@run null
                val limit = quota.limit
                if (limit != null) {
                    "${usageFormattedCost(usage)} used of ${usageFormattedCost(limit)}"
                } else {
                    "${usageFormattedCost(usage)} used"
                }
            }

            return ProviderLimitCard(
                id = provider,
                title = resolvedTitle,
                plan = null,
                rows = listOf(
                    ProviderLimitRow(
                        id = "$provider-credits",
                        label = "Credits",
                        amount = quota.limitRemaining?.let { providerLimitAmountLeftText(it) },
                        fraction = fraction,
                        remainingPercent = fraction?.let { it * 100 },
                        resetAt = null,
                        note = note,
                    ),
                ),
                updatedAt = fetchedAt,
            )
        }
    }
}

/** One line inside a limit card: a rate-limit window, or the single "Credits" row of a prepaid provider. */
data class ProviderLimitRow(
    val id: String,
    /** The window `label` verbatim, or the localized "Credits". */
    val label: String,
    /** The bold right-hand amount ("2% left", "$0.00 left"). Null when the server reported no usable number. */
    val amount: String?,
    /** Remaining share of the bar, 0...1. Null hides the bar entirely. */
    val fraction: Double?,
    /** Drives the tint tier. Null tints with the accent. */
    val remainingPercent: Double?,
    /** The reset instant. The countdown line is rendered from it at draw time. */
    val resetAt: Instant?,
    /** A static secondary line ("$10.03 used of $10.00"), used by credits rows in place of a countdown. */
    val note: String?,
)

/**
 * How urgent a remaining balance is. Accent normally, orange at 25% or less,
 * red at 10% or less — matched by `ProviderLimitsCard`'s colors.
 */
enum class ProviderLimitTint {
    NORMAL,
    WARNING,
    CRITICAL,
}

/**
 * Tiers a remaining percentage. A nil percentage has no signal to tier on and
 * stays neutral rather than alarming.
 */
fun providerLimitTint(remainingPercent: Double?): ProviderLimitTint {
    val remaining = remainingPercent ?: return ProviderLimitTint.NORMAL
    if (remaining <= 10) return ProviderLimitTint.CRITICAL
    if (remaining <= 25) return ProviderLimitTint.WARNING
    return ProviderLimitTint.NORMAL
}

/**
 * "2% left" from a server 0–100 remaining percentage. Rounded to a whole
 * percent to match the reference design, and clamped so a server that reports
 * out-of-range never renders "-3% left".
 */
fun providerLimitPercentLeftText(remainingPercent: Double): String {
    val clamped = minOf(maxOf(remainingPercent, 0.0), 100.0)
    return "${kotlin.math.round(clamped).toInt()}% left"
}

/** "$0.00 left" from a credits balance, in the user's locale. */
fun providerLimitAmountLeftText(amount: Double, locale: Locale = Locale.getDefault()): String =
    "${usageFormattedCost(amount, locale)} left"

/** The bar's fill, 0...1, from a server 0–100 remaining percentage. */
fun providerLimitFraction(remainingPercent: Double): Double =
    minOf(maxOf(remainingPercent / 100, 0.0), 1.0)

/**
 * "Resets in 4h 59m" under 24 hours, "Resets in 1d 3h" beyond it, and
 * "Resets soon" once the window has turned over or is under a minute out.
 * Port of `providerLimitResetText(_:now:width:locale:)` (narrow width, English).
 */
fun providerLimitResetText(resetAt: Instant?, now: Instant): String? {
    val reset = resetAt ?: return null
    val remaining = Duration.between(now, reset)
    if (remaining.seconds < 60) return "Resets soon"

    val totalSeconds = remaining.seconds
    val text = if (totalSeconds < 24 * 60 * 60) {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        "${hours}h ${minutes}m"
    } else {
        val days = totalSeconds / (24 * 60 * 60)
        val hours = (totalSeconds % (24 * 60 * 60)) / 3600
        "${days}d ${hours}h"
    }
    return "Resets in $text"
}

/**
 * "Updated 41 sec ago" — the card footer. One unit only, so a stale card reads
 * "Updated 2 hr ago" rather than a running clock. Computed at render; nothing
 * schedules a refresh of it.
 * Port of `providerLimitUpdatedText(_:now:locale:)` (abbreviated width, English).
 */
fun providerLimitUpdatedText(fetchedAt: Instant?, now: Instant): String? {
    val fetched = fetchedAt ?: return null
    val elapsed = maxOf(0L, Duration.between(fetched, now).seconds)
    val unit = when {
        elapsed < 60 -> "${elapsed}s"
        elapsed < 3600 -> "${elapsed / 60}m"
        elapsed < 86400 -> "${elapsed / 3600}h"
        else -> "${elapsed / 86400}d"
    }
    return "Updated $unit ago"
}

private val providerQuotaPlainFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
private val providerQuotaLocalFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/**
 * Parses the ISO-8601 instants upstream emits for `reset_at` and `fetched_at`.
 * Both shapes appear live: whole seconds ("2026-09-06T07:55:41Z") and
 * microsecond precision ("2026-09-06T02:56:37.848820Z"), the latter more
 * precise than `ISO8601DateFormatter` accepts — so the fraction is dropped and
 * re-parsed rather than failing the row.
 * Port of `providerQuotaDate(_:)`.
 */
fun providerQuotaDate(raw: String?): Instant? {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) return null

    runCatching { Instant.parse(trimmed) }.getOrNull()?.let { return it }

    // Drop a sub-second fraction more precise than nanos, or any fraction, and re-parse.
    return runCatching {
        val dot = trimmed.indexOf('.')
        if (dot < 0) return@runCatching null
        val zoneStart = trimmed.indexOfFirstOrNull(dot + 1) { it == 'Z' || it == 'z' || it == '+' || it == '-' }
            ?: return@runCatching null
        val rebuilt = trimmed.substring(0, dot) + trimmed.substring(zoneStart)
        Instant.parse(rebuilt)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(trimmed, providerQuotaLocalFormatter)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
    }.getOrNull()
}

private fun String.indexOfFirstOrNull(startIndex: Int, predicate: (Char) -> Boolean): Int? {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return null
}

// MARK: - Selection

/**
 * The providers worth probing for quota beyond whichever one is active.
 * Upstream implements account limits for the OAuth providers and credits for
 * OpenRouter; everything else answers `unsupported`, so probing it would only
 * cost a round trip.
 */
val providerQuotaCandidates = listOf("openai-codex", "anthropic", "openrouter")

/**
 * The selection rule: the active provider first, then the known quota
 * providers, deduplicated, keeping only entries `/api/providers` reports with a
 * configured key. Order is stable so cards do not reshuffle between loads.
 * Port of `providerQuotaSelection(from:)`.
 */
fun providerQuotaSelection(response: ProvidersResponse): List<String> {
    val keyed = (response.providers ?: emptyList())
        .filter { it.hasKey == true }
        .mapNotNull { normalizedProviderSlug(it.id) }
        .toSet()

    val candidates =
        listOfNotNull(normalizedProviderSlug(response.activeProvider)) + providerQuotaCandidates

    val seen = mutableSetOf<String>()
    val selected = mutableListOf<String>()
    for (candidate in candidates) {
        if (candidate !in keyed) continue
        if (!seen.add(candidate)) continue
        selected += candidate
    }
    return selected
}

/**
 * `active_provider` comes from config while entry `id`s are canonical slugs, so
 * both sides are trimmed and lowercased before they are compared.
 */
fun normalizedProviderSlug(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()
    if (trimmed.isNullOrEmpty()) return null
    return trimmed
}
