package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * FIX8 P0-003-D/G: a stage whose real atomic move succeeds — the destination genuinely has new
 * bytes — but is reported failed anyway (a post-move verification/cleanup failure, or a
 * cancellation raised right after the move) must still be rolled back to the exact prior bytes,
 * not just a "restore ran" indicator. Split from [SetupPersistenceCoordinatorTest] to keep that
 * file under the repo's file-size guidance.
 */
@RunWith(RobolectricTestRunner::class)
class SetupPersistenceCoordinatorExactBytesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var identityRepository: IdentityRepository
    private lateinit var brokerSecretRepository: BrokerSecretRepository
    private val setupInputFile = File(context.filesDir, "setup_input.json")
    private val configFile = File(context.filesDir, "config.toml")

    @Before
    fun setUp() {
        listOf("config.toml", "setup_input.json", "identity.enc", "identity.pub", "authorized_keys").forEach {
            File(context.filesDir, it).delete()
        }
        identityRepository = IdentityRepository(context, PassthroughCrypto())
        brokerSecretRepository = BrokerSecretRepository(context)
    }

    private class PassthroughCrypto : com.phillipchin.webrtctunnel.security.IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }

    /** Performs the real atomic setup-input write (so the destination genuinely changes), then
     * reports the stage as failed on its first call — the coordinator's rollback restore is the
     * second call, which delegates to the real implementation unchanged. */
    private class SetupInputFailsAfterRealMove(context: Context) : ConfigRepository(context) {
        private var applyCount = 0

        override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> {
            val real = super.saveSetupInputAtomically(input)
            applyCount++
            return if (applyCount == 1) Result.failure(IOException("post-move verify failed")) else real
        }
    }

    /** Same real-move-then-fail shape as [SetupInputFailsAfterRealMove], but raises cancellation
     * instead of a Result failure on the first (apply) call. */
    private class SetupInputCancelsAfterRealMove(context: Context) : ConfigRepository(context) {
        private var applyCount = 0

        override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> {
            val real = super.saveSetupInputAtomically(input)
            applyCount++
            if (applyCount == 1) throw CancellationException("cancelled after move")
            return real
        }
    }

    private class ConfigFailsAfterRealMove(context: Context) : ConfigRepository(context) {
        private var applyCount = 0

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            val real = super.writeConfigAtomically(contents)
            applyCount++
            return if (applyCount == 1) Result.failure(IOException("post-move verify failed")) else real
        }
    }

    /** The move itself succeeds for real; only the temp-file cleanup afterwards fails — proving
     * a cleanup-only failure (not a write/move failure) still marks Config failed and still
     * triggers an exact-byte rollback. */
    private class ConfigCleanupFailsAfterRealMove(context: Context) : ConfigRepository(context) {
        private var applyCount = 0

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            applyCount++
            return if (applyCount == 1) {
                writeConfigAtomicallyWith(
                    File(configPath),
                    contents,
                    object : AtomicConfigFileOps by RealAtomicConfigFileOps {
                        override fun deleteIfExists(temp: java.nio.file.Path) {
                            throw IOException("temp cleanup failed")
                        }
                    },
                )
            } else {
                super.writeConfigAtomically(contents)
            }
        }
    }

    /** Throws [SecurityException] directly from the config write, instead of returning
     * Result.failure — proving a raw SecurityException from a config operation is still caught
     * (by [mutationResult] in [SetupPersistenceCoordinator.applyStage]) and turned into a Failed
     * result with rollback, not left to crash out of persist() uncaught. */
    private class ConfigThrowsSecurityException(context: Context) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
            throw SecurityException("denied by policy")
    }

    private fun coordinator(
        config: ConfigRepository,
        prefs: RecordingPreferences = RecordingPreferences(),
    ) = SetupPersistenceCoordinator(config, identityRepository, brokerSecretRepository, prefs.load, prefs.persist)

    private class RecordingPreferences {
        var stored: AndroidAppPreferences = AndroidAppPreferences()
        val load: suspend () -> AndroidAppPreferences = { stored }
        val persist: suspend (AndroidAppPreferences) -> Result<Unit> = { prefs ->
            stored = prefs
            Result.success(Unit)
        }
    }

    private fun request(setupInput: SetupConfigInput = SetupConfigInput(brokerHost = "broker.new")) =
        SetupPersistenceRequest(
            configContents = "format = \"committed\"\n",
            setupInput = setupInput,
            preferences = AndroidAppPreferences(),
            replacementIdentity = null,
            authorizedPublicIdentityToAdd = null,
            brokerSecretChange = null,
        )

    @Test
    fun setupInputFailureAfterMoveRestoresCurrentStageExactBytes() =
        runBlocking {
            val priorBytes = """{"brokerHost":"broker.prior"}""".toByteArray()
            setupInputFile.writeBytes(priorBytes)

            val result = coordinator(SetupInputFailsAfterRealMove(context)).persist(request())

            assertTrue(result is SetupPersistenceResult.Failed)
            assertArrayEquals(
                "the real move changed the file; rollback must restore the exact prior bytes",
                priorBytes,
                setupInputFile.readBytes(),
            )
        }

    @Test
    fun setupInputCancellationAfterMoveRestoresCurrentStageExactBytes() =
        runBlocking {
            val priorBytes = """{"brokerHost":"broker.prior"}""".toByteArray()
            setupInputFile.writeBytes(priorBytes)

            var caught: CancellationException? = null
            try {
                coordinator(SetupInputCancelsAfterRealMove(context)).persist(request())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation after the move must propagate", caught != null)
            assertArrayEquals(priorBytes, setupInputFile.readBytes())
        }

    @Test
    fun configFailureAfterMoveRestoresCurrentStageExactBytes() =
        runBlocking {
            val priorBytes = "format = \"prior\"\n".toByteArray()
            configFile.writeBytes(priorBytes)

            val result = coordinator(ConfigFailsAfterRealMove(context)).persist(request())

            assertTrue(result is SetupPersistenceResult.Failed)
            assertArrayEquals(
                "the real move changed the file; rollback must restore the exact prior bytes",
                priorBytes,
                configFile.readBytes(),
            )
        }

    @Test
    fun configCleanupFailureAfterMoveRestoresCurrentStageExactBytes() =
        runBlocking {
            val priorBytes = "format = \"prior\"\n".toByteArray()
            configFile.writeBytes(priorBytes)

            val result = coordinator(ConfigCleanupFailsAfterRealMove(context)).persist(request())

            assertTrue(
                "a cleanup-only failure after a successful move must still mark Config failed",
                result is SetupPersistenceResult.Failed,
            )
            assertArrayEquals(priorBytes, configFile.readBytes())
        }

    @Test
    fun securityExceptionFromConfigOperationReturnsFailureAndTriggersRollback() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val priorSetupInputBytes = """{"brokerHost":"broker.prior"}""".toByteArray()
            setupInputFile.writeBytes(priorSetupInputBytes)

            val result =
                coordinator(ConfigThrowsSecurityException(context)).persist(
                    SetupPersistenceRequest(
                        configContents = "format = \"committed\"\n",
                        setupInput = SetupConfigInput(brokerHost = "broker.new"),
                        preferences = AndroidAppPreferences(),
                        replacementIdentity = IdentityReplacement("new-priv".toByteArray(), "new-pub"),
                        authorizedPublicIdentityToAdd = null,
                        brokerSecretChange = null,
                    ),
                )

            assertTrue(
                "a raw SecurityException must be caught and reported as Failed, not crash uncaught",
                result is SetupPersistenceResult.Failed &&
                    result.failedStage == SetupPersistenceStage.Config,
            )
            assertArrayEquals(
                "Identity committed before the SecurityException must be rolled back",
                "prior-priv".toByteArray(),
                identityRepository.readPrivateIdentityPlaintext(),
            )
            assertArrayEquals(priorSetupInputBytes, setupInputFile.readBytes())
        }

    @Test
    fun setupInputSnapshotBytesWipedAfterSuccessFailureCancellationAndFatalError() =
        runBlocking {
            setupInputFile.writeBytes("""{"brokerHost":"broker.success"}""".toByteArray())
            val trackedSuccess = setupInputFile.readBytes()
            val trackingSuccess = TrackedSnapshotConfig(context, trackedSuccess)
            val successResult = coordinator(trackingSuccess).persist(request())
            assertTrue(successResult is SetupPersistenceResult.Success)
            assertTrue(
                "success: setup-input snapshot bytes must be wiped",
                trackedSuccess.all { it == 0.toByte() },
            )

            setupInputFile.writeBytes("""{"brokerHost":"broker.failure"}""".toByteArray())
            val trackedFailure = setupInputFile.readBytes()
            val trackingFailure = TrackedSnapshotConfig(context, trackedFailure, failConfigStage = true)
            val failureResult = coordinator(trackingFailure).persist(request())
            assertTrue(failureResult is SetupPersistenceResult.Failed)
            assertTrue(
                "failure: setup-input snapshot bytes must be wiped",
                trackedFailure.all { it == 0.toByte() },
            )

            setupInputFile.writeBytes("""{"brokerHost":"broker.cancel"}""".toByteArray())
            val trackedCancel = setupInputFile.readBytes()
            val trackingCancel = TrackedSnapshotConfig(context, trackedCancel, cancelConfigStage = true)
            var caught: CancellationException? = null
            try {
                coordinator(trackingCancel).persist(request())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }
            assertTrue(caught != null)
            assertTrue(
                "cancellation: setup-input snapshot bytes must be wiped",
                trackedCancel.all { it == 0.toByte() },
            )

            setupInputFile.writeBytes("""{"brokerHost":"broker.fatal"}""".toByteArray())
            val trackedFatal = setupInputFile.readBytes()
            val trackingFatal = TrackedSnapshotConfig(context, trackedFatal, throwFatalError = true)
            var caughtFatal: FatalTestError? = null
            try {
                coordinator(trackingFatal).persist(request())
            } catch (fatal: FatalTestError) {
                caughtFatal = fatal
            }
            assertTrue("the fatal error must still propagate out of persist()", caughtFatal != null)
            assertTrue(
                "fatal propagation: wipeSecrets() runs in a finally, so bytes must still be wiped",
                trackedFatal.all { it == 0.toByte() },
            )
        }

    /** An [Error] (not an [Exception]) — proves `wipeSecrets()`'s `finally` runs even when a
     * stage throws something [mutationResult]'s `catch (error: Exception)` cannot catch. */
    private class FatalTestError : Error("fatal test error")

    /** Overrides [captureFilesSnapshot] to return a [ConfigFilesSnapshot] built from
     * [trackedSetupInputBytes] directly (constructing [ExactFileSnapshot] is legal from this
     * same-package test), so the test can observe whether the coordinator wiped those exact
     * bytes after each kind of exit. */
    private class TrackedSnapshotConfig(
        context: Context,
        private val trackedSetupInputBytes: ByteArray,
        private val failConfigStage: Boolean = false,
        private val cancelConfigStage: Boolean = false,
        private val throwFatalError: Boolean = false,
    ) : ConfigRepository(context) {
        override suspend fun captureFilesSnapshot(): Result<ConfigFilesSnapshot> =
            Result.success(
                ConfigFilesSnapshot(
                    config = ExactFileSnapshot(existed = false, bytes = null),
                    setupInput = ExactFileSnapshot(existed = true, bytes = trackedSetupInputBytes),
                ),
            )

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            if (throwFatalError) throw FatalTestError()
            if (cancelConfigStage) throw CancellationException("cancelled at Config")
            if (failConfigStage) return Result.failure(IOException("config commit failed"))
            return super.writeConfigAtomically(contents)
        }
    }
}
