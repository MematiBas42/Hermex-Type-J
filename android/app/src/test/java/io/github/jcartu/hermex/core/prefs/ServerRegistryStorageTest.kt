package io.github.jcartu.hermex.core.prefs

import android.content.SharedPreferences
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerRegistryStorageTest {
    @Test
    fun `failed synchronous commit is reported`() {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.putString("servers", "encrypted-value") } returns editor
        every { editor.commit() } returns false
        val keychain = EncryptedPreferencesKeychain(
            preferences = preferences,
            cipher = object : StringCipher {
                override fun encrypt(plainText: String): String = "encrypted-$plainText"
                override fun decrypt(cipherText: String): String = cipherText.removePrefix("encrypted-")
            },
        )

        assertThrows(IOException::class.java) {
            keychain.save("value", "servers")
        }
    }
}
