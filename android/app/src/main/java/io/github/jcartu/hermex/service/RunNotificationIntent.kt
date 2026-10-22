package io.github.jcartu.hermex.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.jcartu.hermex.MainActivity
import io.github.jcartu.hermex.model.ServerKind
import java.security.MessageDigest

/** Port of `ResponseCompletionNotificationRequest.userInfo` and `HermesDeepLink` notification destinations. */
object RunNotificationIntent {
    const val EXTRA_SERVER_ID = "io.github.jcartu.hermex.extra.SERVER_ID"
    const val EXTRA_SESSION_ID = "io.github.jcartu.hermex.extra.SESSION_ID"
    const val EXTRA_HERMES_PROFILE = "io.github.jcartu.hermex.extra.HERMES_PROFILE"
    const val EXTRA_SERVER_KIND = "io.github.jcartu.hermex.extra.SERVER_KIND"
    const val EXTRA_SOURCE = "io.github.jcartu.hermex.extra.SOURCE"
    const val SOURCE_LOCAL = "local"

    data class Target(
        val serverId: String,
        val sessionId: String,
        val hermesProfile: String? = null,
        val serverKind: ServerKind = if (hermesProfile != null) ServerKind.HERMES else ServerKind.WEBUI,
    ) {
        /** Swift's server hash keeps the URL out of the system's notification tag; same session ids on two servers never alias. */
        val notificationTag: String
            get() = "run-alert-${MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }}-$sessionId"
    }

    /** Only local notification extras are authority; a missing/removed server is never replaced by the active one. */
    fun target(intent: Intent?): Target? {
        if (intent?.getStringExtra(EXTRA_SOURCE) != SOURCE_LOCAL) return null
        val server = intent.getStringExtra(EXTRA_SERVER_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val session = intent.getStringExtra(EXTRA_SESSION_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val profile = intent.getStringExtra(EXTRA_HERMES_PROFILE)
        val kind = ServerKind.fromRawValue(intent.getStringExtra(EXTRA_SERVER_KIND))
            ?: if (profile != null) ServerKind.HERMES else ServerKind.WEBUI
        return Target(server, session, profile, kind)
    }

    fun contentIntent(context: Context, target: Target): Intent {
        val uri = Uri.Builder().scheme("hermex-type-j")
            .authority(if (target.serverKind == ServerKind.HERMES) "session" else "webui-push")
            .appendQueryParameter("server", target.serverId)
        if (target.serverKind == ServerKind.HERMES && target.hermesProfile != null) {
            uri.appendQueryParameter("profile", target.hermesProfile)
        }
        uri.appendQueryParameter("id", target.sessionId)
        if (target.serverKind == ServerKind.WEBUI) uri.appendQueryParameter("onSource", "1")
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri.build()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SOURCE, SOURCE_LOCAL)
            putExtra(EXTRA_SERVER_ID, target.serverId)
            putExtra(EXTRA_SESSION_ID, target.sessionId)
            putExtra(EXTRA_SERVER_KIND, target.serverKind.rawValue)
            target.hermesProfile?.let { putExtra(EXTRA_HERMES_PROFILE, it) }
        }
    }
}
