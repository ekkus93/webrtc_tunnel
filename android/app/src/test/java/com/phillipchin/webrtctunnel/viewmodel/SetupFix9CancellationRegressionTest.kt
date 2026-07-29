package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SetupFix9CancellationRegressionTest : AppViewModelTestBase() {
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

    private fun awaitSettled(viewModel: SetupViewModel) {
        awaitCondition(description = "setup operation settled") { !viewModel.state.value.isBusy }
    }

    @Test
    fun cancelDuringIdentityImportFromUriDoesNotPublishImportedIdentity() =
        runBlocking {
            val source = File(app.filesDir, "stale-uri-identity.toml").apply { writeText("private") }
            val result = validIdentity("uri-peer")
            recordingBridge.privateIdentityValidationResult = result
            recordingBridge.blockNextPrivateIdentityValidation()
            val viewModel = SetupViewModel(realIoDeps())
            awaitReady(viewModel)

            viewModel.identity.importIdentityFromUri(Uri.fromFile(source))
            awaitCondition(description = "URI identity validation entered") {
                recordingBridge.privateIdentityValidationEnteredNow()
            }
            viewModel.cancel()
            recordingBridge.releaseBlockedPrivateIdentityValidation(result)
            awaitSettled(viewModel)

            assertEquals("", viewModel.state.value.localPublicIdentity)
            assertNull(viewModel.state.value.identityPeerId)
            assertNull(viewModel.identityDraft.copyForSave())
        }

    @Test
    fun cancelDuringForwardUpsertDoesNotPublishDraftChange() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val viewModel =
                SetupViewModel(
                    realIoDeps(),
                    inspectForwardDraft = {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        null
                    },
                )
            awaitReady(viewModel)

            viewModel.forwardsEditor.upsertForward(validForward())
            entered.await()
            viewModel.cancel()
            release.complete(Unit)
            awaitSettled(viewModel)

            assertTrue(viewModel.forwards.value.isEmpty())
            assertNull(viewModel.state.value.saveResult)
        }

    @Test
    fun cancelDuringForwardDeleteDoesNotPublishDraftChange() =
        runBlocking {
            val testDeps = realIoDeps()
            val existing = validForward()
            testDeps.forwardsStore.saveForwards(listOf(existing))
            testDeps.forwardsRepository.refresh()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val viewModel =
                SetupViewModel(
                    testDeps,
                    inspectForwardDraft = {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        null
                    },
                )
            awaitReady(viewModel)

            viewModel.forwardsEditor.deleteForward(existing.id)
            entered.await()
            viewModel.cancel()
            release.complete(Unit)
            awaitSettled(viewModel)
            awaitCondition(description = "authoritative forwards restored") {
                viewModel.forwards.value == listOf(existing)
            }

            assertEquals(listOf(existing), viewModel.forwards.value)
            assertNull(viewModel.state.value.saveResult)
        }

    @Test
    fun cancelDuringFinalSaveRollsBackAuthoritativeStagesAndCancelsJob() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val writes = AtomicInteger(0)
            val viewModel =
                prepareFinalSaveViewModel { _: AndroidAppPreferences ->
                    if (writes.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } catch (cancelled: CancellationException) {
                            cancellationObserved.complete(Unit)
                            throw cancelled
                        }
                    }
                    Result.success(Unit)
                }

            viewModel.save.saveAndApplyConfig()
            entered.await()
            assertTrue(File(app.filesDir, "setup_input.json").exists())
            viewModel.cancel()
            cancellationObserved.await()
            awaitSettled(viewModel)

            assertFalse(File(app.filesDir, "config.toml").exists())
            assertFalse(File(app.filesDir, "setup_input.json").exists())
            assertFalse(File(app.filesDir, "authorized_keys").exists())
            assertFalse(File(app.filesDir, "runtime/mqtt_password.txt").exists())
            assertEquals(null, viewModel.operations.activeOperationForTest())
            assertNull(viewModel.state.value.saveResult)
            assertNull(viewModel.state.value.errorMessage)
        }

    @Test
    fun cancelDuringStartTunnelFromReviewDoesNotStartForegroundService() =
        runBlocking {
            val viewModel = prepareFinalSaveViewModel()
            recordingBridge.blockNextValidateConfig()

            viewModel.save.startTunnelFromReview()
            awaitCondition(description = "start review validation entered") {
                recordingBridge.validateConfigEnteredNow()
            }
            viewModel.cancel()
            recordingBridge.releaseBlockedValidateConfig(ValidationResult(true, null))
            awaitSettled(viewModel)

            assertEquals(null, Shadows.shadowOf(app).nextStartedService)
            assertNull(viewModel.state.value.saveResult)
        }

    @Test
    fun staleFinalSaveCannotClearNewerSetupError() =
        runBlocking {
            val viewModel = prepareFinalSaveViewModel()
            recordingBridge.blockNextValidateConfig()

            viewModel.save.saveAndApplyConfig()
            awaitCondition(description = "stale final validation entered") {
                recordingBridge.validateConfigEnteredNow()
            }
            viewModel.cancel()
            viewModel.stateAccess.applyState(
                viewModel.state.value.copy(errorMessage = "newer setup error", saveResult = null),
            )
            recordingBridge.releaseBlockedValidateConfig(ValidationResult(true, null))
            awaitSettled(viewModel)

            assertEquals("newer setup error", viewModel.state.value.errorMessage)
            assertNull(viewModel.state.value.saveResult)
        }

    private suspend fun prepareFinalSaveViewModel(
        persistPreferences: suspend (AndroidAppPreferences) -> Result<Unit> = {
            deps.configRepository.savePreferences(it)
        },
    ): SetupViewModel {
        val testDeps = realIoDeps()
        testDeps.identityRepository.storeEncryptedIdentity("stored-private".toByteArray(), "stored-public")
        testDeps.forwardsStore.saveForwards(listOf(validForward()))
        testDeps.forwardsRepository.refresh()
        recordingBridge.publicIdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = "remote-public",
                peerId = "remote-peer",
            )
        recordingBridge.validationResult = ValidationResult(true, null)
        val viewModel = SetupViewModel(testDeps, persistPreferences = persistPreferences)
        awaitReady(viewModel)
        viewModel.setImportPublicIdentity("remote-public")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = "android-phone",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        return viewModel
    }

    private fun validIdentity(peerId: String) =
        IdentityValidationResult(
            valid = true,
            canonicalPrivateIdentity = "canonical-private",
            canonicalPublicIdentity = "canonical-public",
            peerId = peerId,
        )

    private fun validForward() =
        ForwardConfig(
            id = "svc",
            name = "svc",
            localPort = 8080,
            remoteForwardId = "svc",
            enabled = true,
        )
}
