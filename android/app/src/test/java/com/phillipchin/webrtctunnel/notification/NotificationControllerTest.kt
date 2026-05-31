package com.phillipchin.webrtctunnel.notification

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationControllerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var controller: NotificationController

    @Before
    fun setUp() {
        controller = NotificationController(context)
    }

    @Test
    fun ensureChannelsCreatesExpectedChannelsAndIsIdempotent() {
        controller.ensureChannels()
        controller.ensureChannels()
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager.getNotificationChannel(NotificationController.CHANNEL_STATUS))
        assertNotNull(manager.getNotificationChannel(NotificationController.CHANNEL_ERRORS))
    }

    @Test
    fun buildStatusNotificationUsesExpectedTitles() {
        val paused = controller.buildStatusNotification(ServiceState.PausedMeteredBlocked, "body")
        val error = controller.buildStatusNotification(ServiceState.Error, "body")
        val running = controller.buildStatusNotification(ServiceState.Connected, "body")
        assertEquals("WebRTC Tunnel paused", paused.extras.getString("android.title"))
        assertEquals("WebRTC Tunnel error", error.extras.getString("android.title"))
        assertEquals("WebRTC Tunnel running", running.extras.getString("android.title"))
        assertNotNull(running.contentIntent)
    }

    @Test
    fun showSkipsWhenPermissionDenied() {
        var notified = false
        val deniedController = NotificationController(
            context = context,
            notificationsAllowedProvider = { false },
            notifyAction = { _, _ -> notified = true },
        )
        deniedController.show(controller.buildStatusNotification(ServiceState.Connected, "ok"))
        assertTrue(!notified)
    }

    @Test
    fun showCallsNotifyWhenAllowed() {
        var count = 0
        val allowedController = NotificationController(
            context = context,
            notificationsAllowedProvider = { true },
            notifyAction = { _, _ -> count += 1 },
        )
        allowedController.show(controller.buildStatusNotification(ServiceState.Connected, "ok"))
        assertEquals(1, count)
    }

    @Test
    fun showSwallowsNotifyFailure() {
        val failingController = NotificationController(
            context = context,
            notificationsAllowedProvider = { true },
            notifyAction = { _, _ -> error("boom") },
        )
        failingController.show(controller.buildStatusNotification(ServiceState.Connected, "ok"))
    }
}
