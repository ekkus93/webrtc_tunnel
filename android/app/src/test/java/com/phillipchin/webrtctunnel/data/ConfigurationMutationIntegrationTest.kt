package com.phillipchin.webrtctunnel.data

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModel
import com.phillipchin.webrtctunnel.viewmodel.ImportExportViewModel
import com.phillipchin.webrtctunnel.viewmodel.RecordingBridge
import com.phillipchin.webrtctunnel.viewmodel.SettingsViewModel
import com.phillipchin.webrtctunnel.viewmodel.SetupSaveController
import com.phillipchin.webrtctunnel.viewmodel.SetupStep
import com.phillipchin.webrtctunnel.viewmodel.SetupWizardState
import com.phillipchin.webrtctunnel.viewmodel.WizardStateAccess
import com.phillipchin.webrtctunnel.viewmodel.inlineTestDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * FIX7 P0-001-D integration tests: proves the *actual* SetupSaveController / ImportExportViewModel
 * / ForwardsViewModel / SettingsViewModel entry points share one [ConfigurationMutationCoordinator]
 * and cannot overlap (FIX7-INV-009) — not just the coordinator in isolation (see
 * [ConfigurationMutationCoordinatorTest] for that).
 *
 * The shared technique: [GatedConfigRepository] overrides the one method every one of the four
 * production flows eventually calls to commit — `writeConfigAtomically` — and blocks on a
 * [CompletableDeferred] until the test releases it. This lets a test deterministically prove "the
 * first operation is genuinely still running and holding admission" before asserting the second
 * is rejected, without any `Thread.sleep`/timing guess. Suspension on an unresolved
 * [CompletableDeferred] yields control back to the caller regardless of dispatcher, so inline
 * test dispatchers are enough — no real thread hop is needed, which also sidesteps Robolectric's
 * paused main-Looper semantics for `viewModelScope`-launched coroutines. [SetupSaveController] is
 * built with its own explicit `Dispatchers.IO` scope since it is constructed directly rather than
 * as a `ViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigurationMutationIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    private class GatedConfigRepository(
        context: Context,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
            entered.complete(Unit)
            release.await()
            return super.writeConfigAtomically(contents)
        }
    }

    private fun createDeps(configRepository: ConfigRepository): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { RecordingBridge() },
            configRepository = configRepository,
            networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
            identityRepository =
                IdentityRepository(
                    app,
                    object : IdentityCrypto {
                        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                        override fun decrypt(payload: ByteArray): ByteArray = payload
                    },
                ),
            dispatchers = inlineTestDispatchers(),
        )

    private class SetupHarness(
        val controller: SetupSaveController,
        val stateRef: AtomicReference<SetupWizardState>,
    )

    /** A hand-built Review-step state valid enough for [SetupSaveController.saveAndApplyConfig]
     * to reach its Config commit stage: matching stored identity, remote public identity, and
     * an enabled forward — the minimum `SetupStepValidation` requires, without driving the full
     * wizard UI navigation. */
    private fun buildValidSetupHarness(
        deps: AppDependencies,
        // detekt's InjectDispatcher: the real dispatcher only ever appears inside this default —
        // SetupSaveController is constructed directly (not as a ViewModel) and needs its own
        // genuine background scope so its suspension is observable from the test's coroutine.
        controllerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): SetupHarness {
        deps.identityRepository.storeEncryptedIdentity("private-bytes".toByteArray(), "pub-identity")
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        val stateRef =
            AtomicReference(
                SetupWizardState(
                    currentStep = SetupStep.Review,
                    input =
                        SetupConfigInput(
                            localPeerId = "android-phone",
                            brokerHost = "broker.local",
                            remotePeerId = "remote-peer",
                        ),
                    importPublicIdentity = "kid peer",
                ),
            )
        val forwardsRef =
            AtomicReference(
                listOf(
                    ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true),
                ),
            )
        val access =
            WizardStateAccess(
                state = { stateRef.get() },
                forwards = { forwardsRef.get() },
                applyState = { stateRef.set(it) },
                setForwards = { forwardsRef.set(it) },
            )
        val controller =
            SetupSaveController(
                deps = deps,
                scope = CoroutineScope(Job() + controllerDispatcher),
                loadPreferences = { deps.configRepository.preferences.first() },
                persistPreferences = { deps.configRepository.savePreferences(it) },
                access = access,
            )
        return SetupHarness(controller, stateRef)
    }

    // Also pumps the Robolectric main Looper each iteration (SetupSaveControllerTest.awaitState
    // does the same) as a defensive no-op belt-and-suspenders — harmless if nothing is queued.
    private suspend fun <T : Any> awaitNonNull(poll: () -> T?): T {
        var value = poll()
        while (value == null) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(5)
            value = poll()
        }
        return value
    }

    @Test
    fun setupSaveBlocksConcurrentConfigImportAndImportReportsBusyDurably() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val deps = createDeps(GatedConfigRepository(app, entered, release))
            val setup = buildValidSetupHarness(deps)
            val importExportViewModel = ImportExportViewModel(deps)

            setup.controller.saveAndApplyConfig()
            withTimeout(5_000) { entered.await() }
            assertEquals(
                ConfigurationOperation.SetupSave,
                deps.configurationMutationCoordinator.activeOperationForTest(),
            )

            importExportViewModel.importConfig()
            val failure = withTimeout(5_000) { awaitNonNull { importExportViewModel.state.value.lastOperationFailure } }
            assertEquals("configuration_operation_busy", failure.code)
            assertTrue(failure.message.contains("SetupSave"))

            release.complete(Unit)
            withTimeout(5_000) {
                awaitNonNull { setup.stateRef.get().saveResult ?: setup.stateRef.get().errorMessage }
            }
        }
    }

    @Test
    fun configImportBlocksConcurrentForwardMutationAndForwardReportsBusyDurably() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val deps = createDeps(GatedConfigRepository(app, entered, release))
            val importExportViewModel = ImportExportViewModel(deps)
            val forwardsViewModel = ForwardsViewModel(deps)

            val tempFile = File.createTempFile("import-config-src", ".toml")
            tempFile.writeText("# candidate config\n")
            importExportViewModel.updateState { it.copy(configImportPath = tempFile.absolutePath) }

            importExportViewModel.importConfig()
            withTimeout(5_000) { entered.await() }
            assertEquals(
                ConfigurationOperation.ConfigImport,
                deps.configurationMutationCoordinator.activeOperationForTest(),
            )

            forwardsViewModel.saveForward(
                ForwardConfig(id = "svc2", name = "svc2", localPort = 9090, remoteForwardId = "svc2", enabled = true),
            )
            val failure = withTimeout(5_000) { awaitNonNull { forwardsViewModel.lastOperationFailure.value } }
            assertEquals("configuration_operation_busy", failure.code)
            assertTrue(failure.message.contains("ConfigImport"))

            release.complete(Unit)
            tempFile.delete()
        }
    }

    @Test
    fun forwardActivationBlocksConcurrentResetAndResetReportsBusyDurably() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val deps = createDeps(GatedConfigRepository(app, entered, release))
            val forwardsViewModel = ForwardsViewModel(deps)
            val settingsViewModel = SettingsViewModel(deps)

            forwardsViewModel.saveForward(
                ForwardConfig(id = "svc3", name = "svc3", localPort = 9191, remoteForwardId = "svc3", enabled = true),
            )
            withTimeout(5_000) { entered.await() }
            assertEquals(
                ConfigurationOperation.ForwardMutation,
                deps.configurationMutationCoordinator.activeOperationForTest(),
            )

            settingsViewModel.resetConfiguration()
            val failure = withTimeout(5_000) { awaitNonNull { settingsViewModel.uiState.value.lastOperationFailure } }
            assertEquals("configuration_operation_busy", failure.code)
            assertTrue(failure.message.contains("ForwardMutation"))

            release.complete(Unit)
        }
    }

    @Test
    fun resetBlocksConcurrentSetupSaveAndSetupReportsBusyDurably() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val deps = createDeps(GatedConfigRepository(app, entered, release))
            val settingsViewModel = SettingsViewModel(deps)
            // Setup save is the "second" (blocked) party here, so it never has to reach a valid
            // state — Busy short-circuits before validateAndCommit runs at all.
            val setup = buildValidSetupHarness(deps)

            settingsViewModel.resetConfiguration()
            withTimeout(5_000) { entered.await() }
            assertEquals(
                ConfigurationOperation.ConfigurationReset,
                deps.configurationMutationCoordinator.activeOperationForTest(),
            )

            setup.controller.saveAndApplyConfig()
            val errorMessage = withTimeout(5_000) { awaitNonNull { setup.stateRef.get().errorMessage } }
            assertNotNull(errorMessage)
            assertTrue(errorMessage.contains("ConfigurationReset"))

            release.complete(Unit)
        }
    }

    @Test
    fun laterOperationUsesFreshStateAfterFirstOperationCompletes() {
        runBlocking {
            val deps = createDeps(ConfigRepository(app))
            val forwardsViewModel = ForwardsViewModel(deps)
            val settingsViewModel = SettingsViewModel(deps)

            forwardsViewModel.saveForward(
                ForwardConfig(
                    id = "fresh",
                    name = "fresh",
                    localPort = 7070,
                    remoteForwardId = "fresh",
                    enabled = true,
                ),
            )
            // `forwards.value` updates synchronously inside upsertWithReceipt, before the async
            // config regeneration/write that still holds admission — waiting on forward content
            // alone would race the mutation's own release. Wait for admission to actually clear.
            withTimeout(5_000) {
                awaitNonNull {
                    if (deps.configurationMutationCoordinator.activeOperationForTest() == null) Unit else null
                }
            }
            assertTrue(forwardsViewModel.forwards.value.any { it.id == "fresh" })

            settingsViewModel.resetConfiguration()
            withTimeout(5_000) {
                awaitNonNull { if (settingsViewModel.uiState.value.lastOperationFailure == null) Unit else null }
            }
            // Reset ran against the post-forward-save state (it observed and reset the "fresh"
            // forward), not a stale pre-forward snapshot — proven by the forward no longer
            // being present afterward.
            assertTrue(forwardsViewModel.forwards.value.none { it.id == "fresh" })
        }
    }
}
