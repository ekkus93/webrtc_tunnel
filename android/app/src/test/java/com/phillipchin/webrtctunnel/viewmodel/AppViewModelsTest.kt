package com.phillipchin.webrtctunnel.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppViewModelsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var configRepository: ConfigRepository
    private lateinit var recordingBridge: RecordingBridge
    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var deps: AppDependencies

    @Before
    fun setUp() {
        configRepository = ConfigRepository(app)
        recordingBridge = RecordingBridge()
        tunnelRepository = TunnelRepository(app, recordingBridge)
        deps = AppDependencies(
            context = app,
            configRepository = configRepository,
            tunnelRepository = tunnelRepository,
            networkPolicyManager = NetworkPolicyManager {
                com.phillipchin.webrtctunnel.model.NetworkStatus(
                    NetworkType.UnmeteredWifi,
                    false,
                    true,
                    true,
                    true,
                    null,
                )
            },
            identityRepository = IdentityRepository(app, object : IdentityCrypto {
                override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
                override fun decrypt(payload: ByteArray): ByteArray = payload
            }),
        )
    }

    @Test
    fun homeViewModelStartOfferSendsForegroundServiceIntent() {
        val viewModel = HomeViewModel(deps)
        viewModel.startTunnel(TunnelMode.Offer)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(TunnelForegroundService.ACTION_START_OFFER, started.action)
        assertEquals(TunnelForegroundService::class.java.name, started.component?.className)
    }

    @Test
    fun homeViewModelStartAnswerSendsForegroundServiceIntent() {
        val viewModel = HomeViewModel(deps)
        viewModel.startTunnel(TunnelMode.Answer)
        val started = Shadows.shadowOf(app).nextStartedService
        assertEquals(null, started)
    }

    @Test
    fun homeViewModelStopSendsStopIntent() {
        val viewModel = HomeViewModel(deps)
        viewModel.stopTunnel()
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(TunnelForegroundService.ACTION_STOP, started.action)
        assertEquals(TunnelForegroundService::class.java.name, started.component?.className)
    }

    @Test
    fun homeViewModelRefreshDelegatesToRepository() {
        val viewModel = HomeViewModel(deps)
        assertSame(tunnelRepository.status, viewModel.status)
        viewModel.refresh()
        assertEquals(1, recordingBridge.statusReads)
    }

    @Test
    fun setupViewModelDelegatesValidationAndSave() {
        val viewModel = SetupViewModel(deps)
        val identityFile = File(app.filesDir, "incoming_identity.toml").apply {
            writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
        }
        val forward = ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)
        configRepository.saveForwards(listOf(forward))
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "desktop-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
        viewModel.saveAndApplyConfig()
        assertTrue(configRepository.readConfig().contains("broker.local"))
    }

    @Test
    fun settingsViewModelDelegatesValidation() {
        val viewModel = SettingsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, "ok")
        assertEquals(ValidationResult(true, "ok"), viewModel.validateConfig())
    }

    @Test
    fun setupViewModelBlocksNextWhenBrokerInvalid() {
        val viewModel = SetupViewModel(deps)
        val identityFile = File(app.filesDir, "incoming_identity_for_validation.toml").apply {
            writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
        }
        viewModel.goNext()
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.goNext()
        viewModel.setInput(viewModel.state.value.input.copy(brokerHost = "", brokerPort = 0))
        viewModel.goNext()
        assertEquals(SetupStep.Broker, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage?.contains("Broker host") == true)
    }

    private class RecordingBridge : TunnelNativeBridge {
        var statusReads = 0
        var validationResult: ValidationResult = ValidationResult(true, null)

        override fun startOffer(configPath: String, identityBytes: ByteArray?): Result<Unit> = Result.success(Unit)

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
        override fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult = validationResult
        override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = "canon", canonical_private_identity = identityToml, peer_id = "android-phone")
        override fun validatePublicIdentity(line: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = line.trim(), peer_id = "remote-peer")
        override fun generateIdentity(peerId: String): IdentityValidationResult =
            IdentityValidationResult(valid = true, canonical_public_identity = "canon", canonical_private_identity = "private", peer_id = peerId)
    }
}
