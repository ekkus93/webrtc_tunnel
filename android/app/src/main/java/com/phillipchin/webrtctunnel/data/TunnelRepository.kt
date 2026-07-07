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

/**
 * Thrown (wrapped in a `Result.failure`) by [TunnelRepository.stop] when native JNI reports
 * success but the final runtime state cannot be confirmed as [ServiceState.Stopped] — either
 * because the post-stop status refresh itself failed, or because it succeeded but observed a
 * non-`Stopped` state (P0-003).
 */
class StopStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

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

    // P0-005: Log retrieval failure does not affect tunnel lifecycle state.
    private val _logsError = MutableStateFlow<TunnelError?>(null)
    val logsError: StateFlow<TunnelError?> = _logsError.asStateFlow()

    // The one atomic state-mutation primitive (P0-002): every mutator below goes through
    // this compare-and-set loop instead of a plain `_status.value = _status.value.copy(...)`
    // read-modify-write, which could lose a concurrent writer's update between the read and
    // the write. `transform` receives the value current *at commit time*, not a snapshot
    // captured before any expensive work (JNI/JSON decode) — callers must do that work first
    // and pass only the resulting merge logic in here.
    private inline fun updateStatus(transform: (TunnelStatus) -> TunnelStatus): TunnelStatus {
        while (true) {
            val current = _status.value
            val next = transform(current)
            if (_status.compareAndSet(current, next)) {
                return next
            }
        }
    }

    /**
     * Verified start (P0-002): native JNI success alone is not sufficient proof of a clean
     * start — a successful start requires [refreshStatusResult] to both succeed *and*
     * observe an active-or-starting runtime state. Failure to verify does not mean the
     * native runtime is absent (it may be running in an unverified state), so the caller
     * must own the resulting cleanup via the ordered lifecycle coordinator (P0-001).
     */
    fun start(
        mode: TunnelMode,
        configPath: String,
        identityBytes: ByteArray? = null,
    ): Result<Unit> {
        val nativeResult =
            when (mode) {
                TunnelMode.Offer -> bridge.startOffer(configPath, identityBytes)
                TunnelMode.Answer -> bridge.startAnswer(configPath)
            }

        return nativeResult.fold(
            onFailure = { error -> Result.failure(error) },
            onSuccess = {
                refreshStatusResult(preservePolicyPaused = false).fold(
                    onFailure = { error ->
                        Result.failure(
                            StartStatusVerificationException(
                                "Native start returned success but runtime status could not be verified",
                                error,
                            ),
                        )
                    },
                    onSuccess = { status ->
                        if (status.serviceState.isTunnelActiveOrStarting()) {
                            Result.success(Unit)
                        } else {
                            Result.failure(
                                StartStatusVerificationException(
                                    "Native start returned success but final state was " +
                                        "${status.serviceState}",
                                ),
                            )
                        }
                    },
                )
            },
        )
    }

    /**
     * Verified stop (P0-003): native JNI success alone is not sufficient proof of a clean
     * stop — a duplicate/no-op ("not running") success could otherwise be reported while the
     * real owner's stop is still in flight or has actually failed into `Error`. Success here
     * requires [refreshStatusResult] to both succeed *and* observe [ServiceState.Stopped].
     */
    fun stop(): Result<Unit> =
        bridge.stop().fold(
            onFailure = { Result.failure(it) },
            onSuccess = {
                refreshStatusResult().fold(
                    onFailure = { error ->
                        Result.failure(
                            StopStatusVerificationException(
                                "Native stop returned success but final status could not be verified",
                                error,
                            ),
                        )
                    },
                    onSuccess = { verifiedStatus ->
                        if (verifiedStatus.serviceState == ServiceState.Stopped) {
                            Result.success(Unit)
                        } else {
                            Result.failure(
                                StopStatusVerificationException(
                                    "Native stop returned success but final state was " +
                                        "${verifiedStatus.serviceState}",
                                ),
                            )
                        }
                    },
                )
            },
        )

    fun refreshStatus() {
        refreshStatusResult()
    }

    /**
     * Same native-status refresh as [refreshStatus], but returns the outcome instead of only
     * publishing it into [status] — used by [stop] to verify the native runtime actually
     * reached [ServiceState.Stopped] rather than trusting a bare JNI success code (P0-003).
     * Callers that only need "publish error into status, no direct result needed" should keep
     * using [refreshStatus] instead.
     */
    fun refreshStatusResult(preservePolicyPaused: Boolean = true): Result<TunnelStatus> {
        // Expensive native/JSON work happens once, outside the atomic mutation (P0-002):
        // only the merge decision (which depends on whatever the *latest* status turns out
        // to be at commit time, not this stale-by-the-time-we-commit snapshot) runs inside
        // updateStatus's retry loop.
        val native =
            runCatching {
                Json.decodeFromString<NativeRuntimeStatusDto>(bridge.getStatusJson())
            }.getOrElse { error ->
                updateStatus { current ->
                    current.copy(
                        serviceState = ServiceState.Error,
                        lastError =
                            TunnelError(
                                code = "status_decode_failed",
                                message = "Native status decode failed",
                                details =
                                    SensitiveDataRedactor.redactText(
                                        error.message ?: "unknown status decode error",
                                    ),
                            ),
                    )
                }
                return Result.failure(error)
            }
        val committed =
            updateStatus { current ->
                val mapped = native.toTunnelStatus(current)
                // A native status poll must never resurrect a policy-paused state
                // (PausedMeteredBlocked / NoNetwork) back to Connected: the daemon task
                // may still be reported active while network policy has blocked the tunnel.
                // During verified start/stop, the caller owns the policy state transition,
                // so skip preservation and trust the native result.
                val resolved =
                    if (preservePolicyPaused && isPolicyPausedState(current.serviceState) && native.active) {
                        mapped.copy(
                            serviceState = current.serviceState,
                            networkStatus = current.networkStatus,
                            mqttConnected = false,
                            activeSessionCount = 0,
                            lastError = current.lastError,
                        )
                    } else {
                        mapped
                    }
                SensitiveDataRedactor.redactStatus(resolved)
            }
        return Result.success(committed)
    }

    fun recentLogs(maxEvents: Int): List<LogEvent> {
        // P0-005: Log retrieval failure does not affect tunnel lifecycle state.
        // Clear the logs error so a later success clears it.
        _logsError.value = null
        return runCatching {
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
        }.fold(
            onSuccess = { logs -> logs },
            onFailure = { error ->
                _logsError.value =
                    TunnelError(
                        code = "log_decode_failed",
                        message = "Native log retrieval failed",
                        details = SensitiveDataRedactor.redactText(error.message ?: "unknown log retrieval error"),
                    )
                // Never return an empty list on failure — that reads as "no logs". Surface a
                // synthetic error log entry so the log screen shows that retrieval failed.
                listOf(
                    LogEvent(
                        unixMs = 0L,
                        level = "error",
                        message = "Native log retrieval failed; see logsError for details",
                    ),
                )
            },
        )
    }

    fun setPolicyBlocked(blockReason: String) {
        val redacted = SensitiveDataRedactor.redactText(blockReason)
        updateStatus { current ->
            current.copy(
                serviceState = ServiceState.PausedMeteredBlocked,
                mqttConnected = false,
                activeSessionCount = 0,
                networkStatus =
                    current.networkStatus.copy(
                        tunnelAllowed = false,
                        blockReason = redacted,
                    ),
                lastError = null,
            )
        }
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
        updateStatus { current ->
            current.copy(
                serviceState = state,
                mqttConnected = false,
                activeSessionCount = 0,
                lastError = error,
                // "stop_failed"/"stop_status_verification_failed" are the codes every
                // tunnel-stop/cleanup failure site in TunnelForegroundService uses (P0-003);
                // record it as sticky history (P1-005) rather than only in lastError, which a
                // later successful stop's refreshStatus() would otherwise overwrite and
                // silently erase.
                lastCleanupError =
                    if (code == "stop_failed" || code == "stop_status_verification_failed") {
                        error
                    } else {
                        current.lastCleanupError
                    },
            )
        }
    }

    fun updateNetworkStatus(networkStatus: NetworkStatus) {
        updateStatus { current -> current.copy(networkStatus = networkStatus) }
    }

    fun updateSessionMeteredAllowance(allowForCurrentSession: Boolean) {
        updateStatus { current -> current.copy(allowMeteredForCurrentSession = allowForCurrentSession) }
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

private fun mapNativeListenState(state: String): ListenState =
    when (state.lowercase()) {
        "listening" -> ListenState.Listening
        "stopped" -> ListenState.Stopped
        "error" -> ListenState.Error
        "disabled" -> ListenState.Disabled
        "paused" -> ListenState.Paused
        else -> ListenState.Error
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
                        mapNativeListenState(forward.listenState)
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
        // P1-005: Clear stale active peer on terminal state.
        remotePeerId =
            if (stateValue == ServiceState.Stopped || stateValue == ServiceState.Error) {
                null
            } else {
                remotePeerId ?: previous.remotePeerId
            },
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
