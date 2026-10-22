package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Port of `ServerInfo.swift` — the small response shapes for the server
 * health/auth endpoints. All fields optional; synthesized Swift decoding
 * (missing fields stay null, unknown fields ignored by the shared Json config).
 */

/** `GET /health` payload. */
@Serializable
data class HealthResponse(
    val status: String? = null,
    val sessions: Int? = null,
    @SerialName("active_streams") val activeStreams: Int? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Double? = null,
)

/** `GET /auth/status` payload. */
@Serializable
data class AuthStatusResponse(
    @SerialName("auth_enabled") val authEnabled: Boolean? = null,
    @SerialName("logged_in") val loggedIn: Boolean? = null,
    /**
     * Finer-grained capabilities newer servers report. All optional so older
     * servers that omit them decode unchanged. `password_auth_enabled == false`
     * (and only an explicit false) marks a passkey-only server we can't sign
     * into yet (#255); a missing value means "unknown" → treat as today.
     */
    @SerialName("password_auth_enabled") val passwordAuthEnabled: Boolean? = null,
    @SerialName("passkeys_enabled") val passkeysEnabled: Boolean? = null,
    @SerialName("passwordless_enabled") val passwordlessEnabled: Boolean? = null,
    /**
     * Set when the server signs in through OpenID Connect; password auth off
     * on its own never meant passkeys (#3).
     */
    @SerialName("oidc_enabled") val oidcEnabled: Boolean? = null,
    /**
     * Set when an identity proxy in front of the server authenticates the
     * request (Cloudflare Access, Authentik). Present only when that mode is
     * on, so null means "not that kind of server".
     */
    @SerialName("trusted_auth_enabled") val trustedAuthEnabled: Boolean? = null,
) {
    /**
     * The server already considers this client signed in. Trusted-header
     * deployments authenticate at the proxy, so there is no credential for the
     * app to send and no login step to perform.
     */
    val isAlreadySignedIn: Boolean get() = loggedIn == true
}

/** `POST /auth/login` payload. */
@Serializable
data class LoginResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
)
