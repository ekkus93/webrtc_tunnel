package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.AppDispatchers
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val SETUP_COORDINATION_TEST_TIMEOUT_MS = 15_000L
private const val BASELINE_GATE_TIMEOUT_SECONDS = 5L

/**
 * Holds every task dispatched to [dispatchers] until [release] is called. This makes the
 * constructor-time `Initializing` contract deterministic: the test does not depend on whether
 * a real IO pool happens to finish before the assertion runs. The worker is daemonized and every
 * wait is bounded so a failed test cannot strand Gradle's test executor.
 */
private class GatedBaselineDispatchers : AutoCloseable {
    private val releaseLatch = CountDownLatch(1)
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "setup-baseline-gated-io").apply { isDaemon = true }
        }
    private val gatedExecutor =
        Executor { command ->
            executor.execute {
                check(releaseLatch.await(BASELINE_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "setup baseline IO gate was never released"
                }
                command.run()
            }
        }
    private val dispatcher = gatedExecutor.asCoroutineDispatcher()

    val dispatchers = AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher)

    fun release() {
        releaseLatch.countDown()
    }

    override fun close() {
        release()
        executor.shutdownNow()
    }
}

/**
 * FIX8 P1-001: the shared setup-local [SetupOperationCoordinator] — baseline load off the main
 * thread, load-state gating, overlap rejection deriving `isBusy` from real admission (not
 * independently toggled), and the cancellation-first/redacted-catch safety net every setup
 * action now routes through. Split out of [SetupViewModelTest] to stay under detekt's
 * `LargeClass` threshold.
 */
