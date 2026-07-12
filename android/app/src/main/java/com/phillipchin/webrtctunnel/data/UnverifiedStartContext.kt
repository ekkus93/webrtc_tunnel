package com.phillipchin.webrtctunnel.data

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
    if (context.generation != context.currentGeneration.get()) return false
    context.stopStatusPollingAndJoin()
    val stopResult = runCatching { context.stop() }
    stopResult.onFailure {
        context.nativeRuntimeUncertain.set(true)
        context.publishError(
            it.message ?: "Failed to cleanup unverified start",
            "start_verification_cleanup_failed",
        )
    }
    context.nativeStopVerified.set(stopResult.isSuccess)
    return stopResult.isSuccess
}
