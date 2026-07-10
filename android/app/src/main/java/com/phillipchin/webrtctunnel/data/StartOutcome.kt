package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting

/**
 * Typed start outcome for JNI operations.
 *
 * Moves the classification closer to the JNI boundary, making the coordinator
 * use this typed outcome instead of post-hoc classification.
 */
sealed interface StartOutcome {
    /**
     * Successful start with verified active state.
     */
    data object VerifiedSuccess : StartOutcome

    /**
     * Native JNI operation failed.
     */
    data class NativeFailure(
        val error: Throwable,
    ) : StartOutcome

    /**
     * Native succeeded but status verification failed.
     */
    data class VerificationFailure(
        val error: StartStatusVerificationException,
    ) : StartOutcome

    /**
     * Unexpected failure during startup.
     */
    data class UnexpectedFailure(
        val error: Throwable,
    ) : StartOutcome

    /**
     * Startup was aborted by control flow (e.g., stale generation).
     */
    data object Aborted : StartOutcome
}

/**
 * Classifies a repository start result into a typed outcome.
 */
fun classifyStartResult(result: Result<Unit>): StartOutcome =
    result.fold(
        onSuccess = { StartOutcome.VerifiedSuccess },
        onFailure = { error ->
            if (error is StartStatusVerificationException) {
                StartOutcome.VerificationFailure(error)
            } else {
                StartOutcome.NativeFailure(error)
            }
        },
    )

/**
 * Typed result for tunnel start operations.
 * Combines the outcome with the mode for context.
 */
data class StartResult(
    val mode: TunnelMode,
    val outcome: StartOutcome,
    val verifiedState: ServiceState? = null,
) {
    /**
     * Returns true if the start was verified as successful.
     */
    fun isSuccess(): Boolean = outcome is StartOutcome.VerifiedSuccess

    /**
     * Returns the error message if the start failed.
     */
    fun errorMessage(): String? =
        when (outcome) {
            is StartOutcome.VerifiedSuccess -> null
            is StartOutcome.NativeFailure -> outcome.error.message
            is StartOutcome.VerificationFailure -> outcome.error.message
            is StartOutcome.UnexpectedFailure -> outcome.error.message
            is StartOutcome.Aborted -> null
        }
}