package io.github.jcartu.hermex.core.prefs

import android.content.SharedPreferences
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.model.ServerRegistry
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * `ServerRegistry.Keychain` on Android: the registry blob is the list of
 * server URLs (a credential, PROJECT_SPEC Phase 1), so it is encrypted under the
 * Android Keystore and kept in a private [SharedPreferences] file — the
 * synchronous store the registry's load-once-at-init / write-through design
 * (`Models/ServerAccount.swift`) needs. Writes go through `commit()` so the
 * blob is on disk before the registry reports the mutation.
 */
class EncryptedPreferencesKeychain(
    private val preferences: SharedPreferences,
    private val cipher: StringCipher,
) : ServerRegistry.Keychain {
    override fun save(value: String, key: String) {
        if (!preferences.edit().putString(key, cipher.encrypt(value)).commit()) {
            throw IOException("The server registry could not be written.")
        }
    }

    override fun load(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        return cipher.decrypt(stored)
    }

    companion object {
        const val PREFERENCES_NAME = "hermex_servers"
    }
}

/**
 * `ServerRegistry.IdentityDefaults` over [SettingsRepository]: the global
 * identity keys (`sessionIdentity.displayName`, `sessionIdentity.initials`,
 * `headerLogoColorHex`) the registry seeds a new server from and mirrors the
 * active server's identity into (`mirrorIdentityToDefaults`). DataStore is
 * asynchronous while the registry reads synchronously, so [load] primes an
 * in-memory copy once at startup and every write updates that copy before the
 * DataStore edit is launched — a read right after a write already sees it.
 */
class SettingsIdentityDefaults(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : ServerRegistry.IdentityDefaults {
    private val lock = Any()
    private val values = HashMap<String, String>()

    /** Primes the copy from the persisted settings; call before the registry is used. */
    suspend fun load() {
        val current = settings.settings.first()
        synchronized(lock) {
            values[DISPLAY_NAME_KEY] = current.identityDisplayName
            values[INITIALS_KEY] = current.identityInitials
            values[HEADER_LOGO_COLOR_KEY] = current.headerLogoColorHex
        }
    }

    override fun string(key: String): String? = synchronized(lock) { values[key] }

    override fun set(value: String, key: String) {
        synchronized(lock) { values[key] = value }
        scope.launch {
            when (key) {
                DISPLAY_NAME_KEY -> settings.setIdentityDisplayName(value)
                INITIALS_KEY -> settings.setIdentityInitials(value)
                HEADER_LOGO_COLOR_KEY -> settings.setHeaderLogoColor(value)
            }
        }
    }

    companion object {
        /** `SessionIdentitySettings.displayNameKey` (`Features/SessionList/SessionRowView.swift`). */
        const val DISPLAY_NAME_KEY = "sessionIdentity.displayName"

        /** `SessionIdentitySettings.initialsKey`. */
        const val INITIALS_KEY = "sessionIdentity.initials"

        /** `HeaderLogoColor.storageKey` (`Config/AppTheme.swift`). */
        const val HEADER_LOGO_COLOR_KEY = "headerLogoColorHex"
    }
}
