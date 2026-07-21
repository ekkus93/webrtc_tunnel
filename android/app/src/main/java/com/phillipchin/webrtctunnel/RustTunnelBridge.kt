package com.phillipchin.webrtctunnel

import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

interface TunnelControlBridge {
    fun startOffer(
        configPath: String,
        identityBytes: ByteArray? = null,
    ): Result<Unit>

    fun startAnswer(configPath: String): Result<Unit>

    fun stop(): Result<Unit>

    fun getStatusJson(): String

    fun getRecentLogsJson(maxEvents: Int): String
}

interface TunnelValidationBridge {
    fun validateConfig(configPath: String): ValidationResult

    fun validateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): ValidationResult

    fun validatePrivateIdentity(identityToml: String): IdentityValidationResult

    fun validatePublicIdentity(line: String): IdentityValidationResult

    fun generateIdentity(peerId: String): IdentityValidationResult
}

interface TunnelNativeBridge : TunnelControlBridge, TunnelValidationBridge

// Loads libp2p_mobile once for the process; both native declaring classes share it.
private object NativeLibLoader {
    val available: Boolean
    val loadError: Throwable?

    init {
        // FIX7 P1-005-B: safe as runCatching — this runs in a static object's init block,
        // outside any coroutine, before any coroutine could exist; it cannot observe or
        // swallow a CancellationException.
        val load = runCatching { System.loadLibrary("p2p_mobile") }
        available = load.isSuccess
        loadError = load.exceptionOrNull()
    }
}

private fun requireNativeLoaded() {
    if (!NativeLibLoader.available) {
        // Retains the load error as cause, which error()/check() cannot express.
        throw IllegalStateException(
            "Native library p2p_mobile failed to load",
            NativeLibLoader.loadError,
        )
    }
}

// JNI declarations for runtime/control entry points (Java_..._NativeControlLib_*).
internal class NativeControlLib {
    external fun nativeCreateRuntime(): Long

    external fun nativeDestroyRuntime(handle: Long)

    external fun nativeStartOffer(
        handle: Long,
        configPath: String,
    ): Int

    external fun nativeStartOfferWithIdentity(
        handle: Long,
        configPath: String,
        identityBytes: ByteArray,
    ): Int

    external fun nativeStartAnswer(
        handle: Long,
        configPath: String,
    ): Int

    external fun nativeStop(handle: Long): Int

    external fun nativeStatusJson(handle: Long): String

    external fun nativeRecentLogsJson(
        handle: Long,
        maxEvents: Int,
    ): String

    external fun nativeLastError(handle: Long): String
}

// Stateless validators; own JNI declarations (Java_..._RustValidationBridge_*).
class RustValidationBridge : TunnelValidationBridge {
    override fun validateConfig(configPath: String): ValidationResult {
        requireNativeLoaded()
        return Json.decodeFromString(nativeValidateConfig(configPath))
    }

    override fun validateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): ValidationResult {
        requireNativeLoaded()
        return Json.decodeFromString(nativeValidateConfigWithIdentity(configPath, identityBytes))
    }

    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult {
        requireNativeLoaded()
        return Json.decodeFromString(nativeValidatePrivateIdentity(identityToml))
    }

    override fun validatePublicIdentity(line: String): IdentityValidationResult {
        requireNativeLoaded()
        return Json.decodeFromString(nativeValidatePublicIdentity(line))
    }

    override fun generateIdentity(peerId: String): IdentityValidationResult {
        requireNativeLoaded()
        return Json.decodeFromString(nativeGenerateIdentity(peerId))
    }

    private external fun nativeValidateConfig(configPath: String): String

    private external fun nativeValidateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): String

    private external fun nativeValidatePrivateIdentity(identityToml: String): String

    private external fun nativeValidatePublicIdentity(line: String): String

    private external fun nativeGenerateIdentity(peerId: String): String
}

// Stateless on-device WebRTC self-diagnostic; own JNI declaration
// (Java_..._RustWebRtcProbe_*). Returns a JSON report of OS local IP, ICE candidate
// gathering (host/srflx counts), and a loopback handshake — used to check how
// `p2p-webrtc` behaves on Android without a broker, remote peer, or NAT traversal.
class RustWebRtcProbe {
    fun probe(timeoutSecs: Long): String {
        requireNativeLoaded()
        return nativeWebrtcProbe(timeoutSecs)
    }

    private external fun nativeWebrtcProbe(timeoutSecs: Long): String
}

class RustTunnelBridge(
    private val validation: RustValidationBridge = RustValidationBridge(),
) : TunnelNativeBridge, TunnelValidationBridge by validation {
    private val control = NativeControlLib()
    private var runtimeHandle: Long = if (NativeLibLoader.available) control.nativeCreateRuntime() else 0L
    private var disposed: Boolean = false

    // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this is a
    // native JNI mutation (starts the Rust runtime), and runCatching's Throwable-catching
    // could silently convert a native-side fatal Error into an ordinary Result.failure.
    override fun startOffer(
        configPath: String,
        identityBytes: ByteArray?,
    ): Result<Unit> =
        try {
            ensureNativeAvailable()
            val code =
                if (identityBytes == null) {
                    control.nativeStartOffer(runtimeHandle, configPath)
                } else {
                    control.nativeStartOfferWithIdentity(runtimeHandle, configPath, identityBytes)
                }
            check(code == 0) { control.nativeLastError(runtimeHandle) }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    override fun startAnswer(configPath: String): Result<Unit> =
        try {
            ensureNativeAvailable()
            check(control.nativeStartAnswer(runtimeHandle, configPath) == 0) { control.nativeLastError(runtimeHandle) }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    // FIX7 P1-005-B: this is the native cleanup call itself — explicit catch, not runCatching.
    override fun stop(): Result<Unit> =
        try {
            ensureNativeAvailable()
            check(control.nativeStop(runtimeHandle) == 0) { control.nativeLastError(runtimeHandle) }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    override fun getStatusJson(): String {
        ensureNativeAvailable()
        return control.nativeStatusJson(runtimeHandle)
    }

    override fun getRecentLogsJson(maxEvents: Int): String {
        ensureNativeAvailable()
        return control.nativeRecentLogsJson(runtimeHandle, maxEvents)
    }

    fun dispose() {
        if (disposed) {
            return
        }
        if (runtimeHandle != 0L) {
            control.nativeDestroyRuntime(runtimeHandle)
            runtimeHandle = 0L
        }
        disposed = true
    }

    private fun ensureNativeAvailable() {
        if (disposed) {
            error("Tunnel bridge is disposed")
        }
        requireNativeLoaded()
        check(runtimeHandle != 0L) { "Native runtime handle is unavailable" }
    }
}
