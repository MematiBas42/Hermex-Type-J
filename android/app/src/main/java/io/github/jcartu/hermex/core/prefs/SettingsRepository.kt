package io.github.jcartu.hermex.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.ui.theme.Palette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Port of `ThinkingAndToolCards` (`Config/AppTheme.swift`, #1254): Settings → Chat
 * "Thinking & Tools" — whether a chat shows thinking and tool cards, and whether
 * they start open. Presented as one picker; stored as the three pre-#1254 keys
 * (`showsThinkingAndToolCards` / `thinkingCardsStartExpanded` /
 * `toolCardsStartExpanded`) so an upgraded install keeps its choice and every
 * reader of those keys keeps working.
 */
enum class ThinkingAndToolsMode(val title: String) {
    HIDDEN("Hidden"),
    COLLAPSED("Collapsed"),
    EXPANDED("Expanded");

    val showsCards: Boolean get() = this != HIDDEN
    val startsExpanded: Boolean get() = this == EXPANDED

    companion object {
        /**
         * Port of `ChatTranscriptDisplaySettings.migrateThinkingAndToolCards`: cards
         * off is Hidden, either expand toggle on is Expanded, otherwise Collapsed.
         */
        fun fromToggles(
            showsCards: Boolean,
            thinkingCardsStartExpanded: Boolean,
            toolCardsStartExpanded: Boolean,
        ): ThinkingAndToolsMode = when {
            !showsCards -> HIDDEN
            thinkingCardsStartExpanded || toolCardsStartExpanded -> EXPANDED
            else -> COLLAPSED
        }
    }
}

/**
 * User-tweakable app settings (mirrors the iOS app's @AppStorage defaults;
 * keys follow the iOS storage keys where one exists). All values are local-only
 * and apply on this device to every server (`docs/agents/multi-server-state-isolation.md`
 * "Global (intentionally shared) state").
 *
 * The section-visibility toggles (`sectionVisibility.*`, port of
 * `SectionVisibilitySettings` in `Config/AppTheme.swift`) live in
 * [visibilityDataStore]: the fork stored them in their own file before this
 * repository absorbed them, and an upgraded install must keep its choices.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val visibilityDataStore: DataStore<Preferences> = dataStore,
) {

    private companion object {
        val paletteKey = stringPreferencesKey("appPalette")
        val themeKey = stringPreferencesKey("appTheme")
        val identityDisplayNameKey = stringPreferencesKey("sessionIdentity.displayName")
        val identityInitialsKey = stringPreferencesKey("sessionIdentity.initials")
        val headerLogoColorKey = stringPreferencesKey("headerLogoColorHex")
        val hapticsEnabledKey = booleanPreferencesKey("appHaptics.isEnabled")
        val backgroundRunNotificationsKey =
            booleanPreferencesKey("responseCompletionNotifications.isEnabled")
        val hasRequestedNotificationPermissionKey =
            booleanPreferencesKey("responseCompletionNotifications.hasRequestedPermission")
        val hasOfferedNotificationsKey = booleanPreferencesKey("notificationOffer.hasOffered")
        val notifyRepliesKey = booleanPreferencesKey("notifications.replies")
        val notifyApprovalsKey = booleanPreferencesKey("notifications.approvals")
        val notifyQuestionsKey = booleanPreferencesKey("notifications.questions")
        val notifyFailedRunsKey = booleanPreferencesKey("notifications.failedRuns")
        val showsThinkingAndToolCardsKey = booleanPreferencesKey("chatTranscript.showsThinkingAndToolCards")
        val thinkingCardsStartExpandedKey = booleanPreferencesKey("chatTranscript.thinkingCardsStartExpanded")
        val toolCardsStartExpandedKey = booleanPreferencesKey("chatTranscript.toolCardsStartExpanded")
        val foldsSettledTurnsKey = booleanPreferencesKey("chatTranscript.foldsSettledTurns")
        val hidesAttachmentPathsKey = booleanPreferencesKey("chatTranscript.hidesAttachmentPaths")
        val showsAssistantTurnTimestampsKey = booleanPreferencesKey("chatTranscript.showsAssistantTurnTimestamps")
        val showsResponseSpeedKey = booleanPreferencesKey("chatTranscript.showsResponseSpeed")
        val wrapsCodeBlockLinesKey = booleanPreferencesKey("chatTranscript.wrapsCodeBlockLines")
        val rtlChatLayoutEnabledKey = booleanPreferencesKey("chatTranscript.rtlChatLayoutEnabled")
        val dismissKeyboardAfterSendKey = booleanPreferencesKey("sessionChat.dismissKeyboardAfterSend")
        val completionPositionKey = stringPreferencesKey("sessionChat.completionPosition")
        val appLockEnabledKey = booleanPreferencesKey("appLockEnabled")
        val showsSessionRowStatesKey = booleanPreferencesKey("sessionRow.showsStates")
        val showsTasksEntryKey = booleanPreferencesKey("sectionVisibility.tasks")
        val showsKanbanEntryKey = booleanPreferencesKey("sectionVisibility.kanban")
        val showsSkillsEntryKey = booleanPreferencesKey("sectionVisibility.skills")
        val showsMemoryEntryKey = booleanPreferencesKey("sectionVisibility.memory")
        val showsInsightsEntryKey = booleanPreferencesKey("sectionVisibility.insights")
        val showsActiveProfileEntryKey = booleanPreferencesKey("sectionVisibility.activeProfile")
        val showsProjectsEntryKey = booleanPreferencesKey("sectionVisibility.projects")
        val showsFilesButtonKey = booleanPreferencesKey("sectionVisibility.chatFiles")
        val showsGitActionsKey = booleanPreferencesKey("sectionVisibility.chatGit")

        /** `SessionChatPreferences.CompletionPosition` raw values (`Features/Chat/ChatComposerSendGate.swift`). */
        const val COMPLETION_POSITION_LATEST = "latest"
        const val COMPLETION_POSITION_BEGINNING = "beginning"
    }

    enum class Theme { SYSTEM, LIGHT, DARK }

    data class Settings(
        val palette: Palette = Palette.DEFAULT,
        val theme: Theme = Theme.DARK,
        /** Accent hex; when the user has never picked one it is the palette's own accent. */
        val headerLogoColorHex: String = Palette.DEFAULT.defaultAccentHex,
        val hapticsEnabled: Boolean = true,
        val backgroundRunNotificationsEnabled: Boolean = false,
        /** iOS `responseCompletionNotifications.hasRequestedPermission`: the OS prompt was shown once. */
        val hasRequestedNotificationPermission: Boolean = false,
        /**
         * The active server's display name / initials, mirrored here from its
         * registry entry by `ServerRegistry.mirrorIdentityToDefaults` (iOS
         * `SessionIdentitySettings.displayNameKey` / `initialsKey`); the avatar pill reads them.
         */
        val identityDisplayName: String = "",
        val identityInitials: String = "",
        /** iOS `notificationOffer.hasOffered` (#863): the one-time "Want a notification?" question was asked. */
        val hasOfferedNotifications: Boolean = false,
        /** Which local alerts the foreground run monitor posts, per kind (Android equivalent of `PushPreferences`). */
        val notifyReplies: Boolean = true,
        val notifyApprovals: Boolean = true,
        val notifyQuestions: Boolean = true,
        val notifyFailedRuns: Boolean = true,
        val showsThinkingAndToolCards: Boolean = true,
        val thinkingCardsStartExpanded: Boolean = false,
        val toolCardsStartExpanded: Boolean = false,
        /** Settings → Chat "Fold Finished Turns" (`chatTranscript.foldsSettledTurns`, 1.6.0). */
        val foldsSettledTurns: Boolean = true,
        val hidesAttachmentPaths: Boolean = false,
        val showsAssistantTurnTimestamps: Boolean = false,
        val showsResponseSpeed: Boolean = false,
        val wrapsCodeBlockLines: Boolean = false,
        val rtlChatLayoutEnabled: Boolean = false,
        /** Settings → Composer "Dismiss keyboard after sending" (1.9.0). */
        val dismissKeyboardAfterSend: Boolean = false,
        /** Settings → Chat "Position after response completes" = beginning (1.9.0). */
        val readCompletedResponseFromStart: Boolean = false,
        /** The app lock (#885, 1.9.0); off until the owner turns it on. */
        val appLockEnabled: Boolean = false,
        /** Session rows show Approval / Input / Working states (1.6.0). */
        val showsSessionRowStates: Boolean = true,
        // Session-list entries and chat controls, port of `SectionVisibilitySettings`
        // (#189): every entry defaults to visible.
        val showsTasksEntry: Boolean = true,
        val showsKanbanEntry: Boolean = true,
        val showsSkillsEntry: Boolean = true,
        val showsMemoryEntry: Boolean = true,
        val showsInsightsEntry: Boolean = true,
        val showsActiveProfileEntry: Boolean = true,
        val showsProjectsEntry: Boolean = true,
        val showsFilesButton: Boolean = true,
        val showsGitActions: Boolean = true,
    ) {
        /** The three card toggles as the one Thinking & Tools picker (#1254). */
        val thinkingAndToolsMode: ThinkingAndToolsMode
            get() = ThinkingAndToolsMode.fromToggles(
                showsCards = showsThinkingAndToolCards,
                thinkingCardsStartExpanded = thinkingCardsStartExpanded,
                toolCardsStartExpanded = toolCardsStartExpanded,
            )
    }

    val settings: Flow<Settings> = combine(dataStore.data, visibilityDataStore.data) { prefs, visibility ->
        val palette = Palette.fromName(prefs[paletteKey])
        Settings(
            palette = palette,
            theme = prefs[themeKey]?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.DARK,
            headerLogoColorHex = prefs[headerLogoColorKey] ?: palette.defaultAccentHex,
            hapticsEnabled = prefs[hapticsEnabledKey] ?: true,
            backgroundRunNotificationsEnabled = prefs[backgroundRunNotificationsKey] ?: false,
            identityDisplayName = prefs[identityDisplayNameKey] ?: "",
            identityInitials = prefs[identityInitialsKey] ?: "",
            hasRequestedNotificationPermission = prefs[hasRequestedNotificationPermissionKey] ?: false,
            hasOfferedNotifications = prefs[hasOfferedNotificationsKey] ?: false,
            notifyReplies = prefs[notifyRepliesKey] ?: true,
            notifyApprovals = prefs[notifyApprovalsKey] ?: true,
            notifyQuestions = prefs[notifyQuestionsKey] ?: true,
            notifyFailedRuns = prefs[notifyFailedRunsKey] ?: true,
            showsThinkingAndToolCards = prefs[showsThinkingAndToolCardsKey] ?: true,
            thinkingCardsStartExpanded = prefs[thinkingCardsStartExpandedKey] ?: false,
            toolCardsStartExpanded = prefs[toolCardsStartExpandedKey] ?: false,
            foldsSettledTurns = prefs[foldsSettledTurnsKey] ?: true,
            hidesAttachmentPaths = prefs[hidesAttachmentPathsKey] ?: false,
            showsAssistantTurnTimestamps = prefs[showsAssistantTurnTimestampsKey] ?: false,
            showsResponseSpeed = prefs[showsResponseSpeedKey] ?: false,
            wrapsCodeBlockLines = prefs[wrapsCodeBlockLinesKey] ?: false,
            rtlChatLayoutEnabled = prefs[rtlChatLayoutEnabledKey] ?: false,
            dismissKeyboardAfterSend = prefs[dismissKeyboardAfterSendKey] ?: false,
            // `CompletionPosition.storedValue`: any unknown raw value reads as latest.
            readCompletedResponseFromStart = prefs[completionPositionKey] == COMPLETION_POSITION_BEGINNING,
            appLockEnabled = prefs[appLockEnabledKey] ?: false,
            showsSessionRowStates = prefs[showsSessionRowStatesKey] ?: true,
            showsTasksEntry = visibility[showsTasksEntryKey] ?: true,
            showsKanbanEntry = visibility[showsKanbanEntryKey] ?: true,
            showsSkillsEntry = visibility[showsSkillsEntryKey] ?: true,
            showsMemoryEntry = visibility[showsMemoryEntryKey] ?: true,
            showsInsightsEntry = visibility[showsInsightsEntryKey] ?: true,
            showsActiveProfileEntry = visibility[showsActiveProfileEntryKey] ?: true,
            showsProjectsEntry = visibility[showsProjectsEntryKey] ?: true,
            showsFilesButton = visibility[showsFilesButtonKey] ?: true,
            showsGitActions = visibility[showsGitActionsKey] ?: true,
        )
    }

    suspend fun setPalette(palette: Palette) = dataStore.edit { it[paletteKey] = palette.name }
    suspend fun setTheme(theme: Theme) = dataStore.edit { it[themeKey] = theme.name }
    suspend fun setHeaderLogoColor(hex: String) = dataStore.edit { it[headerLogoColorKey] = hex }
    suspend fun setHapticsEnabled(enabled: Boolean) = dataStore.edit { it[hapticsEnabledKey] = enabled }
    suspend fun setBackgroundRunNotificationsEnabled(enabled: Boolean) =
        dataStore.edit { it[backgroundRunNotificationsKey] = enabled }
    suspend fun setIdentityDisplayName(name: String) = dataStore.edit { it[identityDisplayNameKey] = name }
    suspend fun setIdentityInitials(initials: String) = dataStore.edit { it[identityInitialsKey] = initials }
    suspend fun setHasRequestedNotificationPermission(requested: Boolean) =
        dataStore.edit { it[hasRequestedNotificationPermissionKey] = requested }
    suspend fun setHasOfferedNotifications(offered: Boolean) =
        dataStore.edit { it[hasOfferedNotificationsKey] = offered }
    suspend fun setNotifyReplies(enabled: Boolean) = dataStore.edit { it[notifyRepliesKey] = enabled }
    suspend fun setNotifyApprovals(enabled: Boolean) = dataStore.edit { it[notifyApprovalsKey] = enabled }
    suspend fun setNotifyQuestions(enabled: Boolean) = dataStore.edit { it[notifyQuestionsKey] = enabled }
    suspend fun setNotifyFailedRuns(enabled: Boolean) = dataStore.edit { it[notifyFailedRunsKey] = enabled }
    suspend fun setShowsThinkingAndToolCards(enabled: Boolean) = dataStore.edit { it[showsThinkingAndToolCardsKey] = enabled }
    suspend fun setThinkingCardsStartExpanded(enabled: Boolean) = dataStore.edit { it[thinkingCardsStartExpandedKey] = enabled }
    suspend fun setToolCardsStartExpanded(enabled: Boolean) = dataStore.edit { it[toolCardsStartExpandedKey] = enabled }

    /**
     * The Thinking & Tools picker (#1254) writes the three underlying toggles in
     * one transaction: Hidden turns the cards off, Collapsed shows them closed,
     * Expanded shows them open. Reading them back through
     * [Settings.thinkingAndToolsMode] yields the same mode.
     */
    suspend fun setThinkingAndToolsMode(mode: ThinkingAndToolsMode) = dataStore.edit {
        it[showsThinkingAndToolCardsKey] = mode.showsCards
        it[thinkingCardsStartExpandedKey] = mode.startsExpanded
        it[toolCardsStartExpandedKey] = mode.startsExpanded
    }

    suspend fun setFoldsSettledTurns(enabled: Boolean) = dataStore.edit { it[foldsSettledTurnsKey] = enabled }
    suspend fun setHidesAttachmentPaths(enabled: Boolean) = dataStore.edit { it[hidesAttachmentPathsKey] = enabled }
    suspend fun setShowsAssistantTurnTimestamps(enabled: Boolean) = dataStore.edit { it[showsAssistantTurnTimestampsKey] = enabled }
    suspend fun setShowsResponseSpeed(enabled: Boolean) = dataStore.edit { it[showsResponseSpeedKey] = enabled }
    suspend fun setWrapsCodeBlockLines(enabled: Boolean) = dataStore.edit { it[wrapsCodeBlockLinesKey] = enabled }
    suspend fun setRtlChatLayoutEnabled(enabled: Boolean) = dataStore.edit { it[rtlChatLayoutEnabledKey] = enabled }
    suspend fun setDismissKeyboardAfterSend(enabled: Boolean) = dataStore.edit { it[dismissKeyboardAfterSendKey] = enabled }
    suspend fun setReadCompletedResponseFromStart(enabled: Boolean) = dataStore.edit {
        it[completionPositionKey] = if (enabled) COMPLETION_POSITION_BEGINNING else COMPLETION_POSITION_LATEST
    }
    suspend fun setAppLockEnabled(enabled: Boolean) = dataStore.edit { it[appLockEnabledKey] = enabled }
    suspend fun setShowsSessionRowStates(enabled: Boolean) = dataStore.edit { it[showsSessionRowStatesKey] = enabled }
    suspend fun setShowsTasksEntry(visible: Boolean) = visibilityDataStore.edit { it[showsTasksEntryKey] = visible }
    suspend fun setShowsKanbanEntry(visible: Boolean) = visibilityDataStore.edit { it[showsKanbanEntryKey] = visible }
    suspend fun setShowsSkillsEntry(visible: Boolean) = visibilityDataStore.edit { it[showsSkillsEntryKey] = visible }
    suspend fun setShowsMemoryEntry(visible: Boolean) = visibilityDataStore.edit { it[showsMemoryEntryKey] = visible }
    suspend fun setShowsInsightsEntry(visible: Boolean) = visibilityDataStore.edit { it[showsInsightsEntryKey] = visible }
    suspend fun setShowsActiveProfileEntry(visible: Boolean) =
        visibilityDataStore.edit { it[showsActiveProfileEntryKey] = visible }
    suspend fun setShowsProjectsEntry(visible: Boolean) = visibilityDataStore.edit { it[showsProjectsEntryKey] = visible }
    suspend fun setShowsFilesButton(visible: Boolean) = visibilityDataStore.edit { it[showsFilesButtonKey] = visible }
    suspend fun setShowsGitActions(visible: Boolean) = visibilityDataStore.edit { it[showsGitActionsKey] = visible }
}
