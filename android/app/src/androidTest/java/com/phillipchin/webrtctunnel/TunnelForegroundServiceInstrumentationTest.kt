package com.phillipchin.webrtctunnel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TunnelForegroundServiceInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        stopService()
        TestTunnelHooks.bridge.reset()
    }

    @After
    fun tearDown() {
        stopService()
        TestTunnelHooks.bridge.reset()
    }

    @Test
    fun startOfferActionStartsOfferPath() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(waitForCondition(timeoutMs = 10_000) { TestTunnelHooks.bridge.startOfferCalls >= 1 })
    }

    @Test
    fun stopActionStopsTunnel() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        assertTrue(waitForCondition(timeoutMs = 3_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
    }

    @Test
    fun duplicateStopIsSafe() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        assertTrue(waitForCondition(timeoutMs = 5_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
    }

    @Test
    fun serviceStaysActiveWhenActivityBackgrounded() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
        }
        assertTrue(waitForCondition(timeoutMs = 10_000) { TestTunnelHooks.bridge.startOfferCalls >= 1 })
    }

    @Test
    fun duplicateStartDoesNotLaunchDuplicateRuntime() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(waitForCondition(timeoutMs = 10_000) { TestTunnelHooks.bridge.startOfferCalls == 1 })
    }

    @Test
    fun stopDuringPendingStartIsSafe() {
        TestTunnelHooks.bridge.blockNextStartOffer()
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(TestTunnelHooks.bridge.awaitStartOfferEntered(10_000))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        assertTrue(waitForCondition(timeoutMs = 8_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
        TestTunnelHooks.bridge.releaseBlockedStartOffer()
        assertEquals(
            com.phillipchin.webrtctunnel.model.ServiceState.Stopped,
            (context.applicationContext as HasAppDependencies).deps.tunnelRepository.status.value.serviceState,
        )
    }

    @Test
    fun stopBeforeNativeStartSkipsNativeStartCall() {
        TestTunnelHooks.bridge.blockNextValidation()
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(TestTunnelHooks.bridge.awaitValidationEntered(5_000))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        assertTrue(waitForCondition(timeoutMs = 8_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
        TestTunnelHooks.bridge.releaseBlockedValidation()
        assertEquals(0, TestTunnelHooks.bridge.startOfferEnterCalls)
        assertEquals(0, TestTunnelHooks.bridge.startOfferCalls)
    }

    @Test
    fun pauseDuringPendingStartIsSafe() {
        TestTunnelHooks.bridge.blockNextStartOffer()
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(TestTunnelHooks.bridge.awaitStartOfferEntered(10_000))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_PAUSE))
        assertTrue(waitForCondition(timeoutMs = 8_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
        TestTunnelHooks.bridge.releaseBlockedStartOffer()
        assertEquals(
            com.phillipchin.webrtctunnel.model.ServiceState.Stopped,
            (context.applicationContext as HasAppDependencies).deps.tunnelRepository.status.value.serviceState,
        )
    }

    @Test
    fun pauseBeforeNativeStartSkipsNativeStartCall() {
        TestTunnelHooks.bridge.blockNextValidation()
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(TestTunnelHooks.bridge.awaitValidationEntered(5_000))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_PAUSE))
        assertTrue(waitForCondition(timeoutMs = 8_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
        TestTunnelHooks.bridge.releaseBlockedValidation()
        assertEquals(0, TestTunnelHooks.bridge.startOfferEnterCalls)
        assertEquals(0, TestTunnelHooks.bridge.startOfferCalls)
    }

    @Test
    fun startStopStartWorks() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(waitForCondition(timeoutMs = 5_000) { TestTunnelHooks.bridge.startOfferCalls >= 1 })
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        assertTrue(waitForCondition(timeoutMs = 5_000) { TestTunnelHooks.bridge.stopCalls >= 1 })
        stopService()
        assertTrue(waitForCondition(timeoutMs = 5_000) {
            (context.applicationContext as HasAppDependencies).deps.tunnelRepository.status.value.serviceState ==
                com.phillipchin.webrtctunnel.model.ServiceState.Stopped
        })
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        assertTrue(waitForCondition(timeoutMs = 5_000) { TestTunnelHooks.bridge.startOfferCalls >= 2 })
    }

    @Test
    fun answerActionIsExplicitlyDisabled() {
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_ANSWER))
        assertTrue(waitForCondition(timeoutMs = 5_000) { TestTunnelHooks.bridge.startAnswerCalls == 0 })
    }

    @Test
    fun rustBridgeDisposeIsIdempotentAndRejectsCallsAfterDispose() {
        val bridge = RustTunnelBridge()
        bridge.stop()
        bridge.dispose()
        bridge.dispose()
        val result = bridge.startOffer("/does/not/matter")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("disposed") == true)
    }

    private fun stopService() {
        context.stopService(Intent(context, TunnelForegroundService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) {
                return true
            }
            Thread.yield()
        }
        return condition()
    }
}
