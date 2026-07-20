package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
 * FIX7 P0-005: exact setup-input snapshot/restore (CRITICAL-3: absent must not be conflated with
 * default) and cancellation-safe rollback for [TransactionalResetCoordinator]. Split out of
 * [TransactionalResetCoordinatorTest] (detekt LargeClass) — see that file for the coordinator's
 * ordinary stage-order/failure/rollback coverage.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionalResetExactSnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var configRepo: ConfigRepository
    private lateinit var forwardsRepo: ForwardsRepository

    @Before
    fun setUp() {
        File(context.filesDir, "config.toml").delete()
        File(context.filesDir, "setup_input.json").delete()
        File(context.filesDir, "forwards.json").delete()

        configRepo = ConfigRepository(context)
        forwardsRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
    }

    /** Fake ForwardsStore for testing the transactional reset coordinator. Allows injecting
     * failures (including cancellation) on specific operations to test rollback behavior. */
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

    // --- FIX7 P0-005-E: exact setup-input state ------------------------------------------------

    @Test
    fun resetSnapshotDistinguishesAbsentSetupInputFromDefaultSetupInput() =
        runBlocking {
            // Absent case: setup_input.json does not exist before reset.
            File(context.filesDir, "setup_input.json").delete()
            val absentRepo = ConfigRepository(context)
            val absentStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val absentCoordinator =
                TransactionalResetCoordinator(absentRepo, ForwardsRepository(absentStore, AppDispatchers()))
            absentCoordinator.resetConfiguration()
            assertFalse(
                "setup input absent before reset must be restored as absent, not written as default JSON",
                File(context.filesDir, "setup_input.json").exists(),
            )

            // Present-with-default-value case: setup_input.json exists even though its contents
            // happen to equal the default value.
            val presentRepo = ConfigRepository(context)
            presentRepo.saveSetupInput(SetupConfigInput())
            val presentStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val presentCoordinator =
                TransactionalResetCoordinator(presentRepo, ForwardsRepository(presentStore, AppDispatchers()))
            presentCoordinator.resetConfiguration()
            assertTrue(
                "setup input present (even if default-valued) before reset must be restored as present",
                File(context.filesDir, "setup_input.json").exists(),
            )
        }

    @Test
    fun failedResetRestoresAbsentSetupInputAsAbsent() =
        runBlocking {
            File(context.filesDir, "setup_input.json").delete()
            val repo = ConfigRepository(context)
            val failingStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val coord = TransactionalResetCoordinator(repo, ForwardsRepository(failingStore, AppDispatchers()))

            val result = coord.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            assertFalse(
                "absent setup input must be restored as absent, not written as default JSON",
                File(context.filesDir, "setup_input.json").exists(),
            )
        }

    @Test
    fun failedResetRestoresPresentEmptySetupInputExactly() =
        runBlocking {
            // Deliberately unusual formatting that a parse-then-reserialize round trip through
            // SetupConfigInput/saveSetupInput would NOT reproduce byte-for-byte — proving restore
            // uses the exact captured bytes, not a re-derived value.
            val exactPriorBytes = "{\n  \"brokerHost\": \"broker.prior\"\n}\n"
            File(context.filesDir, "setup_input.json").writeText(exactPriorBytes)
            val repo = ConfigRepository(context)
            val failingStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val coord = TransactionalResetCoordinator(repo, ForwardsRepository(failingStore, AppDispatchers()))

            val result = coord.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            assertEquals(
                "setup input must be restored byte-for-byte, not re-serialized from the parsed value",
                exactPriorBytes,
                File(context.filesDir, "setup_input.json").readText(),
            )
        }

    @Test
    fun setupInputSnapshotReadFailureAbortsBeforeMutation() =
        runBlocking {
            val setupInputPath = File(context.filesDir, "setup_input.json")
            setupInputPath.delete()
            // A directory in place of the file forces a read failure distinct from corrupt JSON.
            setupInputPath.mkdirs()
            try {
                val repo = ConfigRepository(context)
                val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current())
                val coord = TransactionalResetCoordinator(repo, ForwardsRepository(fakeStore, AppDispatchers()))

                val result = coord.resetConfiguration()

                assertTrue(
                    "reset must abort when the setup-input snapshot cannot be read",
                    result is ResetResult.Failed,
                )
                val failed = result as ResetResult.Failed
                assertTrue("no stage may mutate when the setup-input snapshot read fails", failed.rollback.isEmpty())
                assertEquals(0, fakeStore.saveCallCount)
            } finally {
                setupInputPath.deleteRecursively()
            }
        }

    // --- FIX7 P0-005-C: cancellation, one test per meaningful point ---------------------------

    @Test
    fun cancellationDuringForwardsResetRestoresSetupInputAndConfig() =
        runBlocking {
            // A cancellation at the LAST reset stage (Forwards) must roll back the
            // already-committed Config and SetupInput stages before propagating, exactly like an
            // ordinary Forwards failure already does.
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

    @Test
    fun cancellationDuringSetupInputResetRestoresConfig() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n")
            val throwingConfigRepo =
                ThrowingSetupInputConfigRepository(
                    context,
                    failOnCallNumber = 1,
                    error = CancellationException("cancelled during setup reset"),
                )
            val coordinator = TransactionalResetCoordinator(throwingConfigRepo, forwardsRepo)

            var caught: CancellationException? = null
            try {
                coordinator.resetConfiguration()
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during SetupInput must propagate", caught != null)
            assertEquals(
                "config committed before the cancelled SetupInput stage must be rolled back",
                "format = \"prior\"\n",
                throwingConfigRepo.readConfig(),
            )
        }

    /** ConfigRepository whose config write fails deterministically on its Nth call (returning
     * Result.failure, not throwing) — used to make a ROLLBACK restore fail while the earlier
     * normal reset write on the same repo instance still succeeds. */
    private class ConfigWriteFailsOnNthCall(
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
    fun resetCancellationRollbackContinuesAfterRestoreFailure() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n")
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            // Call 1 = Config's own reset (succeeds). Call 2 = Config's rollback restore,
            // triggered once the cancelled Forwards stage below rolls back (fails).
            val configRepo2 =
                ConfigWriteFailsOnNthCall(context, failOnCallNumber = 2, error = IOException("config restore failed"))
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                    error = CancellationException("cancelled during forwards reset"),
                )
            val coordinator =
                TransactionalResetCoordinator(configRepo2, ForwardsRepository(fakeStore, AppDispatchers()))

            var caught: CancellationException? = null
            try {
                coordinator.resetConfiguration()
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Forwards must propagate", caught != null)
            assertEquals(
                "SetupInput restore must still run and succeed despite Config's restore failing",
                "broker.prior",
                configRepo2.loadSetupInputResult().getOrThrow().brokerHost,
            )
        }

    @Test
    fun resetCancellationRollbackFailureIsReportedAndSuppressed() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n")
            val configRepo2 =
                ConfigWriteFailsOnNthCall(context, failOnCallNumber = 2, error = IOException("config restore failed"))
            val fakeStore =
                FakeForwardsStore(
                    initialForwards = forwardsRepo.current(),
                    throwOnSave = true,
                    error = CancellationException("cancelled during forwards reset"),
                )
            val coordinator =
                TransactionalResetCoordinator(configRepo2, ForwardsRepository(fakeStore, AppDispatchers()))

            var caught: CancellationException? = null
            try {
                coordinator.resetConfiguration()
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue(caught != null)
            val rollbackFailures = caught!!.suppressedExceptions.filterIsInstance<ResetRollbackException>()
            assertEquals(1, rollbackFailures.size)
            assertEquals(ResetStage.Config, rollbackFailures.single().stage)
        }

    // --- FIX7 P0-005-E: ordinary-failure rollback continuation/completeness --------------------

    @Test
    fun oneRollbackFailureDoesNotPreventRemainingResetRestores() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n")
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val configRepo2 =
                ConfigWriteFailsOnNthCall(context, failOnCallNumber = 2, error = IOException("config restore failed"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val coordinator =
                TransactionalResetCoordinator(configRepo2, ForwardsRepository(fakeStore, AppDispatchers()))

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            assertTrue(
                "SetupInput restore must succeed even though Config's restore failed",
                failed.rollback.any { it is RollbackStageResult.Success && it.stage == ResetStage.SetupInput },
            )
            assertTrue(
                "Config restore must be reported as a Failure, not silently dropped",
                failed.rollback.any { it is RollbackStageResult.Failure && it.stage == ResetStage.Config },
            )
        }

    /** ConfigRepository whose config write fails on its Nth call AND whose setup-input restore
     * always fails — used to prove every failed rollback stage is reported, not just the first. */
    private class ConfigDoubleRollbackFailureRepository(
        context: android.content.Context,
        private val configWriteFailOnCallNumber: Int,
        private val configWriteError: Throwable,
    ) : ConfigRepository(context) {
        private var writeCallCount = 0

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            writeCallCount++
            if (writeCallCount == configWriteFailOnCallNumber) return Result.failure(configWriteError)
            return super.writeConfigAtomically(contents)
        }

        override fun restoreSetupInputFileSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
            Result.failure(IOException("setup input restore failed"))
    }

    @Test
    fun resetRollbackIncompleteListsEveryFailedRestore() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n")
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val doubleFailingRepo =
                ConfigDoubleRollbackFailureRepository(
                    context,
                    configWriteFailOnCallNumber = 2,
                    configWriteError = IOException("config restore failed"),
                )
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val coordinator =
                TransactionalResetCoordinator(doubleFailingRepo, ForwardsRepository(fakeStore, AppDispatchers()))

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            val failed = result as ResetResult.Failed
            val failures = failed.rollback.filterIsInstance<RollbackStageResult.Failure>().map { it.stage }
            assertEquals(setOf(ResetStage.Config, ResetStage.SetupInput), failures.toSet())
        }

    @Test
    fun resetSnapshotSecretBytesAreWiped() =
        runBlocking {
            // setup_input.json can hold a plaintext broker password, so its snapshot bytes are
            // secret-bearing and must be wiped once the transaction finishes (FIX7 P0-005-B).
            configRepo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val trackedBytes = "prior-secret-json".toByteArray()
            val coordinator =
                TransactionalResetCoordinator(configRepo, forwardsRepo, setupInputReadBytes = { trackedBytes })

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Success)
            assertTrue(
                "setup input snapshot bytes must be wiped once the reset finishes",
                trackedBytes.all { it == 0.toByte() },
            )
        }
}
