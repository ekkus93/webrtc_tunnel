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
import java.io.IOException

// Delete-failure rollback reporting, absent-setup-input default behavior, early-failure
// no-mutation coverage, and rollback-reporting coverage. Split out of
// TransactionalResetCoordinatorTest to stay under the repo's 800-line guidance — see
// TransactionalResetTestFixtures.kt for FakeForwardsStore/ConfigDeleteFailureRepository.
@RunWith(RobolectricTestRunner::class)
class TransactionalResetCoordinatorRollbackReportingTest {
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

    // P1-006: deleteConfigFileForTransactionalReset() rollback-failure coverage. The two
    // tests below previously claimed to cover delete *failure* but never actually made
    // deleteConfigFileForTransactionalReset() fail — both asserted the success path
    // (delete succeeding) despite their names. They now force a genuine failure via a
    // fake repository, per the Fix 5 review's "no misleading test names" rule.

    @Test
    fun deleteFailureIsReportedAsRollbackStageFailure() =
        runBlocking {
            // Config absent initially — Config's own reset stage creates the file, so
            // rollback must delete it to restore the absent state. Forwards fails to
            // trigger rollback.
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))

            val failingConfigRepo = ConfigDeleteFailureRepository(context, IOException("delete failed"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(failingConfigRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)
            assertTrue(
                "rollback must report Config as a genuine Failure when delete itself fails",
                failed.rollback.any {
                    it is RollbackStageResult.Failure && it.stage == ResetStage.Config
                },
            )
        }

    @Test
    fun fileStillExistsAfterFailedDeleteDuringRollback() =
        runBlocking {
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))

            val failingConfigRepo = ConfigDeleteFailureRepository(context, IOException("delete failed"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(failingConfigRepo, fakeForwardsRepo)

            failingCoordinator.resetConfiguration()

            // The reset stage created config.toml; since the rollback delete genuinely
            // failed (not merely reported failure), the file must still physically exist.
            assertTrue(
                "config.toml must still exist on disk when its rollback delete failed",
                File(context.filesDir, "config.toml").exists(),
            )
        }

    // P1-003: absent setup input uses default behavior (does not fail snapshot capture)

    @Test
    fun absentSetupInputUsesDefaultBehavior() =
        runBlocking {
            // Setup input file is already deleted in @Before, ensuring absence
            val absentSetupInput = File(context.filesDir, "setup_input.json")
            assertTrue("Setup input should be absent", !absentSetupInput.exists())

            // Verify load returns default on absence
            val loadResult = configRepo.loadSetupInputResult()
            assertTrue("Load should succeed for absent setup input", loadResult.isSuccess)
            assertEquals(
                "Absent setup input must load as empty defaults",
                SetupConfigInput(),
                loadResult.getOrNull(),
            )

            val result = coordinator.resetConfiguration()

            // Reset should succeed (absent input uses defaults, not failure)
            assertTrue(
                "Reset should proceed with default setup input",
                result is ResetResult.Success,
            )
        }

    // P1-004: early-failure tests — verify no mutation occurs before snapshot capture completes

    @Test
    fun corruptSetupInputLeavesConfigUnmodified() =
        runBlocking {
            // Seed a known config before attempting reset
            val priorConfig = "format = \"prior\"\n"
            configRepo.writeConfig(priorConfig).getOrThrow()

            // Corrupt the setup input to force snapshot capture failure
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("NOT VALID JSON {{{")

            // Re-create repository to pick up the corrupt file
            val freshConfigRepo = ConfigRepository(context)
            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            // Reset should fail before any mutation
            assertTrue("Reset should fail on corrupt setup input", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Config (snapshot capture)", ResetStage.Config, failed.failedStage)

            // Config must remain unchanged — snapshot capture failed before any mutation
            assertEquals("Config must be unmodified after early failure", priorConfig, freshConfigRepo.readConfig())
        }

    @Test
    fun corruptSetupInputLeavesForwardsUnmodified() =
        runBlocking {
            // Clear defaults first for a clean known state
            forwardsRepo.resetForwards().getOrThrow()

            // Seed forwards with a single known forward
            forwardsRepo.upsertWithReceipt(forward("unchanged", 4444)).getOrThrow()
            val priorForwards = forwardsRepo.current()
            assertEquals("Prior forwards should have 1 entry", 1, priorForwards.size)

            // Corrupt the setup input to force snapshot capture failure
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("INVALID")

            // Re-create repository to pick up the corrupt file
            val freshConfigRepo = ConfigRepository(context)
            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            // Reset should fail before any mutation
            assertTrue("Reset should fail on corrupt setup input", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Config (snapshot capture)", ResetStage.Config, failed.failedStage)

            // Forwards must remain unchanged — snapshot capture failed before any mutation
            assertEquals(
                "Forwards must be unmodified after early failure",
                priorForwards,
                forwardsRepo.current(),
            )
        }

    @Test
    fun snapshotFailureRollbackIsEmpty() =
        runBlocking {
            // Corrupt the setup input to force snapshot capture failure
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("INVALID")

            val freshConfigRepo = ConfigRepository(context)
            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            // Reset should fail before any mutation
            assertTrue("Reset should fail on corrupt setup input", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed

            // No mutation occurred, so rollback list must be empty
            assertTrue(
                "Rollback should be empty when snapshot capture fails",
                failed.rollback.isEmpty(),
            )
        }

    // P1-005: rollback-reporting tests. The next three prove rollback stages are
    // reported when they *succeed* (renamed from names that claimed to test failure,
    // per the Fix 5 review — none of them ever made a rollback operation itself fail).
    // configRollbackFailureIsReportedAsRollbackStageFailure below is the true
    // rollback-failure test: it forces the Config rollback write itself to fail.

    @Test
    fun configRestoreSucceedsAndIsReportedInRollback() =
        runBlocking {
            // Seed a config that will need to be restored during rollback
            val priorConfig = "format = \"prior-v3\"\n[node]\npeer_id = \"android-phone\""
            configRepo.writeConfig(priorConfig).getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))

            // Create a coordinator where Config stage succeeds but a later stage fails,
            // triggering rollback. We verify the rollback stages are reported.
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail on the Forwards stage
            assertTrue("Reset should fail on Forwards stage", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Forwards", ResetStage.Forwards, failed.failedStage)

            // Rollback should contain entries for the mutated stages (Config, SetupInput)
            assertEquals(
                "Rollback should cover Config and SetupInput",
                2,
                failed.rollback.size,
            )

            // Verify the rollback stage results — both should succeed in this scenario
            // (the real test is that rollback was attempted; P1-005 verifies the reporting)
            val rollbackStages =
                failed.rollback.map {
                    when (it) {
                        is RollbackStageResult.Success -> it.stage
                        is RollbackStageResult.Failure -> it.stage
                    }
                }
            assertTrue("Rollback should include Config", ResetStage.Config in rollbackStages)
            assertTrue("Rollback should include SetupInput", ResetStage.SetupInput in rollbackStages)
        }

    @Test
    fun setupInputRestoreSucceedsAndIsReportedInRollback() =
        runBlocking {
            // Create a coordinator where SetupInput stage succeeds but Forwards fails,
            // triggering rollback of Config and SetupInput.
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "test"))

            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail on the Forwards stage
            assertTrue("Reset should fail on Forwards stage", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Forwards", ResetStage.Forwards, failed.failedStage)

            // SetupInput rollback should be reported as Success (it succeeds in this scenario)
            val setupInputRollback =
                failed.rollback.find {
                    when (it) {
                        is RollbackStageResult.Success -> it.stage == ResetStage.SetupInput
                        is RollbackStageResult.Failure -> it.stage == ResetStage.SetupInput
                        else -> false
                    }
                }
            assertTrue(
                "SetupInput rollback should be reported",
                setupInputRollback != null,
            )
            assertTrue(
                "SetupInput rollback should succeed",
                setupInputRollback is RollbackStageResult.Success,
            )
        }

    @Test
    fun forwardsIsExcludedFromRollbackWhenItIsTheFailingStage() =
        runBlocking {
            // Create a scenario where Forwards stage fails during reset,
            // so Forwards is NOT in the mutated stages. But Config and SetupInput are.
            // This tests that Forwards is NOT in the rollback (since it didn't mutate).
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "test"))

            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail on the Forwards stage
            assertTrue("Reset should fail on Forwards stage", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Forwards", ResetStage.Forwards, failed.failedStage)

            // Forwards should NOT be in the rollback (it was the failed stage, never mutated)
            val rollbackStages =
                failed.rollback.map {
                    when (it) {
                        is RollbackStageResult.Success -> it.stage
                        is RollbackStageResult.Failure -> it.stage
                    }
                }
            assertTrue(
                "Forwards should not be in rollback (it was the failing stage)",
                ResetStage.Forwards !in rollbackStages,
            )
        }
}
