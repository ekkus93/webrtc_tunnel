package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import kotlinx.coroutines.CancellationException

/**
 * Dependencies for the post-guard half of `handlePolicyAllowed` (FIX6 P0-004). Declared
 * as a context object (like [NativeFailureAfterStartupContext]) so the logic can live at
 * top level, keeping `TunnelForegroundService`'s coordinator object under detekt's
 * TooManyFunctions threshold.
 */
class PolicyAllowedResumeContext(
    val readPreferences: suspend () -> AndroidAppPreferences,
    val invalidatePendingRetry: () -> Unit,
    val publishError: (code: String, message: String) -> Unit,
    val recordPendingRetry: () -> Unit,
    val hasActiveStartup: () -> Boolean,
    val resume: suspend () -> Unit,
)

/**
 * Runs the preference-gated resume decision after the runtime guard and policy-paused
 * checks have already passed.
 *
 * - A preference read failure publishes `policy_allowed_preference_read_failed` and
 *   invalidates the pending retry; cancellation propagates rather than being reported.
 * - `resumeOnUnmetered == false` invalidates any pending retry (INV-006: the latest
 *   preference wins, so a token recorded under an older `true` cannot auto-resume later).
 * - Otherwise resume now, or record a pending retry if a startup is already in flight.
 */
suspend fun resumeOnPolicyAllowedIfPreferred(context: PolicyAllowedResumeContext) {
    val prefs =
        try {
            context.readPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            context.invalidatePendingRetry()
            context.publishError(
                "policy_allowed_preference_read_failed",
                SensitiveDataRedactor.redactText(
                    error.message ?: "Failed to read network policy preferences",
                ),
            )
            return
        }

    if (!prefs.resumeOnUnmetered) {
        context.invalidatePendingRetry()
        return
    }

    if (context.hasActiveStartup()) {
        context.recordPendingRetry()
    } else {
        context.invalidatePendingRetry()
        context.resume()
    }
}
