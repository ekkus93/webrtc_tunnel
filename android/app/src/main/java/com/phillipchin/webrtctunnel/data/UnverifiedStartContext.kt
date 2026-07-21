package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Context for cleaning up an unverified start (P0-005 / FIX7 P0-007-E).
 *
 * [stop] returns a [Result] rather than throwing on failure — [cleanupUnverifiedStart] must be
 * able to distinguish a genuine stop failure (a normal `Result.failure`) from this coroutine
 * itself being cancelled, so the fallback stop is never wrapped in a `runCatching` that would
 * silently convert a real cancellation into an ordinary failure Result.
 */
data class UnverifiedStartContext(
    val error: Throwable,
    val generation: Long,
    val currentGeneration: AtomicLong,
    val stopStatusPollingAndJoin: suspend () -> Unit,
    val stop: suspend () -> Result<Unit>,
    val nativeStopVerified: AtomicBoolean,
    val enterQuarantine: (code: String, message: String) -> Unit,
)

/**
 * Cleans up an unverified start by performing a mandatory fallback stop. FIX7 P0-007-E: this
 * cleanup — including the status-poll join — runs entirely under [NonCancellable], so a
 * cancellation of the calling coroutine (e.g. because the service is being destroyed) can never
 * skip the mandatory native stop partway through; the ambient cancellation still propagates
 * normally once this call returns, at the caller's next suspension point. [context.stop] is
 * called directly (not through `runCatching`), so a genuine cancellation thrown by it is not
 * silently converted into an ordinary failure `Result`.
 *
 * Returns true if cleanup succeeded, false otherwise.
 */
suspend fun cleanupUnverifiedStart(context: UnverifiedStartContext): Boolean =
    withContext(NonCancellable) {
        if (context.generation != context.currentGeneration.get()) return@withContext false
        context.stopStatusPollingAndJoin()
        val stopResult =
            try {
                context.stop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        context.nativeStopVerified.set(stopResult.isSuccess)
        stopResult.onFailure {
            context.enterQuarantine(
                "start_verification_cleanup_failed",
                it.message ?: "Failed to cleanup unverified start",
            )
        }
        stopResult.isSuccess
    }
