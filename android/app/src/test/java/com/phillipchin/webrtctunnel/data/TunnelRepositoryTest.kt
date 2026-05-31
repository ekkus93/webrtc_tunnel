package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TunnelRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var bridge: RecordingBridge
    private lateinit var repository: TunnelRepository

    @Before
    fun setUp() {
        bridge = RecordingBridge()
        repository = TunnelRepository(context, bridge)
    }

    @Test
    fun startOfferCallsBridgeAndRefreshesStatus() {
        bridge.statusPayload = statusJson("running", "offer")
        val result = repository.start(TunnelMode.Offer, "/tmp/config.toml")
        assertTrue(result.isSuccess)
        assertEquals("/tmp/config.toml", bridge.offerConfigPath)
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
    }

    @Test
    fun startAnswerCallsBridgeAndRefreshesStatus() {
        bridge.statusPayload = statusJson("running", "answer")
        val result = repository.start(TunnelMode.Answer, "/tmp/config.toml")
        assertTrue(result.isSuccess)
        assertEquals("/tmp/config.toml", bridge.answerConfigPath)
        assertEquals(ServiceState.Serving, repository.status.value.serviceState)
    }

    @Test
    fun stopCallsBridgeAndRefreshesStatus() {
        bridge.statusPayload = statusJson("stopped", "answer")
        val result = repository.stop()
        assertTrue(result.isSuccess)
        assertTrue(bridge.stopped)
        assertEquals(ServiceState.Stopped, repository.status.value.serviceState)
    }

    @Test
    fun refreshStatusSetsErrorStateOnInvalidJson() {
        bridge.statusPayload = "{bad-json"
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun recentLogsParsesValidJson() {
        bridge.logsJson = Json.encodeToString(listOf(NativeLogEventDto(1L, "info", "ok")))
        val logs = repository.recentLogs(10)
        assertEquals(1, logs.size)
        assertEquals("ok", logs.first().message)
    }

    @Test
    fun recentLogsReturnsEmptyOnInvalidJsonAndMarksError() {
        bridge.logsJson = "{not-array"
        assertTrue(repository.recentLogs(10).isEmpty())
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun validateConfigPassesThroughBridgeResult() {
        bridge.validationResult = ValidationResult(false, "invalid")
        assertEquals(ValidationResult(false, "invalid"), repository.validateConfig("/tmp/config.toml"))
    }

    @Test
    fun startFailurePropagates() {
        bridge.failOffer = true
        val result = repository.start(TunnelMode.Offer, "/tmp/config.toml")
        assertTrue(result.isFailure)
    }

    @Test
    fun stopFailurePropagates() {
        bridge.failStop = true
        val result = repository.stop()
        assertTrue(result.isFailure)
    }

    @Test
    fun setLocalErrorRedactsAndClearsActiveState() {
        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()
        repository.setLocalError(
            code = "native_start_failed",
            message = "password=abc sign.private=\"secret\"",
            details = "token=123",
            state = ServiceState.Error,
        )
        val status = repository.status.value
        assertEquals(ServiceState.Error, status.serviceState)
        assertEquals(0, status.activeSessionCount)
        assertTrue(status.lastError?.message?.contains("***REDACTED***") == true)
        assertTrue(status.lastError?.details?.contains("***REDACTED***") == true)
    }

    @Test
    fun setPolicyBlockedRedactsReasonAndClearsActivity() {
        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()
        repository.setPolicyBlocked("token=abc")
        val status = repository.status.value
        assertEquals(ServiceState.PausedMeteredBlocked, status.serviceState)
        assertEquals(0, status.activeSessionCount)
        assertEquals(false, status.networkStatus.tunnelAllowed)
        assertTrue(status.networkStatus.blockReason?.contains("***REDACTED***") == true)
    }

    private fun statusJson(state: String, mode: String): String =
        Json.encodeToString(
            NativeRuntimeStatusDto(
                state = state,
                mode = mode,
                config_path = "/tmp/config.toml",
                active = state == "running",
            ),
        )

    private class RecordingBridge : TunnelNativeBridge {
        var offerConfigPath: String? = null
        var answerConfigPath: String? = null
        var stopped = false
        var failOffer = false
        var failStop = false
        var statusPayload: String = Json.encodeToString(
            NativeRuntimeStatusDto(state = "stopped", mode = "offer"),
        )
        var logsJson: String = "[]"
        var validationResult: ValidationResult = ValidationResult(true, null)

        override fun startOffer(configPath: String, identityBytes: ByteArray?): Result<Unit> {
            offerConfigPath = configPath
            return if (failOffer) Result.failure(IllegalStateException("offer failed")) else Result.success(Unit)
        }

        override fun startAnswer(configPath: String): Result<Unit> {
            answerConfigPath = configPath
            return Result.success(Unit)
        }

        override fun stop(): Result<Unit> {
            stopped = true
            return if (failStop) Result.failure(IllegalStateException("stop failed")) else Result.success(Unit)
        }

        override fun getStatusJson(): String = statusPayload

        override fun getRecentLogsJson(maxEvents: Int): String = logsJson

        override fun validateConfig(configPath: String): ValidationResult = validationResult
        override fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult = validationResult
        override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = "canon", canonical_private_identity = identityToml, peer_id = "peer")
        override fun validatePublicIdentity(line: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = line.trim(), peer_id = "peer")
        override fun generateIdentity(peerId: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = "canon", canonical_private_identity = "private", peer_id = peerId)
    }
}
