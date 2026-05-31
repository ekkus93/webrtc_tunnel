package com.phillipchin.webrtctunnel

import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
    fun serviceStaysActiveWhenActivityBackgrounded() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
        }
        assertTrue(waitForCondition(timeoutMs = 10_000) { TestTunnelHooks.bridge.startOfferCalls >= 1 })
        assertEquals(0, TestTunnelHooks.bridge.stopCalls)
    }

    private fun stopService() {
        context.stopService(Intent(context, TunnelForegroundService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) {
                return true
            }
            SystemClock.sleep(50)
        }
        return condition()
    }
}
