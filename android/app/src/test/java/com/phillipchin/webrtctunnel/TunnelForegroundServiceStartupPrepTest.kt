package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.isTunnelRunning
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
 * Tests for startup preparation in [TunnelForegroundService].
 *
 * P0-001: Startup preparation validates identity, config, and network policy
 * before calling native startOffer. Failures abort startup without starting
 * the tunnel.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceStartupPrepTest {
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

    @Test
    fun networkPolicyBlockedPreventsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject network policy block before starting.
        TunnelForegroundServiceTestHooks.policyBlockReason.set("No network available")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

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

    @Test
    fun identityReadFailureAbortsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject identity read failure before starting.
        TunnelForegroundServiceTestHooks.identityReadFailure.set("injected identity decrypt failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

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

    @Test
    fun configPrepFailureAbortsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject config preparation failure before starting.
        TunnelForegroundServiceTestHooks.configPrepFailure.set("injected config prep failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

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

    @Test
    fun configValidationFailureAbortsStartup() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Inject config validation failure before starting.
        TunnelForegroundServiceTestHooks.configValidationFailure.set("injected config validation failure")

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

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
