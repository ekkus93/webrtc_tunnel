package com.phillipchin.webrtctunnel.data

import org.junit.Test

/**
 * FIX7 P1-005-C: [SnackbarController] is lossy/convenience-only by design — a message
 * emitted with no collector, or past its buffer capacity, is simply gone. This only proves
 * the mechanical property that dropping never throws/blocks; [snackbarDropDoesNotEraseDurableFailure]
 * in `NetworkPolicyViewModelTest` proves the property that actually matters — a caller's own
 * durable failure state survives regardless of snackbar traffic, since `SnackbarController`
 * exposes no way to reach into it at all.
 */
class SnackbarControllerTest {
    @Test
    fun showNeverThrowsEvenPastBufferCapacityWithNoCollector() {
        val snackbar = SnackbarController()

        // Far more messages than the internal buffer can hold, with no collector attached —
        // must not throw, block, or otherwise misbehave; excess messages are simply dropped.
        repeat(50) { index -> snackbar.show("message $index") }
    }
}
