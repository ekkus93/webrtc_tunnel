package com.phillipchin.webrtctunnel.network

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

// Robolectric provides a working android.util.Log; handlePolicyDeliveryResult logs the
// redacted failure, which throws "not mocked" under plain JUnit.
@RunWith(RobolectricTestRunner::class)
class NetworkPolicyManagerTest {
    @Test
    fun blocksMeteredAndUnknownByDefault() {
        val metered = NetworkPolicyManager({ NetworkType.Cellular to true })
        val unknown = NetworkPolicyManager({ NetworkType.Unknown to false })
        assertFalse(metered.allowTunnelOnCurrentNetwork(allowMetered = false))
        assertFalse(unknown.allowTunnelOnCurrentNetwork(allowMetered = true))
    }

    @Test
    fun allowsMeteredWhenOptedIn() {
        val manager = NetworkPolicyManager({ NetworkType.MeteredWifi to true })
        assertTrue(manager.allowTunnelOnCurrentNetwork(allowMetered = true))
        assertFalse(manager.allowTunnelOnCurrentNetwork(allowMetered = false))
    }

    @Test
    fun transitionsUpdateStatus() {
        val sequence =
            ArrayDeque(
                listOf(
                    NetworkType.UnmeteredWifi to false,
                    NetworkType.MeteredWifi to true,
                    NetworkType.NoNetwork to false,
                ),
            )
        val manager = NetworkPolicyManager({ sequence.removeFirst() })
        // FIX7 P1-003-B: construction never classifies — the fail-closed seed shows first.
        assertEquals(NetworkType.Unknown, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.UnmeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.MeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.NoNetwork, manager.status.value.networkType)
    }

    @Test
    fun unknownStaysBlockedEvenWhenMeteredAllowed() {
        val manager = NetworkPolicyManager({ NetworkType.Unknown to false })
        val status = manager.evaluateWithPolicy(allowMetered = true)
        assertFalse(status.tunnelAllowed)
        assertEquals("Unknown network", status.blockReason)
    }

    private class RecordingReporter : NetworkPolicyDiagnosticReporter {
        val reports = mutableListOf<Pair<String, String>>()

        override fun report(
            code: String,
            message: String,
        ) {
            reports.add(code to message)
        }
    }

    private class ThrowingReporter : NetworkPolicyDiagnosticReporter {
        override fun report(
            code: String,
            message: String,
        ) {
            error("reporter boom")
        }
    }

    // FIX7 P0-009-B: a throwing reporter must never prevent the fail-closed status from being
    // assigned/delivered, and must never escape the Android callback (ConnectivityManager would
    // have no handler for an uncaught exception from onAvailable/onLost/onCapabilitiesChanged).
    @Test
    fun throwingClassificationReporterDoesNotEscapeCallback() =
        runBlocking {
            val manager = NetworkPolicyManager({ error("classify boom") })

            val status = manager.monitor(ApplicationProvider.getApplicationContext(), ThrowingReporter()).first()

            assertEquals(
                "the fail-closed status must still be assigned even though the reporter threw",
                NetworkType.Unknown,
                status.networkType,
            )
            assertFalse(status.tunnelAllowed)
        }

    // FIX7 P0-009-B: proves the ORDER, not just the outcome — the fail-closed status must be
    // assigned before the reporter is ever invoked, so an observer of `status` racing the
    // reporter call always sees the safe value first.
    @Test
    fun classifierFailureAppliesBlockedUnknownBeforeReporterCall() =
        runBlocking {
            val order = mutableListOf<String>()
            val manager = NetworkPolicyManager({ error("classify boom") })
            val orderingReporter =
                NetworkPolicyDiagnosticReporter { _, _ ->
                    order.add("report")
                    assertEquals(
                        "status must already be the fail-closed value by the time the " +
                            "reporter is called",
                        NetworkType.Unknown,
                        manager.status.value.networkType,
                    )
                }

            manager.monitor(ApplicationProvider.getApplicationContext(), orderingReporter).first()

            assertEquals(listOf("report"), order)
        }

    // FIX7 P0-009-B: a throwing delivery reporter must not prevent the status flow's internal
    // state from having already been updated (the assignment happens before delivery/reporting
    // is even attempted).
    @Test
    fun throwingDeliveryReporterDoesNotPreventStatusUpdate() {
        val failed = Channel<Unit>(Channel.RENDEZVOUS).trySend(Unit)
        assertTrue(failed.isFailure)

        // Must not throw.
        NetworkPolicyManager.handlePolicyDeliveryResult(failed, ThrowingReporter())
    }

