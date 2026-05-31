package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
    fun startTunnel(mode: TunnelMode): Unit {
        val action = when (mode) {
            TunnelMode.Offer -> TunnelForegroundService.ACTION_START_OFFER
            TunnelMode.Answer -> return
        }
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java).setAction(action),
        )
    }

    fun stopTunnel(): Unit {
        deps.context.startService(
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_STOP),
        )
    }

    fun refresh() = deps.tunnelRepository.refreshStatus()
}

class SetupViewModel(private val deps: AppDependencies) : ViewModel() {
    fun validateConfig() = deps.tunnelRepository.validateConfig(deps.configRepository.configPath)
    fun saveConfig(contents: String) = deps.configRepository.writeConfig(contents)
}

class ForwardsViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
}

class LogsViewModel(private val deps: AppDependencies) : ViewModel() {
    fun logs(maxEvents: Int): List<LogEvent> = deps.tunnelRepository.recentLogs(maxEvents)
}

class SettingsViewModel(private val deps: AppDependencies) : ViewModel() {
    fun validateConfig() = deps.tunnelRepository.validateConfig(deps.configRepository.configPath)
}

class NetworkPolicyViewModel(private val deps: AppDependencies) : ViewModel() {
    val networkStatus = deps.networkPolicyManager.status
}

class AppViewModelFactory(private val deps: AppDependencies) {
    fun home() = HomeViewModel(deps)
    fun setup() = SetupViewModel(deps)
    fun forwards() = ForwardsViewModel(deps)
    fun logs() = LogsViewModel(deps)
    fun settings() = SettingsViewModel(deps)
    fun networkPolicy() = NetworkPolicyViewModel(deps)
}
