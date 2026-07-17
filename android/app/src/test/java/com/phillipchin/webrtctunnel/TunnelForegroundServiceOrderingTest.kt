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
import org.junit.Assert.assertNull
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

    // P0-001: handleStartupCompleted() previously invalidated the pending policy retry
    // unconditionally before the NativeFailure branch could read it, making this race
    // unreachable. These tests drive the exact race — a PolicyAllowed event arriving
    // while a startup is already in flight (activeStartup != null) — through the real
    // command queue and native-bridge fake, not through any new test-only hook.

    @Test
    fun nativeFailureConsumesPendingPolicyRetryAndResumesExactlyOnce() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        runBlocking { service.offer.pauseForPolicy("policy pause before pending-retry race") }
        assertTrue(service.pausedByPolicy.get())

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)

        // First unmetered event: activeStartup is null, so this resumes immediately —
        // block it mid-native-start so the second event below races against it.
        bridge.blockNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(
            "the immediate resume attempt must reach the blocked native start",
            bridge.awaitStartOfferEntered(5_000),
        )
        val startCallsAtRaceStart = bridge.startOfferCalls
        assertTrue(service.pausedByPolicy.get())

        // Second unmetered event while the first resume is still in flight: activeStartup
        // != null here, so this must be recorded as a pending retry, not acted on directly.
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        // Let the blocked native start fail. The NativeFailure branch must consume the
        // pending retry recorded above (rather than finding it already cleared) and
        // submit exactly one RetryPolicyResume.
        bridge.failNextStartOffer()
        bridge.releaseBlockedStartOffer()

        assertTrue(
            "the pending retry recorded during the race must trigger exactly one more native start",
            waitForCondition { bridge.startOfferCalls == startCallsAtRaceStart + 1 },
        )
        assertTrue(
            "the retried start must succeed and leave the tunnel running",
            waitForCondition { deps.tunnelRepository.status.value.serviceState.isTunnelRunning() },
        )
        assertFalse(service.pausedByPolicy.get())

        // Give a spurious extra retry a chance to fire before asserting the final count —
        // proves the retry ran exactly once, not repeatedly.
        Thread.sleep(200)
        assertEquals(startCallsAtRaceStart + 1, bridge.startOfferCalls)
    }

    @Test
    fun nativeFailureWithoutPendingRetryPublishesFailure() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        bridge.failNextStartOffer()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        assertTrue(
            "a native failure with no pending retry must publish an error",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )
        assertEquals(
            "native_start_failed",
            deps.tunnelRepository.status.value.lastError?.code,
        )

        Thread.sleep(200)
        assertEquals(
            "no pending retry means no automatic retry attempt",
            1,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun nativeFailurePendingRetryWithoutPausedByPolicyDoesNotResume() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        runBlocking {
            deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true))
        }

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        runBlocking { service.offer.pauseForPolicy("policy pause before stale-pause race") }
        assertTrue(service.pausedByPolicy.get())

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)

        bridge.blockNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(bridge.awaitStartOfferEntered(5_000))
        val startCallsAtRaceStart = bridge.startOfferCalls

        // Records the pending retry while activeStartup is still in flight, exactly as in
        // the resume-succeeds test above.
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        // Unlike the resume-succeeds test: pausedByPolicy flips false before the native
        // failure is processed (e.g. some other path already resumed/cleared it). Per the
        // Fix 5 review, the pending generation match alone must not be sufficient to
        // resume — pausedByPolicy must also still be true at completion time.
        service.pausedByPolicy.set(false)

        bridge.failNextStartOffer()
        bridge.releaseBlockedStartOffer()

        assertTrue(
            "a native failure must publish an error when pausedByPolicy no longer holds",
            waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error },
        )

        Thread.sleep(200)
        assertEquals(
            "pending retry without pausedByPolicy must not trigger an automatic retry",
            startCallsAtRaceStart,
            bridge.startOfferCalls,
        )
    }

    // P1-002: handlePolicyAllowed() preference-read failure must publish a visible
    // diagnostic (not just invalidate the pending retry silently), and cancellation
    // must propagate rather than being treated as a normal failure.

    @Test
    fun policyAllowedPreferenceReadFailurePublishesVisibleDiagnosticAndDoesNotResume() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        runBlocking { service.offer.pauseForPolicy("policy pause before preference-read failure") }
        assertTrue(service.pausedByPolicy.get())
        val startCallsBeforeFailure = bridge.startOfferCalls

        // Skip 1 read: the network monitor loop's own preferences.first() call (to
        // evaluate policy before it decides to submit PolicyAllowed) must succeed so the
        // failure below actually targets handlePolicyAllowed()'s own read.
        TunnelForegroundServiceTestHooks.preferenceReadInterceptSkipCount.set(1)
        TunnelForegroundServiceTestHooks.preferenceReadFailure.set("preferences datastore unavailable")

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "preference-read failure must publish a visible diagnostic error code",
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "policy_allowed_preference_read_failed"
            },
        )

        Thread.sleep(200)
        assertEquals(
            "preference-read failure must not trigger a resume/native start",
            startCallsBeforeFailure,
            bridge.startOfferCalls,
        )
        assertFalse(
            "the tunnel must not end up running after a preference-read failure",
            bridge.state.isTunnelRunning(),
        )
    }

    @Test
    fun policyAllowedPreferenceReadCancellationDoesNotPublishFailureDiagnostic() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        runBlocking { service.offer.pauseForPolicy("policy pause before preference-read cancellation") }
        assertTrue(service.pausedByPolicy.get())
        val startCallsBeforeCancellation = bridge.startOfferCalls

        // Skip 1 read: the network monitor loop's own preferences.first() call must
        // succeed so the cancellation below actually targets handlePolicyAllowed()'s read.
        TunnelForegroundServiceTestHooks.preferenceReadInterceptSkipCount.set(1)
        TunnelForegroundServiceTestHooks.preferenceReadCancels.set(true)

        val connectivityManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(ConnectivityManager::class.java)
        val shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        val network = ShadowNetwork.newInstance(1)
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        // Give the cancellation time to propagate; there is nothing to waitForCondition on
        // since a genuinely propagated cancellation produces no diagnostic at all.
        Thread.sleep(300)

        assertFalse(
            "cancellation must not be reported through the preference-read-failure " +
                "diagnostic path — it must propagate, not be converted into a failure",
            deps.tunnelRepository.status.value.lastError?.code == "policy_allowed_preference_read_failed",
        )
        assertEquals(
            "a cancelled preference read must not trigger a resume/native start",
            startCallsBeforeCancellation,
            bridge.startOfferCalls,
        )
    }

    // FIX6 P0-004 / INV-006. The preference-read cancellation case
    // (preferenceReadCancellationStillPropagates) is already covered above by
    // policyAllowedPreferenceReadCancellationDoesNotPublishFailureDiagnostic.

    // Drives a pending policy retry into existence: pause by policy, then two unmetered
    // events while a resume start is blocked mid-flight so the second records a pending
    // token. Returns the native start count captured with the token pending.
    private fun arrangePendingPolicyRetry(
        deps: com.phillipchin.webrtctunnel.data.AppDependencies,
        bridge: com.phillipchin.webrtctunnel.FailableRecordingBridge,
        shadowConnectivityManager: org.robolectric.shadows.ShadowConnectivityManager,
        network: android.net.Network,
    ): Int {
        runBlocking { deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = true)) }
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        runBlocking { service.offer.pauseForPolicy("policy pause before pending-retry setup") }
        assertTrue(service.pausedByPolicy.get())

        bridge.blockNextStartOffer()
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(bridge.awaitStartOfferEntered(5_000))
        val startCallsWithPending = bridge.startOfferCalls

        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(
            "a pending retry must have been recorded while the resume start is in flight",
            waitForCondition { service.pendingPolicyResumeGenerationForTest != null },
        )
        return startCallsWithPending
    }

    @Test
    fun pendingRetryIsInvalidatedWhenResumeOnUnmeteredTurnsFalse() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val shadowConnectivityManager =
            Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getSystemService(ConnectivityManager::class.java),
            )
        val network = ShadowNetwork.newInstance(1)

        arrangePendingPolicyRetry(deps, bridge, shadowConnectivityManager, network)

        // The user turns auto-resume off after the token was recorded under true.
        runBlocking { deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = false)) }
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }

        assertTrue(
            "a token recorded under resumeOnUnmetered=true must be invalidated once it is false",
            waitForCondition { service.pendingPolicyResumeGenerationForTest == null },
        )

        // Clean up the still-blocked resume start.
        bridge.failNextStartOffer()
        bridge.releaseBlockedStartOffer()
    }

    @Test
    fun nativeFailureAfterPreferenceTurnsFalseDoesNotResume() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        val shadowConnectivityManager =
            Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getSystemService(ConnectivityManager::class.java),
            )
        val network = ShadowNetwork.newInstance(1)

        val startCallsWithPending = arrangePendingPolicyRetry(deps, bridge, shadowConnectivityManager, network)

        // Preference flips false and a PolicyAllowed invalidates the pending token.
        runBlocking { deps.configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = false)) }
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(network) }
        assertTrue(waitForCondition { service.pendingPolicyResumeGenerationForTest == null })

        // The in-flight start now fails. With no pending token, NativeFailure must publish a
        // failure and must NOT resume — the review's race (stale token resuming against the
        // user's new preference) is closed.
        bridge.failNextStartOffer()
        bridge.releaseBlockedStartOffer()

        assertTrue(
            "native failure with an invalidated token must publish, not resume",
            waitForCondition { deps.tunnelRepository.status.value.lastError?.code == "native_start_failed" },
        )
        assertEquals(
            "no resume may occur after the token was invalidated by the false preference",
            startCallsWithPending,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun policyAllowedDuringRuntimeQuarantinePublishesVisibleError() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        // A failed STOP quarantines the runtime (nativeRuntimeUncertain) and keeps the
        // service alive and foreground.
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })

        val shadowConnectivityManager =
            Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getSystemService(ConnectivityManager::class.java),
            )
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(ShadowNetwork.newInstance(1)) }

        assertTrue(
            "policy-allowed during quarantine must publish native_runtime_quarantined, not silently drop",
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )
    }

    @Test
    fun policyAllowedDuringRuntimeQuarantineClearsPendingRetry() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(waitForCondition { bridge.state == ServiceState.Connected })
        bridge.failNextStop()
        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(waitForCondition { deps.tunnelRepository.status.value.serviceState == ServiceState.Error })

        val startCallsBeforeQuarantinedEvent = bridge.startOfferCalls
        val shadowConnectivityManager =
            Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getSystemService(ConnectivityManager::class.java),
            )
        shadowConnectivityManager.networkCallbacks.forEach { it.onAvailable(ShadowNetwork.newInstance(1)) }
        // Every quarantine-setting path (failed stop/pause) already invalidates the token, so
        // a token cannot naturally survive into quarantine. This proves the handler's
        // quarantine branch leaves no pending token and never resumes while quarantined.
        assertTrue(
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "native_runtime_quarantined"
            },
        )
        assertNull(
            "the quarantine path must leave no pending policy retry",
            service.pendingPolicyResumeGenerationForTest,
        )
        assertEquals(
            "quarantine must not resume the native runtime",
            startCallsBeforeQuarantinedEvent,
            bridge.startOfferCalls,
        )
    }
}
