package com.phillipchin.webrtctunnel.security

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryCoherentReadTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun readPublicIdentityCannotObserveHalfWrittenPair() {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                val blockReplacement = AtomicBoolean(false)
                val replacementCount = AtomicInteger(0)
                val publicReplaceEntered = CompletableDeferred<Unit>()
                val releasePublicReplace = CompletableDeferred<Unit>()
                val repository =
                    IdentityRepository(
                        context,
                        crypto = PassThroughIdentityCrypto,
                        atomicReplace = { file, bytes ->
                            file.parentFile?.mkdirs()
                            if (blockReplacement.get() && replacementCount.incrementAndGet() == 2) {
                                publicReplaceEntered.complete(Unit)
                                runBlocking { releasePublicReplace.await() }
                            }
                            file.writeBytes(bytes)
                        },
                    )
                repository.storeEncryptedIdentity("old-private".toByteArray(), "old-public")
                replacementCount.set(0)
                blockReplacement.set(true)

                val writer =
                    launch(dispatcher) {
                        repository.storeEncryptedIdentity("new-private".toByteArray(), "new-public")
                    }
                publicReplaceEntered.await()
                val readerStarted = CompletableDeferred<Unit>()
                val reader =
                    async(dispatcher) {
                        readerStarted.complete(Unit)
                        repository.readPublicIdentity()
                    }
                readerStarted.await()
                yield()

                assertFalse(
                    "coherent read must wait while pair replacement holds the lock",
                    reader.isCompleted,
                )
                releasePublicReplace.complete(Unit)
                writer.join()
                assertEquals("new-public", reader.await())
            }
        }
    }

    private object PassThroughIdentityCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }
}
