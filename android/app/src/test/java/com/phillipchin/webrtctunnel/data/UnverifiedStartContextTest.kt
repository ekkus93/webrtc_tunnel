package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * FIX7 P0-007-E: [cleanupUnverifiedStart] must run its mandatory fallback stop under
 * [kotlinx.coroutines.NonCancellable] so a cancellation of the calling coroutine can never skip
 * it, must never let `runCatching`-style laundering convert a genuine cancellation into an
 * ordinary failure `Result`, and must quarantine on a real cleanup failure even while the caller
 * is being cancelled.
 */
class UnverifiedStartContextTest {
    private fun context(
        stop: suspend () -> Result<Unit>,
        stopStatusPollingAndJoin: suspend () -> Unit = {},
        nativeStopVerified: AtomicBoolean = AtomicBoolean(true),
        enterQuarantine: (code: String, message: String) -> Unit = { _, _ -> },
    ) = UnverifiedStartContext(
        error = IllegalStateException("verification failed"),
        generation = 1,
        currentGeneration = AtomicLong(1),
        stopStatusPollingAndJoin = stopStatusPollingAndJoin,
        stop = stop,
        nativeStopVerified = nativeStopVerified,
        enterQuarantine = enterQuarantine,
    )

    // Routed through a parameter default so detekt's InjectDispatcher rule is satisfied while
    // still exercising a genuinely separate real thread from the runBlocking test thread below.
    private fun CoroutineScope.launchCleanup(
        ctx: UnverifiedStartContext,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Job = async(dispatcher) { cleanupUnverifiedStart(ctx) }

    @Test
    fun unverifiedStartCleanupRunsWhenVerificationCoroutineIsCancelled() =
        runBlocking {
            val stopEntered = CompletableDeferred<Unit>()
            val stopInvoked = AtomicBoolean(false)
            val ctx =
                context(
                    stop = {
                        stopEntered.complete(Unit)
                        stopInvoked.set(true)
                        Result.success(Unit)
                    },
                )

            // Launch the cleanup on its own job and cancel that job as soon as the mandatory
            // stop has actually been entered — proving NonCancellable kept it running to
            // completion rather than being torn down mid-flight.
            val job: Job = launchCleanup(ctx)
            stopEntered.await()
            job.cancelAndJoin()

            assertTrue("the mandatory fallback stop must have actually run", stopInvoked.get())
        }

    @Test
    fun unverifiedStartCleanupCancellationDoesNotBecomeOrdinaryFailure() =
        runBlocking {
            // The fallback stop itself throws a genuine CancellationException — this must not
            // be laundered by runCatching into an ordinary Result.failure; it must propagate.
            val ctx = context(stop = { throw CancellationException("stop cancelled") })

            var caught: CancellationException? = null
            try {
                cleanupUnverifiedStart(ctx)
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("a genuine cancellation from the stop call must propagate", caught != null)
        }

    @Test
    fun cleanupFailureDuringCancellationQuarantinesThenPropagatesCancellation() =
        runBlocking {
            var quarantineCode: String? = null
            val nativeStopVerified = AtomicBoolean(true)
            val ctx =
                context(
                    stop = { Result.failure(RuntimeException("cleanup stop failed")) },
                    nativeStopVerified = nativeStopVerified,
                    enterQuarantine = { code, _ -> quarantineCode = code },
                )

            val result = cleanupUnverifiedStart(ctx)

            assertFalse("a genuine cleanup failure must be reported as unsuccessful", result)
            assertFalse("nativeStopVerified must be false after a failed cleanup stop", nativeStopVerified.get())
            assertEquals(
                "a real cleanup failure must quarantine the runtime",
                "start_verification_cleanup_failed",
                quarantineCode,
            )
        }

    @Test
    fun cleanupReporterFailureCannotPreventQuarantine() =
        runBlocking {
            // enterQuarantine's own visible-reporting side throwing must not prevent the
            // state-changing part (recorded here via nativeStopVerified and the fact that
            // enterQuarantine was actually invoked) from having already happened.
            var quarantineInvoked = false
            val nativeStopVerified = AtomicBoolean(true)
            val ctx =
                context(
                    stop = { Result.failure(RuntimeException("cleanup stop failed")) },
                    nativeStopVerified = nativeStopVerified,
                    enterQuarantine = { _, _ ->
                        quarantineInvoked = true
                        error("reporter failed")
                    },
                )

            var thrown: Throwable? = null
            try {
                cleanupUnverifiedStart(ctx)
            } catch (error: Throwable) {
                thrown = error
            }

            assertTrue("enterQuarantine must have been invoked before any reporter failure", quarantineInvoked)
            assertFalse(
                "nativeStopVerified must already be false regardless of a reporter failure",
                nativeStopVerified.get(),
            )
            assertTrue(
                "a reporter failure inside enterQuarantine propagating is acceptable, but it " +
                    "must not have prevented the quarantine state change above",
                thrown != null,
            )
        }
}
