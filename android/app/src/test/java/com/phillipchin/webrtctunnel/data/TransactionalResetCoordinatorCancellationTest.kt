package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
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

// Setup-input mutation/rollback cancellation propagation, true early-stage-failure ("stops
// immediately") coverage, and the true rollback-failure test. Split out of
// TransactionalResetCoordinatorTest to stay under the repo's 800-line guidance — see
// TransactionalResetTestFixtures.kt for FakeForwardsStore, ThrowingSetupInputConfigRepository,
// ThrowingConfigWriteRepository, and ConfigRollbackFailureRepository.
@RunWith(RobolectricTestRunner::class)
class TransactionalResetCoordinatorCancellationTest {
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

    // P1-001: TransactionalReset setup-input mutation/rollback must use explicit
    // try/catch (not runCatching) — cancellation propagates, failures are reported.

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
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
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
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()

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

    @Test
    fun configRollbackFailureIsReportedAsRollbackStageFailure() =
        runBlocking {
            val priorConfig = "format = \"prior-rollback-failure-test\"\n"
            configRepo.writeConfig(priorConfig).getOrThrow()
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
