package io.github.jcartu.hermex.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port of `AppIntentRouter`'s pending voice hand-off
 * (`AppIntents/HermexAppIntents.swift`, #338): a "New Chat with Voice"
 * request crosses from the link router to whichever new-chat composer appears
 * next, without a reference to the live view tree — a shared bridge is the
 * standard pattern because the intent has none. The composer consumes the
 * request once and starts dictation through its normal tap path (mic
 * permission prompt when undetermined; its own hidden/denied state when
 * denied).
 */
object VoiceDictationRequest {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Records a voice auto-start for the next new-chat composer. */
    fun request() {
        _pending.value = true
    }

    /** The composer takes the request; later composers are unaffected. */
    fun consume() {
        _pending.value = false
    }
}
