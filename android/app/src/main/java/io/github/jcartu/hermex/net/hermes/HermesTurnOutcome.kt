package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Port of `Networking/Hermes/HermesTurnOutcome.swift`: how a Hermes turn ended, read
 * tolerantly from what the host already sends about it: the failure `session.resume` retains
 * in `inflight`, or a live `message.complete`. A pure value with every field optional, so an
 * older or newer host decodes to whatever it sent.
 */
data class HermesTurnOutcome(
    /** The host's raw error text; null on a turn that did not fail. */
    val error: String? = null,
    val surface: Surface? = null,
    /** True on every failure the host retains; the retry verdict for a host without a surface. */
    val recoverable: Boolean? = null,
    /** The provider's billing page, only as an `https` URL (`message.complete` only). */
    val billingURL: HttpUrl? = null,
    /** The classified reason sent with a billing wall (`message.complete` only). */
    val failureReason: String? = null,
    /** A free-form host warning, such as a reply that was shown but not saved (`message.complete` only). */
    val warning: String? = null,
) {
    /** The host's advisory `error_surface`: which layer failed, why, and whether retrying unchanged can help. */
    data class Surface(
        /** `provider`, `endpoint`, `streaming`, `auth`, `billing`, `gateway`, `disk` or `runtime`. */
        val layer: String? = null,
        /** The failure reason, such as `rate_limit` or `context_overflow`. */
        val code: String? = null,
        val retryable: Boolean? = null,
        val provider: String? = null,
        /** When a rate limit lifts, as epoch seconds, if the provider said. */
        val resetsAt: Double? = null,
        /** The host's own sentence for the failure (sent for `free_tier_*` codes). */
        val message: String? = null,
    )

    /** Whether retrying unchanged can help: the host's own verdict, or whether it kept the failure as recoverable. */
    val offersRetry: Boolean get() = surface?.retryable ?: (recoverable == true)

    companion object {
        /**
         * `init?(inflight:)` — the failure `session.resume` retains until the next turn starts
         * or the session closes, or a failed `message.complete`. Null when it carries no error.
         */
        fun fromInflight(inflight: BotJSON): HermesTurnOutcome? {
            if (inflight["error"].isNull) return null
            return failure(inflight)
        }

        /** `init?(complete:)` — a `message.complete` payload. Null when it carries nothing beyond the reply. */
        fun fromComplete(payload: BotJSON): HermesTurnOutcome? {
            val base = failure(payload)
            val billing = payload["billing"]["billing_url"].text?.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }
            val warning = nonEmpty(payload["warning"])
            val outcome = base.copy(billingURL = billing, failureReason = nonEmpty(payload["failure_reason"]), warning = warning)
            if (payload["error"].isNull && payload["billing"].isNull && warning == null) return null
            return outcome
        }

        private fun failure(fields: BotJSON): HermesTurnOutcome {
            val surface = fields["error_surface"]
            return HermesTurnOutcome(
                error = nonEmpty(fields["error"]),
                recoverable = fields["recoverable"].flag,
                surface = if (surface.isObject) {
                    Surface(
                        layer = nonEmpty(surface["layer"]), code = nonEmpty(surface["code"]),
                        retryable = surface["retryable"].flag, provider = nonEmpty(surface["provider"]),
                        resetsAt = surface["resets_at"].number?.takeIf { it.isFinite() },
                        message = nonEmpty(surface["message"]),
                    )
                } else {
                    null
                },
            )
        }

        private fun nonEmpty(value: BotJSON): String? = value.text?.trim()?.takeIf { it.isNotEmpty() }
    }
}
