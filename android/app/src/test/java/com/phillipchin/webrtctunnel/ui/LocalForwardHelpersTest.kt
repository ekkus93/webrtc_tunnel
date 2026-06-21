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

    @Test
    fun forwardFieldErrorsFlagsBlankRequiredFields() {
        val draft = ForwardConfig(id = "f", name = "", localPort = 8080, remoteForwardId = "")
        val errors = forwardFieldErrors(draft, portText = "8080", existingForwards = emptyList())
        assertEquals("Display name is required", errors.name)
        assertEquals(null, errors.port)
        assertEquals("Remote forward ID is required", errors.remoteId)
        assertEquals(true, errors.hasError)
    }

    @Test
    fun forwardFieldErrorsRejectsOutOfRangeAndNonNumericPort() {
        val draft = ForwardConfig(id = "f", name = "web", localPort = 0, remoteForwardId = "web")
        assertEquals("Local port is required", forwardFieldErrors(draft, "", emptyList()).port)
        assertEquals("Port must be between 1 and 65535", forwardFieldErrors(draft, "0", emptyList()).port)
        assertEquals("Port must be between 1 and 65535", forwardFieldErrors(draft, "70000", emptyList()).port)
    }

    @Test
    fun forwardFieldErrorsFlagsDuplicatePortAgainstOtherEnabledForward() {
        val other = ForwardConfig(id = "other", name = "other", localPort = 8080, remoteForwardId = "o")
        val draft = ForwardConfig(id = "f", name = "web", localPort = 8080, remoteForwardId = "web")
        assertEquals(
            "Port already used by another enabled forward",
            forwardFieldErrors(draft, "8080", listOf(other)).port,
        )
        // The forward's own entry (same id) does not count as a duplicate of itself.
        assertEquals(null, forwardFieldErrors(draft, "8080", listOf(draft)).port)
    }

    @Test
    fun forwardFieldErrorsAcceptsAValidDraft() {
        val draft = ForwardConfig(id = "f", name = "web", localPort = 8080, remoteForwardId = "web")
        val errors = forwardFieldErrors(draft, portText = "8080", existingForwards = emptyList())
        assertEquals(false, errors.hasError)
    }
}
