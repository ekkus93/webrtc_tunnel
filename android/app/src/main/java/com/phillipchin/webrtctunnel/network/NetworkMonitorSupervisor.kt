package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

/** Bounded retry backoff for the network monitor. Injected so tests use virtual time. */
fun interface NetworkMonitorBackoff {
    fun delayFor(attempt: Int): Long
}

/**
 * Exponential backoff capped at [maxMs]. `delayFor(attempt)` = `baseMs * 2^attempt`, clamped to
 * `[baseMs, maxMs]`, so retries never grow unbounded and never busy-loop.
 */
class BoundedExponentialBackoff(
    private val baseMs: Long = DEFAULT_BASE_MS,
    private val maxMs: Long = DEFAULT_MAX_MS,
) : NetworkMonitorBackoff {
    override fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        val scaled = baseMs shl shift
        return scaled.coerceIn(baseMs, maxMs)
    }

    private companion object {
        const val DEFAULT_BASE_MS = 1_000L
        const val DEFAULT_MAX_MS = 30_000L
        const val MAX_SHIFT = 16
    }
}

/**
 * Supervises the network-policy monitor for its whole lifecycle (FIX6 P0-006-B / INV-006).
 *
 * The previous `runCatching` sat inside `collect`, so it caught only per-event failures and
 * missed callback registration, upstream, and unregister failures — the monitor could die while
 * the service kept running unrestricted. This supervisor wraps the entire
 * `monitor().collect { … }` in a retry loop that:
 *
 * - reports every non-cancellation failure via [reporter] (`network_policy_monitor_failed`),
 * - fails closed by invoking [onMonitorFailure] **before** the retry wait, so the tunnel is
 *   never left unrestricted during backoff,
 * - retries with bounded [backoff], resetting the attempt counter after a fully successful event,
 * - and exits immediately on cancellation without reporting a failure or retrying.
 *
 * Extracted from `TunnelForegroundService` (RESPONSES Q1): the service sits at detekt's function
 * limit, and this also makes the lifecycle unit-testable with virtual time via [delayFn].
 */
class NetworkMonitorSupervisor(
    private val monitorFlow: () -> Flow<NetworkPolicyStatus>,
    private val reporter: NetworkPolicyDiagnosticReporter,
    private val onSignal: suspend () -> Unit,
    private val onMonitorFailure: suspend (String) -> Unit,
    private val backoff: NetworkMonitorBackoff = BoundedExponentialBackoff(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                monitorFlow().collect {
                    onSignal()
                    // A fully successful event (emission + handling) resets the backoff.
                    attempt = 0
                }
                error("Network policy monitor completed unexpectedly")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message =
                    SensitiveDataRedactor.redactText(error.message ?: "Network policy monitor failed")
                reporter.report(code = "network_policy_monitor_failed", message = message)
                onMonitorFailure(message)
                delayFn(backoff.delayFor(attempt++))
            }
        }
    }
}