@RunWith(RobolectricTestRunner::class)
class SetupDraftOperationCoordinationTest : AppViewModelTestBase() {
    private fun realIoDeps(dispatchers: AppDispatchers = realIoTestDispatchers()): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { recordingBridge },
            configRepository = configRepository,
            networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
            identityRepository = deps.identityRepository,
            dispatchers = dispatchers,
        )

    private fun awaitSetupState(
        viewModel: SetupViewModel,
        predicate: (SetupWizardState) -> Boolean,
    ): SetupWizardState =
        awaitCondition(currentValue = { viewModel.state.value }, predicate = predicate, description = "setup state")

    private fun awaitLoadReady(viewModel: SetupViewModel) {
        awaitCondition(description = "setup load state Ready") { viewModel.loadState.value is SetupLoadState.Ready }
    }

    // setupViewModelConstructionPerformsNoFileIoOnMainThread
    @Test(timeout = SETUP_COORDINATION_TEST_TIMEOUT_MS)
    fun setupViewModelConstructionPerformsNoFileIoOnMainThread() {
        configRepository.saveSetupInput(
            SetupConfigInput(brokerHost = "saved-broker.local", remotePeerId = "saved-remote-peer"),
        )
        GatedBaselineDispatchers().use { gate ->
            val viewModel = SetupViewModel(realIoDeps(gate.dispatchers))

            // FIX8 P1-001-B: construction returns while the actual IO-dispatched baseline is
            // deliberately held behind a test-owned gate. This proves the constructor did not
            // perform the setup-input read synchronously on its caller thread without racing a
            // fast shared IO pool.
            assertEquals(SetupLoadState.Initializing, viewModel.loadState.value)

            gate.release()
            awaitLoadReady(viewModel)
            assertEquals("saved-broker.local", viewModel.state.value.input.brokerHost)
        }
    }

    // setupLoadInitializingBlocksNextAndSave
    @Test(timeout = SETUP_COORDINATION_TEST_TIMEOUT_MS)
    fun setupLoadInitializingBlocksNextAndSave() {
        GatedBaselineDispatchers().use { gate ->
            val viewModel = SetupViewModel(realIoDeps(gate.dispatchers))
            assertEquals(SetupLoadState.Initializing, viewModel.loadState.value)
            val stepBefore = viewModel.state.value.currentStep

            viewModel.goNext()

            assertEquals(
                "Next must be rejected while the baseline is still loading",
                stepBefore,
                viewModel.state.value.currentStep,
            )
            assertTrue(viewModel.state.value.errorMessage != null)
            gate.release()
            awaitLoadReady(viewModel)
        }
    }

    // setupLoadReadyUsesLoadedDraftBaseline
    @Test
    fun setupLoadReadyUsesLoadedDraftBaseline() {
        deps.configRepository.saveSetupInput(
            SetupConfigInput(brokerHost = "saved-broker.local", remotePeerId = "saved-remote-peer"),
        )

        val viewModel = SetupViewModel(deps)

        assertEquals(SetupLoadState.Ready, viewModel.loadState.value)
        assertEquals(null, viewModel.state.value.errorMessage)
        assertEquals("saved-broker.local", viewModel.state.value.input.brokerHost)
        assertEquals("saved-remote-peer", viewModel.state.value.input.remotePeerId)
    }

    // setupLoadFailureIsDurableAndDoesNotUseBlankFallback
    @Test
    fun setupLoadFailureIsDurableAndDoesNotUseBlankFallback() {
        val setupInputFile = File(app.filesDir, "setup_input.json")
        setupInputFile.parentFile?.mkdirs()
        setupInputFile.writeText("{ corrupt json")

        val viewModel = SetupViewModel(deps)

        assertTrue(viewModel.loadState.value is SetupLoadState.Failed)
        assertTrue(viewModel.state.value.errorMessage != null)
        // Must not silently present a blank draft the user could unknowingly save over the
        // actual (corrupt) saved one.
        assertEquals(SetupConfigInput().brokerHost, viewModel.state.value.input.brokerHost)
        assertEquals("", viewModel.state.value.input.remotePeerId)

        // Durable: Next remains blocked even well after the failed load settled (not just in
        // the brief Initializing window).
        val stepBefore = viewModel.state.value.currentStep
        viewModel.goNext()
        assertEquals(stepBefore, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage != null)
    }

    // FIX9 P0-001-G: real production-path test, not a direct runGuarded seam.
    @Test(timeout = SETUP_COORDINATION_TEST_TIMEOUT_MS)
    fun cancelDuringIdentityImportFromPathDoesNotPublishImportedIdentity() =
        runBlocking {
            val staleIdentityFile =
                File(app.filesDir, "stale_identity.toml").apply {
                    writeText("peer_id = \"stale-peer\"\nsecret = \"abc\"")
                }
            recordingBridge.privateIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    canonicalPrivateIdentity = "stale-private",
                    canonicalPublicIdentity = "stale-public",
                    peerId = "stale-peer",
                )
            recordingBridge.blockNextPrivateIdentityValidation()
            val viewModel = SetupViewModel(realIoDeps())
            awaitLoadReady(viewModel)
            viewModel.setImportIdentityPath(staleIdentityFile.absolutePath)

            viewModel.identity.importIdentityFromPath()
            awaitCondition(description = "private identity validation entered") {
                recordingBridge.privateIdentityValidationEnteredNow()
            }

            viewModel.cancel()
            recordingBridge.releaseBlockedPrivateIdentityValidation()
            awaitSetupState(viewModel) { !it.isBusy }

            val state = viewModel.state.value
            assertEquals("", state.localPublicIdentity)
            assertEquals(null, state.identityPeerId)
            assertEquals("", state.importIdentityPath)
            assertNull(
                "stale imported private identity must not remain in the draft",
                viewModel.identityDraft.copyForSave(),
            )
        }

    // overlappingIdentityAndForwardActionsCannotPublishStaleBusyOrState
    @Test(timeout = SETUP_COORDINATION_TEST_TIMEOUT_MS)
    fun overlappingIdentityAndForwardActionsCannotPublishStaleBusyOrState() =
        runBlocking {
            val viewModel = SetupViewModel(deps)
            awaitLoadReady(viewModel)

            val identityActionEntered = CompletableDeferred<Unit>()
            val releaseIdentityAction = CompletableDeferred<Unit>()
            val identityJob =
                viewModel.viewModelScope.launch {
                    viewModel.operations.runGuarded(viewModel.stateAccess, SetupDraftOperation.IdentityAction) {
                        identityActionEntered.complete(Unit)
                        releaseIdentityAction.await()
                    }
                }
            identityActionEntered.await()
            assertTrue(viewModel.state.value.isBusy)

            // FIX8 P1-001-A: attempt a forward edit through the SAME shared coordinator while
            // identity action still holds admission — driven directly (like the identity action
            // above) rather than through SetupForwardsController.upsertForward's own
            // viewModelScope-hopping launch, whose scheduling under Robolectric's real Main
            // dispatcher does not reliably interleave with an already-parked viewModelScope
            // coroutine in this test harness. The coordinator's admission/rejection/publish
            // logic exercised here is identical either way — every real controller routes
            // through the exact same WizardStateAccess.operations.runGuarded call.
            var forwardEditRan = false
            val forwardJob =
                viewModel.viewModelScope.launch {
                    viewModel.operations.runGuarded(viewModel.stateAccess, SetupDraftOperation.ForwardEdit) {
                        forwardEditRan = true
                    }
                }
            forwardJob.join()

            assertFalse("a busy rejection must never run the overlapping action's own body", forwardEditRan)
            val rejected = viewModel.state.value
            assertTrue(rejected.errorMessage?.contains("setup_draft_operation_busy") == true)
            assertTrue(rejected.errorMessage!!.contains("IdentityAction"))
            assertTrue(
                "the still-active identity action must keep isBusy true even after the " +
                    "rejected overlap's own state publish",
                rejected.isBusy,
            )

            releaseIdentityAction.complete(Unit)
            identityJob.join()

            // Now that the identity action has released admission, the same category's
            // operation succeeds normally through the coordinator, and isBusy correctly returns
            // to false once it completes.
            var secondForwardEditRan = false
            val secondForwardJob =
                viewModel.viewModelScope.launch {
                    viewModel.operations.runGuarded(viewModel.stateAccess, SetupDraftOperation.ForwardEdit) {
                        secondForwardEditRan = true
                    }
                }
            secondForwardJob.join()
            assertTrue("a non-overlapping action must actually run once admission is free", secondForwardEditRan)
            assertEquals(false, viewModel.state.value.isBusy)
            assertEquals(null, viewModel.operations.activeOperationForTest())
        }

    // setupActionExceptionIsRedactedAndDurable
    @Test
    fun setupActionExceptionIsRedactedAndDurable() =
        runBlocking {
            val viewModel = SetupViewModel(deps)
            awaitLoadReady(viewModel)
            val secretMessage = "password=hunter2 leaked from an unanticipated exception"

            viewModel.viewModelScope.launch {
                viewModel.operations.runGuarded(viewModel.stateAccess, SetupDraftOperation.IdentityAction) {
                    throw IllegalStateException(secretMessage)
                }
            }

            val state = awaitSetupState(viewModel) { it.errorMessage != null }
            // FIX8 P1-001-C: an exception the block does not itself handle must still clear
            // busy AND report a fixed/redacted, durable error — never merely clear busy silently.
            assertEquals(false, state.isBusy)
            assertFalse(
                "the raw secret-bearing message must never reach UI state",
                state.errorMessage!!.contains("hunter2"),
            )
            assertEquals(SensitiveDataRedactor.redactText(secretMessage), state.errorMessage)
        }

    // setupActionCancellationEmitsNoOrdinaryResultAndReleasesOwnership
    @Test(timeout = SETUP_COORDINATION_TEST_TIMEOUT_MS)
    fun setupActionCancellationEmitsNoOrdinaryResultAndReleasesOwnership() =
        runBlocking {
            val viewModel = SetupViewModel(deps)
            awaitLoadReady(viewModel)

            val entered = CompletableDeferred<Unit>()
            val job =
                viewModel.viewModelScope.launch {
                    viewModel.operations.runGuarded(viewModel.stateAccess, SetupDraftOperation.IdentityAction) {
                        entered.complete(Unit)
                        delay(10_000)
                    }
                }
            entered.await()
            assertTrue(viewModel.state.value.isBusy)

            job.cancel()
            job.join()

            // Cancellation must never be reported as an ordinary success/failure.
            assertNull(viewModel.state.value.errorMessage)
            assertNull(viewModel.state.value.saveResult)
            // Admission must be released — isBusy returns to false and a later action can run.
            assertEquals(false, viewModel.state.value.isBusy)
            assertEquals(null, viewModel.operations.activeOperationForTest())

            viewModel.identity.generateIdentity()
            val state = awaitSetupState(viewModel) { it.saveResult == "Identity generated" }
            assertEquals(false, state.isBusy)
        }
}
