package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
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
        appDependencies = AppDependencies(
            context = this,
            configRepository = configRepository,
            tunnelRepository = TunnelRepository(this, bridge),
            networkPolicyManager = NetworkPolicyManager {
                com.phillipchin.webrtctunnel.model.NetworkStatus(
                    networkType = com.phillipchin.webrtctunnel.model.NetworkType.UnmeteredWifi,
                    isMetered = false,
                    tunnelAllowed = true,
                )
            },
            identityRepository = IdentityRepository(this, object : IdentityCrypto {
                override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
                override fun decrypt(payload: ByteArray): ByteArray = payload
            }),
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

    override fun startOffer(configPath: String): Result<Unit> {
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
        TunnelStatus(
            serviceState = state,
            mode = if (state == ServiceState.Serving) TunnelMode.Answer else TunnelMode.Offer,
            localPeerId = "android-phone",
        ),
    )

    override fun getRecentLogsJson(maxEvents: Int): String = "[]"

    override fun validateConfig(configPath: String): ValidationResult = ValidationResult(true, null)
}
