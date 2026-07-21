package com.phillipchin.webrtctunnel

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.isTunnelRunning
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
 * P0-002: Tests for pending retry invalidation.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class PendingRetryInvalidationTest {
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
     * P0-002: pending retry then Destroy does not restart.
     *
     * Establishes a genuine pending policy retry — a PolicyAllowed event arriving while
     * a startup is already in flight (the same race P0-001 fixes the resume side of) —
     * then destroys the service while that startup is still unresolved. Proves destroy's
     * invalidation wins the race: no extra native start ever occurs, and a late trigger
     * that would otherwise have consumed the pending retry does nothing.
     */
    @Test
    fun pendingRetryThenDestroyDoesNotRestart() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        runBlocking { service.offer.pauseForPolicy("policy pause before destroy race") }
        assertTrue(service.pausedByPolicy.get())

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)

        // First event resumes immediately (activeStartup was null); block it mid-native-start.
        bridge.blockNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(bridge.awaitStartOfferEntered(5_000))
        val startCallsBeforeDestroy = bridge.startOfferCalls

        // Second event while the resume is still in flight: this is what would become a
        // pending retry (consumed by the NativeFailure branch, per P0-001) if destroy did
        // not win the race.
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        controller.destroy()
        // Unblock the in-flight native start immediately (rather than waiting out the
        // fake bridge's internal 5s block timeout) so destroy's cancelAndJoin resolves
        // quickly; the coroutine notices cancellation and unwinds without ever posting
        // StartupCompleted, so this does not itself consume the pending retry.
        bridge.releaseBlockedStartOffer()

        assertTrue(
            "destroy's fallback cleanup stop must complete",
            waitForCondition { bridge.stopCalls >= 1 },
        )

        // P2-001: destroy cancels-and-joins the network monitor and then stops the command
        // processor. Wait for that deterministic exit (the processor is stopped) instead of a
        // fixed sleep — by then the monitor is cancelled and command acceptance is closed, so a
        // late trigger has no path left to resume.
        assertTrue(
            "destroy must stop the command processor",
            waitForCondition { service.coordinatorStoppedForTest },
        )
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        // The destroy fallback stop is destroy's terminal action, so the state converges to
        // not-running; wait for that convergence rather than sampling a transient in-flight
        // value. Once it holds, no further start can occur (processor stopped, monitor cancelled).
        assertTrue(
            "the service must not end up running after destroy",
            waitForCondition { !bridge.state.isTunnelRunning() },
        )
        assertEquals(
            "no native start may occur once destroy has invalidated the pending retry",
            startCallsBeforeDestroy,
            bridge.startOfferCalls,
        )
    }

    /**
     * P0-002: pending retry then explicit Pause does not restart.
     *
     * Simulates a pending policy retry, then explicitly pauses.
     * The retry must not fire.
     */
    @Test
    fun pendingRetryThenPauseDoesNotRestart() {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Explicit pause should invalidate any pending retry.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.stopCalls >= 1 })

        // Verify the pause completed and no restart occurred.
        assertFalse(
            "pause after pending retry should leave tunnel stopped, not running",
            TunnelForegroundServiceTestHooks.bridge.state.isTunnelRunning(),
        )
    }

    /**
     * P0-002: pending retry then explicit Stop does not restart.
     *
     * Simulates a pending policy retry, then explicitly stops.
     * The retry must not fire.
     */
    @Test
    fun pendingRetryThenStopDoesNotRestart() {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Explicit stop should invalidate any pending retry.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.stopCalls >= 1 })

        // Verify the stop completed.
        assertFalse(
            "stop after pending retry should leave tunnel stopped, not running",
            TunnelForegroundServiceTestHooks.bridge.state.isTunnelRunning(),
        )
    }

    /**
     * P0-002: new StartOffer invalidates pending retry.
     *
     * When a new StartOffer is submitted, any pending retry generation should be
     * invalidated to prevent concurrent startup attempts.
     */
    @Test
    fun pendingRetryThenNewStartOfferInvalidatesOldRetry() {
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { TunnelForegroundServiceTestHooks.bridge.state == ServiceState.Connected })

        // Second start should be blocked by the already-running state.
        val initialStartCount = TunnelForegroundServiceTestHooks.bridge.startOfferCalls
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)

        // The second start should not trigger an additional native start.
        assertEquals(
            "duplicate start should not trigger additional native start",
            initialStartCount,
            TunnelForegroundServiceTestHooks.bridge.startOfferCalls,
        )
    }

    /**
     * P0-002: valid retry while policy-paused runs exactly once.
     *
     * When the retry is valid (policy-paused, matching generation), it should resume
     * exactly once, not loop indefinitely.
     */
    @Test
    fun validRetryWhilePolicyPausedRunsExactlyOnce() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Start and connect.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // Policy pause.
        runBlocking { service.offer.pauseForPolicy("policy pause for retry test") }
        assertTrue(service.pausedByPolicy.get())

        // The tunnel must be paused.
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        // Resume should succeed exactly once.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_RESUME)).startCommand(0, 2)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        // Verify exactly one native start occurred after resume.
        val resumeStartCount = bridge.startOfferCalls
        assertEquals("resume should trigger exactly one start after policy pause", 2, resumeStartCount)
    }
}
