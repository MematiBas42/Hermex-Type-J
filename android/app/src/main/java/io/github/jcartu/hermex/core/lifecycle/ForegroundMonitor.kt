package io.github.jcartu.hermex.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Android counterpart of the iOS `scenePhase == .active` triggers: a
 * process-wide foreground counter driven by [ProcessLifecycleOwner]. Screens
 * that must refresh when the app returns (the session list, 1.8.0 "Sessions
 * refresh on foregrounding"; chat stream recovery) collect [foregroundEvents]
 * and act on every bump; the initial value is 0 so a fresh collector does
 * nothing until the next foreground.
 */
class ForegroundMonitor {
    private val _foregroundEvents = MutableStateFlow(0)
    val foregroundEvents: StateFlow<Int> = _foregroundEvents.asStateFlow()

    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    private var isObserving = false

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            _isInForeground.value = true
            _foregroundEvents.update { it + 1 }
        }

        override fun onStop(owner: LifecycleOwner) {
            _isInForeground.value = false
        }
    }

    /** Must be called on the main thread (ProcessLifecycleOwner's contract). */
    fun start() {
        if (isObserving) return
        isObserving = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }
}
