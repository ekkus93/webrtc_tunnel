package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

/**
 * The four config-related operations that must never run concurrently (FIX7-INV-009):
 * different ViewModels can otherwise race each other's disk mutations because each screen
 * previously only guarded itself.
 */
enum class ConfigurationOperation {
    SetupSave,
    ConfigImport,
    ForwardMutation,
    ConfigurationReset,
}

/** Outcome of a [ConfigurationMutationCoordinator.tryRun] admission attempt. */
sealed interface ConfigurationAdmission<out T> {
    data class Completed<T>(val value: T) : ConfigurationAdmission<T>

    /** [active] is the operation currently holding admission (not necessarily the caller's own). */
    data class Busy(
        val active: ConfigurationOperation,
    ) : ConfigurationAdmission<Nothing>
}

/**
 * Application-scoped admission gate serializing setup save, config import, forward
 * mutation+activation, and configuration reset (FIX7-INV-009). A per-screen mutex is not
 * sufficient because different ViewModels run concurrently; this is the single authoritative
 * cross-feature guard. FIX7 policy is visible reject-on-overlap, not queueing.
 */
class ConfigurationMutationCoordinator {
    private val mutex = Mutex()
    private val active = AtomicReference<ConfigurationOperation?>(null)

    /**
     * Attempts to admit [operation]. If another operation already holds admission, returns
     * [ConfigurationAdmission.Busy] without invoking [block]. Otherwise runs [block] with
     * admission held for its entire duration — including cancellation and fatal errors, which
     * always release admission via `finally` — and returns [ConfigurationAdmission.Completed].
     */
    suspend fun <T> tryRun(
        operation: ConfigurationOperation,
        block: suspend () -> T,
    ): ConfigurationAdmission<T> {
        if (!mutex.tryLock()) {
            return ConfigurationAdmission.Busy(active.get() ?: operation)
        }

        active.set(operation)
        return try {
            ConfigurationAdmission.Completed(block())
        } finally {
            active.set(null)
            mutex.unlock()
        }
    }

    /** Read-only visibility for tests; never mutated outside [tryRun]. */
    internal fun activeOperationForTest(): ConfigurationOperation? = active.get()
}
