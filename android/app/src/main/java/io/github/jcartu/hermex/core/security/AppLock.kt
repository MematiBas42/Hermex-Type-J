package io.github.jcartu.hermex.core.security

import android.os.SystemClock
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of `AppLockCapability` (`Auth/AppLock.swift`): what the device can
 * unlock Hermex with right now.
 */
data class AppLockCapability(
    /** What the Settings label and the Unlock button name. */
    val method: Method,
    /** Android can't authenticate at all without a screen lock (Swift: a passcode). */
    val hasPasscode: Boolean,
) {
    enum class Method(val settingsTitle: String, val unlockTitle: String) {
        FACE("Require Face Unlock", "Unlock with face"),
        FINGERPRINT("Require Fingerprint", "Unlock with fingerprint"),
        DEVICE_CREDENTIAL("Require Screen Lock", "Unlock"),
    }

    companion object {
        /** Assumed until the device is asked; the lock never touches biometrics while off. */
        val ASSUMED = AppLockCapability(method = Method.DEVICE_CREDENTIAL, hasPasscode = true)
    }
}

/**
 * Port of Swift `AppLockAuthenticator`: biometrics or the device credential.
 * [AppLock] asks through this so tests can fake it; the real one needs an
 * Activity (`DeviceAppLockAuthenticator`), so the shell attaches it.
 */
interface AppLockAuthenticator {
    fun capability(): AppLockCapability

    /** Prompts once; true only when the device owner authenticated. */
    suspend fun authenticate(reason: String): Boolean
}

/**
 * Port of `Auth/AppLock.swift` (#885): the optional app lock — one Settings
 * toggle, off by default, app-wide rather than per server
 * ([SettingsRepository.Settings.appLockEnabled]). `MainActivity` feeds it the
 * activity's lifecycle (resume = active, pause = inactive, stop = background)
 * and draws `AppLockOverlay` whenever [State.showsLockOverlay] is true.
 *
 * - With the lock on, a cold launch is locked, and so is becoming active more
 *   than [relockDelayMillis] after entering the background. The time runs on the
 *   monotonic boot clock, so the phone sleeping counts and changing the clock
 *   doesn't. Pausing and resuming (a permission dialog, the biometric sheet)
 *   never locks.
 * - While on, the window is covered whenever it isn't active and carries
 *   `FLAG_SECURE`, so the app switcher shows no chat. The lock's own prompt
 *   doesn't cover.
 * - When locked, it prompts once by itself as the activity resumes; after a
 *   cancel or failure only the Unlock button tries again.
 * - Turning the lock on or off needs a successful authentication. It can't be
 *   turned on without a screen lock. If the screen lock is removed while it's
 *   on, the lock says so and Continue turns it off: it never opens silently.
 * - It is a cover, not a gate: the app stays composed behind it, so streams and
 *   reconnects keep running, and deep links and share handoffs land behind it.
 */
