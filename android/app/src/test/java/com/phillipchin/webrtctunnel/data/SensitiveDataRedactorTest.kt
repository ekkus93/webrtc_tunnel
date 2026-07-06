package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {
    // --- sign.private / kex.private TOML lines ---

    @Test
    fun redactsSignPrivateLine() {
        val output = SensitiveDataRedactor.redactText("sign.private = \"c2VjcmV0a2V5\"")
        assertEquals("sign.private = \"***REDACTED***\"", output)
        assertFalse(output.contains("c2VjcmV0a2V5"))
    }

    @Test
    fun redactsKexPrivateLine() {
        val output = SensitiveDataRedactor.redactText("kex.private = \"c2VjcmV0a2V5\"")
        assertEquals("kex.private = \"***REDACTED***\"", output)
        assertFalse(output.contains("c2VjcmV0a2V5"))
    }

    @Test
    fun signPrivateLineRedactedInsideSurroundingText() {
        val input = "before\nsign.private = \"c2VjcmV0\"\nafter"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("before\nsign.private = \"***REDACTED***\"\nafter", output)
    }

    // --- PEM private key block ---

    @Test
    fun redactsPemPrivateKeyBlock() {
        val input =
            "leading\n-----BEGIN RSA PRIVATE KEY-----\nMIIBogIBAAKCcaf\n" +
                "-----END RSA PRIVATE KEY-----\ntrailing"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("leading\n***REDACTED_PRIVATE_KEY_BLOCK***\ntrailing", output)
        assertFalse(output.contains("MIIBogIBAAKCcaf"))
    }

    @Test
    fun redactsPemPrivateKeyBlockWithoutKeyTypePrefix() {
        val input = "-----BEGIN PRIVATE KEY-----\nabc123\n-----END PRIVATE KEY-----"
        assertEquals("***REDACTED_PRIVATE_KEY_BLOCK***", SensitiveDataRedactor.redactText(input))
    }

    // --- password ---

    @Test
    fun redactsPasswordField() {
        assertEquals("password=***REDACTED***", SensitiveDataRedactor.redactText("password=hunter2"))
    }

    @Test
    fun redactsPasswordFieldVariantName() {
        // The replacement always normalizes to the literal "password=...", regardless of the
        // actual field-name variant matched (e.g. password_file) — documenting real behavior.
        val output = SensitiveDataRedactor.redactText("password_file=/etc/secret/pw")
        assertEquals("password=***REDACTED***", output)
        assertFalse(output.contains("/etc/secret/pw"))
    }

    @Test
    fun redactsPasswordFieldColonVariant() {
        val output = SensitiveDataRedactor.redactText("password: hunter2")
        assertEquals("password=***REDACTED***", output)
        assertFalse(output.contains("hunter2"))
    }

    @Test
    fun redactsPasswordFieldSpacedEqualsVariant() {
        val output = SensitiveDataRedactor.redactText("password = hunter2")
        assertEquals("password=***REDACTED***", output)
        assertFalse(output.contains("hunter2"))
    }

    @Test
    fun redactsPasswordFieldSpacedColonVariant() {
        val output = SensitiveDataRedactor.redactText("password : hunter2")
        assertEquals("password=***REDACTED***", output)
        assertFalse(output.contains("hunter2"))
    }

    // --- token ---

    @Test
    fun redactsTokenField() {
        assertEquals("token=***REDACTED***", SensitiveDataRedactor.redactText("token=abc.def.ghi"))
    }

    // --- bearer ---

    @Test
    fun redactsBearerToken() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc-def_123~xyz"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("Authorization: Bearer ***REDACTED***", output)
    }

    // --- api_key ---

    @Test
    fun redactsApiKeyField() {
        assertEquals("api_key=***REDACTED***", SensitiveDataRedactor.redactText("api_key=sk_live_12345"))
    }

    @Test
    fun redactsApiKeyFieldHyphenVariant() {
        assertEquals("api_key=***REDACTED***", SensitiveDataRedactor.redactText("api-key=sk_live_12345"))
    }

    @Test
    fun redactsApiKeyFieldSpaceAndColonVariant() {
        val output = SensitiveDataRedactor.redactText("api key: sk_live_123")
        assertEquals("api_key=***REDACTED***", output)
        assertFalse(output.contains("sk_live_123"))
    }

    // --- mqtt(s) credentials ---

    @Test
    fun redactsMqttsCredentials() {
        val input = "mqtts://alice:s3cr3t@broker.example.com:8883"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("mqtts://***REDACTED***:***REDACTED***@broker.example.com:8883", output)
        assertFalse(output.contains("s3cr3t"))
    }

    @Test
    fun mqttSchemeCredentialsAreRedactedAndOriginalSchemeIsPreserved() {
        // The original scheme must be preserved: rewriting a plain mqtt:// URL to mqtts://
        // would misrepresent whether the connection was actually TLS-protected.
        val input = "mqtt://alice:s3cr3t@broker.example.com:1883"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("mqtt://***REDACTED***:***REDACTED***@broker.example.com:1883", output)
        assertFalse(output.contains("s3cr3t"))
    }

    // --- sdp ---

    @Test
    fun redactsSdpBlockUpToBlankLineCrlf() {
        // Regression test for a real bug found while writing this suite: the original regex's
        // blank-line terminator was a bare "\n\n", which never matches real \r\n\r\n line
        // endings (SDP's own convention), so it silently swallowed everything to the end of the
        // string instead of stopping at the blank line. Fixed to accept \r?\n\r?\n.
        val input = "sdp: v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\n\r\nnext section unaffected"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("sdp=***REDACTED***\nnext section unaffected", output)
    }

    @Test
    fun redactsSdpBlockUpToBlankLineLf() {
        val input = "sdp: v=0\no=- 12345 2 IN IP4 127.0.0.1\n\nnext section unaffected"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("sdp=***REDACTED***\nnext section unaffected", output)
    }

    @Test
    fun redactsSdpBlockToEndOfStringWhenNoBlankLineFollows() {
        val input = "sdp: v=0\r\no=- 12345 2 IN IP4 127.0.0.1"
        assertEquals("sdp=***REDACTED***\n", SensitiveDataRedactor.redactText(input))
    }

    // --- candidate ---

    @Test
    fun redactsCandidateLine() {
        val input = "before\ncandidate: 1 1 UDP 12345 10.0.0.5 54321 typ host\nafter"
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("before\ncandidate=***REDACTED***\nafter", output)
    }

    // --- decrypted_payload ---

    @Test
    fun redactsDecryptedPayloadUnderscoreVariant() {
        val output = SensitiveDataRedactor.redactText("decrypted_payload: 48 65 6c 6c 6f")
        assertEquals("decrypted_payload=***REDACTED***", output)
    }

    @Test
    fun redactsDecryptedPayloadSpaceVariant() {
        val output = SensitiveDataRedactor.redactText("decrypted payload: 48 65 6c 6c 6f")
        assertEquals("decrypted_payload=***REDACTED***", output)
    }

    // --- forwarded_data ---

    @Test
    fun redactsForwardedDataLine() {
        val output = SensitiveDataRedactor.redactText("forwarded_data: 48 65 6c 6c 6f")
        assertEquals("forwarded_data=***REDACTED***", output)
    }

    // --- kex_secret ---

    @Test
    fun redactsKexSecretLine() {
        assertEquals("kex_secret=***REDACTED***", SensitiveDataRedactor.redactText("kex_secret=deadbeef"))
    }

    @Test
    fun redactsKexSecretSpaceVariant() {
        val output = SensitiveDataRedactor.redactText("kex secret = deadbeef")
        assertEquals("kex_secret=***REDACTED***", output)
        assertFalse(output.contains("deadbeef"))
    }

    @Test
    fun redactsKexSecretColonVariant() {
        val output = SensitiveDataRedactor.redactText("kex_secret: deadbeef")
        assertEquals("kex_secret=***REDACTED***", output)
        assertFalse(output.contains("deadbeef"))
    }

    // --- signing_key ---

    @Test
    fun redactsSigningKeyLine() {
        assertEquals("signing_key=***REDACTED***", SensitiveDataRedactor.redactText("signing_key=deadbeef"))
    }

    @Test
    fun redactsSigningKeySpaceAndColonVariant() {
        val output = SensitiveDataRedactor.redactText("signing key: deadbeef")
        assertEquals("signing_key=***REDACTED***", output)
        assertFalse(output.contains("deadbeef"))
    }

    // --- identity path (broad, intentionally over-inclusive) ---

    @Test
    fun redactsTrailingIdentityPathSegment() {
        // The pattern only reaches back through contiguous "/segment/" groups immediately
        // preceding the literal "identity" — it does not necessarily consume the whole path
        // (verified against the actual regex rather than assumed).
        val output = SensitiveDataRedactor.redactText("/home/user/.config/p2ptunnel/identity")
        assertEquals("/home/user/.config***REDACTED_IDENTITY_PATH***", output)
    }

    @Test
    fun redactsIdentityFilePathWithExtension() {
        val output = SensitiveDataRedactor.redactText("/etc/p2ptunnel/offer/identity.toml")
        assertEquals("/etc/p2ptunnel***REDACTED_IDENTITY_PATH***", output)
    }

    @Test
    fun identityRuleAlsoMatchesThePlainWordInProse() {
        // Known over-redaction quirk: the pattern has no word-boundary anchor, so it matches
        // the bare word "identity" anywhere, including inside an unrelated English sentence.
        // Documented here as the actual, current behavior rather than assumed — acceptable for
        // a diagnostics-export redactor, where erring toward over-redaction is the safe choice.
        val input = "Please verify your identity before continuing."
        val output = SensitiveDataRedactor.redactText(input)
        assertEquals("Please verify your ***REDACTED_IDENTITY_PATH*** before continuing.", output)
    }

    // --- multiple secrets in one input ---

    @Test
    fun redactsMultipleDistinctSecretsInOneInput() {
        val input =
            "sign.private = \"AAAA\"\n" +
                "password=hunter2\n" +
                "token=abc123\n" +
                "api_key=sk_live_999"
        val output = SensitiveDataRedactor.redactText(input)
        assertFalse(output.contains("AAAA"))
        assertFalse(output.contains("hunter2"))
        assertFalse(output.contains("abc123"))
        assertFalse(output.contains("sk_live_999"))
        assertTrue(output.contains("sign.private = \"***REDACTED***\""))
        assertTrue(output.contains("password=***REDACTED***"))
        assertTrue(output.contains("token=***REDACTED***"))
        assertTrue(output.contains("api_key=***REDACTED***"))
    }

    // --- empty / no-secret input ---

    @Test
    fun emptyInputIsUnchanged() {
        assertEquals("", SensitiveDataRedactor.redactText(""))
    }

    @Test
    fun textWithNoSecretsIsUnchanged() {
        val input = "Tunnel started successfully on port 2223."
        assertEquals(input, SensitiveDataRedactor.redactText(input))
    }

    // --- idempotency ---

    @Test
    fun redactingAlreadyRedactedOutputIsStable() {
        val input = "password=hunter2 token=abc123 sign.private = \"AAAA\""
        val once = SensitiveDataRedactor.redactText(input)
        val twice = SensitiveDataRedactor.redactText(once)
        assertEquals(once, twice)
    }

    // --- redactLogEvent / redactStatus wrappers ---

    @Test
    fun redactLogEventRedactsMessageOnlyAndPreservesOtherFields() {
        val event = LogEvent(unixMs = 42L, level = "info", message = "password=hunter2")
        val redacted = SensitiveDataRedactor.redactLogEvent(event)
        assertEquals(42L, redacted.unixMs)
        assertEquals("info", redacted.level)
        assertEquals("password=***REDACTED***", redacted.message)
    }

    @Test
    fun redactStatusRedactsLastErrorMessageAndDetails() {
        val status =
            sampleStatus(
                lastError =
                    TunnelError(
                        code = "ice_failed",
                        message = "password=hunter2",
                        details = "token=abc123",
                    ),
            )
        val redacted = SensitiveDataRedactor.redactStatus(status)
        assertEquals("password=***REDACTED***", redacted.lastError?.message)
        assertEquals("token=***REDACTED***", redacted.lastError?.details)
        assertEquals("ice_failed", redacted.lastError?.code)
    }

    @Test
    fun redactStatusWithNullLastErrorIsUnchanged() {
        val redacted = SensitiveDataRedactor.redactStatus(sampleStatus(lastError = null))
        assertNull(redacted.lastError)
    }

    private fun sampleStatus(lastError: TunnelError?): TunnelStatus =
        TunnelStatus(
            serviceState = ServiceState.Error,
            mode = TunnelMode.Offer,
            localPeerId = "offer-home",
            networkStatus =
                NetworkStatus(
                    networkType = NetworkType.NoNetwork,
                    isMetered = false,
                    allowedByDefault = false,
                    allowedByUserPolicy = false,
                    tunnelAllowed = false,
                ),
            lastError = lastError,
        )
}
