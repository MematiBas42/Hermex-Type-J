package io.github.jcartu.hermex.ui.feature.sessions

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import io.github.jcartu.hermex.model.AutomatedSessionVisibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Port of the session-list `@AppStorage` settings from
 * `Features/SessionList/SessionRowView.swift` (`SessionRowDisplaySettings` /
 * `SessionSidebarDisclosureSettings`), backed by a feature-local Preferences
 * DataStore. Key strings mirror the iOS storage keys exactly.
 *
 * The CLI and Claude Code toggles are stored per server (issue #19): the CLI
 * value mirrors the active server's own `show_cli_sessions` setting, so a
 * value adopted from server A must never leak to server B. The pre-#19 global
 * CLI key survives only as the migration seed for servers with no per-server
 * value yet.
 */
data class SessionListSettings(
    val showsMessageCount: Boolean = true,
    val showsWorkspace: Boolean = true,
    val showsCronSessions: Boolean = true,
    val showsCliSessions: Boolean = true,
    val showsClaudeCodeSessions: Boolean = true,
    val showsSubagentSessions: Boolean = DEFAULT_SHOWS_SUBAGENT_SESSIONS,
    val projectsAreExpanded: Boolean = DEFAULT_PROJECTS_ARE_EXPANDED,
    val scheduledSessionsAreExpanded: Boolean = DEFAULT_SCHEDULED_SESSIONS_ARE_EXPANDED,
) {
    val automatedVisibility: AutomatedSessionVisibility
        get() = AutomatedSessionVisibility(
            showsCron = showsCronSessions,
            showsCli = showsCliSessions,
            showsClaudeCode = showsClaudeCodeSessions,
            showsSubagents = showsSubagentSessions,
        )

    companion object {
        /** `SessionRowDisplaySettings.defaultShowsSubagentSessions`. */
        const val DEFAULT_SHOWS_SUBAGENT_SESSIONS = false

        /** `SessionSidebarDisclosureSettings.defaultProjectsAreExpanded`. */
        const val DEFAULT_PROJECTS_ARE_EXPANDED = false

        /** `SessionSidebarDisclosureSettings.defaultScheduledSessionsAreExpanded`. */
        const val DEFAULT_SCHEDULED_SESSIONS_ARE_EXPANDED = false
    }
}

private val Context.sessionListDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hermex_session_list_prefs",
)

/** Feature-local DataStore holding the session-list display settings. */
fun sessionListSettingsDataStore(context: Context): DataStore<Preferences> =
    context.applicationContext.sessionListDataStore

class SessionListSettingsRepository(private val dataStore: DataStore<Preferences>) {

    private companion object {
        val showMessageCountKey = booleanPreferencesKey("sessionRow.showMessageCount")
        val showWorkspaceKey = booleanPreferencesKey("sessionRow.showWorkspace")
        val showCronSessionsKey = booleanPreferencesKey("sessionRow.showCronSessions")
        val showSubagentSessionsKey = booleanPreferencesKey("sessionRow.showSubagentSessions")

        /** Legacy pre-#19 global CLI key — read-only migration seed. */
        val legacyShowCliSessionsKey = booleanPreferencesKey("sessionRow.showCliSessions")

        val projectsAreExpandedKey = booleanPreferencesKey("sessionSidebar.projectsAreExpanded")
        val scheduledSessionsAreExpandedKey =
            booleanPreferencesKey("sessionSidebar.scheduledSessionsAreExpanded")

        /** `SessionRowDisplaySettings.showCliSessionsKey(for:)` — key per server URL. */
        fun showCliSessionsKey(serverUrl: String): Preferences.Key<Boolean> =
            booleanPreferencesKey("sessionRow.showCliSessions|$serverUrl")

        /** `SessionRowDisplaySettings.showClaudeCodeSessionsKey(for:)`. */
        fun showClaudeCodeSessionsKey(serverUrl: String): Preferences.Key<Boolean> =
            booleanPreferencesKey("sessionRow.showClaudeCodeSessions|$serverUrl")
    }

    /** Settings for [serverUrl]; per-server CLI/Claude Code values fall back like the Swift statics. */
    fun settings(serverUrl: String): Flow<SessionListSettings> = dataStore.data.map { prefs ->
        SessionListSettings(
            showsMessageCount = prefs[showMessageCountKey] ?: true,
            showsWorkspace = prefs[showWorkspaceKey] ?: true,
            showsCronSessions = prefs[showCronSessionsKey] ?: true,
            showsCliSessions = prefs[showCliSessionsKey(serverUrl)]
                ?: prefs[legacyShowCliSessionsKey]
                ?: true,
            showsClaudeCodeSessions = prefs[showClaudeCodeSessionsKey(serverUrl)] ?: true,
            showsSubagentSessions = prefs[showSubagentSessionsKey]
                ?: SessionListSettings.DEFAULT_SHOWS_SUBAGENT_SESSIONS,
            projectsAreExpanded = prefs[projectsAreExpandedKey]
                ?: SessionListSettings.DEFAULT_PROJECTS_ARE_EXPANDED,
            scheduledSessionsAreExpanded = prefs[scheduledSessionsAreExpandedKey]
                ?: SessionListSettings.DEFAULT_SCHEDULED_SESSIONS_ARE_EXPANDED,
        )
    }

    suspend fun setShowsMessageCount(value: Boolean) =
        dataStore.edit { it[showMessageCountKey] = value }

    suspend fun setShowsWorkspace(value: Boolean) =
        dataStore.edit { it[showWorkspaceKey] = value }

    suspend fun setShowsCronSessions(value: Boolean) =
        dataStore.edit { it[showCronSessionsKey] = value }

    suspend fun setShowsCliSessions(serverUrl: String, value: Boolean) =
        dataStore.edit { it[showCliSessionsKey(serverUrl)] = value }

    suspend fun setShowsClaudeCodeSessions(serverUrl: String, value: Boolean) =
        dataStore.edit { it[showClaudeCodeSessionsKey(serverUrl)] = value }

    suspend fun setShowsSubagentSessions(value: Boolean) =
        dataStore.edit { it[showSubagentSessionsKey] = value }

    suspend fun setProjectsAreExpanded(value: Boolean) =
        dataStore.edit { it[projectsAreExpandedKey] = value }

    suspend fun setScheduledSessionsAreExpanded(value: Boolean) =
        dataStore.edit { it[scheduledSessionsAreExpandedKey] = value }
}
