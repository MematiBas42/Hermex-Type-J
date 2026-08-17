package io.github.jcartu.hermex.ui.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.model.DefaultModelResponse
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.model.ModelsResponse
import io.github.jcartu.hermex.model.SettingsResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.ui.feature.models.DefaultProfileSelection
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
class SettingsViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var api: HermesApi
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var visibilitySettings: VisibilitySettings
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var authManager: AuthManager

    private class FakeCipher : StringCipher {
        override fun encrypt(plainText: String) = plainText
        override fun decrypt(cipherText: String) = cipherText
    }

    private fun dataStore(name: String): DataStore<Preferences> {
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val file = tmpFolder.newFile("$name.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = mockk()
        settingsRepository = SettingsRepository(dataStore("settings"))
        visibilitySettings = VisibilitySettings(dataStore("visibility"))
        serverConfig = ServerConfigRepository(dataStore("server"), FakeCipher())
        authManager = AuthManager(api, serverConfig, sessionResetter = {})
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubHealthyServer(
        version: String? = "0.51.85",
        defaultModel: String? = "gpt-5",
        activeProfile: String? = "default",
    ) {
        coEvery { api.settings() } returns SettingsResponse(webuiVersion = version)
        coEvery { api.models() } returns ModelsResponse(defaultModel = defaultModel)
        coEvery { api.profiles() } returns ProfilesResponse(
            profiles = listOf(ProfileSummary(name = activeProfile, isActive = true)),
            active = activeProfile,
        )
    }

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        api = api,
        settingsRepository = settingsRepository,
        visibilitySettings = visibilitySettings,
        serverConfig = serverConfig,
        authManager = authManager,
    )

    @Test
    fun `load populates server version model and profile`() = runBlocking {
        serverConfig.save("https://server.example.com", "pw")
        stubHealthyServer()

        val state = viewModel().uiState.value

        assertEquals("0.51.85", state.serverVersion)
        assertNull(state.serverSettingsError)
        assertEquals("Connected", state.serverStatusLabel)
        assertTrue(state.isServerStatusHealthy)
        assertEquals("gpt-5", state.defaultModel)
        assertEquals("gpt-5", state.defaultModelLabel)
        assertEquals("default", state.defaultProfileName)
        assertEquals("Default", state.defaultProfileLabel)
        assertEquals("https://server.example.com", state.serverBaseUrl)
    }

    @Test
    fun `unreachable server degrades to unavailable`() {
        coEvery { api.settings() } throws ApiError.Network(RuntimeException("offline"))
        coEvery { api.models() } throws ApiError.Network(RuntimeException("offline"))
        coEvery { api.profiles() } throws ApiError.Network(RuntimeException("offline"))

        val state = viewModel().uiState.value

        assertEquals("Unavailable", state.serverSettingsError)
        assertEquals("Unavailable", state.serverStatusLabel)
        assertFalse(state.isServerStatusHealthy)
        assertNull(state.defaultModel)
        assertEquals("Not set", state.defaultModelLabel)
        assertEquals("Not set", state.defaultProfileLabel)
    }

    @Test
    fun `server without a version reports unknown`() {
        stubHealthyServer(version = null)

        val state = viewModel().uiState.value

        assertEquals("Unknown", state.serverSettingsError)
        assertNull(state.serverVersion)
        assertFalse(state.isServerStatusHealthy)
    }

    @Test
    fun `a 401 during load demotes the auth state`() = runBlocking {
        serverConfig.save("https://server.example.com", "pw")
        coEvery { api.settings() } throws ApiError.Unauthorized
        coEvery { api.models() } throws ApiError.Unauthorized
        coEvery { api.profiles() } throws ApiError.Unauthorized

        val vm = viewModel()

        assertTrue(authManager.state.value is AuthState.LoggedOut)
        assertEquals("Unavailable", vm.uiState.value.serverSettingsError)
    }

    @Test
    fun `theme and toggle changes persist through the repository`() = runBlocking {
        stubHealthyServer()
        val vm = viewModel()

        vm.setTheme(SettingsRepository.Theme.DARK)
        vm.setHapticsEnabled(false)
        vm.setWrapsCodeBlockLines(true)
        vm.setRtlChatLayoutEnabled(true)

        val persisted = settingsRepository.settings.first()
        assertEquals(SettingsRepository.Theme.DARK, persisted.theme)
        assertFalse(persisted.hapticsEnabled)
        assertTrue(persisted.wrapsCodeBlockLines)
        assertTrue(persisted.rtlChatLayoutEnabled)

        // The ViewModel mirrors the repository stream.
        val state = vm.uiState.value
        assertEquals(SettingsRepository.Theme.DARK, state.settings.theme)
        assertFalse(state.settings.hapticsEnabled)
    }

    @Test
    fun `accent color persists`() = runBlocking {
        stubHealthyServer()
        val vm = viewModel()

        vm.setHeaderLogoColor("#5B7CFF")

        assertEquals(
            "#5B7CFF",
            settingsRepository.settings.first().headerLogoColorHex,
        )
        assertEquals("Blue", HeaderLogoColor.displayName(vm.uiState.value.settings.headerLogoColorHex))
    }

    @Test
    fun `section visibility persists under the swift keys`() = runBlocking {
        stubHealthyServer()
        val vm = viewModel()

        vm.setSectionVisible(VisibilitySettings.Section.TASKS, false)
        vm.setSectionVisible(VisibilitySettings.Section.INSIGHTS, false)

        val persisted = visibilitySettings.state.first()
        assertFalse(persisted.tasks)
        assertFalse(persisted.insights)
        assertTrue(persisted.skills)
        assertTrue(persisted.memory)
        assertTrue(persisted.projects)

        val state = vm.uiState.value
        assertFalse(state.visibility.tasks)
        assertFalse(state.visibility.insights)
    }

    @Test
    fun `saveDefaultModel commits only on ok`() {
        stubHealthyServer(defaultModel = "old-model")
        coEvery { api.saveDefaultModel("new-model") } returns DefaultModelResponse(ok = true)

        val vm = viewModel()
        vm.saveDefaultModel("new-model")

        val state = vm.uiState.value
        assertEquals("new-model", state.defaultModel)
        assertNull(state.defaultModelSaveError)
        assertFalse(state.isSavingDefaultModel)
    }

    @Test
    fun `saveDefaultModel surfaces an unconfirmed change`() {
        stubHealthyServer(defaultModel = "old-model")
        coEvery { api.saveDefaultModel("new-model") } returns DefaultModelResponse(ok = false)

        val vm = viewModel()
        vm.saveDefaultModel("new-model")

        val state = vm.uiState.value
        assertEquals("old-model", state.defaultModel)
        assertEquals("The server did not confirm the change.", state.defaultModelSaveError)
    }

    @Test
    fun `saveDefaultModel surfaces api errors`() {
        stubHealthyServer(defaultModel = "old-model")
        coEvery { api.saveDefaultModel(any()) } throws ApiError.Http(500, null)

        val vm = viewModel()
        vm.saveDefaultModel("new-model")

        val state = vm.uiState.value
        assertEquals("old-model", state.defaultModel)
        assertTrue(state.defaultModelSaveError.orEmpty().isNotEmpty())
    }

    @Test
    fun `profile switch mirrors the selection and adopts its default model`() {
        stubHealthyServer(defaultModel = "old-model", activeProfile = "default")

        val vm = viewModel()
        vm.onDefaultProfileSaved(
            DefaultProfileSelection(name = "work", displayName = "work", defaultModel = "work-model"),
        )

        val state = vm.uiState.value
        assertEquals("work", state.defaultProfileName)
        assertEquals("work", state.defaultProfileLabel)
        assertEquals("work-model", state.defaultModel)
        assertFalse(state.isShowingProfilePicker)
    }

    @Test
    fun `profile switch without a default model keeps the current one`() {
        stubHealthyServer(defaultModel = "old-model")

        val vm = viewModel()
        vm.onDefaultProfileSaved(
            DefaultProfileSelection(name = "work", displayName = "Work", defaultModel = null),
        )

        assertEquals("old-model", vm.uiState.value.defaultModel)
        assertEquals("Work", vm.uiState.value.defaultProfileLabel)
    }

    @Test
    fun `sign out resets state through the auth manager`() = runBlocking {
        serverConfig.save("https://server.example.com", "pw")
        stubHealthyServer()
        coEvery { api.logout() } returns io.github.jcartu.hermex.model.LoginResponse(ok = true)

        val vm = viewModel()
        vm.requestSignOut()
        assertTrue(vm.uiState.value.isConfirmingSignOut)

        vm.confirmSignOut()

        val state = vm.uiState.value
        assertFalse(state.isConfirmingSignOut)
        assertFalse(state.isSigningOut)
        assertEquals(AuthState.Unconfigured, authManager.state.value)
    }

    @Test
    fun `detail flags round trip`() {
        stubHealthyServer()
        val vm = viewModel()

        vm.openProviders()
        assertTrue(vm.uiState.value.isShowingProviders)
        vm.closeProviders()
        assertFalse(vm.uiState.value.isShowingProviders)

        vm.openModelPicker()
        assertTrue(vm.uiState.value.isShowingModelPicker)
        vm.dismissModelPicker()
        assertFalse(vm.uiState.value.isShowingModelPicker)

        vm.openProfilePicker()
        assertTrue(vm.uiState.value.isShowingProfilePicker)
        vm.dismissProfilePicker()
        assertFalse(vm.uiState.value.isShowingProfilePicker)
    }
}

