package com.phillipchin.webrtctunnel.data

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The config-related operations that must never run concurrently (FIX7-INV-009 / FIX8
 * §3.6): different ViewModels can otherwise race each other's disk mutations because each
 * screen previously only guarded itself.
 */
enum class ConfigurationOperation {
    SetupSave,
    ConfigImport,
    ForwardMutation,
    ConfigurationReset,

    // FIX8 P0-002-B: settings/network-policy preference writes now also serialize through this
    // same admission gate, so a concurrent preference save can never be lost or overwritten by
    // a setup rollback (spec §3.6/INV-016).
    PreferenceMutation,
}

/** Outcome of a [ConfigurationMutationCoordinator.tryRun] admission attempt. */
sealed interface ConfigurationAdmission<out T> {
    data class Completed<T>(val value: T) : ConfigurationAdmission<T>

    /** [active] is the operation currently holding admission (not necessarily the caller's own). */
    data class Busy(
        val active: ConfigurationOperation,
    ) : ConfigurationAdmission<Nothing>
}

/** An admitted operation's identity: [id] is unique per acquisition so a stale release can
 * never be mistaken for (or clear) a different, later acquisition of the same [operation]. */
private data class ActiveConfigurationMutation(
    val id: Long,
    val operation: ConfigurationOperation,
)

/**
 * Application-scoped admission gate serializing setup save, config import, forward
 * mutation+activation, configuration reset, and preference mutation (FIX7-INV-009, FIX8
 * §3.6/INV-004). A per-screen mutex is not sufficient because different ViewModels run
 * concurrently; this is the single authoritative cross-feature guard. Policy is visible
 * reject-on-overlap, not queueing.
 *
 * FIX8 P0-002-A: acquisition and owner publication are one atomic [AtomicReference.compareAndSet]
 * — unlike the previous `Mutex.tryLock()` then separately `active.set(operation)` design, there is
 * no window where the lock is held but the active operation is still null (the FIX7 HIGH-3 defect:
 * a caller that lost the race could observe `active == null` and report its OWN operation as the
 * busy owner instead of the true one). If a losing `compareAndSet` races the winner's own release
 * (so the immediately-following read finds `active` already null again), the whole attempt retries
 * rather than ever reporting a null-derived or invented owner.
 */
class ConfigurationMutationCoordinator {
    private val sequence = AtomicLong(0)
    private val active = AtomicReference<ActiveConfigurationMutation?>(null)

    suspend fun <T> tryRun(
        operation: ConfigurationOperation,
        block: suspend () -> T,
    ): ConfigurationAdmission<T> {
        while (true) {
            val token = ActiveConfigurationMutation(sequence.incrementAndGet(), operation)
            if (active.compareAndSet(null, token)) {
                return try {
                    ConfigurationAdmission.Completed(block())
                } finally {
                    check(active.compareAndSet(token, null)) {
                        "Configuration admission owner changed unexpectedly"
                    }
                }
            }
            // Lost the race to acquire. The true owner may have already released between the
            // failed compareAndSet above and this read (a benign, momentary gap, not the
            // acquisition-window bug this design eliminates) — retry rather than report a stale
            // or null-derived owner.
            val busyOwner = active.get() ?: continue
            return ConfigurationAdmission.Busy(busyOwner.operation)
        }
    }

    /** Read-only visibility for tests; never mutated outside [tryRun]. */
    internal fun activeOperationForTest(): ConfigurationOperation? = active.get()?.operation
}
