package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.RustTunnelBridge
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
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

    fun start(mode: TunnelMode, configPath: String, identityBytes: ByteArray? = null): Result<Unit> {
        val result = when (mode) {
            TunnelMode.Offer -> bridge.startOffer(configPath, identityBytes)
            TunnelMode.Answer -> bridge.startAnswer(configPath)
        }
        result.onSuccess { refreshStatus() }
        return result
    }

    fun stop(): Result<Unit> = bridge.stop().onSuccess { refreshStatus() }

    fun refreshStatus() {
        runCatching {
            val native = Json.decodeFromString<NativeRuntimeStatusDto>(bridge.getStatusJson())
            _status.value = SensitiveDataRedactor.redactStatus(native.toTunnelStatus(_status.value))
        }.onFailure { error ->
            _status.value = _status.value.copy(
                serviceState = ServiceState.Error,
                lastError = TunnelError(
                    code = "status_decode_failed",
                    message = "Native status decode failed",
                    details = SensitiveDataRedactor.redactText(error.message ?: "unknown status decode error"),
                ),
            )
        }
    }

    fun recentLogs(maxEvents: Int): List<LogEvent> =
        runCatching {
            Json.decodeFromString<List<NativeLogEventDto>>(bridge.getRecentLogsJson(maxEvents))
                .map { event ->
                    SensitiveDataRedactor.redactLogEvent(
                        LogEvent(
                        unixMs = event.unix_ms,
                        level = event.level,
                        message = event.message,
                    ),
                    )
                }
        }.onFailure { error ->
            _status.value = _status.value.copy(
                serviceState = ServiceState.Error,
                lastError = TunnelError(
                    code = "log_decode_failed",
                    message = "Native log decode failed",
                    details = SensitiveDataRedactor.redactText(error.message ?: "unknown log decode error"),
                ),
            )
        }.getOrDefault(emptyList())

    fun validateConfig(configPath: String): ValidationResult = bridge.validateConfig(configPath)

    fun validateConfigWithIdentity(configPath: String, identityBytes: ByteArray): ValidationResult =
        bridge.validateConfigWithIdentity(configPath, identityBytes)

    fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        bridge.validatePrivateIdentity(identityToml)

    fun validatePublicIdentity(line: String): IdentityValidationResult =
        bridge.validatePublicIdentity(line)

    fun generateIdentity(peerId: String): IdentityValidationResult =
        bridge.generateIdentity(peerId)

    fun setPolicyBlocked(blockReason: String) {
        _status.value = _status.value.copy(
            serviceState = ServiceState.PausedMeteredBlocked,
            networkStatus = _status.value.networkStatus.copy(
                tunnelAllowed = false,
                blockReason = blockReason,
            ),
            lastError = null,
        )
    }

    fun updateNetworkStatus(networkStatus: NetworkStatus) {
        _status.value = _status.value.copy(networkStatus = networkStatus)
    }

    private fun NativeRuntimeStatusDto.toTunnelStatus(previous: TunnelStatus): TunnelStatus {
        val modeValue = when (mode) {
            "answer" -> TunnelMode.Answer
            else -> TunnelMode.Offer
        }
        val stateValue = when (state) {
            "running" -> if (modeValue == TunnelMode.Answer) ServiceState.Serving else ServiceState.Connected
            "starting" -> ServiceState.Starting
            "stopping" -> ServiceState.Stopping
            "error" -> ServiceState.Error
            else -> ServiceState.Stopped
        }
        return previous.copy(
            serviceState = stateValue,
            mode = modeValue,
            mqttConnected = active,
            activeSessionCount = if (active) 1 else 0,
            lastError = last_error?.let {
                TunnelError(code = "native_runtime_error", message = it, details = config_path)
            },
        )
    }
}
