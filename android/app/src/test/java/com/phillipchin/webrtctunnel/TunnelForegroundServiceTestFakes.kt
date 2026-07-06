package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A `test`-only-scoped Application/bridge fake for
 * [TunnelForegroundServiceStopFailureTest]. Deliberately not shared with
 * `androidTest`'s [com.phillipchin.webrtctunnel.TestWebRtcTunnelApplication]/
 * `RecordingBridge` via a shared source set: doing so was tried first (P0-003/
 * P0-004) and empirically caused unrelated Robolectric `viewmodel` tests
 * elsewhere in this module to fail when run in the same JVM, for reasons not
 * pinned down further. A small, self-contained duplicate avoids that risk.
 */
object TunnelForegroundServiceTestHooks {
    lateinit var bridge: FailableRecordingBridge
}

class TunnelForegroundServiceTestApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        val bridge = FailableRecordingBridge()
        TunnelForegroundServiceTestHooks.bridge = bridge
        val configRepository = ConfigRepository(this)
        val identityRepository =
            IdentityRepository(
                this,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = payload
                },
            )
        identityRepository.storeEncryptedIdentity(
            """
            [identity]
            peer_id = "android-phone"
            signing_key = "test-signing-key"
            kex_secret = "test-kex-secret"
            """.trimIndent().toByteArray(),
            "android-phone ssh-ed25519 AAAA test",
        )
        appDependencies =
            AppDependencies(
                context = this,
                nativeBridgeFactory = { bridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        com.phillipchin.webrtctunnel.model.NetworkType.UnmeteredWifi to false
                    },
                identityRepository = identityRepository,
            )
        configRepository.ensureDefaultConfig(configRepository.defaultConfigTemplate())
        // Pin resumeOnUnmetered = false regardless of any residual preference left on
        // disk by an earlier Robolectric test sharing this JVM's real DataStore file:
        // the fake NetworkPolicyManager below always reports UnmeteredWifi, so leaving
        // this at its true default would race the service's own auto-resume-on-unmetered
        // feature against this test's direct pauseForPolicy() calls.
        runBlocking { configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = false)) }
    }
}

/**
 * Minimal native-bridge fake: only `stop()` is exercised (with an injectable one-shot
 * failure); every other call just reports an idle/stopped state.
 */
class FailableRecordingBridge : TunnelNativeBridge {
    var stopCalls = 0
    private var failNextStop = false
    var state: ServiceState = ServiceState.Stopped

    /** The next (and only the next) `stop()` call fails instead of succeeding. */
    fun failNextStop() {
        failNextStop = true
    }

    override fun startOffer(
        configPath: String,
        identityBytes: ByteArray?,
    ): Result<Unit> {
        state = ServiceState.Connected
        return Result.success(Unit)
    }

    override fun startAnswer(configPath: String): Result<Unit> {
        state = ServiceState.Serving
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        stopCalls += 1
        if (failNextStop) {
            failNextStop = false
            return Result.failure(RuntimeException("injected stop failure"))
        }
        state = ServiceState.Stopped
        return Result.success(Unit)
    }

    override fun getStatusJson(): String =
        Json.encodeToString(
            NativeRuntimeStatusDto(
                state =
                    when (state) {
                        ServiceState.Connected, ServiceState.Serving -> "running"
                        ServiceState.Starting -> "starting"
                        ServiceState.Stopping -> "stopping"
                        ServiceState.Error -> "error"
                        else -> "stopped"
                    },
                mode = if (state == ServiceState.Serving) "answer" else "offer",
                active = state == ServiceState.Connected || state == ServiceState.Serving,
            ),
        )

    override fun getRecentLogsJson(maxEvents: Int): String = "[]"

    override fun validateConfig(configPath: String): ValidationResult = ValidationResult(true, null)

    override fun validateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): ValidationResult = ValidationResult(true, null)

    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "android-phone ssh-ed25519 AAAA test",
            canonicalPrivateIdentity = identityToml,
            peerId = "android-phone",
        )

    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = line.isNotBlank(),
            message = if (line.isBlank()) "empty" else null,
            canonicalPublicIdentity = line.trim(),
            peerId = "desktop-peer",
        )

    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "$peerId ssh-ed25519 AAAA generated",
            canonicalPrivateIdentity = "[identity]\npeer_id = \"$peerId\"\n",
            peerId = peerId,
        )
}
