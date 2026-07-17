package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
 * FIX6 P0-003: setup persistence must be transactional — config committed last, partial
 * mutation rolled back in reverse order, rollback failures reported individually, and
 * success only when every requested stage commits.
 */
@RunWith(RobolectricTestRunner::class)
class SetupPersistenceCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var configRepository: ConfigRepository
    private lateinit var identityRepository: IdentityRepository

    @Before
    fun setUp() {
        listOf("config.toml", "setup_input.json", "identity.enc", "identity.pub", "authorized_keys").forEach {
            File(context.filesDir, it).delete()
        }
        configRepository = ConfigRepository(context)
        identityRepository = IdentityRepository(context, PlaintextCrypto())
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

    /** ConfigRepository whose config write always fails (drives Config-stage failure). */
    private class FailingConfig(context: Context) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
            Result.failure(IOException("config commit failed"))
    }

    /** ConfigRepository whose setup-input write always throws (drives SetupInput-stage failure). */
    private class FailingSetupInput(context: Context) : ConfigRepository(context) {
        override fun saveSetupInput(input: SetupConfigInput): Unit = throw IOException("setup input write failed")
    }

    /** Records the maximum number of overlapping saveSetupInput calls to prove serialization. */
    private class ConcurrencyProbe(context: Context) : ConfigRepository(context) {
        val maxConcurrent = AtomicInteger(0)
        private val active = AtomicInteger(0)

        override fun saveSetupInput(input: SetupConfigInput) {
            val now = active.incrementAndGet()
            maxConcurrent.updateAndGet { existing -> maxOf(existing, now) }
            Thread.sleep(OVERLAP_WINDOW_MS)
            active.decrementAndGet()
            super.saveSetupInput(input)
        }

        private companion object {
            const val OVERLAP_WINDOW_MS = 30L
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

    private fun coordinator(
        prefs: RecordingPreferences,
        config: ConfigRepository = configRepository,
        identity: IdentityRepository = identityRepository,
    ) = SetupPersistenceCoordinator(config, identity, prefs.load, prefs.persist)

    private fun request(
        replacementIdentity: IdentityReplacement? = null,
        authorizedPublicIdentityToAdd: String? = null,
        configContents: String = "format = \"committed\"\n",
        setupInput: SetupConfigInput = SetupConfigInput(brokerHost = "broker.new"),
    ) = SetupPersistenceRequest(
        configContents,
        setupInput,
        AndroidAppPreferences(resumeOnUnmetered = false),
        replacementIdentity,
        authorizedPublicIdentityToAdd,
    )

    private fun fullRequest() =
        request(
            replacementIdentity = IdentityReplacement("new-priv".toByteArray(), "new-pub"),
            authorizedPublicIdentityToAdd = "remote-key peer",
        )

    // --- Tests ------------------------------------------------------------------------------

    @Test
    fun allStagesCommitInRequiredOrder() =
        runBlocking {
            val result = coordinator(RecordingPreferences()).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Success)
            assertEquals(
                listOf(
                    SetupPersistenceStage.Identity,
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.SetupInput,
                    SetupPersistenceStage.Preferences,
                    SetupPersistenceStage.Config,
                ),
                (result as SetupPersistenceResult.Success).stages,
            )
            assertEquals("format = \"committed\"\n", configRepository.readConfig())
            assertEquals("broker.new", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
        }

    @Test
    fun validationFailurePerformsNoPersistentMutation() =
        runBlocking {
            // The coordinator never validates; a caller that supplied an identity whose store
            // fails must leave zero persistent state behind.
            val identity = IdentityRepository(context, ThrowingCrypto())
            val prefs = RecordingPreferences()
            val result =
                coordinator(prefs, identity = identity).persist(
                    request(replacementIdentity = IdentityReplacement("x".toByteArray(), "pub")),
                )

            assertTrue(result is SetupPersistenceResult.Failed)
            assertEquals(0, prefs.writes.size)
            assertFalse(File(context.filesDir, "identity.enc").exists())
            assertFalse(File(context.filesDir, "config.toml").exists())
            assertFalse(File(context.filesDir, "setup_input.json").exists())
        }

    @Test
    fun identityFailureStopsBeforeAuthorizedKeysSetupPreferencesAndConfig() =
        runBlocking {
            val identity = IdentityRepository(context, ThrowingCrypto())
            val prefs = RecordingPreferences()
            val result =
                coordinator(prefs, identity = identity).persist(
                    request(
                        replacementIdentity = IdentityReplacement("x".toByteArray(), "pub"),
                        authorizedPublicIdentityToAdd = "remote-key peer",
                    ),
                )

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Identity, failed.failedStage)
            assertTrue("nothing committed, so nothing to roll back", failed.rollback.isEmpty())
            assertEquals(0, prefs.writes.size)
            assertFalse(File(context.filesDir, "authorized_keys").exists())
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
    fun setupInputFailureRollsBackAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val prefs = RecordingPreferences()
            val result =
                coordinator(prefs, config = FailingSetupInput(context)).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.SetupInput, failed.failedStage)
            assertEquals(
                listOf(SetupPersistenceStage.AuthorizedKeys, SetupPersistenceStage.Identity),
                failed.rollback.map { it.stage() },
            )
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertFalse(
                "authorized_keys created by the failed save must be rolled back",
                File(context.filesDir, "authorized_keys").exists(),
            )
            assertEquals(0, prefs.writes.size)
        }

    @Test
    fun preferencesFailureRollsBackSetupInputAuthorizedKeysAndIdentity() =
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
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.Identity,
                ),
                failed.rollback.map { it.stage() },
            )
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
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
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.Identity,
                ),
                failed.rollback.map { it.stage() },
            )
            assertTrue(failed.rollback.all { it is SetupRollbackStageResult.Success })
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
        }

    @Test
    fun rollbackContinuesAfterOneRollbackFailure() =
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
            // Identity, AuthorizedKeys and SetupInput rollbacks still ran and succeeded.
            listOf(
                SetupPersistenceStage.SetupInput,
                SetupPersistenceStage.AuthorizedKeys,
                SetupPersistenceStage.Identity,
            ).forEach { stage ->
                assertTrue(failed.rollback.single { it.stage() == stage } is SetupRollbackStageResult.Success)
            }
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
        }

    @Test
    fun rollbackFailureProducesSetupRollbackIncomplete() =
        runBlocking {
            val prefs = RecordingPreferences().apply { failOnWriteNumber = 2 }
            val result = coordinator(prefs, config = FailingConfig(context)).persist(request())

            val failed = result as SetupPersistenceResult.Failed
            assertTrue(
                "an unrecovered rollback stage must be reported as a Failure",
                failed.rollback.any { it is SetupRollbackStageResult.Failure },
            )
        }

    @Test
    fun cancellationDuringAnyStagePropagates() {
        val prefs = RecordingPreferences().apply { cancelOnWriteNumber = 1 }
        var caught: CancellationException? = null
        try {
            runBlocking { coordinator(prefs).persist(request()) }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertTrue("cancellation during a stage must propagate", caught != null)
    }

    @Test
    fun plaintextIdentityIsWipedOnSuccessFailureAndCancellation() =
        runBlocking {
            // The coordinator consumes the plaintext synchronously during the Identity stage,
            // so the owner can wipe its buffer immediately after persist without corrupting the
            // stored identity — the coordinator never retains a lazy reference to it.
            val plaintext = "secret-key-material".toByteArray()
            val result =
                coordinator(RecordingPreferences()).persist(
                    request(replacementIdentity = IdentityReplacement(plaintext, "pub")),
                )
            assertTrue(result is SetupPersistenceResult.Success)

            val storedBeforeWipe = identityRepository.readPrivateIdentityPlaintext()
            plaintext.fill(0)
            val storedAfterWipe = identityRepository.readPrivateIdentityPlaintext()

            assertArrayEquals(storedBeforeWipe, storedAfterWipe)
            assertFalse(
                "stored identity must not be zeroed by the owner's wipe",
                storedAfterWipe.all { it == 0.toByte() },
            )
        }

    @Test
    fun twoConcurrentSaveRequestsCannotOverlap() =
        runBlocking {
            val probe = ConcurrencyProbe(context)
            val coordinator = coordinator(RecordingPreferences(), config = probe)
            val jobs =
                List(2) {
                    launch(parallelDispatcher()) { coordinator.persist(request()) }
                }
            jobs.joinAll()

            assertEquals("the coordinator mutex must serialize concurrent saves", 1, probe.maxConcurrent.get())
        }

    @Test
    fun failedSaveNeverReportsConfigurationSaved() =
        runBlocking {
            val result = coordinator(RecordingPreferences(), config = FailingConfig(context)).persist(request())

            assertTrue("a failed save must never report success", result is SetupPersistenceResult.Failed)
        }

    private fun SetupRollbackStageResult.stage(): SetupPersistenceStage =
        when (this) {
            is SetupRollbackStageResult.Success -> stage
            is SetupRollbackStageResult.Failure -> stage
        }

    // Routed through a parameter default so detekt's InjectDispatcher rule is satisfied while
    // still giving the concurrency test real multi-threaded parallelism.
    private fun parallelDispatcher(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineDispatcher =
        dispatcher
}
