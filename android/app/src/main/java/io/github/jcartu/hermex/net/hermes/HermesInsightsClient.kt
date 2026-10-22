package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.ProviderSummary
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesInsightsDailyToken
import io.github.jcartu.hermex.model.hermes.HermesInsightsModelBreakdown
import io.github.jcartu.hermex.model.hermes.HermesInsightsUnavailable
import io.github.jcartu.hermex.model.hermes.HermesInsightsWindow
import io.github.jcartu.hermex.model.hermes.HermesProviderQuota
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * Port of `HermesInsightsClient` (`Networking/Hermes/HermesInsightsClient.swift`): the Usage
 * screen's client on a Hermes host (#1074): one Profile's analytics over the sign-in, headers
 * and cookie jar the server's Bot screens share. A window is three reads made together:
 * `/api/analytics/usage`, `/api/analytics/models` and `insights.get` over the shared gateway
 * socket for the message count. Provider limits come from the host's hermex-push plugin where
 * it mounts `GET /provider-quota` (#1237). A store the host can't read, its 503 or
 * `insights.get`'s 5017, is [HermesInsightsUnavailable].
 */
class HermesInsightsClient(private val http: HermesConnection, val profile: String) {

    suspend fun insights(days: Int, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): HermesInsightsWindow = coroutineScope {
        val usage = async { read(HermesREST.AnalyticsUsage(days, profile)) }
        val models = async { read(HermesREST.AnalyticsModels(days, profile)) }
        val counts = async { gateway(HermesCall.InsightsGet(days, profile)) }
        window(usage.await(), models.await(), counts.await(), nowEpochSeconds)
    }

    /**
     * The Profile's providers from `model.options`, read only where the host's hermex-push
     * mounts `GET /provider-quota` (#1237). Without the route no provider is offered.
     */
    suspend fun providers(): ProvidersResponse {
        if (!BotDashboardClient(http).companionRoutes().supports("GET /provider-quota")) {
            return ProvidersResponse(providers = emptyList(), activeProvider = null)
        }
        val options = gateway(HermesCall.ProfileModelOptions(profile))
        val providers = (options["providers"].list ?: emptyList()).mapNotNull { row ->
            val slug = row["slug"].text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ProviderSummary(id = slug, displayName = row["name"].text, hasKey = row["authenticated"].flag != false)
        }
        return ProvidersResponse(providers = providers, activeProvider = options["provider"].text)
    }

    /** One provider's limits for the Profile from hermex-push, on the long deadline (a Codex read can be slow). */
    suspend fun providerQuota(provider: String, refresh: Boolean): HermesProviderQuota {
        val reply = http.reply(
            HermesREST.CompanionProviderQuota(provider, profile, refresh), HermesConnection.Deadline.PROVISIONING,
        )
        val body = HermesCronClient.accepted(reply)
        val obj = BotJSON.parseOrNull(body)?.element as? JsonObject ?: throw ApiError.Decoding(ShapeError())
        return HermesProviderQuota.from(obj)
    }

    /** One analytics read's JSON. The host's own 503 names a store it can't read. */
    private suspend fun read(rest: HermesREST): BotJSON {
        val reply = try {
            http.reply(rest)
        } catch (error: BotFailure.Network) {
            throw ApiError.Network(error.underlying)
        }
        val body = reply.json
        if (reply.status == 503 && body?.get("detail")?.get("error")?.text?.startsWith("state_db") == true) throw HermesInsightsUnavailable()
        HermesCronClient.accepted(reply)
        return body ?: throw ApiError.Decoding(ShapeError())
    }

    /** One gateway call on its own attachment to the connection's shared socket. */
    private suspend fun gateway(call: HermesCall): BotJSON {
        val client = BotClient(http)
        try {
            client.connect()
            return client.call(call)
        } catch (error: BotFailure.Rejected) {
            if (error.code == 5017) throw HermesInsightsUnavailable()
            throw error
        } finally {
            client.close()
        }
    }

    /** An analytics reply without the lists or totals every window has. */
    private class ShapeError : SerializationException("An analytics reply without the lists or totals every window has")

    companion object {
        private val UTC_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        /**
         * `InsightsResponse(hermesUsage:models:counts:now:)` — a Hermes host's window as the
         * Usage screen reads it. Totals, sessions, cost and the daily chart come from [usage],
         * the models card from [models], and messages from [counts]. The host leaves cache
         * reads out of `input_tokens`, so the hit rate is cache reads over input plus cache
         * reads. A sum over an empty window arrives as null and reads as 0. `daily` lists only
         * days with sessions, by UTC date, so the chart is carried on to now's UTC day.
         */
        fun window(usage: BotJSON, models: BotJSON, counts: BotJSON, nowEpochSeconds: Long): HermesInsightsWindow {
            val totals = usage["totals"]
            val daily = usage["daily"].list
            val rows = models["models"].list
            if (!totals.isObject || daily == null || rows == null) throw ApiError.Decoding(ShapeError())
            val input = count(totals["total_input"])
            val output = count(totals["total_output"])
            val cacheRead = count(totals["total_cache_read"])

            val days = daily.map { row ->
                HermesInsightsDailyToken(
                    date = row["day"].text, inputTokens = count(row["input_tokens"]), outputTokens = count(row["output_tokens"]),
                    cacheReadTokens = count(row["cache_read_tokens"]), sessions = count(row["sessions"]), cost = amount(row["estimated_cost"]),
                )
            }.toMutableList()
            val today = UTC_DAY.format(Instant.ofEpochSecond(nowEpochSeconds))
            val latest = days.mapNotNull { it.date }.maxOrNull()
            if (latest == null || latest < today) {
                days += HermesInsightsDailyToken(date = today, inputTokens = 0, outputTokens = 0, cacheReadTokens = 0, sessions = 0, cost = 0.0)
            }

            data class Breakdown(
                val model: String?, val provider: String?, val input: Int, val output: Int, val cacheRead: Int,
                val sessions: Int, val cost: Double,
            )
            val breakdowns = rows.map { row ->
                Breakdown(
                    model = row["model"].text?.takeIf { it.isNotEmpty() }, provider = row["provider"].text?.takeIf { it.isNotEmpty() },
                    input = count(row["input_tokens"]), output = count(row["output_tokens"]), cacheRead = count(row["cache_read_tokens"]),
                    sessions = count(row["sessions"]), cost = amount(row["estimated_cost"]),
                )
            }
            val allSessions = breakdowns.sumOf { it.sessions.toDouble() }
            val allTokens = breakdowns.sumOf { (it.input + it.output).toDouble() }
            val allCost = breakdowns.sumOf { it.cost }

            return HermesInsightsWindow(
                periodDays = usage["period_days"].integer,
                totalSessions = count(totals["total_sessions"]),
                totalMessages = count(counts["messages"]),
                totalInputTokens = input,
                totalOutputTokens = output,
                totalTokens = input + output,
                totalCost = amount(totals["total_estimated_cost"]),
                totalCacheReadTokens = cacheRead,
                totalCacheHitPercent = cacheHitPercent(cacheRead, input),
                models = breakdowns.map { row ->
                    HermesInsightsModelBreakdown(
                        model = row.model, provider = row.provider, sessions = row.sessions, inputTokens = row.input,
                        outputTokens = row.output, cacheReadTokens = row.cacheRead, totalTokens = row.input + row.output, cost = row.cost,
                        cacheHitPercent = cacheHitPercent(row.cacheRead, row.input),
                        sessionShare = share(row.sessions.toDouble(), allSessions),
                        tokenShare = share((row.input + row.output).toDouble(), allTokens),
                        costShare = share(row.cost, allCost),
                    )
                },
                dailyTokens = days,
            )
        }

        /** A token or session count: a whole number from 0 to 2^31-1; null, negative or unreadable reads as 0. */
        private fun count(value: BotJSON): Int {
            val number = value.number?.takeIf { it.isFinite() } ?: return 0
            return minOf(maxOf(Math.round(number), 0L), Int.MAX_VALUE.toLong()).toInt()
        }

        /** A dollar amount, 0 when null, negative or unreadable. */
        private fun amount(value: BotJSON): Double {
            val number = value.number?.takeIf { it.isFinite() } ?: return 0.0
            return maxOf(number, 0.0)
        }

        /** Cache reads as a whole percent of everything the model was shown, capped at 100; null without cache reads. */
        private fun cacheHitPercent(cacheRead: Int, input: Int): Double? {
            if (cacheRead <= 0) return null
            return minOf(100.0, Math.round(cacheRead.toDouble() / (input.toDouble() + cacheRead.toDouble()) * 100).toDouble())
        }

        /** [part] as a whole percent of [whole], 0 when the whole is. */
        private fun share(part: Double, whole: Double): Int = if (whole > 0) (part / whole * 100).roundToLong().toInt() else 0
    }
}
