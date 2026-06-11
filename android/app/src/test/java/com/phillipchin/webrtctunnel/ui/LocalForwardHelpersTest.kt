package com.phillipchin.webrtctunnel.ui

import com.phillipchin.webrtctunnel.model.ForwardConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalForwardHelpersTest {
    private fun forward(
        host: String,
        port: Int = 8080,
    ) = ForwardConfig(
        id = "f",
        name = "f",
        localHost = host,
        localPort = port,
        remoteForwardId = "f",
        enabled = true,
    )

    @Test
    fun localForwardAddressUsesConfiguredHostExactly() {
        assertEquals("127.0.0.1:8080", localForwardAddress(forward("127.0.0.1")))
        assertEquals("localhost:3000", localForwardAddress(forward("localhost", 3000)))
    }

    @Test
    fun browserHostKeepsLoopbackAndNamedHosts() {
        assertEquals("127.0.0.1", browserHostForLocalForward("127.0.0.1"))
        assertEquals("localhost", browserHostForLocalForward("localhost"))
    }

    @Test
    fun browserHostNormalizesWildcardAndBlankToLoopback() {
        assertEquals("127.0.0.1", browserHostForLocalForward(""))
        assertEquals("127.0.0.1", browserHostForLocalForward("  "))
        assertEquals("127.0.0.1", browserHostForLocalForward("0.0.0.0"))
        assertEquals("127.0.0.1", browserHostForLocalForward("::"))
        assertEquals("127.0.0.1", browserHostForLocalForward("[::]"))
    }

    @Test
    fun browserUrlForForwardBuildsNormalizedUrl() {
        assertEquals("http://127.0.0.1:8080", browserUrlForForward(forward("127.0.0.1")))
        assertEquals("http://localhost:5000", browserUrlForForward(forward("localhost", 5000)))
        assertEquals("http://127.0.0.1:9000", browserUrlForForward(forward("0.0.0.0", 9000)))
    }
}
