package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicLong

private const val SETUP_ABANDONED_REASON = "setup abandoned"

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

    fun publishIfFresh(block: () -> Unit): Boolean {
        if (!isFresh()) {
            return false
        }
        block()
        return true
    }
}

/**
 * An admitted operation's identity. The owning [job] is captured at admission so abandoning the
 * wizard can cancel the actual production coroutine, not merely hide its eventual publication.
 */
private class ActiveSetupDraftOperation(
    val id: Long,
    val operation: SetupDraftOperation,
    val job: Job,
)

/**
 * Setup-wizard-local admission gate (FIX8 P1-001-A/§5.2), mirroring
 * [com.phillipchin.webrtctunnel.data.ConfigurationMutationCoordinator]'s owner-token design at the
 * wizard's own scope. Admission state is protected by [ownerLock], but the lock is never held while
 * user work suspends. This lets [invalidateAndCancelActive] atomically identify the current owner,
 * mark its token stale, and cancel its exact coroutine without allowing a stale owner to clear a
 * later acquisition.
 */
internal class SetupOperationCoordinator {
    private val sequence = AtomicLong(0)
    private val ownerLock = Any()
    private var active: ActiveSetupDraftOperation? = null
    private val staleBefore = AtomicLong(0)

    /** Derived from actual admission ownership; never toggled independently by a controller. */
    val isBusy: Boolean
        get() = synchronized(ownerLock) { active != null }

    /**
     * Marks the current operation stale and cancels its owning coroutine. Cancellation propagates
     * into transactional final-save persistence, whose coordinator performs rollback under
     * `NonCancellable` before rethrowing. The job is cancelled outside [ownerLock] so cancellation
     * handlers can never deadlock while releasing admission.
     */
    fun invalidateAndCancelActive(reason: String = SETUP_ABANDONED_REASON) {
        val jobToCancel =
            synchronized(ownerLock) {
                staleBefore.set(sequence.get())
                active?.job
            }
        jobToCancel?.cancel(CancellationException(reason))
    }

    /** Compatibility entry point: invalidation is cancellation, never a silent UI-only reset. */
    fun invalidate() = invalidateAndCancelActive()

    /** Whether [operationId] belongs to an operation invalidated by setup abandonment. */
    fun isStale(operationId: Long): Boolean = operationId <= staleBefore.get()

    suspend fun <T> tryRun(
        operation: SetupDraftOperation,
        block: suspend (token: SetupOperationToken) -> T,
    ): SetupDraftAdmission<T> {
        val job = checkNotNull(currentCoroutineContext()[Job]) { "Setup operation requires a coroutine Job" }
        val ownerOrBusy =
            synchronized(ownerLock) {
                val busyOwner = active
                if (busyOwner != null) {
                    SetupDraftAdmission.Busy(busyOwner.operation)
                } else {
                    val owner = ActiveSetupDraftOperation(sequence.incrementAndGet(), operation, job)
                    active = owner
                    owner
                }
            }
        if (ownerOrBusy is SetupDraftAdmission.Busy) {
            return ownerOrBusy
        }
        val owner = ownerOrBusy as ActiveSetupDraftOperation
        return try {
            SetupDraftAdmission.Completed(
                block(SetupOperationToken(owner.id, owner.operation, ::isStale)),
            )
        } finally {
            synchronized(ownerLock) {
                check(active === owner) { "Setup draft admission owner changed unexpectedly" }
                active = null
            }
        }
    }

    suspend fun <T> runGuarded(
        access: WizardStateAccess,
        operation: SetupDraftOperation,
        block: suspend (token: SetupOperationToken) -> T,
    ): T? {
        try {
            val admission =
                tryRun(operation) { token ->
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
            // Re-stamp only the current state. After cancel(), this is the reset state, never a
            // captured stale operation state; it merely reflects the released admission owner.
            access.applyState(access.state())
        }
    }

    /** Read-only visibility for tests; never mutated outside [tryRun]/cancellation. */
    internal fun activeOperationForTest(): SetupDraftOperation? = synchronized(ownerLock) { active?.operation }
}
