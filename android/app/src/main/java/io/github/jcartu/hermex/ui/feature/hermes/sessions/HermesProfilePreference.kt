package io.github.jcartu.hermex.ui.feature.hermes.sessions

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Port of Features/Chat/HermesChatSettings.swift's HermesProfilePreference; never changes the host's default. */
object HermesProfilePreference {
    private const val FILE = "hermex_hermes_profiles"
    private val observed = mutableMapOf<String, MutableStateFlow<String?>>()
    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(serverId: String) = "hermes.selectedProfile|$serverId"
    fun selected(context: Context, serverId: String): String? = prefs(context).getString(key(serverId), null)
    @Synchronized fun observe(context: Context, serverId: String): StateFlow<String?> =
        observed.getOrPut(serverId) { MutableStateFlow(selected(context, serverId)) }
    @Synchronized fun save(context: Context, serverId: String, profile: String?) {
        prefs(context).edit { putString(key(serverId), profile) }
        observed[serverId]?.value = profile
    }
    fun showsAllProfiles(context: Context, serverId: String): Boolean = prefs(context).getBoolean("hermes.sessionsShowAllProfiles|$serverId", false)
    fun saveShowsAllProfiles(context: Context, serverId: String, all: Boolean) {
        prefs(context).edit { putBoolean("hermes.sessionsShowAllProfiles|$serverId", all) }
    }
    fun lastListed(context: Context, serverId: String): String? = prefs(context).getString("hermes.lastListedProfile|$serverId", null)
    fun saveLastListed(context: Context, serverId: String, profile: String?) {
        prefs(context).edit { putString("hermes.lastListedProfile|$serverId", profile) }
    }
    fun resolve(context: Context, serverId: String, listed: List<String>, current: String): String {
        val saved = selected(context, serverId) ?: return current
        if (listed.isEmpty()) return current
        if (saved in listed) return saved
        save(context, serverId, null)
        return current
    }
    fun clear(context: Context, serverId: String) {
        save(context, serverId, null)
        saveLastListed(context, serverId, null)
        saveShowsAllProfiles(context, serverId, false)
    }
}

/** Injectable preferences for the list, equivalent to the Swift source's UserDefaults parameter. */
interface HermesListPreferences {
    var selected: String?
    var showsAllProfiles: Boolean
    var lastListed: String?
    fun resolve(listed: List<String>, current: String): String {
        val saved = selected ?: return current
        if (listed.isEmpty()) return current
        if (saved in listed) return saved
        selected = null
        return current
    }
}

/** The production preference adapter; memory fakes belong in tests, not in the application. */
class SavedHermesListPreferences(private val context: Context, private val serverId: String) : HermesListPreferences {
    override var selected: String?
        get() = HermesProfilePreference.selected(context, serverId)
        set(value) = HermesProfilePreference.save(context, serverId, value)
    override var showsAllProfiles: Boolean
        get() = HermesProfilePreference.showsAllProfiles(context, serverId)
        set(value) = HermesProfilePreference.saveShowsAllProfiles(context, serverId, value)
    override var lastListed: String?
        get() = HermesProfilePreference.lastListed(context, serverId)
        set(value) = HermesProfilePreference.saveLastListed(context, serverId, value)
}
