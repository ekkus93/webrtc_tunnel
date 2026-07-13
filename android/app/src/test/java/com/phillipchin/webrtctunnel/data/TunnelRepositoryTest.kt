package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.ListenState
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class TunnelRepositoryTest {
    private lateinit var bridge: RecordingBridge
    private lateinit var repository: TunnelRepository
    private lateinit var validationClient: IdentityValidationClient

    @Before
    fun setUp() {
        bridge = RecordingBridge()
        repository = TunnelRepository(bridge)
        validationClient = IdentityValidationClient(bridge)
    }

    @Test
    fun startOfferCallsBridgeAndRefreshesStatus() {
        bridge.statusPayload = statusJson("running", "offer")
        val result = repository.start(TunnelMode.Offer, "/tmp/config.toml")
        assertTrue(result.isSuccess)
        assertEquals("/tmp/config.toml", bridge.offerConfigPath)
        // Offer "running" with no active session is Listening, not Connected.
        assertEquals(ServiceState.Listening, repository.status.value.serviceState)
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

    // Required P0-003 tests: native JNI success alone must not be sufficient proof of a
    // clean stop — only a verified final Stopped state counts.

    @Test
    fun nativeStopSuccessAndStoppedStatusReturnsSuccess() {
        bridge.statusPayload = statusJson("stopped", "offer")
        val result = repository.stop()
        assertTrue(result.isSuccess)
        assertEquals(ServiceState.Stopped, repository.status.value.serviceState)
    }

    @Test
    fun nativeStopSuccessAndStatusReadFailureReturnsFailure() {
        bridge.statusPayload = "{bad-json"
        val result = repository.stop()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is StopStatusVerificationException)
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun nativeStopSuccessAndErrorStatusReturnsFailure() {
        bridge.statusPayload = statusJson("error", "offer")
        val result = repository.stop()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is StopStatusVerificationException)
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun nativeStopSuccessAndRunningStatusReturnsFailure() {
        // bridge.stop() succeeds (default), but the post-stop status read still reports the
        // daemon task as active — e.g. a duplicate/no-op ("not running") native success while
        // the real owner's stop is still in flight. Must not be reported as clean.
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(state = "running", mode = "offer", active = true, activeSessionCount = 1),
            )
        val result = repository.stop()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is StopStatusVerificationException)
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
    }

    @Test
    fun refreshStatusSetsErrorStateOnInvalidJson() {
        bridge.statusPayload = "{bad-json"
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun refreshStatusDecodesIceDecisionFields() {
        // A status payload carrying the ICE decision must decode cleanly (not error).
        bridge.statusPayload =
            """
            {"state":"running","mode":"offer","active":true,
             "ice":{"requested_mode":"vnet_mux","selected_path":"vnet_mux",
                    "fallback":false,"reason":"mode_vnet_mux","advertised_local_ipv4":"10.1.3.11"}}
            """.trimIndent()
        repository.refreshStatus()
        assertTrue(repository.status.value.serviceState != ServiceState.Error)
    }

    @Test
    fun missingModeReturnsSchemaError() {
        // P1-007: When the native status JSON is missing the mode field,
        // the repository should surface a schema error, not silently default.
        bridge.statusPayload = """{"state":"stopped","active":false}"""
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
        assertEquals("native_status_schema_error", repository.status.value.lastError?.code)
    }

    @Test
    fun unknownNativeModeReturnsSchemaError() {
        // When the native status returns an unknown mode (e.g., future version),
        // the repository should surface a schema error rather than crashing.
        bridge.statusPayload =
            """
            {"state":"stopped","mode":"future_unknown_mode","active":false}
            """.trimIndent()
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
        assertEquals("native_status_schema_error", repository.status.value.lastError?.code)
    }

    @Test
    fun unknownNativeStateMapsToError() {
        // When the native status returns an unknown runtime state,
        // it should map to a safe Error state rather than crashing.
        bridge.statusPayload =
            """
            {"state":"totally_unknown_state","mode":"offer","active":false}
            """.trimIndent()
        repository.refreshStatus()
        // Unknown state should result in Error state (safe fallback)
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun recentLogsParsesValidJson() {
        bridge.logsJson = Json.encodeToString(listOf(NativeLogEventDto(1L, "info", "ok")))
        val result = repository.recentLogs(10)
        assertEquals(1, result.logs.size)
        assertEquals("ok", result.logs.first().message)
    }

    @Test
    fun recentLogsSurfacesErrorEventOnInvalidJsonAndMarksError() {
        // P0-005: Invalid native log output must not affect tunnel lifecycle state.
        // P1-001: Failure returns empty list with typed TunnelError — not a synthetic log
        // entry — so the log screen cannot confuse a failure with "one error log".
        // Repository does NOT mutate _logsError; ViewModel applies it under generation guard.
        bridge.logsJson = "{not-array"
        val result = repository.recentLogs(10)
        assertEquals(0, result.logs.size)
        // Log failure must NOT set serviceState to Error (P0-005).
        assertTrue(
            "log retrieval failure must not change lifecycle state to Error",
            repository.status.value.serviceState != ServiceState.Error,
        )
        // Error must be carried in LogsFetchResult (not set in repository _logsError).
        val error = result.error
        assertNotNull(error)
        assertEquals("logs_refresh_failed", error!!.code)
    }

    @Test
    fun validateConfigPassesThroughBridgeResult() {
        bridge.validationResult = ValidationResult(false, "invalid")
        assertEquals(ValidationResult(false, "invalid"), validationClient.validateConfig("/tmp/config.toml"))
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
        assertEquals(ServiceState.Listening, repository.status.value.serviceState)

        bridge.failAnswer = true
        val result = repository.start(TunnelMode.Answer, "/tmp/config.toml")
        assertTrue(result.isFailure)
        assertEquals(ServiceState.Listening, repository.status.value.serviceState)
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
                    configPath = "/tmp/config.toml",
                    active = true,
                    mqttConnected = true,
                    activeSessionCount = 2,
                    sessionCapacity = 16,
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
        assertEquals(ServiceState.Listening, status.serviceState)
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
    fun refreshStatusReportsConfigurationErrorInsteadOfFabricatingAnEndpoint() {
        bridge.statusPayload =
            """
            {"state":"running","mode":"offer","active":true,
             "forwards":[
               {"id":"orphan","local_host":null,"local_port":null,"listen_state":"listening",
                "configuration_error":"daemon reported forward 'orphan' but no matching configured endpoint exists"}
             ]}
            """.trimIndent()
        repository.refreshStatus()
        val forward = repository.status.value.forwards.single()
        assertEquals(null, forward.localHost)
        assertEquals(null, forward.localPort)
        // A configuration mismatch always surfaces as an error, regardless of the
        // (meaningless, since there's no real endpoint) listen_state the daemon reported.
        assertEquals(ListenState.Error, forward.listenState)
        assertTrue(forward.configurationError?.contains("orphan") == true)
        assertTrue(forward.lastError?.contains("orphan") == true)
    }

    @Test
    fun refreshStatusForwardUnknownListenStateBecomesError() {
        // P1-004: Unknown listen state must become ListenState.Error, not Stopped.
        bridge.statusPayload =
            """{"state":"running","mode":"offer","active":true,"forwards":[{"id":"x","listen_state":"weird"}]}"""
        repository.refreshStatus()
        val forward = repository.status.value.forwards.single()
        assertEquals(
            "unknown listen state must map to Error, not silently fall back to Stopped",
            ListenState.Error,
            forward.listenState,
        )
        // P1-007: Unknown listen state must surface the raw value (redacted).
        assertTrue(
            "unknown listen state error must include the raw value",
            forward.lastError?.contains("weird") == true,
        )
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

    @Test
    fun runningOfferWithActiveSessionMapsToConnected() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(state = "running", mode = "offer", active = true, activeSessionCount = 1),
            )
        repository.refreshStatus()
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
    }

    @Test
    fun runningAnswerWithActiveSessionMapsToConnected() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(state = "running", mode = "answer", active = true, activeSessionCount = 2),
            )
        repository.refreshStatus()
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
    }

    @Test
    fun unknownNativeStateMapsToErrorNotStopped() {
        bridge.statusPayload = """{"state":"some_future_state","mode":"offer","active":true}"""
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)
    }

    @Test
    fun uptimeIsHiddenForStoppedStateEvenWithStaleTimestamp() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(state = "stopped", mode = "offer", startedAtUnixMs = 1L),
            )
        repository.refreshStatus()
        assertEquals(null, repository.status.value.uptimeSeconds)
    }

    @Test
    fun uptimeIsShownWhileConnected() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(
                    state = "running",
                    mode = "offer",
                    active = true,
                    activeSessionCount = 1,
                    startedAtUnixMs = 1L,
                ),
            )
        repository.refreshStatus()
        assertTrue((repository.status.value.uptimeSeconds ?: -1L) >= 0L)
    }

    /**
     * Regression test for P0-002: `refreshStatus()` used to capture `previous = _status.value`
     * once, before the (blocking) native read, then unconditionally overwrite `_status.value`
     * with a value derived from that stale snapshot. A concurrent `setLocalError(...)` landing
     * while the native read was still in flight would be silently lost the moment the stale
     * refresh finally committed. The fix reads native status outside any atomic mutation and
     * merges against the *current* value inside `updateStatus`'s retry loop, so a concurrent
     * commit can never be overwritten by a stale one.
     */
    @Test
    fun cleanupHistorySurvivesStaleRefreshCommit() {
        bridge.statusPayload = statusJson("running", "offer")
        bridge.blockNextStatusJsonRead()

        val refreshThread = Thread { repository.refreshStatus() }
        refreshThread.start()
        assertTrue(
            "refreshStatus() should have entered the blocked native read by now",
            bridge.awaitStatusJsonReadEntered(5_000),
        )

        repository.setLocalError(code = "stop_failed", message = "cleanup-history-sentinel")

        bridge.releaseBlockedStatusJsonRead()
        refreshThread.join(5_000)
        assertFalse("refreshStatus() should have finished by now", refreshThread.isAlive)

        assertEquals(
            "cleanup-history-sentinel",
            repository.status.value.lastCleanupError?.message,
        )
    }

    /** Same concern as [cleanupHistorySurvivesStaleRefreshCommit], for a concurrent
     * `updateNetworkStatus(...)` instead of `setLocalError(...)`. */
    @Test
    fun networkStatusSurvivesStaleRefreshCommit() {
        bridge.statusPayload = statusJson("running", "offer")
        bridge.blockNextStatusJsonRead()

        val refreshThread = Thread { repository.refreshStatus() }
        refreshThread.start()
        assertTrue(
            "refreshStatus() should have entered the blocked native read by now",
            bridge.awaitStatusJsonReadEntered(5_000),
        )

        val latestNetworkStatus =
            NetworkPolicyStatus(
                networkType = NetworkType.Cellular,
                isMetered = true,
                allowedByDefault = false,
                allowedByUserPolicy = false,
                tunnelAllowed = false,
                blockReason = "network-status-sentinel",
            )
        repository.updateNetworkStatus(latestNetworkStatus)

        bridge.releaseBlockedStatusJsonRead()
        refreshThread.join(5_000)
        assertFalse("refreshStatus() should have finished by now", refreshThread.isAlive)

        assertEquals(latestNetworkStatus, repository.status.value.networkStatus)
    }

    // P0-009: start_verification_cleanup_failed must be sticky cleanup history
    @Test
    fun startVerificationCleanupFailedIsStickyHistory() {
        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()

        repository.setLocalError(
            code = "start_verification_cleanup_failed",
            message = "cleanup-failure-sentinel",
        )

        val status = repository.status.value
        assertEquals(
            "start_verification_cleanup_failed should set lastCleanupError",
            "cleanup-failure-sentinel",
            status.lastCleanupError?.message,
        )

        // A later successful refresh must not erase the cleanup history.
        bridge.statusPayload = statusJson("running", "offer")
        repository.refreshStatus()

        assertEquals(
            "cleanup history must survive later refresh",
            "cleanup-failure-sentinel",
            repository.status.value.lastCleanupError?.message,
        )
    }

    private fun statusJson(
        state: String,
        mode: String,
    ): String =
        Json.encodeToString(
            NativeRuntimeStatusDto(
                state = state,
                mode = mode,
                configPath = "/tmp/config.toml",
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

        // P0-002: deterministic barrier for a status read blocked mid-flight, exercised by
        // a real background Thread concurrently with the test thread, so — like
        // FailableRecordingBridge's equivalent — these specific fields are thread-safe
        // primitives even though the rest of this fake's fields are plain (untouched by the
        // new concurrency tests that use this barrier).
        private val blockStatusJsonRead = AtomicBoolean(false)
        private val statusJsonReadEntered = AtomicReference(CountDownLatch(0))
        private val statusJsonReadRelease = AtomicReference(CountDownLatch(0))

        fun blockNextStatusJsonRead() {
            statusJsonReadEntered.set(CountDownLatch(1))
            statusJsonReadRelease.set(CountDownLatch(1))
            blockStatusJsonRead.set(true)
        }

        fun awaitStatusJsonReadEntered(timeoutMs: Long): Boolean =
            statusJsonReadEntered.get().await(timeoutMs, TimeUnit.MILLISECONDS)

        fun releaseBlockedStatusJsonRead() {
            statusJsonReadRelease.get().countDown()
        }

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

        override fun getStatusJson(): String {
            if (blockStatusJsonRead.compareAndSet(true, false)) {
                statusJsonReadEntered.get().countDown()
                check(statusJsonReadRelease.get().await(5, TimeUnit.SECONDS)) {
                    "blocked status JSON read was never released"
                }
            }
            return statusPayloads.pollFirst() ?: statusPayload
        }

        override fun getRecentLogsJson(maxEvents: Int): String = logsPayloads.pollFirst() ?: logsJson

        override fun validateConfig(configPath: String): ValidationResult = validationResult

        override fun validateConfigWithIdentity(
            configPath: String,
            identityBytes: ByteArray,
        ): ValidationResult = validationResult

        override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = "canon",
                canonicalPrivateIdentity = identityToml,
                peerId = "peer",
            )

        override fun validatePublicIdentity(line: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonicalPublicIdentity = line.trim(), peerId = "peer")

        override fun generateIdentity(peerId: String): IdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = "canon",
                canonicalPrivateIdentity = "private",
                peerId = peerId,
            )
    }
}
