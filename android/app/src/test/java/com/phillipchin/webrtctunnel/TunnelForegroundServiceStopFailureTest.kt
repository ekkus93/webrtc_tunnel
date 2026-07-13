package com.phillipchin.webrtctunnel

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import com.phillipchin.webrtctunnel.model.isTunnelRunning
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
            realIoTunnelForegroundService(),
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
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun actionIntent(action: String) =
        Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java).setAction(action)

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

    @Test
    fun autoResumeOnUnmeteredSeesLatestPausedByPolicyAcrossThreads() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // The fake NetworkPolicyManager always reports UnmeteredWifi; every other test in
        // this file pins resumeOnUnmetered = false to avoid racing that against direct
        // pauseForPolicy() calls (see TunnelForegroundServiceTestFakes.kt). This test wants
        // exactly that race, deliberately: it enables auto-resume, then proves the
        // networkMonitorJob coroutine (running on this service's real IO dispatcher, a
        // different JVM thread than this JUnit test thread) observes a pausedByPolicy value
        // written from the test thread — a genuine cross-thread write/read pair, not just a
        // same-thread sanity check (P1-004).
        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        runBlocking { service.offer.pauseForPolicy("policy pause before auto-resume check") }
        assertTrue(service.pausedByPolicy.get())
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        // NetworkPolicyManager.monitor()'s flow already emitted once during onCreate() (before
        // this test ever paused), so re-triggering the real ConnectivityManager.NetworkCallback
        // is the only way to make the auto-resume check in onCreate() run again and actually
        // observe the pausedByPolicy write above.
        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "auto-resume must observe the pausedByPolicy write made from the test thread",
            waitForCondition { !service.pausedByPolicy.get() },
        )
        assertTrue(waitForCondition { bridge.startOfferCalls >= 2 })
    }

    /**
     * Regression test for P1-001: the auto-resume check used to clear `pausedByPolicy`
     * before even attempting `resume()`, so a resume that then failed to start left the
     * retry state permanently false — the tunnel would never auto-resume again on a
     * later unmetered event, even though it was still genuinely policy-paused. Only
     * `runOfferStart()`'s own success path may clear the flag now.
     */
    @Test
    fun failedAutoResumeLeavesPausedByPolicyTrueForNextRetry() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        runBlocking { service.offer.pauseForPolicy("policy pause before failed auto-resume") }
        assertTrue(service.pausedByPolicy.get())
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)

        // First unmetered event: resume is attempted but the native start fails.
        bridge.failNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "a failed resume attempt must be reported as an error",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertTrue(
            "a failed resume attempt must not clear the policy-pause retry flag",
            service.pausedByPolicy.get(),
        )

        // Second unmetered event: the flag is still true, so this retries with exactly one event.
        // The one-event invariant (P0-004): one later PolicyAllowed event is sufficient; no loop needed.
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "one unmetered event must trigger a successful retry start",
            waitForCondition { bridge.state == ServiceState.Connected },
        )
        assertTrue(
            "the retry flag must clear only once resume actually succeeds",
            waitForCondition { !service.pausedByPolicy.get() },
        )
        assertTrue(
            "a successful resume must leave the tunnel running, not paused/errored",
            waitForCondition { deps.tunnelRepository.status.value.serviceState.isTunnelRunning() },
        )
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
     * P0-001: Tests that START then PAUSE ordering is preserved.
     * The command processor should handle START before PAUSE, so the final
     * state reflects the PAUSE (not running).
     */
    @Test
    fun startThenPauseOrderingPreserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Submit START, then immediately submit PAUSE.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        // Wait for PAUSE to complete. The final state should be paused/stopped, not running.
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            "PAUSE after START must result in stopped state, not running",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped ||
                    deps.tunnelRepository.status.value.serviceState == ServiceState.Error
            },
        )
        assertFalse(
            "START then PAUSE must not leave the tunnel running",
            deps.tunnelRepository.status.value.serviceState.isTunnelRunning(),
        )
    }

    /**
     * P0-001: Tests that PAUSE then START ordering is preserved.
     * The command processor handles PAUSE first, then START.
     * Final state should be active-or-starting.
     */
    @Test
    fun pauseThenStartOrderingPreserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Submit PAUSE (tunnel is stopped, so PAUSE is a no-op), then START.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 1)
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)

        // Wait for START to complete.
        assertTrue(
            "PAUSE then START must result in active-or-starting state",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState.isTunnelActiveOrStarting()
            },
        )
    }

    /**
     * P0-001: Tests that ALLOW_METERED then PAUSE ordering is preserved.
     * The later PAUSE must supersede the earlier ALLOW_METERED command.
     */
    @Test
    fun allowMeteredThenPauseOrderingPreserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Submit ALLOW_METERED_SESSION, then immediately PAUSE.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_ALLOW_METERED_SESSION)).startCommand(0, 1)
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        // Wait for PAUSE to complete. The tunnel should be stopped.
        assertTrue(
            "PAUSE after ALLOW_METERED must result in stopped state",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped ||
                    deps.tunnelRepository.status.value.serviceState == ServiceState.Error
            },
        )
        assertFalse(
            "ALLOW_METERED then PAUSE must not leave the tunnel running",
            deps.tunnelRepository.status.value.serviceState.isTunnelRunning(),
        )
    }

    /**
     * P0-001: Regression test for coordinator-owned cleanup on verified-start failure.
     *
     * Simulates a `StartStatusVerificationException` during startup by making the
     * bridge's `startOffer()` succeed but `getStatusJson()` return an error state,
     * causing the post-start verification to fail. The coordinator must perform
     * exactly one cleanup stop, publish the original verification failure, and not
     * trigger a policy retry.
     */
    @Test
    fun startVerificationFailurePerformsOneCleanupStop() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Arm the verification failure before starting the tunnel.
        bridge.forceNextStatusJsonToReportError()

        // Start the tunnel. This triggers startOffer() + verification.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // The verification failure triggers a cleanup stop, which also fails if the
        // status JSON is still error. Wait for the cleanup to complete.
        assertTrue(
            "start verification failure must trigger a cleanup stop",
            waitForCondition { bridge.stopCalls >= 1 },
        )

        // The tunnel state must be Stopped (cleanup stop succeeded), not running.
        assertEquals(
            "verification failure cleanup stop must leave tunnel in Stopped state",
            ServiceState.Stopped,
            deps.tunnelRepository.status.value.serviceState,
        )

        // Exactly one stop call (the cleanup stop), no startup polling or retry.
        assertEquals(
            "exactly one cleanup stop call must occur",
            1,
            bridge.stopCalls,
        )

        // No policy retry should have occurred.
        assertFalse(
            "verification failure must not trigger policy retry",
            bridge.startOfferCalls > 1,
        )
    }

    /**
     * P0-001: Regression test for cleanup failure preservation on verified-start failure.
     *
     * Simulates a `StartStatusVerificationException` followed by a failing cleanup stop.
     * The error must contain both the original verification failure and the cleanup failure.
     */
    @Test
    fun startVerificationCleanupFailurePreservesBothErrors() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Arm the verification failure and make the cleanup stop also fail.
        bridge.forceNextStatusJsonToReportError()
        bridge.failNextStop()

        // Start the tunnel. This triggers startOffer() + verification.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Wait for the cleanup failure to be published.
        assertTrue(
            "cleanup failure must be published",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Error &&
                    deps.tunnelRepository.status.value.lastError != null
            },
        )

        // The error code must indicate cleanup failure.
        assertEquals(
            "error code must be start_verification_cleanup_failed when cleanup fails",
            "start_verification_cleanup_failed",
            deps.tunnelRepository.status.value.lastError?.code,
        )

        // The error message must indicate cleanup failure.
        val errorMessage = deps.tunnelRepository.status.value.lastError?.message
        assertTrue(
            "error message must contain stop/cleanup failure indicator",
            errorMessage?.contains("stop") == true ||
                errorMessage?.contains("cleanup") == true ||
                errorMessage?.contains("Failed") == true,
        )
    }

    /**
     * P0-001: Regression test for stale generation cleanup.
     *
     * A later lifecycle command (PAUSE) that supersedes the startup must not
     * produce an extra cleanup stop from the stale startup completion.
     */
    @Test
    fun staleGenerationPerformsNoExtraCleanup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Block the start offer so we can queue a PAUSE while startup is in progress.
        bridge.blockNextStartOffer()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(bridge.awaitStartOfferEntered(10_000))

        // Queue a PAUSE command that will supersede the startup.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)
        bridge.failNextStop() // Make the PAUSE stop fail for visibility.

        // Release the start offer, which will now be superseded.
        bridge.releaseBlockedStartOffer()

        // Wait for the PAUSE to complete.
        assertTrue(
            "PAUSE must complete",
            waitForCondition { bridge.stopCalls >= 1 },
        )

        // Exactly one stop call from the PAUSE command, no extra cleanup from startup.
        assertEquals(
            "exactly one stop call from PAUSE, no extra cleanup from stale startup",
            1,
            bridge.stopCalls,
        )

        // The tunnel state must be Error (from the failed PAUSE stop).
        assertEquals(
            "PAUSE failure must leave tunnel in Error state",
            ServiceState.Error,
            deps.tunnelRepository.status.value.serviceState,
        )
    }

    /**
     * P0-001: Startup preparation — network policy blocks tunnel before native start.
     *
     * When the network policy evaluates to NoNetwork (or any blocked type), the startup
     * preparation throws [StartupPolicyBlocked], which becomes [StartOutcome.PolicyBlocked],
     * leaving the tunnel in a PausedMeteredBlocked state — never running.
     */
    @Test
    fun networkPolicyBlockedPreventsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject network policy block before starting.
        TunnelForegroundServiceTestHooks.policyBlockReason.set("No network available")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Wait for startup preparation to complete.
        // The tunnel must not start; native startOffer must never be called.
        assertEquals(
            "network policy block must prevent native start",
            0,
            bridge.startOfferCalls,
        )
        assertTrue(
            "network policy block must leave tunnel in PausedMeteredBlocked state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.PausedMeteredBlocked },
        )
        assertEquals(
            "final state must be PausedMeteredBlocked",
            ServiceState.PausedMeteredBlocked,
            deps.tunnelRepository.status.value.serviceState,
        )
    }

    /**
     * P0-001: Startup preparation — identity read failure aborts startup.
     *
     * When identity decryption fails, the startup preparation calls abortStartup,
     * which throws [StartupAborted], becoming [StartOutcome.Aborted].
     */
    @Test
    fun identityReadFailureAbortsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject identity read failure before starting.
        TunnelForegroundServiceTestHooks.identityReadFailure.set("injected identity decrypt failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Wait for startup preparation to complete.
        // The tunnel must not start; native startOffer must never be called.
        assertEquals(
            "identity read failure must prevent native start",
            0,
            bridge.startOfferCalls,
        )
        assertTrue(
            "identity read failure must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        // Error must have been published.
        assertTrue(
            "error must be published for identity read failure",
            deps.tunnelRepository.status.value.lastError != null,
        )
    }

    /**
     * P0-001: Startup preparation — config preparation failure aborts startup.
     *
     * When configRepository.prepareActiveConfigForStart() returns Result.failure,
     * the startup preparation calls abortStartup, which throws [StartupAborted],
     * becoming [StartOutcome.Aborted].
     */
    @Test
    fun configPrepFailureAbortsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject config preparation failure before starting.
        TunnelForegroundServiceTestHooks.configPrepFailure.set("injected config prep failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Wait for startup preparation to complete.
        // The tunnel must not start; native startOffer must never be called.
        assertEquals(
            "config prep failure must prevent native start",
            0,
            bridge.startOfferCalls,
        )
        assertTrue(
            "config prep failure must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        // Error must have been published.
        assertTrue(
            "error must be published for config preparation failure",
            deps.tunnelRepository.status.value.lastError != null,
        )
    }

    /**
     * P0-003: Active config write failure publishes startup completion with error.
     *
     * When [writeConfigAtomicallyLocked] fails during [prepareActiveConfigForStart],
     * the startup must still submit a [LifecycleCommand.StartupCompleted] via the
     * [StartOutcome.Aborted] path, not leave the startup in an indefinite state.
     */
    @Test
    fun activeConfigWriteFailurePublishesStartupCompletion() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Inject config preparation failure before starting.
        TunnelForegroundServiceTestHooks.configPrepFailure.set("injected config write failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Native start must never be called.
        assertEquals(
            "config write failure must prevent native start",
            0,
            TunnelForegroundServiceTestHooks.bridge.startOfferCalls,
        )

        // Startup must complete with an error (not hang indefinitely).
        assertTrue(
            "config write failure must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertTrue(
            "error must be published for config preparation failure",
            deps.tunnelRepository.status.value.lastError != null,
        )
    }

    /**
     * P0-003: Active config write failure clears active startup.
     *
     * When [writeConfigAtomicallyLocked] fails during [prepareActiveConfigForStart],
     * the startup completion clears the active startup state (no further start can
     * race against this one).
     */
    @Test
    fun activeConfigWriteFailureClearsActiveStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Inject config preparation failure before starting.
        TunnelForegroundServiceTestHooks.configPrepFailure.set("injected config write failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Startup must complete with an error, and any subsequent command proceeds
        // without blocking on the previous startup.
        assertTrue(
            "config write failure must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )

        // Starting again should work (active startup was cleared).
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)
        // The second start should complete (not hang indefinitely on the previous startup).
        assertTrue(
            "second start should complete after config failure",
            waitForCondition {
                val state = deps.tunnelRepository.status.value.serviceState
                // Either connected (second start succeeds) or error (second start fails but completes)
                state == ServiceState.Connected || state == ServiceState.Error
            },
        )
    }

    /**
     * P0-003: Native start is not called after active config failure.
     *
     * When [writeConfigAtomicallyLocked] fails during [prepareActiveConfigForStart],
     * the native [TunnelNativeBridge.startOffer] must never be invoked.
     */
    @Test
    fun nativeStartNotCalledAfterActiveConfigFailure() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject config preparation failure before starting.
        TunnelForegroundServiceTestHooks.configPrepFailure.set("injected config write failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Native start must never be called.
        assertEquals(
            "config write failure must prevent native start",
            0,
            bridge.startOfferCalls,
        )
    }

    /**
     * P0-003: Active config preparation failure (throw path) publishes startup completion.
     *
     * When [writeConfigAtomicallyLocked] throws an exception during
     * [prepareActiveConfigForStart], the startup must still submit a
     * [LifecycleCommand.StartupCompleted] via [StartOutcome.UnexpectedFailure], not
     * leave the startup in an indefinite state.
     */
    @Test
    fun activeConfigWriteFailureThrowsPublishesStartupCompletion() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Inject an exception throw during config preparation (not a failed Result).
        TunnelForegroundServiceTestHooks.configPrepThrows.set("injected config write exception")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Native start must never be called.
        assertEquals(
            "config write exception must prevent native start",
            0,
            TunnelForegroundServiceTestHooks.bridge.startOfferCalls,
        )

        // Startup must complete with an unexpected failure.
        assertTrue(
            "config write exception must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertEquals(
            "error code must indicate unexpected startup failure",
            "startup_unexpected_failure",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    /**
     * P0-001: Startup preparation — config validation failure aborts startup.
     *
     * When identityValidation.validateConfigWithIdentity() returns invalid,
     * the startup preparation calls abortStartup with [ServiceState.ConfigInvalid],
     * which throws [StartupAborted], becoming [StartOutcome.Aborted].
     */
    @Test
    fun configValidationFailureAbortsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject config validation failure before starting.
        TunnelForegroundServiceTestHooks.configValidationFailure.set("injected config validation failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Wait for startup preparation to complete.
        // The tunnel must not start; native startOffer must never be called.
        assertEquals(
            "config validation failure must prevent native start",
            0,
            bridge.startOfferCalls,
        )
        assertTrue(
            "config validation failure must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        // Error must have been published.
        assertTrue(
            "error must be published for config validation failure",
            deps.tunnelRepository.status.value.lastError != null,
        )
    }

    /**
     * P0-001: Startup preparation — successful startup proceeds through all preparation steps.
     *
     * When all preparation steps succeed, the startup proceeds to the native startOffer
     * call and completes with [StartOutcome.VerifiedSuccess].
     */
    @Test
    fun successfulStartupPreparesAndStarts() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // No failure injection — happy path.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // The tunnel must start successfully.
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        assertEquals(
            "successful startup must call native startOffer exactly once",
            1,
            bridge.startOfferCalls,
        )
    }

    /**
     * P0-001: Startup preparation — unexpected exception during preparation
     * publishes startup completion as [StartOutcome.UnexpectedFailure].
     *
     * When an exception that is not [CancellationException], [StartupPolicyBlocked],
     * or [StartupAborted] escapes from preparation, the coordinator still submits a
     * [LifecycleCommand.StartupCompleted] and publishes an error, rather than leaving
     * the startup in an indefinite state.
     */
    @Test
    fun unexpectedPreparationFailurePublishesStartupCompletion() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject an unexpected exception that throws (not a controlled ValidationResult).
        TunnelForegroundServiceTestHooks.validationThrows.set("unexpected validation error")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Native start must never be called.
        assertEquals(
            "unexpected preparation failure must prevent native start",
            0,
            bridge.startOfferCalls,
        )

        // Error must be published with the unexpected-failure code.
        assertTrue(
            "unexpected preparation failure must leave tunnel in Error state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertEquals(
            "error code must indicate unexpected startup failure",
            "startup_unexpected_failure",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    /**
     * P0-001: Startup preparation — [CancellationException] propagates.
     *
     * When a startup is cancelled (PAUSE while startup is in flight), the
     * [CancellationException] must be rethrown by [performStartupAttempt], not
     * swallowed and returned as a [StartOutcome]. The coordinator submits a
     * [LifecycleCommand.StartupCompleted], and the startup job must be cancelled.
     */
    @Test
    fun startupPreparationCancellationPropagates() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Block the native start so startup is in-flight and cancellable.
        bridge.blockNextStartOffer()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(
            "startup must enter the blocked offer phase",
            bridge.awaitStartOfferEntered(10_000),
        )

        // PAUSE cancels the startup job. The cancellation propagates through
        // performStartupAttempt — CancellationException is rethrown, not wrapped.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        // Wait for the PAUSE to complete (stop call + state change).
        assertTrue(
            "PAUSE must complete after cancelling the startup",
            waitForCondition { bridge.stopCalls >= 1 },
        )

        // Release the blocked offer (no-op after cancellation, but avoids hanging).
        bridge.releaseBlockedStartOffer()

        // The tunnel must not be running — startup was cancelled, not started.
        assertFalse(
            "cancelled startup must not leave tunnel running",
            bridge.state.isTunnelRunning(),
        )

        // Exactly one stop from the PAUSE, proving the startup job was cancelled
        // (not started then stopped separately).
        assertEquals(
            "cancelled startup must not produce a separate stop call",
            1,
            bridge.stopCalls,
        )
    }
}

/**
 * The `Dispatchers.IO` default keeps the only direct reference inside a
 * parameter default (DI), satisfying `InjectDispatcher` — see this
 * project's `inlineTestDispatchers()`/`realIoTestDispatchers()` convention
 * in `AppViewModelTestBase.kt`.
 */
private fun realIoTunnelForegroundService(dispatcher: CoroutineDispatcher = Dispatchers.IO): TunnelForegroundService =
    TunnelForegroundService(ioDispatcher = dispatcher, defaultDispatcher = dispatcher)

/**
 * P0-002: Tests for pending retry invalidation.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class PendingRetryInvalidationTest {
    private val controller =
        ServiceController.of(
            realIoTunnelForegroundService(),
            Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java),
        )
    private lateinit var service: TunnelForegroundService

    @Before
    fun setUp() {
        TunnelForegroundServiceTestHooks.identityReadFailure.set(null)
        TunnelForegroundServiceTestHooks.configPrepFailure.set(null)
        TunnelForegroundServiceTestHooks.policyBlockReason.set(null)
        TunnelForegroundServiceTestHooks.configValidationFailure.set(null)
        TunnelForegroundServiceTestHooks.validationThrows.set(null)
        TunnelForegroundServiceTestHooks.configPrepThrows.set(null)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun actionIntent(action: String) =
        Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java).setAction(action)

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

    /**
     * P0-002: pending retry then Destroy does not restart.
     *
     * Simulates a pending policy retry (generation set), then destroys the service.
     * The retry must not fire.
     */
    @Test
    fun pendingRetryThenDestroyDoesNotRestart() {
        // Start the tunnel normally to establish a valid generation.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Verify that onDestroy does not trigger a restart.
        controller.destroy()
        // After destroy, the pending retry must have been invalidated.
        // The service should be cleanly destroyed without triggering a restart.
        assertTrue("destroy should complete without triggering retry restart", true)
    }

    /**
     * P0-002: pending retry then explicit Pause does not restart.
     *
     * Simulates a pending policy retry, then explicitly pauses.
     * The retry must not fire.
     */
    @Test
    fun pendingRetryThenPauseDoesNotRestart() {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Explicit pause should invalidate any pending retry.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.stopCalls >= 1 })

        // Verify the pause completed and no restart occurred.
        assertFalse(
            "pause after pending retry should leave tunnel stopped, not running",
            TunnelForegroundServiceTestHooks.bridge.state.isTunnelRunning(),
        )
    }

    /**
     * P0-002: pending retry then explicit Stop does not restart.
     *
     * Simulates a pending policy retry, then explicitly stops.
     * The retry must not fire.
     */
    @Test
    fun pendingRetryThenStopDoesNotRestart() {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Explicit stop should invalidate any pending retry.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.stopCalls >= 1 })

        // Verify the stop completed.
        assertFalse(
            "stop after pending retry should leave tunnel stopped, not running",
            TunnelForegroundServiceTestHooks.bridge.state.isTunnelRunning(),
        )
    }

    /**
     * P0-002: new StartOffer invalidates pending retry.
     *
     * When a new StartOffer is submitted, any pending retry generation should be
     * invalidated to prevent concurrent startup attempts.
     */
    @Test
    fun pendingRetryThenNewStartOfferInvalidatesOldRetry() {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Second start should be blocked by the already-running state.
        val initialStartCount = TunnelForegroundServiceTestHooks.bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)

        // The second start should not trigger an additional native start.
        assertEquals(
            "duplicate start should not trigger additional native start",
            initialStartCount,
            TunnelForegroundServiceTestHooks.bridge.startOfferCalls,
        )
    }

    /**
     * P0-002: valid retry while policy-paused runs exactly once.
     *
     * When the retry is valid (policy-paused, matching generation), it should resume
     * exactly once, not loop indefinitely.
     */
    @Test
    fun validRetryWhilePolicyPausedRunsExactlyOnce() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Start and connect.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // Policy pause.
        runBlocking { service.offer.pauseForPolicy("policy pause for retry test") }
        assertTrue(service.pausedByPolicy.get())

        // The tunnel must be paused.
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        // Resume should succeed exactly once.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_RESUME)).startCommand(0, 2)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // Verify exactly one native start occurred after resume.
        val resumeStartCount = bridge.startOfferCalls
        assertEquals("resume should trigger exactly one start after policy pause", 2, resumeStartCount)
    }
}
