package io.github.jcartu.hermex.ui.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.core.prefs.ThinkingAndToolsMode
import io.github.jcartu.hermex.core.security.AppLock
import io.github.jcartu.hermex.ui.feature.onboarding.AuthTestFixture
import io.github.jcartu.hermex.ui.theme.Palette
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

    private lateinit var prefs: DataStore<Preferences>
    private lateinit var visibility: DataStore<Preferences>
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fixture: AuthTestFixture

    private fun dataStore(name: String): DataStore<Preferences> {
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val file = tmpFolder.newFile("$name.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs = dataStore("settings")
        visibility = dataStore("visibility")
        settingsRepository = SettingsRepository(prefs, visibility)
        fixture = AuthTestFixture(tmpFolder)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = settingsRepository,
        authManager = fixture.manager(),
        appLock = AppLock(settingsRepository, fixture.scope),
    )


    @Test
    fun `settings tolerate unknown raw values and keep upgraded files`() = runBlocking {
        prefs.edit {
            it[stringPreferencesKey("appTheme")] = "SEPIA"
            it[stringPreferencesKey("sessionChat.completionPosition")] = "sideways"
            it[stringPreferencesKey("appPalette")] = "nonsense"
        }
        // The fork's older visibility file keeps its choices.
        visibility.edit { it[booleanPreferencesKey("sectionVisibility.tasks")] = false }

        val decoded = settingsRepository.settings.first()
        assertFalse(decoded.showsTasksEntry)
    }

    @Test
    fun `thinking and tools mode maps to and from the three legacy keys`() = runBlocking {
        val vm = viewModel()

        vm.setThinkingAndToolsMode(ThinkingAndToolsMode.EXPANDED)
        var persisted = settingsRepository.settings.first()
        assertTrue(persisted.showsThinkingAndToolCards)
        assertTrue(persisted.thinkingCardsStartExpanded && persisted.toolCardsStartExpanded)
        assertEquals(ThinkingAndToolsMode.EXPANDED, persisted.thinkingAndToolsMode)

        vm.setThinkingAndToolsMode(ThinkingAndToolsMode.HIDDEN)
        persisted = settingsRepository.settings.first()
        assertFalse(persisted.showsThinkingAndToolCards)
        assertEquals(ThinkingAndToolsMode.HIDDEN, persisted.thinkingAndToolsMode)

        vm.setThinkingAndToolsMode(ThinkingAndToolsMode.COLLAPSED)
        persisted = settingsRepository.settings.first()
        assertTrue(persisted.showsThinkingAndToolCards)
        assertFalse(persisted.thinkingCardsStartExpanded || persisted.toolCardsStartExpanded)
        assertEquals(ThinkingAndToolsMode.COLLAPSED, persisted.thinkingAndToolsMode)

        // Legacy keys written by an older build read as the right mode (iOS migration rules).
        settingsRepository.setShowsThinkingAndToolCards(true)
        settingsRepository.setToolCardsStartExpanded(true)
        assertEquals(ThinkingAndToolsMode.EXPANDED, settingsRepository.settings.first().thinkingAndToolsMode)
        settingsRepository.setShowsThinkingAndToolCards(false)
        assertEquals(ThinkingAndToolsMode.HIDDEN, settingsRepository.settings.first().thinkingAndToolsMode)
        assertEquals(ThinkingAndToolsMode.COLLAPSED, ThinkingAndToolsMode.fromToggles(true, false, false))
    }

    @Test
    fun `new keys persist through the view model`() = runBlocking {
        val vm = viewModel()

        vm.setTheme(SettingsRepository.Theme.DARK)
        vm.setDismissKeyboardAfterSend(true)
        vm.setReadCompletedResponseFromStart(true)
        vm.setFoldsSettledTurns(false)
        vm.setNotifyFailedRuns(false)
        vm.setShowsKanbanEntry(false)
        vm.setShowsGitActions(false)
        vm.setShowsSessionRowStates(false)

        val persisted = settingsRepository.settings.first()
        assertEquals(SettingsRepository.Theme.DARK, persisted.theme)
        assertTrue(persisted.dismissKeyboardAfterSend)
        assertTrue(persisted.readCompletedResponseFromStart)
        assertFalse(persisted.foldsSettledTurns)
        assertFalse(persisted.notifyFailedRuns)
        assertTrue(persisted.notifyReplies)
        assertFalse(persisted.showsKanbanEntry)
        assertFalse(persisted.showsGitActions)
        assertFalse(persisted.showsSessionRowStates)
        // The ViewModel mirrors the repository stream.
        assertEquals(SettingsRepository.Theme.DARK, vm.uiState.value.settings.theme)
    }

    @Test
    fun `accent color edits the active server and mirrors into the global key`() = runBlocking {
        fixture.stubHealthy("https://a.example.com", authEnabled = false)
        val auth = fixture.manager()
        auth.configure("a.example.com", "")
        val vm = SettingsViewModel(settingsRepository, auth, AppLock(settingsRepository, fixture.scope))

        vm.setHeaderLogoColor("#5B7CFF")

        assertEquals("#5B7CFF", auth.servers.value.single().headerLogoColorHex)
        assertEquals("#5B7CFF", fixture.identityDefaults.values["headerLogoColorHex"])
        assertEquals("Blue", HeaderLogoColor.displayName("#5B7CFF"))
    }

    @Test
    fun `navigation flags round trip`() = runBlocking {
        val vm = viewModel()
        vm.openPage(SettingsPage.Server("https://a.example.com"))
        vm.openDetail(SettingsDetail.PROVIDERS)
        assertEquals(SettingsDetail.PROVIDERS, vm.uiState.value.detail)
        vm.closeDetail()
        assertNull(vm.uiState.value.detail)

        vm.openPage(SettingsPage.Chat)
        assertEquals(SettingsPage.Chat, vm.uiState.value.page)
        vm.closePage()
        assertNull(vm.uiState.value.page)
        vm.openAddServer()
        assertTrue(vm.uiState.value.isAddingServer)
        vm.dismissAddServer()
        assertFalse(vm.uiState.value.isAddingServer)
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
        assertTrue(HeaderLogoColor.prefersDarkForeground("garbage"))
    }

    @Test
    fun `preset names resolve and unknown hexes are custom`() {
        assertEquals("Yellow", HeaderLogoColor.displayName("#FFD700"))
        assertEquals("Blue", HeaderLogoColor.displayName("#5b7cff"))
        assertEquals("Custom", HeaderLogoColor.displayName("#123456"))
    }
}

class ServerIdentityTest {

    @Test
    fun `initials follow stored then display name then host then UZ`() {
        assertEquals("AB", ServerIdentity.displayInitials("Alpha Beta", "ab", "host.example.com"))
        assertEquals("AB", ServerIdentity.displayInitials("Alpha Beta", "", "host.example.com"))
        assertEquals("HE", ServerIdentity.displayInitials("", "", "host-example"))
        assertEquals("UZ", ServerIdentity.displayInitials("", "", ""))
        assertEquals("A1B", ServerIdentity.normalizedInitials("a-1 b c"))
    }
}
