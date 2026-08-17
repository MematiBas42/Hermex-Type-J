package io.github.jcartu.hermex.core.prefs

import io.github.jcartu.hermex.core.crypto.StringCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerConfigTest {

    @Test
    fun `normalize adds https scheme when missing`() {
        assertEquals("https://hermes.example.com", ServerConfig.normalizeBaseUrl("hermes.example.com"))
    }

    @Test
    fun `normalize preserves explicit http scheme`() {
        assertEquals("http://192.168.1.10:8787", ServerConfig.normalizeBaseUrl("http://192.168.1.10:8787/"))
    }

    @Test
    fun `normalize trims whitespace and trailing slashes`() {
        assertEquals("https://hermes.example.com", ServerConfig.normalizeBaseUrl("  https://hermes.example.com/// "))
    }

    @Test
    fun `normalize keeps empty input empty`() {
        assertEquals("", ServerConfig.normalizeBaseUrl("   "))
    }

    @Test
    fun `configured requires both url and password`() {
        assertFalse(ServerConfig("", "pw").isConfigured)
        assertFalse(ServerConfig("https://x", "").isConfigured)
        assertTrue(ServerConfig("https://x", "pw").isConfigured)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConfigRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repository: ServerConfigRepository


    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val file: File = tmpFolder.newFile("prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        repository = ServerConfigRepository(dataStore, FakeCipher())
    }

    private class FakeCipher : StringCipher {
        override fun encrypt(plainText: String) = plainText.map { (it.code + 1).toChar() }.joinToString("")
        override fun decrypt(cipherText: String) = cipherText.map { (it.code - 1).toChar() }.joinToString("")
    }

    @Test
    fun `save stores url normalized and password encrypted then reads back`() = runBlocking {
        repository.save("hermes.example.com/", "hunter2")

        val config = repository.current()
        assertEquals("https://hermes.example.com", config.baseUrl)
        assertEquals("hunter2", config.password)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `clear removes everything`() = runBlocking {
        repository.save("https://hermes.example.com", "pw")
        repository.clear()

        val config = repository.current()
        assertEquals("", config.baseUrl)
        assertEquals("", config.password)
        assertFalse(config.isConfigured)
    }
}
