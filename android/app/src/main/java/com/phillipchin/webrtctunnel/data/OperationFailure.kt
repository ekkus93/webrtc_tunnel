package com.phillipchin.webrtctunnel.data

/**
 * FIX6 P1-008: a durable, redacted record of the last failed mutating operation on a screen.
 *
 * Required operation failures must survive in ViewModel state, not only in a one-shot snackbar:
 * a snackbar is dismissed or missed if no collector is subscribed (e.g. across recreation), so
 * the snackbar mirrors this state but never owns the only copy. [message] is already redacted
 * before assignment; [code] is a stable machine-readable identifier for the failure kind.
 */
data class OperationFailure(
    val code: String,
    val message: String,
)
