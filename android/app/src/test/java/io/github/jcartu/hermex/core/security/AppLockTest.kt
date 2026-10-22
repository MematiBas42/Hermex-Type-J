package io.github.jcartu.hermex.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A scripted device: answers prompts from a queue, or holds one open until released. */
private class FakeAuthenticator(var capability: AppLockCapability = AppLockCapability(AppLockCapability.Method.FINGERPRINT, true)) :
    AppLockAuthenticator {
    val answers = ArrayDeque<Boolean>()
    val reasons = mutableListOf<String>()
    var pending: CompletableDeferred<Boolean>? = null

    override fun capability() = capability

    override suspend fun authenticate(reason: String): Boolean {
        reasons += reason
        answers.removeFirstOrNull()?.let { return it }
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return deferred.await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
    private lateinit var settings: SettingsRepository
    private lateinit var authenticator: FakeAuthenticator
    private var now = 0L

    @Before
    fun setUp() {
        val file = tmpFolder.newFile("lock.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        authenticator = FakeAuthenticator()
    }

    private fun lock(): AppLock = AppLock(settings, scope, now = { now }, relockDelayMillis = 60_000).also {
        it.authenticator = authenticator
    }

    @Test
    fun `off by default a cold launch is unlocked and nothing prompts`() = runBlocking {
        val lock = lock()
        lock.restore()
        assertFalse(lock.state.value.isEnabled)
        assertFalse(lock.state.value.isLocked)
        assertNull(lock.sceneDidBecomeActive())
        assertTrue(authenticator.reasons.isEmpty())
        assertFalse(lock.state.value.showsLockOverlay)
    }

    @Test
    fun `turning the lock on needs a successful authentication and persists`() = runBlocking {
        val lock = lock()
        lock.restore()
        lock.sceneDidBecomeActive()

        authenticator.answers += false
        lock.setEnabled(true)
        assertFalse(lock.state.value.isEnabled)

        authenticator.answers += true
        lock.setEnabled(true)
        assertTrue(lock.state.value.isEnabled)
        assertEquals(listOf(AppLock.ENABLE_REASON, AppLock.ENABLE_REASON), authenticator.reasons)
        assertTrue(settings.settings.first().appLockEnabled)
        // Enabling doesn't lock the running app; a cold launch with it on does.
        assertFalse(lock.state.value.isLocked)
        val relaunched = lock()
        relaunched.restore()
        assertTrue(relaunched.state.value.isLocked)
        assertTrue(relaunched.state.value.showsLockOverlay)
    }

    @Test
    fun `background longer than the relock delay locks and prompts once on return`() = runBlocking {
        settings.setAppLockEnabled(true)
        val lock = lock()
        lock.restore()
        authenticator.answers += true
        lock.sceneDidBecomeActive()?.join()
        assertFalse(lock.state.value.isLocked)

        // A short pause (a permission dialog) never locks.
        lock.sceneWillResignActive()
        assertTrue(lock.state.value.isCovered)
        lock.sceneDidBecomeActive()
        assertFalse(lock.state.value.isLocked)

        lock.sceneDidEnterBackground()
        now += 30_000
        assertNull(lock.sceneDidBecomeActive())
        assertFalse(lock.state.value.isLocked)

        lock.sceneDidEnterBackground()
        assertTrue(lock.state.value.isCovered)
        now += 61_000
        authenticator.answers += false
        val prompt = lock.sceneDidBecomeActive()
        prompt?.join()
        assertTrue(lock.state.value.isLocked)
        assertFalse(lock.state.value.isCovered)
        assertEquals(AppLock.UNLOCK_REASON, authenticator.reasons.last())
        // After a failed or cancelled prompt only the Unlock button tries again.
        assertNull(lock.sceneDidBecomeActive())
        authenticator.answers += true
        lock.unlock()
        assertFalse(lock.state.value.isLocked)
    }

    @Test
    fun `a prompt of ours holding the activity paused does not cover and cancelling keeps the lock`() = runBlocking {
        settings.setAppLockEnabled(true)
        val lock = lock()
        lock.restore()
        lock.sceneDidBecomeActive() // the prompt pends on the fake device (launched eagerly, suspended on the deferred)
        assertTrue(lock.state.value.isAuthenticating)
        lock.sceneWillResignActive()
        assertFalse(lock.state.value.isCovered)
        authenticator.pending!!.complete(false)
        assertFalse(lock.state.value.isAuthenticating)
        assertTrue(lock.state.value.isLocked)
        // The hold lasts until the activity is active again (or backgrounded), as on iOS.
        assertFalse(lock.state.value.isCovered)
        lock.sceneDidEnterBackground()
        assertTrue(lock.state.value.isCovered)
        lock.sceneDidBecomeActive()
        assertFalse(lock.state.value.isCovered)
        assertTrue(lock.state.value.isLocked)
    }

    @Test
    fun `a removed screen lock says so and continue turns the lock off`() = runBlocking {
        settings.setAppLockEnabled(true)
        authenticator.capability = AppLockCapability(AppLockCapability.Method.DEVICE_CREDENTIAL, hasPasscode = false)
        val lock = lock()
        lock.restore()

        assertNull(lock.sceneDidBecomeActive())
        assertTrue(lock.state.value.isPasscodeMissing)
        assertTrue(authenticator.reasons.isEmpty())

        lock.continueWithoutPasscode()
        assertFalse(lock.state.value.isEnabled)
        assertFalse(lock.state.value.isLocked)
        assertFalse(lock.state.value.isPasscodeMissing)
        assertFalse(settings.settings.first().appLockEnabled)
    }

    @Test
    fun `turning the lock off needs authentication too`() = runBlocking {
        settings.setAppLockEnabled(true)
        val lock = lock()
        lock.restore()
        authenticator.answers += true
        lock.sceneDidBecomeActive()?.join()

        authenticator.answers += false
        lock.setEnabled(false)
        assertTrue(lock.state.value.isEnabled)
        authenticator.answers += true
        scope.launch { lock.setEnabled(false) }.join()
        assertFalse(lock.state.value.isEnabled)
        assertEquals(AppLock.DISABLE_REASON, authenticator.reasons.last())
    }
}
