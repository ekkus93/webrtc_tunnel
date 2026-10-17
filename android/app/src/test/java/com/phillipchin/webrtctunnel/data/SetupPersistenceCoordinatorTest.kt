package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * FIX6 P0-003 / FIX7 P0-004: setup persistence must be transactional — config committed last,
 * partial mutation rolled back in reverse order, rollback failures reported individually, and
 * success only when every requested stage commits. This class covers stage order and ordinary
 * (non-cancellation) failure; the sibling classes cover cancellation, snapshot wiping and
 * concurrency, and share their fixtures via [SetupPersistenceCoordinatorTestBase].
 */
@RunWith(RobolectricTestRunner::class)
internal class SetupPersistenceCoordinatorTest : SetupPersistenceCoordinatorTestBase() {
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
                    SetupPersistenceStage.Forwards,
                    SetupPersistenceStage.Config,
                ),
                (result as SetupPersistenceResult.Success).stages,
            )
            assertEquals("format = \"committed\"\n", configRepository.configContents)
            assertEquals("broker.new", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertEquals("new-broker-secret", passwordFile.readText())
        }

    @Test
    fun snapshotFailurePerformsZeroMutationIncludingCurrentStage() =
        runBlocking {
            val prefs = RecordingPreferences()
            val result = coordinator(prefs, config = FailingSnapshotConfig(context)).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            assertEquals(SetupPersistenceStage.Snapshot, (result as SetupPersistenceResult.Failed).failedStage)
            assertTrue("nothing committed, so nothing to roll back", result.rollback.isEmpty())
            assertEquals(0, prefs.writes.size)
            assertFalse(File(context.filesDir, "identity.enc").exists())
            assertFalse(File(context.filesDir, "identity.pub").exists())
            assertFalse(File(context.filesDir, "authorized_keys").exists())
            assertFalse(passwordFile.exists())
            assertFalse(
                "the Snapshot stage itself must not create setup_input.json",
                File(context.filesDir, "setup_input.json").exists(),
            )
            assertFalse(
                "the Snapshot stage itself must not create config.toml",
                File(context.filesDir, "config.toml").exists(),
            )
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
            // FIX8 P0-003-D: the failing stage itself is now included in rollback (added to
            // `attempted` before its own apply runs) — its restore is idempotent since nothing
            // was actually mutated before the failure.
            assertEquals(listOf(SetupPersistenceStage.Identity), failed.rollback.map { it.stage() })
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
            // FIX8 P0-003-D: the failing stage itself is now included in rollback too.
            assertEquals(
                listOf(SetupPersistenceStage.AuthorizedKeys, SetupPersistenceStage.Identity),
                failed.rollback.map { it.stage() },
            )
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
            // FIX8 P0-003-D: the failing stage itself is now included in rollback too.
            assertEquals(
                listOf(
                    SetupPersistenceStage.BrokerSecret,
                    SetupPersistenceStage.AuthorizedKeys,
                    SetupPersistenceStage.Identity,
                ),
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
            // FIX8 P0-003-D: the failing stage itself is now included in rollback too.
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
            // FIX8 P0-003-D: the failing stage itself is now included in rollback too.
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
            assertArrayEquals("prior-priv".toByteArray(), identityRepository.readPrivateIdentityPlaintext())
            assertEquals("broker.prior", configRepository.loadSetupInputResult().getOrThrow().brokerHost)
            assertFalse("broker secret created by the failed save must be rolled back", passwordFile.exists())
        }

    @Test
    fun configFailureRollsBackEveryEarlierStage() =
        runBlocking {
            configRepository.writeConfig("format = \"prior\"\n").getOrThrow()
            configRepository.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val prefs = RecordingPreferences()

            val result = coordinator(prefs, config = FailingConfig(context)).persist(fullRequest())

            assertTrue(result is SetupPersistenceResult.Failed)
            val failed = result as SetupPersistenceResult.Failed
            assertEquals(SetupPersistenceStage.Config, failed.failedStage)
            // FIX8 P0-003-D: the failing stage itself is now included in rollback too.
            assertEquals(
                listOf(
                    SetupPersistenceStage.Config,
                    SetupPersistenceStage.Forwards,
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

    @Test
    fun failedSaveNeverReportsConfigurationSaved() =
        runBlocking {
            val result = coordinator(RecordingPreferences(), config = FailingConfig(context)).persist(request())

            assertTrue("a failed save must never report success", result is SetupPersistenceResult.Failed)
        }
}
