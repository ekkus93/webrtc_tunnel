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
        assertEquals(
            "verification failure must not trigger policy retry",
            1,
            bridge.startOfferCalls,
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
}
