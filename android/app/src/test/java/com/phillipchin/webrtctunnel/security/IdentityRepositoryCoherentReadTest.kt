package com.phillipchin.webrtctunnel.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val CONCURRENCY_WAIT_SECONDS = 5L
private const val LOCK_OBSERVATION_MILLIS = 200L

@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryCoherentReadTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun readPublicIdentityCannotObserveHalfWrittenPair() {
        val barrier = PairReplacementBarrier()
        val repository =
            IdentityRepository(
                context,
                crypto = PassThroughIdentityCrypto,
                atomicReplace = barrier::replace,
            )
        repository.storeEncryptedIdentity("old-private".toByteArray(), "old-public")
        barrier.arm()

        val writerFailure = AtomicReference<Throwable?>()
        val writerDone = CountDownLatch(1)
        daemonThread("identity-pair-writer") {
            try {
                repository.storeEncryptedIdentity("new-private".toByteArray(), "new-public")
            } catch (failure: Throwable) {
                writerFailure.set(failure)
            } finally {
                writerDone.countDown()
            }
        }
        assertTrue(barrier.publicReplaceEntered.awaitDeadline())

        val readResult = AtomicReference<String?>()
        val readerFailure = AtomicReference<Throwable?>()
        val readerStarted = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        daemonThread("identity-pair-reader") {
            readerStarted.countDown()
            try {
                readResult.set(repository.readPublicIdentity())
            } catch (failure: Throwable) {
                readerFailure.set(failure)
            } finally {
                readerDone.countDown()
            }
        }
        assertTrue(readerStarted.awaitDeadline())
        assertFalse(
            "coherent read must wait while pair replacement holds the lock",
            readerDone.await(LOCK_OBSERVATION_MILLIS, TimeUnit.MILLISECONDS),
        )

        barrier.releasePublicReplace.countDown()
        assertTrue(writerDone.awaitDeadline())
        assertNull(writerFailure.get())
        assertTrue(readerDone.awaitDeadline())
        assertNull(readerFailure.get())
        assertEquals("new-public", readResult.get())
    }

    private object PassThroughIdentityCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }
}

private class PairReplacementBarrier {
    private val blocking = AtomicBoolean(false)
    private val replacementCount = AtomicInteger(0)
    val publicReplaceEntered = CountDownLatch(1)
    val releasePublicReplace = CountDownLatch(1)

    fun arm() {
        replacementCount.set(0)
        blocking.set(true)
    }

    fun replace(
        file: File,
        bytes: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        if (blocking.get() && replacementCount.incrementAndGet() == 2) {
            publicReplaceEntered.countDown()
            check(releasePublicReplace.awaitDeadline()) {
                "timed out waiting to release public identity replacement"
            }
        }
        file.writeBytes(bytes)
    }
}

private fun CountDownLatch.awaitDeadline(): Boolean = await(CONCURRENCY_WAIT_SECONDS, TimeUnit.SECONDS)

private fun daemonThread(
    name: String,
    block: () -> Unit,
): Thread = thread(start = true, isDaemon = true, name = name, block = block)