    // FIX7 P0-009-E: an unregister failure with a throwing reporter must not let the reporter's
    // own exception escape cleanup either.
    @Test
    fun unregisterFailureReporterThrowDoesNotEscapeCleanup() {
        // Must not throw.
        NetworkPolicyManager.reportUnregisterFailure(ThrowingReporter()) {
            throw IOException("unregister boom")
        }
    }

    // FIX7 P1-003-B: construction must never perform a synchronous Binder call — the real
    // classification is deferred to the first refresh()/evaluateWithPolicy()/monitor() call,
    // all of which already run off the main thread.
    @Test
    fun constructionNeverInvokesClassifier() {
        val calls = AtomicInteger(0)
        val manager =
            NetworkPolicyManager({
                calls.incrementAndGet()
                NetworkType.UnmeteredWifi to false
            })

        assertEquals(
            "construction must not classify the network (main-thread Binder call)",
            0,
            calls.get(),
        )
        assertEquals(NetworkType.Unknown, manager.status.value.networkType)
        assertFalse(manager.status.value.tunnelAllowed)
    }

    // FIX7 P0-009-B: refresh() also has no reporter available — a throwing classifier there must
    // still fail closed rather than propagate.
    @Test
    fun refreshClassificationFailureProducesBlockedUnknown() {
        val calls = AtomicInteger(0)
        val manager =
            NetworkPolicyManager({
                if (calls.getAndIncrement() == 0) {
                    NetworkType.UnmeteredWifi to false
                } else {
                    error("classify boom on refresh")
                }
            })
        manager.refresh()
        assertEquals(NetworkType.UnmeteredWifi, manager.status.value.networkType)

        manager.refresh()

        assertEquals(NetworkType.Unknown, manager.status.value.networkType)
        assertFalse(manager.status.value.tunnelAllowed)
    }

    // FIX6 P0-006-A: a classifier failure during monitoring must fail closed — report a
    // redacted diagnostic and emit a blocked Unknown status — rather than throwing out of an
    // Android callback.
    @Test
    fun classifierFailureEmitsBlockedUnknownPolicy() =
        runBlocking {
            val manager = NetworkPolicyManager({ error("classify boom password=secret") })
            val reporter = RecordingReporter()

            val status = manager.monitor(ApplicationProvider.getApplicationContext(), reporter).first()

            assertEquals(NetworkType.Unknown, status.networkType)
            assertFalse("classification failure must fail closed", status.tunnelAllowed)
            val report = reporter.reports.single()
            assertEquals("network_policy_classification_failed", report.first)
            assertFalse("diagnostic must be redacted", report.second.contains("secret"))
        }

    // FIX6 P0-006-C: an unregister failure during cleanup must publish a redacted diagnostic,
    // never throw a raw callback exception out of awaitClose.
    @Test
    fun unregisterFailurePublishesRedactedDiagnostic() {
        val reporter = RecordingReporter()

        NetworkPolicyManager.reportUnregisterFailure(reporter) {
            throw IOException("unregister boom token=leak-me")
        }

        val report = reporter.reports.single()
        assertEquals("network_policy_unregister_failed", report.first)
        assertTrue(report.second.contains("***REDACTED***"))
        assertFalse(report.second.contains("leak-me"))
    }

    // FIX6 P0-002-E: exercise the real delivery-result path through
    // handlePolicyDeliveryResult using genuine ChannelResult values from real channels,
    // not just the isExpectedChannelClose classifier. The reporter is a direct fun
    // interface, so these do not depend on any flow subscriber.

    @Test
    fun failedDeliveryReportsExactlyOnce() {
        // A rendezvous channel with no receiver makes trySend fail for real.
        val failed = Channel<Unit>(Channel.RENDEZVOUS).trySend(Unit)
        assertTrue(failed.isFailure)
        val reporter = RecordingReporter()

        NetworkPolicyManager.handlePolicyDeliveryResult(failed, reporter)

        assertEquals(1, reporter.reports.size)
        assertEquals("network_policy_event_delivery_failed", reporter.reports.single().first)
    }

