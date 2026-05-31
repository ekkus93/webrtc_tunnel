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
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, TestTunnelHooks.bridge.startOfferCalls)
    }

    @Test
    fun startAnswerActionStartsAnswerPath() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_ANSWER))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, TestTunnelHooks.bridge.startAnswerCalls)
    }

    @Test
    fun stopActionStopsTunnel() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        context.startService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_STOP))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(TestTunnelHooks.bridge.stopCalls >= 1)
    }

    @Test
    fun serviceStaysActiveWhenActivityBackgrounded() {
        context.startForegroundService(Intent(context, TunnelForegroundService::class.java).setAction(TunnelForegroundService.ACTION_START_OFFER))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, TestTunnelHooks.bridge.startOfferCalls)
        assertEquals(0, TestTunnelHooks.bridge.stopCalls)
    }

    private fun stopService() {
        context.stopService(Intent(context, TunnelForegroundService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
