package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import java.io.IOException

// Shared test fixtures for TransactionalResetCoordinator's test classes (split across
// TransactionalResetCoordinatorTest, TransactionalResetCoordinatorRollbackReportingTest,
// and TransactionalResetCoordinatorCancellationTest to stay under the repo's 800-line
// file-size guidance). `internal` (not `private`) so every file in this package can
// reach them without an import, matching Kotlin's same-package visibility.

internal fun forward(
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
internal class FakeForwardsStore(
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

// P1-006: deleteConfigFileForTransactionalReset() rollback-failure coverage. The two
// tests using this previously claimed to cover delete *failure* but never actually made
// deleteConfigFileForTransactionalReset() fail — both asserted the success path
// (delete succeeding) despite their names. They now force a genuine failure via a
// fake repository, per the Fix 5 review's "no misleading test names" rule.

internal class ConfigDeleteFailureRepository(
    context: android.content.Context,
    private val deleteError: Throwable,
) : ConfigRepository(context) {
    override suspend fun deleteConfigFileForTransactionalReset(): Result<Unit> = Result.failure(deleteError)
}

// P1-001: TransactionalReset setup-input mutation/rollback must use explicit
// try/catch (not runCatching) — cancellation propagates, failures are reported.

/**
 * A [ConfigRepository] whose [saveSetupInput] throws [error] on the
 * [failOnCallNumber]th call (1-based) and delegates to the real implementation on
 * every other call, so a specific reset-vs-rollback call can be targeted precisely.
 */
internal class ThrowingSetupInputConfigRepository(
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

// P1-004: true early-stage failure tests — every prior test that claimed to prove
// "stops immediately" actually only failed on the final (Forwards) stage. These
// force failure at the Config and SetupInput stages specifically.

internal class ThrowingConfigWriteRepository(
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

// P1-005: true rollback-failure test — forces the rollback operation itself
// (Config's restore write) to fail, per the answered RESPONSES Q4 (fake repository,
// not a real file-permission scenario).

internal class ConfigRollbackFailureRepository(
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
