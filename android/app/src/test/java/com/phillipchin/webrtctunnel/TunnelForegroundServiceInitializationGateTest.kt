package com.phillipchin.webrtctunnel

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An application whose default-config creation always fails, so readiness settles on
 * [com.phillipchin.webrtctunnel.data.AppInitializationState.Failed].
 *
 * A separate application (rather than a hook toggled in `@Before`) because Robolectric
 * builds the Application when the controller's field initializer first touches the
 * context — before `@Before` could set anything.
 */
class FailedInitTestApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        val bridge = FailableRecordingBridge()
        TunnelForegroundServiceTestHooks.bridge = bridge
        appDependencies =
            AppDependencies(
                context = this,
                nativeBridgeFactory = { bridge },
                configRepository =
                    object : ConfigRepository(this) {
                        override suspend fun ensureDefaultConfig(contents: String): Result<Unit> =
                            Result.failure(IOException("disk full password=sentinel"))
                    },
                networkPolicyManager =
                    NetworkPolicyManager {
                        com.phillipchin.webrtctunnel.model.NetworkType.UnmeteredWifi to false
                    },
            )
        kotlinx.coroutines.runBlocking { appDependencies.appInitializationCoordinator.initialize() }
    }
}

/**
 * FIX6 P1-003-B / INV-010: a start request must fail visibly and perform no native call
 * while application initialization has not succeeded.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FailedInitTestApplication::class)
class TunnelForegroundServiceInitializationGateTest {
    private val controller =
        ServiceController.of(
            realIoService(),
            Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java),
        )
    private lateinit var service: TunnelForegroundService

    @Before
    fun setUp() {
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

    @Test
    fun startAfterInitializationFailurePublishesVisibleErrorAndDoesNotCallNative() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        assertTrue(
            "a start blocked by failed initialization must be visible, not silent",
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "config_initialization_failed"
            },
        )
        assertEquals(
            "initialization failure must not attempt a native start",
            0,
            bridge.startOfferCalls,
        )
        assertFalse(deps.tunnelRepository.status.value.serviceState.isTunnelActiveOrStarting())
    }

    @Test
    fun initializationFailureMessageReachingTheUiIsRedacted() {
        val deps = (service.applicationContext as HasAppDependencies).deps

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        assertTrue(
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "config_initialization_failed"
            },
        )
        val message = deps.tunnelRepository.status.value.lastError?.message.orEmpty()
        assertFalse("a raw secret must not reach the visible error", message.contains("sentinel"))
    }

    // FIX7 P1-003-C: the Failed state must be durable — a second start attempt after the
    // first must still be blocked and visible, not merely the first one (proving Failed
    // doesn't clear itself, unlike a transient/one-shot rejection).
    @Test
    fun startAfterFailedInitializationIsDurableAndVisible() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)
        assertTrue(
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "config_initialization_failed"
            },
        )
        assertEquals(0, bridge.startOfferCalls)

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 2)
        assertTrue(
            "a second start attempt after Failed must still be blocked and visible",
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "config_initialization_failed"
            },
        )
        assertEquals(
            "the durable Failed state must still refuse a native call on a later attempt",
            0,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun resumeAfterInitializationFailureAlsoRefusesWithoutNativeCall() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_RESUME)).startCommand(0, 1)

        assertTrue(
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "config_initialization_failed"
            },
        )
        assertEquals(0, bridge.startOfferCalls)
        assertEquals(ServiceState.Error, deps.tunnelRepository.status.value.serviceState)
    }
}
