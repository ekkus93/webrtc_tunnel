package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FIX8 P0-009 (CRITICAL-6/HIGH-10): a point-in-time view of [NativeRuntimeSafetyState].
 * [generation] increases on every transition, so a stale reader (e.g. a service instance from
 * before an Android-driven recreation) can tell whether a newer instance has already changed
 * this state before it acts on it.
 */
internal data class NativeRuntimeSafetySnapshot(
    val quarantined: Boolean,
    val stopVerified: Boolean,
    val code: String?,
    val message: String?,
    val generation: Long,
)

/**
 * FIX8 P0-009: the single application-scoped owner of native-runtime quarantine/stop-
 * verification state (CRITICAL-6/HIGH-10). This previously lived on `TunnelForegroundService`
 * itself, so Android recreating the service (a routine lifecycle event) silently reset it to
 * "not quarantined" — losing the guarantee that a stop-like failure blocks every future start
 * until a verified explicit STOP. Living here instead means every service instance across a
 * recreation reads and writes through the same owner, held by [AppDependencies].
 *
 * The four transitions below are the load-bearing distinction — deliberately not one generic
 * setter — matching the four cases scattered across `TunnelForegroundService`/`OfferCoordinator`
 * before this change: only [markVerifiedExplicitStop] may ever clear
 * [NativeRuntimeSafetySnapshot.quarantined].
 */
internal class NativeRuntimeSafetyState {
    private val lock = Any()
    private val mutableState =
        MutableStateFlow(
            NativeRuntimeSafetySnapshot(
                quarantined = false,
                stopVerified = true,
                code = null,
                message = null,
                generation = 0,
            ),
        )
    val state: StateFlow<NativeRuntimeSafetySnapshot> = mutableState.asStateFlow()

    /** A new native start attempt begins: stop is no longer verified, but a pre-existing
     * quarantine (from an unrelated earlier failure) is not touched. */
    fun markStartAttempted() = update { it.copy(stopVerified = false, generation = it.generation + 1) }

    /** Every stop-like failure quarantines before it is reported (FIX7 P0-007-B/FIX8 P0-009-B). */
    fun quarantine(
        code: String,
        message: String,
    ) = update {
        it.copy(
            quarantined = true,
            stopVerified = false,
            code = code,
            message = SensitiveDataRedactor.redactText(message),
            generation = it.generation + 1,
        )
    }

    /** A successful pause, destroy-fallback stop, or start-verification cleanup observed the
     * runtime stopped, but must NOT clear a pre-existing quarantine from a different failure
     * (FIX8 P0-009-B) — only [markVerifiedExplicitStop] may do that. */
    fun markObservedStopWithoutRecovery() = update { it.copy(stopVerified = true, generation = it.generation + 1) }

    /**
     * Same as [markObservedStopWithoutRecovery], but only applies if the generation has not
     * advanced past [expectedGeneration] since the caller last observed it — protecting a stale
     * caller (e.g. a service instance whose destroy-time fallback stop is still in flight after
     * a newer instance already changed this shared state) from overwriting a newer transition
     * with outdated information (FIX8 P0-009-E). Returns whether the transition was applied.
     */
    fun markObservedStopWithoutRecoveryIfGenerationUnchanged(expectedGeneration: Long): Boolean =
        synchronized(lock) {
            val current = mutableState.value
            if (current.generation != expectedGeneration) {
                false
            } else {
                mutableState.value = current.copy(stopVerified = true, generation = current.generation + 1)
                true
            }
        }

    /** Only a verified explicit STOP clears quarantine. */
    fun markVerifiedExplicitStop() =
        update {
            NativeRuntimeSafetySnapshot(
                quarantined = false,
                stopVerified = true,
                code = null,
                message = null,
                generation = it.generation + 1,
            )
        }

    private inline fun update(transform: (NativeRuntimeSafetySnapshot) -> NativeRuntimeSafetySnapshot) {
        synchronized(lock) { mutableState.value = transform(mutableState.value) }
    }
}
