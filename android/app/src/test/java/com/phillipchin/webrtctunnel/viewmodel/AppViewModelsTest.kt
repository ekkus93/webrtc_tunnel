package com.phillipchin.webrtctunnel.viewmodel

import android.app.Application
import android.os.Looper
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.ServerSocket

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
    fun homeViewModelAllowMeteredTemporarilyDoesNotPersistPreference() =
        runBlocking {
            configRepository.savePreferences(
                com.phillipchin.webrtctunnel.model.AndroidAppPreferences(
                    allowMetered = false,
                    resumeOnUnmetered = true,
                    showMeteredWarning = true,
                    startTunnelWhenAppOpens = false,
                    debugLogsEnabled = false,
                    advancedSettingsEnabled = false,
                ),
            )
            val viewModel = HomeViewModel(deps)
            viewModel.allowMeteredTemporarily()
            val started = Shadows.shadowOf(app).nextStartedService
            assertNotNull(started)
            assertEquals(TunnelForegroundService.ACTION_ALLOW_METERED_SESSION, started.action)
            assertEquals(false, configRepository.preferences.first().allowMetered)
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
        prepareValidReviewState(viewModel)
        viewModel.save.saveAndApplyConfig()
        awaitSetupState(viewModel) { it.saveResult == "Configuration saved" }
        assertTrue(configRepository.readConfig().contains("broker.local"))
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun settingsViewModelDelegatesValidation() {
        val viewModel = SettingsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, "ok")
        assertEquals(ValidationResult(true, "ok"), viewModel.validateConfig())
    }

    @Test
    fun settingsViewModelReadsPublicIdentityExactlyOnce() {
        var readCount = 0
        val viewModel =
            SettingsViewModel(
                deps = deps,
                loadPublicIdentity = {
                    readCount += 1
                    "peer_id = \"android-phone\""
                },
            )
        awaitSettingsState(viewModel) { it.publicIdentity != null }
        assertEquals(1, readCount)
    }

    @Test
    fun settingsViewModelLoadsPublicIdentityIntoState() {
        deps.identityRepository.storeEncryptedIdentity("private".toByteArray(), "peer_id = \"android-phone\"")
        val viewModel = SettingsViewModel(deps)
        val state = awaitSettingsState(viewModel) { it.publicIdentity != null }
        assertEquals("peer_id = \"android-phone\"", state.publicIdentity)
        assertEquals(null, state.publicIdentityLoadError)
    }

    @Test
    fun settingsViewModelHandlesMissingPublicIdentity() {
        val viewModel = SettingsViewModel(deps)
        val state = awaitSettingsState(viewModel) { it.publicIdentity == null && it.publicIdentityLoadError == null }
        assertEquals(null, state.publicIdentity)
        assertEquals(null, state.publicIdentityLoadError)
    }

    @Test
    fun settingsViewModelHandlesPublicIdentityReadError() {
        val viewModel =
            SettingsViewModel(
                deps = deps,
                loadPublicIdentity = { throw IllegalStateException("identity read failed") },
            )
        val state = awaitSettingsState(viewModel) { it.publicIdentityLoadError != null }
        assertTrue(state.publicIdentityLoadError?.isNotBlank() == true)
        assertEquals(null, state.publicIdentity)
    }

    @Test
    fun setupViewModelBlocksNextWhenBrokerInvalid() {
        val viewModel = SetupViewModel(deps)
        val identityFile =
            File(app.filesDir, "incoming_identity_for_validation.toml").apply {
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

    @Test
    fun setupViewModelBlocksSaveWhenLocalPeerIdMismatchesIdentityPeerId() {
        val viewModel = SetupViewModel(deps)
        val identityFile =
            File(app.filesDir, "incoming_identity_peer_mismatch.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.identity.importIdentityFromPath()
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.identity.validateRemotePublicIdentity()
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = "different-peer",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
        viewModel.save.saveAndApplyConfig()
        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("Local peer ID must match private identity peer ID") == true)
    }

    @Test
    fun setupViewModelBlocksStartWhenRemotePeerDoesNotMatchPublicIdentityPeerId() {
        val viewModel = SetupViewModel(deps)
        val identityFile =
            File(app.filesDir, "incoming_identity_remote_mismatch.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = "android-phone",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
        viewModel.setInput(viewModel.state.value.input.copy(remotePeerId = "desktop-peer"))
        viewModel.save.startTunnelFromReview()
        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("Remote peer ID must match imported public identity peer ID") == true)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun setupViewModelStartTunnelWaitsForPreferenceSave() {
        val gate = CompletableDeferred<Unit>()
        val viewModel =
            SetupViewModel(
                deps,
                persistPreferences = {
                    gate.await()
                    deps.configRepository.savePreferences(it)
                },
            )
        prepareValidReviewState(viewModel)
        viewModel.save.startTunnelFromReview()
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
        gate.complete(Unit)
        val state = awaitSetupState(viewModel) { it.saveResult == "Tunnel start requested" }
        assertEquals("Tunnel start requested", state.saveResult)
        assertEquals(TunnelForegroundService.ACTION_START_OFFER, Shadows.shadowOf(app).nextStartedService.action)
    }

    @Test
    fun setupViewModelFailedPreferenceSavePreventsStartAndShowsError() {
        val viewModel =
            SetupViewModel(
                deps,
                persistPreferences = { throw IllegalStateException("prefs save failed") },
            )
        prepareValidReviewState(viewModel)
        viewModel.save.startTunnelFromReview()
        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("prefs save failed") == true)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun setupViewModelSuccessfulStartRequestsServiceOnce() {
        val viewModel = SetupViewModel(deps)
        prepareValidReviewState(viewModel)
        viewModel.save.startTunnelFromReview()
        val state = awaitSetupState(viewModel) { it.saveResult == "Tunnel start requested" }
        assertEquals("Tunnel start requested", state.saveResult)
        assertEquals(TunnelForegroundService.ACTION_START_OFFER, Shadows.shadowOf(app).nextStartedService.action)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun setupViewModelFailedConfigValidationPreventsStartAndShowsError() {
        val viewModel = SetupViewModel(deps)
        prepareValidReviewState(viewModel)
        recordingBridge.validationResult = ValidationResult(false, "invalid review config")

        viewModel.save.startTunnelFromReview()

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("invalid review config") == true)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun importExportViewModelRequiresConfirmationForRawConfigExport() {
        val vm = ImportExportViewModel(deps)
        val output = File(app.filesDir, "raw-config-export.toml")
        vm.updateState { it.copy(configExportPath = output.absolutePath) }
        vm.exportConfig(confirmSensitive = false)
        assertTrue(vm.state.value.resultMessage?.contains("requires explicit confirmation") == true)
    }

    @Test
    fun importExportViewModelDeletesTempFileOnSuccessAndFailure() {
        val vm = ImportExportViewModel(deps)
        val tempFile = File(app.cacheDir, "config-import-candidate.toml")
        tempFile.delete()
        val baseline = configRepository.readConfig()
        val validFile = File(app.filesDir, "valid-import-config.toml").apply { writeText(baseline) }

        vm.updateState { it.copy(configImportPath = validFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)
        vm.importConfig()
        assertTrue(!tempFile.exists())
        assertEquals(baseline, configRepository.readConfig())

        val invalidFile = File(app.filesDir, "invalid-import-config.toml").apply { writeText("not valid toml") }
        vm.updateState { it.copy(configImportPath = invalidFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(false, "invalid config")
        vm.importConfig()
        assertTrue(!tempFile.exists())
        assertEquals(baseline, configRepository.readConfig())
        assertNotNull(vm.state.value.resultMessage)
    }

    @Test
    fun importExportViewModelDeletesTempFileOnThrownValidationError() {
        val throwingBridge =
            object : TunnelNativeBridge {
                override fun startOffer(
                    configPath: String,
                    identityBytes: ByteArray?,
                ) = Result.success(Unit)

                override fun startAnswer(configPath: String) = Result.success(Unit)

                override fun stop() = Result.success(Unit)

                override fun getStatusJson(): String = recordingBridge.getStatusJson()

                override fun getRecentLogsJson(maxEvents: Int): String = "[]"

                override fun validateConfig(configPath: String): ValidationResult = error("boom")

                override fun validateConfigWithIdentity(
                    configPath: String,
                    identityBytes: ByteArray,
                ): ValidationResult = error("boom")

                override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        canonicalPublicIdentity = "canon",
                        canonicalPrivateIdentity = identityToml,
                        peerId = "android-phone",
                    )

                override fun validatePublicIdentity(line: String): IdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        canonicalPublicIdentity = line.trim(),
                        peerId = "remote-peer",
                    )

                override fun generateIdentity(peerId: String): IdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        canonicalPublicIdentity = "canon",
                        canonicalPrivateIdentity = "private",
                        peerId = peerId,
                    )
            }
        val throwingDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { throwingBridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        NetworkType.UnmeteredWifi to false
                    },
                identityRepository = deps.identityRepository,
            )
        val vm = ImportExportViewModel(throwingDeps)
        val tempFile = File(app.cacheDir, "config-import-candidate.toml")
        tempFile.delete()
        val configFile =
            File(
                app.filesDir,
                "exception-import-config.toml",
            ).apply { writeText(configRepository.readConfig()) }
        vm.updateState { it.copy(configImportPath = configFile.absolutePath) }
        vm.importConfig()
        assertTrue(!tempFile.exists())
        assertTrue(vm.state.value.resultMessage?.contains("boom") == true)
    }

    @Test
    fun forwardsViewModelTestLocalPortReportsSuccessAndFailure() {
        runBlocking {
            val server = ServerSocket(0)
            val successVm = ForwardsViewModel(deps)
            val successForward =
                ForwardConfig(
                    id = "svc-open",
                    name = "svc-open",
                    localHost = "127.0.0.1",
                    localPort = server.localPort,
                    remoteForwardId = "svc-open",
                    enabled = true,
                )
            val successMessage =
                async {
                    withTimeout(5_000) {
                        successVm.message.first { it?.contains("succeeded") == true }
                    }
                }
            successVm.testLocalPort(successForward)
            assertTrue(successMessage.await()?.contains("succeeded") == true)
            server.close()

            val failureVm = ForwardsViewModel(deps)
            val failureForward = successForward.copy(id = "svc-closed", localPort = successForward.localPort)
            val failureMessage =
                async {
                    withTimeout(5_000) {
                        failureVm.message.first { it?.contains("failed") == true }
                    }
                }
            failureVm.testLocalPort(failureForward)
            assertTrue(failureMessage.await()?.contains("failed") == true)
        }
    }

    private class RecordingBridge : TunnelNativeBridge {
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

    private fun prepareValidReviewState(viewModel: SetupViewModel) {
        val identityFile =
            File(app.filesDir, "incoming_identity.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        val forward = ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)
        deps.forwardsStore.saveForwards(listOf(forward))
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
    }

    private fun awaitSetupState(
        viewModel: SetupViewModel,
        predicate: (SetupWizardState) -> Boolean,
    ): SetupWizardState =
        runBlocking {
            withTimeout(5_000) {
                var matched: SetupWizardState? = null
                while (true) {
                    val current = viewModel.state.value
                    if (predicate(current)) {
                        matched = current
                        break
                    }
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                matched ?: error("Timed out waiting for setup state")
            }
        }

    private fun awaitSettingsState(
        viewModel: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState =
        runBlocking {
            withTimeout(5_000) {
                var matched: SettingsUiState? = null
                while (true) {
                    val current = viewModel.uiState.value
                    if (predicate(current)) {
                        matched = current
                        break
                    }
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                matched ?: error("Timed out waiting for settings state")
            }
        }
}
