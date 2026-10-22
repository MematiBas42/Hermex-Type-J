package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.URI

/** Type-J's default Tokyo Night accent; explicitly saved server colors are preserved. */
private const val DEFAULT_HEADER_LOGO_COLOR_HEX = "#7AA2F7"

/** `HeaderLogoColor.storageKey` (`Config/AppTheme.swift`). */
private const val HEADER_LOGO_COLOR_STORAGE_KEY = "headerLogoColorHex"

/** `SessionIdentitySettings.displayNameKey` (`Features/SessionList/SessionRowView.swift`). */
private const val IDENTITY_DISPLAY_NAME_KEY = "sessionIdentity.displayName"

/** `SessionIdentitySettings.initialsKey` (`Features/SessionList/SessionRowView.swift`). */
private const val IDENTITY_INITIALS_KEY = "sessionIdentity.initials"

/**
 * Port of `ServerKind` (`Models/ServerAccount.swift`, #899): what a configured
 * server is. A webui server is a `hermes-webui` instance, signed in through
 * `AuthManager` with its own cookie; a Hermes server is a Hermes dashboard
 * Hermex reaches directly (`net/hermes`), whose sign-in is the `BotConnection`
 * saved under its own URL. A missing or unknown value reads as [WEBUI], the
 * only kind before #899.
 */
enum class ServerKind(val rawValue: String) {
    WEBUI("webui"),
    HERMES("hermes"),
    ;

