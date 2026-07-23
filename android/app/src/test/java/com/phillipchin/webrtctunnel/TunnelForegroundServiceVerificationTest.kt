package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests for startup verification and cleanup failures in [TunnelForegroundService].
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceVerificationTest {
    private val controller =
        ServiceController.of(
            realIoService(),
            Intent(
                ApplicationProvider.getApplicationContext(),
                TunnelForegroundService::class.java,
            ),
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

        // Gate on the cleanup stop having both happened *and* published its outcome.
        // Both halves are required: FailableRecordingBridge.stop() increments stopCalls on
        // its first line whereas Stopped is only published afterwards by repository.stop()'s
        // verification refresh (so gating on stopCalls alone can assert inside that window
        // and read the intervening Error state), while Stopped alone is the state this test
        // *starts* in (so gating on it alone passes instantly, before any stop at all).
        assertTrue(
            "start verification failure must trigger a cleanup stop that reaches Stopped",
            waitForCondition {
                bridge.stopCalls >= 1 &&
                    deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped
            },
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
        assertEquals(
            "verification failure must not trigger policy retry",
            1,
            bridge.startOfferCalls,
        )
    }

    /**
     * P0-001/FIX7 P0-007-B: regression test for cleanup failure preservation on verified-start
     * failure, and quarantine entry.
     *
     * Simulates a `StartStatusVerificationException` followed by a failing cleanup stop. The
     * durable lastError must become the canonical quarantine code, while the specific cleanup
     * failure remains inspectable via sticky cleanup history.
     */
    @Test
    fun startVerificationCleanupFailureEntersRuntimeQuarantine() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Arm the verification failure and make the cleanup stop also fail.
        bridge.forceNextStatusJsonToReportError()
        bridge.failNextStop()

        // Start the tunnel. This triggers startOffer() + verification.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        // Wait for the cleanup failure to be published. FIX8 P1-004-C: poll for the exact
        // canonical code asserted below, not just "some error is present" — enterNativeRuntimeQuarantine
        // makes two separate, non-atomic setLocalError calls (the narrower diagnostic code, then
        // the canonical quarantine code), so a weaker predicate could observe the intermediate
        // state between them and report success before the final code lands, racing the
        // assertion below against production code running on another thread.
        assertTrue(
            "cleanup failure must be published with the canonical quarantine code",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Error &&
                    deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )

        // FIX7 P0-007-A/RESPONSES item 2: the durable lastError becomes the canonical
        // native_runtime_quarantined code — a cleanup failure quarantines the runtime, exactly
        // like every other stop-like failure — while the specific diagnostic is preserved as
        // sticky cleanup-failure history.
        assertEquals(
            "lastError must be the canonical quarantine code once cleanup fails",
            "native_runtime_quarantined",
            deps.tunnelRepository.status.value.lastError?.code,
        )
        assertEquals(
            "the specific cleanup-failure diagnostic must remain in sticky cleanup history",
            "start_verification_cleanup_failed",
            deps.tunnelRepository.status.value.lastCleanupError?.code,
        )

        // The error message must indicate cleanup failure.
        val errorMessage = deps.tunnelRepository.status.value.lastCleanupError?.message
        assertTrue(
            "error message must contain stop/cleanup failure indicator",
            errorMessage?.contains("stop") == true ||
                errorMessage?.contains("cleanup") == true ||
                errorMessage?.contains("Failed") == true,
        )
    }

    // FIX8 P1-004-B/D: productionReporterThrowCannotPreventQuarantineOrProcessorFailureTruth.
    // A REAL production com.phillipchin.webrtctunnel.notification.NotificationController (only
    // its notifyAction lambda swapped, the same seam P0-009's
    // TunnelForegroundServiceRuntimeSafetyRecreationTest uses) is made to throw on every
    // notify call. This exercises a DIFFERENT call site than that P0-009 test (which covers
    // service-recreation/explicit-stop-failure): here the reporter throws during the
    // VerificationFailure/cleanup path's own enterNativeRuntimeQuarantine ->
    // reporter.publishErrorSafely call. publishErrorSafely's own catch must absorb the throw
    // without preventing the canonical quarantine code (set BEFORE the notification call) from
    // landing durably.
    @Test
    fun productionReporterThrowCannotPreventQuarantineOrProcessorFailureTruth() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        service.notifications =
            com.phillipchin.webrtctunnel.notification.NotificationController(
                service,
                notificationsAllowedProvider = { true },
                notifyAction = { _, _ -> error("injected notification failure") },
            )

        bridge.forceNextStatusJsonToReportError()
        bridge.failNextStop()

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        assertTrue(
            "the canonical quarantine code must land even though every notify call throws",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Error &&
                    deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )
        assertEquals(
            "the specific cleanup-failure diagnostic must still reach sticky history despite the reporter throw",
            "start_verification_cleanup_failed",
            deps.tunnelRepository.status.value.lastCleanupError?.code,
        )
        // Proves the command processor itself survived the reporter throw (not just that one
        // field landed) — a later, independent command is still accepted and processed.
        assertTrue(
            "the lifecycle command processor must still be alive after the reporter throw",
            service.submitLifecycleCommand(com.phillipchin.webrtctunnel.data.LifecycleCommand.Stop),
        )
        assertTrue(
            "the STOP submitted after the reporter throw must still be processed",
            waitForCondition { bridge.stopCalls >= 2 },
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
        val generationDuringStartup = service.lifecycleGenerationForTest

        // Queue a PAUSE command that will supersede the startup.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)
        bridge.failNextStop() // Make the PAUSE stop fail for visibility.

        // Wait for PAUSE to have actually superseded the startup before releasing it.
        // startCommand() only submits (onStartCommand hands off to the ordered queue and
        // returns), so without this the release races the supersession: the startup would
        // often observe its own generation still current, complete as a *non*-stale
        // success, and this test would silently stop covering the stale path it is named
        // for. PAUSE increments the generation before it blocks in cancelAndJoin waiting
        // on the release below, so this is observable here and cannot deadlock.
        assertTrue(
            "PAUSE must supersede the in-flight startup before it is released",
            waitForCondition { service.lifecycleGenerationForTest > generationDuringStartup },
        )

        // Release the start offer, which is now provably superseded.
        bridge.releaseBlockedStartOffer()

        // Gate on the PAUSE's observable *outcome*, not merely on stop() having been
        // entered: FailableRecordingBridge.stop() increments stopCalls on its first line
        // and only then returns the injected failure, so the Error state is published
        // strictly afterwards (through a dispatcher hop back from repository.stop()).
        // Gating on stopCalls let this assert land inside that window and read the
        // superseded startup's transient Listening state instead — the original flake.
        assertTrue(
            "PAUSE must complete and publish its stop failure",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )

        // Exactly one stop call from the PAUSE command, no extra cleanup from startup.
        // Checked only after the Error above: PAUSE performs its stop *after* joining the
        // cancelled startup, so any extra cleanup stop from that startup would already
        // have been counted by now.
        assertEquals(
            "exactly one stop call from PAUSE, no extra cleanup from stale startup",
            1,
            bridge.stopCalls,
        )

        // The superseded startup must not have retried/restarted the native runtime.
        assertEquals(
            "stale startup must not trigger an additional native start",
            1,
            bridge.startOfferCalls,
        )

        // The tunnel state must be Error (from the failed PAUSE stop).
        assertEquals(
            "PAUSE failure must leave tunnel in Error state",
            ServiceState.Error,
            deps.tunnelRepository.status.value.serviceState,
        )
    }
}
