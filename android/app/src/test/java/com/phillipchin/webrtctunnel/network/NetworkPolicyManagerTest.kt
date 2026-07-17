package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun eachInstanceOwnsItsOwnDiagnosticEventBus() {
        // P0-003: every NetworkPolicyManager always has a live diagnostic bus — there is
        // no no-op/production reporter split left to misconfigure.
        val first = NetworkPolicyManager({ NetworkType.UnmeteredWifi to false })
        val second = NetworkPolicyManager({ NetworkType.UnmeteredWifi to false })
        assertTrue(first.diagnosticEvents !== second.diagnosticEvents)
    }

    @Test
    fun expectedCloseCancellationExceptionDoesNotReport() {
        val cause = CancellationException("cancelled")
        assertTrue(NetworkPolicyManager.isExpectedChannelClose(cause))
    }

    @Test
    fun expectedCloseClosedSendChannelDoesNotReport() {
        val cause = ClosedSendChannelException("channel closed")
        assertTrue(NetworkPolicyManager.isExpectedChannelClose(cause))
    }

    @Test
    fun activeFailedDeliveryReportsDiagnostic() {
        // Non-expected causes should be reported
        val cause = RuntimeException("delivery failed")
        assertFalse(NetworkPolicyManager.isExpectedChannelClose(cause))
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
