package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.ListenState
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
class TunnelRepositoryTest {
    private lateinit var bridge: RecordingBridge
    private lateinit var repository: TunnelRepository

    @Before
    fun setUp() {
        bridge = RecordingBridge()
        repository = TunnelRepository(bridge)
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
    fun startAnswerFailurePropagatesActionableError() {
        bridge.failAnswer = true
        val result = repository.start(TunnelMode.Answer, "/tmp/config.toml")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("answer failed") == true)
    }

    @Test
    fun startAnswerFailureKeepsExistingStatus() {
        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)

        bridge.failAnswer = true
        val result = repository.start(TunnelMode.Answer, "/tmp/config.toml")
        assertTrue(result.isFailure)
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
    }

    @Test
    fun offerAndAnswerFailuresAreConsistent() {
        bridge.offerResults.add(Result.failure(IllegalStateException("offer failed queued")))
        bridge.answerResults.add(Result.failure(IllegalStateException("answer failed queued")))

        val offerResult = repository.start(TunnelMode.Offer, "/tmp/offer.toml")
        val answerResult = repository.start(TunnelMode.Answer, "/tmp/answer.toml")

        assertTrue(offerResult.isFailure)
        assertTrue(answerResult.isFailure)
        assertEquals("/tmp/offer.toml", bridge.offerConfigPath)
        assertEquals("/tmp/answer.toml", bridge.answerConfigPath)
    }

    @Test
    fun answerStartSuccessStillRefreshesStatusAfterFailure() {
        bridge.failAnswer = true
        assertTrue(repository.start(TunnelMode.Answer, "/tmp/config.toml").isFailure)

        bridge.failAnswer = false
        bridge.statusPayload = statusJson("running", "answer")
        val result = repository.start(TunnelMode.Answer, "/tmp/config.toml")
        assertTrue(result.isSuccess)
        assertEquals(ServiceState.Serving, repository.status.value.serviceState)
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

    @Test
    fun refreshStatusMapsMeasuredDaemonFields() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(
                    state = "running",
                    mode = "offer",
                    config_path = "/tmp/config.toml",
                    active = true,
                    mqtt_connected = true,
                    active_session_count = 2,
                    session_capacity = 16,
                ),
            )
        repository.refreshStatus()
        val status = repository.status.value
        assertEquals(true, status.mqttConnected)
        assertEquals(2, status.activeSessionCount)
        assertEquals(16, status.sessionCapacity)
    }

    @Test
    fun refreshStatusDecodesNativeJsonWithoutMeasuredFields() {
        bridge.statusPayload = """{"state":"running","mode":"offer","active":true}"""
        repository.refreshStatus()
        val status = repository.status.value
        assertEquals(ServiceState.Connected, status.serviceState)
        assertEquals(false, status.mqttConnected)
        assertEquals(0, status.activeSessionCount)
    }

    @Test
    fun refreshStatusMapsForwardRuntimeStatus() {
        bridge.statusPayload =
            """
            {"state":"running","mode":"offer","active":true,
             "forwards":[
               {"id":"web","local_host":"127.0.0.1","local_port":8080,"listen_state":"listening"},
               {"id":"ssh","local_host":"127.0.0.1","local_port":2222,"listen_state":"error","last_error":"Address already in use"}
             ]}
            """.trimIndent()
        repository.refreshStatus()
        val forwards = repository.status.value.forwards
        assertEquals(2, forwards.size)
        val web = forwards.first { it.id == "web" }
        assertEquals(ListenState.Listening, web.listenState)
        assertEquals(8080, web.localPort)
        val ssh = forwards.first { it.id == "ssh" }
        assertEquals(ListenState.Error, ssh.listenState)
        assertTrue(ssh.lastError != null)
    }

    @Test
    fun refreshStatusForwardUnknownListenStateFallsBack() {
        bridge.statusPayload =
            """{"state":"running","active":true,"forwards":[{"id":"x","listen_state":"weird"}]}"""
        repository.refreshStatus()
        val forward = repository.status.value.forwards.single()
        assertEquals(ListenState.Stopped, forward.listenState)
    }

    @Test
    fun refreshStatusWithoutForwardsLeavesEmptyList() {
        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()
        assertTrue(repository.status.value.forwards.isEmpty())
    }

    @Test
    fun refreshStatusDoesNotResurrectPolicyPausedState() {
        // Tunnel was paused by policy while the native daemon task is still "running"/active.
        repository.setPolicyBlocked("metered network blocked")
        assertEquals(ServiceState.PausedMeteredBlocked, repository.status.value.serviceState)

        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()

        val status = repository.status.value
        assertEquals(ServiceState.PausedMeteredBlocked, status.serviceState)
        assertEquals(false, status.networkStatus.tunnelAllowed)
        assertEquals(0, status.activeSessionCount)
    }

    @Test
    fun refreshStatusAppliesNativeStateWhenNotPolicyPaused() {
        bridge.statusPayload = statusJson("error", "offer")
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun updateSessionMeteredAllowanceUpdatesStatusFlag() {
        repository.updateSessionMeteredAllowance(true)
        assertEquals(true, repository.status.value.allowMeteredForCurrentSession)
        repository.updateSessionMeteredAllowance(false)
        assertEquals(false, repository.status.value.allowMeteredForCurrentSession)
    }

    private fun statusJson(
        state: String,
        mode: String,
    ): String =
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
        var failAnswer = false
        var failStop = false
        val offerResults: ArrayDeque<Result<Unit>> = ArrayDeque()
        val answerResults: ArrayDeque<Result<Unit>> = ArrayDeque()
        val stopResults: ArrayDeque<Result<Unit>> = ArrayDeque()
        var statusPayload: String =
            Json.encodeToString(
                NativeRuntimeStatusDto(state = "stopped", mode = "offer"),
            )
        val statusPayloads: ArrayDeque<String> = ArrayDeque()
        var logsJson: String = "[]"
        val logsPayloads: ArrayDeque<String> = ArrayDeque()
        var validationResult: ValidationResult = ValidationResult(true, null)

        override fun startOffer(
            configPath: String,
            identityBytes: ByteArray?,
        ): Result<Unit> {
            offerConfigPath = configPath
            return offerResults.pollFirst()
                ?: if (failOffer) Result.failure(IllegalStateException("offer failed")) else Result.success(Unit)
        }

        override fun startAnswer(configPath: String): Result<Unit> {
            answerConfigPath = configPath
            return answerResults.pollFirst()
                ?: if (failAnswer) Result.failure(IllegalStateException("answer failed")) else Result.success(Unit)
        }

        override fun stop(): Result<Unit> {
            stopped = true
            return stopResults.pollFirst()
                ?: if (failStop) Result.failure(IllegalStateException("stop failed")) else Result.success(Unit)
        }

        override fun getStatusJson(): String = statusPayloads.pollFirst() ?: statusPayload

        override fun getRecentLogsJson(maxEvents: Int): String = logsPayloads.pollFirst() ?: logsJson

        override fun validateConfig(configPath: String): ValidationResult = validationResult

        override fun validateConfigWithIdentity(
            configPath: String,
            identityBytes: ByteArray,
        ): ValidationResult = validationResult

        override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonical_public_identity = "canon",
                canonical_private_identity = identityToml,
                peer_id = "peer",
            )

        override fun validatePublicIdentity(line: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = line.trim(), peer_id = "peer")

        override fun generateIdentity(peerId: String): IdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonical_public_identity = "canon",
                canonical_private_identity = "private",
                peer_id = peerId,
            )
    }
}
