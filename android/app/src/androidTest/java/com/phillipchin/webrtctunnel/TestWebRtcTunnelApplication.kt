package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TestTunnelHooks {
    lateinit var bridge: RecordingBridge
}

class TestWebRtcTunnelApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        val bridge = RecordingBridge()
        TestTunnelHooks.bridge = bridge
        val configRepository = ConfigRepository(this)
        val identityRepository = IdentityRepository(this, object : IdentityCrypto {
            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
            override fun decrypt(payload: ByteArray): ByteArray = payload
        })
        identityRepository.storeEncryptedIdentity(
            """
            [identity]
            peer_id = "android-phone"
            signing_key = "test-signing-key"
            kex_secret = "test-kex-secret"
            """.trimIndent().toByteArray(),
            "android-phone ssh-ed25519 AAAA test",
        )
        appDependencies = AppDependencies(
            context = this,
            configRepository = configRepository,
            tunnelRepository = TunnelRepository(this, bridge),
            networkPolicyManager = NetworkPolicyManager {
                com.phillipchin.webrtctunnel.model.NetworkType.UnmeteredWifi to false
            },
            identityRepository = identityRepository,
        )
        configRepository.ensureDefaultConfig(configRepository.defaultConfigTemplate())
    }
}

class RecordingBridge : TunnelNativeBridge {
    var startOfferCalls = 0
    var startAnswerCalls = 0
    var stopCalls = 0
    var state: ServiceState = ServiceState.Stopped

    fun reset() {
        startOfferCalls = 0
        startAnswerCalls = 0
        stopCalls = 0
        state = ServiceState.Stopped
    }

    override fun startOffer(configPath: String, identityBytes: ByteArray?): Result<Unit> {
        startOfferCalls += 1
        state = ServiceState.Connected
        return Result.success(Unit)
    }

    override fun startAnswer(configPath: String): Result<Unit> {
        startAnswerCalls += 1
        state = ServiceState.Serving
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        stopCalls += 1
        state = ServiceState.Stopped
        return Result.success(Unit)
    }

    override fun getStatusJson(): String = Json.encodeToString(
        NativeRuntimeStatusDto(
            state = when (state) {
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
    override fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult =
        ValidationResult(true, null)
    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonical_public_identity = "android-phone ssh-ed25519 AAAA test", canonical_private_identity = identityToml, peer_id = "android-phone")
    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(valid = line.isNotBlank(), message = if (line.isBlank()) "empty" else null, canonical_public_identity = line.trim(), peer_id = "desktop-peer")
    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonical_public_identity = "$peerId ssh-ed25519 AAAA generated", canonical_private_identity = "[identity]\npeer_id = \"$peerId\"\n", peer_id = peerId)
}
