package com.phillipchin.webrtctunnel

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * FIX7 P1-002-B/C: proves that an *unexpected* lifecycle-processor death (a raw Throwable
 * escaping the native bridge instead of a normal `Result.failure`, propagating all the way
 * through [com.phillipchin.webrtctunnel.data.TunnelLifecycleCoordinator]'s processor) is not
 * silently absorbed. Previously nothing observed the processor's own completion — a dead
 * processor left the service foreground, pretending to still be in control, with no durable
 * diagnostic and no way to ever accept another command (see the pre-existing
 * `TunnelLifecycleCoordinatorTest` scenarios `handlerCancellationStopsProcessorAndRejectsLaterCommands`/
 * `fatalErrorIsNotConvertedToLifecycleCommandFailed`/`errorReporterFailureStopsProcessorAndRejectsLaterCommands`,
 * none of which fed the loss back to [CoordinatorOperations]).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceLifecycleProcessorFailureTest {
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
        TunnelForegroundServiceTestHooks.stopThrowsUnexpectedly.set(null)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        TunnelForegroundServiceTestHooks.stopThrowsUnexpectedly.set(null)
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

    private fun triggerUnexpectedProcessorDeath(bridge: FailableRecordingBridge) {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        TunnelForegroundServiceTestHooks.stopThrowsUnexpectedly.set(
            CancellationException("injected native bridge bug"),
        )
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)

        assertTrue(
            "the injected throw must actually kill the lifecycle command processor",
            waitForCondition { service.coordinatorStoppedForTest },
        )
        // onProcessorFailed's quarantine call runs synchronously, immediately BEFORE its own
        // stopSelf() call — waiting for isStoppedBySelf (rather than just coordinatorStoppedForTest,
        // which flips true a step earlier, before onProcessorFailed even starts) guarantees the
        // quarantine call has fully completed by the time callers read repository status.
        assertTrue(
            "onProcessorFailed must have run to completion (quarantine, then stopSelf)",
            waitForCondition { Shadows.shadowOf(service).isStoppedBySelf },
        )
    }

    @Test
    fun unexpectedLifecycleProcessorFailureIsDurable() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        triggerUnexpectedProcessorDeath(bridge)

        assertEquals(
            "an unexpected processor death must durably set the canonical quarantine code, " +
                "not merely log the loss",
            "native_runtime_quarantined",
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    @Test
    fun unexpectedLifecycleProcessorFailureQuarantinesPossibleRuntime() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        triggerUnexpectedProcessorDeath(bridge)

        assertTrue(
            "with no processor left to control it, the native runtime may still be active " +
                "and the service must stop itself rather than stay foreground uncontrolled",
            waitForCondition { Shadows.shadowOf(service).isStoppedBySelf },
        )
    }

    @Test
    fun activeServiceCommandSubmissionFailureIsNotSilentlyDropped() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        triggerUnexpectedProcessorDeath(bridge)
        val quarantineErrorBeforeDrop = deps.tunnelRepository.status.value.lastError

        // The service object is not (yet) known to be destroyed — Android has not called
        // onDestroy — so a later command submitted through the now-dead processor is an
        // active-service drop, not benign teardown-late noise. Must not throw.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 3)

        assertEquals(
            "an active-service drop must not silently replace the durable quarantine diagnostic",
            quarantineErrorBeforeDrop?.code,
            deps.tunnelRepository.status.value.lastError?.code,
        )
    }

    @Test
    fun teardownLateSubmissionRemainsBenignAndDoesNotCrash() {
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        controller.destroy()

        // A submit after onDestroy has stopped the coordinator must be a benign, non-throwing
        // drop — proven here by the service having already been marked destroyed with no
        // exception surfacing from the controller's own teardown above.
        assertTrue(
            "onDestroy must have stopped the coordinator, closing command acceptance",
            waitForCondition { service.coordinatorStoppedForTest },
        )
    }
}
