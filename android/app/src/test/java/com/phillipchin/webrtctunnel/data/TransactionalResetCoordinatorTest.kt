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
        forwardsRepo =
            ForwardsRepository(ForwardsConfigStore(context), AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, config should be absent (it was absent before the reset).
            assertTrue("Config should be absent after rollback", configRepo.configContents.isEmpty())
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)

            // After rollback, config should be restored to the exact prior content.
            assertEquals(priorConfig, configRepo.configContents)
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail with Forwards as the failed stage.
            assertTrue("reset should fail when Forwards stage throws", result is ResetResult.Failed)
        }

    // FIX8 P0-006-A/D: corrupt setup_input.json is captured/restored as raw bytes, with no
    // parsing required — a corrupt draft must not block a reset the user needs precisely to
    // escape that corruption (CRITICAL-2/HIGH-5), and reset must repair it to known defaults.
    @Test
    fun corruptSetupInputDoesNotPreventReset() =
        runBlocking {
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("NOT VALID JSON {{{")

            // Verify the file really is corrupt (would fail to parse) — proving reset
            // succeeding below is despite the corruption, not because it was never corrupt.
            val freshConfigRepo = ConfigRepository(context)
            assertTrue(
                "the setup input file must genuinely be corrupt for this test to be meaningful",
                freshConfigRepo.loadSetupInputResult().isFailure,
            )

            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            assertTrue("reset must succeed despite a corrupt setup input draft", result is ResetResult.Success)
            assertEquals(
                "reset must repair the corrupt draft to known defaults",
                SetupConfigInput(),
                freshConfigRepo.loadSetupInputResult().getOrThrow(),
            )
        }
}
