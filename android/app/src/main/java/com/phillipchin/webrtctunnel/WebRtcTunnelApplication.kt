package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.notification.NotificationController

open class WebRtcTunnelApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        appDependencies = AppDependencies(this)
        NotificationController(this).ensureChannels()
        // FIX6 INV-010: initialization runs off the main thread and publishes observable
        // readiness. It previously ran here inside runBlocking — unbounded file I/O on the
        // main thread (ANR risk on slow storage) whose Result was discarded, so a failed
        // default-config write left the app running with no config and no indication.
        // Start requests are now gated on readiness instead.
        deps.appInitializationCoordinator.start()
    }
}
