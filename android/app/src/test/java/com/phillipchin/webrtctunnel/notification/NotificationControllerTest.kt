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
    fun buildStatusNotificationUsesExplicitPerStateTitles() {
        fun title(state: ServiceState) =
            controller.buildStatusNotification(state, "body").extras.getString("android.title")

        assertEquals("WebRTC Tunnel stopped", title(ServiceState.Stopped))
        assertEquals("WebRTC Tunnel starting", title(ServiceState.Starting))
        assertEquals("WebRTC Tunnel starting", title(ServiceState.Connecting))
        assertEquals("WebRTC Tunnel running", title(ServiceState.Listening))
        assertEquals("WebRTC Tunnel running", title(ServiceState.Serving))
        assertEquals("WebRTC Tunnel connected", title(ServiceState.Connected))
        assertEquals("WebRTC Tunnel paused", title(ServiceState.PausedMeteredBlocked))
        assertEquals("WebRTC Tunnel paused", title(ServiceState.NoNetwork))
        assertEquals("WebRTC Tunnel stopping", title(ServiceState.Stopping))
        assertEquals("WebRTC Tunnel error", title(ServiceState.Error))
        assertEquals("WebRTC Tunnel error", title(ServiceState.ConfigInvalid))
        assertNotNull(controller.buildStatusNotification(ServiceState.Connected, "body").contentIntent)
    }

    @Test
    fun showSkipsWhenPermissionDenied() {
        var notified = false
        val deniedController =
            NotificationController(
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
        val allowedController =
            NotificationController(
                context = context,
                notificationsAllowedProvider = { true },
                notifyAction = { _, _ -> count += 1 },
            )
        allowedController.show(controller.buildStatusNotification(ServiceState.Connected, "ok"))
        assertEquals(1, count)
    }

    @Test
    fun showSwallowsNotifyFailure() {
        val failingController =
            NotificationController(
                context = context,
                notificationsAllowedProvider = { true },
                notifyAction = { _, _ -> error("boom") },
            )
        failingController.show(controller.buildStatusNotification(ServiceState.Connected, "ok"))
    }

    @Test
    fun showPostsOnApiBelow33WithoutRequiringRuntimePermission() {
        var count = 0
        val preTiramisuController =
            NotificationController(
                context = context,
                sdkIntProvider = { 30 },
                notifyAction = { _, _ -> count += 1 },
            )
        preTiramisuController.show(controller.buildStatusNotification(ServiceState.Connected, "ok"))
        assertEquals(1, count)
    }
}
