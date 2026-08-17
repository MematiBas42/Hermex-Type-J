package io.github.jcartu.hermex.ui.feature.settings

import io.github.jcartu.hermex.model.ProviderModel
import io.github.jcartu.hermex.model.ProviderSummary
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersViewModelTest {

    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load preserves server order and normalizes the active id`() {
        coEvery { api.providers() } returns ProvidersResponse(
            providers = listOf(
                ProviderSummary(id = "openrouter", hasKey = true),
                ProviderSummary(id = "anthropic", hasKey = false),
            ),
            activeProvider = "  OpenRouter  ",
        )

        val vm = ProvidersViewModel(api)
        vm.load()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf("openrouter", "anthropic"), state.providers.map { it.id })
        assertEquals("openrouter", state.activeProviderId)
        assertTrue(vm.isActive(state.providers[0]))
        assertFalse(vm.isActive(state.providers[1]))
    }

    @Test
    fun `load failure surfaces the error message`() {
        coEvery { api.providers() } throws ApiError.Http(500, null)

        val vm = ProvidersViewModel(api)
        vm.load()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage.orEmpty().isNotEmpty())
        assertTrue(state.providers.isEmpty())
    }

    @Test
    fun `normalizedProviderId trims lowercases and drops blanks`() {
        assertEquals("openai", ProvidersViewModel.normalizedProviderId(" OpenAI "))
        assertNull(ProvidersViewModel.normalizedProviderId("   "))
        assertNull(ProvidersViewModel.normalizedProviderId(null))
    }

    @Test
    fun `displayName falls back from display name to id to unknown`() {
        assertEquals(
            "OpenRouter",
            ProvidersViewModel.displayName(ProviderSummary(id = "openrouter", displayName = "OpenRouter")),
        )
        assertEquals(
            "openrouter",
            ProvidersViewModel.displayName(ProviderSummary(id = "openrouter", displayName = "  ")),
        )
        assertEquals("Unknown provider", ProvidersViewModel.displayName(ProviderSummary()))
    }

    @Test
    fun `keySourceBadge collapses the upstream vocabulary`() {
        fun badge(source: String?, hasKey: Boolean = true) =
            ProvidersViewModel.keySourceBadge(ProviderSummary(hasKey = hasKey, keySource = source))

        assertEquals("env", badge("env_file"))
        assertEquals("env", badge("env_var"))
        assertEquals("env", badge("ENV"))
        assertEquals("OAuth", badge("oauth"))
        assertEquals("OAuth", badge("token"))
        assertEquals("config", badge("config_yaml"))
        assertEquals("config", badge("config"))
        // Unknown future values pass through verbatim rather than being hidden.
        assertEquals("vault", badge("vault"))
        // No key → no badge, regardless of source.
        assertNull(badge("env", hasKey = false))
        assertNull(badge(null))
        assertNull(badge("none"))
    }

    @Test
    fun `modelCount prefers the larger of total and visible list`() {
        val trimmed = ProviderSummary(
            models = listOf(ProviderModel(id = "a"), ProviderModel(id = "b")),
            modelsTotal = 40,
        )
        assertEquals(40, ProvidersViewModel.modelCount(trimmed))

        val untrimmed = ProviderSummary(models = listOf(ProviderModel(id = "a")))
        assertEquals(1, ProvidersViewModel.modelCount(untrimmed))
        assertEquals(0, ProvidersViewModel.modelCount(ProviderSummary()))
    }

    @Test
    fun `truncatedModelInfo only fires for a genuinely trimmed list`() {
        val trimmed = ProviderSummary(
            models = listOf(ProviderModel(id = "a"), ProviderModel(id = "b")),
            modelsTotal = 40,
        )
        assertEquals(2 to 40, ProvidersViewModel.truncatedModelInfo(trimmed))

        assertNull(
            ProvidersViewModel.truncatedModelInfo(
                ProviderSummary(models = listOf(ProviderModel(id = "a")), modelsTotal = 1),
            ),
        )
        assertNull(ProvidersViewModel.truncatedModelInfo(ProviderSummary(modelsTotal = 5)))
        assertNull(
            ProvidersViewModel.truncatedModelInfo(
                ProviderSummary(models = listOf(ProviderModel(id = "a"))),
            ),
        )
    }

    @Test
    fun `authErrorText trims and drops empty messages`() {
        assertEquals(
            "Key expired",
            ProvidersViewModel.authErrorText(ProviderSummary(authError = " Key expired ")),
        )
        assertNull(ProvidersViewModel.authErrorText(ProviderSummary(authError = "  ")))
        assertNull(ProvidersViewModel.authErrorText(ProviderSummary()))
    }
}
