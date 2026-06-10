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
            val previous = _status.value
            val native = Json.decodeFromString<NativeRuntimeStatusDto>(bridge.getStatusJson())
            val mapped = native.toTunnelStatus(previous)
            // A native status poll must never resurrect a policy-paused state
            // (PausedMeteredBlocked / NoNetwork) back to Connected: the daemon task
            // may still be reported active while network policy has blocked the tunnel.
            val resolved = if (isPolicyPausedState(previous.serviceState) && native.active) {
                mapped.copy(
                    serviceState = previous.serviceState,
                    networkStatus = previous.networkStatus,
                    mqttConnected = false,
                    activeSessionCount = 0,
                    lastError = previous.lastError,
                )
            } else {
                mapped
            }
            _status.value = SensitiveDataRedactor.redactStatus(resolved)
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
        val redacted = SensitiveDataRedactor.redactText(blockReason)
        _status.value = _status.value.copy(
            serviceState = ServiceState.PausedMeteredBlocked,
            mqttConnected = false,
            activeSessionCount = 0,
            networkStatus = _status.value.networkStatus.copy(
                tunnelAllowed = false,
                blockReason = redacted,
            ),
            lastError = null,
        )
    }

    fun setLocalError(
        code: String,
        message: String,
        details: String? = null,
        state: ServiceState = ServiceState.Error,
    ) {
        _status.value = _status.value.copy(
            serviceState = state,
            mqttConnected = false,
            activeSessionCount = 0,
            lastError = TunnelError(
                code = code,
                message = SensitiveDataRedactor.redactText(message),
                details = details?.let(SensitiveDataRedactor::redactText),
            ),
        )
    }

    fun updateNetworkStatus(networkStatus: NetworkStatus) {
        _status.value = _status.value.copy(networkStatus = networkStatus)
    }

    fun updateSessionMeteredAllowance(allowForCurrentSession: Boolean) {
        _status.value = _status.value.copy(allowMeteredForCurrentSession = allowForCurrentSession)
    }

    private fun isPolicyPausedState(state: ServiceState): Boolean =
        state == ServiceState.PausedMeteredBlocked || state == ServiceState.NoNetwork

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
        val uptimeSeconds = started_at_unix_ms?.let { startedAt ->
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            elapsedMs / 1000L
        }
        return previous.copy(
            serviceState = stateValue,
            mode = modeValue,
            mqttConnected = mqtt_connected,
            activeSessionCount = active_session_count,
            sessionCapacity = session_capacity ?: previous.sessionCapacity,
            uptimeSeconds = uptimeSeconds,
            lastError = last_error?.let {
                TunnelError(code = "native_runtime_error", message = it, details = config_path)
            },
        )
    }
}
