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
        forwardsRepo =
            ForwardsRepository(ForwardsConfigStore(context), AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
            val failingCoordinator = TransactionalResetCoordinator(failingConfigRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            assertTrue(
                "forwards throwOnSave and the delete failure both force this reset to fail",
                result is ResetResult.Failed,
            )
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

    // FIX8 P0-006-A/D: corrupt-but-readable setup_input.json no longer blocks snapshot capture
    // (raw bytes, no parsing) — the whole reset transaction proceeds normally and repairs every
    // component to known defaults, not just setup input.
    @Test
    fun corruptSetupInputDoesNotPreventConfigOrForwardsFromResetting() =
        runBlocking {
            val priorConfig = "format = \"prior\"\n"
            configRepo.writeConfig(priorConfig).getOrThrow()
            forwardsRepo.resetForwards().getOrThrow()
            forwardsRepo.upsertWithReceipt(forward("unchanged", 4444)).getOrThrow()
            val corruptSetupInput = File(context.filesDir, "setup_input.json")
            corruptSetupInput.writeText("NOT VALID JSON {{{")
            val freshConfigRepo = ConfigRepository(context)

            val coordinator = TransactionalResetCoordinator(freshConfigRepo, forwardsRepo)
            val result = coordinator.resetConfiguration()

            assertTrue("reset must succeed despite a corrupt setup input draft", result is ResetResult.Success)
            assertEquals(
                "config must reset to its default template",
                freshConfigRepo.defaultConfigTemplate,
                freshConfigRepo.configContents,
            )
            assertTrue("forwards must reset to empty", forwardsRepo.current().isEmpty())
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail on the Forwards stage
            assertTrue("Reset should fail on Forwards stage", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Forwards", ResetStage.Forwards, failed.failedStage)

            // Rollback covers every attempted stage (Config, SetupInput, and Forwards itself —
            // FIX8 P0-006-C: attempted before apply, so the failing stage's own restore still
            // runs, as a no-op success here).
            assertEquals(
                "Rollback should cover Config, SetupInput, and Forwards",
                3,
                failed.rollback.size,
            )

            // Verify the rollback stage results — all three should succeed in this scenario
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
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
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
    fun forwardsFailingStageIsStillIncludedInRollbackAsANoOpRestore() =
        runBlocking {
            // FIX8 P0-006-C: Forwards is added to `attempted` BEFORE its own apply runs, so even
            // though it's the failing stage here (never actually mutated, since FakeForwardsStore
            // throws before touching its backing state), its restore is still attempted — in
            // case a real stage partially mutated before reporting failure. It succeeds as a
            // no-op since nothing had changed.
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "test"))

            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } }
            val failingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            // Reset should fail on the Forwards stage
            assertTrue("Reset should fail on Forwards stage", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals("Failed stage should be Forwards", ResetStage.Forwards, failed.failedStage)

            val forwardsRollback = failed.rollback.single { it.stageOf() == ResetStage.Forwards }
            assertTrue(
                "Forwards' own restore must still be attempted (and succeed as a no-op) even " +
                    "though it was the failing stage",
                forwardsRollback is RollbackStageResult.Success,
            )
        }
}

private fun RollbackStageResult.stageOf(): ResetStage =
    when (this) {
        is RollbackStageResult.Success -> stage
        is RollbackStageResult.Failure -> stage
    }
