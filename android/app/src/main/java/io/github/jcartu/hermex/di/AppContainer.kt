package io.github.jcartu.hermex.di

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jcartu.hermex.core.crypto.AndroidKeystoreCipher
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.core.net.PersistentCookieJar
import io.github.jcartu.hermex.core.prefs.CookieSerializer
import kotlinx.coroutines.launch
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "hermex_prefs")

/**
 * Manual dependency container. One instance per process, owned by [io.github.jcartu.hermex.HermexApplication].
 * Feature services are constructed lazily here or passed the pieces they need.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    val settings: SettingsRepository = SettingsRepository(context.dataStore)

    val cookieStore: CookieSerializer = CookieSerializer(context.dataStore)

    val cookieJar: CookieJar = PersistentCookieJar(
        store = cookieStore,
        scope = appScope,
    )

    val api: io.github.jcartu.hermex.net.HermesApi by lazy {
        io.github.jcartu.hermex.net.HermesApi(
            httpClient = okHttpClient,
            json = json,
            configProvider = { serverConfig.current() },
        )
    }

    /** Single auth state machine shared by shell, onboarding, and settings. */
    val authManager: io.github.jcartu.hermex.ui.feature.onboarding.AuthManager by lazy {
        io.github.jcartu.hermex.ui.feature.onboarding.AuthManager(
            api = api,
            serverConfig = serverConfig,
            sessionResetter = {
                (cookieJar as? PersistentCookieJar)?.clearAll()
                appScope.launch { serverConfig.clear() }
            },
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
}
