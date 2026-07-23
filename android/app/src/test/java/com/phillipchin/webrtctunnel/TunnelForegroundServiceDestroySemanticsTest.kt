package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.data.StartOutcome
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
 * FIX7 P2-001-B (deviation, see TODO signoff) / FIX8 P1-004-B: a genuine "startup completes and
 * submits StartupCompleted just as destroy has already closed the command queue but before
 * cancelAndJoin reaches the startup job" race could not be forced via literal thread
 * interleaving with the existing blockNextStartOffer/awaitStartOfferEntered/
 * releaseBlockedStartOffer hooks — releasing the block after destroy has requested cancellation
 * reliably makes the startup coroutine observe that cancellation instead (see
 * TunnelForegroundServiceStopFailureTest's pendingRetryThenDestroyDoesNotRestart). Forcing the
 * literal interleaving would require a new production-only pause point between the native call
 * returning and the `StartupCompleted` submit, which this repo's established convention (real DI
 * seams, not static test-hook checks in production) argues against adding just for this one
 * window. Instead, [lateStartupCompletionAfterDestroyIsRejectedByRealGenerationPath] drives the
 * real lifecycle collaborator directly: it advances the real `lifecycleGeneration` (exactly as
 * `onDestroy()` does before `cancelStartupJobAndJoinLocked()`) and then calls the real
 * `OfferCoordinator.handleStartupCompleted` with the stale generation — the exact production
 * guard (`if (service.lifecycleGeneration.get() != generation) return`) that the race would
 * exercise, proven directly rather than merely inspected.
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

    // FIX8 P1-004-B/D: see the class doc for why this drives the real generation-guard
    // collaborator directly rather than forcing literal thread interleaving. Calls the real
    // ServiceCoordinatorOperations.handleStartupCompleted directly (a suspend call, awaited
    // synchronously — no barrier command needed or wanted here) rather than through
    // submitLifecycleCommand: every OTHER lifecycle command's successful handler also resets
    // `lastError` as part of publishing its own clean state (setPolicyBlocked, an explicit
    // Stop's repository.stop() success, etc.), so using any of them as a FIFO-drain barrier
    // would silently overwrite whatever a wrongly-processed stale completion had set,
    // making the assertion below pass regardless of whether the guard actually worked. A
    // direct suspend call has no such intervening step.
    @Test
    fun lateStartupCompletionAfterDestroyIsRejectedByRealGenerationPath() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        startConnected()

        val staleGeneration = service.lifecycleGeneration.get()
        val startCallsBefore = bridge.startOfferCalls

        // Simulates onDestroy()'s real ordering: lifecycleGeneration is bumped (by the
        // concurrent destroy path) before this startup's StartupCompleted is processed.
        service.lifecycleGeneration.incrementAndGet()

        // A NativeFailure is the outcome with the most visible side effects if the guard were
        // ever bypassed (publishError sets a durable lastError, or a matching pending retry
        // would trigger an extra native start) — the strongest proof that the whole branch,
        // not just a state field, is skipped for a stale generation.
        kotlinx.coroutines.runBlocking {
            service.coordinatorOps.handleStartupCompleted(
                staleGeneration,
                StartOutcome.NativeFailure(RuntimeException("late completion")),
            )
        }

        assertEquals(
            "a stale-generation completion must trigger no extra native start",
            startCallsBefore,
            bridge.startOfferCalls,
        )
        assertEquals(
            "a stale-generation completion must never publish a durable error",
            null,
            deps.tunnelRepository.status.value.lastError,
        )
    }
}
