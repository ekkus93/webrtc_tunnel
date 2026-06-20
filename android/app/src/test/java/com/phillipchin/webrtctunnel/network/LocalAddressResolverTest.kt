package com.phillipchin.webrtctunnel.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAddressResolverTest {
    @Test
    fun noAddressesResolvesToNull() {
        assertNull(LocalAddressResolver { emptyList() }.currentIpv4())
    }

    @Test
    fun noRoutableAddressResolvesToNull() {
        // Loopback / unspecified / multicast / broadcast are all unusable host candidates.
        val resolver =
            LocalAddressResolver { listOf("127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255") }
        assertNull(resolver.currentIpv4())
    }

    @Test
    fun picksTheRoutableIpv4() {
        val resolver = LocalAddressResolver { listOf("127.0.0.1", "10.1.3.11") }
        assertEquals("10.1.3.11", resolver.currentIpv4())
    }

    @Test
    fun prefersFirstRoutableWhenMultiplePresent() {
        val resolver = LocalAddressResolver { listOf("192.168.1.5", "10.1.3.11") }
        assertEquals("192.168.1.5", resolver.currentIpv4())
    }

    @Test
    fun prefersNonLinkLocalOverLinkLocal() {
        // A 169.254/16 link-local address is only used as a last resort.
        val resolver = LocalAddressResolver { listOf("169.254.1.2", "10.1.3.11") }
        assertEquals("10.1.3.11", resolver.currentIpv4())
    }

    @Test
    fun fallsBackToLinkLocalWhenNothingBetter() {
        val resolver = LocalAddressResolver { listOf("169.254.1.2") }
        assertEquals("169.254.1.2", resolver.currentIpv4())
    }

    @Test
    fun rejectsMalformedOctets() {
        val resolver = LocalAddressResolver { listOf("10.0.0.999", "not-an-ip", "1.2.3") }
        assertNull(resolver.currentIpv4())
    }

    @Test
    fun selectRoutableIpv4IsPureAndTrimsWhitespace() {
        assertEquals(
            "10.1.3.11",
            LocalAddressResolver.selectRoutableIpv4(listOf("  10.1.3.11  ")),
        )
    }
}
