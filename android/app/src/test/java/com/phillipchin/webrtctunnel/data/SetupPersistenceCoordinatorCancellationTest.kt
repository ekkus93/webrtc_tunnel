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
 * FIX7 P0-004: cancellation at any stage must propagate to the caller, but only after every
 * stage already committed has been rolled back (in reverse order), and a rollback that itself
 * fails must not stop the remaining rollbacks. One focused test per meaningful cancellation
 * point. Ordinary (non-cancellation) failures are in [SetupPersistenceCoordinatorTest].
 */
@RunWith(RobolectricTestRunner::class)
internal class SetupPersistenceCoordinatorCancellationTest : SetupPersistenceCoordinatorTestBase() {
    @Test
    fun cancellationBeforeFirstMutationPerformsNoRollbackAndPropagates() =
        runBlocking {
            val identity = IdentityRepository(context, CancellingCrypto())

            val caught =
                persistExpectingCancellation(
                    coordinator(RecordingPreferences(), identity = identity),
                    during = "the first stage",
                )

            assertTrue(
                "nothing committed, so no rollback exceptions should be attached",
                caught.suppressedExceptions.isEmpty(),
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

            persistExpectingCancellation(
                coordinator(RecordingPreferences(), identity = identity),
                during = "AuthorizedKeys",
            )

            assertArrayEquals("prior-priv".toByteArray(), identity.readPrivateIdentityPlaintext())
            assertEquals("prior-pub", identity.readPublicIdentity())
        }

    @Test
    fun cancellationDuringBrokerSecretRollsBackAuthorizedKeysAndIdentity() =
        runBlocking {
            identityRepository.storeEncryptedIdentity("prior-priv".toByteArray(), "prior-pub")
            val cancellingBrokerSecret = brokerSecretRepositoryCancellingReplace(context)

            persistExpectingCancellation(
                coordinator(RecordingPreferences(), brokerSecret = cancellingBrokerSecret),
                during = "BrokerSecret",
            )

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

            persistExpectingCancellation(
                coordinator(RecordingPreferences(), config = CancellingSetupInput(context)),
                during = "SetupInput",
            )

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

            persistExpectingCancellation(coordinator(prefs), during = "Preferences")

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

            persistExpectingCancellation(coordinator(prefs, config = CancellingConfig(context)), during = "Config")

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

            persistExpectingCancellation(coordinator(prefs, config = CancellingConfig(context)), during = "Config")

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

            val caught =
                persistExpectingCancellation(coordinator(prefs, config = CancellingConfig(context)), during = "Config")

            val rollbackFailures = caught.suppressedExceptions.filterIsInstance<SetupRollbackException>()
            assertEquals(1, rollbackFailures.size)
            assertEquals(SetupPersistenceStage.Preferences, rollbackFailures.single().stage)
        }
}
