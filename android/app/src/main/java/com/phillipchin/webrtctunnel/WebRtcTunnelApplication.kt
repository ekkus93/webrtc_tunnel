package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.notification.NotificationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class WebRtcTunnelApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        appDependencies = AppDependencies(this)
        NotificationController(this).ensureChannels()
        appScope.launch {
            deps.configRepository.ensureDefaultConfig(deps.configRepository.defaultConfigTemplate())
        }
    }
}
