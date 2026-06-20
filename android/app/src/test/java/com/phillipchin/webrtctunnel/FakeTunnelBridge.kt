package com.phillipchin.webrtctunnel

import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val FAKE_LOG_LIMIT = 3

/**
 * Test-only in-memory [TunnelNativeBridge] that simulates a successful native tunnel without
 * the Rust JNI library. It lives in the test source set so a release build can neither
 * reference nor instantiate a fake success bridge — production always uses [RustTunnelBridge].
 */
class FakeTunnelBridge : TunnelNativeBridge {
    private var state = "stopped"
    private var mode = "offer"

    override fun startOffer(
        configPath: String,
        identityBytes: ByteArray?,
    ): Result<Unit> =
        runCatching {
            mode = "offer"
            state = "running"
        }

    override fun startAnswer(configPath: String): Result<Unit> =
        runCatching {
            mode = "answer"
            state = "running"
        }

    override fun stop(): Result<Unit> =
        runCatching {
            state = "stopped"
        }

    override fun getStatusJson(): String =
        Json.encodeToString(
            NativeRuntimeStatusDto.serializer(),
            NativeRuntimeStatusDto(
                state = state,
                mode = mode,
                configPath = "/tmp/fake-config.toml",
                active = state == "running",
            ),
        )

    override fun getRecentLogsJson(maxEvents: Int): String =
        Json.encodeToString(
            ListSerializer(NativeLogEventDto.serializer()),
            List(maxEvents.coerceAtMost(FAKE_LOG_LIMIT)) { NativeLogEventDto(0L, "info", "fake log $it") },
        )

    override fun validateConfig(configPath: String): ValidationResult = ValidationResult(true, null)

    override fun validateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): ValidationResult = ValidationResult(true, null)

    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "p2ptunnel-ed25519 peer_id=android-phone sign_pub=Zm9v kex_pub=YmFy",
            canonicalPrivateIdentity = identityToml,
            peerId = "android-phone",
        )

    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = line.isNotBlank(),
            message = if (line.isBlank()) "empty" else null,
            canonicalPublicIdentity = line.trim(),
            peerId = "remote-peer",
        )

    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "p2ptunnel-ed25519 peer_id=$peerId sign_pub=Zm9v kex_pub=YmFy",
            canonicalPrivateIdentity = "format = \"p2ptunnel-identity-v1\"\npeer_id = \"$peerId\"\n",
            peerId = peerId,
        )
}
