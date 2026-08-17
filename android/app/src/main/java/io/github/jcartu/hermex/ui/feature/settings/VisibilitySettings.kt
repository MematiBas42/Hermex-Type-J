package io.github.jcartu.hermex.ui.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.visibilityDataStore by preferencesDataStore(name = "hermex_section_visibility")

/**
 * Main-page section visibility toggles, port of Swift
 * `SectionVisibilitySettings` (Config/AppTheme.swift). Keys mirror the iOS
 * `@AppStorage` keys. Every entry defaults to visible, so an install that
 * predates these toggles looks exactly as it did before. Purely a display
 * preference — nothing stops loading or syncing.
 */
class VisibilitySettings(private val dataStore: DataStore<Preferences>) {

    enum class Section(val key: String) {
        TASKS("sectionVisibility.tasks"),
        SKILLS("sectionVisibility.skills"),
        MEMORY("sectionVisibility.memory"),
        INSIGHTS("sectionVisibility.insights"),
        PROJECTS("sectionVisibility.projects"),
    }

    data class State(
        val tasks: Boolean = true,
        val skills: Boolean = true,
        val memory: Boolean = true,
        val insights: Boolean = true,
        val projects: Boolean = true,
    ) {
        fun isVisible(section: Section): Boolean = when (section) {
            Section.TASKS -> tasks
            Section.SKILLS -> skills
            Section.MEMORY -> memory
            Section.INSIGHTS -> insights
            Section.PROJECTS -> projects
        }
    }

    val state: Flow<State> = dataStore.data.map { prefs ->
        State(
            tasks = prefs[booleanPreferencesKey(Section.TASKS.key)] ?: true,
            skills = prefs[booleanPreferencesKey(Section.SKILLS.key)] ?: true,
            memory = prefs[booleanPreferencesKey(Section.MEMORY.key)] ?: true,
            insights = prefs[booleanPreferencesKey(Section.INSIGHTS.key)] ?: true,
            projects = prefs[booleanPreferencesKey(Section.PROJECTS.key)] ?: true,
        )
    }

    suspend fun setVisible(section: Section, visible: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey(section.key)] = visible }
    }

    companion object {
        fun create(context: Context): VisibilitySettings =
            VisibilitySettings(context.applicationContext.visibilityDataStore)
    }
}
