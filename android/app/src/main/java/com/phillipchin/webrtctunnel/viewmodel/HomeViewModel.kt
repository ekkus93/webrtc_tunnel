package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status

    // Observe the shared forwards source of truth so Home reflects edits made elsewhere.
    val configuredForwards: StateFlow<List<ForwardConfig>> = deps.forwardsRepository.forwards

    init {
        // FIX7 P1-003-B: ForwardsRepository no longer reads its baseline at construction
        // (that was main-thread I/O) — the first real load now happens here, off the main
        // thread, instead of relying on a caller to trigger refreshForwards() manually.
        refreshForwards()
    }

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
        viewModelScope.launch { deps.forwardsRepository.refresh() }
    }
}
