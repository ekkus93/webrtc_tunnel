package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.RustTunnelBridge
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class TunnelRepository(
    @Suppress("UNUSED_PARAMETER") context: Context,
    bridgeFactory: () -> TunnelNativeBridge = { RustTunnelBridge() },
) {
    constructor(context: Context, bridge: TunnelNativeBridge) : this(context, { bridge })

    private val bridge: TunnelNativeBridge by lazy(bridgeFactory)
    private val _status = MutableStateFlow(
        TunnelStatus(
            serviceState = com.phillipchin.webrtctunnel.model.ServiceState.Stopped,
            mode = TunnelMode.Offer,
            localPeerId = "android-phone",
        ),
    )
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    fun start(mode: TunnelMode, configPath: String): Result<Unit> {
        val result = when (mode) {
            TunnelMode.Offer -> bridge.startOffer(configPath)
            TunnelMode.Answer -> bridge.startAnswer(configPath)
        }
        result.onSuccess { refreshStatus() }
        return result
    }

    fun stop(): Result<Unit> = bridge.stop().onSuccess { refreshStatus() }

    fun refreshStatus() {
        runCatching {
            _status.value = Json.decodeFromString(TunnelStatus.serializer(), bridge.getStatusJson())
        }
    }

    fun recentLogs(maxEvents: Int): List<LogEvent> =
        runCatching { Json.decodeFromString<List<LogEvent>>(bridge.getRecentLogsJson(maxEvents)) }
            .getOrDefault(emptyList())

    fun validateConfig(configPath: String): ValidationResult = bridge.validateConfig(configPath)
}
