package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Context for cleaning up an unverified start (P0-005).
 */
data class UnverifiedStartContext(
    val error: Throwable,
    val generation: Long,
    val currentGeneration: AtomicLong,
    val stopStatusPollingAndJoin: suspend () -> Unit,
    val stop: suspend () -> Unit,
    val nativeStopVerified: AtomicBoolean,
    val nativeRuntimeUncertain: AtomicBoolean,
    val publishError: (message: String, code: String) -> Unit,
)

/**
 * Cleans up an unverified start by performing a fallback stop.
 * Returns true if cleanup succeeded, false otherwise.
 */
suspend fun cleanupUnverifiedStart(context: UnverifiedStartContext): Boolean {
    val result = runCatching {
        if (context.generation == context.currentGeneration.get()) {
            context.stopStatusPollingAndJoin()
            context.stop()
        }
    }
    result.fold(
        onSuccess = {
            context.nativeStopVerified.set(true)
        },
        onFailure = {
            context.nativeRuntimeUncertain.set(true)
            context.publishError(
                it.message ?: "Failed to cleanup unverified start",
                "unverified_start_cleanup_failed",
            )
        },
    )
    return result.isSuccess
}