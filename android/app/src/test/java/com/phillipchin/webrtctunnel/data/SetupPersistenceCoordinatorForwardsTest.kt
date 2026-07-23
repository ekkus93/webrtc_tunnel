package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
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

/**
 * FIX8 P0-004-D/G: the setup transaction's `Forwards` stage — full-draft commit before Config,
 * exact-byte rollback (disk + list + load state), and distinct identity-pair/authorized-keys
 * restore members. Split from [SetupPersistenceCoordinatorTest] to keep that file under the
 * repo's file-size guidance.
 */
@RunWith(RobolectricTestRunner::class)
class SetupPersistenceCoordinatorForwardsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var configRepository: ConfigRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var brokerSecretRepository: BrokerSecretRepository
    private val forwardsFile = File(context.filesDir, "forwards.json")

    @Before
    fun setUp() =
        runBlocking {
            listOf("config.toml", "setup_input.json", "identity.enc", "identity.pub", "authorized_keys").forEach {
                File(context.filesDir, it).delete()
            }
            forwardsFile.delete()
            configRepository = ConfigRepository(context)
            identityRepository = IdentityRepository(context, PlaintextCrypto())
            brokerSecretRepository = BrokerSecretRepository(context, permissionEnforcer = RecordingPermissionEnforcer())
        }

    private class PlaintextCrypto : IdentityCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
    }

    private fun forward(
        id: String,
        port: Int,
    ) = ForwardConfig(id = id, name = id, localPort = port, remoteForwardId = id, enabled = true)

    /** Delegates every call to [real] except [saveForwards], which fails on its [failOn]-th call
     * (1-indexed) — used to make the Forwards stage's initial apply fail while leaving a later
     * rollback-restore call to the real implementation, or vice versa. */
    private class FailingOnNthSaveStore(
        private val real: ForwardsStore,
        private val failOn: Int,
    ) : ForwardsStore by real {
        private var callCount = 0

        override fun saveForwards(forwards: List<ForwardConfig>) {
            callCount++
            if (callCount == failOn) throw IOException("simulated forwards save failure #$failOn")
            real.saveForwards(forwards)
        }
    }

    /** Delegates every call to [real] except [restoreExactSnapshot], which always fails — drives
     * a Forwards-stage rollback-restore failure while the initial apply succeeded normally. */
    private class RestoreAlwaysFailsStore(
        private val real: ForwardsStore,
    ) : ForwardsStore by real {
        override fun restoreExactSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
            Result.failure(IOException("simulated forwards restore failure"))
    }

    private fun forwardsRepository(store: ForwardsStore = ForwardsConfigStore(context)): ForwardsRepository =
        ForwardsRepository(store, AppDispatchers()).also { runBlocking { it.refresh() } }

    private class RecordingPreferences {
        var stored: AndroidAppPreferences = AndroidAppPreferences()
        val load: suspend () -> AndroidAppPreferences = { stored }
        val persist: suspend (AndroidAppPreferences) -> Result<Unit> = { prefs ->
            stored = prefs
            Result.success(Unit)
        }
    }

    private fun coordinator(
        forwards: ForwardsRepository,
        config: ConfigRepository = configRepository,
        prefs: RecordingPreferences = RecordingPreferences(),
    ) = SetupPersistenceCoordinator(
        config,
        identityRepository,
        brokerSecretRepository,
        forwards,
        prefs.load,
        prefs.persist,
    )

    private fun request(forwardsDraft: List<ForwardConfig>) =
        SetupPersistenceRequest(
            configContents = "format = \"committed\"\n",
            setupInput = SetupConfigInput(brokerHost = "broker.new"),
            preferences = AndroidAppPreferences(),
            forwards = forwardsDraft,
            optionalChanges = SetupOptionalChanges(null, null, null),
        )

    private fun SetupRollbackStageResult.stage(): SetupPersistenceStage =
        when (this) {
            is SetupRollbackStageResult.Success -> stage
            is SetupRollbackStageResult.Failure -> stage
        }

    @Test
    fun setupCommitsFullDraftForwardsBeforeConfig() =
        runBlocking {
            val forwardsRepo = forwardsRepository()
            val draft = listOf(forward("ssh", 2222), forward("disabled", 3333).copy(enabled = false))

            val result = coordinator(forwardsRepo).persist(request(draft))

            assertTrue(result is SetupPersistenceResult.Success)
            val stages = (result as SetupPersistenceResult.Success).stages
            assertTrue(
                "Forwards must commit before Config",
                stages.indexOf(SetupPersistenceStage.Forwards) < stages.indexOf(SetupPersistenceStage.Config),
            )
            assertEquals(draft, forwardsRepo.current())
            assertEquals(draft, ForwardsConfigStore(context).loadForwardsResult().getOrThrow())
        }

    @Test
    fun forwardsFailureRollsBackCurrentStageAndEveryEarlierSetupStage() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val failingForwards = forwardsRepository(FailingOnNthSaveStore(realStore, failOn = 1))

            val requestWithIdentity =
                SetupPersistenceRequest(
                    configContents = "format = \"committed\"\n",
                    setupInput = SetupConfigInput(brokerHost = "broker.new"),
                    preferences = AndroidAppPreferences(),
                    forwards = listOf(forward("new", 2222)),
                    optionalChanges =
                        SetupOptionalChanges(
                            replacementIdentity = IdentityReplacement("next-priv".toByteArray(), "next-pub"),
                            authorizedPublicIdentityToAdd = null,
                            brokerSecretChange = null,
                        ),
                )

            val result = coordinator(failingForwards).persist(requestWithIdentity)

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Forwards, failed.failedStage)
            assertEquals(
                listOf(
                    SetupPersistenceStage.Forwards,
                    SetupPersistenceStage.Preferences,
                    SetupPersistenceStage.SetupInput,
                    SetupPersistenceStage.Identity,
                ),
                failed.rollback.map { it.stage() },
            )
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals(listOf(forward("prior", 1111)), failingForwards.current())
        }

    @Test
    fun configFailureRestoresExactForwardsBytesListLoadStateAndEarlierStages() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository(realStore)
            val priorBytes = forwardsFile.readBytes()

            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        Result.failure(IOException("config commit failed"))
                }

            val result =
                coordinator(forwardsRepo, config = failingConfig).persist(request(listOf(forward("new", 2222))))

            assertTrue(result is SetupPersistenceResult.Failed)
            assertEquals(SetupPersistenceStage.Config, (result as SetupPersistenceResult.Failed).failedStage)
            assertArrayEquals(
                "Config failing after Forwards committed must restore forwards' exact prior bytes",
                priorBytes,
                forwardsFile.readBytes(),
            )
            assertEquals(listOf(forward("prior", 1111)), forwardsRepo.current())
        }

    @Test
    fun cancellationDuringForwardsRestoresCurrentForwardsStageAndEarlierStages() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val cancellingForwards =
                forwardsRepository(
                    object : ForwardsStore by realStore {
                        override fun saveForwards(forwards: List<ForwardConfig>): Unit =
                            throw CancellationException("forwards write cancelled")
                    },
                )

            var caught: CancellationException? = null
            try {
                // Captured to satisfy detekt's IgnoredReturnValue — cancellation always
                // throws before persist() can actually return here.
                val result = coordinator(cancellingForwards).persist(request(listOf(forward("new", 2222))))
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Forwards must propagate", caught != null)
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals(listOf(forward("prior", 1111)), cancellingForwards.current())
        }

    @Test
    fun cancellationDuringConfigRestoresForwardsAndEveryEarlierStage() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository(realStore)
            val priorBytes = forwardsFile.readBytes()
            val cancellingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        throw CancellationException("config write cancelled")
                }

            var caught: CancellationException? = null
            try {
                // Captured to satisfy detekt's IgnoredReturnValue — cancellation always
                // throws before persist() can actually return here.
                val result =
                    coordinator(forwardsRepo, config = cancellingConfig).persist(request(listOf(forward("new", 2222))))
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Config must propagate", caught != null)
            assertArrayEquals(priorBytes, forwardsFile.readBytes())
            assertEquals(listOf(forward("prior", 1111)), forwardsRepo.current())
        }

    @Test
    fun setupForwardsRollbackFailureIsListedAndDurable() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository(RestoreAlwaysFailsStore(realStore))
            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        Result.failure(IOException("config commit failed"))
                }

            val result =
                coordinator(forwardsRepo, config = failingConfig).persist(request(listOf(forward("new", 2222))))

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            val forwardsRollback = failed.rollback.single { it.stage() == SetupPersistenceStage.Forwards }
            assertTrue(
                "a Forwards rollback failure must be reported, not hidden",
                forwardsRollback is SetupRollbackStageResult.Failure,
            )
        }

    @Test
    fun setupIdentityAndAuthorizedKeysRollbackUseDistinctRestoreMembers() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            identityRepository.appendAuthorizedPublicIdentity("prior-key peerA").getOrThrow()
            val forwardsRepo = forwardsRepository()
            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        Result.failure(IOException("config commit failed"))
                }
            val requestWithIdentityAndKey =
                SetupPersistenceRequest(
                    configContents = "format = \"committed\"\n",
                    setupInput = SetupConfigInput(brokerHost = "broker.new"),
                    preferences = AndroidAppPreferences(),
                    forwards = emptyList(),
                    optionalChanges =
                        SetupOptionalChanges(
                            replacementIdentity = IdentityReplacement("new-priv".toByteArray(), "new-pub"),
                            authorizedPublicIdentityToAdd = "new-key peerB",
                            brokerSecretChange = null,
                        ),
                )

            val result = coordinator(forwardsRepo, config = failingConfig).persist(requestWithIdentityAndKey)

            assertTrue(result is SetupPersistenceResult.Failed)
            // If Identity's rollback used the full-triplet restoreStorageSnapshot, it would
            // restore authorized_keys to ITS pre-transaction state too (absent, since no key was
            // appended before this transaction). Confirming the appended key is what AuthorizedKeys'
            // OWN restore removed — not a side effect of Identity's restore also touching the
            // file — establishes each stage restored only its own file(s).
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("prior-pub", identityRepository.readPublicIdentity())
            assertEquals(
                listOf("prior-key peerA"),
                File(context.filesDir, "authorized_keys").readLines(),
            )
            assertFalse(File(context.filesDir, "config.toml").exists())
        }
}