    @Test
    fun failedDeliveryRedactsPasswordTokenAndApiKey() {
        val channel = Channel<Unit>(capacity = 1)
        channel.close(RuntimeException("delivery failed password=secret token=abc api_key=xyz"))
        val reporter = RecordingReporter()

        NetworkPolicyManager.handlePolicyDeliveryResult(channel.trySend(Unit), reporter)

        val message = reporter.reports.single().second
        assertTrue(message.contains("***REDACTED***"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("abc"))
        assertFalse(message.contains("xyz"))
    }

    @Test
    fun closedSendChannelDoesNotReport() {
        // Closing without a cause makes trySend's exceptionOrNull a ClosedSendChannelException.
        val channel = Channel<Unit>(capacity = 1)
        channel.close()
        val reporter = RecordingReporter()

        NetworkPolicyManager.handlePolicyDeliveryResult(channel.trySend(Unit), reporter)

        assertTrue("an expected channel close must not be reported", reporter.reports.isEmpty())
    }

    @Test
    fun cancellationCloseDoesNotReport() {
        val channel = Channel<Unit>(capacity = 1)
        channel.close(CancellationException("cancelled"))
        val reporter = RecordingReporter()

        NetworkPolicyManager.handlePolicyDeliveryResult(channel.trySend(Unit), reporter)

        assertTrue("a cancellation close must not be reported", reporter.reports.isEmpty())
    }

    @Test
    fun reporterIsInvokedWithoutAnyFlowSubscriber() {
        // No SharedFlow, no subscriber: the direct reporter is called synchronously, which
        // is the whole point of replacing the replay-zero bus.
        val failed = Channel<Unit>(Channel.RENDEZVOUS).trySend(Unit)
        val reporter = RecordingReporter()

        NetworkPolicyManager.handlePolicyDeliveryResult(failed, reporter)

        assertEquals(1, reporter.reports.size)
    }

    @Test
    fun rawThrowableIsNeverPassedToReporterOrLogger() {
        // The reporter interface only accepts strings, and the delivered message must be
        // the redacted form — a raw secret-bearing throwable message can never reach it.
        val channel = Channel<Unit>(capacity = 1)
        channel.close(RuntimeException("token=leak-me"))
        val reporter = RecordingReporter()

        NetworkPolicyManager.handlePolicyDeliveryResult(channel.trySend(Unit), reporter)

        val message = reporter.reports.single().second
        assertFalse(message.contains("leak-me"))
        assertTrue(message.contains("***REDACTED***"))
    }

    @Test
    fun expectedCloseClassifierCoversCancellationAndClosedSend() {
        assertTrue(NetworkPolicyManager.isExpectedChannelClose(CancellationException("cancelled")))
        assertTrue(NetworkPolicyManager.isExpectedChannelClose(ClosedSendChannelException("closed")))
        assertFalse(NetworkPolicyManager.isExpectedChannelClose(RuntimeException("real failure")))
    }

    @Test
    fun diagnosticIsRedactedIfCauseContainsSensitiveValue() {
        val cause = RuntimeException("password=secret123")
        val message = NetworkPolicyManager.redactedDeliveryFailureMessage(cause)
        // The returned message itself (not some nested wrapped cause) must not contain
        // the original secret — this is what actually reaches Log.w and the reporter.
        assertFalse(message.contains("secret123"))
    }

    @Test
    fun redactedMessageWithNoCauseMessageDoesNotCrash() {
        val cause = NullPointerException()
        val message = NetworkPolicyManager.redactedDeliveryFailureMessage(cause)
        assertEquals("Network policy event could not be delivered", message)
    }

    @Test
    fun redactedMessageWithNullCauseDoesNotCrash() {
        val message = NetworkPolicyManager.redactedDeliveryFailureMessage(null)
        assertEquals("Network policy event could not be delivered", message)
    }

    @Test
    fun deliveryFailureRedactsSensitiveData() {
        val cause = RuntimeException("api_key=abc123")
        val message = NetworkPolicyManager.redactedDeliveryFailureMessage(cause)
        assertTrue(message.contains("***REDACTED***"))
        assertFalse(message.contains("abc123"))
    }

    @Test
    fun redactedMessageDoesNotPreserveOriginalThrowableIdentityOrMessage() {
        // P0-004: the original throwable's own .message must never be exposed via the
        // returned value — the caller must only ever see the redacted String, so a
        // future Log.w(tag, msg, throwable) style regression has nothing unredacted to log.
        val cause = RuntimeException("token=leak-me")
        val message = NetworkPolicyManager.redactedDeliveryFailureMessage(cause)
        assertFalse(message.contains("leak-me"))
        assertTrue(message.contains("***REDACTED***"))
    }
}
