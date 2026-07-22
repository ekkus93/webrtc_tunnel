package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

/**
 * FIX8 P1-001: the shared setup-local [SetupOperationCoordinator] — baseline load off the main
 * thread, load-state gating, overlap rejection deriving `isBusy` from real admission (not
 * independently toggled), and the cancellation-first/redacted-catch safety net every setup
 * action now routes through. Split out of [SetupViewModelTest] to stay under detekt's
 * `LargeClass` threshold.
 */
@RunWith(RobolectricTestRunner::class)
class SetupDraftOperationCoordinationTest : AppViewModelTestBase() {
    private fun realIoDeps(): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { recordingBridge },
            configRepository = configRepository,
            networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
            identityRepository = deps.identityRepository,
            dispatchers = realIoTestDispatchers(),
        )

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

    private fun awaitLoadReady(viewModel: SetupViewModel) {
        runBlocking {
            withTimeout(5_000) {
                while (viewModel.loadState.value !is SetupLoadState.Ready) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }

    // setupViewModelConstructionPerformsNoFileIoOnMainThread
    @Test
    fun setupViewModelConstructionPerformsNoFileIoOnMainThread() {
        configRepository.saveSetupInput(
            SetupConfigInput(brokerHost = "saved-broker.local", remotePeerId = "saved-remote-peer"),
        )
        val viewModel = SetupViewModel(realIoDeps())

        // FIX8 P1-001-B: the setup-input read/decode is genuinely IO-dispatched — construction
        // itself must return before that real thread hop completes, proven here by observing
        // the load is still Initializing at the instant the constructor returns (no idling yet).
        assertEquals(SetupLoadState.Initializing, viewModel.loadState.value)

        awaitLoadReady(viewModel)
        assertEquals("saved-broker.local", viewModel.state.value.input.brokerHost)
    }

    // setupLoadInitializingBlocksNextAndSave
    @Test
    fun setupLoadInitializingBlocksNextAndSave() {
        val viewModel = SetupViewModel(realIoDeps())
        assertEquals(SetupLoadState.Initializing, viewModel.loadState.value)
        val stepBefore = viewModel.state.value.currentStep

        viewModel.goNext()

        assertEquals(
            "Next must be rejected while the baseline is still loading",
            stepBefore,
            viewModel.state.value.currentStep,
        )
        assertTrue(viewModel.state.value.errorMessage != null)
        awaitLoadReady(viewModel)
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

    // overlappingIdentityAndForwardActionsCannotPublishStaleBusyOrState
    @Test
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
    @Test
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
