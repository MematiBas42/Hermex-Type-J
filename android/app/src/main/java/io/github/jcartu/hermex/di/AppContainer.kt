package io.github.jcartu.hermex.di

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jcartu.hermex.core.crypto.AndroidKeystoreCipher
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.core.net.PersistentCookieJar
import io.github.jcartu.hermex.core.prefs.CookieSerializer
import io.github.jcartu.hermex.net.CustomHeaderStore
import kotlinx.coroutines.launch
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "hermex_prefs")

/** The section-visibility toggles' own file (pre-dates `SettingsRepository` absorbing them; kept for in-place upgrades). */
private val Context.visibilityDataStore by preferencesDataStore(name = "hermex_section_visibility")

/**
 * Manual dependency container. One instance per process, owned by [io.github.jcartu.hermex.HermexApplication].
 * Feature services are constructed lazily here or passed the pieces they need.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Port of NetworkPathMonitor: stream recovery waits offline and resumes on a path change. */
    val networkMonitor by lazy { io.github.jcartu.hermex.net.NetworkMonitor(appContext).also { it.start() } }
    /** ProcessLifecycleOwner foreground trigger shared by chat and the session list. */
    val foregroundMonitor by lazy { io.github.jcartu.hermex.core.lifecycle.ForegroundMonitor().also { it.start() } }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    val cipher: StringCipher = AndroidKeystoreCipher()

    val serverConfig: ServerConfigRepository = ServerConfigRepository(
        dataStore = context.dataStore,
        cipher = cipher,
    )

    val settings: SettingsRepository = SettingsRepository(
        dataStore = context.dataStore,
        visibilityDataStore = context.visibilityDataStore,
    )

    /** Per-server encrypted webui passwords, keyed by registry id (Contract C). */
    val serverCredentials: io.github.jcartu.hermex.core.prefs.ServerCredentialStore =
        io.github.jcartu.hermex.core.prefs.ServerCredentialStore(context.dataStore, cipher)

    /** The global identity defaults the registry seeds from and mirrors the active server into. */
    val identityDefaults: io.github.jcartu.hermex.core.prefs.SettingsIdentityDefaults =
        io.github.jcartu.hermex.core.prefs.SettingsIdentityDefaults(settings, appScope)

    /** Every configured server and which one is active; `AuthManager` owns all writes. */
    val serverRegistry: io.github.jcartu.hermex.model.ServerRegistry by lazy {
        io.github.jcartu.hermex.model.ServerRegistry(
            keychain = io.github.jcartu.hermex.core.prefs.EncryptedPreferencesKeychain(
                preferences = appContext.getSharedPreferences(
                    io.github.jcartu.hermex.core.prefs.EncryptedPreferencesKeychain.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
                cipher = cipher,
            ),
            identityDefaults = identityDefaults,
        )
    }

    /** The optional app lock (#885); `MainActivity` attaches the device authenticator and lifecycle. */
    val appLock: io.github.jcartu.hermex.core.security.AppLock by lazy {
        io.github.jcartu.hermex.core.security.AppLock(settings = settings, scope = appScope)
    }

    val notificationOffer: io.github.jcartu.hermex.core.prefs.NotificationOffer by lazy {
        io.github.jcartu.hermex.core.prefs.NotificationOffer(
            settings = settings,
            authorization = { io.github.jcartu.hermex.ui.feature.settings.NotificationPermissions.authorization(appContext, settings.settings.first().hasRequestedNotificationPermission) },
        )
    }

    val cookieStore: CookieSerializer = CookieSerializer(context.dataStore)

    val cookieJar: CookieJar = PersistentCookieJar(
        store = cookieStore,
        scope = appScope,
    )

    val customHeaderStore = CustomHeaderStore()
    val customHeaders = io.github.jcartu.hermex.core.prefs.EncryptedCustomHeaderRepository(
        dataStore = context.dataStore,
        cipher = cipher,
        json = json,
        runtimeStore = customHeaderStore,
    )
    val deepLinks = io.github.jcartu.hermex.core.navigation.DeepLinkRepository(context.dataStore)
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        appScope.launch {
            deepLinks.load()
            identityDefaults.load()
            customHeaders.load(serverConfig.current().baseUrl)
            appLock.restore()
            _isReady.value = true
            serverConfig.config.collect { customHeaders.activate(it.baseUrl) }
        }
    }

    val api: io.github.jcartu.hermex.net.HermesApi by lazy {
        io.github.jcartu.hermex.net.HermesApi(
            httpClient = okHttpClient,
            json = json,
            configProvider = { serverConfig.current() },
            customHeaderProvider = { customHeaderStore.snapshot() },
        )
    }

    /** Single auth state machine shared by shell, onboarding, and settings. */
    val authManager: io.github.jcartu.hermex.ui.feature.onboarding.AuthManager by lazy {
        io.github.jcartu.hermex.ui.feature.onboarding.AuthManager(
            // A probed server gets its own client, bound to its URL and the headers
            // being tried, so probing never touches the active server's client.
            clientFactory = { baseUrl, headers ->
                io.github.jcartu.hermex.ui.feature.onboarding.HermesAuthClient(
                    io.github.jcartu.hermex.net.HermesApi(
                        httpClient = okHttpClient,
                        json = json,
                        configProvider = { ServerConfig(baseUrl, "") },
                        customHeaderProvider = { headers },
                    ),
                )
            },
            serverConfig = serverConfig,
            registry = serverRegistry,
            credentials = serverCredentials,
            customHeaders = customHeaders,
            cookies = object : io.github.jcartu.hermex.ui.feature.onboarding.SessionCookieStore {
                override fun clear(serverUrl: String) {
                    val url = serverUrl.toHttpUrlOrNull() ?: return
                    (cookieJar as? PersistentCookieJar)?.clearFor(url)
                }

                override fun clearAll() {
                    (cookieJar as? PersistentCookieJar)?.clearAll()
                }
            },
            scope = appScope,
        )
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .cookieJar(cookieJar)
            // NOTE: never add Origin or Referer headers anywhere — the upstream
            // CSRF check treats requests without them as non-browser clients,
            // which is the supported path for native apps. See PROJECT_SPEC.md §6.1.
            .build()
    }

    /**
     * The per-server registry of direct-Hermes connections (`HermesConnections.shared` in
     * Swift): every Hermes screen on the active server shares one sign-in and one gateway
     * socket through it. Built on [okHttpClient] so the connection pool is shared; each
     * connection keeps its own ephemeral cookie jar.
     */
    val hermesConnections: io.github.jcartu.hermex.net.hermes.HermesConnections by lazy {
        io.github.jcartu.hermex.net.hermes.HermesConnections(httpClient = { okHttpClient })
    }

    val modelFavorites: io.github.jcartu.hermex.model.ModelFavoritesStore by lazy {
        io.github.jcartu.hermex.model.ModelFavoritesStore(dataStore = appContext.dataStore)
    }

    val modelRecents: io.github.jcartu.hermex.model.ModelRecentsStore by lazy {
        io.github.jcartu.hermex.model.ModelRecentsStore(dataStore = appContext.dataStore)
    }

    val cacheStore: io.github.jcartu.hermex.data.CacheStore by lazy {
        io.github.jcartu.hermex.data.CacheStore(
            directory = java.io.File(appContext.filesDir, "offline-cache"),
            json = json,
        )
    }

    val sharedImports: io.github.jcartu.hermex.core.share.SharedImportRepository by lazy {
        io.github.jcartu.hermex.core.share.SharedImportRepository(appContext, json).also { repository ->
            appScope.launch { repository.refresh() }
        }
    }
}
