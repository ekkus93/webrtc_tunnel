package com.phillipchin.webrtctunnel.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.CandidateCleanupException
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * FIX6 P0-001-C: config import must consume the atomic-write result, must not report
 * success when the write fails, must propagate cancellation, and must redact secrets.
 *
 * Tests drive [ImportExportService] directly rather than through [ImportExportViewModel]:
 * the ViewModel's op runner still wraps the call in `runCatching` (a cancellation-swallow
 * fixed in Stage B / P0-005), so the service is the correct layer to prove cancellation
 * propagation now.
 */
@RunWith(RobolectricTestRunner::class)
class ImportExportServiceTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        File(app.filesDir, "config.toml").deleteRecursively()
    }

    private class WriteResultConfigRepository(
        context: android.content.Context,
        private val onWrite: () -> Result<Unit>,
    ) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> = onWrite()
    }

    /** Passes bytes through unchanged but records the exact array instance [decrypt] returns,
     * so a test can verify the caller wiped that specific buffer afterward (FIX7 P1-001-D). A
     * fresh copy is returned each time so the recorded reference is decoupled from the file's
     * own on-disk byte array. */
    private class CapturingIdentityCrypto : IdentityCrypto {
        var lastDecrypted: ByteArray? = null

        // FIX7 P2-001-B: no copy — the exact reference IdentityRepository.storeEncryptedIdentity
        // passes in is what a private-identity-import test must observe being wiped afterward.
        var lastEncrypted: ByteArray? = null

        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.also { lastEncrypted = it }

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf().also { lastDecrypted = it }
    }

    private fun serviceWith(
        configRepository: ConfigRepository,
        identityRepository: IdentityRepository = IdentityRepository(app, CapturingIdentityCrypto()),
        deleteCandidateFile: (File) -> Result<Unit> = ::deleteCandidateFileSafely,
    ): ImportExportService {
        // No encrypted identity is stored unless the caller sets one up via identityRepository,
        // so import uses identity-less validation by default, and the shared RecordingBridge's
        // validateConfig returns valid by default.
        val deps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { RecordingBridge() },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = identityRepository,
                dispatchers = inlineTestDispatchers(),
            )
        return ImportExportService(deps, deleteCandidateFile)
    }

    @Test
    fun configImportWriteFailureDoesNotReportImported() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.failure(IOException("disk full")) },
            )

        var thrown: Throwable? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (error: Exception) {
            thrown = error
        }

        assertTrue(
            "a failed config write must surface as a thrown failure, not a silent success",
            thrown is IOException,
        )
    }

    @Test
    fun configImportWriteFailureLeavesOldConfigUnchanged() {
        File(app.filesDir, "config.toml").writeText("format = \"old\"\n")
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.failure(IOException("disk full")) },
            )

        runCatching { runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") } }

        assertEquals(
            "a failed import must leave the previous config on disk",
            "format = \"old\"\n",
            File(app.filesDir, "config.toml").readText(),
        )
    }

    @Test
    fun configImportCancellationPropagates() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { throw CancellationException("cancelled during write") },
            )

        var caught: CancellationException? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue(
            "cancellation during import must propagate, not be converted into a normal failure",
            caught != null,
        )
    }

    @Test
    fun configImportWriteFailureRedactsSecretMessage() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) {
                    Result.failure(IOException("write failed: password=sentinel"))
                },
            )

        var message: String? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (error: IOException) {
            message = error.message
        }

        assertFalse("the raw secret must not reach the import failure", message.orEmpty().contains("sentinel"))
        assertTrue(message.orEmpty().contains("***REDACTED***"))
    }

    // FIX7 P1-001-C/P1-001-E: a candidate-cleanup failure after an otherwise-successful
    // write must never be silently discarded — it must surface as a visible failure, not as
    // "Config imported".
    @Test
    fun configImportCleanupFailureAfterWriteSuccessReportsFailureNotImported() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.success(Unit) },
                deleteCandidateFile = { Result.failure(IOException("cleanup boom")) },
            )

        var thrown: Throwable? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (error: Exception) {
            thrown = error
        }

        assertTrue(
            "a cleanup-only failure after a successful write must surface as a visible " +
                "CandidateCleanupException, not a silent success",
            thrown is CandidateCleanupException,
        )
    }

    // FIX7 P1-001-E: when the write itself fails AND cleanup also fails, the original write
    // failure must remain the primary, reported error — never replaced by the cleanup failure.
    @Test
    fun configImportPrimaryFailurePreservedWhenCleanupAlsoFails() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.failure(IOException("disk full password=sentinel")) },
                deleteCandidateFile = { Result.failure(IOException("cleanup boom")) },
            )

        var thrown: Throwable? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (error: Exception) {
            thrown = error
        }

        assertTrue(
            "the primary write failure must be the reported error, got $thrown",
            thrown is IOException && thrown.message.orEmpty().contains("REDACTED"),
        )
        assertTrue(
            "the cleanup failure must still be attached (not silently dropped)",
            thrown?.suppressed?.any { it.message?.contains("cleanup boom") == true } == true,
        )
    }

    // FIX7 P1-001-E: a genuine cancellation must propagate even when cleanup also fails —
    // never converted into an ordinary CandidateCleanupException/failure.
    @Test
    fun configImportCancellationPreservedWhenCleanupAlsoFails() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { throw CancellationException("cancelled during write") },
                deleteCandidateFile = { Result.failure(IOException("cleanup boom")) },
            )

        var caught: CancellationException? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue(
            "cancellation must propagate even when cleanup also fails",
            caught != null,
        )
        assertTrue(
            "the cleanup failure must still be attached to the propagated cancellation",
            caught?.suppressed?.any { it.message?.contains("cleanup boom") == true } == true,
        )
    }

    private fun identityRepositoryWithStoredIdentity(crypto: CapturingIdentityCrypto): IdentityRepository {
        val repo = IdentityRepository(app, crypto)
        repo.storeEncryptedIdentity("private-identity-bytes".toByteArray(), "public-identity-line")
        return repo
    }

    // FIX7 P1-001-D: the plaintext identity buffer read for identity-aware config validation
    // must be wiped after a successful import.
    @Test
    fun importedPrivateBytesWipedOnSuccess() {
        val crypto = CapturingIdentityCrypto()
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.success(Unit) },
                identityRepository = identityRepositoryWithStoredIdentity(crypto),
            )

        runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }

        val decrypted = crypto.lastDecrypted
        assertTrue("the identity plaintext buffer must have been captured", decrypted != null)
        assertTrue(
            "the identity plaintext buffer must be wiped after a successful import",
            decrypted!!.all { it == 0.toByte() },
        )
    }

    // FIX7 P1-001-D: the plaintext identity buffer must be wiped even when config validation
    // (using that identity) fails.
    @Test
    fun importedPrivateBytesWipedOnValidationFailure() {
        val crypto = CapturingIdentityCrypto()
        val deps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = {
                    RecordingBridge().apply {
                        validationResult = com.phillipchin.webrtctunnel.model.ValidationResult(false, "invalid")
                    }
                },
                configRepository = WriteResultConfigRepository(app) { Result.success(Unit) },
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = identityRepositoryWithStoredIdentity(crypto),
                dispatchers = inlineTestDispatchers(),
            )
        val service = ImportExportService(deps)

        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (_: Exception) {
            // Expected: validation failure surfaces as a thrown IllegalArgumentException.
        }

        val decrypted = crypto.lastDecrypted
        assertTrue("the identity plaintext buffer must have been captured", decrypted != null)
        assertTrue(
            "the identity plaintext buffer must be wiped even when validation fails",
            decrypted!!.all { it == 0.toByte() },
        )
    }

    // FIX7 P1-001-D: the plaintext identity buffer must be wiped even when the config write
    // (persistence) fails after validation succeeded.
    @Test
    fun importedPrivateBytesWipedOnPersistenceFailure() {
        val crypto = CapturingIdentityCrypto()
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.failure(IOException("disk full")) },
                identityRepository = identityRepositoryWithStoredIdentity(crypto),
            )

        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (_: Exception) {
            // Expected: persistence failure surfaces as a thrown IOException.
        }

        val decrypted = crypto.lastDecrypted
        assertTrue("the identity plaintext buffer must have been captured", decrypted != null)
        assertTrue(
            "the identity plaintext buffer must be wiped even when persistence fails",
            decrypted!!.all { it == 0.toByte() },
        )
    }

    // FIX7 P1-001-D: the plaintext identity buffer must be wiped even when the import is
    // cancelled mid-flight.
    @Test
    fun importedPrivateBytesWipedOnCancellation() {
        val crypto = CapturingIdentityCrypto()
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { throw CancellationException("cancelled during write") },
                identityRepository = identityRepositoryWithStoredIdentity(crypto),
            )

        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (_: CancellationException) {
            // Expected: cancellation propagates.
        }

        val decrypted = crypto.lastDecrypted
        assertTrue("the identity plaintext buffer must have been captured", decrypted != null)
        assertTrue(
            "the identity plaintext buffer must be wiped even when cancelled",
            decrypted!!.all { it == 0.toByte() },
        )
    }

    // FIX7 P2-001-B: ImportKind.PrivateIdentity's own wipe/cleanup-composition path had zero
    // test coverage — every existing wipe test above drives ImportKind.Config's identity-for-
    // validation *read*, a different caller. This drives the real import-a-new-private-identity
    // caller (importPrivateIdentityContent's canonicalBytes) end to end through
    // ImportExportService.importContent, observing the exact ByteArray instance via
    // IdentityCrypto.encrypt (which storeEncryptedIdentity calls with that same reference).
    @Test
    fun privateIdentityImportCanonicalBytesWipedOnSuccess() {
        val crypto = CapturingIdentityCrypto()
        val service =
            serviceWith(configRepository = ConfigRepository(app), identityRepository = IdentityRepository(app, crypto))

        runBlocking {
            service.importContent(ImportKind.PrivateIdentity, "peer_id = \"android-phone\"\nprivate_key = \"secret\"\n")
        }

        val encrypted = crypto.lastEncrypted
        assertTrue("the canonical private-identity buffer must have been captured", encrypted != null)
        assertTrue(
            "the canonical private-identity buffer must be wiped after a successful import",
            encrypted!!.all { it == 0.toByte() },
        )
    }

    // FIX7 P2-001-B: the same buffer must be wiped even when persistence (the atomic identity
    // replace) fails after the buffer was already allocated.
    @Test
    fun privateIdentityImportCanonicalBytesWipedOnPersistenceFailure() {
        val crypto = CapturingIdentityCrypto()
        val failingIdentityRepository =
            IdentityRepository(app, crypto, atomicReplace = { _, _ -> throw IOException("disk full") })
        val service =
            serviceWith(configRepository = ConfigRepository(app), identityRepository = failingIdentityRepository)

        try {
            runBlocking {
                service.importContent(
                    ImportKind.PrivateIdentity,
                    "peer_id = \"android-phone\"\nprivate_key = \"secret\"\n",
                )
            }
        } catch (_: Exception) {
            // Expected: persistence failure surfaces as a thrown failure.
        }

        val encrypted = crypto.lastEncrypted
        assertTrue("the canonical private-identity buffer must have been captured", encrypted != null)
        assertTrue(
            "the canonical private-identity buffer must be wiped even when persistence fails",
            encrypted!!.all { it == 0.toByte() },
        )
    }

    // FIX7 P2-001-B: two real, sequential config imports through the actual ImportExportService
    // call site must each use their own unique candidate workspace file — MutationHelpersTest's
    // createCandidateFileProducesUniquePathsForTheSamePrefix only proves the helper is unique in
    // isolation, never driven through a real caller.
    @Test
    fun twoRealSequentialConfigImportsUseDistinctCandidateFiles() {
        val candidatePaths = mutableListOf<String>()
        val service =
            serviceWith(
                configRepository = ConfigRepository(app),
                deleteCandidateFile = { file ->
                    candidatePaths.add(file.absolutePath)
                    deleteCandidateFileSafely(file)
                },
            )

        runBlocking {
            service.importContent(ImportKind.Config, "format = \"first\"\n")
            service.importContent(ImportKind.Config, "format = \"second\"\n")
        }

        assertEquals("both real imports must have used a candidate file", 2, candidatePaths.size)
        assertTrue(
            "each real import must use its own unique candidate workspace file, not a shared one",
            candidatePaths[0] != candidatePaths[1],
        )
    }
}
