package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
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
    fun failedPolicyStopForcesPausedByPolicyFalseEvenFromStaleTruePrecondition() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // First policy pause succeeds, establishing the precondition this bug needs:
        // pausedByPolicy already true from a prior, clean pause.
        runBlocking { service.offer.pauseForPolicy("first policy pause") }
        assertTrue(service.pausedByPolicy)
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        // A second, re-entrant policy pause now fails to stop the tunnel.
        TunnelForegroundServiceTestHooks.bridge.failNextStop()
        runBlocking { service.offer.pauseForPolicy("second policy pause") }

        assertFalse(
            "a failed policy-pause stop must never leave a stale pausedByPolicy == true",
            service.pausedByPolicy,
        )
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)

        // Retry/reevaluation stays open: a subsequent successful pause still lands cleanly.
        runBlocking { service.offer.pauseForPolicy("retry policy pause") }
        assertTrue(service.pausedByPolicy)
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)
    }

    @Test
    fun stopDuringPendingStartWithFailingCleanupStopPublishesError() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        bridge.blockNextStartOffer()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(bridge.awaitStartOfferEntered(10_000))

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        // stopServiceWork()'s own stop() call (unblocked) lands first and succeeds;
        // only after that do we arm the failure, so it targets the startup-
        // cancellation cleanup's own stop() call specifically, once the blocked
        // start is released.
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        bridge.failNextStop()
        bridge.releaseBlockedStartOffer()

        // Exact-branch proof (P0-003): wait for the cancellation cleanup's own
        // event, not just a stop-call count — a generic "some later stop failed"
        // check cannot distinguish this branch from stopServiceWork()'s own call or
        // the supersedence-cleanup branch.
        val event = runBlocking { withTimeout(10_000) { service.testEvents.receive() } }
        assertEquals(ServiceTestEvent.StartupCancellationCleanupStopEntered, event)

        assertTrue(waitForCondition { bridge.stopCalls >= 2 })
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })
        // No clean startup success can have been published: the cancellation catch
        // branch returns unconditionally after handling cleanup, never reaching the
        // result.onSuccess { ... } success path.
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

    @Test
    fun startupSupersedenceCleanupStopFailurePublishesError() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Pause runOfferStart after a *successful* native start but before the
        // generation check that decides whether a newer start superseded this one.
        val hooks =
            StartupTestHooks(
                afterNativeStartBeforeGenerationCheck = CompletableDeferred(),
                releaseAfterNativeStart = CompletableDeferred(),
            )
        service.startupTestHooks = hooks

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        runBlocking { withTimeout(10_000) { hooks.afterNativeStartBeforeGenerationCheck!!.await() } }

        // Simulate a newer start having superseded this one: bump the generation
        // alone, without touching startupJob. No current production path can do this
        // — every real generation bump also cancels startupJob in the same lock
        // scope, which the in-flight runOfferStart observes as a
        // CancellationException from its native-start withContext call (the
        // cancellation-cleanup branch, P0-003) rather than this supersedence check.
        // This exists solely to stimulate that real check/cleanup under test.
        runBlocking { service.lifecycleMutex.withLock { service.lifecycleGeneration += 1 } }

        // Arm the failure for the supersedence cleanup's own stop() call, then let
        // runOfferStart proceed to its (now-stale) generation check.
        bridge.failNextStop()
        hooks.releaseAfterNativeStart!!.complete(Unit)

        // Exact-branch proof (P0-004): wait for the supersedence cleanup's own
        // event, not just a stop-call count.
        val event = runBlocking { withTimeout(10_000) { service.testEvents.receive() } }
        assertEquals(ServiceTestEvent.StartupSupersedenceCleanupStopEntered, event)

        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        // No clean startup success can have been published: the supersedence branch
        // returns to the outer `if`'s `else` only when the generation still matches,
        // which it deliberately does not here.
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
