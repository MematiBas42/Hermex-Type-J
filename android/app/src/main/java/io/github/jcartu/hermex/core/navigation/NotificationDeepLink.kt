package io.github.jcartu.hermex.core.navigation

import android.content.Intent
import android.net.Uri
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.service.RunNotificationIntent

/**
 * Notification-tap entry — port of `PushNotificationRouter.localAlertLink`
 * (`Push/PushNotificationRouter.swift`): a tapped local run alert opens the
 * conversation it is about, on the server the alert names (#1190, #1192).
 *
 * `RunNotificationIntent.contentIntent` (ChatCoreFollowup) carries the
 * destination twice: in typed extras (the authority — SOURCE_LOCAL only) and
 * as a grammar-valid deep link in `Intent.data`. Extras win when present; the
 * data URI is the fallback. Both parse through the one grammar, so a tap
 * lands on the exact session its server names, Hermes or webui.
 */
object NotificationDeepLink {

    /**
     * The deep link [intent] routes, or null when it names nothing this app
     * opens (the app simply opens, like a push that names nothing).
     */
    fun uriFrom(intent: Intent?): Uri? {
        RunNotificationIntent.target(intent)?.let { target -> uri(target)?.let { return it } }
        return intent?.data
    }

    /**
     * The grammar form of a notification target: a Hermes target is its
     * server-named session link (#1176, Profile tri-state preserved — null
     * Profile leaves the item out, `""` stays as the host's default); a webui
     * target is the run's server-owned push link, which opens on its server
     * while it is configured (`opensOnSource`, #1217).
     */
    fun uri(target: RunNotificationIntent.Target): Uri? =
        when (target.serverKind) {
            ServerKind.HERMES -> HermexDeepLink.hermesSessionUri(
                HermesSessionDestination(
                    serverId = target.serverId,
                    profile = target.hermesProfile,
                    key = target.sessionId,
                ),
            )
            ServerKind.WEBUI -> HermexDeepLink.webuiPushUri(
                serverId = target.serverId,
                sessionId = target.sessionId,
                opensOnSource = true,
            )
        }
}
