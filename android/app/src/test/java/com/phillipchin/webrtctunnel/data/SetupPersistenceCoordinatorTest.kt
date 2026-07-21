package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * FIX6 P0-003 / FIX7 P0-004: setup persistence must be transactional — config committed last,
 * partial mutation rolled back in reverse order (on ordinary failure AND on cancellation),
 * rollback failures reported individually, and success only when every requested stage commits.
 */
@RunWith(RobolectricTestRunner::class)
class SetupPersistenceCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var configRepository: ConfigRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var brokerSecretRepository: BrokerSecretRepository
    private val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")

    @Before
    fun setUp() {
        listOf("config.toml", "setup_input.json", "identity.enc", "identity.pub", "authorized_keys").forEach {
            File(context.filesDir, it).delete()
        }
        File(context.filesDir, "runtime").deleteRecursively()
        configRepository = ConfigRepository(context)
        identityRepository = IdentityRepository(context, PlaintextCrypto())
        brokerSecretRepository = BrokerSecretRepository(context)
    }

    // --- Test seams -------------------------------------------------------------------------

    /** Stores plaintext verbatim so tests can read back exactly what was written. */
    private class PlaintextCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }

    /** Throws on encrypt so the Identity stage fails deterministically. */
    private class ThrowingCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = throw IOException("identity write failed")

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }

    /** Throws cancellation on encrypt, to drive "cancelled during the very first stage". */
    private class CancellingCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = throw CancellationException("identity write cancelled")

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }

    /** ConfigRepository whose config write always fails (drives Config-stage failure). */
    private class FailingConfig(context: Context) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
            Result.failure(IOException("config commit failed"))
    }

    /** ConfigRepository whose config write always throws cancellation (drives Config-stage
     * cancellation while every earlier stage has already committed). */
    private class CancellingConfig(context: Context) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
            throw CancellationException("config write cancelled")
    }

    /** ConfigRepository whose setup-input write always throws (drives SetupInput-stage failure). */
    private class FailingSetupInput(context: Context) : ConfigRepository(context) {
        override fun saveSetupInput(input: SetupConfigInput): Unit = throw IOException("setup input write failed")
    }

    /** ConfigRepository whose setup-input write always throws cancellation (drives SetupInput-
     * stage cancellation while Identity/AuthorizedKeys/BrokerSecret have already committed). */
    private class CancellingSetupInput(context: Context) : ConfigRepository(context) {
        override fun saveSetupInput(input: SetupConfigInput): Unit =
            throw CancellationException("setup input write cancelled")
    }

    /** ConfigRepository whose snapshot read (of the *current* config, taken before any mutation)
     * always throws, driving a Snapshot-stage abort before the first mutation. */
    private class FailingSnapshotConfig(context: Context) : ConfigRepository(context) {
        override fun readConfig(): String = throw IOException("snapshot read failed")
    }

    /**
     * Records the maximum number of overlapping saveSetupInput calls to prove serialization.
     * FIX7 P2-001-A: the first call blocks on a test-controlled [releaseFirst] barrier (never an
     * elapsed-time guess) so the second call has an unbounded window to attempt entry while the
     * first is still inside — a broken mutex would let it in, a working one cannot.
     */
    private class ConcurrencyProbe(context: Context) : ConfigRepository(context) {
        val maxConcurrent = AtomicInteger(0)
        private val active = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        override fun saveSetupInput(input: SetupConfigInput) {
            val now = active.incrementAndGet()
            maxConcurrent.updateAndGet { existing -> maxOf(existing, now) }
            if (!firstEntered.isCompleted) {
                firstEntered.complete(Unit)
                runBlocking { releaseFirst.await() }
            }
            active.decrementAndGet()
            super.saveSetupInput(input)
        }
    }

    private class RecordingPreferences {
        var stored: AndroidAppPreferences = AndroidAppPreferences()
        val writes = mutableListOf<AndroidAppPreferences>()
        var failOnWriteNumber: Int = -1
        var cancelOnWriteNumber: Int = -1
        private val writeCount = AtomicInteger(0)

        val load: suspend () -> AndroidAppPreferences = { stored }
        val persist: suspend (AndroidAppPreferences) -> Result<Unit> = { prefs ->
            when (writeCount.incrementAndGet()) {
                cancelOnWriteNumber -> throw CancellationException("cancelled during preference write")
                failOnWriteNumber -> Result.failure(IOException("preference write failed"))
                else -> {
                    writes.add(prefs)
                    stored = prefs
                    Result.success(Unit)
                }
            }
        }
    }

    /** [IdentityRepository] whose `authorized_keys` write always throws cancellation, while its
     * identity-pair write (same injected [atomicReplace] seam) still succeeds normally — drives
     * "cancelled during AuthorizedKeys, after Identity already committed". */
    private fun identityRepositoryCancellingAuthorizedKeysWrite(context: Context): IdentityRepository =
        IdentityRepository(
            context,
            PlaintextCrypto(),
            atomicReplace = { file, bytes ->
                if (file.name == "authorized_keys") {
                    throw CancellationException("authorized_keys write cancelled")
                }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            },
        )

    /** [BrokerSecretRepository] whose replacement write always throws cancellation. */
    private fun brokerSecretRepositoryCancellingReplace(context: Context): BrokerSecretRepository =
        BrokerSecretRepository(
            context,
            atomicReplace = { _, _ -> throw CancellationException("broker secret write cancelled") },
        )

    /** [BrokerSecretRepository] whose replacement write fails deterministically on its [failOn]-th
     * call (1-indexed) and otherwise performs a plain (non-atomic — precision isn't the point of
     * this fake) write; used to make the Nth call — e.g. a rollback restore rather than the
     * initial apply — fail while an earlier call of the same kind succeeds. */
    private fun brokerSecretRepositoryFailingOnNthReplace(
        context: Context,
        failOn: Int,
    ): BrokerSecretRepository {
        val callCount = AtomicInteger(0)
        return BrokerSecretRepository(
            context,
            atomicReplace = { file, bytes ->
                if (callCount.incrementAndGet() == failOn) {
                    error("simulated broker secret failure #$failOn")
                }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            },
        )
    }

    private fun coordinator(
        prefs: RecordingPreferences,
        config: ConfigRepository = configRepository,
        identity: IdentityRepository = identityRepository,
        brokerSecret: BrokerSecretRepository = brokerSecretRepository,
    ) = SetupPersistenceCoordinator(config, identity, brokerSecret, prefs.load, prefs.persist)

    private fun request(
        replacementIdentity: IdentityReplacement? = null,
        authorizedPublicIdentityToAdd: String? = null,
        brokerSecretChange: BrokerSecretChange? = null,
        configContents: String = "format = \"committed\"\n",
        setupInput: SetupConfigInput = SetupConfigInput(brokerHost = "broker.new"),
    ) = SetupPersistenceRequest(
        configContents,
        setupInput,
        AndroidAppPreferences(resumeOnUnmetered = false),
        replacementIdentity,
        authorizedPublicIdentityToAdd,
        brokerSecretChange,
    )

    private fun fullRequest() =
        request(
            replacementIdentity = IdentityReplacement("new-priv".toByteArray(), "new-pub"),
            authorizedPublicIdentityToAdd = "remote-key peer",
            brokerSecretChange = BrokerSecretChange.Set("new-broker-secret"),
        )

    private fun SetupRollbackStageResult.stage(): SetupPersistenceStage =
        when (this) {
            is SetupRollbackStageResult.Success -> stage
            is SetupRollbackStageResult.Failure -> stage
        }

    // Routed through a parameter default so detekt's InjectDispatcher rule is satisfied while
    // still giving the concurrency test real multi-threaded parallelism.
    private fun parallelDispatcher(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineDispatcher =
        dispatcher

    // --- Stage order and ordinary failure ----------------------------------------------------

    @Test
    fun allSetupStagesCommitInRequiredOrderIncludingBrokerSecret() =
        runBlocking {
            val result = coordinator(RecordingPreferences()).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Success)
            assertEquals(
                listOf(
                    SetupPersistenceStage.Identity,
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.BrokerSecret,
                    SetupPersistenceStage.SetupInput,
                    SetupPersistenceStage.Preferences,
                    SetupPersistenceStage.Config,
                ),
                (result as SetupPersistenceResult.Success).stages,
            )
            assertEquals("format = \"committed\"\n", configRepository.readConfig())
            assertEquals("broker.new", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertEquals("new-broker-secret", passwordFile.readText())
        }

    @Test
    fun snapshotFailurePerformsNoMutation() =
        runBlocking {
            val prefs = RecordingPreferences()
            val result = coordinator(prefs, config = FailingSnapshotConfig(context)).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            assertEquals(SetupPersistenceStage.Snapshot, (result as SetupPersistenceResult.Failed).failedStage)
            assertTrue("nothing committed, so nothing to roll back", result.rollback.isEmpty())
            assertEquals(0, prefs.writes.size)
            assertFalse(File(context.filesDir, "identity.enc").exists())
            assertFalse(passwordFile.exists())
        }

    @Test
    fun identityFailureStopsAllLaterStages() =
        runBlocking {
            val identity = IdentityRepository(context, ThrowingCrypto())
            val prefs = RecordingPreferences()
            val result = coordinator(prefs, identity = identity).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Identity, failed.failedStage)
            assertTrue("nothing committed, so nothing to roll back", failed.rollback.isEmpty())
            assertEquals(0, prefs.writes.size)
            assertFalse(File(context.filesDir, "authorized_keys").exists())
            assertFalse(passwordFile.exists())
            assertFalse(File(context.filesDir, "setup_input.json").exists())
            assertFalse(File(context.filesDir, "config.toml").exists())
        }

    @Test
    fun authorizedKeysFailureRollsBackIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val prefs = RecordingPreferences()
            // A blank authorized-key line is added as a stage but fails validation inside the repo.
            val result =
                coordinator(prefs).persist(
                    request(
                        replacementIdentity = IdentityReplacement("new-priv".toByteArray(), "new-pub"),
                        authorizedPublicIdentityToAdd = "   ",
                    ),
                )

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.AuthorizedKeys, failed.failedStage)
            assertEquals(listOf(SetupPersistenceStage.Identity), failed.rollback.map { it.stage() })
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("prior-pub", identityRepository.readPublicIdentity())
        }

    @Test
    fun brokerSecretFailureRollsBackAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            brokerSecretRepository.persist("prior-secret").getOrThrow()
            val prefs = RecordingPreferences()
            val failingBrokerSecret =
                BrokerSecretRepository(context, atomicReplace = {
                        _,
                        _,
                    ->
                    error("broker secret write failed")
                })

            val result = coordinator(prefs, brokerSecret = failingBrokerSecret).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.BrokerSecret, failed.failedStage)
            assertEquals(
                listOf(SetupPersistenceStage.AuthorizedKeys, SetupPersistenceStage.Identity),
                failed.rollback.map { it.stage() },
            )
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("prior-secret", passwordFile.readText())
        }

    @Test
    fun setupInputFailureRollsBackBrokerSecretAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val prefs = RecordingPreferences()
            val result =
                coordinator(prefs, config = FailingSetupInput(context)).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.SetupInput, failed.failedStage)
            assertEquals(
                listOf(
                    SetupPersistenceStage.BrokerSecret,
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.Identity,
                ),
                failed.rollback.map { it.stage() },
            )
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertFalse(
                "authorized_keys created by the failed save must be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
            assertFalse(
                "broker secret created by the failed save must be rolled back",
                passwordFile.exists(),
            )
            assertEquals(0, prefs.writes.size)
        }

    @Test
    fun preferencesFailureRollsBackSetupInputBrokerSecretAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val prefs = RecordingPreferences().apply { failOnWriteNumber = 1 }

            val result = coordinator(prefs).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Preferences, failed.failedStage)
            assertEquals(
                listOf(
                    SetupPersistenceStage.SetupInput,
                    SetupPersistenceStage.BrokerSecret,
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.Identity,
                ),
                failed.rollback.map { it.stage() },
            )
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertFalse("broker secret created by the failed save must be rolled back", passwordFile.exists())
        }

    @Test
    fun configFailureRollsBackEveryEarlierStage() =
        runBlocking {
            configRepository.writeConfig("format = \"prior\"\n")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val prefs = RecordingPreferences()

            val result = coordinator(prefs, config = FailingConfig(context)).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Config, failed.failedStage)
            assertEquals(
                listOf(
                    SetupPersistenceStage.Preferences,
                    SetupPersistenceStage.SetupInput,
                    SetupPersistenceStage.BrokerSecret,
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.Identity,
                ),
                failed.rollback.map { it.stage() },
            )
            assertTrue(failed.rollback.all { it is SetupRollbackStageResult.Success })
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertFalse("broker secret created by the failed save must be rolled back", passwordFile.exists())
        }

    @Test
    fun rollbackContinuesAfterEachIndividualRestoreFailure() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            // Preferences stage (write 1) commits; Config fails; the Preferences *restore*
            // (write 2) fails, but every other rollback must still run.
            val prefs = RecordingPreferences().apply { failOnWriteNumber = 2 }

            val result = coordinator(prefs, config = FailingConfig(context)).persist(fullRequest())

            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Config, failed.failedStage)
            val prefRollback = failed.rollback.single { it.stage() == SetupPersistenceStage.Preferences }
            assertTrue(prefRollback is SetupRollbackStageResult.Failure)
            // Identity, AuthorizedKeys, BrokerSecret and SetupInput rollbacks still ran and succeeded.
            listOf(
                SetupPersistenceStage.SetupInput,
                SetupPersistenceStage.BrokerSecret,
                SetupPersistenceStage.AuthorizedKeys,
                SetupPersistenceStage.Identity,
            ).forEach { stage ->
                assertTrue(failed.rollback.single { it.stage() == stage } is SetupRollbackStageResult.Success)
            }
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
        }

    @Test
    fun rollbackIncompleteReturnsEveryFailedRollbackStage() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            brokerSecretRepository.persist("prior-secret").getOrThrow()
            val prefs = RecordingPreferences().apply { failOnWriteNumber = 2 }
            val brokerSecretFailingOnRestore = brokerSecretRepositoryFailingOnNthReplace(context, failOn = 2)

            val result =
                coordinator(prefs, config = FailingConfig(context), brokerSecret = brokerSecretFailingOnRestore)
                    .persist(fullRequest())

            val failed = result as SetupPersistenceResult.Failed
            val failures = failed.rollback.filterIsInstance<SetupRollbackStageResult.Failure>().map { it.stage }
            assertEquals(
                "both the Preferences restore and the BrokerSecret restore failed and both must be reported",
                setOf(SetupPersistenceStage.Preferences, SetupPersistenceStage.BrokerSecret),
                failures.toSet(),
            )
        }

    // --- Cancellation: one focused test per meaningful point -----------------------------------

    @Test
    fun cancellationBeforeFirstMutationPerformsNoRollbackAndPropagates() =
        runBlocking {
            val identity = IdentityRepository(context, CancellingCrypto())
            var caught: CancellationException? = null
            try {
                coordinator(RecordingPreferences(), identity = identity).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation before the first mutation must propagate", caught != null)
            assertTrue(
                "nothing committed, so no rollback exceptions should be attached",
                caught!!.suppressedExceptions.isEmpty(),
            )
            assertFalse(File(context.filesDir, "identity.enc").exists())
            assertFalse(passwordFile.exists())
        }

    @Test
    fun cancellationDuringAuthorizedKeysRollsBackIdentityAndPropagates() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val identity = identityRepositoryCancellingAuthorizedKeysWrite(context)
            // Same underlying identity storage, so the "prior" state is visible to both handles.
            identity.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")

            var caught: CancellationException? = null
            try {
                coordinator(RecordingPreferences(), identity = identity).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during AuthorizedKeys must propagate", caught != null)
            assertArrayEquals("prior-priv".toByteArray(), identity.readPrivateIdentityPlaintext())
            assertEquals("prior-pub", identity.readPublicIdentity())
        }

    @Test
    fun cancellationDuringBrokerSecretRollsBackAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val cancellingBrokerSecret = brokerSecretRepositoryCancellingReplace(context)

            var caught: CancellationException? = null
            try {
                coordinator(RecordingPreferences(), brokerSecret = cancellingBrokerSecret).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during BrokerSecret must propagate", caught != null)
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertFalse(
                "authorized_keys committed before cancellation must be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
        }

    @Test
    fun cancellationDuringSetupInputRollsBackBrokerSecretAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")

            var caught: CancellationException? = null
            try {
                coordinator(RecordingPreferences(), config = CancellingSetupInput(context)).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during SetupInput must propagate", caught != null)
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertFalse(
                "authorized_keys committed before cancellation must be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
            assertFalse("broker secret committed before cancellation must be rolled back", passwordFile.exists())
        }

    @Test
    fun cancellationDuringPreferencesRollsBackAllEarlierStages() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val prefs = RecordingPreferences().apply { cancelOnWriteNumber = 1 }

            var caught: CancellationException? = null
            try {
                coordinator(prefs).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Preferences must propagate", caught != null)
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertFalse(
                "authorized_keys committed before cancellation must be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
            assertFalse("broker secret committed before cancellation must be rolled back", passwordFile.exists())
            assertFalse("Config must never have been reached", File(context.filesDir, "config.toml").exists())
        }

    @Test
    fun cancellationDuringConfigRollsBackAllEarlierStages() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val prefs = RecordingPreferences()
            val priorPreferences = prefs.stored

            var caught: CancellationException? = null
            try {
                coordinator(prefs, config = CancellingConfig(context)).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Config must propagate", caught != null)
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertFalse(
                "authorized_keys committed before cancellation must be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
            assertFalse("broker secret committed before cancellation must be rolled back", passwordFile.exists())
            assertEquals(
                "preferences committed before cancellation must be rolled back to their prior value",
                priorPreferences,
                prefs.stored,
            )
        }

    @Test
    fun cancellationRollbackContinuesAfterOneRestoreFailure() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            // Preferences stage (write 1) commits; Config is cancelled; the Preferences *restore*
            // (write 2) fails, but every other rollback must still run to completion.
            val prefs = RecordingPreferences().apply { failOnWriteNumber = 2 }

            var caught: CancellationException? = null
            try {
                coordinator(prefs, config = CancellingConfig(context)).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Config must propagate", caught != null)
            // Despite the Preferences restore failing, Identity/AuthorizedKeys/BrokerSecret/
            // SetupInput were still restored.
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertFalse(
                "authorized_keys committed before cancellation must still be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
            assertFalse(
                "broker secret committed before cancellation must still be rolled back",
                passwordFile.exists(),
            )
        }

    @Test
    fun cancellationRollbackFailureIsReportedAndAttachedAsSuppressed() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val prefs = RecordingPreferences().apply { failOnWriteNumber = 2 }

            var caught: CancellationException? = null
            try {
                coordinator(prefs, config = CancellingConfig(context)).persist(fullRequest())
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue(caught != null)
            val rollbackFailures = caught!!.suppressedExceptions.filterIsInstance<SetupRollbackException>()
            assertEquals(1, rollbackFailures.size)
            assertEquals(SetupPersistenceStage.Preferences, rollbackFailures.single().stage)
        }

    // --- Wiping -------------------------------------------------------------------------------

    @Test
    fun brokerSecretSnapshotBytesAreWipedAfterSuccessFailureAndCancellation() =
        runBlocking {
            brokerSecretRepository.persist("prior-secret-success").getOrThrow()
            val trackedSuccess = "prior-secret-success".toByteArray()
            val trackingSuccess = BrokerSecretRepository(context, readBytes = { trackedSuccess })
            val resultSuccess =
                coordinator(RecordingPreferences(), brokerSecret = trackingSuccess).persist(
                    request(brokerSecretChange = BrokerSecretChange.Set("new-secret-success")),
                )
            assertTrue(resultSuccess is SetupPersistenceResult.Success)
            assertTrue(
                "success: broker secret snapshot bytes must be wiped",
                trackedSuccess.all { it == 0.toByte() },
            )

            brokerSecretRepository.persist("prior-secret-failure").getOrThrow()
            val trackedFailure = "prior-secret-failure".toByteArray()
            val trackingFailure = BrokerSecretRepository(context, readBytes = { trackedFailure })
            val resultFailure =
                coordinator(RecordingPreferences(), config = FailingConfig(context), brokerSecret = trackingFailure)
                    .persist(request(brokerSecretChange = BrokerSecretChange.Set("new-secret-failure")))
            assertTrue(resultFailure is SetupPersistenceResult.Failed)
            assertTrue(
                "failure: broker secret snapshot bytes must be wiped",
                trackedFailure.all { it == 0.toByte() },
            )

            brokerSecretRepository.persist("prior-secret-cancel").getOrThrow()
            val trackedCancel = "prior-secret-cancel".toByteArray()
            val trackingCancel = BrokerSecretRepository(context, readBytes = { trackedCancel })
            var caught: CancellationException? = null
            try {
                coordinator(RecordingPreferences(), config = CancellingConfig(context), brokerSecret = trackingCancel)
                    .persist(request(brokerSecretChange = BrokerSecretChange.Set("new-secret-cancel")))
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }
            assertTrue(caught != null)
            assertTrue(
                "cancellation: broker secret snapshot bytes must be wiped",
                trackedCancel.all { it == 0.toByte() },
            )
        }

    // --- Concurrency ---------------------------------------------------------------------------

    // FIX7 P2-001-A: replaces a Thread.sleep-widened race window with a deterministic barrier.
    // The first call is held open inside saveSetupInput (still holding the coordinator's real
    // Mutex) until this test explicitly releases it. The second call is launched with
    // CoroutineStart.UNDISPATCHED, which runs it synchronously, on this thread, up to its first
    // suspension point before `launch` returns — since the mutex is currently held, that first
    // suspension point is guaranteed to be the mutex acquisition itself. By the time the launch
    // call returns, the second call has therefore definitely attempted entry while the first is
    // still inside, with no elapsed-time guess anywhere.
    @Test
    fun twoSetupCoordinatorCallsCannotOverlap() =
        runBlocking {
            val probe = ConcurrencyProbe(context)
            val coordinator = coordinator(RecordingPreferences(), config = probe)

            val firstJob = launch(parallelDispatcher()) { coordinator.persist(request()) }
            probe.firstEntered.await()

            val secondJob =
                launch(parallelDispatcher(), start = CoroutineStart.UNDISPATCHED) {
                    coordinator.persist(request())
                }

            probe.releaseFirst.complete(Unit)
            joinAll(firstJob, secondJob)

            assertEquals("the coordinator mutex must serialize concurrent saves", 1, probe.maxConcurrent.get())
        }

    @Test
    fun failedSaveNeverReportsConfigurationSaved() =
        runBlocking {
            val result = coordinator(RecordingPreferences(), config = FailingConfig(context)).persist(request())

            assertTrue("a failed save must never report success", result is SetupPersistenceResult.Failed)
        }
}
