package io.github.jcartu.hermex.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.jcartu.hermex.ui.theme.Palette

/**
 * User-tweakable app settings (mirrors the iOS app's @AppStorage defaults;
 * keys follow the iOS storage keys where one exists). All values are local-only.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private companion object {
        val paletteKey = stringPreferencesKey("appPalette")
        val themeKey = stringPreferencesKey("appTheme")
        val headerLogoColorKey = stringPreferencesKey("headerLogoColorHex")
        val hapticsEnabledKey = booleanPreferencesKey("appHaptics.isEnabled")
        val backgroundRunNotificationsKey =
            booleanPreferencesKey("responseCompletionNotifications.isEnabled")
        val showsThinkingAndToolCardsKey = booleanPreferencesKey("chatTranscript.showsThinkingAndToolCards")
        val thinkingCardsStartExpandedKey = booleanPreferencesKey("chatTranscript.thinkingCardsStartExpanded")
        val toolCardsStartExpandedKey = booleanPreferencesKey("chatTranscript.toolCardsStartExpanded")
        val hidesAttachmentPathsKey = booleanPreferencesKey("chatTranscript.hidesAttachmentPaths")
        val showsAssistantTurnTimestampsKey = booleanPreferencesKey("chatTranscript.showsAssistantTurnTimestamps")
        val showsResponseSpeedKey = booleanPreferencesKey("chatTranscript.showsResponseSpeed")
        val wrapsCodeBlockLinesKey = booleanPreferencesKey("chatTranscript.wrapsCodeBlockLines")
        val rtlChatLayoutEnabledKey = booleanPreferencesKey("chatTranscript.rtlChatLayoutEnabled")
    }

    enum class Theme { SYSTEM, LIGHT, DARK }

    data class Settings(
        val palette: Palette = Palette.DEFAULT,
        val theme: Theme = Theme.SYSTEM,
        /** Accent hex; when the user has never picked one it is the palette's own accent. */
        val headerLogoColorHex: String = Palette.DEFAULT.defaultAccentHex,
        val hapticsEnabled: Boolean = true,
        val backgroundRunNotificationsEnabled: Boolean = false,
        val showsThinkingAndToolCards: Boolean = true,
        val thinkingCardsStartExpanded: Boolean = false,
        val toolCardsStartExpanded: Boolean = false,
        val hidesAttachmentPaths: Boolean = false,
        val showsAssistantTurnTimestamps: Boolean = false,
        val showsResponseSpeed: Boolean = false,
        val wrapsCodeBlockLines: Boolean = false,
        val rtlChatLayoutEnabled: Boolean = false,
    )

    val settings: Flow<Settings> = dataStore.data.map { prefs ->
        val palette = Palette.fromName(prefs[paletteKey])
        Settings(
            palette = palette,
            theme = prefs[themeKey]?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.SYSTEM,
            headerLogoColorHex = prefs[headerLogoColorKey] ?: palette.defaultAccentHex,
            hapticsEnabled = prefs[hapticsEnabledKey] ?: true,
            backgroundRunNotificationsEnabled = prefs[backgroundRunNotificationsKey] ?: false,
            showsThinkingAndToolCards = prefs[showsThinkingAndToolCardsKey] ?: true,
            thinkingCardsStartExpanded = prefs[thinkingCardsStartExpandedKey] ?: false,
            toolCardsStartExpanded = prefs[toolCardsStartExpandedKey] ?: false,
            hidesAttachmentPaths = prefs[hidesAttachmentPathsKey] ?: false,
            showsAssistantTurnTimestamps = prefs[showsAssistantTurnTimestampsKey] ?: false,
            showsResponseSpeed = prefs[showsResponseSpeedKey] ?: false,
            wrapsCodeBlockLines = prefs[wrapsCodeBlockLinesKey] ?: false,
            rtlChatLayoutEnabled = prefs[rtlChatLayoutEnabledKey] ?: false,
        )
    }

    suspend fun setPalette(palette: Palette) = dataStore.edit { it[paletteKey] = palette.name }
    suspend fun setTheme(theme: Theme) = dataStore.edit { it[themeKey] = theme.name }
    suspend fun setHeaderLogoColor(hex: String) = dataStore.edit { it[headerLogoColorKey] = hex }
    suspend fun setHapticsEnabled(enabled: Boolean) = dataStore.edit { it[hapticsEnabledKey] = enabled }
    suspend fun setBackgroundRunNotificationsEnabled(enabled: Boolean) =
        dataStore.edit { it[backgroundRunNotificationsKey] = enabled }
    suspend fun setShowsThinkingAndToolCards(enabled: Boolean) = dataStore.edit { it[showsThinkingAndToolCardsKey] = enabled }
    suspend fun setThinkingCardsStartExpanded(enabled: Boolean) = dataStore.edit { it[thinkingCardsStartExpandedKey] = enabled }
    suspend fun setToolCardsStartExpanded(enabled: Boolean) = dataStore.edit { it[toolCardsStartExpandedKey] = enabled }
    suspend fun setHidesAttachmentPaths(enabled: Boolean) = dataStore.edit { it[hidesAttachmentPathsKey] = enabled }
    suspend fun setShowsAssistantTurnTimestamps(enabled: Boolean) = dataStore.edit { it[showsAssistantTurnTimestampsKey] = enabled }
    suspend fun setShowsResponseSpeed(enabled: Boolean) = dataStore.edit { it[showsResponseSpeedKey] = enabled }
    suspend fun setWrapsCodeBlockLines(enabled: Boolean) = dataStore.edit { it[wrapsCodeBlockLinesKey] = enabled }
    suspend fun setRtlChatLayoutEnabled(enabled: Boolean) = dataStore.edit { it[rtlChatLayoutEnabledKey] = enabled }
}
