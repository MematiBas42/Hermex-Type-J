package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.core.prefs.EncryptedCustomHeaderRepository
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.ServerCredentialStore
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.model.HealthResponse
import io.github.jcartu.hermex.model.LoginResponse
import io.github.jcartu.hermex.model.ServerRegistry
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.CustomHeaderStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.rules.TemporaryFolder

/** In-memory `ServerRegistry.Keychain` — the persisted registry blob. */
class InMemoryKeychain : ServerRegistry.Keychain {
    val values = HashMap<String, String>()
    override fun save(value: String, key: String) { values[key] = value }
    override fun load(key: String): String? = values[key]
}

/** In-memory `ServerRegistry.IdentityDefaults` — the global identity mirror. */
class InMemoryIdentityDefaults : ServerRegistry.IdentityDefaults {
    val values = HashMap<String, String>()
    override fun string(key: String): String? = values[key]
    override fun set(value: String, key: String) { values[key] = value }
}

/** Records which host's cookies were cleared. */
class RecordingCookieStore : SessionCookieStore {
    val cleared = mutableListOf<String>()
    var clearAllCount = 0
    override fun clear(serverUrl: String) { cleared += serverUrl }
    override fun clearAll() { clearAllCount += 1 }
}

/**
 * Everything an `AuthManager` needs, on pure-JVM stores: one mock
 * [AuthApiClient] per probed URL (so a test can script server A and server B
 * differently and assert which URL/headers a call was bound to).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthTestFixture(tmpFolder: TemporaryFolder) {
    private class PassthroughCipher : StringCipher {
        override fun encrypt(plainText: String) = "enc:$plainText"
        override fun decrypt(cipherText: String) = cipherText.removePrefix("enc:")
    }

    val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { tmpFolder.newFile("auth_prefs.preferences_pb") },
    )
    val cipher: StringCipher = PassthroughCipher()
    val serverConfig = ServerConfigRepository(dataStore, cipher)
    val credentials = ServerCredentialStore(dataStore, cipher)
    val keychain = InMemoryKeychain()
    val identityDefaults = InMemoryIdentityDefaults()
    val runtimeHeaders = CustomHeaderStore()
    val customHeaders = EncryptedCustomHeaderRepository(
        dataStore = dataStore,
        cipher = cipher,
        json = Json { ignoreUnknownKeys = true },
        runtimeStore = runtimeHeaders,
    )
    val cookies = RecordingCookieStore()
    val botRecords = mutableMapOf<String, io.github.jcartu.hermex.model.hermes.BotConnection>()
    val botConnections = object : io.github.jcartu.hermex.core.prefs.BotConnectionRecords {
        override fun load(server: String) = botRecords[server]
        override fun save(connection: io.github.jcartu.hermex.model.hermes.BotConnection, server: String) { botRecords[server] = connection }
        override fun remove(server: String) { botRecords.remove(server) }
    }
    val discardedHermesData = mutableListOf<Pair<String, String?>>()

    /** The clients handed out, keyed by probed URL, so tests can stub per server. */
    val clients = HashMap<String, AuthApiClient>()

    /** Every (url, headers) pair a client was built for, in order. */
    val clientRequests = mutableListOf<Pair<String, List<CustomHeader>>>()

    fun client(url: String): AuthApiClient = clients.getOrPut(url) { mockk(relaxed = false) }

    fun stubHealthy(url: String, authEnabled: Boolean?, passwordAuthEnabled: Boolean? = null, loggedIn: Boolean? = null) {
        val client = client(url)
        coEvery { client.health() } returns HealthResponse(status = "ok")
        coEvery { client.authStatus() } returns AuthStatusResponse(
            authEnabled = authEnabled,
            passwordAuthEnabled = passwordAuthEnabled,
            loggedIn = loggedIn,
        )
        coEvery { client.logout() } returns LoginResponse(ok = true)
    }

    fun stubLogin(url: String, password: String, ok: Boolean) {
        coEvery { client(url).login(password) } returns LoginResponse(ok = ok)
    }

    /** A new registry each time, loaded from [keychain] like a process start. */
    fun registry(): ServerRegistry = ServerRegistry(keychain, identityDefaults, now = { 1_000.0 })

    fun manager(registry: ServerRegistry = registry(), logoutTimeoutMillis: Long = 5_000): AuthManager {
        runBlocking { customHeaders.load(serverConfig.current().baseUrl) }
        return AuthManager(
            clientFactory = { url, headers ->
                clientRequests += url to headers
                client(url)
            },
            serverConfig = serverConfig,
            registry = registry,
            credentials = credentials,
            customHeaders = customHeaders,
            cookies = cookies,
            scope = scope,
            botConnections = botConnections,
            discardHermesData = { server, connectionID -> discardedHermesData += server to connectionID },
            logoutTimeoutMillis = logoutTimeoutMillis,
        )
    }
}
