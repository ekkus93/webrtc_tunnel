package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SetupViewModelTest : AppViewModelTestBase() {
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
}
