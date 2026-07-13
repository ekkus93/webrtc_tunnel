package com.phillipchin.webrtctunnel

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
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
 * Tests for command ordering and auto-resume behavior in [TunnelForegroundService].
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TunnelForegroundServiceTestApplication::class)
class TunnelForegroundServiceOrderingTest {
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
    fun startThenPauseOrderingPreserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // Submit START, then immediately submit PAUSE.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        // Wait for PAUSE to complete. The final state should be paused/stopped, not running.
        assertTrue(waitForCondition { bridge.stopCalls >= 1 })
        assertTrue(
            "PAUSE after START must result in stopped state, not running",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped ||
                    deps.tunnelRepository.status.value.serviceState == ServiceState.Error
            },
        )
        assertFalse(
            "START then PAUSE must not leave the tunnel running",
            deps.tunnelRepository.status.value.serviceState.isTunnelRunning(),
        )
    }

    @Test
    fun pauseThenStartOrderingPreserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Submit PAUSE (tunnel is stopped, so PAUSE is a no-op), then START.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 1)
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)

        // Wait for START to complete.
        assertTrue(
            "PAUSE then START must result in active-or-starting state",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState.isTunnelActiveOrStarting()
            },
        )
    }

    @Test
    fun allowMeteredThenPauseOrderingPreserved() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        // Submit ALLOW_METERED_SESSION, then immediately PAUSE.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_ALLOW_METERED_SESSION)).startCommand(0, 1)
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_PAUSE)).startCommand(0, 2)

        // Wait for PAUSE to complete. The tunnel should be stopped.
        assertTrue(
            "PAUSE after ALLOW_METERED must result in stopped state",
            waitForCondition {
                deps.tunnelRepository.status.value.serviceState == ServiceState.Stopped ||
                    deps.tunnelRepository.status.value.serviceState == ServiceState.Error
            },
        )
        assertFalse(
            "ALLOW_METERED then PAUSE must not leave the tunnel running",
            deps.tunnelRepository.status.value.serviceState.isTunnelRunning(),
        )
    }

    @Test
    fun autoResumeOnUnmeteredSeesLatestPausedByPolicyAcrossThreads() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // The fake NetworkPolicyManager always reports UnmeteredWifi; every other test in
        // this file pins resumeOnUnmetered = false to avoid racing that against direct
        // pauseForPolicy() calls (see TunnelForegroundServiceTestFakes.kt). This test wants
        // exactly that race, deliberately: it enables auto-resume, then proves the
        // networkMonitorJob coroutine (running on this service's real IO dispatcher, a
        // different JVM thread than this JUnit test thread) observes a pausedByPolicy value
        // written from the test thread — a genuine cross-thread write/read pair, not just a
        // same-thread sanity check (P1-004).
        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        runBlocking { service.offer.pauseForPolicy("policy pause before auto-resume check") }
        assertTrue(service.pausedByPolicy.get())
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        // NetworkPolicyManager.monitor()'s flow already emitted once during onCreate() (before
        // this test ever paused), so re-triggering the real ConnectivityManager.NetworkCallback
        // is the only way to make the auto-resume check in onCreate() run again and actually
        // observe the pausedByPolicy write above.
        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "auto-resume must observe the pausedByPolicy write made from the test thread",
            waitForCondition { !service.pausedByPolicy.get() },
        )
        assertTrue(waitForCondition { bridge.startOfferCalls >= 2 })
    }

    @Test
    fun failedAutoResumeLeavesPausedByPolicyTrueForNextRetry() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })

        runBlocking { service.offer.pauseForPolicy("policy pause before failed auto-resume") }
        assertTrue(service.pausedByPolicy.get())
        assertEquals(ServiceState.PausedMeteredBlocked, deps.tunnelRepository.status.value.serviceState)

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)

        // First unmetered event: resume is attempted but the native start fails.
        bridge.failNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "a failed resume attempt must be reported as an error",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertTrue(
            "a failed resume attempt must not clear the policy-pause retry flag",
            service.pausedByPolicy.get(),
        )

        // Second unmetered event: the flag is still true, so this retries with exactly one event.
        // The one-event invariant (P0-004): one later PolicyAllowed event is sufficient; no loop needed.
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "one unmetered event must trigger a successful retry start",
            waitForCondition { bridge.state == ServiceState.Connected },
        )
        assertTrue(
            "the retry flag must clear only once resume actually succeeds",
            waitForCondition { !service.pausedByPolicy.get() },
        )
        assertTrue(
            "a successful resume must leave the tunnel running, not paused/errored",
            waitForCondition { deps.tunnelRepository.status.value.serviceState.isTunnelRunning() },
        )
    }
}
