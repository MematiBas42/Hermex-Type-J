package io.github.jcartu.hermex.ui.feature.models

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ModelHistoryServerIsolationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var favorites: ModelFavoritesStore
    private lateinit var recents: ModelRecentsStore
    private val activeServer = stringPreferencesKey("server.base_url")

    @Before
    fun setUp() {
        storeScope = CoroutineScope(dispatcher + Job())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            temporaryFolder.newFile("server-models.preferences_pb")
        }
        favorites = ModelFavoritesStore(dataStore)
        recents = ModelRecentsStore(dataStore)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `legacy favorites migrate once without being copied to the next server`() = runTest(dispatcher) {
        val legacy = ModelFavoriteKey("legacy-model", "provider-a")
        favorites.save(listOf(legacy))
        dataStore.edit { it[activeServer] = "https://a.example" }
        assertEquals(listOf(legacy), favorites.currentFavoriteKeys())

        dataStore.edit { it[activeServer] = "https://b.example" }
        assertTrue(favorites.currentFavoriteKeys().isEmpty())
        favorites.toggleFavorite(ModelCatalogOption("new-model", "New", "provider-b"))
        assertEquals(listOf(ModelFavoriteKey("new-model", "provider-b")), favorites.currentFavoriteKeys())

        dataStore.edit { it[activeServer] = "https://a.example" }
        assertEquals(listOf(legacy), favorites.currentFavoriteKeys())
        assertTrue(dataStore.data.first().contains(stringPreferencesKey("hermes.mobile.favoriteModels")))
    }

    @Test
    fun `favorites and recents stay separate across active server changes`() = runTest(dispatcher) {
        val optionA = ModelCatalogOption("model-a", "A", "provider-a")
        val optionB = ModelCatalogOption("model-b", "B", "provider-b")
        dataStore.edit { it[activeServer] = "https://a.example" }
        favorites.toggleFavorite(optionA)
        recents.recordRecent(optionA)

        dataStore.edit { it[activeServer] = "https://b.example" }
        assertTrue(favorites.currentFavoriteKeys().isEmpty())
        assertTrue(recents.currentRecentKeys().isEmpty())
        favorites.toggleFavorite(optionB)
        recents.recordRecent(optionB)

        dataStore.edit { it[activeServer] = "https://a.example" }
        assertEquals(listOf(ModelFavoriteKey("model-a", "provider-a")), favorites.currentFavoriteKeys())
        assertEquals(listOf(ModelFavoriteKey("model-a", "provider-a")), recents.currentRecentKeys())
    }

    @Test
    fun `legacy recent models keep their order and migrate only to first server`() = runTest(dispatcher) {
        val keys = listOf(ModelFavoriteKey("newest"), ModelFavoriteKey("older"))
        recents.save(keys)
        dataStore.edit { it[activeServer] = "https://a.example" }
        assertEquals(keys, recents.currentRecentKeys())

        dataStore.edit { it[activeServer] = "https://b.example" }
        assertTrue(recents.currentRecentKeys().isEmpty())
        dataStore.edit { it[activeServer] = "https://a.example" }
        assertEquals(keys, recents.currentRecentKeys())
    }

    @Test
    fun `old model history tolerates missing provider and unknown fields`() = runTest(dispatcher) {
        dataStore.edit {
            it[stringPreferencesKey("hermes.mobile.favoriteModels")] =
                """[{"modelID":"old-model","future":true}]"""
            it[activeServer] = "https://a.example"
        }
        assertEquals(listOf(ModelFavoriteKey("old-model")), favorites.currentFavoriteKeys())
    }
}