class HeaderLogoColorTest {

    @Test
    fun `normalizes hex values`() {
        assertEquals("#FFD700", HeaderLogoColor.normalizedHex("#ffd700"))
        assertEquals("#FFD700", HeaderLogoColor.normalizedHex("ffd700"))
        assertEquals("#FFD700", HeaderLogoColor.normalizedHex("  #FFD700  "))
        assertNull(HeaderLogoColor.normalizedHex("#FFD70"))
        assertNull(HeaderLogoColor.normalizedHex("nonsense"))
    }

    @Test
    fun `light colors prefer dark foregrounds`() {
        assertTrue(HeaderLogoColor.prefersDarkForeground("#FFFFFF"))
        assertTrue(HeaderLogoColor.prefersDarkForeground("#FFD700"))
        assertFalse(HeaderLogoColor.prefersDarkForeground("#FF3B30"))
        assertFalse(HeaderLogoColor.prefersDarkForeground("#5B7CFF"))
        // Malformed input falls back to the default (dark foreground).
        assertTrue(HeaderLogoColor.prefersDarkForeground("garbage"))
    }

    @Test
    fun `preset names resolve and unknown hexes are custom`() {
        assertEquals("Yellow", HeaderLogoColor.displayName("#FFD700"))
        assertEquals("Blue", HeaderLogoColor.displayName("#5b7cff"))
        assertEquals("Custom", HeaderLogoColor.displayName("#123456"))
    }
}
