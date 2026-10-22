package io.github.jcartu.hermex.core.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.HermesOAuth
import io.github.jcartu.hermex.net.CustomHeader
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BotConnectionRecordStoreTest {
    private val cipher = object : StringCipher {
        override fun encrypt(plainText: String) = "test:" + Base64.getEncoder().encodeToString(plainText.toByteArray())
        override fun decrypt(cipherText: String) = String(Base64.getDecoder().decode(cipherText.removePrefix("test:")))
    }
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("records-test", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val retirements = mutableListOf<Pair<String, BotConnection?>>()
    private val store = BotConnectionRecordStore(preferences, cipher, json) { server, connection -> retirements += server to connection }
    private val server = "https://hermes.example"
    private fun record() = BotConnection("uuid-a", "Host", server.toHttpUrl(), "user", "secret", "0.22.2", "install-a",
        listOf(CustomHeader("X-Proxy", "proxy-token")), HermesOAuth("nous", "user", "refresh-a"))

    @Test fun `round trip uses encrypted Swift camelCase and missing fields decode tolerantly`() {
        preferences.edit().clear().commit()
        val record = record()
        store.save(record, server)
        assertEquals(record, store.load(server))
        val stored = preferences.getString("bot_connection::$server", null)!!
        assertFalse(stored.contains("secret"))
        val plain = cipher.decrypt(stored)
        assertTrue(plain.contains("\"installID\""))
        assertTrue(plain.contains("\"refreshToken\""))
        assertEquals(listOf(server to record), retirements)
        preferences.edit().putString(BotConnectionRecordStore.key(server), cipher.encrypt("""{"id":"old","address":"https://hermes.example","future":true}""")).commit()
        val old = store.load(server)!!
        assertEquals("old", old.id)
        assertTrue(old.hasNoCredentials)
        assertNull(old.installID)
    }

    @Test fun `rotation is compare and save without retiring and cannot recreate removed sign in`() {
        preferences.edit().clear().commit()
        store.save(record(), server)
        store.saveRotatedRefreshToken("refresh-b", "wrong", "uuid-a", server)
        assertEquals("refresh-a", store.load(server)?.oauth?.refreshToken)
        store.saveRotatedRefreshToken("refresh-b", "refresh-a", "other-id", server)
        assertEquals("refresh-a", store.load(server)?.oauth?.refreshToken)
        store.saveRotatedRefreshToken("refresh-b", "refresh-a", "uuid-a", server)
        assertEquals("refresh-b", store.load(server)?.oauth?.refreshToken)
        assertEquals(1, retirements.size)
        store.save(record().copy(id = "new-id"), server)
        store.saveRotatedRefreshToken("late-token", "refresh-a", "uuid-a", server)
        assertEquals("new-id", store.load(server)?.id)
        store.remove(server)
        store.saveRotatedRefreshToken("late-token", "refresh-a", "new-id", server)
        assertNull(store.load(server))
    }
}
