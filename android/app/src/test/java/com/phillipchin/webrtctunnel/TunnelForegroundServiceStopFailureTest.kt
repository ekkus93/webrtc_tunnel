package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceStopFailureTest {
    // Unconfined dispatchers (matching this project's inlineTestDispatchers() convention
    // elsewhere) keep every suspend call synchronous on the test thread. Real
    // Dispatchers.IO/Default would leave the service's own networkMonitorJob running on
    // shared, process-wide thread pools after the test method returns, bleeding into
    // whichever unrelated Robolectric test runs next in the same JVM.
    private val controller =
        ServiceController.of(
            TunnelForegroundService(ioDispatcher = Dispatchers.Unconfined, defaultDispatcher = Dispatchers.Unconfined),
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

    // `serviceScope.launch { ... }` (used by onStartCommand's action handlers) is
    // fire-and-forget: it returns as soon as the coroutine hits its first suspension,
    // not once the whole action has finished, even under Dispatchers.Unconfined. Poll
    // for the observable outcome instead of assuming synchronous completion.
    private fun waitForCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }

    @Test
    fun pauseWithFailingStopPublishesErrorNotPaused() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.startOfferCalls >= 1 })

        TunnelForegroundServiceTestHooks.bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.stopCalls >= 1 })
        assertTrue(
            "a failed pause stop must be reported as an error, never the paused state",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
    }

    @Test
    fun stopServiceWorkWithFailingStopStillReportsErrorNotClean() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.startOfferCalls >= 1 })

        TunnelForegroundServiceTestHooks.bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)

        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.stopCalls >= 1 })
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
}

/**
 * Isolated from the class above: this scenario needs genuine asynchrony (a blocked
 * native start running on a real background thread while the test thread drives a
 * second action), which `Dispatchers.Unconfined`'s eager, same-thread execution
 * cannot provide. Real `Dispatchers.IO` schedules `serviceScope.launch { ... }` onto
 * a background thread and returns immediately, matching how this scenario is proved
 * under real instrumentation in `TunnelForegroundServiceInstrumentationTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceStartupCancellationStopFailureTest {
    private val controller =
        ServiceController.of(
            TunnelForegroundService(ioDispatcher = Dispatchers.IO, defaultDispatcher = Dispatchers.IO),
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
        timeoutMs: Long,
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
    fun stopDuringPendingStartWithFailingCleanupStopPublishesError() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        bridge.blockNextStartOffer()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(bridge.awaitStartOfferEntered(10_000))

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        // stopServiceWork()'s own stop() call (unblocked) lands first; only after that do
        // we arm the failure, so it lands on the startup-cancellation cleanup's own
        // repository.stop() call once the blocked start is released.
        assertTrue(waitForCondition(8_000) { bridge.stopCalls >= 1 })
        bridge.failNextStop()
        bridge.releaseBlockedStartOffer()

        assertTrue(waitForCondition(8_000) { bridge.stopCalls >= 2 })
        assertTrue(
            waitForCondition(5_000) {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Error
            },
        )
    }
}
