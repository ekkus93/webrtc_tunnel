package com.phillipchin.webrtctunnel.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import org.junit.Before

/** A TunnelNativeBridge double that records status reads and returns a configurable validation result. */
class RecordingBridge : TunnelNativeBridge {
    var statusReads = 0
    var validationResult: ValidationResult = ValidationResult(true, null)

    override fun startOffer(
        configPath: String,
        identityBytes: ByteArray?,
    ): Result<Unit> = Result.success(Unit)

    override fun startAnswer(configPath: String): Result<Unit> = Result.success(Unit)

    override fun stop(): Result<Unit> = Result.success(Unit)

    override fun getStatusJson(): String {
        statusReads += 1
        return kotlinx.serialization.json.Json.encodeToString(
            NativeRuntimeStatusDto.serializer(),
            NativeRuntimeStatusDto(state = "stopped", mode = "offer"),
        )
    }

    override fun getRecentLogsJson(maxEvents: Int): String = "[]"

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
            peerId = "android-phone",
        )

    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(valid = true, canonicalPublicIdentity = line.trim(), peerId = "remote-peer")

    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "canon",
            canonicalPrivateIdentity = "private",
            peerId = peerId,
        )
}

/** Shared Robolectric fixture for the per-ViewModel test classes. */
open class AppViewModelTestBase {
    protected val app: Application = ApplicationProvider.getApplicationContext()
    protected lateinit var configRepository: ConfigRepository
    protected lateinit var recordingBridge: RecordingBridge
    protected lateinit var tunnelRepository: TunnelRepository
    protected lateinit var deps: AppDependencies

    @Before
    fun setUp() {
        configRepository = ConfigRepository(app)
        recordingBridge = RecordingBridge()
        deps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        NetworkType.UnmeteredWifi to false
                    },
                identityRepository =
                    IdentityRepository(
                        app,
                        object : IdentityCrypto {
                            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                            override fun decrypt(payload: ByteArray): ByteArray = payload
                        },
                    ),
            )
        tunnelRepository = deps.tunnelRepository
    }
}
