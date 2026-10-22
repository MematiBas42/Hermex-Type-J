package io.github.jcartu.hermex.core.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the OS lets Hermex post right now — the subset of `UNAuthorizationStatus`
 * the offer decides on. Below API 33 Android has no runtime permission, so
 * "notifications enabled for the app" maps to [GRANTED] and off to [DENIED].
 */
enum class NotificationAuthorization { NOT_DETERMINED, GRANTED, DENIED }

/**
 * Port of `Config/NotificationOffer.swift` (#863): the one-time question after the
 * first run started from the phone, "Want a notification when it's done?". Asked
 * once per install, never per server; the flag lives in
 * [SettingsRepository.Settings.hasOfferedNotifications].
 *
 * The chat calls [claimForRunStart] as a run starts; when it returns true the
 * app shell shows the dialog bound to [pending]. The flag is set before the
 * offer is returned, so a crash or kill can never show it twice; a null
 * decision leaves the flag unset, so a later run can still ask. Android has no
 * push relay path, so the only offer is iOS's `.localAlerts` (the fork's
 * foreground run monitor + local notifications).
 */
class NotificationOffer(
    private val settings: SettingsRepository,
    private val authorization: suspend () -> NotificationAuthorization,
) {
    private val _pending = MutableStateFlow(false)

    /** True while the claimed offer waits for an answer in the shell's dialog. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private val claimLock = Mutex()

    /**
     * Port of `NotificationOffer.claim(server:…)`: decides once, marks the offer
     * as made, and raises [pending]. Every send after the offer, or with alerts
     * already on, returns false before any permission read.
     */
    suspend fun claimForRunStart(): Boolean = claimLock.withLock {
        val current = settings.settings.first()
        if (current.hasOfferedNotifications || current.backgroundRunNotificationsEnabled) return false
        if (!shouldOffer(
                hasOffered = current.hasOfferedNotifications,
                localAlertsEnabled = current.backgroundRunNotificationsEnabled,
                authorization = authorization(),
            )
        ) {
            return false
        }
        settings.setHasOfferedNotifications(true)
        _pending.value = true
        true
    }

    /** "Not now", or the offer's own button once it acted: closes the dialog. */
    fun dismiss() {
        _pending.value = false
    }

    companion object {
        /**
         * Port of `NotificationOffer.decide`: nothing worth asking once asked, with
         * local alerts already on, or with the OS permission refused.
         */
        fun shouldOffer(
            hasOffered: Boolean,
            localAlertsEnabled: Boolean,
            authorization: NotificationAuthorization,
        ): Boolean = !hasOffered && !localAlertsEnabled && authorization != NotificationAuthorization.DENIED
    }
}
