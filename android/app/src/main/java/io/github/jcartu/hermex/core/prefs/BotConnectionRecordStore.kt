package io.github.jcartu.hermex.core.prefs

import android.content.SharedPreferences
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.net.hermes.HermesConnections
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The saved Hermes sign-in records as `AuthManager` and the connection forms use them: the
 * subset of Swift's `BotConnectionStore` (`Features/Bots/BotConnection.swift`) that reads,
 * writes and deletes one record per configured server. [HermesConnections.BotConnectionStore]
 * is the narrower view the connection registry rotates refresh tokens through.
 */
interface BotConnectionRecords {
    /** The record saved for [server] (a `ServerAccount.id`), or null when it has none. Throws when it can't be read. */
    @Throws(Exception::class)
    fun load(server: String): BotConnection?

    /**
     * Writes [connection] as [server]'s record, the commit point of a sign-in, and retires the
     * server's shared connection unless it is still this record (a rename or an install id
     * backfill keeps it). Throws when the write fails.
     */
    @Throws(Exception::class)
    fun save(connection: BotConnection, server: String)

    /** Deletes [server]'s record and retires its shared connection. */
    @Throws(Exception::class)
    fun remove(server: String)
}

/**
 * Port of `BotConnectionStore` (`Features/Bots/BotConnection.swift`): one sign-in record per
 * configured server — a Hermes server's own, or a webui server's optional Bots one — as the
 * same camelCase JSON the iOS Keychain holds ([BotConnection]'s serializer), under
 * `bot_connection::<server id>`, where the id is the server's URL. The value is encrypted
 * under the Android Keystore and kept in a private [SharedPreferences] file, the synchronous
 * store a sign-in's commit point needs: every write goes through `commit()`, so the record is
 * on disk before the caller reports the sign-in saved.
 *
 * [save] and [remove] retire the server's shared `HermesConnection` through [retire]
 * (`HermesConnections.retire(server:unlessStill:)`), so a sign-in finishing afterwards stores
 * nothing and resends nothing; [saveRotatedRefreshToken] never does, since the live connection
 * made that change itself.
 */
class BotConnectionRecordStore(
    private val preferences: SharedPreferences,
    private val cipher: StringCipher,
    private val json: Json,
    private val retire: (server: String, unlessStill: BotConnection?) -> Unit = { _, _ -> },
) : BotConnectionRecords, HermesConnections.BotConnectionStore {

    override fun load(server: String): BotConnection? = synchronized(preferences) {
        val stored = preferences.getString(key(server), null) ?: return@synchronized null
        json.decodeFromString(BotConnection.serializer(), cipher.decrypt(stored))
    }

    override fun save(connection: BotConnection, server: String) {
        synchronized(preferences) { write(connection, server) }
        retire(server, connection)
    }

    /**
     * Writes the refresh token the host just rotated into, only while [server]'s record is
     * still connection [id] holding [sent], the token that connection last saw saved: a record
     * removed or signed in again meanwhile keeps what it has.
     */
    override fun saveRotatedRefreshToken(rotated: String, sent: String, id: String, server: String) {
        synchronized(preferences) {
            val saved = load(server) ?: return
            val account = saved.oauth ?: return
            if (saved.id != id || account.refreshToken != sent) return
            write(saved.copy(oauth = account.copy(refreshToken = rotated)), server)
        }
    }

    override fun remove(server: String) {
        synchronized(preferences) {
            if (!preferences.edit().remove(key(server)).commit()) throw IOException("The sign-in record could not be removed.")
        }
        retire(server, null)
    }

    private fun write(connection: BotConnection, server: String) {
        val encoded = json.encodeToString(BotConnection.serializer(), connection)
        if (!preferences.edit().putString(key(server), cipher.encrypt(encoded)).commit()) {
            throw IOException("The sign-in record could not be written.")
        }
    }

    companion object {
        /** The private preferences file; the registry blob stays in its own. */
        const val PREFERENCES_NAME = "hermex_bot_connections"

        /** `KeychainKey.botConnection` scoped by the server: `bot_connection::<server id>`. */
        fun key(server: String): String = "bot_connection::$server"
    }
}
