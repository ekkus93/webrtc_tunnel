package com.phillipchin.webrtctunnel

import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.model.TunnelStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface TunnelNativeBridge {
    fun startOffer(configPath: String, identityBytes: ByteArray? = null): Result<Unit>
    fun startAnswer(configPath: String): Result<Unit>
    fun stop(): Result<Unit>
    fun getStatusJson(): String
    fun getRecentLogsJson(maxEvents: Int): String
    fun validateConfig(configPath: String): ValidationResult
    fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult
    fun validatePrivateIdentity(identityToml: String): IdentityValidationResult
    fun validatePublicIdentity(line: String): IdentityValidationResult
    fun generateIdentity(peerId: String): IdentityValidationResult
}

class RustTunnelBridge : TunnelNativeBridge {
    companion object {
        private var nativeAvailable: Boolean = false
        private var nativeLoadError: Throwable? = null
        init {
            val load = runCatching { System.loadLibrary("p2p_mobile") }
            nativeAvailable = load.isSuccess
            nativeLoadError = load.exceptionOrNull()
        }
    }

    private var runtimeHandle: Long = if (nativeAvailable) nativeCreateRuntime() else 0L

    override fun startOffer(configPath: String, identityBytes: ByteArray?): Result<Unit> = runCatching {
        ensureNativeAvailable()
        val code = if (identityBytes == null) {
            nativeStartOffer(runtimeHandle, configPath)
        } else {
            nativeStartOfferWithIdentity(runtimeHandle, configPath, identityBytes)
        }
        check(code == 0) { nativeLastError(runtimeHandle) }
    }

    override fun startAnswer(configPath: String): Result<Unit> = runCatching {
        ensureNativeAvailable()
        check(nativeStartAnswer(runtimeHandle, configPath) == 0) { nativeLastError(runtimeHandle) }
    }

    override fun stop(): Result<Unit> = runCatching {
        ensureNativeAvailable()
        check(nativeStop(runtimeHandle) == 0) { nativeLastError(runtimeHandle) }
    }

    override fun getStatusJson(): String {
        ensureNativeAvailable()
        return nativeStatusJson(runtimeHandle)
    }

    override fun getRecentLogsJson(maxEvents: Int): String {
        ensureNativeAvailable()
        return nativeRecentLogsJson(runtimeHandle, maxEvents)
    }

    override fun validateConfig(configPath: String): ValidationResult {
        ensureNativeAvailable()
        return Json.decodeFromString(nativeValidateConfig(configPath))
    }

    override fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult {
        ensureNativeAvailable()
        return Json.decodeFromString(nativeValidateConfigWithIdentity(configPath, identityBytes))
    }

    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult {
        ensureNativeAvailable()
        return Json.decodeFromString(nativeValidatePrivateIdentity(identityToml))
    }

    override fun validatePublicIdentity(line: String): IdentityValidationResult {
        ensureNativeAvailable()
        return Json.decodeFromString(nativeValidatePublicIdentity(line))
    }

    override fun generateIdentity(peerId: String): IdentityValidationResult {
        ensureNativeAvailable()
        return Json.decodeFromString(nativeGenerateIdentity(peerId))
    }

    fun dispose() {
        if (runtimeHandle != 0L) {
            nativeDestroyRuntime(runtimeHandle)
        }
        runtimeHandle = 0L
    }

    private fun ensureNativeAvailable() {
        if (!nativeAvailable) {
            throw IllegalStateException(
                "Native library p2p_mobile failed to load",
                nativeLoadError,
            )
        }
    }

    private external fun nativeCreateRuntime(): Long
    private external fun nativeDestroyRuntime(handle: Long)
    private external fun nativeStartOffer(handle: Long, configPath: String): Int
    private external fun nativeStartOfferWithIdentity(
        handle: Long,
        configPath: String,
        identityBytes: ByteArray,
    ): Int
    private external fun nativeStartAnswer(handle: Long, configPath: String): Int
    private external fun nativeStop(handle: Long): Int
    private external fun nativeStatusJson(handle: Long): String
    private external fun nativeRecentLogsJson(handle: Long, maxEvents: Int): String
    private external fun nativeValidateConfig(configPath: String): String
    private external fun nativeValidateConfigWithIdentity(configPath: String, identityBytes: ByteArray): String
    private external fun nativeValidatePrivateIdentity(identityToml: String): String
    private external fun nativeValidatePublicIdentity(line: String): String
    private external fun nativeGenerateIdentity(peerId: String): String
    private external fun nativeLastError(handle: Long): String
}

class FakeTunnelBridge : TunnelNativeBridge {
    private var status = TunnelStatus(
        serviceState = com.phillipchin.webrtctunnel.model.ServiceState.Stopped,
        mode = com.phillipchin.webrtctunnel.model.TunnelMode.Offer,
        localPeerId = "android-phone",
    )

    override fun startOffer(configPath: String, identityBytes: ByteArray?): Result<Unit> = runCatching {
        status = status.copy(serviceState = com.phillipchin.webrtctunnel.model.ServiceState.Connected)
    }

    override fun startAnswer(configPath: String): Result<Unit> = runCatching {
        status = status.copy(serviceState = com.phillipchin.webrtctunnel.model.ServiceState.Serving)
    }

    override fun stop(): Result<Unit> = runCatching {
        status = status.copy(serviceState = com.phillipchin.webrtctunnel.model.ServiceState.Stopped)
    }

    override fun getStatusJson(): String = Json.encodeToString(TunnelStatus.serializer(), status)

    override fun getRecentLogsJson(maxEvents: Int): String =
        Json.encodeToString(
            ListSerializer(LogEvent.serializer()),
            List(maxEvents.coerceAtMost(3)) { LogEvent(0L, "info", "fake log $it") }
        )

    override fun validateConfig(configPath: String): ValidationResult = ValidationResult(true, null)
    override fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult =
        ValidationResult(true, null)
    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonical_public_identity = "p2ptunnel-ed25519 peer_id=android-phone sign_pub=Zm9v kex_pub=YmFy", canonical_private_identity = identityToml, peer_id = "android-phone")
    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(valid = line.isNotBlank(), message = if (line.isBlank()) "empty" else null, canonical_public_identity = line.trim(), peer_id = "remote-peer")
    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonical_public_identity = "p2ptunnel-ed25519 peer_id=$peerId sign_pub=Zm9v kex_pub=YmFy", canonical_private_identity = "format = \"p2ptunnel-identity-v1\"\npeer_id = \"$peerId\"\n", peer_id = peerId)
}
