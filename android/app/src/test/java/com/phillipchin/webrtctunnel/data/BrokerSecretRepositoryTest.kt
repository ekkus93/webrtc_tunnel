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

/**
 * FIX7 P0-003-B: [BrokerSecretRepository] is the one authoritative owner of the managed broker
 * password file. Config rendering must never write it as a side effect (CRITICAL-6) — these
 * tests exercise the repository itself in isolation.
 *
 * FIX8 P0-008-A: every test here injects a [BrokerSecretPermissionEnforcer] fake — `android
 * .system.Os.chmod`/`Os.stat` do not behave reliably under Robolectric's plain-JVM environment,
 * so real owner-only enforcement is proven by an emulator/instrumentation test, not here.
 */
@RunWith(RobolectricTestRunner::class)
class BrokerSecretRepositoryTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val passwordFile = File(app.filesDir, "runtime/mqtt_password.txt")

    @Before
    fun setUp() {
        File(app.filesDir, "runtime").deleteRecursively()
    }

    private fun repo(enforcer: BrokerSecretPermissionEnforcer = RecordingPermissionEnforcer()) =
        BrokerSecretRepository(app, permissionEnforcer = enforcer)

    @Test
    fun brokerPasswordPersistUsesAtomicReplacement() {
        val repository = repo()
        val result = repository.persist("s3cret")

        assertTrue(result.isSuccess)
        assertTrue(passwordFile.exists())
        assertArrayEquals("s3cret".toByteArray(), passwordFile.readBytes())
        // No leftover partial/temp file from the unique-temp-file-plus-move pattern.
        val leftovers = passwordFile.parentFile?.listFiles { file -> file.name.contains(".tmp-") }.orEmpty()
        assertTrue("no temp files should remain after a successful persist", leftovers.isEmpty())
    }

    // FIX8 P0-008-A: proves the repository actually calls the enforcer for both the temp file
    // (secured before the plaintext secret is written into it) and the destination (verified
    // again after the atomic move) — the real Os-level effect is proven by an
    // emulator/instrumentation test, not here.
    @Test
    fun brokerPasswordPersistEnforcesOwnerOnlyOnTempAndDestination() {
        val enforcer = RecordingPermissionEnforcer()
        val repository = repo(enforcer)

        repository.persist("s3cret").getOrThrow()

        assertTrue(
            "the destination file must have owner-only permissions enforced/verified after the move",
            enforcer.enforcedFiles.contains(passwordFile.name),
        )
        assertTrue(
            "the temp file must have owner-only permissions enforced before the plaintext secret was written",
            enforcer.enforcedFiles.any { it != passwordFile.name },
        )
    }

    @Test
    fun brokerPasswordSnapshotDistinguishesAbsentAndEmpty() {
        val repository = repo()

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
        val repository = repo()
        repository.persist("original-secret").getOrThrow()
        val snapshot = repository.captureSnapshot().getOrThrow()

        repository.persist("mutated-secret").getOrThrow()
        val restore = repository.restore(snapshot)

        assertTrue(restore.isSuccess)
        assertArrayEquals("original-secret".toByteArray(), passwordFile.readBytes())
    }

    @Test
    fun brokerPasswordRestoreDeletesFileWhenPreviouslyAbsent() {
        val repository = repo()
        val absentSnapshot = repository.captureSnapshot().getOrThrow()
        repository.persist("newly-created").getOrThrow()
        assertTrue(passwordFile.exists())

        val restore = repository.restore(absentSnapshot)

        assertTrue(restore.isSuccess)
        assertFalse(passwordFile.exists())
    }

    // FIX8 P0-008-A: restore also enforces/verifies owner-only permissions — a rolled-back
    // secret must never end up less protected than a freshly persisted one.
    @Test
    fun brokerSecretRestoreVerifiesOwnerOnlyPermissions() {
        val repository = repo()
        repository.persist("original-secret").getOrThrow()
        val snapshot = repository.captureSnapshot().getOrThrow()
        repository.persist("mutated-secret").getOrThrow()

        val enforcer = RecordingPermissionEnforcer()
        val restoringRepo = repo(enforcer)
        val restore = restoringRepo.restore(snapshot)

        assertTrue(restore.isSuccess)
        assertTrue(
            "restore must enforce/verify owner-only permissions on the restored destination",
            enforcer.enforcedFiles.contains(passwordFile.name),
        )
    }

    @Test
    fun brokerPasswordWriteFailureLeavesOldSecretUnchanged() {
        // Establish the "old" secret through a repository using the real atomic replace.
        repo().persist("old-secret").getOrThrow()

        // A second repository instance over the SAME file, with an injected failing replace —
        // mirrors AtomicConfigFileOps-style fakes rather than a filesystem permission trick.
        val failing = BrokerSecretRepository(app, atomicReplace = { _, _ -> error("simulated disk failure") })
        val result = failing.persist("new-secret")

        assertTrue(result.isFailure)
        assertArrayEquals("old-secret".toByteArray(), passwordFile.readBytes())
    }

    // FIX8 P0-008-D: a permission enforcement/verification failure occurring AFTER the atomic
    // move already committed the new secret's bytes is visible as a genuine failure (not
    // silently treated as success) — a coordinator-level caller (SetupPersistenceCoordinator)
    // is the one that restores the prior secret, using exactly this repository's own
    // captureSnapshot/restore primitives, which this test proves still work correctly
    // immediately after such a failure.
    @Test
    fun brokerSecretPermissionFailureAfterMoveRestoresPriorSecret() {
        repo().persist("old-secret").getOrThrow()
        val priorSnapshot = repo().captureSnapshot().getOrThrow()
        // Fails only on the destination (post-move verify), letting the temp-file enforcement
        // (pre-write) succeed — proving the move itself happened before the failure.
        val failing = repo(FailingPermissionEnforcer(failOn = { it.name == passwordFile.name }))

        val result = failing.persist("new-secret")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BrokerSecretPermissionException)
        assertArrayEquals(
            "the real move already committed the new secret's bytes despite the reported failure " +
                "— a caller must not assume the destination is untouched",
            "new-secret".toByteArray(),
            passwordFile.readBytes(),
        )

        assertTrue(
            "a caller (e.g. SetupPersistenceCoordinator) must be able to restore the prior secret " +
                "using this repository's own restore primitive",
            repo().restore(priorSnapshot).isSuccess,
        )
        assertArrayEquals("old-secret".toByteArray(), passwordFile.readBytes())
    }

    // FIX8 P0-008-D: a permission enforcement failure on the very first secret (nothing to roll
    // back to) must leave the file absent — no partially-secured or unsecured secret left behind.
    @Test
    fun brokerSecretPermissionFailureBeforeFirstSecretLeavesFileAbsent() {
        assertFalse(passwordFile.exists())
        val failing = repo(FailingPermissionEnforcer())

        val result = failing.persist("first-secret")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BrokerSecretPermissionException)
        assertFalse("no file must be left behind when the very first secret's persist fails", passwordFile.exists())
    }

    // FIX8 P0-008-C: neither the permission-failure code/message nor the cleanup-failure log
    // line may leak the file's absolute path.
    @Test
    fun noRawSecretPathAppearsInCleanupOrPermissionDiagnostics() {
        val failing = repo(FailingPermissionEnforcer())

        val result = failing.persist("s3cret")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertFalse(
            "the permission-failure message must never contain the absolute file path",
            message.contains(passwordFile.absolutePath),
        )
        assertEqualsFixedIdentifier(message)
    }

    private fun assertEqualsFixedIdentifier(message: String) {
        assertTrue(
            "the permission-failure message must be the fixed, safe identifier",
            message == "broker_secret_permissions_failed",
        )
    }
}
