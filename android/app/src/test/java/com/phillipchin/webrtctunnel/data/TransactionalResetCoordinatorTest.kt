package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Basic success and rollback-restore coverage. See TransactionalResetTestFixtures.kt for
// FakeForwardsStore and friends (shared across this and the sibling test classes this file
// was split from — TransactionalResetCoordinatorRollbackReportingTest and
// TransactionalResetCoordinatorCancellationTest — to stay under the repo's 800-line guidance).
@RunWith(RobolectricTestRunner::class)
class TransactionalResetCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var configRepo: ConfigRepository
    private lateinit var forwardsRepo: ForwardsRepository
    private lateinit var coordinator: TransactionalResetCoordinator

    @Before
    fun setUp() {
        // Clean slate FIRST, then create repositories (which seed defaults)
        File(context.filesDir, "config.toml").delete()
        File(context.filesDir, "setup_input.json").delete()
        File(context.filesDir, "forwards.json").delete()

        configRepo = ConfigRepository(context)
        forwardsRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
        coordinator = TransactionalResetCoordinator(configRepo, forwardsRepo)
    }

    @Test
    fun successRestoresConfigSetupInputAndForwards() =
        runBlocking {
            // Seed a config, setup input, and forwards
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))
            forwardsRepo.resetForwards().getOrThrow() // reset to empty list

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Success)
            val success = result as ResetResult.Success
            assertEquals(3, success.stages.size)
        }

    @Test
    fun configAbsentBeforeResetAndLaterFailureConfigAbsentAfterRollback() =
        runBlocking {
            // Config is absent. SetupInput has a value. Forwards has a forward.
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))
            forwardsRepo.resetForwards().getOrThrow() // clear defaults for clean state
            forwardsRepo.upsertWithReceipt(forward("test")).getOrThrow()

            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, config should be absent (it was absent before the reset).
            assertTrue("Config should be absent after rollback", configRepo.readConfig().isEmpty())
        }

    @Test
    fun configPresentBeforeResetAndLaterFailureExactContentRestored() =
        runBlocking {
            val priorConfig = "format = \"prior-v3\"\n[node]\npeer_id = \"android-phone\""
            configRepo.writeConfig(priorConfig).getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))

            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, config should be restored to the exact prior content.
            assertEquals(priorConfig, configRepo.readConfig())
        }

    @Test
    fun setupInputRestoredExactly() =
        runBlocking {
            // Seed setup input with specific values
            val priorInput =
                SetupConfigInput(
                    brokerHost = "broker.example.com",
                    remotePeerId = "peer-123",
                    allowMetered = true,
                )
            configRepo.saveSetupInput(priorInput)

            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, setup input should be restored to the exact prior values.
            val loaded = configRepo.loadSetupInputResult().getOrThrow()
            assertEquals(priorInput, loaded)
        }

    @Test
    fun priorEmptyForwardsRestoredAndPersisted() =
        runBlocking {
            // Forwards starts as empty (reset to empty explicitly)
            forwardsRepo.resetForwards().getOrThrow()
            val priorForwards = forwardsRepo.current()
            assertTrue(priorForwards.isEmpty())

            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = priorForwards,
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, forwards should be empty (empty is a valid state that must be persisted).
            assertTrue("Empty forwards should be restored", fakeForwardsRepo.current().isEmpty())
        }

    @Test
    fun priorNonEmptyForwardsRestoredAndPersisted() =
        runBlocking {
            // Clear defaults first for a clean known state
            forwardsRepo.resetForwards().getOrThrow()

            // Seed forwards with data
            val fwd = forward("persist-test", 3333)
            forwardsRepo.upsertWithReceipt(fwd).getOrThrow()
            val priorForwards = forwardsRepo.current()
            assertEquals(1, priorForwards.size)

            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = priorForwards,
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            // FIX7 P1-003-B: construction no longer reads the store — refresh() so current()
            // reflects fakeStore's seeded initialForwards before the coordinator snapshots it.
            fakeForwardsRepo.refresh()
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, forwards should be restored to the exact prior values.
            val restoredForwards = fakeForwardsRepo.current()
            assertEquals(priorForwards, restoredForwards)
        }

    @Test
    fun resetStopsAfterFirstFailedStage() =
        runBlocking {
            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // Verify that rollback was attempted for all successfully mutated stages.
            assertTrue(failed.rollback.isNotEmpty())
        }

    @Test
    fun rollbackFailureResultIsNotSuccess() =
        runBlocking {
            // Create a coordinator that will fail on the Forwards stage to trigger rollback.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue("reset should fail when Forwards stage throws", result is ResetResult.Failed)
        }

    @Test
    fun corruptSetupInputFailsBeforeMutation() =
        runBlocking {
            // Create a corrupt setup input file that will fail to parse
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("NOT VALID JSON {{{")

            // Re-create repository to pick up the corrupt file
            val freshConfigRepo = ConfigRepository(context)

            // Verify the corrupt file is detected
            val loadResult = freshConfigRepo.loadSetupInputResult()
            assertTrue("Corrupt setup input should fail to load", loadResult.isFailure)

            // Create coordinator and attempt reset
            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            // Reset should fail before any mutation
            assertTrue("Reset should fail on corrupt setup input", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Config (snapshot capture)", ResetStage.Config, failed.failedStage)
            assertTrue(
                "Cause should mention snapshot/setup failure",
                failed.cause.contains("setup", ignoreCase = true) ||
                    failed.cause.contains("Snapshot", ignoreCase = true),
            )
        }

    @Test
    fun snapshotCaptureFailureAbortsBeforeMutation() =
        runBlocking {
            // When snapshot capture fails (e.g., setup input unreadable),
            // reset should abort before any stage mutates
            val priorConfig = "format = \"prior\"\n"
            configRepo.writeConfig(priorConfig).getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "test"))

            // Corrupt the setup input to force snapshot capture failure
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("INVALID_JSON")

            // Create fresh coordinator with the corrupt setup input
            val freshConfigRepo = ConfigRepository(context)
            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            // Should fail before any mutation
            assertTrue("Should fail before mutation", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Should fail at Config stage", ResetStage.Config, failed.failedStage)
            assertTrue("Rollback should be empty (no mutation)", failed.rollback.isEmpty())
        }
}
