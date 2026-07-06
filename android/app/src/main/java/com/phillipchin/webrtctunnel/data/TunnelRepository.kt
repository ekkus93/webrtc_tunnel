package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.RustTunnelBridge
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.ForwardStatus
import com.phillipchin.webrtctunnel.model.ListenState
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

private const val MILLIS_PER_SECOND = 1000L

class TunnelRepository(
    bridgeFactory: () -> TunnelNativeBridge = { RustTunnelBridge() },
) {
    constructor(bridge: TunnelNativeBridge) : this({ bridge })

    private val bridge: TunnelNativeBridge by lazy(bridgeFactory)
    private val _status =
        MutableStateFlow(
            TunnelStatus(
                serviceState = com.phillipchin.webrtctunnel.model.ServiceState.Stopped,
                mode = TunnelMode.Offer,
                localPeerId = "android-phone",
            ),
        )
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    fun start(
        mode: TunnelMode,
        configPath: String,
        identityBytes: ByteArray? = null,
    ): Result<Unit> {
        val result =
            when (mode) {
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
            val resolved =
                if (isPolicyPausedState(previous.serviceState) && native.active) {
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
            _status.value =
                _status.value.copy(
                    serviceState = ServiceState.Error,
                    lastError =
                        TunnelError(
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
                            unixMs = event.unixMs,
                            level = event.level,
                            message = event.message,
                        ),
                    )
                }
        }.onFailure { error ->
            _status.value =
                _status.value.copy(
                    serviceState = ServiceState.Error,
                    lastError =
                        TunnelError(
                            code = "log_decode_failed",
                            message = "Native log decode failed",
                            details = SensitiveDataRedactor.redactText(error.message ?: "unknown log decode error"),
                        ),
                )
        }.getOrElse {
            // Never return an empty list on failure — that reads as "no logs". Surface a
            // synthetic error log entry (in addition to the Error status set above) so the
            // log screen shows that retrieval failed. An empty list means a successful fetch
            // with no logs.
            listOf(
                LogEvent(
                    unixMs = 0L,
                    level = "error",
                    message = "Native log retrieval failed; see status for details",
                ),
            )
        }

    fun setPolicyBlocked(blockReason: String) {
        val redacted = SensitiveDataRedactor.redactText(blockReason)
        _status.value =
            _status.value.copy(
                serviceState = ServiceState.PausedMeteredBlocked,
                mqttConnected = false,
                activeSessionCount = 0,
                networkStatus =
                    _status.value.networkStatus.copy(
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
        val error =
            TunnelError(
                code = code,
                message = SensitiveDataRedactor.redactText(message),
                details = details?.let(SensitiveDataRedactor::redactText),
            )
        _status.value =
            _status.value.copy(
                serviceState = state,
                mqttConnected = false,
                activeSessionCount = 0,
                lastError = error,
                // "stop_failed" is the code every tunnel-stop/cleanup failure site in
                // TunnelForegroundService uses; record it as sticky history (P1-005) rather
                // than only in lastError, which a later successful stop's refreshStatus()
                // would otherwise overwrite and silently erase.
                lastCleanupError = if (code == "stop_failed") error else _status.value.lastCleanupError,
            )
    }

    fun updateNetworkStatus(networkStatus: NetworkStatus) {
        _status.value = _status.value.copy(networkStatus = networkStatus)
    }

    fun updateSessionMeteredAllowance(allowForCurrentSession: Boolean) {
        _status.value = _status.value.copy(allowMeteredForCurrentSession = allowForCurrentSession)
    }
}

private fun isPolicyPausedState(state: ServiceState): Boolean =
    state == ServiceState.PausedMeteredBlocked || state == ServiceState.NoNetwork

// Truthful mapping: native "running" only means the daemon task is alive. Reserve
// Connected for an actual active session/tunnel; otherwise show a listening/serving
// label. Unknown native states map to Error, never silently to Stopped.
private fun mapNativeServiceState(
    state: String,
    mode: TunnelMode,
    activeSessionCount: Int,
): ServiceState =
    when (state) {
        "running" ->
            when {
                activeSessionCount > 0 -> ServiceState.Connected
                mode == TunnelMode.Answer -> ServiceState.Serving
                else -> ServiceState.Listening
            }
        "starting" -> ServiceState.Starting
        "stopping" -> ServiceState.Stopping
        "stopped" -> ServiceState.Stopped
        "error" -> ServiceState.Error
        else -> ServiceState.Error
    }

private fun mapNativeListenState(
    state: String,
    lastError: String?,
): ListenState =
    when (state.lowercase()) {
        "listening" -> ListenState.Listening
        "stopped" -> ListenState.Stopped
        "error" -> ListenState.Error
        "disabled" -> ListenState.Disabled
        "paused" -> ListenState.Paused
        else -> if (lastError != null) ListenState.Error else ListenState.Stopped
    }

private fun NativeRuntimeStatusDto.toTunnelStatus(previous: TunnelStatus): TunnelStatus {
    val modeValue =
        when (mode) {
            "answer" -> TunnelMode.Answer
            else -> TunnelMode.Offer
        }
    val stateValue = mapNativeServiceState(state, modeValue, activeSessionCount)
    // Uptime is only meaningful while a run is in progress; never show it for
    // stopped/error/paused states even if a stale timestamp were present.
    val uptimeSeconds =
        if (stateValue.isTunnelActiveOrStarting()) {
            startedAtUnixMs?.let { startedAt ->
                val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                elapsedMs / MILLIS_PER_SECOND
            }
        } else {
            null
        }
    val mappedForwards =
        forwards.map { forward ->
            val configurationError = forward.configurationError?.let(SensitiveDataRedactor::redactText)
            ForwardStatus(
                id = forward.id,
                name = forward.id,
                localHost = forward.localHost,
                localPort = forward.localPort,
                remoteForwardId = forward.id,
                enabled = forward.listenState.lowercase() != "disabled",
                // A configuration mismatch always means an error, regardless of what
                // listen_state the daemon reported alongside it.
                listenState =
                    if (configurationError != null) {
                        ListenState.Error
                    } else {
                        mapNativeListenState(forward.listenState, forward.lastError)
                    },
                lastError = configurationError ?: forward.lastError?.let(SensitiveDataRedactor::redactText),
                configurationError = configurationError,
            )
        }
    return previous.copy(
        serviceState = stateValue,
        mode = modeValue,
        // Surface the real remote peer from the active session; retain the last-known
        // value between sessions rather than flicker to null.
        remotePeerId = remotePeerId ?: previous.remotePeerId,
        mqttConnected = mqttConnected,
        activeSessionCount = activeSessionCount,
        sessionCapacity = sessionCapacity ?: previous.sessionCapacity,
        uptimeSeconds = uptimeSeconds,
        forwards = mappedForwards,
        lastError =
            lastError?.let {
                TunnelError(code = "native_runtime_error", message = it, details = configPath)
            },
    )
}
