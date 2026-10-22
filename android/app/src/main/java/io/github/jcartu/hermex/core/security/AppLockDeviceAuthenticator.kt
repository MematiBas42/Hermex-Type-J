package io.github.jcartu.hermex.core.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.content.pm.PackageManager as AndroidPackageManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Port of `DeviceAppLockAuthenticator` (`Auth/AppLock.swift`) on the Android
 * framework: `android.hardware.biometrics.BiometricPrompt` (API 28+) with the
 * device credential as fallback, and `KeyguardManager`'s confirm-credential
 * screen on API 26–27 or when no biometric is enrolled. A fresh prompt per
 * call, so one attempt's result never carries into the next. Needs the
 * Activity: the prompt is drawn over it and the credential screen returns
 * through its result registry, registered once in `onCreate`.
 */
class DeviceAppLockAuthenticator(private val activity: ComponentActivity) : AppLockAuthenticator {

    private var pendingCredential: CompletableDeferred<Boolean>? = null

    private val credentialLauncher: ActivityResultLauncher<android.content.Intent> =
        activity.activityResultRegistry.register(
            CREDENTIAL_RESULT_KEY,
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            pendingCredential?.complete(result.resultCode == Activity.RESULT_OK)
            pendingCredential = null
        }

    /** Releases the result registration; call from the Activity's `onDestroy`. */
    fun dispose() {
        credentialLauncher.unregister()
        pendingCredential?.complete(false)
        pendingCredential = null
    }

    override fun capability(): AppLockCapability {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val hasPasscode = keyguard?.isDeviceSecure == true
        // Without a screen lock biometrics can't be enrolled, so name the hardware, as device Settings does.
        return AppLockCapability(
            method = enrolledBiometric() ?: AppLockCapability.Method.DEVICE_CREDENTIAL,
            hasPasscode = hasPasscode,
        )
    }

    /** Biometrics, falling back to the device credential. */
    override suspend fun authenticate(reason: String): Boolean = withContext(Dispatchers.Main.immediate) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> biometricPrompt(reason) == PromptOutcome.SUCCEEDED
            Build.VERSION.SDK_INT == Build.VERSION_CODES.P && enrolledBiometric() != null ->
                when (biometricPrompt(reason)) {
                    PromptOutcome.SUCCEEDED -> true
                    PromptOutcome.FAILED -> false
                    PromptOutcome.USE_CREDENTIAL -> confirmDeviceCredential(reason)
                }
            else -> confirmDeviceCredential(reason)
        }
    }

    /** The enrolled biometric kind, or null when the device can't use one right now. */
    private fun enrolledBiometric(): AppLockCapability.Method? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val enrolled = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                activity.getSystemService(BiometricManager::class.java)
                    ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION")
                activity.getSystemService(BiometricManager::class.java)
                    ?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            else ->
                // API 28 has no BiometricManager; the fingerprint feature flag is the
                // available signal, and the prompt itself reports "no fingerprints".
                activity.packageManager.hasSystemFeature(AndroidPackageManager.FEATURE_FINGERPRINT)
        }
        if (!enrolled) return null
        val packageManager = activity.packageManager
        val hasFace = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)
        val hasFingerprint = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        return if (hasFace && !hasFingerprint) AppLockCapability.Method.FACE else AppLockCapability.Method.FINGERPRINT
    }

    private enum class PromptOutcome { SUCCEEDED, FAILED, USE_CREDENTIAL }

    /**
     * `BiometricPrompt`: on API 30+ biometrics or the device credential in one
     * sheet; API 29 the same through the deprecated flag; API 28 biometrics only,
     * with a "Use screen lock" button that hands over to the credential screen.
     */
    private suspend fun biometricPrompt(reason: String): PromptOutcome = suspendCancellableCoroutine { continuation ->
        val builder = BiometricPrompt.Builder(activity).setTitle(reason)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            else -> builder.setNegativeButton("Use screen lock", activity.mainExecutor) { _, _ ->
                if (continuation.isActive) continuation.resume(PromptOutcome.USE_CREDENTIAL)
            }
        }
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        builder.build().authenticate(
            signal,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(PromptOutcome.SUCCEEDED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val outcome = if (errorCode == ERROR_NEGATIVE_BUTTON) {
                        PromptOutcome.USE_CREDENTIAL
                    } else {
                        PromptOutcome.FAILED
                    }
                    continuation.resume(outcome)
                }

                // A failed attempt keeps the sheet up for another try; the sheet
                // ends with success, cancel or lockout, which arrive as errors.
            },
        )
    }

    /** `KeyguardManager.createConfirmDeviceCredentialIntent`: null without a screen lock. */
    private suspend fun confirmDeviceCredential(reason: String): Boolean {
        val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return false
        val intent = keyguard.createConfirmDeviceCredentialIntent(reason, null) ?: return false
        pendingCredential?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingCredential = deferred
        credentialLauncher.launch(intent)
        return deferred.await()
    }

    private companion object {
        /** `BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON` (hidden in the framework; 13 in AndroidX too). */
        const val ERROR_NEGATIVE_BUTTON = 13
        const val CREDENTIAL_RESULT_KEY = "io.github.jcartu.hermex.appLock.confirmCredential"
    }
}
