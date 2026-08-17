package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.model.HealthResponse
import io.github.jcartu.hermex.model.LoginResponse
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
class OnboardingViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var api: HermesApi
    private lateinit var authManager: AuthManager

    private class FakeCipher : StringCipher {
        override fun encrypt(plainText: String) = plainText
        override fun decrypt(cipherText: String) = cipherText
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val file = tmpFolder.newFile("onboarding_prefs.preferences_pb")
        val repository = ServerConfigRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            FakeCipher(),
        )
        api = mockk()
        authManager = AuthManager(api, repository, sessionResetter = {})
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stub(authEnabled: Boolean?, passwordAuthEnabled: Boolean? = null) {
        coEvery { api.health() } returns HealthResponse(status = "ok")
        coEvery { api.authStatus() } returns AuthStatusResponse(
            authEnabled = authEnabled,
            passwordAuthEnabled = passwordAuthEnabled,
        )
    }

    @Test
    fun `saved server prefills url and lands on the connect page`() {
        val viewModel = OnboardingViewModel(
            authManager,
            savedServer = ServerConfig("https://server.example.com", ""),
            initialErrorMessage = "Your session expired. Sign in again.",
        )

        val state = viewModel.uiState.value
        assertEquals("https://server.example.com", state.serverUrl)
        assertEquals(OnboardingFlowPolicy.connectPageIndex, state.currentPage)
        assertEquals("Your session expired. Sign in again.", state.errorMessage)
    }

    @Test
    fun `first run starts on the welcome page without errors`() {
        val state = OnboardingViewModel(authManager).uiState.value
        assertEquals(0, state.currentPage)
        assertNull(state.errorMessage)
        assertTrue(state.isPasswordRequired) // unknown auth status keeps the field visible
    }

    @Test
    fun `testConnection reports password required`() {
        stub(authEnabled = true)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("server.example.com")

        viewModel.testConnection()

        val state = viewModel.uiState.value
        assertEquals("Connection ok. Password required.", state.connectionMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isWorking)
        assertTrue(state.isPasswordRequired)
    }

    @Test
    fun `testConnection reports password not required`() {
        stub(authEnabled = false)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("server.example.com")

        viewModel.testConnection()

        val state = viewModel.uiState.value
        assertEquals("Connection ok. Password not required.", state.connectionMessage)
        assertFalse(state.isPasswordRequired)
    }

    @Test
    fun `testConnection flags passkey-only servers`() {
        stub(authEnabled = true, passwordAuthEnabled = false)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("server.example.com")

        viewModel.testConnection()

        val state = viewModel.uiState.value
        assertEquals(AuthManager.PASSKEY_ONLY_MESSAGE, state.errorMessage)
        assertNull(state.connectionMessage)
        assertFalse(state.isPasswordRequired)
    }

    @Test
    fun `testConnection surfaces an invalid url`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("ftp://nope")

        viewModel.testConnection()

        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("valid server URL"))
    }

    @Test
    fun `connect validates an empty password after a probe`() {
        stub(authEnabled = true)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("server.example.com")
        viewModel.testConnection()

        viewModel.connect()

        assertEquals(AuthManager.EMPTY_PASSWORD_MESSAGE, viewModel.uiState.value.errorMessage)
        coVerify(exactly = 0) { api.login(any()) }
    }

    @Test
    fun `connect probes first when no auth status is known`() {
        stub(authEnabled = true)
        coEvery { api.login("hunter2") } returns LoginResponse(ok = true)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("server.example.com")
        viewModel.onPasswordChange("hunter2")

        viewModel.connect()

        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(authManager.state.value is AuthState.LoggedIn)
    }

    @Test
    fun `connect succeeds without a password when auth is off`() {
        stub(authEnabled = false)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("server.example.com")

        viewModel.connect()

        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(authManager.state.value is AuthState.LoggedIn)
        coVerify(exactly = 0) { api.login(any()) }
    }

    @Test
    fun `password validation matches the swift rules`() {
        // No auth known → no demand.
        assertNull(OnboardingViewModel.passwordValidationMessage(null, ""))
        assertNull(
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = false), "",
            ),
        )
        // Passkey-only falls through to configure()'s specific message.
        assertNull(
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = true, passwordAuthEnabled = false), "",
            ),
        )
        // Auth on + blank password → demand one (whitespace does not count).
        assertEquals(
            AuthManager.EMPTY_PASSWORD_MESSAGE,
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = true), "   ",
            ),
        )
        assertNull(
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = true), "pw",
            ),
        )
    }

    @Test
    fun `primary action on the agent prompt page raises the copy reminder`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex)

        viewModel.handlePrimaryAction()

        val state = viewModel.uiState.value
        assertTrue(state.isShowingCopyReminder)
        assertEquals(OnboardingFlowPolicy.agentPromptPageIndex, state.currentPage)
    }

    @Test
    fun `copying the prompt unlocks forward navigation`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex)
        viewModel.onAgentPromptCopied()

        viewModel.handlePrimaryAction()

        val state = viewModel.uiState.value
        assertFalse(state.isShowingCopyReminder)
        assertEquals(OnboardingFlowPolicy.agentPromptPageIndex + 1, state.currentPage)
    }

    @Test
    fun `forward swipe from the agent prompt page is intercepted once`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex)

        val resolved = viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex + 1)

        assertEquals(OnboardingFlowPolicy.agentPromptPageIndex, resolved)
        assertTrue(viewModel.uiState.value.isShowingCopyReminder)

        // Bypassing advances and stops intercepting.
        viewModel.bypassCopyReminderAndAdvance()
        assertEquals(
            OnboardingFlowPolicy.agentPromptPageIndex + 1,
            viewModel.uiState.value.currentPage,
        )
        val afterBypass = viewModel.onPageChangeRequested(OnboardingFlowPolicy.connectPageIndex)
        assertEquals(OnboardingFlowPolicy.connectPageIndex, afterBypass)
    }

    @Test
    fun `backward swipe from the agent prompt page is never intercepted`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex)

        val resolved = viewModel.onPageChangeRequested(1)

        assertEquals(1, resolved)
        assertFalse(viewModel.uiState.value.isShowingCopyReminder)
    }
}