    companion object {
        /** `ServerKind(rawValue:)` — null for an unknown wire value. */
        fun fromRawValue(rawValue: String?): ServerKind? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Non-secret-shaped metadata for one configured Hermes Web UI server.
 *
 * This is the persisted account model introduced by I-039a (#15) — the
 * foundation the rest of the multi-server epic (#16/#17/#18) builds on. The
 * server URL is treated as a credential (PROJECT_SPEC Phase 1), so the whole
 * registry is persisted alongside `server_url` and `custom_headers` (see
 * [ServerRegistry]). The auth cookie still lives in the cookie jar. This model
 * is an additive shadow of the single-server state; nothing in this slice reads
 * it for routing yet.
 *
 * Decoding is tolerant (CLAUDE.md rule 3): missing fields fall back to sensible
 * defaults so a blob written by a different slice in the epic still loads.
 * Dates are epoch-seconds doubles (the port-wide `Date` mapping).
 */
@Serializable(with = ServerAccountSerializer::class)
data class ServerAccount(
    /**
     * Stable per-server identity. We reuse the normalized base-URL string so it
     * matches the offline cache's `serverURLString` key (`CachedSession` /
     * `CachedMessage`) — no separate id↔URL mapping to keep in sync.
     */
    val id: String,
    /** Normalized base URL (scheme + host [+ port]); equal to [id] in this slice. */
    val urlString: String,
    /**
     * User-facing label. Seeded from the global identity on migration; editable
     * per server in #17.
     */
    val displayName: String = "",
    /** Avatar initials. Seeded/derived on migration; editable per server in #17. */
    val initials: String = "",
    /**
     * Per-server Header Logo Color (hex). Seeded from the global color on
     * migration; editable per server in #17.
     */
    val headerLogoColorHex: String = DEFAULT_HEADER_LOGO_COLOR_HEX,
    /**
     * Reference under which this server's custom request headers are scoped.
     * Seeded to the server [id]; as of #16 headers are persisted per server
     * under a key scoped by that id (`AuthManager` scopes by the equivalent
     * normalized URL string), so server A's proxy token is never sent to server B.
     */
    val customHeadersRef: String? = null,
    val createdAt: Double = 0.0,
    val updatedAt: Double = 0.0,
    /**
     * Set when the server is added and never changed: a webui server and a
     * Hermes server can't share a URL (`AuthManager.addHermesServer`). Wire key
     * `kind`; missing or unknown reads as [ServerKind.WEBUI].
     */
    val kind: ServerKind = ServerKind.WEBUI,
    /**
     * A Hermes server's release, as `/api/status` reported it at the last saved
     * sign-in. Null for webui servers, whose version Settings reads live.
     */
    val serverVersion: String? = null,
)

internal object ServerAccountSerializer : TolerantObjectSerializer<ServerAccount>("ServerAccount") {
    override fun fromJson(json: Json, obj: JsonObject): ServerAccount {
        // An entry needs at least an identifier; tolerate either key being the
        // one present so older/newer blobs across the epic still decode. Both
        // spellings are accepted: the iOS blob stores the CodingKeys verbatim
        // (camelCase, plain JSONEncoder) while the port writes snake_case.
        val decodedID = obj.lossyString("id")
        val decodedURL = obj.lossyString("url_string", "urlString")
        val resolved = decodedID ?: decodedURL
            ?: throw SerializationException("ServerAccount requires an id or urlString")
        val createdAt = obj.lossyDouble("created_at", "createdAt") ?: 0.0
        return ServerAccount(
            id = decodedID ?: resolved,
            urlString = decodedURL ?: resolved,
            displayName = obj.lossyString("display_name", "displayName") ?: "",
            initials = obj.lossyString("initials") ?: "",
            headerLogoColorHex = obj.lossyString("header_logo_color_hex", "headerLogoColorHex")
                ?: DEFAULT_HEADER_LOGO_COLOR_HEX,
            customHeadersRef = obj.lossyString("custom_headers_ref", "customHeadersRef"),
            createdAt = createdAt,
            updatedAt = obj.lossyDouble("updated_at", "updatedAt") ?: createdAt,
            // Read as text so a kind from a newer build, or a value of the wrong
            // type, falls back to webui instead of failing the entry.
            kind = ServerKind.fromRawValue(obj.lossyString("kind")) ?: ServerKind.WEBUI,
            serverVersion = obj.lossyString("server_version", "serverVersion"),
        )
    }

    override fun toJson(json: Json, value: ServerAccount): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(value.id))
        put("url_string", JsonPrimitive(value.urlString))
        put("display_name", JsonPrimitive(value.displayName))
        put("initials", JsonPrimitive(value.initials))
        put("header_logo_color_hex", JsonPrimitive(value.headerLogoColorHex))
        value.customHeadersRef?.let { put("custom_headers_ref", JsonPrimitive(it)) }
        put("created_at", JsonPrimitive(value.createdAt))
        put("updated_at", JsonPrimitive(value.updatedAt))
        put("kind", JsonPrimitive(value.kind.rawValue))
        value.serverVersion?.let { put("server_version", JsonPrimitive(it)) }
    }
}

/**
 * Process-wide, thread-safe registry of configured servers plus which one is
 * active, persisted as a single JSON blob through the injected [Keychain] seam
 * (the server URL is a credential — PROJECT_SPEC Phase 1, #15).
 *
 * Mirrors `CustomHeaderStore`: the blob is loaded **once** at init into a
 * lock-guarded in-memory snapshot, reads come from that snapshot (no
 * synchronous storage IPC on every property access), and mutations update the
 * snapshot and write through to the store under the same lock.
 * `AuthManager` owns all writes: it calls [activate] when it configures or
 * restores a server, and [forgetActiveServer] on full sign-out. Per-server
 * identity is seeded from the global identity defaults on first insert only, so
 * later per-server edits (#17) survive relaunch.
 *
 * Port note: Swift's `KeychainStoring` / `UserDefaults` / `OSAllocatedUnfairLock`
 * couplings are replaced by the pure-JVM seams [Keychain], [IdentityDefaults]
 * and a `synchronized` lock; Swift's `static let shared` singleton is not
 * ported — on Android the instance is wired once through AppContainer with the
 * real keystore-backed store. [activate] takes the already-normalized URL
 * string (Swift takes a `URL` and immediately uses `absoluteString`).
 */
class ServerRegistry(
    private val keychain: Keychain,
    private val identityDefaults: IdentityDefaults,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * Storage seam for the persisted registry blob — the subset of Swift's
     * `KeychainStoring` the registry uses (string save/load for the
     * `"servers"` key). Implementations may throw; the registry swallows
     * failures exactly like Swift's `try?`.
     */
    interface Keychain {
        fun save(value: String, key: String)

        fun load(key: String): String?
    }

    /**
     * Seam for the global identity defaults — the subset of Swift's
     * `UserDefaults` the registry uses (`sessionIdentity.*` /
     * `headerLogoColorHex` string read/write).
     */
    interface IdentityDefaults {
        fun string(key: String): String?

        fun set(value: String, key: String)
    }

    /**
     * Single persisted blob so the list and the active selection stay atomic.
     * (Swift: private nested `Snapshot`; tolerant `init(from:)` ported to
     * [SnapshotSerializer].)
     */
    @Serializable(with = SnapshotSerializer::class)
    data class Snapshot(
        val servers: List<ServerAccount> = emptyList(),
        val activeServerID: String? = null,
    ) {
        val activeServer: ServerAccount?
            get() = activeServerID?.let { id -> servers.firstOrNull { it.id == id } }
    }

    internal object SnapshotSerializer : TolerantObjectSerializer<Snapshot>("Snapshot") {
        override fun fromJson(json: Json, obj: JsonObject): Snapshot = Snapshot(
            // `(try? decodeIfPresent([ServerAccount].self)) ?? []` — any decode
            // failure of the array (wrong shape, bad entry) yields an empty list.
            servers = obj.presentElement("servers")?.let { element ->
                runCatching {
                    json.decodeFromJsonElement(ListSerializer(ServerAccount.serializer()), element)
                }.getOrNull()
            } ?: emptyList(),
            // `try? decodeIfPresent(String.self)` — a present non-string yields null.
            activeServerID = obj.presentElement("active_server_id", "activeServerID")
                ?.let { it as? JsonPrimitive }
                ?.takeIf { it.isString }
                ?.content,
        )

        override fun toJson(json: Json, value: Snapshot): JsonObject = buildJsonObject {
            put("servers", json.encodeToJsonElement(ListSerializer(ServerAccount.serializer()), value.servers))
            value.activeServerID?.let { put("active_server_id", JsonPrimitive(it)) }
        }
    }

    private val lock = Any()

    /** In-memory snapshot, loaded once at init; guarded by [lock]. */
    private var storage: Snapshot = loadSnapshot(keychain, json)

    // MARK: - Reads (in-memory snapshot, no storage IPC)

    val servers: List<ServerAccount>
        get() = synchronized(lock) { storage.servers }

    val activeServerID: String?
        get() = synchronized(lock) { storage.activeServerID }

    val activeServer: ServerAccount?
        get() = synchronized(lock) { storage.activeServer }

    // MARK: - Mutations (update snapshot + write through to the store)

    /**
     * Ensures `url` is registered and marked active, returning the active entry.
     *
     * Dedupes by id (the normalized URL string): a URL already present is just
     * re-activated, never duplicated, and its identity and kind are left untouched
     * so any per-server edits from #17 survive. A new URL is inserted as [kind],
     * with [serverVersion] and its identity seeded from the current global identity
     * defaults. Callers pass an already-normalized URL
     * (`AuthManager.normalizedServerURL`, or `HermesAddress.parse` for a Hermes server).
     */
    fun activate(
        url: String,
        kind: ServerKind = ServerKind.WEBUI,
        serverVersion: String? = null,
    ): ServerAccount {
        val id = url
        // When re-activating an already-registered server we mirror its (possibly
        // per-server-edited, #17) identity into the global identity defaults so the
        // existing settings-backed consumers follow the switch. We never mirror
        // on first insert: a new entry is *seeded from* those defaults, so writing
        // back would change first-run identity for single-server users.
        var identityToMirror: ServerAccount? = null
        val result: ServerAccount = synchronized(lock) {
            val existing = storage.servers.firstOrNull { it.id == id }
            if (existing != null) {
                // Already registered: only flip the active selection + write
                // through when it actually changes, so the launch path
                // (restoreSavedServer → activate for the already-active server)
                // doesn't do a redundant synchronous storage write every start.
                if (storage.activeServerID != id) {
                    storage = storage.copy(activeServerID = id)
                    persist(storage)
                    identityToMirror = existing
                }
                existing
            } else {
                val account = makeSeededAccount(id, url).copy(kind = kind, serverVersion = serverVersion)
                storage = storage.copy(
                    servers = storage.servers + account,
                    activeServerID = id,
                )
                persist(storage)
                account
            }
        }
        identityToMirror?.let { mirrorIdentityToDefaults(it) }
        return result
    }

    /**
     * Port of `ServerRegistry.add(url:kind:serverVersion:)`: inserts a server without
     * changing the active one, as [activate] inserts it: seeded from the global
     * identity defaults. An id already present is returned untouched. Used for a
     * Hermes server signed in from Move to Hermes, which switches only at its Done (#1266).
     */
    fun add(url: String, kind: ServerKind, serverVersion: String?): ServerAccount {
        val id = url
        return synchronized(lock) {
            storage.servers.firstOrNull { it.id == id } ?: run {
                val account = makeSeededAccount(id, url).copy(kind = kind, serverVersion = serverVersion)
                storage = storage.copy(servers = storage.servers + account)
                persist(storage)
                account
            }
        }
    }

    /**
     * Marks an already-registered server active (the Settings switcher, #17).
     * No-op — returning null — when `id` isn't registered or is already active.
     * Mirrors the newly active server's identity into the global identity
     * defaults so the avatar / header tint follow the switch.
     */
    fun setActive(id: String): ServerAccount? {
        var newActive: ServerAccount? = null
        synchronized(lock) {
            if (storage.activeServerID != id) {
                val account = storage.servers.firstOrNull { it.id == id }
                if (account != null) {
                    storage = storage.copy(activeServerID = id)
                    persist(storage)
                    newActive = account
                }
            }
        }
        newActive?.let { mirrorIdentityToDefaults(it) }
        return newActive
    }

    /**
     * Removes the server `id`. When it was the active server, auto-selects the
     * next remaining server as active and mirrors its identity; returns the
     * server that is active *after* removal (null when none remain, i.e. the
     * caller should return to onboarding). Removing a non-active server leaves
     * the active selection untouched. No-op for an unregistered id. (#17)
     */
    fun remove(id: String): ServerAccount? {
        var activeChangedTo: ServerAccount? = null
        var didChangeActive = false
        val activeAfter: ServerAccount? = synchronized(lock) {
            if (storage.servers.none { it.id == id }) {
                return@synchronized storage.activeServer
            }
            val wasActive = storage.activeServerID == id
            storage = storage.copy(servers = storage.servers.filterNot { it.id == id })
            if (wasActive) {
                val next = storage.servers.firstOrNull()
                storage = storage.copy(activeServerID = next?.id)
                didChangeActive = true
                activeChangedTo = next
            }
            persist(storage)
            storage.activeServer
        }
        if (didChangeActive) {
            activeChangedTo?.let { mirrorIdentityToDefaults(it) }
        }
        return activeAfter
    }

    /**
     * Replaces the stored entry for [account.id] (per-server identity edits,
     * #17), bumping `updatedAt`. No-op for an unregistered id. Mirrors to the
     * global identity defaults when the updated server is the active one, so the
     * active server's edits show up live without each consumer reading the
     * registry directly.
     */
    fun update(account: ServerAccount) {
        var activeUpdate: ServerAccount? = null
        synchronized(lock) {
            val index = storage.servers.indexOfFirst { it.id == account.id }
            if (index >= 0) {
                val updated = account.copy(updatedAt = now())
                storage = storage.copy(
                    servers = storage.servers.toMutableList().also { it[index] = updated },
                )
                persist(storage)
                if (storage.activeServerID == account.id) {
                    activeUpdate = updated
                }
            }
        }
        activeUpdate?.let { mirrorIdentityToDefaults(it) }
    }

    /**
     * Forgets the active server entirely, mirroring a full sign-out
     * (`AuthManager.clearLocalAuth`) so a single-server install returns to
     * "no servers configured."
     */
    fun forgetActiveServer() {
        synchronized(lock) {
            val id = storage.activeServerID ?: return
            storage = storage.copy(
                servers = storage.servers.filterNot { it.id == id },
                activeServerID = null,
            )
            persist(storage)
        }
    }

    // MARK: - Seeding

    /**
     * Builds a fresh entry, seeding per-server identity from the global identity
     * defaults and falling back to a host-derived label/initials when those are
     * empty (intake: "derive from the normalized host by default").
     */
    private fun makeSeededAccount(id: String, url: String): ServerAccount {
        val hostFallback = hostOf(url) ?: url
        val storedName = (identityDefaults.string(IDENTITY_DISPLAY_NAME_KEY) ?: "").trim()
        val storedInitials = identityDefaults.string(IDENTITY_INITIALS_KEY) ?: ""
        val storedColor = identityDefaults.string(HEADER_LOGO_COLOR_STORAGE_KEY) ?: ""

        val displayName = if (storedName.isEmpty()) hostFallback else storedName
        val initials = displayInitials(
            displayName = displayName,
            storedInitials = storedInitials,
            fallbackFullName = hostFallback,
        )
        val colorHex = normalizedHex(storedColor) ?: DEFAULT_HEADER_LOGO_COLOR_HEX

        val timestamp = now()
        return ServerAccount(
            id = id,
            urlString = id,
            displayName = displayName,
            initials = initials,
            headerLogoColorHex = colorHex,
            customHeadersRef = id,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
    }

    /**
     * Writes [account]'s per-server identity into the global identity defaults
     * (`sessionIdentity.*` / `headerLogoColorHex`). The active server's stored
     * identity stays the source of truth; this keeps the global mirror — which
     * the avatar, header logo color, and New Chat/Send tint read — in step
     * whenever the active server changes or its identity is edited (#17).
     * Called outside the storage lock (the defaults store has its own
     * synchronization).
     */
    private fun mirrorIdentityToDefaults(account: ServerAccount) {
        identityDefaults.set(account.displayName, IDENTITY_DISPLAY_NAME_KEY)
        identityDefaults.set(account.initials, IDENTITY_INITIALS_KEY)
        identityDefaults.set(account.headerLogoColorHex, HEADER_LOGO_COLOR_STORAGE_KEY)
    }

    // MARK: - Persistence

    /** Writes the snapshot through to the store. Called under [lock]. */
    private fun persist(snapshot: Snapshot) {
        val blob = runCatching { json.encodeToString(SnapshotSerializer, snapshot) }.getOrNull()
            ?: return
        runCatching { keychain.save(blob, SERVERS_STORAGE_KEY) }
    }

    companion object {
        /** `KeychainStore.Key.servers`. */
        private const val SERVERS_STORAGE_KEY = "servers"

        private val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true }

        /** `loadSnapshot(from:)` — any load/decode failure yields an empty snapshot. */
        private fun loadSnapshot(keychain: Keychain, json: Json): Snapshot {
            val blob = runCatching { keychain.load(SERVERS_STORAGE_KEY) }.getOrNull() ?: return Snapshot()
            return runCatching { json.decodeFromString(SnapshotSerializer, blob) }.getOrNull() ?: Snapshot()
        }
    }
}

// MARK: - Inlined identity helpers
// `SessionIdentitySettings` (`Features/SessionList/SessionRowView.swift`) and
// `HeaderLogoColor` (`Config/AppTheme.swift`) are UI-layer types outside the
// model port; only these pure functions/semantics are needed for seeding.

/** `SessionIdentitySettings.normalizedInitials`. */
private fun normalizedInitials(rawValue: String): String =
    rawValue
        .filter { it.isLetterOrDigit() }
        .take(3)
        .map { it.uppercase() }
        .joinToString("")

/** `SessionIdentitySettings.displayInitials`. */
private fun displayInitials(
    displayName: String,
    storedInitials: String,
    fallbackFullName: String,
): String {
    val normalizedStoredInitials = normalizedInitials(storedInitials)
    if (normalizedStoredInitials.isNotEmpty()) {
        return normalizedStoredInitials
    }

    val displayNameInitials = initialsFrom(displayName)
    if (displayNameInitials.isNotEmpty()) {
        return displayNameInitials
    }

    val fallbackInitials = initialsFrom(fallbackFullName)
    return if (fallbackInitials.isEmpty()) "UZ" else fallbackInitials
}

/** `SessionIdentitySettings.initials(from:)` — first letter of each word, max 2. */
private fun initialsFrom(rawName: String): String =
    INITIALS_SEPARATOR_REGEX
        .split(rawName)
        .mapNotNull { it.firstOrNull() }
        .take(2)
        .map { it.uppercase() }
        .joinToString("")

/**
 * Splits on whitespace, "-", "_" like `split(whereSeparator:)` (Unicode-aware).
 * `\p{Z}` covers Unicode separators without the JVM-only `(?U)` flag, which
 * Android's ICU regex engine rejects at class-init time.
 */
private val INITIALS_SEPARATOR_REGEX = Regex("[\\s\\p{Z}\\-_]+")

/** `HeaderLogoColor.normalizedHex` — `"#RRGGBB"` or null when malformed. */
private fun normalizedHex(rawValue: String): String? {
    var hex = rawValue.trim()
    if (hex.startsWith("#")) {
        hex = hex.substring(1)
    }

    if (hex.length != 6 || !hex.all { it.isHexDigit }) {
        return null
    }

    return "#${hex.uppercase()}"
}

private val Char.isHexDigit: Boolean
    get() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Swift `url.host ?? url.absoluteString` — null host (or unparsable) falls back. */
private fun hostOf(url: String): String? =
    runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }
