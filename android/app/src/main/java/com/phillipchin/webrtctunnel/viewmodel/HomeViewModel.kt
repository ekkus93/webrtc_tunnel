package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
    private val _configuredForwards = MutableStateFlow(deps.forwardsStore.loadForwards())
    val configuredForwards: StateFlow<List<ForwardConfig>> = _configuredForwards.asStateFlow()

    fun startTunnel(mode: TunnelMode) {
        val action =
            when (mode) {
                TunnelMode.Offer -> TunnelForegroundService.ACTION_START_OFFER
                TunnelMode.Answer -> return
            }
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java).setAction(action),
        )
    }

    fun stopTunnel() {
        deps.context.startService(
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_STOP),
        )
    }

    fun allowMeteredTemporarily() {
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_ALLOW_METERED_SESSION),
        )
    }

    fun refresh() = deps.tunnelRepository.refreshStatus()

    fun refreshForwards() {
        _configuredForwards.value = deps.forwardsStore.loadForwards()
    }
}
