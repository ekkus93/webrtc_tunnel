package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.model.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyManagerTest {
    @Test
    fun blocksMeteredAndUnknownByDefault() {
        val metered = NetworkPolicyManager { NetworkType.Cellular to true }
        val unknown = NetworkPolicyManager { NetworkType.Unknown to false }
        assertFalse(metered.allowTunnelOnCurrentNetwork(allowMetered = false))
        assertFalse(unknown.allowTunnelOnCurrentNetwork(allowMetered = true))
    }

    @Test
    fun allowsMeteredWhenOptedIn() {
        val manager = NetworkPolicyManager { NetworkType.MeteredWifi to true }
        assertTrue(manager.allowTunnelOnCurrentNetwork(allowMetered = true))
        assertFalse(manager.allowTunnelOnCurrentNetwork(allowMetered = false))
    }

    @Test
    fun transitionsUpdateStatus() {
        val sequence = ArrayDeque(
            listOf(
                NetworkType.UnmeteredWifi to false,
                NetworkType.MeteredWifi to true,
                NetworkType.NoNetwork to false,
            ),
        )
        val manager = NetworkPolicyManager { sequence.removeFirst() }
        assertEquals(NetworkType.UnmeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.MeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.NoNetwork, manager.status.value.networkType)
    }

    @Test
    fun unknownStaysBlockedEvenWhenMeteredAllowed() {
        val manager = NetworkPolicyManager { NetworkType.Unknown to false }
        val status = manager.evaluateWithPolicy(allowMetered = true)
        assertFalse(status.tunnelAllowed)
        assertEquals("Unknown network", status.blockReason)
    }
}
