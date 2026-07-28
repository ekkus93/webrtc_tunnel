package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** FIX9 production-path stale final-save tests. These do not call SetupOperationCoordinator
 * directly; they drive SetupSaveController through SetupViewModel and pause the native validation
 * bridge at the same boundary a real user-triggered save crosses before authoritative commit. */
@RunWith(RobolectricTestRunner::class)
class SetupStaleFinalSaveTest : AppViewModelTestBase() {
    private fun realIoDeps(): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { recordingBridge },
            configRepository = ConfigRepository(app),
            networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
            identityRepository = deps.identityRepository,
            dispatchers = realIoTestDispatchers(),
        )

    @Test
    fun cancelDuringFinalSaveValidationDoesNotPersistOrPublishSuccess() =
        runBlocking {
            recordingBridge.privateIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    message = null,
                    peerId = "android-phone",
                    canonicalPublicIdentity = "canon-pub",
                    canonicalPrivateIdentity = "canon-private",
                )
            recordingBridge.publicIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    message = null,
                    peerId = "remote-peer",
                    canonicalPublicIdentity = "remote-public",
                    canonicalPrivateIdentity = null,
                )
            recordingBridge.validationResult = ValidationResult(true, null)
            recordingBridge.blockNextValidateConfig()

            val deps = realIoDeps()
            deps.identityRepository.storeEncryptedIdentity("stored-private".toByteArray(), "stored-public")
            deps.forwardsStore.saveForwards(
                listOf(
                    ForwardConfig(
                        id = "svc",
                        name = "svc",
                        localPort = 8080,
                        remoteForwardId = "svc",
                        enabled = true,
                    ),
                ),
            )
            runBlocking { deps.forwardsRepository.refresh() }
            val viewModel = SetupViewModel(deps)
            awaitCondition(description = "setup load state Ready") { viewModel.loadState.value is SetupLoadState.Ready }
            viewModel.setImportPublicIdentity("remote-public")
            viewModel.setInput(
                viewModel.state.value.input.copy(
                    brokerHost = "broker.local",
                    remotePeerId = "remote-peer",
                ),
            )

            viewModel.save.saveAndApplyConfig()
            awaitCondition(description = "config validation entered") { recordingBridge.validateConfigEnteredNow() }

            viewModel.cancel()
            recordingBridge.releaseBlockedValidateConfig(ValidationResult(true, null))
            awaitCondition(description = "stale save settled") { !viewModel.state.value.isBusy }

            assertNull(viewModel.state.value.saveResult)
            assertNull(viewModel.state.value.errorMessage)
            assertFalse(
                "a stale save cancelled before commit must not write authoritative config",
                File(app.filesDir, "config.toml").exists(),
            )
            assertFalse(
                "a stale save cancelled before commit must not write setup_input.json",
                File(app.filesDir, "setup_input.json").exists(),
            )
            assertEquals(emptyList<ForwardConfig>(), viewModel.forwards.value)
        }
}
