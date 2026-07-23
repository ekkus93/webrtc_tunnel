package com.phillipchin.webrtctunnel

import android.os.Looper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows

/**
 * FIX8 P1-004-C: the one shared implementation behind every per-file `awaitXyz` polling helper
 * across the Robolectric ViewModel/integration tests. Its bounded purpose is strictly *positive
 * external convergence*: proving a real dispatcher hop, `viewModelScope.launch`, or async
 * write that is already known to be in flight eventually lands — never proving a negative
 * ("nothing happened"), which has no event to converge on and belongs to a small bounded pump
 * (`repeat(n) { idle(); delay(...) }`) at the call site instead.
 *
 * Each poll pumps the Robolectric main looper (so queued `Handler`/main-dispatcher work runs)
 * and then waits [pollIntervalMs] before re-checking, bounded overall by [timeoutMs] of real
 * wall-clock time. A timeout surfaces as an [AssertionError] naming [description], rather than
 * kotlinx.coroutines's generic "Timed out waiting for N ms", so a failing test says what it was
 * actually waiting for.
 *
 * @param currentValue reads the value to test; may itself suspend (e.g. a `Flow.first()` read).
 * @param predicate returns true once [currentValue] has converged to the awaited condition.
 */
fun <T> awaitCondition(
    timeoutMs: Long = 5_000,
    pollIntervalMs: Long = 10,
    description: String = "condition",
    currentValue: suspend () -> T,
    predicate: (T) -> Boolean,
): T =
    runBlocking {
        try {
            withTimeout(timeoutMs) {
                var current = currentValue()
                while (!predicate(current)) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(pollIntervalMs)
                    current = currentValue()
                }
                current
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for $description", timeout)
        }
    }

/**
 * Boolean-predicate convenience overload for callers with no separate "current value" to thread
 * through a second lambda — e.g. `awaitCondition { viewModel.message.value != null }`. See the
 * primary [awaitCondition] overload for the shared bounded-polling contract this implements.
 */
fun awaitCondition(
    timeoutMs: Long = 5_000,
    pollIntervalMs: Long = 10,
    description: String = "condition",
    predicate: suspend () -> Boolean,
) {
    awaitCondition(
        timeoutMs = timeoutMs,
        pollIntervalMs = pollIntervalMs,
        description = description,
        currentValue = predicate,
        predicate = { it },
    )
}
