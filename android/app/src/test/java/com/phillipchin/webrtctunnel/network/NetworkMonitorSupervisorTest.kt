package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                onMonitorFailure = { blocked.add(it) },
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
                onMonitorFailure = { blocked.add(it) },
                delayFn = { throw CancellationException("stop") },
            )

        runToCompletion(supervisor)

        assertEquals("the emitted event was handled before the upstream failed", 1, signals)
        assertEquals("network_policy_monitor_failed", reporter.reports.single().first)
        assertFalse(reporter.reports.single().second.contains("leak"))
        assertEquals(1, blocked.size)
    }

    @Test
    fun monitorRetriesWithBoundedBackoff() {
        val delays = mutableListOf<Long>()
        val supervisor =
            NetworkMonitorSupervisor(
                monitorFlow = { flow { throw IOException("boom") } },
                reporter = RecordingReporter(),
                onSignal = {},
                onMonitorFailure = {},
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
                onMonitorFailure = {},
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
                onMonitorFailure = { blocked.add(it) },
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
                onMonitorFailure = { order.add("block") },
                delayFn = {
                    order.add("delay")
                    throw CancellationException("stop")
                },
            )

        runToCompletion(supervisor)

        assertEquals(listOf("block", "delay"), order)
    }
}
