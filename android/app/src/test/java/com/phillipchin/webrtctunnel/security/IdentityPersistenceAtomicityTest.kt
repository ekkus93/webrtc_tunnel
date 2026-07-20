package com.phillipchin.webrtctunnel.security

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
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
    fun cancellationDuringPublicIdentityReplaceRestoresPriorEncryptedAndPublicPair() {
        // FIX7 P0-006-B: a CancellationException during the public-identity replace (after the
        // encrypted file already committed) must still restore BOTH prior files before
        // propagating, exactly like an ordinary IOException already does.
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                if (dest.name == "identity.pub") throw CancellationException("cancelled during public write")
                plainReplace(dest, bytes)
            }

        var caught: CancellationException? = null
        try {
            repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub")
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue("cancellation during public identity replace must propagate", caught != null)
        assertArrayEquals(
            "encrypted file committed before the cancelled public write must be rolled back",
            "old-priv".toByteArray(),
            repo.readPrivateIdentityPlaintext(),
        )
        assertEquals("old-pub", repo.readPublicIdentity())
    }

    @Test
    fun cancellationRollbackFailureIsSuppressedAndCancellationPropagates() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val encCalls = AtomicInteger(0)
        val pubCalls = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                when (dest.name) {
                    // 1st pub write (forward) is cancelled; 2nd (rollback restore) succeeds.
                    "identity.pub" ->
                        if (pubCalls.getAndIncrement() == 0) {
                            throw CancellationException("cancelled during public write")
                        } else {
                            plainReplace(dest, bytes)
                        }
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

        var caught: CancellationException? = null
        try {
            repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub")
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue("cancellation must propagate even though its own rollback partly failed", caught != null)
        assertTrue(
            "the failed encrypted-file rollback must be attached as suppressed, not silently lost",
            caught!!.suppressedExceptions.isNotEmpty(),
        )
    }

    @Test
    fun encryptedRestoreFailureDoesNotSkipPublicRestore() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val encCalls = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                when (dest.name) {
                    "identity.pub" -> throw IOException("pub boom") // forward write fails
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

        val error = runCatching { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }.exceptionOrNull()

        assertTrue(error is IdentityRollbackIncompleteException)
        assertEquals(
            "public file must still be restored even though the encrypted file's restore failed",
            "old-pub",
            repo.readPublicIdentity(),
        )
    }

    @Test
    fun publicRestoreFailureDoesNotEraseEncryptedRestoreResult() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val pubCalls = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                when (dest.name) {
                    // 1st pub write (forward) fails, triggering rollback; 2nd (rollback restore)
                    // also fails.
                    "identity.pub" -> throw IOException("pub boom on call ${pubCalls.getAndIncrement()}")
                    else -> plainReplace(dest, bytes)
                }
            }

        val error = runCatching { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }.exceptionOrNull()

        assertTrue(error is IdentityRollbackIncompleteException)
        assertArrayEquals(
            "encrypted file's restore must still succeed even though the public file's restore also failed",
            "old-priv".toByteArray(),
            repo.readPrivateIdentityPlaintext(),
        )
    }

    @Test
    fun identityRollbackIncompleteExceptionContainsEveryRollbackFailure() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val encCalls = AtomicInteger(0)
        val pubCalls = AtomicInteger(0)
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                when (dest.name) {
                    "identity.pub" -> throw IOException("pub boom on call ${pubCalls.getAndIncrement()}")
                    "identity.enc" ->
                        if (encCalls.getAndIncrement() >= 1) {
                            throw IOException("enc rollback boom")
                        } else {
                            plainReplace(dest, bytes)
                        }
                    else -> plainReplace(dest, bytes)
                }
            }

        val error = runCatching { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }.exceptionOrNull()

        assertTrue(error is IdentityRollbackIncompleteException)
        assertEquals(
            "both the encrypted and public restore failures must be attached as suppressed",
            2,
            error!!.suppressedExceptions.size,
        )
    }

    @Test
    fun restoreStorageSnapshotAttemptsAllThreeFilesAfterFirstFailure() {
        encFile.writeBytes("priv-a".toByteArray())
        pubFile.writeText("pub-a")
        authorizedKeysFile.writeText("key-a")
        val plainRepo = IdentityRepository(context, PlaintextCrypto())
        val snapshot = plainRepo.captureStorageSnapshot()

        // Mutate all three away from the snapshot.
        encFile.writeBytes("priv-b".toByteArray())
        pubFile.writeText("pub-b")
        authorizedKeysFile.writeText("key-b")

        val failingRepo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                if (dest.name == "identity.enc") throw IOException("enc restore boom") else plainReplace(dest, bytes)
            }

        val results = failingRepo.restoreStorageSnapshot(snapshot)

        assertEquals(3, results.size)
        assertTrue(
            "encrypted identity restore must be reported as a Failure",
            results.any { it is IdentityRestoreResult.Failure && it.file == IdentityStorageFile.EncryptedIdentity },
        )
        assertTrue(
            "public identity restore must still be attempted and succeed",
            results.any { it is IdentityRestoreResult.Success && it.file == IdentityStorageFile.PublicIdentity },
        )
        assertTrue(
            "authorized_keys restore must still be attempted and succeed",
            results.any { it is IdentityRestoreResult.Success && it.file == IdentityStorageFile.AuthorizedKeys },
        )
        assertEquals("pub-a", pubFile.readText())
        assertEquals("key-a", authorizedKeysFile.readText())
    }

    @Test
    fun restoreStorageSnapshotDeletesFilesThatWerePreviouslyAbsent() {
        val repo = IdentityRepository(context, PlaintextCrypto())
        val snapshot = repo.captureStorageSnapshot() // nothing exists yet

        encFile.writeBytes("created".toByteArray())
        pubFile.writeText("created")
        authorizedKeysFile.writeText("created")

        val results = repo.restoreStorageSnapshot(snapshot)

        assertTrue("every file must restore (delete) successfully", results.all { it is IdentityRestoreResult.Success })
        assertFalse("identity.enc must be absent again", encFile.exists())
        assertFalse("identity.pub must be absent again", pubFile.exists())
        assertFalse("authorized_keys must be absent again", authorizedKeysFile.exists())
    }

    @Test
    fun failedDeleteIsReturnedAsRestoreFailure() {
        val repo = IdentityRepository(context, PlaintextCrypto())
        val snapshot = repo.captureStorageSnapshot() // nothing exists -> snapshot records absence

        // Make authorized_keys undeletable via Files.deleteIfExists: a non-empty directory in
        // its place, rather than a filesystem permission trick.
        authorizedKeysFile.mkdirs()
        File(authorizedKeysFile, "child").writeText("blocks deletion")
        try {
            val results = repo.restoreStorageSnapshot(snapshot)

            val authorizedKeysResult = results.single { it.stageFile() == IdentityStorageFile.AuthorizedKeys }
            assertTrue(
                "an undeletable file must be reported as a restore Failure, not silently ignored",
                authorizedKeysResult is IdentityRestoreResult.Failure,
            )
        } finally {
            authorizedKeysFile.deleteRecursively()
        }
    }

    @Test
    fun concurrentSnapshotAndAuthorizedKeyAppendAreSerialized() {
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                // Sleep BEFORE writing (not after): a snapshot that isn't serialized against this
                // write could observe the pre-write state during the window; storageLock must
                // force it to wait for the whole append (sleep included) to finish first.
                if (dest.name == "authorized_keys") Thread.sleep(SERIALIZATION_WINDOW_MS)
                plainReplace(dest, bytes)
            }

        val snapshots = java.util.Collections.synchronizedList(mutableListOf<StoredFileSnapshot>())
        val appendThread = Thread { repo.appendAuthorizedPublicIdentity("key-a peer=a").getOrThrow() }
        val snapshotThread =
            Thread {
                Thread.sleep(SERIALIZATION_LEAD_IN_MS)
                snapshots.add(repo.captureStorageSnapshot().authorizedKeys)
            }
        appendThread.start()
        snapshotThread.start()
        appendThread.join()
        snapshotThread.join()

        val captured = snapshots.single()
        assertTrue("snapshot must observe the fully-appended state, not an in-progress one", captured.existed)
        assertEquals("key-a peer=a", captured.bytes?.decodeToString())
    }

    @Test
    fun concurrentSnapshotAndIdentityCommitAreSerialized() {
        encFile.writeBytes("old-priv".toByteArray())
        pubFile.writeText("old-pub")
        val repo =
            IdentityRepository(context, PlaintextCrypto()) { dest, bytes ->
                plainReplace(dest, bytes)
                // Sleep AFTER writing the encrypted file, still inside the lock, before the
                // public file is written — the window a non-serialized snapshot could exploit
                // to observe a mismatched pair (new encrypted, old public).
                if (dest.name == "identity.enc") Thread.sleep(SERIALIZATION_WINDOW_MS)
            }

        val snapshots = java.util.Collections.synchronizedList(mutableListOf<IdentityStorageSnapshot>())
        val commitThread = Thread { repo.storeEncryptedIdentity("new-priv".toByteArray(), "new-pub") }
        val snapshotThread =
            Thread {
                Thread.sleep(SERIALIZATION_LEAD_IN_MS)
                snapshots.add(repo.captureStorageSnapshot())
            }
        commitThread.start()
        snapshotThread.start()
        commitThread.join()
        snapshotThread.join()

        val snapshot = snapshots.single()
        val encryptedIsNew = snapshot.encryptedIdentity.bytes?.decodeToString() == "new-priv"
        val publicIsNew = snapshot.publicIdentity.bytes?.decodeToString() == "new-pub"
        assertEquals(
            "the snapshot must never observe a mismatched pair (one file new, the other old)",
            encryptedIsNew,
            publicIsNew,
        )
    }

    @Test
    fun plaintextIdentityNeverReachesDiskOnAnyFailurePath() {
        // A crypto that transforms the bytes, so plaintext appearing anywhere would be detectable.
        val plaintext = "SUPER-SECRET-PLAINTEXT-FAILURE-PATH".toByteArray()
        val transformingCrypto =
            object : IdentityCrypto {
                override fun encrypt(plaintext: ByteArray): ByteArray =
                    plaintext.map { (it + 1).toByte() }.toByteArray()

                override fun decrypt(payload: ByteArray): ByteArray = payload.map { (it - 1).toByte() }.toByteArray()
            }
        val repo =
            IdentityRepository(context, transformingCrypto) { dest, bytes ->
                if (dest.name == "identity.pub") throw IOException("pub boom") else plainReplace(dest, bytes)
            }

        runCatching { repo.storeEncryptedIdentity(plaintext, "pub") }

        context.filesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse(
                "plaintext must never appear on disk in ${file.name}, even on a failure path",
                containsSubsequence(file.readBytes(), plaintext),
            )
        }
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

    private fun IdentityRestoreResult.stageFile(): IdentityStorageFile =
        when (this) {
            is IdentityRestoreResult.Success -> file
            is IdentityRestoreResult.Failure -> file
        }

    private companion object {
        const val SERIALIZATION_WINDOW_MS = 40L
        const val SERIALIZATION_LEAD_IN_MS = 10L
    }
}

private fun containsSubsequence(
    haystack: ByteArray,
    needle: ByteArray,
): Boolean =
    needle.isNotEmpty() &&
        haystack.size >= needle.size &&
        (0..haystack.size - needle.size).any { offset -> needle.indices.all { j -> haystack[offset + j] == needle[j] } }
