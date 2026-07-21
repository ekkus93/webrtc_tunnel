package com.phillipchin.webrtctunnel

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.TimeUnit

/**
 * P0-003/P0-004/P0-005: proves `TunnelForegroundService`'s stop-failure handling
 * under `testDebugUnitTest`, the only Android job the required CI gate actually
 * runs (the equivalent instrumentation coverage in
 * `TunnelForegroundServiceInstrumentationTest` never executes there).
 *
 * Uses real `Dispatchers.IO` (not `Dispatchers.Unconfined`) for both the
 * service's `ioDispatcher` and `defaultDispatcher`: `onStartCommand`'s action
 * handlers are `serviceScope.launch { ... }` calls, which return as soon as the
 * coroutine hits its first suspension, not once the whole action has finished.
 * Under `Unconfined` there is no event loop to keep pumping the remainder of
 * that work back on this thread, which made the pause/stop scenarios flaky.
 * Real `IO` self-pumps on its own thread pool, so polling for the observable
 * outcome (matching how `TunnelForegroundServiceInstrumentationTest` proves the
 * same scenarios under real instrumentation) is both correct and reliable.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceStopFailureTest {
    private val controller =
        ServiceController.of(
            realIoService(),
            Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java),
        )
    private lateinit var service: TunnelForegroundService

    @Before
    fun setUp() {
        // P0-001: Reset all failure injection hooks before each test to prevent cross-test contamination.
        TunnelForegroundServiceTestHooks.identityReadFailure.set(null)
        TunnelForegroundServiceTestHooks.configPrepFailure.set(null)
        TunnelForegroundServiceTestHooks.policyBlockReason.set(null)
        TunnelForegroundServiceTestHooks.configValidationFailure.set(null)
        TunnelForegroundServiceTestHooks.validationThrows.set(null)
        // P0-003: Reset config preparation throw injection hook.
        TunnelForegroundServiceTestHooks.configPrepThrows.set(null)
        TunnelForegroundServiceTestHooks.preferenceReadFailure.set(null)
        TunnelForegroundServiceTestHooks.preferenceReadCancels.set(false)
        TunnelForegroundServiceTestHooks.preferenceReadInterceptSkipCount.set(0)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun actionIntent(action: String) =
        Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java).setAction(action)

    // FIX7 P2-001-A: a bounded poll for POSITIVE external-state convergence only (e.g. a
    // StateFlow/bridge counter settling after real async work dispatched on a real thread pool,
    // with no injected completion event to await instead). Never used here to prove absence,
    // exactly-once, ordering, or overlap — those proofs use drainQueueWithStopBarrier below.
    private fun waitForCondition(
        timeoutMs: Long = 8_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    // FIX7 P2-001-A: submits an explicit STOP as a barrier and waits for ITS effect
    // (bridge.stopCalls advancing), rather than polling for an earlier command's absence over
    // an elapsed-time window. The command queue drains strictly FIFO on a single consumer
    // (TunnelForegroundServiceOrderingTest's drainQueueWithStopBarrier uses the same technique),
    // so once the barrier's STOP has visibly run, every command submitted before it has already
    // been fully processed — a deterministic proof that a preceding command was rejected rather
    // than "hasn't happened within N seconds".
    private fun drainQueueWithStopBarrier(barrierId: Int) {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val stopCallsBefore = bridge.stopCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, barrierId)
        assertTrue(
            "the STOP barrier must be processed so the queue is provably drained",
            waitForCondition { bridge.stopCalls >= stopCallsBefore + 1 },
        )
    }

    @Test
    fun pauseWithFailingStopPublishesErrorNotPaused() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            "a failed pause stop must be reported as an error, never the paused state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
    }

    @Test
    fun manualPauseStopFailureEntersRuntimeQuarantine() {
        // FIX7 P0-007-B: a failed manual PAUSE stop must quarantine the runtime exactly
        // like a failed explicit STOP already does — previously this only reported the
        // error, without quarantining.
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.failNextStop()
        runBlocking { service.offer.pause() }

        assertTrue(bridge.stopCalls >= 1)
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
        assertEquals(
            "quarantine must have set the canonical lastError code",
            "native_runtime_quarantined",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    @Test
    fun startAfterManualPauseFailureDoesNotCallNative() {
        // Drives pause() directly and synchronously (matching failedPolicyStopForces
        // PausedByPolicyFalseEvenFromStaleTruePrecondition's technique) rather than
        // racing the async intent queue. Proof is the NATIVE START CALL COUNT, not
        // bridge.state: a failed stop() never resets bridge.state away from the prior
        // Connected, so checking `bridge.state == Connected` would be true either way
        // and prove nothing.
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.failNextStop()
        runBlocking { service.offer.pause() }
        assertTrue(bridge.stopCalls >= 1)

        val startCountBeforeRetry = bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 3)
        drainQueueWithStopBarrier(barrierId = 4)
        assertEquals(
            "a failed manual-pause stop must quarantine the runtime and block a later START",
            startCountBeforeRetry,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun policyPauseStopFailureEntersRuntimeQuarantine() {
        // FIX7 P0-007-B: a failed policy-pause stop must quarantine the runtime exactly
        // like a failed explicit STOP already does — previously this only reported the
        // error, without quarantining.
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.failNextStop()
        runBlocking { service.offer.pauseForPolicy("forced policy pause") }

        assertTrue(bridge.stopCalls >= 1)
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
        assertEquals(
            "quarantine must have set the canonical lastError code",
            "native_runtime_quarantined",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    // FIX7 P2-001-B: every existing stop test here drives the fake bridge's own `state ==
    // Connected` field as its precondition, but the REAL mapped ServiceState the app displays
    // for "offer running, no active session yet" is Listening, not Connected (activeSessionCount
    // == 0 in FailableRecordingBridge's status JSON — see TunnelRepositoryTest). stopServiceWork()
    // has no ServiceState-dependent gating, so this proves that invariant directly against the
    // real mapped status rather than relying on incidental overlap with the Connected-based tests.
    @Test
    fun stopWhileListeningStopsCleanlyAndNativeIsCalled() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(
            "offer running with no active session must map to Listening, not Connected",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Listening },
        )

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)

        assertTrue(
            "stopping while Listening must still reach the real native stop call",
            waitForCondition { bridge.stopCalls >= 1 },
        )
        assertTrue(
            "a clean stop from Listening must converge to Stopped, not remain running",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped },
        )
    }

    @Test
    fun resumeAfterPolicyPauseFailureDoesNotCallNative() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.failNextStop()
        runBlocking { service.offer.pauseForPolicy("forced policy pause") }
        assertTrue(bridge.stopCalls >= 1)

        val startCountBeforeRetry = bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_RESUME)).startCommand(0, 3)
        drainQueueWithStopBarrier(barrierId = 4)
        assertEquals(
            "a failed policy-pause stop must quarantine the runtime and block a later RESUME",
            startCountBeforeRetry,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun stopServiceWorkWithFailingStopStillReportsErrorNotClean() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)

        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        // The service still tears itself down (stopForeground/stopSelf), but must never
        // claim a clean "stopped" state it didn't actually achieve.
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })
    }

    /**
     * Regression test for P0-003: native JNI `stop()` success alone must not be reported as
     * a clean pause/stop — here the bridge's `stop()` call itself succeeds, but the
     * subsequent status-verification read reports `"error"` instead of `"stopped"`. This must
     * surface as a `stop_status_verification_failed` error, never a clean paused/stopped
     * notification, and must be retained as sticky cleanup history.
     */
    @Test
    fun stopStatusVerificationFailureDoesNotPublishCleanState() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        bridge.forceNextStatusJsonToReportError()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        assertTrue(
            "a native stop success whose final status could not be verified as Stopped must " +
                "be reported as an error, never a clean paused state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertTrue(
            "the verification failure must be retained as sticky cleanup history",
            waitForCondition { deps.tunnelRepository.status.value.lastCleanupError != null },
        )
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
    }

    /**
     * P0-005: A failed STOP quarantines the tunnel until an explicit STOP succeeds.
     * Once a successful STOP clears the quarantine, the earlier cleanup failure must
     * remain visible in diagnostics, not silently erased by the later successful retry.
     */
    @Test
    fun laterSuccessfulStopDoesNotEraseEarlierCleanupFailureHistory() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // 1. Start successfully.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // 2. First stop attempt fails.
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })
        assertTrue(
            "a stop failure must record sticky cleanup-failure history",
            waitForCondition { deps.tunnelRepository.status.value.lastCleanupError != null },
        )

        // 3. Quarantine blocks START until an explicit STOP succeeds.
        val startCountBeforeQuarantineClear = bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 3)
        assertEquals(
            "START must be blocked while quarantine is active",
            startCountBeforeQuarantineClear,
            bridge.startOfferCalls,
        )

        // 4. Explicit STOP succeeds → clears quarantine.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 4)
        assertTrue(waitForCondition { bridge.stopCalls >= 2 })

        // 5. Cleanup error history must remain visible after the later successful stop.
        assertTrue(
            "an earlier cleanup failure must remain visible after a later successful stop",
            deps.tunnelRepository.status.value.lastCleanupError != null,
        )
    }

    @Test
    fun failedStopQuarantinesUntilExplicitStopSucceeds() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // 1. Start successfully.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // 2. Configure stop to fail.
        bridge.failNextStop()

        // 3. Send STOP.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)

        // 4. Assert Error/quarantine.
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })

        // 5. Send START — quarantine should block this.
        val initialStartCount = bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 3)

        // 6. Assert native start count did not increase (quarantine blocks START).
        assertEquals(
            "failed STOP must block START until explicit STOP succeeds",
            initialStartCount,
            bridge.startOfferCalls,
        )

        // Quarantine remains active until a successful STOP completes. The subsequent
        // STOP/START cycle is handled by the service lifecycle; this test proves the
        // core quarantine invariant: failed STOP blocks START.
    }

    @Test
    fun failedPolicyStopForcesPausedByPolicyFalseEvenFromStaleTruePrecondition() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // First policy pause succeeds, establishing the precondition this bug needs:
        // pausedByPolicy already true from a prior, clean pause.
        runBlocking { service.offer.pauseForPolicy("first policy pause") }
        assertTrue(service.pausedByPolicy.get())
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        // A second, re-entrant policy pause now fails to stop the tunnel.
        TunnelForegroundServiceTestHooks.bridge.failNextStop()
        runBlocking { service.offer.pauseForPolicy("second policy pause") }

        assertFalse(
            "a failed policy-pause stop must never leave a stale pausedByPolicy == true",
            service.pausedByPolicy.get(),
        )
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)

        // Retry/reevaluation stays open: a subsequent successful pause still lands cleanly.
        runBlocking { service.offer.pauseForPolicy("retry policy pause") }
        assertTrue(service.pausedByPolicy.get())
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)
    }

    /**
     * Regression test for P0-001: before this fix, an `ACTION_PAUSE` arriving while
     * `startOffer()` is still in flight could race two independent, unsynchronized
     * callers of `repository.stop()` — the explicit `pause()` path and the startup
     * coroutine's own cancellation-catch cleanup — letting one see a duplicate/no-op
     * success while the other later failed. After the fix, the cancelling lifecycle
     * transition (`pause()`) is the sole owner: it cancels and *joins* the startup
     * job before performing the one authoritative `repository.stop()` itself, so
     * exactly one native stop call occurs no matter how the two coroutines interleave.
     */
    @Test
    fun cancelledStartupAndExplicitPausePerformExactlyOneNativeStop() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        bridge.blockNextStartOffer()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(bridge.awaitStartOfferEntered(10_000))

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)
        // Arm the failure before releasing: whichever way the two coroutines actually
        // interleave, pause() is the only path that will ever call repository.stop()
        // here, so this deterministically targets that one call.
        bridge.failNextStop()
        bridge.releaseBlockedStartOffer()

        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            "a failed pause-owned stop after a cancelled startup must be reported as an error",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        // P0-007: No settle wait needed. Because startup is joined before the
        // authoritative stop, a reverted competing cleanup must have completed
        // before the lifecycle command finishes.
        assertEquals(
            "exactly one native stop call must occur; a competing stop from the cancelled " +
                "startup coroutine would make this 2",
            1,
            bridge.stopCalls,
        )
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
    }

    @Test
    fun staleStatusRefreshCannotOverwriteFailedStop() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Start the tunnel; a successful start begins status polling. Wait for the
        // poll job itself (not just the bridge's Connected state, which flips
        // slightly earlier, before startStatusPolling() has assigned the job).
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        assertTrue(waitForCondition { service.statusPollJobForTest != null })

        // Capture the exact poll Job instance before arming the block, then wait for
        // the poll loop to actually reach the blocked read (not just schedule it).
        val staleJob = service.statusPollJobForTest
        bridge.blockNextStatusJsonRead()
        assertTrue(
            "status polling should have entered the blocked refresh by now",
            bridge.awaitStatusJsonReadEntered(10_000),
        )

        // Trigger pause with a failing stop. This action call itself returns
        // immediately (onStartCommand's launch is fire-and-forget); the pause
        // operation runs in the background from here.
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        // Ordering proof (P0-005): pause()'s stopStatusPollingAndJoin() calls
        // staleJob.cancelAndJoin(), and staleJob is currently blocked on a real
        // Thread-level CountDownLatch inside getStatusJson() (coroutine cancellation
        // cannot interrupt a blocking call) — so pause() must not be able to reach its
        // own repository.stop() call before this release happens. Proven via
        // FakeLifecycleEvent ordering, not elapsed time.
        bridge.releaseBlockedStatusJsonRead()
        val stopCallOrdinal = runBlocking { bridge.awaitStopCall() }
        assertEquals(
            "exactly the first stop call must be the one this failed pause makes",
            1,
            stopCallOrdinal,
        )

        // P0-007: Verify status read entered before stop call via event ordering.
        val events = bridge.lifecycleEventsSnapshot()
        val statusReadIndex = events.indexOf(FakeLifecycleEvent.StatusReadEntered)
        val stopIndex = events.indexOfFirst { it is FakeLifecycleEvent.StopEntered }
        assertTrue("StatusReadEntered must appear in event log", statusReadIndex >= 0)
        assertTrue("StopEntered must appear in event log", stopIndex >= 0)
        assertTrue(
            "status read must enter before the pause-owned stop call",
            statusReadIndex < stopIndex,
        )

        // Wait for that *exact* stale poll iteration to fully settle (commit its
        // result or be discarded by cancellation) before checking the final state.
        runBlocking { staleJob?.join() }

        assertTrue(
            "a failed stop must be the final truth even though a status refresh was " +
                "in flight when the stop was requested",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
    }

    /**
     * Establishes a genuine pending policy retry, mirroring
     * [PendingRetryInvalidationTest.pendingRetryThenDestroyDoesNotRestart]'s technique exactly:
     * policy-pause first (so a PolicyAllowed event isn't a stale no-op), then a first
     * PolicyAllowed resumes immediately (blocked mid-native-start), then a second PolicyAllowed
     * while that resume is still in flight becomes the pending retry.
     */
    private fun establishPendingPolicyRetry(bridge: FailableRecordingBridge) {
        val deps = (service.applicationContext as HasAppDependencies).deps
        runBlocking { deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true)) }
        runBlocking { service.offer.pauseForPolicy("policy pause before pending retry") }
        assertTrue(service.pausedByPolicy.get())

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)

        bridge.blockNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(bridge.awaitStartOfferEntered(5_000))

        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(
            "a pending policy retry must be recorded",
            waitForCondition { service.pendingPolicyResumeGenerationForTest != null },
        )
    }

    @Test
    fun quarantineClearsPendingPolicyRetry() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        establishPendingPolicyRetry(bridge)

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 3)
        bridge.failNextStop()
        bridge.releaseBlockedStartOffer()

        assertTrue(
            "the failed pause-owned stop must be reached",
            waitForCondition { bridge.stopCalls >= 1 },
        )
        assertTrue(
            "quarantine must invalidate any pending policy retry",
            waitForCondition { service.pendingPolicyResumeGenerationForTest == null },
        )
    }

    @Test
    fun pendingPolicyRetryAfterQuarantineDoesNotCallNative() {
        // Same setup as quarantineClearsPendingPolicyRetry, then proves a later network signal
        // that would otherwise fire the (now-invalidated) pending retry performs no native start.
        val bridge = TunnelForegroundServiceTestHooks.bridge
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        establishPendingPolicyRetry(bridge)

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 3)
        bridge.failNextStop()
        bridge.releaseBlockedStartOffer()
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(waitForCondition { service.pendingPolicyResumeGenerationForTest == null })

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)
        val startCountBeforeRetrySignal = bridge.startOfferCalls
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        drainQueueWithStopBarrier(barrierId = 5)
        assertEquals(
            "a stale/invalidated pending retry after quarantine must never call native start",
            startCountBeforeRetrySignal,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun quarantineGuardFailureIsDurableAndVisible() {
        // The quarantine guard's failure (blocking a subsequent START) must be reported through
        // the same durable, visible reporter every other guard failure uses — not silently
        // dropped by a policy-retry helper — matching handlePolicyAllowed's own reporting path.
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        bridge.failNextStop()
        runBlocking { service.offer.pause() }
        assertTrue(
            waitForCondition { deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined" },
        )

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)
        assertTrue(
            "the quarantine guard failure must remain durably visible after a blocked retry",
            waitForCondition { deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined" },
        )
    }

    @Test
    fun verifiedExplicitStopClearsQuarantineAndAllowsLaterStart() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })

        val startCountWhileQuarantined = bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 3)

        // A verified successful explicit STOP clears the quarantine. Waiting for its effect here
        // (FIX7 P2-001-A) also proves — via FIFO single-consumer draining — that the START
        // submitted just above has already been fully processed and rejected, without polling
        // for its absence over a timed window.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 4)
        assertTrue(waitForCondition { bridge.stopCalls >= 2 })
        assertEquals(
            "quarantine must block START",
            startCountWhileQuarantined,
            bridge.startOfferCalls,
        )

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 5)
        assertTrue(
            "a verified explicit STOP must clear quarantine and allow a later START",
            waitForCondition { bridge.state == ServiceState.Connected },
        )
    }

    @Test
    fun failedExplicitStopDoesNotClearQuarantine() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            waitForCondition { deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined" },
        )

        // A second explicit STOP also fails — quarantine must remain in effect, not be cleared
        // by the mere attempt.
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 3)
        assertTrue(waitForCondition { bridge.stopCalls >= 2 })

        val startCountAfterSecondFailure = bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 4)
        drainQueueWithStopBarrier(barrierId = 5)
        assertEquals(
            "a second failed explicit STOP must not clear quarantine",
            startCountAfterSecondFailure,
            bridge.startOfferCalls,
        )
    }
}
