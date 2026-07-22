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
 * FIX6 P1-002: transactional reset must contain snapshot read failures before any mutation,
 * continue rolling back after one restore stage throws, redact every reason, and propagate
 * cancellation rather than turning it into a Failed result.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionalResetHardeningTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var configRepo: ConfigRepository
    private lateinit var forwardsRepo: ForwardsRepository

    @Before
    fun setUp() {
        File(context.filesDir, "config.toml").delete()
        File(context.filesDir, "setup_input.json").delete()
        File(context.filesDir, "forwards.json").delete()
        configRepo = ConfigRepository(context)
        forwardsRepo =
            ForwardsRepository(ForwardsConfigStore(context), AppDispatchers()).also { runBlocking { it.refresh() } }
    }

    private fun forward(id: String) =
        ForwardConfig(id = id, name = id, localPort = 9999, remoteForwardId = id, enabled = true)

    private class FakeForwardsStore(
        private val initialForwards: List<ForwardConfig> = emptyList(),
        private val throwOnSave: Boolean = false,
    ) : ForwardsStore {
        var saveCallCount = 0
        private var loaded = initialForwards

        override fun loadForwardsResult(): Result<List<ForwardConfig>> = Result.success(loaded)

        override fun saveForwards(forwards: List<ForwardConfig>) {
            saveCallCount++
            if (throwOnSave) throw IOException("Simulated save failure")
            loaded = forwards
        }

        override fun validateForwards(forwards: List<ForwardConfig>): String? = null

        override fun captureExactSnapshot(): Result<ExactFileSnapshot> =
            Result.success(ExactFileSnapshot(existed = true, bytes = ByteArray(0)))

        override fun restoreExactSnapshot(snapshot: ExactFileSnapshot): Result<Unit> = Result.success(Unit)
    }

    private class ConfigReadThrows(
        context: android.content.Context,
        private val error: Throwable,
    ) : ConfigRepository(context) {
        override val captureConfigSnapshotForReset: Result<ExactFileSnapshot>
            get() {
                if (error is CancellationException) throw error
                return Result.failure(error)
            }
    }

    private class ConfigWriteThrowsOnNthCall(
        context: android.content.Context,
        private val throwOnCall: Int,
        private val error: Throwable,
    ) : ConfigRepository(context) {
        private var calls = 0

        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            calls++
            if (calls == throwOnCall) throw error
            return super.writeConfigAtomically(contents)
        }

        // FIX8 P0-006-A: Config's rollback restore now goes through restoreConfigSnapshot
        // (byte-exact) instead of writeConfigAtomically — the 2nd call in every test using this
        // class (the rollback restore) must throw here instead, or it goes silently inert.
        override suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit> {
            calls++
            if (calls == throwOnCall) throw error
            return super.restoreConfigSnapshot(snapshot)
        }
    }

    private class ConfigSaveSetupThrowsOnNthCall(
        context: android.content.Context,
        private val throwOnCall: Int,
        private val error: Throwable,
    ) : ConfigRepository(context) {
        private var calls = 0

        // FIX8 P0-006-B: the reset stage's own apply now goes through saveSetupInputAtomically
        // instead of saveSetupInput.
        override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> {
            calls++
            return if (calls == throwOnCall) {
                Result.failure(error as? Exception ?: Exception(error))
            } else {
                super.saveSetupInputAtomically(input)
            }
        }

        // FIX7 P0-005-A: rollback-restore of setup-input now goes through this method instead of
        // saveSetupInput (which cannot represent "absent"); this fake's call 2 (the rollback
        // restore in every test using it) must fail here instead.
        override suspend fun restoreSetupInputFileSnapshot(snapshot: ExactFileSnapshot): Result<Unit> {
            calls++
            return if (calls == throwOnCall) {
                Result.failure(error as? Exception ?: Exception(error))
            } else {
                super.restoreSetupInputFileSnapshot(snapshot)
            }
        }
    }

    private class ConfigRedactionFailRepo(context: android.content.Context) : ConfigRepository(context) {
        override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
            Result.failure(IOException("setup boom password=setupsecret"))

        override suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
            Result.failure(IOException("delete boom password=deletesecret"))
    }

    // FIX8 P0-006-A: forwards' own snapshot capture (captureForTransaction) requires the
    // baseline to have reached Ready — a repository that never refreshed must abort the reset
    // before any mutation, attributed to ResetStage.Forwards specifically.
    @Test
    fun forwardsSnapshotFailureAbortsBeforeMutationAndNamesForwardsStage() =
        runBlocking {
            val neverReadyForwards = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
            val coord = TransactionalResetCoordinator(ConfigRepository(context), neverReadyForwards)

            val failed = coord.resetConfiguration() as ResetResult.Failed

            assertEquals(ResetStage.Forwards, failed.failedStage)
            assertTrue(failed.rollback.isEmpty())
        }

    @Test
    fun configSnapshotReadExceptionAbortsBeforeMutation() =
        runBlocking {
            val fakeStore = FakeForwardsStore(initialForwards = listOf(forward("keep")))
            val coord =
                TransactionalResetCoordinator(
                    ConfigReadThrows(context, IOException("read boom")),
                    ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } },
                )

            val failed = coord.resetConfiguration() as ResetResult.Failed

            assertEquals(ResetStage.Config, failed.failedStage)
            assertTrue("no stage may mutate when snapshot read fails", failed.rollback.isEmpty())
            assertEquals("forwards store must never be written", 0, fakeStore.saveCallCount)
        }

    // FIX8 P0-006-D: corrupt-but-readable setup_input.json no longer blocks reset at all (the
    // gate that used to require loadSetupInputResult() to parse before reset is gone) — a
    // setup-input snapshot failure now only comes from a genuine read failure, and must be
    // attributed to ResetStage.SetupInput specifically, not a hardcoded Config.
    @Test
    fun setupSnapshotReadExceptionAbortsBeforeMutationAndNamesSetupInputStage() =
        runBlocking {
            File(context.filesDir, "setup_input.json").writeText("{}")
            val freshRepo = ConfigRepository(context)
            val fakeStore = FakeForwardsStore(initialForwards = listOf(forward("keep")))
            val readError = IOException("setup input read boom")
            val coord =
                TransactionalResetCoordinator(
                    freshRepo,
                    ForwardsRepository(fakeStore, AppDispatchers()).also { runBlocking { it.refresh() } },
                    setupInputReadBytes = { throw readError },
                )

            val failed = coord.resetConfiguration() as ResetResult.Failed

            assertEquals(ResetStage.SetupInput, failed.failedStage)
            assertTrue(failed.rollback.isEmpty())
            assertEquals(0, fakeStore.saveCallCount)
        }

    @Test
    fun rollbackContinuesAfterConfigRestoreThrows() =
        runBlocking {
            // Seed via the plain repo so the throwing repo's call counter starts at reset.
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
            // Config restore is the 2nd write on this repo (1 = reset stage, 2 = rollback restore).
            val writeRepo =
                ConfigWriteThrowsOnNthCall(context, throwOnCall = 2, error = IOException("restore boom"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val coord =
                TransactionalResetCoordinator(
                    writeRepo,
                    ForwardsRepository(fakeStore, AppDispatchers()).also {
                        runBlocking { it.refresh() }
                    },
                )

            val failed = coord.resetConfiguration() as ResetResult.Failed

            assertEquals(ResetStage.Forwards, failed.failedStage)
            // Every attempted stage has a rollback result — Config, SetupInput, and Forwards
            // itself (FIX8 P0-006-C: attempted before apply, so the failing stage's own restore
            // still runs, as a no-op success here).
            assertEquals("every attempted stage must have a rollback result", 3, failed.rollback.size)
            assertTrue(
                "the earlier-in-reverse SetupInput restore must be preserved",
                failed.rollback.any { it is RollbackStageResult.Success && it.stage == ResetStage.SetupInput },
            )
            assertTrue(
                "the throwing Config restore is contained as a Failure, not an uncaught abort",
                failed.rollback.any { it is RollbackStageResult.Failure && it.stage == ResetStage.Config },
            )
        }

    @Test
    fun rollbackContinuesAfterSetupRestoreThrows() =
        runBlocking {
            configRepo.writeConfig("format = \"prior\"\n").getOrThrow()
            // saveSetupInput throws on the 2nd call (1 = reset stage, 2 = rollback restore).
            val setupRepo =
                ConfigSaveSetupThrowsOnNthCall(context, throwOnCall = 2, error = IOException("setup restore boom"))
            val fakeStore = FakeForwardsStore(initialForwards = forwardsRepo.current(), throwOnSave = true)
            val coord =
                TransactionalResetCoordinator(
                    setupRepo,
                    ForwardsRepository(fakeStore, AppDispatchers()).also {
                        runBlocking { it.refresh() }
                    },
                )

            val failed = coord.resetConfiguration() as ResetResult.Failed

            assertEquals(ResetStage.Forwards, failed.failedStage)
            assertTrue(
                "SetupInput restore threw and is recorded as a Failure",
                failed.rollback.any { it is RollbackStageResult.Failure && it.stage == ResetStage.SetupInput },
            )
            assertTrue(
                "Config restore must still run after SetupInput restore threw",
                failed.rollback.any { it is RollbackStageResult.Success && it.stage == ResetStage.Config },
            )
        }

    @Test
    fun everyResetAndRollbackReasonIsRedacted() =
        runBlocking {
            val coord = TransactionalResetCoordinator(ConfigRedactionFailRepo(context), forwardsRepo)

            val failed = coord.resetConfiguration() as ResetResult.Failed

            assertEquals(ResetStage.SetupInput, failed.failedStage)
            assertFalse("reset reason must be redacted", failed.cause.contains("setupsecret"))
            assertTrue(failed.cause.contains("***REDACTED***"))
            val configRollback =
                failed.rollback.filterIsInstance<RollbackStageResult.Failure>()
                    .single { it.stage == ResetStage.Config }
            assertFalse("rollback reason must be redacted", configRollback.reason.contains("deletesecret"))
            assertTrue(configRollback.reason.contains("***REDACTED***"))
        }

    @Test
    fun snapshotCancellationPropagates() {
        val coord =
            TransactionalResetCoordinator(
                ConfigReadThrows(context, CancellationException("cancelled during snapshot")),
                forwardsRepo,
            )
        var caught: CancellationException? = null
        try {
            runBlocking { coord.resetConfiguration() }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertTrue("snapshot cancellation must propagate, not become a Failed result", caught != null)
    }
}
