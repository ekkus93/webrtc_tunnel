package com.phillipchin.webrtctunnel.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * FIX8 P0-007-C: [IdentityRepository.readStoredIdentityMaterial] must never observe the
 * encrypted-identity and public-identity files as a mismatched pair mid-[IdentityRepository
 * .storeEncryptedIdentity] replacement, and must not hold the storage lock any longer than the
 * coherent read itself takes — a slow caller-side step (e.g. native validation) after the read
 * must never block a concurrent identity replacement.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityCoherenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val encFile = File(context.filesDir, "identity.enc")
    private val pubFile = File(context.filesDir, "identity.pub")

    @Before
    fun setUp() {
        encFile.delete()
        pubFile.delete()
    }

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
    fun coherentIdentityReadNeverObservesMismatchedPairDuringReplacement() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        lateinit var repo: IdentityRepository
        val observedPublic = AtomicReference<String?>(null)
        val readerThreadRef = AtomicReference<Thread?>(null)
        repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                plainReplace(dest, bytes)
                if (dest.name == "identity.enc" && readerThreadRef.get() == null) {
                    // At this exact moment identity.enc already holds the NEW bytes while
                    // identity.pub still holds the OLD bytes — storeEncryptedIdentity still
                    // holds storageLock at this point (this callback runs inside it). A reader
                    // started here must block until the whole replacement finishes.
                    val reader =
                        Thread {
                            val material = repo.readStoredIdentityMaterial
                            observedPublic.set(material?.publicIdentity)
                        }
                    readerThreadRef.set(reader)
                    reader.start()
                }
            }

        repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub")
        readerThreadRef.get()?.join(5_000)

        assertEquals(
            "a coherent read must never observe the encrypted file's new value paired with the " +
                "public file's stale value",
            "new-pub",
            observedPublic.get(),
        )
    }

    @Test
    fun identityReaderDoesNotHoldLockDuringNativeValidation() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val repo = IdentityRepository(context, PlaintextCrypto())

        // The coherent read itself.
        val material = checkNotNull(repo.readStoredIdentityMaterial)
        repo.decryptPrivateIdentity(material)

        // Simulate a slow "native validation" step happening entirely AFTER the coherent read
        // returned. If readStoredIdentityMaterial held storageLock past its own return, this
        // concurrent replacement (started only now, well after the read) would be blocked for
        // however long the "validation" below takes.
        val concurrentWriteCompleted = CountDownLatch(1)
        Thread {
            repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub")
            concurrentWriteCompleted.countDown()
        }.start()

        assertTrue(
            "a concurrent identity replacement must not be blocked by a read that has already " +
                "returned — the storage lock must be released before the caller's own " +
                "(slow, native) validation step runs",
            concurrentWriteCompleted.await(2, TimeUnit.SECONDS),
        )
    }
}
