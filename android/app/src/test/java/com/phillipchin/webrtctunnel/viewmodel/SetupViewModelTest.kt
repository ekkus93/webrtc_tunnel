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

    // --- Step navigation ---

    @Test
    fun goNextFromModeAdvancesToIdentity() {
        val viewModel = SetupViewModel(deps)
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        viewModel.goNext()
        assertEquals(SetupStep.Identity, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun goBackAtFirstStepIsNoOp() {
        val viewModel = SetupViewModel(deps)
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        viewModel.goBack()
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
    }

    @Test
    fun goBackReturnsToPreviousStepAndClearsError() {
        val viewModel = SetupViewModel(deps)
        viewModel.goNext() // Mode -> Identity
        viewModel.goNext() // blocked at Identity (no identity), error set
        assertEquals(SetupStep.Identity, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage != null)

        viewModel.goBack()
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun identityStepBlocksAdvanceWithoutIdentity() {
        val viewModel = SetupViewModel(deps)
        viewModel.goNext() // Mode -> Identity
        viewModel.goNext() // attempt Identity -> Broker
        assertEquals(SetupStep.Identity, viewModel.state.value.currentStep)
        assertTrue(
            viewModel.state.value.errorMessage?.contains("Import or generate a private identity") == true,
        )
    }

    @Test
    fun peerStepBlocksAdvanceWhenRemotePeerIdBlank() {
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(navIdentityFile("peer_block").absolutePath)
        viewModel.setInput(viewModel.state.value.input.copy(brokerHost = "broker.local", remotePeerId = ""))
        advanceTo(viewModel, SetupStep.Peer)
        assertEquals(SetupStep.Peer, viewModel.state.value.currentStep)

        viewModel.goNext() // attempt Peer -> Forwards
        assertEquals(SetupStep.Peer, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage?.contains("Remote peer id is required") == true)
    }

    @Test
    fun forwardsStepBlocksAdvanceWhenNoEnabledForward() {
        val viewModel = newValidViewModel("forwards_block")
        deps.forwardsStore.saveForwards(emptyList()) // clear the seeded default forward
        advanceTo(viewModel, SetupStep.Forwards)
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)

        viewModel.goNext() // attempt Forwards -> NetworkPolicy with no forwards saved
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage?.contains("Enable at least one forward") == true)
    }

    @Test
    fun forwardsStepCannotAdvanceUntilAForwardIsEnabled() {
        val viewModel = newValidViewModel("forwards_canadvance")
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = false)),
        )
        viewModel.forwardsEditor.refreshForwards()
        advanceTo(viewModel, SetupStep.Forwards)
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)
        // canAdvance must mirror save-time validation: disabled-only forwards block the step.
        assertTrue(!viewModel.canAdvanceFromCurrentStep())

        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        viewModel.forwardsEditor.refreshForwards()
        assertTrue(viewModel.canAdvanceFromCurrentStep())
    }

    @Test
    fun goNextClearsPreviousErrorOnSuccessfulAdvance() {
        val viewModel = newValidViewModel("clear_error")
        deps.forwardsStore.saveForwards(emptyList()) // clear the seeded default forward
        advanceTo(viewModel, SetupStep.Forwards)
        viewModel.goNext() // blocked: no enabled forward
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage != null)

        // Fixing the underlying condition outside the wizard setters must not clear the error on its own.
        saveEnabledForward()
        assertTrue(viewModel.state.value.errorMessage != null)

        viewModel.goNext() // now passes
        assertEquals(SetupStep.NetworkPolicy, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun goNextOnReviewStepIsNoOp() {
        val viewModel = newValidViewModel("review_noop")
        saveEnabledForward()
        advanceTo(viewModel, SetupStep.Review)
        assertEquals(SetupStep.Review, viewModel.state.value.currentStep)

        viewModel.goNext()
        assertEquals(SetupStep.Review, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun sequentialGoNextVisitsEveryStepInOrder() {
        val viewModel = newValidViewModel("sequence")
        saveEnabledForward()

        val visited = mutableListOf(viewModel.state.value.currentStep)
        repeat(SetupStep.entries.size - 1) {
            viewModel.goNext()
            visited.add(viewModel.state.value.currentStep)
        }

        assertEquals(
            listOf(
                SetupStep.Mode,
                SetupStep.Identity,
                SetupStep.Broker,
                SetupStep.Peer,
                SetupStep.Forwards,
                SetupStep.NetworkPolicy,
                SetupStep.Review,
            ),
            visited,
        )
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun goBackFromReviewWalksBackToMode() {
        val viewModel = newValidViewModel("back_sequence")
        saveEnabledForward()
        advanceTo(viewModel, SetupStep.Review)
        assertEquals(SetupStep.Review, viewModel.state.value.currentStep)

        val visited = mutableListOf(viewModel.state.value.currentStep)
        repeat(SetupStep.entries.size - 1) {
            viewModel.goBack()
            visited.add(viewModel.state.value.currentStep)
        }

        assertEquals(
            listOf(
                SetupStep.Review,
                SetupStep.NetworkPolicy,
                SetupStep.Forwards,
                SetupStep.Peer,
                SetupStep.Broker,
                SetupStep.Identity,
                SetupStep.Mode,
            ),
            visited,
        )
    }

    private fun navIdentityFile(tag: String): File =
        File(app.filesDir, "nav_identity_$tag.toml").apply {
            writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
        }

    private fun newValidViewModel(tag: String): SetupViewModel {
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(navIdentityFile(tag).absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        return viewModel
    }

    private fun saveEnabledForward() {
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
    }

    private fun advanceTo(
        viewModel: SetupViewModel,
        target: SetupStep,
    ) {
        var guard = 0
        while (viewModel.state.value.currentStep != target && guard < SetupStep.entries.size) {
            val before = viewModel.state.value.currentStep
            viewModel.goNext()
            if (viewModel.state.value.currentStep == before) break // blocked by validation
            guard += 1
        }
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
