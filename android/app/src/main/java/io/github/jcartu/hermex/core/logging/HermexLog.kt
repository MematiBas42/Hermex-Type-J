package io.github.jcartu.hermex.core.logging

import android.util.Log

/**
 * Thin logging wrapper so call sites never depend on android.util.Log directly
 * (keeps JVM unit tests quiet) and the tag stays consistent.
 */
object HermexLog {

    private const val TAG = "Hermex"

    fun d(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
