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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * FIX6 P1-004: identity-pair and authorized-key persistence must be atomic and
 * concurrency-safe — the pair commits together or rolls back, rollback failure is visible,
 * concurrent appends never lose data, and no plaintext key reaches disk.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityPersistenceAtomicityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val encFile = File(context.filesDir, "identity.enc")
    private val pubFile = File(context.filesDir, "identity.pub")
    private val authorizedKeysFile = File(context.filesDir, "authorized_keys")

    @Before
    fun setUp() {
        encFile.delete()
        pubFile.delete()
        authorizedKeysFile.delete()
    }

    /** Stores plaintext verbatim so on-disk bytes are readable in assertions. */
    private class PlaintextCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }

    private fun plainReplace(
        dest: File,
        bytes: ByteArray,
    ) {
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
    }

    @Test
    fun newIdentityPairCommitsTogether() {
        val repo = IdentityRepository(context, PlaintextCrypto())

        repo.storeEncryptedIdentity("priv".toByteArray(), "pub")

        assertTrue(encFile.exists())
        assertTrue(pubFile.exists())
        assertArrayEquals("priv".toByteArray(), repo.readPrivateIdentityPlaintext())
        assertEquals("pub", repo.readPublicIdentity())
    }

    @Test
    fun privateIdentityWriteFailureLeavesOldPairUntouched() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                if (dest.name == "identity.enc") throw IOException("enc boom") else plainReplace(dest, bytes)
            }

        runCatching { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }

        assertArrayEquals("old-priv".toByteArray(), repo.readPrivateIdentityPlaintext())
        assertEquals("old-pub", repo.readPublicIdentity())
    }

    @Test
    fun publicIdentityWriteFailureRestoresPreviousEncryptedAndPublicPair() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val pubCalls = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                if (dest.name == "identity.pub" && pubCalls.getAndIncrement() == 0) {
                    throw IOException("pub boom") // fail only the forward write; rollback restore succeeds
                }
                plainReplace(dest, bytes)
            }

        val error =
            runCatching { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }.exceptionOrNull()

        assertTrue("clean rollback must report a plain persistence failure", error is IdentityPersistenceException)
        assertArrayEquals(
            "encrypted file must be rolled back",
            "old-priv".toByteArray(),
            repo.readPrivateIdentityPlaintext(),
        )
        assertEquals("public file must be rolled back", "old-pub", repo.readPublicIdentity())
    }

    @Test
    fun identityRollbackFailureIsVisible() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val encCalls = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                when (dest.name) {
                    "identity.pub" -> throw IOException("pub boom")
                    // 1st enc write (forward) succeeds; 2nd (rollback restore) fails.
                    "identity.enc" ->
                        if (encCalls.getAndIncrement() >= 1) {
                            throw IOException("enc rollback boom")
                        } else {
                            plainReplace(dest, bytes)
                        }
                    else -> plainReplace(dest, bytes)
                }
            }

        val error =
            runCatching { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }.exceptionOrNull()

        assertTrue(
            "an incomplete rollback must be visibly distinct",
            error is IdentityRollbackIncompleteException,
        )
    }

    @Test
    fun plaintextIdentityIsNotWrittenToDisk() {
        // A crypto that transforms the bytes, so plaintext appearing on disk would be detectable.
        val repo =
            IdentityRepository(
                context,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray =
                        plaintext.map { (it + 1).toByte() }.toByteArray()

                    override fun decrypt(payload: ByteArray): ByteArray =
                        payload.map { (it - 1).toByte() }.toByteArray()
                },
            )
        val plaintext = "SUPER-SECRET-PLAINTEXT".toByteArray()

        repo.storeEncryptedIdentity(plaintext, "pub")

        assertFalse("identity.enc must hold ciphertext, not plaintext", encFile.readBytes().contentEquals(plaintext))
        val leftoverTemp = context.filesDir.listFiles()?.filter { it.name.endsWith(".partial") }.orEmpty()
        assertTrue("no partial temp file may be left behind", leftoverTemp.isEmpty())
    }

    @Test
    fun concurrentAuthorizedKeyAppendsPreserveBothKeys() {
        val repo = IdentityRepository(context, PlaintextCrypto())
        val threads =
            listOf("ssh-a peer=a", "ssh-b peer=b").map { key ->
                Thread { repo.appendAuthorizedPublicIdentity(key).getOrThrow() }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val lines = authorizedKeysFile.readLines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("ssh-a peer=a", "ssh-b peer=b"), lines)
    }

    @Test
    fun duplicateAuthorizedKeyDoesNotRewriteOrDuplicate() {
        val writes = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                if (dest.name == "authorized_keys") writes.incrementAndGet()
                plainReplace(dest, bytes)
            }

        repo.appendAuthorizedPublicIdentity("ssh-a peer=a").getOrThrow()
        repo.appendAuthorizedPublicIdentity("ssh-a peer=a").getOrThrow()

        assertEquals("a duplicate append must not rewrite the file", 1, writes.get())
        assertEquals(listOf("ssh-a peer=a"), authorizedKeysFile.readLines().filter { it.isNotBlank() })
    }

    @Test
    fun authorizedKeyWriteFailureLeavesOldFileIntact() {
        authorizedKeysFile.writeText("ssh-old peer=old")
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                if (dest.name == "authorized_keys") throw IOException("authorized boom") else plainReplace(dest, bytes)
            }

        val result = repo.appendAuthorizedPublicIdentity("ssh-new peer=new")

        assertTrue(result.isFailure)
        assertEquals("the old authorized-keys file must be intact", "ssh-old peer=old", authorizedKeysFile.readText())
    }
}
