package com.phillipchin.webrtctunnel.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Context for handling a [StartOutcome.NativeFailure] that completed while a policy
 * retry may be pending (P0-001). Declared outside [com.phillipchin.webrtctunnel.TunnelForegroundService]
 * (mirroring [UnverifiedStartContext]/[cleanupUnverifiedStart]) to keep that class under
 * detekt's TooManyFunctions threshold.
 */
data class NativeFailureAfterStartupContext(
    val error: Throwable,
    val generation: Long,
    val pendingPolicyResumeGeneration: AtomicReference<Long?>,
    val pausedByPolicy: AtomicBoolean,
    val submitRetryPolicyResume: (generation: Long) -> Unit,
    val publishError: (message: String, code: String) -> Unit,
)

/**
 * Consumes (reads + clears) the pending policy retry before deciding whether to resume.
 * Resuming also requires [NativeFailureAfterStartupContext.pausedByPolicy] — the pending
 * generation match alone doesn't prove the service is still policy-paused at the moment
 * this completion is processed, since state can change between the network event that
 * recorded the pending retry and this startup's completion.
 */
fun handleNativeFailureAfterStartup(context: NativeFailureAfterStartupContext) {
    val pending = context.pendingPolicyResumeGeneration.getAndSet(null)
    if (pending == context.generation && context.pausedByPolicy.get()) {
        context.submitRetryPolicyResume(context.generation)
        return
    }
    context.publishError(
        context.error.message ?: "Unable to start tunnel",
        "native_start_failed",
    )
}
