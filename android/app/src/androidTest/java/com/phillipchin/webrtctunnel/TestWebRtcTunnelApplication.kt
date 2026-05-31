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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    var startOfferEnterCalls = 0
    var startAnswerCalls = 0
    var stopCalls = 0
    private var blockStartOffer = false
    private var startOfferEntered = CountDownLatch(0)
    private var startOfferRelease = CountDownLatch(0)
    private var blockValidation = false
    private var validationEntered = CountDownLatch(0)
    private var validationRelease = CountDownLatch(0)
    var state: ServiceState = ServiceState.Stopped

    fun reset() {
        startOfferCalls = 0
        startOfferEnterCalls = 0
        startAnswerCalls = 0
        stopCalls = 0
        blockStartOffer = false
        startOfferEntered = CountDownLatch(0)
        startOfferRelease = CountDownLatch(0)
        blockValidation = false
        validationEntered = CountDownLatch(0)
        validationRelease = CountDownLatch(0)
        state = ServiceState.Stopped
    }

    fun blockNextStartOffer() {
        blockStartOffer = true
        startOfferEntered = CountDownLatch(1)
        startOfferRelease = CountDownLatch(1)
    }

    fun awaitStartOfferEntered(timeoutMs: Long): Boolean = startOfferEntered.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun releaseBlockedStartOffer() {
        startOfferRelease.countDown()
    }

    fun blockNextValidation() {
        blockValidation = true
        validationEntered = CountDownLatch(1)
        validationRelease = CountDownLatch(1)
    }

    fun awaitValidationEntered(timeoutMs: Long): Boolean = validationEntered.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun releaseBlockedValidation() {
        validationRelease.countDown()
    }

    override fun startOffer(configPath: String, identityBytes: ByteArray?): Result<Unit> {
        startOfferEnterCalls += 1
        if (blockStartOffer) {
            startOfferEntered.countDown()
            startOfferRelease.await(5, TimeUnit.SECONDS)
            blockStartOffer = false
        }
        startOfferCalls += 1
        if (stopCalls == 0) {
            state = ServiceState.Connected
        }
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
        if (blockValidation) {
            validationEntered.countDown()
            validationRelease.await(5, TimeUnit.SECONDS)
            blockValidation = false
            ValidationResult(true, null)
        } else {
            ValidationResult(true, null)
        }
    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonical_public_identity = "android-phone ssh-ed25519 AAAA test", canonical_private_identity = identityToml, peer_id = "android-phone")
    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(valid = line.isNotBlank(), message = if (line.isBlank()) "empty" else null, canonical_public_identity = line.trim(), peer_id = "desktop-peer")
    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonical_public_identity = "$peerId ssh-ed25519 AAAA generated", canonical_private_identity = "[identity]\npeer_id = \"$peerId\"\n", peer_id = peerId)
}
