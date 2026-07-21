package com.phillipchin.webrtctunnel.data

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "SnackbarController"

/**
 * App-wide one-shot user messages surfaced as snackbars. Backed by a [SharedFlow] with no
 * replay, so a message is shown exactly once and never re-appears on recomposition or when
 * navigating back to a screen (unlike a `StateFlow`, which retains its last value). Emitting
 * is non-suspending and drops the oldest buffered message under burst, so callers in any
 * context can fire-and-forget.
 *
 * FIX7 P1-005-C: this is convenience-only and lossy by design — a message emitted with no
 * active collector (e.g. no screen currently observing [messages]) is gone forever, and even
 * with a collector attached, a burst past the buffer silently drops the oldest. No REQUIRED
 * failure may exist only here: every caller that reports a failure a user must be able to see
 * (not just a transient confirmation) is expected to also record it in its own durable
 * `StateFlow` state (the established `lastOperationFailure`/`errorMessage` pattern used
 * throughout the ViewModels) *before* calling [show] — this class has no way to enforce that,
 * it can only guarantee it never becomes the sole record itself by not returning a false sense
 * of delivery. [show] intentionally returns nothing to consume: it must never be mistaken for
 * an acknowledged, durable write.
 */
class SnackbarController {
    private val _messages =
        MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun show(message: String) {
        // DROP_OLDEST means tryEmit only fails if this SharedFlow's buffering configuration
        // changes in a way that removes that drop policy — logged (not escalated) so a future
        // regression is visible without pretending this call site can do anything about it;
        // the message's own durable-state owner (if any) already has the failure recorded.
        if (!_messages.tryEmit(message)) {
            Log.d(TAG, "Dropped a snackbar message (no drop policy could accept it)")
        }
    }
}
