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
    init {
        require(baseMs > 0) { "initialDelayMs must be > 0, was $baseMs" }
        require(maxMs >= baseMs) { "maxDelayMs ($maxMs) must be >= initialDelayMs ($baseMs)" }
    }

    override fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        // FIX7 P0-009-D: `baseMs shl shift` wraps silently on overflow rather than throwing,
        // and a wrapped-negative `scaled` would make `coerceIn(baseMs, maxMs)` return `baseMs`
        // (the *minimum* bound a Long.coerceIn falls back to when the value is below it) —
        // silently producing no backoff growth at all instead of the intended `maxMs` cap.
        // Detecting the overflow explicitly and substituting Long.MAX_VALUE keeps the value
        // above `maxMs`, so coerceIn correctly clamps to the cap either way.
        val scaled =
            if (shift > 0 && baseMs > (Long.MAX_VALUE shr shift)) {
                Long.MAX_VALUE
            } else {
                baseMs shl shift
            }
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
 * - fails closed by invoking [onMonitorFailure] **before** reporting or the retry wait, so the
 *   tunnel is never left unrestricted during backoff,
 * - reports every non-cancellation failure via [reporter] (`network_policy_monitor_failed`)
 *   through [reportNetworkDiagnosticSafely], so a throwing reporter can never escape this loop,
 * - retries with bounded [backoff] only while [onMonitorFailure] reports the lifecycle
 *   processor/control plane is still available (FIX7 P0-009-C) — if it isn't, the loop exits
 *   without retrying, since the failing fail-closed submission means the service is already
 *   tearing down and further retries would be pointless,
 * - resets the attempt counter after a fully successful event,
 * - and exits immediately on cancellation without reporting a failure or retrying.
 *
 * Extracted from `TunnelForegroundService` (RESPONSES Q1): the service sits at detekt's function
 * limit, and this also makes the lifecycle unit-testable with virtual time via [delayFn].
 */
class NetworkMonitorSupervisor(
    private val monitorFlow: () -> Flow<NetworkPolicyStatus>,
    private val reporter: NetworkPolicyDiagnosticReporter,
    private val onSignal: suspend () -> Unit,
    private val onMonitorFailure: suspend (String) -> Boolean,
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
                val lifecycleProcessorAvailable = onMonitorFailure(message)
                reportNetworkDiagnosticSafely(reporter, code = "network_policy_monitor_failed", message = message)
                if (!lifecycleProcessorAvailable) return
                delayFn(backoff.delayFor(attempt++))
            }
        }
    }
}
