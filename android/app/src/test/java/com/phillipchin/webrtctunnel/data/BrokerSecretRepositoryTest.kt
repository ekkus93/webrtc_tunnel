package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * FIX7 P0-003-B: [BrokerSecretRepository] is the one authoritative owner of the managed broker
 * password file. Config rendering must never write it as a side effect (CRITICAL-6) — these
 * tests exercise the repository itself in isolation.
 */
@RunWith(RobolectricTestRunner::class)
class BrokerSecretRepositoryTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val passwordFile = File(app.filesDir, "runtime/mqtt_password.txt")

    @Before
    fun setUp() {
        File(app.filesDir, "runtime").deleteRecursively()
    }

    @Test
    fun brokerPasswordPersistUsesAtomicReplacement() {
        val repository = BrokerSecretRepository(app)
        val result = repository.persist("s3cret")

        assertTrue(result.isSuccess)
        assertTrue(passwordFile.exists())
        assertArrayEquals("s3cret".toByteArray(), passwordFile.readBytes())
        // No leftover partial/temp file from the unique-temp-file-plus-move pattern.
        val leftovers = passwordFile.parentFile?.listFiles { file -> file.name.contains(".tmp-") }.orEmpty()
        assertTrue("no temp files should remain after a successful persist", leftovers.isEmpty())
    }

    @Test
    fun brokerPasswordPermissionsAreOwnerOnly() {
        val repository = BrokerSecretRepository(app)
        repository.persist("s3cret").getOrThrow()

        val permissions = Files.getPosixFilePermissions(passwordFile.toPath())
        assertTrue(permissions.contains(PosixFilePermission.OWNER_READ))
        assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE))
        assertFalse(permissions.contains(PosixFilePermission.GROUP_READ))
        assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE))
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ))
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE))
    }

    @Test
    fun brokerPasswordSnapshotDistinguishesAbsentAndEmpty() {
        val repository = BrokerSecretRepository(app)

        val absentSnapshot = repository.captureSnapshot().getOrThrow()
        assertFalse(absentSnapshot.existed)
        assertNull(absentSnapshot.bytes)

        repository.persist("").getOrThrow() // persist("") is deletion (isNullOrEmpty), stays absent
        repository.persist(null).getOrThrow()
        passwordFile.parentFile?.mkdirs()
        passwordFile.writeBytes(ByteArray(0)) // present but empty, bypassing persist's own semantics
        val presentEmptySnapshot = repository.captureSnapshot().getOrThrow()
        assertTrue(presentEmptySnapshot.existed)
        assertArrayEquals(ByteArray(0), presentEmptySnapshot.bytes)
    }

    @Test
    fun brokerPasswordRestoreRecreatesExactBytes() {
        val repository = BrokerSecretRepository(app)
        repository.persist("original-secret").getOrThrow()
        val snapshot = repository.captureSnapshot().getOrThrow()

        repository.persist("mutated-secret").getOrThrow()
        val restore = repository.restore(snapshot)

        assertTrue(restore.isSuccess)
        assertArrayEquals("original-secret".toByteArray(), passwordFile.readBytes())
    }

    @Test
    fun brokerPasswordRestoreDeletesFileWhenPreviouslyAbsent() {
        val repository = BrokerSecretRepository(app)
        val absentSnapshot = repository.captureSnapshot().getOrThrow()
        repository.persist("newly-created").getOrThrow()
        assertTrue(passwordFile.exists())

        val restore = repository.restore(absentSnapshot)

        assertTrue(restore.isSuccess)
        assertFalse(passwordFile.exists())
    }

    @Test
    fun brokerPasswordWriteFailureLeavesOldSecretUnchanged() {
        // Establish the "old" secret through a repository using the real atomic replace.
        BrokerSecretRepository(app).persist("old-secret").getOrThrow()

        // A second repository instance over the SAME file, with an injected failing replace —
        // mirrors AtomicConfigFileOps-style fakes rather than a filesystem permission trick.
        val failing =
            BrokerSecretRepository(app, atomicReplace = { _, _ -> error("simulated disk failure") })
        val result = failing.persist("new-secret")

        assertTrue(result.isFailure)
        assertArrayEquals("old-secret".toByteArray(), passwordFile.readBytes())
    }
}
