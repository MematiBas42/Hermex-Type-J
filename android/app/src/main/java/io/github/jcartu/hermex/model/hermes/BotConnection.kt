package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.TolerantObjectSerializer
import io.github.jcartu.hermex.model.lossyString
import io.github.jcartu.hermex.net.CustomHeader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Port of `BotConnection` (`Features/Bots/BotConnection.swift`): the saved sign-in of one
 * direct-Hermes connection — a Hermes server's own, or a webui server's optional Bots one.
 * Persisted as the same JSON the iOS Keychain record holds (`bot_connection::<server URL>`),
 * with Swift's camelCase keys, so a record either client wrote reads on both.
 *
 * [address] is the dashboard root `HermesAddress.parse` produced: scheme, host and port,
 * never a path, query or fragment.
 */
@Serializable(with = BotConnectionSerializer::class)
data class BotConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: HttpUrl,
    val username: String,
    val password: String,
    /**
     * Release string `/api/status` reported at the last successful connect. Null for
     * records saved before the pin existed or when the host omits `version`.
     */
    val hermesVersion: String? = null,
    /**
     * The host's `install_id` from `/api/status`: one per Hermes root. Null for records
     * saved before it existed or while the host has never reported one. Only a connect
     * that saves a new UUID replaces it; a response that omits it never clears it.
     */
    val installID: String? = null,
    /**
     * Connection Headers for a proxy in front of this host, sent with every request to
     * [address] and nowhere else (`HermesHeaders`). Null when there are none.
     */
    val headers: List<CustomHeader>? = null,
    /**
     * Set when this connection signs in through the host's browser sign-in rather than a
     * password (#708). Its [username] is then the host's user id and its [password] empty.
     */
    val oauth: HermesOAuth? = null,
) {
    /**
     * Saved for a dashboard with no sign-in (`HermesSignInMethod.OPEN`, #1314): no username
     * and no browser sign-in, so anyone who reaches [address] reaches the agent too.
     */
    val hasNoCredentials: Boolean
        get() = username.isEmpty() && oauth == null

    /**
     * `requireSameInstall(_:)` — called with the live `install_id` before any password is
     * sent. Throws [BotFailure.DifferentHost] only when both ids are known and differ; a
     * record without one, or a host that omits it this time, connects as before (trust on
     * first use). The id is public, so this catches an address that now reaches another host.
     */
    fun requireSameInstall(live: String?) {
        if (installID != null && live != null && installID != live) throw BotFailure.DifferentHost
    }

    companion object {
        /**
         * `installID(in:)` — the `install_id` a `/api/status` reply reports, or null when it
         * is omitted (the host omits it rather than sending null when it can't persist one).
         */
        fun installID(status: BotJSON): String? =
            status["install_id"].text?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Port of `HermesOAuth`: the account and refresh token of a connection signed in through
 * the host's browser sign-in (#708). The host rotates [refreshToken] on every refresh, so
 * the live `HermesConnection` holds the current one and saves it before using the access
 * token it came with.
 */
@Serializable(with = HermesOAuthSerializer::class)
data class HermesOAuth(
    /** The host's provider name, such as `nous`, sent with every refresh. */
    val provider: String,
    val userID: String,
    val refreshToken: String,
)

internal object HermesOAuthSerializer : TolerantObjectSerializer<HermesOAuth>("HermesOAuth") {
    override fun fromJson(json: Json, obj: JsonObject): HermesOAuth = HermesOAuth(
        provider = obj.lossyString("provider") ?: throw SerializationException("HermesOAuth requires a provider"),
        userID = obj.lossyString("userID", "user_id") ?: throw SerializationException("HermesOAuth requires a userID"),
        refreshToken = obj.lossyString("refreshToken", "refresh_token")
            ?: throw SerializationException("HermesOAuth requires a refreshToken"),
    )

    override fun toJson(json: Json, value: HermesOAuth): JsonObject = buildJsonObject {
        put("provider", value.provider)
        put("userID", value.userID)
        put("refreshToken", value.refreshToken)
    }
}

internal object BotConnectionSerializer : TolerantObjectSerializer<BotConnection>("BotConnection") {
    private val headerList = ListSerializer(CustomHeader.serializer())

    override fun fromJson(json: Json, obj: JsonObject): BotConnection {
        val address = obj.lossyString("address")?.toHttpUrlOrNull()
            ?: throw SerializationException("BotConnection requires an HTTP address")
        val headers = obj["headers"]?.takeIf { it !is JsonNull }?.let { element ->
            runCatching { json.decodeFromJsonElement(headerList, element) }.getOrNull()
        }
        val oauth = obj["oauth"]?.takeIf { it !is JsonNull }?.let { element ->
            runCatching { json.decodeFromJsonElement(HermesOAuthSerializer, element) }.getOrNull()
        }
        return BotConnection(
            id = obj.lossyString("id") ?: UUID.randomUUID().toString(),
            name = obj.lossyString("name") ?: "",
            address = address,
            username = obj.lossyString("username") ?: "",
            password = obj.lossyString("password") ?: "",
            hermesVersion = obj.lossyString("hermesVersion", "hermes_version"),
            installID = obj.lossyString("installID", "install_id"),
            headers = headers,
            oauth = oauth,
        )
    }

    override fun toJson(json: Json, value: BotConnection): JsonObject = buildJsonObject {
        put("id", value.id)
        put("name", value.name)
        put("address", value.address.toString())
        put("username", value.username)
        put("password", value.password)
        value.hermesVersion?.let { put("hermesVersion", it) }
        value.installID?.let { put("installID", it) }
        value.headers?.let { put("headers", JsonArray(it.map { header -> json.encodeToJsonElement(CustomHeader.serializer(), header) })) }
        value.oauth?.let { put("oauth", json.encodeToJsonElement(HermesOAuthSerializer, it)) }
    }
}

/** A Swift `UUID().uuidString`-shaped id (uppercase), as the iOS record keys sign-ins. */
internal fun newConnectionID(): String = UUID.randomUUID().toString().uppercase()

/** `JsonPrimitive` of a nullable string, or JSON null. */
internal fun jsonStringOrNull(value: String?): JsonPrimitive = value?.let { JsonPrimitive(it) } ?: JsonNull
