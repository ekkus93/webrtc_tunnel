package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The setup-wizard-local asynchronous actions that must never run concurrently (FIX8 P1-001-A):
 * overlapping actions previously each toggled their own `isBusy` independently, so one operation's
 * completion could clear busy while another was still running, and nothing stopped e.g. an
 * identity import from mutating the shared draft while a save was reading it mid-commit.
 */
enum class SetupDraftOperation {
    BaselineLoad,
    IdentityAction,
    ForwardEdit,
    ValidationNavigation,
    FinalSave,
}

/** Outcome of a [SetupOperationCoordinator.tryRun] admission attempt. */
internal sealed interface SetupDraftAdmission<out T> {
    data class Completed<T>(val value: T) : SetupDraftAdmission<T>

    /** [active] is the operation currently holding admission (not necessarily the caller's own). */
    data class Busy(val active: SetupDraftOperation) : SetupDraftAdmission<Nothing>
}

/**
 * FIX9 P0-001: explicit freshness token handed to every admitted setup operation. The previous
 * API passed a raw `Long`, which made it easy for real controller call sites to ignore freshness
 * before publishing state or committing storage after [SetupOperationCoordinator.invalidate].
 */
internal class SetupOperationToken internal constructor(
    val id: Long,
    val operation: SetupDraftOperation,
    private val staleCheck: (Long) -> Boolean,
) {
    fun isFresh(): Boolean = !staleCheck(id)

    inline fun publishIfFresh(block: () -> Unit): Boolean {
        if (!isFresh()) {
            return false
        }
        block()
        return true
    }
}

/** An admitted operation's identity: [id] is unique per acquisition, so a stale release can never
 * be mistaken for a different, later acquisition. */
private data class ActiveSetupDraftOperation(
    val id: Long,
    val operation: SetupDraftOperation,
)

/**
 * Setup-wizard-local admission gate (FIX8 P1-001-A/§5.2), mirroring
 * [com.phillipchin.webrtctunnel.data.ConfigurationMutationCoordinator]'s atomic-owner-token design
 * at the wizard's own scope: serializes baseline load, identity actions, forward edits,
 * validation/navigation, and final save so overlapping setup actions can never publish stale UI
 * state or clear `isBusy` while another operation is still active. Policy is visible
 * reject-on-overlap, not queueing — the same choice `ConfigurationMutationCoordinator` makes.
 *
 * This is NOT a substitute for the application-scoped `ConfigurationMutationCoordinator`: final
 * save acquires *this* setup-local admission and then, from inside it, the global `SetupSave`
 * admission (nested) — see [SetupSaveController].
 */
internal class SetupOperationCoordinator {
    private val sequence = AtomicLong(0)
    private val active = AtomicReference<ActiveSetupDraftOperation?>(null)

    // FIX8 P1-001-A: "no stale action completion may overwrite newer draft state" — the only way
    // that can happen under reject-on-overlap admission is [invalidate] (the wizard's own abandon
    // action, which must always be able to interrupt an in-flight operation rather than being
    // rejected as busy, so it does not itself go through [tryRun]). Any operation whose token was
    // issued at or before the last [invalidate] call is stale; its caller must skip publishing a
    // result once it observes that via [isStale].
    private val staleBefore = AtomicLong(0)

    /** Derived from actual admission ownership (FIX8 P1-001-A) — never toggled independently by
     * a controller. */
    val isBusy: Boolean get() = active.get() != null

    /** Marks every operation currently in flight (or already completed but not yet observed) as
     * stale — called when the user abandons the wizard, so a still-running operation's eventual
     * completion cannot overwrite the freshly-reset draft state. */
    fun invalidate() {
        staleBefore.set(sequence.get())
    }

    /** Whether the operation identified by [operationId] was issued before the last [invalidate]
     * call and must not publish its result. Kept for existing tests and diagnostic assertions; new
     * production controller code should use [SetupOperationToken.isFresh] or
     * [SetupOperationToken.publishIfFresh]. */
    fun isStale(operationId: Long): Boolean = operationId <= staleBefore.get()

    suspend fun <T> tryRun(
        operation: SetupDraftOperation,
        block: suspend (token: SetupOperationToken) -> T,
    ): SetupDraftAdmission<T> {
        while (true) {
            val owner = ActiveSetupDraftOperation(sequence.incrementAndGet(), operation)
            if (active.compareAndSet(null, owner)) {
                return try {
                    SetupDraftAdmission.Completed(
                        block(SetupOperationToken(owner.id, owner.operation, ::isStale)),
                    )
                } finally {
                    check(active.compareAndSet(owner, null)) {
                        "Setup draft admission owner changed unexpectedly"
                    }
                }
            }
            // Lost the race to acquire; the true owner may have already released between the
            // failed compareAndSet above and this read — retry rather than report a stale or
            // null-derived owner (same reasoning as ConfigurationMutationCoordinator.tryRun).
            val busyOwner = active.get() ?: continue
            return SetupDraftAdmission.Busy(busyOwner.operation)
        }
    }

    /**
     * Runs [block] under setup-local admission for [operation]. Cancellation is caught first and
     * rethrown; any other exception escaping [block] is reported as a fixed/redacted, durable
     * `errorMessage` — [block] itself is still expected to handle its own anticipated failures
     * with specific messages, this is the safety net so an unanticipated exception can never
     * merely clear busy with no visible message (FIX8 P1-001-C). A busy rejection is reported the
     * same durable/visible way, naming the active operation. Returns `null` when [block] did not
     * complete normally (busy rejection or a caught ordinary exception) — a caller that needs to
     * chain a follow-up action only on genuine success (e.g. starting the tunnel after a save)
     * checks for `null`.
     */
    suspend fun <T> runGuarded(
        access: WizardStateAccess,
        operation: SetupDraftOperation,
        block: suspend (token: SetupOperationToken) -> T,
    ): T? {
        try {
            val admission =
                tryRun(operation) { token ->
                    // FIX8 P1-001-A: publish isBusy=true the instant admission is acquired —
                    // otherwise a caller observing published state (not operations.isBusy
                    // directly) would see busy=false for as long as block() runs before its own
                    // first applyState call, which may be its entire duration.
                    token.publishIfFresh { access.applyState(access.state()) }
                    try {
                        block(token)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        token.publishIfFresh {
                            access.applyState(
                                access.state().copy(
                                    errorMessage =
                                        SensitiveDataRedactor.redactText(error.message ?: "Setup action failed"),
                                    saveResult = null,
                                ),
                            )
                        }
                        null
                    }
                }
            return when (admission) {
                is SetupDraftAdmission.Completed -> admission.value
                is SetupDraftAdmission.Busy -> {
                    access.applyState(
                        access.state().copy(
                            errorMessage =
                                "A setup action is already in progress (setup_draft_operation_busy): " +
                                    "${admission.active}",
                            saveResult = null,
                        ),
                    )
                    null
                }
            }
        } finally {
            // FIX8 P1-001-A/C: re-stamps isBusy now that admission has definitely been released
            // (tryRun's own `finally` already ran) — covers both a normal return above AND a
            // CancellationException propagating straight through (a cancelled action must still
            // release its busy indicator, not leave it stuck true for the rest of the wizard's
            // life). This restamps the current state only; stale operation-specific state must be
            // guarded by [SetupOperationToken] before this point.
            access.applyState(access.state())
        }
    }

    /** Read-only visibility for tests; never mutated outside [tryRun]. */
    internal fun activeOperationForTest(): SetupDraftOperation? = active.get()?.operation
}
