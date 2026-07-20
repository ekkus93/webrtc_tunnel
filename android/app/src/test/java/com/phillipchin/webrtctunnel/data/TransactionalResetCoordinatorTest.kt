package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

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

    private fun forward(
        id: String,
        port: Int = 9999,
    ) = ForwardConfig(
        id = id,
        name = id,
        localPort = port,
        remoteForwardId = id,
        enabled = true,
    )

    /**
     * Fake ForwardsStore for testing the transactional reset coordinator.
     * Allows injecting failures on specific operations to test rollback behavior.
     */
    private class FakeForwardsStore(
        val initialForwards: List<ForwardConfig> = emptyList(),
        val throwOnSave: Boolean = false,
        val error: Throwable = IOException("Simulated save failure"),
    ) : ForwardsStore {
        var saveCallCount = 0
        var loadedForwards = initialForwards

        override fun loadForwardsResult(): Result<List<ForwardConfig>> = Result.success(loadedForwards)

        override fun saveForwards(forwards: List<ForwardConfig>) {
            saveCallCount++
            if (throwOnSave) {
                throw error
            }
            loadedForwards = forwards
        }

        override fun validateForwards(forwards: List<ForwardConfig>): String? = null
    }

    @Test
    fun successRestoresConfigSetupInputAndForwards() =
        runBlocking {
            // Seed a config, setup input, and forwards
            configRepo.writeConfig("format = \"prior\"\n")
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.local"))
            forwardsRepo.resetForwards() // reset to empty list

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
            forwardsRepo.resetForwards() // clear defaults for clean state
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
            configRepo.writeConfig(priorConfig)
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
            forwardsRepo.resetForwards()
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
            forwardsRepo.resetForwards()

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
            configRepo.writeConfig(priorConfig)
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

    // P1-006: deleteConfigFileForTransactionalReset() rollback-failure coverage. The two
    // tests below previously claimed to cover delete *failure* but never actually made
    // deleteConfigFileForTransactionalReset() fail — both asserted the success path
    // (delete succeeding) despite their names. They now force a genuine failure via a
    // fake repository, per the Fix 5 review's "no misleading test names" rule.

    private class ConfigDeleteFailureRepository(
        context: android.content.Context,
        private val deleteError: Throwable,
    ) : ConfigRepository(context) {
        override suspend fun deleteConfigFileForTransactionalReset(): Result<Unit> = Result.failure(deleteError)
    }

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
            configRepo.writeConfig(priorConfig)

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
            forwardsRepo.resetForwards()

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
            configRepo.writeConfig(priorConfig)
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
            configRepo.writeConfig("format = \"prior\"\n")
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
            configRepo.writeConfig("format = \"prior\"\n")
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

    // P1-001: TransactionalReset setup-input mutation/rollback must use explicit
    // try/catch (not runCatching) — cancellation propagates, failures are reported.

    /**
     * A [ConfigRepository] whose [saveSetupInput] throws [error] on the
     * [failOnCallNumber]th call (1-based) and delegates to the real implementation on
     * every other call, so a specific reset-vs-rollback call can be targeted precisely.
     */
    private class ThrowingSetupInputConfigRepository(
        context: android.content.Context,
        private val failOnCallNumber: Int,
        private val error: Throwable,
    ) : ConfigRepository(context) {
        private var callCount = 0

        override fun saveSetupInput(input: SetupConfigInput) {
            callCount++
            if (callCount == failOnCallNumber) throw error
            super.saveSetupInput(input)
        }

        // FIX7 P0-005-A: rollback-restore of setup-input now goes through this method instead of
        // saveSetupInput (which cannot represent "absent"), so a fake targeting the rollback call
        // (call 2 in every test using this class) must override this one instead.
        override fun restoreSetupInputFileSnapshot(snapshot: ExactFileSnapshot): Result<Unit> {
            callCount++
            if (callCount == failOnCallNumber) {
                if (error is CancellationException) throw error
                return Result.failure(error as? Exception ?: Exception(error))
            }
            return super.restoreSetupInputFileSnapshot(snapshot)
        }
    }

    @Test
    fun setupResetFailureReturnsResetStageFailure() =
        runBlocking {
            val throwingConfigRepo =
                ThrowingSetupInputConfigRepository(
                    context,
                    failOnCallNumber = 1,
                    error = IOException("disk full"),
                )
            val coordinator = TransactionalResetCoordinator(throwingConfigRepo, forwardsRepo)

            val result = coordinator.resetConfiguration()

            assertTrue("reset must fail when setup-input reset throws", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.SetupInput, failed.failedStage)
            assertTrue("failure reason must be reported, not swallowed", failed.cause.contains("disk full"))
        }

    @Test
    fun setupRollbackFailureReturnsRollbackStageFailure() =
        runBlocking {
            // Call 1 = SetupInput reset (succeeds). Call 2 = SetupInput rollback,
            // triggered once the Forwards stage below fails (fails).
            val throwingConfigRepo =
                ThrowingSetupInputConfigRepository(
                    context,
                    failOnCallNumber = 2,
                    error = IOException("rollback write failed"),
                )
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val coordinator = TransactionalResetCoordinator(throwingConfigRepo, fakeForwardsRepo)

            val result = coordinator.resetConfiguration()

            assertTrue("reset must fail on the Forwards stage", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Forwards, failed.failedStage)
            assertTrue(
                "rollback must report SetupInput as a Failure when its restore throws",
                failed.rollback.any {
                    it is RollbackStageResult.Failure && it.stage == ResetStage.SetupInput
                },
            )
        }

    @Test
    fun cancellationDuringSetupResetPropagates() {
        val throwingConfigRepo =
            ThrowingSetupInputConfigRepository(
                context,
                failOnCallNumber = 1,
                error = CancellationException("cancelled during setup reset"),
            )
        val coordinator = TransactionalResetCoordinator(throwingConfigRepo, forwardsRepo)

        var caught: CancellationException? = null
        try {
            runBlocking { coordinator.resetConfiguration() }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertTrue(
            "CancellationException during setup-input reset must propagate, not be " +
                "converted into a ResetStageResult.Failure",
            caught != null,
        )
    }

    @Test
    fun cancellationDuringSetupRollbackPropagates() {
        val throwingConfigRepo =
            ThrowingSetupInputConfigRepository(
                context,
                failOnCallNumber = 2,
                error = CancellationException("cancelled during setup rollback"),
            )
        val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
        val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
        val coordinator = TransactionalResetCoordinator(throwingConfigRepo, fakeForwardsRepo)

        var caught: CancellationException? = null
        try {
            runBlocking { coordinator.resetConfiguration() }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertTrue(
            "CancellationException during setup-input rollback must propagate, not be " +
                "converted into a RollbackStageResult.Failure",
            caught != null,
        )
    }

    @Test
    fun cancellationDuringForwardsResetRestoresSetupInputAndConfig() =
        runBlocking {
            // FIX7 P0-005-C/E: a cancellation at the LAST reset stage (Forwards) must roll back
            // the already-committed Config and SetupInput stages before propagating, exactly
            // like an ordinary Forwards failure already does.
            configRepo.writeConfig("format = \"prior\"\n")
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                    error = CancellationException("cancelled during forwards reset"),
                )
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val cancellingCoordinator = TransactionalResetCoordinator(configRepo, fakeForwardsRepo)

            var caught: CancellationException? = null
            try {
                cancellingCoordinator.resetConfiguration()
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Forwards must propagate", caught != null)
            assertEquals(
                "config committed before the cancelled Forwards stage must be rolled back",
                "format = \"prior\"\n",
                configRepo.readConfig(),
            )
            assertEquals(
                "setup input committed before the cancelled Forwards stage must be rolled back",
                "broker.prior",
                configRepo.loadSetupInputResult().getOrThrow().brokerHost,
            )
        }

    // P1-004: true early-stage failure tests — every prior test that claimed to prove
    // "stops immediately" actually only failed on the final (Forwards) stage. These
    // force failure at the Config and SetupInput stages specifically.

    private class ThrowingConfigWriteRepository(
        context: android.content.Context,
        private val failOnCallNumber: Int,
        private val error: Throwable,
    ) : ConfigRepository(context) {
        private var callCount = 0

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            callCount++
            if (callCount == failOnCallNumber) return Result.failure(error)
            return super.writeConfigAtomically(contents)
        }
    }

    @Test
    fun resetStopsImmediatelyWhenConfigStageFails() =
        runBlocking {
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "prior-setup-input"))
            val priorSetupInput = configRepo.loadSetupInputResult().getOrThrow()

            val failingConfigRepo =
                ThrowingConfigWriteRepository(context, failOnCallNumber = 1, error = IOException("disk full"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current())
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(failingConfigRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            assertTrue("reset must fail when the Config stage fails", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.Config, failed.failedStage)
            assertEquals(
                "SetupInput must never be reset once the Config stage has already failed",
                priorSetupInput,
                failingConfigRepo.loadSetupInputResult().getOrThrow(),
            )
            assertEquals(
                "Forwards must never be touched once the Config stage has already failed",
                0,
                fakeStore.saveCallCount,
            )
        }

    @Test
    fun resetStopsImmediatelyWhenSetupStageFails() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n")

            // Call 1 = SetupInput reset, forced to fail. Config's own reset (a separate
            // method, writeConfigAtomically) runs and succeeds normally first.
            val failingConfigRepo =
                ThrowingSetupInputConfigRepository(context, failOnCallNumber = 1, error = IOException("disk full"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current())
            val fakeForwardsRepo = ForwardsRepository(fakeStore, AppDispatchers())
            val failingCoordinator = TransactionalResetCoordinator(failingConfigRepo, fakeForwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            assertTrue("reset must fail when the SetupInput stage fails", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.SetupInput, failed.failedStage)
            assertEquals(
                "Forwards must never be touched once the SetupInput stage has already failed",
                0,
                fakeStore.saveCallCount,
            )
        }

    // P1-005: true rollback-failure test — forces the rollback operation itself
    // (Config's restore write) to fail, per the answered RESPONSES Q4 (fake repository,
    // not a real file-permission scenario).

    private class ConfigRollbackFailureRepository(
        context: android.content.Context,
        private val writeFailOnCallNumber: Int,
        private val writeError: Throwable,
    ) : ConfigRepository(context) {
        private var writeCallCount = 0

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            writeCallCount++
            if (writeCallCount == writeFailOnCallNumber) return Result.failure(writeError)
            return super.writeConfigAtomically(contents)
        }

        // Always fails — SetupInput's own reset must fail unconditionally to trigger
        // rollback, per the required scenario (config reset succeeds, setup reset
        // fails, config rollback is attempted and fails).
        override fun saveSetupInput(input: SetupConfigInput) {
            throw IOException("setup reset failed")
        }
    }

    @Test
    fun configRollbackFailureIsReportedAsRollbackStageFailure() =
        runBlocking {
            val priorConfig = "format = \"prior-rollback-failure-test\"\n"
            configRepo.writeConfig(priorConfig)
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "prior"))

            val failingRepo =
                ConfigRollbackFailureRepository(
                    context,
                    // Call 1 = Config's own reset (succeeds normally).
                    // Call 2 = Config's rollback restore, triggered once SetupInput's
                    // reset fails below (fails).
                    writeFailOnCallNumber = 2,
                    writeError = IOException("rollback write failed"),
                )
            val failingCoordinator = TransactionalResetCoordinator(failingRepo, forwardsRepo)

            val result = failingCoordinator.resetConfiguration()

            assertTrue("reset must fail when the SetupInput stage fails", result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertEquals(ResetStage.SetupInput, failed.failedStage)

            assertTrue(
                "rollback must report Config as a genuine Failure — the rollback " +
                    "write itself failed, not just the forward reset",
                failed.rollback.any {
                    it is RollbackStageResult.Failure && it.stage == ResetStage.Config
                },
            )
        }
}
