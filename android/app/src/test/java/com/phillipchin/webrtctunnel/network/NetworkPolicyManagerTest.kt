package com.phillipchin.webrtctunnel.network

import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyManagerTest {
    @Test
    fun blocksMeteredAndUnknownByDefault() {
        val metered = NetworkPolicyManager {
            NetworkStatus(NetworkType.Cellular, true, false, false, false, "blocked")
        }
        val unknown = NetworkPolicyManager {
            NetworkStatus(NetworkType.Unknown, false, false, false, false, "unknown")
        }
        assertFalse(metered.allowTunnelOnCurrentNetwork(allowMetered = false))
        assertFalse(unknown.allowTunnelOnCurrentNetwork(allowMetered = true))
    }

    @Test
    fun allowsMeteredWhenOptedIn() {
        val manager = NetworkPolicyManager {
            NetworkStatus(NetworkType.MeteredWifi, true, false, false, false, "blocked")
        }
        assertTrue(manager.allowTunnelOnCurrentNetwork(allowMetered = true))
        assertFalse(manager.allowTunnelOnCurrentNetwork(allowMetered = false))
    }

    @Test
    fun transitionsUpdateStatus() {
        val sequence = ArrayDeque(
            listOf(
                NetworkStatus(NetworkType.UnmeteredWifi, false, true, true, true, null),
                NetworkStatus(NetworkType.MeteredWifi, true, false, false, false, "Tunnel blocked by policy"),
                NetworkStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
            ),
        )
        val manager = NetworkPolicyManager {
            sequence.removeFirst()
        }
        assertEquals(NetworkType.UnmeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.MeteredWifi, manager.status.value.networkType)
        manager.refresh()
        assertEquals(NetworkType.NoNetwork, manager.status.value.networkType)
    }
}
