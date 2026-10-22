package io.github.jcartu.hermex.ui.feature.onboarding

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CustomHeader
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private lateinit var fixture: AuthTestFixture
    private lateinit var authManager: AuthManager

    private val serverA = "https://a.example.com"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = AuthTestFixture(tmpFolder)
        authManager = fixture.manager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saved server prefills url and headers and lands on the connect page`() {
        val saved = ServerAccount(id = serverA, urlString = serverA)
        val viewModel = OnboardingViewModel(
            authManager,
            savedServer = saved,
            savedHeaders = listOf(CustomHeader("X-Api-Key", "a-token")),
            initialErrorMessage = "Your session expired. Sign in again.",
        )

        val state = viewModel.uiState.value
        assertEquals(serverA, state.serverUrl)
        assertEquals(listOf(CustomHeader("X-Api-Key", "a-token")), state.customHeaders)
        assertEquals(OnboardingFlowPolicy.connectPageIndex, state.currentPage)
        assertEquals("Your session expired. Sign in again.", state.errorMessage)
        // Any edit clears the inherited banner, which has no probe owning it.
        viewModel.onServerUrlChange("$serverA/")
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `first run starts on the welcome page with the hermes prompt`() {
        val state = OnboardingViewModel(authManager).uiState.value
        assertEquals(0, state.currentPage)
        assertNull(state.errorMessage)
        assertEquals(OnboardingSetupPrompt.HERMES, state.shownPrompt)
        assertTrue(state.isPasswordRequired) // unknown auth status keeps the field visible
        assertEquals(ConnectionMode.PRIVATE_NETWORK, state.connectionMode)
    }

    @Test
    fun `copy reminder follows the prompt shown`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.jumpToConnectPage()
        viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex)

        viewModel.handlePrimaryAction()
        assertTrue(viewModel.uiState.value.isShowingCopyReminder)
        viewModel.dismissCopyReminder()

        viewModel.onPromptCopied(OnboardingSetupPrompt.HERMES)
        viewModel.handlePrimaryAction()
        assertEquals(OnboardingFlowPolicy.agentPromptPageIndex + 1, viewModel.uiState.value.currentPage)

        // Swapping to the other prompt asks again until it, too, is copied or skipped.
        viewModel.onPageChangeRequested(OnboardingFlowPolicy.agentPromptPageIndex)
        viewModel.setShownPrompt(OnboardingSetupPrompt.WEB_UI)
        assertEquals(OnboardingFlowPolicy.agentPromptPageIndex, viewModel.onPageChangeRequested(3))
        assertTrue(viewModel.uiState.value.isShowingCopyReminder)
        viewModel.bypassCopyReminderAndAdvance()
        assertEquals(3, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `cloudflare tunnel opens empty access rows and leaving it drops only the empty ones`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onCustomHeadersChange(listOf(CustomHeader("X-Api-Key", "token")))

        viewModel.setConnectionMode(ConnectionMode.CLOUDFLARE_TUNNEL)
        assertEquals(
            listOf("X-Api-Key", "CF-Access-Client-Id", "CF-Access-Client-Secret"),
            viewModel.uiState.value.customHeaders.map { it.name },
        )
        assertEquals("https://hermes.example.com", viewModel.uiState.value.connectionMode.placeholder)

        // A filled Access row survives the mode change; the empty one goes.
        viewModel.onCustomHeadersChange(
            viewModel.uiState.value.customHeaders.map { if (it.name == "CF-Access-Client-Id") it.copy(value = "id") else it },
        )
        viewModel.setConnectionMode(ConnectionMode.SAME_WIFI)
        assertEquals(listOf("X-Api-Key", "CF-Access-Client-Id"), viewModel.uiState.value.customHeaders.map { it.name })

        // The other modes never add Access rows.
        viewModel.onCustomHeadersChange(emptyList())
        viewModel.setConnectionMode(ConnectionMode.PRIVATE_NETWORK)
        assertTrue(viewModel.uiState.value.customHeaders.isEmpty())
    }

    @Test
    fun `header rows without a value are never sent or saved`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = false)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("a.example.com")
        viewModel.setConnectionMode(ConnectionMode.CLOUDFLARE_TUNNEL)
        viewModel.onCustomHeadersChange(viewModel.uiState.value.customHeaders + CustomHeader("X-Api-Key", "token"))

        viewModel.connect()

        assertTrue(authManager.state.value is AuthState.LoggedIn)
        fixture.clientRequests.forEach { (_, headers) ->
            assertEquals(listOf(CustomHeader("X-Api-Key", "token")), headers)
        }
        assertEquals(listOf(CustomHeader("X-Api-Key", "token")), fixture.customHeaders.snapshotFor(serverA))
    }

    @Test
    fun `will connect to preview follows the parser and clears for unparseable text`() {
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("A.example.com/path")
        assertEquals("https://a.example.com", viewModel.uiState.value.addressPreview)
        viewModel.onServerUrlChange("ftp://nope")
        assertNull(viewModel.uiState.value.addressPreview)
        assertFalse(OnboardingViewModel(authManager).uiState.value.canSubmit)
    }

    @Test
    fun `testConnection reports the server's password needs and edits invalidate the probe`() {
        fixture.stubHealthy(serverA, authEnabled = true)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("a.example.com")

        viewModel.testConnection()

        val state = viewModel.uiState.value
        assertEquals("Connection ok. Password required.", state.connectionMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isWorking)
        assertFalse(state.isConnectionLocked)
        assertTrue(state.showsPasswordField)
        assertNotNull(state.authStatus)

        // A changed credential set (a new header) drops the probe.
        viewModel.onCustomHeadersChange(listOf(CustomHeader("Authorization", "Bearer x")))
        assertNull(viewModel.uiState.value.authStatus)
        assertNull(viewModel.uiState.value.connectionMessage)
    }

    @Test
    fun `connect demands a password only when the server requires one`() {
        fixture.stubHealthy(serverA, authEnabled = true)
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("a.example.com")

        viewModel.connect()

        assertEquals(AuthManager.EMPTY_PASSWORD_MESSAGE, viewModel.uiState.value.errorMessage)
        assertEquals(AuthState.Unconfigured, authManager.state.value)

        fixture.stubLogin(serverA, "hunter2", ok = true)
        viewModel.onPasswordChange("hunter2")
        viewModel.connect()
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(authManager.state.value is AuthState.LoggedIn)
    }

    @Test
    fun `connect surfaces unreachable servers`() {
        coEvery { fixture.client(serverA).health() } throws ApiError.Network(RuntimeException("unreachable"))
        val viewModel = OnboardingViewModel(authManager)
        viewModel.onServerUrlChange("a.example.com")

        viewModel.connect()

        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().isNotEmpty())
        assertFalse(viewModel.uiState.value.isWorking)
        assertEquals(AuthState.Unconfigured, authManager.state.value)
    }

    @Test
    fun `add server refuses an address already in the registry and reveals the password on demand`() = runBlocking {
        fixture.stubHealthy(serverA, authEnabled = true)
        fixture.stubLogin(serverA, "pw-a", ok = true)
        authManager.configure("a.example.com", "pw-a")

        val duplicate = OnboardingViewModel(authManager, entry = ConnectFormEntry.ADD_SERVER)
        duplicate.onServerUrlChange("https://A.example.com/")
        duplicate.connect()
        assertEquals(AuthManager.ALREADY_CONFIGURED_MESSAGE, duplicate.uiState.value.errorMessage)
        assertNull(duplicate.uiState.value.addedAccount)
        assertEquals(1, authManager.servers.value.size)

        val serverB = "https://b.example.com"
        fixture.stubHealthy(serverB, authEnabled = true)
        fixture.stubLogin(serverB, "pw-b", ok = true)
        val form = OnboardingViewModel(authManager, entry = ConnectFormEntry.ADD_SERVER)
        form.onServerUrlChange("b.example.com")
        assertFalse(form.uiState.value.showsPasswordField)

        form.connect()
        assertTrue(form.uiState.value.showsPasswordField)
        assertNull(form.uiState.value.errorMessage)

        form.onPasswordChange("pw-b")
        form.connect()
        assertEquals(serverB, form.uiState.value.addedAccount?.id)
        assertEquals(serverB, authManager.activeServerID)
    }

    @Test
    fun `password validation message`() {
        val requires = io.github.jcartu.hermex.model.AuthStatusResponse(authEnabled = true)
        assertEquals(AuthManager.EMPTY_PASSWORD_MESSAGE, OnboardingViewModel.passwordValidationMessage(requires, "  "))
        assertNull(OnboardingViewModel.passwordValidationMessage(requires, "pw"))
        assertNull(OnboardingViewModel.passwordValidationMessage(requires.copy(loggedIn = true), ""))
        assertNull(OnboardingViewModel.passwordValidationMessage(requires.copy(passwordAuthEnabled = false), ""))
        assertNull(OnboardingViewModel.passwordValidationMessage(null, ""))
    }
}