class AppLock(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val relockDelayMillis: Long = RELOCK_DELAY_MILLIS,
) {
    private enum class Phase { ACTIVE, INACTIVE, BACKGROUND }

    data class State(
        val isEnabled: Boolean = false,
        val isLocked: Boolean = false,
        /** The lock is on but the device has no screen lock, so nothing can unlock it. */
        val isPasscodeMissing: Boolean = false,
        val isAuthenticating: Boolean = false,
        val capability: AppLockCapability = AppLockCapability.ASSUMED,
        /** The activity isn't active and the lock is on. */
        val isCovered: Boolean = false,
    ) {
        val showsLockOverlay: Boolean get() = isLocked || isCovered
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Attached by the shell once an Activity exists; null means nothing can prompt. */
    @Volatile
    var authenticator: AppLockAuthenticator? = null

    private var phase = Phase.INACTIVE

    /**
     * A prompt of ours may be holding the activity paused: set when one starts,
     * cleared once the activity is active with no prompt running, or in the background.
     */
    private var promptHoldsScene = false
    private var backgroundedAt: Long? = null
    private var didPromptForThisLock = false

    /**
     * Reads the persisted toggle once at launch: with the lock on, launch is
     * locked. With the lock off, Settings asks the device when it appears;
     * nothing touches biometrics at launch.
     */
    suspend fun restore() {
        val isEnabled = settings.settings.first().appLockEnabled
        _state.update {
            it.copy(
                isEnabled = isEnabled,
                isLocked = isEnabled,
                capability = if (isEnabled) authenticator?.capability() ?: AppLockCapability.ASSUMED else AppLockCapability.ASSUMED,
                isCovered = computeCovered(isEnabled),
            )
        }
    }

    fun sceneWillResignActive() {
        phase = Phase.INACTIVE
        publishCover()
    }

    fun sceneDidEnterBackground() {
        phase = Phase.BACKGROUND
        promptHoldsScene = false
        backgroundedAt = now()
        publishCover()
    }

    /**
     * Locks if the activity was in the background too long, then prompts if this
     * lock hasn't yet. Returns that prompt so tests can wait for it.
     */
    fun sceneDidBecomeActive(): Job? {
        phase = Phase.ACTIVE
        if (!_state.value.isAuthenticating) promptHoldsScene = false
        val since = backgroundedAt
        if (since != null && _state.value.isEnabled && now() - since > relockDelayMillis) {
            _state.update { it.copy(isLocked = true) }
            didPromptForThisLock = false
        }
        backgroundedAt = null
        publishCover()

        val current = _state.value
        if (!current.isLocked || current.isAuthenticating) return null
        refreshCapability()
        // Checked on every return, so a screen lock set again brings the lock back.
        val passcodeMissing = !_state.value.capability.hasPasscode
        _state.update { it.copy(isPasscodeMissing = passcodeMissing) }
        if (passcodeMissing || didPromptForThisLock) return null
        didPromptForThisLock = true
        return scope.launch { unlock() }
    }

    /** The Unlock button, and the automatic prompt. */
    suspend fun unlock() {
        val current = _state.value
        if (!current.isLocked || current.isPasscodeMissing || current.isAuthenticating) return
        didPromptForThisLock = true
        if (authenticate(UNLOCK_REASON)) {
            _state.update { it.copy(isLocked = false) }
        } else {
            refreshCapability()
            if (!_state.value.capability.hasPasscode) {
                _state.update { it.copy(isPasscodeMissing = true) }
            }
        }
    }

    /** Continue on the "can't lock" note: the screen lock is gone, so the lock turns off. */
    fun continueWithoutPasscode() {
        if (!_state.value.isPasscodeMissing) return
        store(enabled = false)
    }

    /** The Settings toggle. Authenticates first, and leaves the lock as it was on failure. */
    suspend fun setEnabled(enabled: Boolean) {
        val current = _state.value
        if (enabled == current.isEnabled || current.isAuthenticating) return
        refreshCapability()
        if (!_state.value.capability.hasPasscode) {
            // Nothing can authenticate without a screen lock, and only the owner can remove one.
            if (!enabled) store(enabled = false)
            return
        }
        val reason = if (enabled) ENABLE_REASON else DISABLE_REASON
        if (authenticate(reason)) {
            store(enabled = enabled)
        }
    }

    fun refreshCapability() {
        val current = authenticator?.capability() ?: return
        if (current != _state.value.capability) {
            _state.update { it.copy(capability = current) }
        }
    }

    private suspend fun authenticate(reason: String): Boolean {
        val authenticator = authenticator ?: return false
        _state.update { it.copy(isAuthenticating = true) }
        promptHoldsScene = true
        publishCover()
        val succeeded = try {
            authenticator.authenticate(reason)
        } finally {
            _state.update { it.copy(isAuthenticating = false) }
            if (phase == Phase.ACTIVE) promptHoldsScene = false
            publishCover()
        }
        return succeeded
    }

    private fun store(enabled: Boolean) {
        scope.launch { settings.setAppLockEnabled(enabled) }
        _state.update {
            it.copy(
                isEnabled = enabled,
                isLocked = if (enabled) it.isLocked else false,
                isPasscodeMissing = if (enabled) it.isPasscodeMissing else false,
                isCovered = computeCovered(enabled),
            )
        }
    }

    /** Port of `isCovered`: the activity isn't active and the lock is on. */
    private fun computeCovered(isEnabled: Boolean): Boolean {
        if (!isEnabled) return false
        return when (phase) {
            Phase.ACTIVE -> false
            Phase.INACTIVE -> !promptHoldsScene
            Phase.BACKGROUND -> true
        }
    }

    private fun publishCover() {
        val covered = computeCovered(_state.value.isEnabled)
        if (covered != _state.value.isCovered) {
            _state.update { it.copy(isCovered = covered) }
        }
    }

    companion object {
        /** Background time after which becoming active locks again (`AppLock.relockDelay`). */
        const val RELOCK_DELAY_MILLIS = 60_000L

        const val UNLOCK_REASON = "Unlock Hermex"
        const val ENABLE_REASON = "Turn on the app lock"
        const val DISABLE_REASON = "Turn off the app lock"
    }
}
