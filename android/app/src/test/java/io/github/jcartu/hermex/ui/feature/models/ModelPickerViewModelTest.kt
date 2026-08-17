package io.github.jcartu.hermex.ui.feature.models

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import io.github.jcartu.hermex.model.ModelsResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var favoritesStore: ModelFavoritesStore
    private lateinit var recentsStore: ModelRecentsStore

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Wire-shaped `GET /api/models` fixture (groups → models with id/name/label). */
    private val catalogFixture = """
        {
          "groups": [
            {
              "provider_id": "anthropic",
              "name": "Anthropic",
              "models": [
                {"id": "claude-fable-5", "name": "Claude Fable 5"},
                {"id": "claude-opus-5", "name": "Claude Opus 5"}
              ]
            },
            {
              "provider_id": "openai",
              "name": "OpenAI",
              "models": [{"id": "codex-sol", "label": "Codex Sol"}]
            }
          ],
          "default_model": "claude-fable-5",
          "unknown_future_field": {"nested": true}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        storeScope = CoroutineScope(dispatcher + Job())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            tmpFolder.newFile("models.preferences_pb")
        }
        favoritesStore = ModelFavoritesStore(dataStore)
        recentsStore = ModelRecentsStore(dataStore)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun apiReturningCatalog(): HermesApi {
        val api = mockk<HermesApi>()
        coEvery { api.models() } returns json.decodeFromString<ModelsResponse>(catalogFixture)
        return api
    }

    private fun viewModel(
        api: HermesApi = apiReturningCatalog(),
        initialModelID: String? = null,
        initialProviderID: String? = null,
    ) = ModelPickerViewModel(
        api = api,
        favoritesStore = favoritesStore,
        recentsStore = recentsStore,
        initialModelID = initialModelID,
        initialProviderID = initialProviderID,
    )

    @Test
    fun `catalog load decodes groups and seeds selection from default_model`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf("anthropic", "openai"), state.modelGroups.map { it.id })
        assertEquals(3, state.modelGroups.sumOf { it.models.size })
        assertEquals("claude-fable-5", state.selectedModelID)
        // custom provider entry seeded from the first provider choice
        assertEquals("anthropic", state.customProviderID)
    }

    @Test
    fun `explicit initial selection wins over default_model`() = runTest(dispatcher) {
        val vm = viewModel(initialModelID = "codex-sol", initialProviderID = "openai")
        val state = vm.uiState.value

        assertEquals("codex-sol", state.selectedModelID)
        assertEquals("openai", state.selectedModelProviderID)
        assertTrue(state.isSelected(ModelCatalogOption("codex-sol", "Codex Sol", "openai")))
        assertFalse(state.isSelected(ModelCatalogOption("claude-fable-5", "Claude Fable 5", "anthropic")))
        // provider seed prefers the selection's provider
        assertEquals("openai", state.customProviderID)
    }

    @Test
    fun `catalog failure surfaces the error message`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.models() } throws ApiError.Http(statusCode = 500, body = null)

        val vm = viewModel(api = api)
        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.modelGroups.isEmpty())
        assertEquals(ApiError.Http(500, null).message, state.errorMessage)
    }

    @Test
    fun `favorite toggle persists across store instances`() = runTest(dispatcher) {
        val option = ModelCatalogOption("claude-fable-5", "Claude Fable 5", "anthropic")
        val vm = viewModel()

        vm.toggleFavorite(option)
        assertEquals(
            listOf(ModelFavoriteKey("claude-fable-5", "anthropic")),
            vm.uiState.value.favoriteKeys,
        )

        // A fresh store over the same DataStore sees the persisted favorite.
        assertEquals(
            listOf(ModelFavoriteKey("claude-fable-5", "anthropic")),
            ModelFavoritesStore(dataStore).currentFavoriteKeys(),
        )

        vm.toggleFavorite(option)
        assertTrue(vm.uiState.value.favoriteKeys.isEmpty())
    }

    @Test
    fun `selection records recents most-recent-first and dedups`() = runTest(dispatcher) {
        val fable = ModelCatalogOption("claude-fable-5", "Claude Fable 5", "anthropic")
        val codex = ModelCatalogOption("codex-sol", "Codex Sol", "openai")
        val vm = viewModel()

        vm.select(fable)
        vm.select(codex)
        vm.select(fable)

        assertEquals(
            listOf(
                ModelFavoriteKey("claude-fable-5", "anthropic"),
                ModelFavoriteKey("codex-sol", "openai"),
            ),
            vm.uiState.value.recentKeys,
        )
        assertEquals("claude-fable-5", vm.uiState.value.selectedModelID)
        assertEquals("anthropic", vm.uiState.value.selectedModelProviderID)
    }

    @Test
    fun `recents are capped at five`() = runTest(dispatcher) {
        val vm = viewModel()
        repeat(7) { index ->
            vm.select(ModelCatalogOption("model-$index", "Model $index", "prov"))
        }
        val keys = vm.uiState.value.recentKeys
        assertEquals(5, keys.size)
        assertEquals("model-6", keys.first().modelID)
        assertEquals("model-2", keys.last().modelID)
    }

    @Test
    fun `deleteSavedCustom removes the model from favorites and recents`() = runTest(dispatcher) {
        val custom = ModelCatalogOption("my-model", "my-model", "my-provider")
        val vm = viewModel()

        vm.toggleFavorite(custom)
        vm.select(custom)
        assertTrue(vm.uiState.value.favoriteKeys.isNotEmpty())
        assertTrue(vm.uiState.value.recentKeys.isNotEmpty())

        vm.deleteSavedCustom(custom)
        assertTrue(vm.uiState.value.favoriteKeys.isEmpty())
        assertTrue(vm.uiState.value.recentKeys.isEmpty())
    }

    @Test
    fun `non-catalog favorite shows up as a saved custom section`() = runTest(dispatcher) {
        val custom = ModelCatalogOption("my-model", "my-model", "my-provider")
        val vm = viewModel()

        vm.toggleFavorite(custom)
        val groups = vm.uiState.value.displayGroups
        val saved = groups.first { it.id == SAVED_CUSTOM_GROUP_ID }
        assertEquals(listOf("my-model"), saved.models.map { it.id })
        assertTrue(saved.allowsDelete)
    }

    @Test
    fun `search narrows display groups and updates expansion mode`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.updateSearchText("codex")
        val state = vm.uiState.value
        assertEquals(listOf("openai"), state.displayGroups.map { it.id })
        // during a search every group is expanded by default
        assertTrue(state.isExpanded("openai"))

        vm.updateSearchText("")
        // outside a search provider groups are collapsed unless opted in
        assertFalse(vm.uiState.value.isExpanded("openai"))
        vm.setGroupExpanded("openai", true)
        assertTrue(vm.uiState.value.isExpanded("openai"))
    }

    @Test
    fun `empty catalog yields no display groups and no error`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.models() } returns ModelsResponse()

        val vm = viewModel(api = api)
        assertTrue(vm.uiState.value.modelGroups.isEmpty())
        assertTrue(vm.uiState.value.displayGroups.isEmpty())
        assertNull(vm.uiState.value.errorMessage)
    }
}
