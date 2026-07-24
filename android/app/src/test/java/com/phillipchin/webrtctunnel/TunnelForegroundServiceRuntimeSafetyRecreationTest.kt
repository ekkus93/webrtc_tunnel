package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.notification.NotificationController
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
 * FIX8 P0-009 (CRITICAL-6/HIGH-10): [com.phillipchin.webrtctunnel.data.NativeRuntimeSafetyState]
 * moved quarantine/stop-verification bookkeeping out of `TunnelForegroundService` (reset on every
 * Android-driven service recreation) into an application-scoped owner shared by every service
 * instance. These tests construct TWO real service instances sharing one Robolectric
 * application/dependency graph — never a locally mutated Boolean — to prove the shared owner
 * actually survives a recreation.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceRuntimeSafetyRecreationTest {
    private val controller =
        ServiceController.of(
            realIoService(),
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

    // FIX7 P2-001-A: a bounded poll for POSITIVE external-state convergence only.
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

    // Same technique as TunnelForegroundServiceStopFailureTest.drainQueueWithStopBarrier: STOP is
    // never gated by the quarantine guard, so waiting for its effect proves — via FIFO
    // single-consumer draining — that every command submitted before it has already been fully
    // processed (accepted or rejected), without polling for absence over a timed window.
    private fun drainQueueWithStopBarrier(
        targetController: ServiceController<TunnelForegroundService>,
        barrierId: Int,
    ) {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val stopCallsBefore = bridge.stopCalls
        targetController.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, barrierId)
        assertTrue(
            "the STOP barrier must be processed so the queue is provably drained",
            waitForCondition { bridge.stopCalls >= stopCallsBefore + 1 },
        )
    }

    private fun newController() =
        ServiceController.of(
            realIoService(),
            Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java),
        )

    private fun quarantineViaFailedExplicitStop() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(waitForCondition { deps.nativeRuntimeSafetyState.state.value.quarantined })
    }

    @Test
    fun serviceRecreationWhileQuarantinedStillBlocksNativeStart() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        quarantineViaFailedExplicitStop()
        controller.destroy()

        val controller2 = newController()
        try {
            controller2.create()
            val startCountBeforeNewInstance = bridge.startOfferCalls
            controller2.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
            drainQueueWithStopBarrier(controller2, barrierId = 2)

            assertEquals(
                "a recreated service instance sharing the same application-scoped safety state " +
                    "must still be blocked from a native start while quarantined",
                startCountBeforeNewInstance,
                bridge.startOfferCalls,
            )
        } finally {
            controller2.destroy()
        }
    }

    @Test
    fun serviceRecreationWhileQuarantinedStillBlocksManualResume() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        quarantineViaFailedExplicitStop()
        controller.destroy()

        val controller2 = newController()
        try {
            controller2.create()
            val startCountBeforeNewInstance = bridge.startOfferCalls
            controller2.withIntent(actionIntent(TunnelForegroundService.ACTION_RESUME)).startCommand(0, 1)
            drainQueueWithStopBarrier(controller2, barrierId = 2)

            assertEquals(
                "a recreated service instance sharing the same application-scoped safety state " +
                    "must still be blocked from a manual RESUME while quarantined",
                startCountBeforeNewInstance,
                bridge.startOfferCalls,
            )
        } finally {
            controller2.destroy()
        }
    }

    @Test
    fun pendingPolicyRetryQuarantineGuardFailureIsDurableAndVisible() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        quarantineViaFailedExplicitStop()

        val notifiedTexts = mutableListOf<String>()
        service.notifications =
            NotificationController(
                service,
                notificationsAllowedProvider = { true },
                notifyAction = { _, notification ->
                    val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                    notifiedTexts.add(text.toString())
                },
            )

        // FIX8 P0-009-C: previously handleRetryPolicyResume's `.getOrNull()` silently discarded
        // a guard failure here — this must now report through the same durable, visible
        // reporter every other guard failure uses. ServiceCoordinatorOperations is internal and
        // constructed directly (same technique as UnverifiedStartContextTest) to exercise the
        // guard deterministically rather than racing the real pending-retry-establishment path.
        val ops = ServiceCoordinatorOperations(service)
        runBlocking { ops.handleRetryPolicyResume(service.lifecycleGenerationForTest) }

        assertTrue(
            "a blocked retry-policy-resume must report through the durable reporter, unlike the " +
                "previous getOrNull()-based implementation which silently dropped it",
            notifiedTexts.any { it.contains("explicit STOP is required before restart") },
        )
        assertEquals(
            "the canonical durable quarantine code must remain visible, unreplaced by the " +
                "narrower recovery-required diagnostic",
            "native_runtime_quarantined",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    @Test
    fun destroyFallbackSuccessDoesNotClearPreexistingQuarantine() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps
        quarantineViaFailedExplicitStop()

        // destroy's fallback stop is not primed to fail this time — it succeeds.
        controller.destroy()
        assertTrue(
            "destroy's fallback stop must actually be attempted (stopVerified was left false " +
                "by the earlier quarantine)",
            waitForCondition { bridge.stopCalls >= 2 },
        )

        assertTrue(
            "a successful destroy-fallback stop must not clear a pre-existing quarantine from " +
                "a different, unresolved failure",
            deps.nativeRuntimeSafetyState.state.value.quarantined,
        )
    }

    @Test
    fun successfulPauseDoesNotClearPreexistingQuarantine() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps
        quarantineViaFailedExplicitStop()

        // pause() is not gated by requireRuntimeStartAllowed, and its stop succeeds this time.
        runBlocking { service.offer.pause() }
        assertTrue(bridge.stopCalls >= 2)

        assertTrue(
            "a successful pause must not clear a pre-existing quarantine from a different, " +
                "unresolved failure",
            deps.nativeRuntimeSafetyState.state.value.quarantined,
        )
    }

    @Test
    fun nativeStatusRefreshCannotOverwriteQuarantineWithConnected() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps
        quarantineViaFailedExplicitStop()

        // The failed stop never actually changed the fake bridge's state; it is still Connected.
        assertEquals(ServiceState.Connected, bridge.state)
        deps.tunnelRepository.refreshStatus()

        assertEquals(
            "a native status refresh reporting Connected must never overwrite a quarantined " +
                "Error state",
            ServiceState.Error,
            deps.tunnelRepository.status.value.serviceState,
        )
        assertEquals("native_runtime_quarantined", deps.tunnelRepository.status.value.lastError?.code)
    }

    @Test
    fun nativeStatusRefreshCannotOverwriteQuarantineWithStopped() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        quarantineViaFailedExplicitStop()

        // Simulate the native runtime independently reporting Stopped on a later poll —
        // e.g. it happened to wind down on its own — without going through the one path
        // (a verified explicit STOP) allowed to clear quarantine.
        TunnelForegroundServiceTestHooks.bridge.state = ServiceState.Stopped
        deps.tunnelRepository.refreshStatus()

        assertEquals(
            "a native status refresh reporting Stopped alone must never clear quarantine",
            ServiceState.Error,
            deps.tunnelRepository.status.value.serviceState,
        )
        assertTrue(deps.nativeRuntimeSafetyState.state.value.quarantined)
    }

    @Test
    fun verifiedExplicitStopClearsSharedQuarantineForLaterServiceInstance() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps
        quarantineViaFailedExplicitStop()

        // A verified explicit STOP on the same instance clears the shared quarantine.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 3)
        assertTrue(waitForCondition { bridge.stopCalls >= 2 })
        assertTrue(waitForCondition { !deps.nativeRuntimeSafetyState.state.value.quarantined })

        controller.destroy()

        val controller2 = newController()
        try {
            val service2 = controller2.create().get()
            controller2.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
            assertTrue(
                "a later service instance must see the cleared shared quarantine and be able " +
                    "to start",
                waitForCondition { bridge.state == ServiceState.Connected },
            )
            assertFalse(service2.requireRuntimeStartAllowed().isFailure)
        } finally {
            controller2.destroy()
        }
    }

    @Test
    fun staleServiceDestroyCannotClearNewerRuntimeSafetyGeneration() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps

        // The existing `service` instance begins a start attempt that blocks mid native-call:
        // markStartAttempted() has already run (stopVerified is now false), but the shared
        // status has not yet become active, since the native call itself has not returned.
        // Calling offer.startOffer() directly (bypassing ServiceCoordinatorOperations' gate,
        // irrelevant here) mirrors how OfferCoordinator itself drives a start.
        bridge.blockNextStartOffer()
        runBlocking { service.offer.startOffer() }
        assertTrue(bridge.awaitStartOfferEntered(5_000))

        // A stale destroy-fallback (e.g. this instance's onDestroy) would capture the generation
        // at the moment it decides a fallback stop is needed.
        val staleObservedGeneration = deps.nativeRuntimeSafetyState.state.value.generation

        // Before that stale fallback's stop call actually lands, a NEWER instance sharing the
        // same AppDependencies begins its own start attempt — the one-shot block was already
        // consumed above, so this one completes immediately — advancing the shared generation
        // further. The shared status is still not active (the first instance's native call
        // never returned), so this newer start is not gated off by an "already active" check.
        val controller2 = newController()
        try {
            val service2 = controller2.create().get()
            runBlocking { service2.offer.startOffer() }
            assertTrue(
                "the newer instance's start attempt must have advanced the shared generation",
                deps.nativeRuntimeSafetyState.state.value.generation > staleObservedGeneration,
            )

            // The stale fallback's stop call now finally lands, stamped with the old generation
            // it captured before the newer instance acted.
            val stopResult = deps.tunnelRepository.stop(ifGenerationUnchanged = staleObservedGeneration)
            assertTrue(stopResult.isSuccess)

            assertFalse(
                "a stale destroy-fallback stamped with an old generation must not be able to " +
                    "mark the newer generation's stop as observed",
                deps.nativeRuntimeSafetyState.state.value.stopVerified,
            )
        } finally {
            bridge.releaseBlockedStartOffer()
            controller2.destroy()
        }
    }

    @Test
    fun reporterFailureCannotPreventSharedQuarantineTransition() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val deps = (service.applicationContext as HasAppDependencies).deps

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // Force every notification post to throw, simulating a reporter/notification-layer
        // failure. enterNativeRuntimeQuarantine's own try/catch (publishErrorSafely) already
        // swallows this — the point of this test is proving the SHARED safety-state transition,
        // which happens before any reporter call, is unaffected either way.
        service.notifications =
            NotificationController(
                service,
                notificationsAllowedProvider = { true },
                notifyAction = { _, _ -> error("injected notification failure") },
            )

        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)

        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        // FIX8 P2-002 (CI flakiness root-cause, same shape as
        // TunnelForegroundServiceVerificationTest.startVerificationCleanupFailureEntersRuntimeQuarantine):
        // enterNativeRuntimeQuarantine makes two separate, non-atomic setLocalError calls (the
        // narrower diagnostic code, then the canonical quarantine code). Polling only the weaker
        // `quarantined` flag before a bare assertEquals on the canonical code races production
        // code running on another thread — the poll can observe the intermediate window and
        // return before the final code lands. Poll for both together instead.
        assertTrue(
            "the shared quarantine transition must land even if the notification/reporter " +
                "layer throws, with the canonical durable error code set",
            waitForCondition {
                deps.nativeRuntimeSafetyState.state.value.quarantined &&
                    deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )
        assertEquals(
            "the canonical durable error code must still be set despite the reporter failure",
            "native_runtime_quarantined",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }
}
