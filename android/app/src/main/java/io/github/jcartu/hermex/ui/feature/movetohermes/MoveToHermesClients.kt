package io.github.jcartu.hermex.ui.feature.movetohermes

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.SessionExportFormat
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.CrossOriginHeaderStripper
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.applyTo
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesSessionChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Webui read/export surface from `WebuiSessionTransfer.swift` (#1213). */
interface MoveWebuiClient {
    suspend fun scanListing(): MoveWebuiListing
    suspend fun profiles(): List<String>
    suspend fun switchProfile(profile: String): Boolean
    suspend fun exportSession(sessionId: String): ByteArray
}

@kotlinx.serialization.Serializable
data class MoveWebuiListing(
    val sessions: List<MoveSourceChat>,
    val allProfiles: Boolean? = null,
    val activeProfile: String? = null,
)

/** Direct-Hermes surface used by scan, imports, reconcile, and resume. */
interface MoveHermesClient {
    suspend fun connect()
    suspend fun profileCatalog(): HermesMoveProfileCatalog
    suspend fun sessionScanPage(profile: String, offset: Int): HermesSessionPage
    suspend fun sessionRow(sessionId: String, profile: String): BotJSON?
    suspend fun importSessions(body: ByteArray): BotJSON
    suspend fun updateTitle(sessionId: String, profile: String, title: String)
    suspend fun updatePinned(sessionId: String, profile: String, pinned: Boolean)
    suspend fun updateFolder(sessionId: String, profile: String, folder: String)
    suspend fun close()
}

/**
 * Real direct-Hermes adapter. [BotClient] dispatches on `HermesActor.dispatcher`; callers update
 * Compose state only after these suspend calls return on their Main-bound ViewModel scope.
 */
class HermesMoveClient(connection: HermesConnection) : MoveHermesClient {
    private val client = BotClient(connection)

    override suspend fun connect() = client.connect()

    override suspend fun profileCatalog(): HermesMoveProfileCatalog {
        val reply = client.call(HermesCall.ProfilesList(includeSessions = false))
        val profiles = reply["profiles"].list.orEmpty().mapNotNull { it["name"].text }.filter { it.isNotBlank() }
        val default = reply["profiles"].list.orEmpty()
            .firstOrNull { it["is_default"].flag == true }
            ?.get("name")
            ?.text
        if (profiles.isEmpty()) throw IllegalStateException("Hermes did not return any Profiles.")
        return HermesMoveProfileCatalog(profiles.distinct(), default?.takeIf { it in profiles })
    }

    override suspend fun sessionScanPage(profile: String, offset: Int): HermesSessionPage =
        client.sessionScanPage(profile, offset)

    override suspend fun sessionRow(sessionId: String, profile: String): BotJSON? =
        client.sessionRow(sessionId, profile)

    override suspend fun importSessions(body: ByteArray): BotJSON = client.importSessions(body)

    override suspend fun updateTitle(sessionId: String, profile: String, title: String) {
        client.updateSession(HermesSessionChange.Title(title), sessionId, profile)
    }

    override suspend fun updatePinned(sessionId: String, profile: String, pinned: Boolean) {
        client.updateSession(HermesSessionChange.Pinned(pinned), sessionId, profile)
    }

    override suspend fun updateFolder(sessionId: String, profile: String, folder: String) {
        client.call(HermesCall.SessionWorkspaceMove(profile, sessionId, folder))
    }

    override suspend fun close() = client.close()
}

/**
 * A move gets a private cookie jar seeded from the source server's signed-in jar. Profile switches
 * therefore never change the cookie used by the visible webui session list (`WebuiSessionTransfer`).
 */
class WebuiMoveClient private constructor(
    private val baseUrl: HttpUrl,
    private val headers: List<CustomHeader>,
    private val json: Json,
    private val rawClient: OkHttpClient,
    private val api: HermesApi,
) : MoveWebuiClient {

    override suspend fun scanListing(): MoveWebuiListing {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/sessions")
            .addQueryParameter("all_profiles", "1")
            .addQueryParameter("include_archived", "1")
            .build()
        val builder = Request.Builder().url(url).get()
        headers.applyTo(builder)
        builder.header("Accept", "application/json")
        val response = rawClient.await(builder.build())
        val bytes = try {
            val body = withContext(Dispatchers.IO) { response.body?.bytes() ?: ByteArray(0) }
            if (!response.isSuccessful) throw IOException("webui sessions returned HTTP ${response.code}")
            body
        } finally {
            response.close()
        }
        val root = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw IOException("webui sessions returned unreadable JSON")
        val slots = root.element("sessions") as? JsonArray
            ?: throw IOException("webui sessions reply omitted sessions")
        val rows = slots.mapNotNull { parseRow(it as? JsonObject ?: return@mapNotNull null) }
        return MoveWebuiListing(
            sessions = rows,
            allProfiles = root.lossyBoolean("all_profiles", "allProfiles"),
            activeProfile = root.lossyString("active_profile", "activeProfile"),
        )
    }

    override suspend fun profiles(): List<String> =
        api.profiles().profiles.orEmpty().mapNotNull { it.normalizedName }.distinct()

    override suspend fun switchProfile(profile: String): Boolean {
        val reply = api.switchProfile(profile)
        return reply.error.isNullOrBlank() && (reply.active.isNullOrBlank() || reply.active == profile)
    }

    override suspend fun exportSession(sessionId: String): ByteArray =
        api.exportSession(sessionId, SessionExportFormat.json).data

    private fun parseRow(row: JsonObject): MoveSourceChat? {
        val id = row.lossyString("session_id", "sessionId")?.takeIf { it.isNotBlank() } ?: return null
        val sources = listOf("session_source", "sessionSource", "source_tag", "sourceTag", "raw_source", "rawSource")
            .mapNotNull { row.lossyString(it)?.trim()?.lowercase(Locale.ROOT) }
        val hasWebuiMarker = "webui" in sources
        val external = !hasWebuiMarker && (
            row.lossyBoolean("is_cli_session", "isCliSession") == true || "external_agent" in sources
            )
        if (external) return null
        val running = row.lossyBoolean("is_streaming", "isStreaming") == true ||
            !row.lossyString("active_stream_id", "activeStreamId").isNullOrBlank()
        return MoveSourceChat(
            id = id,
            profile = row.lossyString("profile")?.takeIf { it.isNotBlank() } ?: "default",
            title = row.lossyString("title"),
            messageCount = row.lossyInt("message_count", "messageCount"),
            archived = row.lossyBoolean("archived") ?: false,
            pinned = row.lossyBoolean("pinned") ?: false,
            workspace = row.lossyString("workspace"),
            isRunning = running,
        )
    }

    companion object {
        fun create(container: AppContainer, source: ServerAccount): WebuiMoveClient {
            val base = source.urlString.toHttpUrl()
            val headers = container.authManager.savedCustomHeaders(source)
            val seed = container.cookieJar.loadForRequest(base)
            val isolated = container.okHttpClient.newBuilder().cookieJar(IsolatedCookieJar(seed)).build()
            val guarded = isolated.newBuilder().addNetworkInterceptor(
                CrossOriginHeaderStripper(baseUrlProvider = { base }, customHeaderProvider = { headers }),
            ).build()
            val api = HermesApi(
                httpClient = isolated,
                json = container.json,
                configProvider = { ServerConfig(source.urlString, "") },
                customHeaderProvider = { headers },
            )
            return WebuiMoveClient(base, headers, container.json, guarded, api)
        }
    }
}

private class IsolatedCookieJar(seed: List<Cookie>) : CookieJar {
    private val lock = Any()
    private val cookies = seed.toMutableList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        cookies.filter { it.matches(url) }
    }
}

private suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}

private fun JsonObject.element(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeUnless { it is JsonNull } }

private fun JsonObject.lossyString(vararg names: String): String? =
    (element(*names) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.lossyBoolean(vararg names: String): Boolean? {
    val primitive = element(*names) as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.lowercase(Locale.ROOT)) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}

private fun JsonObject.lossyInt(vararg names: String): Int? {
    val primitive = element(*names) as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}
