package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * FIX6 P1-010: destroy-time cleanup is best effort, not an authoritative stop. These prove the
 * truthful semantics: an explicit verified STOP is authoritative (destroy performs no redundant
 * native stop), and an observed destroy-fallback failure is published and never recorded as a
 * clean stop. Waits are on observable published state, not elapsed time.
 *
 * FIX7 P2-001-B (deviation, see TODO signoff): a genuine "startup completes and submits
 * StartupCompleted just as destroy has already closed the command queue but before
 * cancelAndJoin reaches the startup job" race could not be forced deterministically with the
 * existing blockNextStartOffer/awaitStartOfferEntered/releaseBlockedStartOffer hooks — releasing
 * the block after destroy has requested cancellation reliably makes the startup coroutine
 * observe that cancellation instead (see TunnelForegroundServiceStopFailureTest's
 * pendingRetryThenDestroyDoesNotRestart), so this class does not cover that specific race.
 * onDestroy()'s ordering (coordinator.stop() before cancelStartupJobAndJoinLocked()) and
 * handleStartupCompleted's generation guard are the two mechanisms that would prevent it, per
 * code inspection.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceDestroySemanticsTest {
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

    // FIX7 P2-001-A: a bounded poll for POSITIVE external-state convergence only (e.g. a
    // StateFlow/bridge counter settling after real async work dispatched on a real thread pool,
    // with no injected completion event to await instead). Never used here to prove absence,
    // exactly-once, ordering, or overlap — those proofs use an explicit barrier/latch instead.
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

    private fun startConnected() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
    }

    @Test
    fun explicitStopRemainsAuthoritativeBeforeDestroy() {
        val bridge = TunnelForegroundServiceTestHooks.bridge
        startConnected()

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        val stopCallsAfterExplicit = bridge.stopCalls

        controller.destroy()

        // The verified explicit stop is authoritative, so destroy's fallback is guarded off and
        // performs no redundant native stop regardless of whether its cleanup coroutine ran.
        assertEquals(
            "destroy must not perform a redundant native stop after a verified explicit stop",
            stopCallsAfterExplicit,
            bridge.stopCalls,
        )
    }

    @Test
    fun destroyFallbackStopFailureEntersRuntimeQuarantineWhenObserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        startConnected()

        bridge.failNextStop()
        controller.destroy()

        assertTrue("destroy fallback stop must be attempted", waitForCondition { bridge.stopCalls >= 1 })
        // FIX7 P0-007-A/RESPONSES item 2: the durable lastError becomes the canonical
        // native_runtime_quarantined code (not overwritten back to the narrower one), while
        // the specific diagnostic is still recorded as sticky cleanup-failure history.
        assertTrue(
            "an observed destroy-fallback stop failure must durably quarantine the runtime",
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )
        // Note: "destroy_fallback_stop_failed" is not one of TunnelRepository.setLocalError's
        // sticky-cleanup-history codes (only stop_failed/stop_status_verification_failed/
        // start_verification_cleanup_failed are), so unlike those, it is not expected to also
        // land in lastCleanupError.
    }

    @Test
    fun destroyWithoutCleanupCompletionDoesNotPublishFalseVerifiedStop() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        startConnected()

        // A failed fallback stop means cleanup did not complete successfully; the service must
        // never record that as a clean/verified stopped state.
        bridge.failNextStop()
        controller.destroy()

        assertTrue(
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )
        assertNotEquals(
            "a failed destroy cleanup must not be published as a clean stopped state",
            ServiceState.Stopped,
            deps.tunnelRepository.status.value.serviceState,
        )
    }
}
