package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyManagerTest {
    @Test
    fun blocksMeteredAndUnknownByDefault() {
        val metered = NetworkPolicyManager({ NetworkType.Cellular to true }, NoopNetworkPolicyEventReporter)
        val unknown = NetworkPolicyManager({ NetworkType.Unknown to false }, NoopNetworkPolicyEventReporter)
        assertFalse(metered.allowTunnelOnCurrentNetwork(allowMetered = false))
        assertFalse(unknown.allowTunnelOnCurrentNetwork(allowMetered = true))
    }

    @Test
    fun allowsMeteredWhenOptedIn() {
        val manager = NetworkPolicyManager({ NetworkType.MeteredWifi to true }, NoopNetworkPolicyEventReporter)
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
        val manager = NetworkPolicyManager({ sequence.removeFirst() }, NoopNetworkPolicyEventReporter)
        assertEquals(NetworkType.UnmeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.MeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.NoNetwork, manager.status.value.networkType)
    }

    @Test
    fun unknownStaysBlockedEvenWhenMeteredAllowed() {
        val manager = NetworkPolicyManager({ NetworkType.Unknown to false }, NoopNetworkPolicyEventReporter)
        val status = manager.evaluateWithPolicy(allowMetered = true)
        assertFalse(status.tunnelAllowed)
        assertEquals("Unknown network", status.blockReason)
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
        val redacted = NetworkPolicyManager.redactCause(cause)
        assertNotNull(redacted)
        // The cause should have a redacted message in its cause
        val redactedCause = redacted!!.cause
        assertNotNull(redactedCause)
        // The sensitive data should be redacted
        assertFalse(redactedCause!!.message!!.contains("secret123"))
    }

    @Test
    fun redactCauseWithNoMessageDoesNotCrash() {
        val cause = NullPointerException()
        val redacted = NetworkPolicyManager.redactCause(cause)
        assertEquals(cause, redacted)
    }

    @Test
    fun redactCauseWithNullDoesNotCrash() {
        val redacted = NetworkPolicyManager.redactCause(null)
        assertNull(redacted)
    }

    @Test
    fun deliveryFailureRedactsSensitiveData() {
        val cause = RuntimeException("api_key=abc123")
        val redacted = NetworkPolicyManager.redactCause(cause)
        assertNotNull(redacted)
        // The sensitive data should be redacted
        val redactedCause = redacted!!.cause
        assertNotNull(redactedCause)
        assertTrue(redactedCause!!.message!!.contains("***REDACTED***"))
    }
}
