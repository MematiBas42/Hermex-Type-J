package io.github.jcartu.hermex.core.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.CustomHeaderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedCustomHeaderRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var runtime: CustomHeaderStore
    private lateinit var repository: EncryptedCustomHeaderRepository
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class ReversingCipher : StringCipher {
        override fun encrypt(plainText: String) = "v1:" + plainText.reversed()
        override fun decrypt(cipherText: String) = cipherText.removePrefix("v1:").reversed()
    }

    @Before fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        file = temp.newFile("headers.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        runtime = CustomHeaderStore()
        repository = EncryptedCustomHeaderRepository(dataStore, ReversingCipher(), json, runtime)
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `saved secret is encrypted and restored for matching origin`() = runTest {
        repository.load("https://one.example")
        repository.replaceFor("https://one.example/path", listOf(CustomHeader("X-Api-Key", "super-secret-token")))
        assertEquals("super-secret-token", runtime.snapshot().single().value)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("super-secret-token"))

        scope.cancel()
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val restoredRuntime = CustomHeaderStore()
        val restored = EncryptedCustomHeaderRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            ReversingCipher(),
            json,
            restoredRuntime,
        )
        restored.load("https://one.example/elsewhere")
        assertEquals("X-Api-Key", restoredRuntime.snapshot().single().name)
        assertEquals("super-secret-token", restoredRuntime.snapshot().single().value)
        assertEquals("https://one.example:443", EncryptedCustomHeaderRepository.normalizedOrigin("https://one.example/a"))
    }

    @Test fun `headers never cross to another server origin`() = runTest {
        repository.load("https://one.example")
        repository.replaceFor("https://one.example", listOf(CustomHeader("Authorization", "Bearer one")))
        repository.activate("https://two.example")
        assertTrue(runtime.snapshot().isEmpty())
        repository.replaceFor("https://two.example", listOf(CustomHeader("X-Tenant", "two")))
        repository.activate("https://one.example")
        assertEquals("Bearer one", runtime.snapshot().single().value)
    }

    @Test fun `validation rejects injection protected headers and duplicates`() {
        val problems = repository.validate(
            listOf(
                CustomHeader("Host", "evil.example"),
                CustomHeader("X-Key", "one"),
                CustomHeader("x-key", "two"),
                CustomHeader("X-Bad", "line\nbreak"),
            ),
        ).joinToString(" ")
        assertTrue(problems.contains("managed"))
        assertTrue(problems.contains("appears more than once"))
        assertTrue(problems.contains("invalid"))
    }

    @Test fun `invalid list is not persisted or activated`() = runTest {
        repository.load("https://one.example")
        assertThrows(CustomHeaderValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.replaceFor("https://one.example", listOf(CustomHeader("Cookie", "secret")))
            }
        }
        assertTrue(runtime.snapshot().isEmpty())
    }

    @Test fun `origin includes effective port and rejects non http urls`() {
        assertEquals("http://localhost:8787", EncryptedCustomHeaderRepository.normalizedOrigin("http://localhost:8787/api"))
        assertEquals(null, EncryptedCustomHeaderRepository.normalizedOrigin("not a url"))
    }
}
