package com.phillipchin.webrtctunnel.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: IdentityRepository

    @Before
    fun setUp() {
        File(context.filesDir, "identity.enc").delete()
        File(context.filesDir, "identity.pub").delete()
        repository = IdentityRepository(context, TestAesGcmCrypto())
    }

    @Test
    fun hasEncryptedIdentityReflectsFilePresence() {
        assertFalse(repository.hasEncryptedIdentity())
        repository.storeEncryptedIdentity("private".toByteArray(), "public")
        assertTrue(repository.hasEncryptedIdentity())
    }

    @Test
    fun storeWritesEncryptedPrivateAndPublicIdentity() {
        val privateIdentity = "very-secret-private".toByteArray()
        repository.storeEncryptedIdentity(privateIdentity, "public-identity")
        val encFile = File(context.filesDir, "identity.enc")
        val pubFile = File(context.filesDir, "identity.pub")
        assertTrue(encFile.exists())
        assertTrue(pubFile.exists())
        assertEquals("public-identity", pubFile.readText())
        assertFalse(encFile.readBytes().contentEquals(privateIdentity))
    }

    @Test
    fun readEncryptedIdentityRoundTrips() {
        val payload = ByteArray(128) { index -> (index % 255).toByte() }
        repository.storeEncryptedIdentity(payload, "pub")
        assertArrayEquals(payload, repository.readEncryptedIdentity())
    }

    @Test(expected = Exception::class)
    fun corruptedCiphertextFailsToDecrypt() {
        repository.storeEncryptedIdentity("private".toByteArray(), "pub")
        File(context.filesDir, "identity.enc").writeBytes(byteArrayOf(1, 2, 3, 4))
        repository.readEncryptedIdentity()
    }

    private class TestAesGcmCrypto : IdentityCrypto {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plaintext)
            return cipher.iv + ciphertext
        }

        override fun decrypt(payload: ByteArray): ByteArray {
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
