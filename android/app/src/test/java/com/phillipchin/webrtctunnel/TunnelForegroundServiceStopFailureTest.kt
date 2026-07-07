package com.phillipchin.webrtctunnel

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ServiceState
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

    @Test
    fun laterSuccessfulStopDoesNotEraseEarlierCleanupFailureHistory() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // First stop attempt fails.
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })
        assertTrue(
            "a stop failure must record sticky cleanup-failure history",
            waitForCondition { deps.tunnelRepository.status.value.lastCleanupError != null },
        )

        // A later retry succeeds: the current runtime state may truthfully become Stopped...
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 3)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 4)
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped })

        // ...but the earlier cleanup failure must remain visible in diagnostics, not silently
        // erased by the later successful retry (P1-005).
        assertTrue(
            "an earlier cleanup failure must remain visible after a later successful stop",
            deps.tunnelRepository.status.value.lastCleanupError != null,
        )
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
        // Give a hypothetical second (buggy, competing) stop call every chance to land
        // before the final count check below: with the fix this can never happen (the
        // startup coroutine has no remaining code path that calls repository.stop() at
        // all, so waiting longer cannot change the outcome), so this only strengthens
        // the regression-detection power of this test against the old dual-ownership
        // bug without weakening the proof for the fixed behavior.
        waitForCondition(timeoutMs = 3_000) { bridge.stopCalls >= 2 }
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

        // The core proof: pause() must not be able to even attempt the native stop
        // while the stale refresh it depends on quiescing is still blocked. This is
        // a positive assertion of absence within a generous bounded window, not a
        // sleep used to synchronize correctness — without quiescing, nothing else
        // holds pause() back, so this reliably distinguishes the two behaviors
        // instead of racing the eventual final state (which either implementation
        // can reach through incidental thread-scheduling timing, proving nothing).
        assertFalse(
            "pause() must not call native stop before the in-flight stale status " +
                "refresh has been quiesced",
            waitForCondition(timeoutMs = 500) { bridge.stopCalls >= 1 },
        )

        // Release the stale refresh so its blocked native read can finally return,
        // and wait for that *exact* stale poll iteration to fully settle (commit its
        // result or be discarded by cancellation) before checking the final state.
        bridge.releaseBlockedStatusJsonRead()
        runBlocking { staleJob?.join() }

        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            "a failed stop must be the final truth even though a status refresh was " +
                "in flight when the stop was requested",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
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
