package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared `TunnelNativeBridge` fake for [TunnelRepositoryTest] and
 * [TunnelRepositoryInvalidStatusTest] — extracted so neither test class grows large
 * enough to trip detekt's `LargeClass` threshold.
 */
internal class RecordingBridge : TunnelNativeBridge {
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
