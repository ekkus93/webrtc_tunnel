package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupFix9NativeBarrierCancellationTest : AppViewModelTestBase() {
    private fun realIoDeps(): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { recordingBridge },
            configRepository = ConfigRepository(app),
            networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
            identityRepository = deps.identityRepository,
            dispatchers = realIoTestDispatchers(),
        )

    private fun awaitReady(viewModel: SetupViewModel) {
        awaitCondition(description = "setup baseline Ready") {
            viewModel.loadState.value is SetupLoadState.Ready
        }
    }

    @Test
    fun cancelDuringGenerateIdentityDoesNotPublishGeneratedIdentity() =
        runBlocking {
            val generated =
                IdentityValidationResult(
                    valid = true,
                    canonicalPrivateIdentity = "generated-private",
                    canonicalPublicIdentity = "generated-public",
                    peerId = "generated-peer",
                )
            recordingBridge.generateIdentityResult = generated
            recordingBridge.blockNextGenerateIdentity()
            val viewModel = SetupViewModel(realIoDeps())
            awaitReady(viewModel)
            viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "generated-peer"))

            viewModel.identity.generateIdentity()
            awaitCondition(description = "identity generation entered") {
                recordingBridge.generateIdentityEnteredNow()
            }
            viewModel.cancel()
            recordingBridge.releaseBlockedGenerateIdentity(generated)
            awaitCondition(description = "identity generation settled") { !viewModel.state.value.isBusy }

            assertEquals("", viewModel.state.value.localPublicIdentity)
            assertNull(viewModel.state.value.identityPeerId)
            assertNull(viewModel.state.value.saveResult)
            assertNull(viewModel.identityDraft.copyForSave())
        }

    @Test
    fun cancelDuringNavigationValidationDoesNotAdvanceStep() =
        runBlocking {
            val testDeps = realIoDeps()
            testDeps.identityRepository.storeEncryptedIdentity("stored-private".toByteArray(), "stored-public")
            val viewModel = SetupViewModel(testDeps)
            awaitReady(viewModel)
            viewModel.goNext()
            awaitCondition { viewModel.state.value.currentStep == SetupStep.Identity }
            viewModel.goNext()
            awaitCondition { viewModel.state.value.currentStep == SetupStep.Broker }
            viewModel.setInput(viewModel.state.value.input.copy(brokerHost = "broker.local"))
            viewModel.goNext()
            awaitCondition { viewModel.state.value.currentStep == SetupStep.Peer }
            val remote =
                IdentityValidationResult(
                    valid = true,
                    canonicalPublicIdentity = "remote-public",
                    peerId = "remote-peer",
                )
            recordingBridge.publicIdentityValidationResult = remote
            recordingBridge.blockNextPublicIdentityValidation()
            viewModel.setImportPublicIdentity("remote-public")
            viewModel.setInput(viewModel.state.value.input.copy(remotePeerId = "remote-peer"))

            viewModel.goNext()
            awaitCondition(description = "navigation validation entered") {
                recordingBridge.publicIdentityValidationEnteredNow()
            }
            viewModel.cancel()
            recordingBridge.releaseBlockedPublicIdentityValidation(remote)
            awaitCondition(description = "navigation validation settled") { !viewModel.state.value.isBusy }

            assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        }
}
