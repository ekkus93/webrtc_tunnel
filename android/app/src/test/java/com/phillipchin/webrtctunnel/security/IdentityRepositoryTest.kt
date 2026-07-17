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
        File(context.filesDir, "authorized_keys").delete()
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
    fun readPrivateIdentityPlaintextRoundTrips() {
        val payload = ByteArray(128) { index -> (index % 255).toByte() }
        repository.storeEncryptedIdentity(payload, "pub")
        assertArrayEquals(payload, repository.readPrivateIdentityPlaintext())
    }

    @Test(expected = Exception::class)
    fun corruptedCiphertextFailsToDecrypt() {
        repository.storeEncryptedIdentity("private".toByteArray(), "pub")
        File(context.filesDir, "identity.enc").writeBytes(byteArrayOf(1, 2, 3, 4))
        repository.readPrivateIdentityPlaintext()
    }

    @Test
    fun exportPrivateIdentityRequiresExplicitConfirmation() {
        repository.storeEncryptedIdentity("private-data".toByteArray(), "pub")
        val outFile = File(context.filesDir, "private-export.toml")
        outFile.delete()
        val denied = repository.exportPrivateIdentity(outFile.absolutePath, confirmRisk = false)
        assertTrue(denied.isFailure)
        assertFalse(outFile.exists())
        val allowed = repository.exportPrivateIdentity(outFile.absolutePath, confirmRisk = true)
        assertTrue(allowed.isSuccess)
        assertEquals("private-data", outFile.readText())
    }

    @Test
    fun usePrivateIdentityPlaintextWipesBufferAfterUse() {
        repository.storeEncryptedIdentity("secret-bytes".toByteArray(), "pub")
        var captured: ByteArray? = null
        repository.usePrivateIdentityPlaintext { bytes ->
            captured = bytes
            assertTrue("plaintext should be present during use", bytes.any { it.toInt() != 0 })
        }
        val buffer = requireNotNull(captured)
        assertTrue("buffer must be zeroed after use", buffer.all { it.toInt() == 0 })
    }

    @Test
    fun usePrivateIdentityPlaintextWipesBufferEvenWhenBlockThrows() {
        repository.storeEncryptedIdentity("secret-bytes".toByteArray(), "pub")
        var captured: ByteArray? = null
        runCatching {
            repository.usePrivateIdentityPlaintext { bytes ->
                captured = bytes
                error("boom")
            }
        }
        val buffer = requireNotNull(captured)
        assertTrue("buffer must be zeroed even when the block throws", buffer.all { it.toInt() == 0 })
    }

    @Test
    fun appendAuthorizedPublicIdentityDeduplicates() {
        val line = "kid1 peer1"
        assertTrue(repository.appendAuthorizedPublicIdentity(line).isSuccess)
        assertTrue(repository.appendAuthorizedPublicIdentity(line).isSuccess)
        val file = File(context.filesDir, "authorized_keys")
        assertEquals(listOf(line), file.readLines())
    }

    // FIX6 P0-003 / INV-011: snapshot/restore of the identity-storage triplet, so a failed
    // setup transaction can put every file back exactly as it was.

    @Test
    fun restoreStorageSnapshotRevertsIdentityPairMutation() {
        repository.storeEncryptedIdentity("old-private".toByteArray(), "old-public")
        repository.appendAuthorizedPublicIdentity("old-key peerA")
        val snapshot = repository.captureStorageSnapshot()

        repository.storeEncryptedIdentity("new-private".toByteArray(), "new-public")
        repository.appendAuthorizedPublicIdentity("new-key peerB")

        repository.restoreStorageSnapshot(snapshot)

        assertArrayEquals("old-private".toByteArray(), repository.readPrivateIdentityPlaintext())
        assertEquals("old-public", repository.readPublicIdentity())
        assertEquals(listOf("old-key peerA"), File(context.filesDir, "authorized_keys").readLines())
    }

    @Test
    fun restoreStorageSnapshotRecreatesAbsentFiles() {
        // No identity stored: the snapshot records absence and restore must delete anything
        // created in between, not leave a stale file.
        val snapshot = repository.captureStorageSnapshot()
        assertFalse(snapshot.encryptedIdentity.existed)

        repository.storeEncryptedIdentity("created-later".toByteArray(), "pub")
        repository.appendAuthorizedPublicIdentity("created-key peer")

        repository.restoreStorageSnapshot(snapshot)

        assertFalse("identity.enc must be absent again", repository.hasEncryptedIdentity())
        assertEquals("", repository.readPublicIdentity())
        assertFalse("authorized_keys must be absent again", File(context.filesDir, "authorized_keys").exists())
    }

    @Test
    fun snapshotOfEncryptedIdentityCapturesCiphertextNotPlaintext() {
        repository.storeEncryptedIdentity("secret-private".toByteArray(), "pub")
        val snapshot = repository.captureStorageSnapshot()

        // The captured bytes are the on-disk ciphertext, never the plaintext.
        assertFalse(
            snapshot.encryptedIdentity.bytes!!.toString(Charsets.ISO_8859_1).contains("secret-private"),
        )
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
