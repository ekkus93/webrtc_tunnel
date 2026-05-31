package com.phillipchin.webrtctunnel.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

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
                com.phillipchin.webrtctunnel.model.NetworkStatus(NetworkType.UnmeteredWifi, false, true, null)
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
        assertNotNull(started)
        assertEquals(TunnelForegroundService.ACTION_START_ANSWER, started.action)
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
        configRepository.writeConfig("before")
        recordingBridge.validationResult = ValidationResult(false, "bad config")
        assertEquals(ValidationResult(false, "bad config"), viewModel.validateConfig())
        viewModel.saveConfig("after")
        assertEquals("after", configRepository.readConfig())
    }

    @Test
    fun settingsViewModelDelegatesValidation() {
        val viewModel = SettingsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, "ok")
        assertEquals(ValidationResult(true, "ok"), viewModel.validateConfig())
    }

    private class RecordingBridge : TunnelNativeBridge {
        var statusReads = 0
        var validationResult: ValidationResult = ValidationResult(true, null)

        override fun startOffer(configPath: String): Result<Unit> = Result.success(Unit)

        override fun startAnswer(configPath: String): Result<Unit> = Result.success(Unit)

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun getStatusJson(): String {
            statusReads += 1
            return """{"serviceState":"Stopped","mode":"Offer","localPeerId":"android-phone","networkStatus":{"networkType":"NoNetwork","isMetered":false,"tunnelAllowed":false}}"""
        }

        override fun getRecentLogsJson(maxEvents: Int): String = "[]"

        override fun validateConfig(configPath: String): ValidationResult = validationResult
    }
}
