package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.notification.NotificationController
import kotlinx.coroutines.runBlocking

open class WebRtcTunnelApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        appDependencies = AppDependencies(this)
        NotificationController(this).ensureChannels()
        runBlocking {
            deps.configRepository.ensureDefaultConfig(deps.configRepository.defaultConfigTemplate)
        }
    }
}
