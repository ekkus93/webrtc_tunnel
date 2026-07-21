package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * FIX6 P0-006-B: the network monitor supervisor must make every monitor-lifecycle failure
 * visible, fail closed, retry with bounded backoff, and exit immediately on cancellation.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkMonitorSupervisorTest {
    private val sampleStatus: NetworkPolicyStatus =
        NetworkPolicyManager.evaluate(NetworkType.UnmeteredWifi to false, allowMetered = false)

    private class RecordingReporter : NetworkPolicyDiagnosticReporter {
        val reports = mutableListOf<Pair<String, String>>()

        override fun report(
            code: String,
            message: String,
        ) {
            reports.add(code to message)
        }
    }

    private fun stopAfter(
        limit: Int,
        sink: MutableList<Long>,
    ): suspend (Long) -> Unit =
        { delayMs ->
            sink.add(delayMs)
            if (sink.size >= limit) throw CancellationException("stop after $limit backoffs")
        }

    private fun runToCompletion(supervisor: NetworkMonitorSupervisor) =
        runBlocking {
            try {
                supervisor.run()
            } catch (_: CancellationException) {
                // Expected: tests stop the infinite retry loop via the injected delay function.
            }
        }

    @Test
    fun registerFailurePublishesAndBlocksTunnel() {
        val reporter = RecordingReporter()
        val blocked = mutableListOf<String>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("register boom password=secret") } },
                reporter = reporter,
                onSignal = {},
                onMonitorFailure = {
                    blocked.add(it)
                    true
                },
                delayFn = { throw CancellationException("stop") },
            )

        runToCompletion(supervisor)

        assertEquals("network_policy_monitor_failed", reporter.reports.single().first)
        assertFalse("diagnostic must be redacted", reporter.reports.single().second.contains("secret"))
        assertEquals("tunnel must be blocked on register failure", 1, blocked.size)
    }

    @Test
    fun upstreamCollectionFailurePublishesAndBlocksTunnel() {
        val reporter = RecordingReporter()
        val blocked = mutableListOf<String>()
        var signals = 0
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = {
                    flow<NetworkPolicyStatus> {
                        emit(sampleStatus)
                        throw IOException("upstream boom token=leak")
                    }
                },
                reporter = reporter,
                onSignal = { signals++ },
                onMonitorFailure = {
                    blocked.add(it)
                    true
                },
                delayFn = { throw CancellationException("stop") },
            )

        runToCompletion(supervisor)

        assertEquals("the emitted event was handled before the upstream failed", 1, signals)
        assertEquals("network_policy_monitor_failed", reporter.reports.single().first)
        assertFalse(reporter.reports.single().second.contains("leak"))
        assertEquals(1, blocked.size)
    }

    private class ThrowingReporter : NetworkPolicyDiagnosticReporter {
        override fun report(
            code: String,
            message: String,
        ) {
            error("reporter boom")
        }
    }

    // FIX7 P0-009-E: a reporter that throws must not prevent onMonitorFailure (the fail-closed
    // block) from being invoked on a register failure — reporting and fail-closing are
    // independent, and reportNetworkDiagnosticSafely must swallow the reporter's own exception.
    @Test
    fun registerFailureBlocksTunnelEvenWhenReporterThrows() {
        val blocked = mutableListOf<String>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("register boom") } },
                reporter = ThrowingReporter(),
                onSignal = {},
                onMonitorFailure = {
                    blocked.add(it)
                    true
                },
                delayFn = { throw CancellationException("stop") },
            )

        runToCompletion(supervisor)

        assertEquals("tunnel must be blocked even though the reporter threw", 1, blocked.size)
    }

    // FIX7 P0-009-E: same as above, but for an upstream collection failure after at least one
    // successful event.
    @Test
    fun upstreamFailureBlocksTunnelEvenWhenReporterThrows() {
        val blocked = mutableListOf<String>()
        var signals = 0
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = {
                    flow<NetworkPolicyStatus> {
                        emit(sampleStatus)
                        throw IOException("upstream boom")
                    }
                },
                reporter = ThrowingReporter(),
                onSignal = { signals++ },
                onMonitorFailure = {
                    blocked.add(it)
                    true
                },
                delayFn = { throw CancellationException("stop") },
            )

        runToCompletion(supervisor)

        assertEquals(1, signals)
        assertEquals("tunnel must be blocked even though the reporter threw", 1, blocked.size)
    }

    @Test
    fun monitorRetriesWithBoundedBackoff() {
        val delays = mutableListOf<Long>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("boom") } },
                reporter = RecordingReporter(),
                onSignal = {},
                onMonitorFailure = { true },
                backoff = BoundedExponentialBackoff(baseMs = 100, maxMs = 800),
                delayFn = stopAfter(limit = 5, sink = delays),
            )

        runToCompletion(supervisor)

        // Exponential, monotonic non-decreasing, capped at maxMs.
        assertEquals(listOf(100L, 200L, 400L, 800L, 800L), delays)
    }

    @Test
    fun successfulEventResetsBackoff() {
        val delays = mutableListOf<Long>()
        val round = AtomicInteger(0)
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = {
                    flow {
                        when (round.getAndIncrement()) {
                            // round 1 emits a good event (which must reset the backoff) then fails.
                            1 -> {
                                emit(sampleStatus)
                                throw IOException("boom-after-success")
                            }
                            else -> throw IOException("boom")
                        }
                    }
                },
                reporter = RecordingReporter(),
                onSignal = {},
                onMonitorFailure = { true },
                backoff = BoundedExponentialBackoff(baseMs = 100, maxMs = 10_000),
                delayFn = stopAfter(limit = 3, sink = delays),
            )

        runToCompletion(supervisor)

        // round 0 fail -> delayFor(0)=100; round 1 emits (resets) then fails -> delayFor(0)=100;
        // round 2 fail -> delayFor(1)=200. Without the reset, the middle value would be 200.
        assertEquals(listOf(100L, 100L, 200L), delays)
    }

    @Test
    fun monitorCancellationDoesNotPublishFailureOrRetry() {
        val reporter = RecordingReporter()
        val blocked = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw CancellationException("cancelled") } },
                reporter = reporter,
                onSignal = {},
                onMonitorFailure = {
                    blocked.add(it)
                    true
                },
                delayFn = { delays.add(it) },
            )

        runToCompletion(supervisor)

        assertTrue("cancellation must not report a failure", reporter.reports.isEmpty())
        assertTrue("cancellation must not block the tunnel", blocked.isEmpty())
        assertTrue("cancellation must not retry", delays.isEmpty())
    }

    @Test
    fun serviceDoesNotRemainRunningUnrestrictedAfterMonitorFailure() {
        // The fail-closed block must happen BEFORE the retry wait, so the tunnel is never left
        // unrestricted while the supervisor backs off.
        val order = mutableListOf<String>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("boom") } },
                reporter = RecordingReporter(),
                onSignal = {},
                onMonitorFailure = {
                    order.add("block")
                    true
                },
                delayFn = {
                    order.add("delay")
                    throw CancellationException("stop")
                },
            )

        runToCompletion(supervisor)

        assertEquals(listOf("block", "delay"), order)
    }

    // FIX7 P0-009-C: onMonitorFailure (fail-closed repository update + policy-blocked submission)
    // must run before the monitor-failure reporter call, matching P0-009's required ordering.
    @Test
    fun monitorFailureSubmitsPolicyBlockedBeforeReporting() {
        val order = mutableListOf<String>()
        val reporter =
            NetworkPolicyDiagnosticReporter { code, _ ->
                order.add("report:$code")
            }
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("boom") } },
                reporter = reporter,
                onSignal = {},
                onMonitorFailure = {
                    order.add("submit")
                    true
                },
                delayFn = { throw CancellationException("stop") },
            )

        runToCompletion(supervisor)

        assertEquals(listOf("submit", "report:network_policy_monitor_failed"), order)
    }

    // FIX7 P0-009-C: when the fail-closed policy-blocked command cannot be submitted (the
    // lifecycle processor/control plane is unavailable), the supervisor must stop retrying
    // instead of backing off forever against a dead control plane — and the failure must still
    // be visible via the reporter.
    @Test
    fun failedPolicyBlockedSubmissionStopsSupervisorAndIsVisible() {
        val reporter = RecordingReporter()
        val delays = mutableListOf<Long>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("boom") } },
                reporter = reporter,
                onSignal = {},
                onMonitorFailure = { false },
                delayFn = { delays.add(it) },
            )

        runToCompletion(supervisor)

        assertTrue(
            "the supervisor must not retry once the lifecycle processor is unavailable",
            delays.isEmpty(),
        )
        assertEquals(
            "the monitor failure must still be reported even though the fail-closed submission failed",
            "network_policy_monitor_failed",
            reporter.reports.single().first,
        )
    }

    // FIX7 P0-009-C: cancellation must exit without ever invoking the reporter, even one that
    // would throw — proving the cancellation branch is fully separate from the failure-reporting
    // path (no code path there could accidentally call a throwing reporter).
    @Test
    fun monitorCancellationWithThrowingReporterStillExitsWithoutRetry() {
        val delays = mutableListOf<Long>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw CancellationException("cancelled") } },
                reporter = ThrowingReporter(),
                onSignal = {},
                onMonitorFailure = { true },
                delayFn = { delays.add(it) },
            )

        runToCompletion(supervisor)

        assertTrue("cancellation must not retry, even with a throwing reporter", delays.isEmpty())
    }

    // FIX7 P0-009-D.
    @Test
    fun invalidBackoffParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedExponentialBackoff(baseMs = 0, maxMs = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedExponentialBackoff(baseMs = -5, maxMs = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedExponentialBackoff(baseMs = 1_000, maxMs = 500)
        }
    }

    // FIX7 P0-009-D: a large baseMs pushed through many attempts must not silently wrap to a
    // negative delay (which coerceIn would then clamp to baseMs, producing no backoff growth) —
    // it must clamp to maxMs instead.
    @Test
    fun backoffCalculationIsCappedAndCannotOverflow() {
        val backoff = BoundedExponentialBackoff(baseMs = Long.MAX_VALUE / 2, maxMs = Long.MAX_VALUE)

        val delay = backoff.delayFor(attempt = 32)

        assertEquals(Long.MAX_VALUE, delay)
        assertTrue("the calculated delay must never be negative", delay >= 0)
    }
}
