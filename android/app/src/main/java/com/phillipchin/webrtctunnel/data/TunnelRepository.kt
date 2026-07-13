package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.RustTunnelBridge
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.model.ForwardStatus
import com.phillipchin.webrtctunnel.model.ListenState
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import kotlinx.coroutines.CancellationException
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

    data class LogsFetchResult(
        val logs: List<LogEvent>,
        val error: TunnelError?,
    )

    fun recentLogs(maxEvents: Int): LogsFetchResult {
        // P0-005: Log retrieval failure does not affect tunnel lifecycle state.
        // P1-007: Return typed result so ViewModel owns generation check for both logs and error.
        // Cancellation propagates; other errors become typed LogsFetchResult.
        // Repository does NOT mutate _logsError — ViewModel applies it under generation guard.
        return try {
            val dtos = Json.decodeFromString<List<NativeLogEventDto>>(bridge.getRecentLogsJson(maxEvents))
            val logs =
                dtos.map { event ->
                    SensitiveDataRedactor.redactLogEvent(
                        LogEvent(
                            unixMs = event.unixMs,
                            level = event.level,
                            message = event.message,
                        ),
                    )
                }
            LogsFetchResult(logs = logs, error = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LogsFetchResult(
                logs = emptyList(),
                error =
                    TunnelError(
                        code = "logs_refresh_failed",
                        message =
                            SensitiveDataRedactor.redactText(
                                error.message
                                    ?: "Log refresh failed",
                            ),
                    ),
            )
        }
    }

    fun setPolicyBlocked(blockReason: String) {
        val redacted = SensitiveDataRedactor.redactText(blockReason)
        updateStatus { current ->
            current
                .copy(
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
                .withoutActivePeer()
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
            // "stop_failed"/"stop_status_verification_failed"/"start_verification_cleanup_failed"
            // are the codes every tunnel-stop/cleanup failure site in TunnelForegroundService
            // uses (P0-003); record it as sticky history (P1-005) rather than only in lastError,
            // which a later successful stop's refreshStatus() would otherwise overwrite and
            // silently erase. Sticky codes are cleanup-related failures.
            val isStickyCode =
                code in
                    setOf(
                        "stop_failed",
                        "stop_status_verification_failed",
                        "start_verification_cleanup_failed",
                    )
            val updated =
                current.copy(
                    serviceState = state,
                    mqttConnected = false,
                    activeSessionCount = 0,
                    lastError = error,
                    lastCleanupError =
                        if (isStickyCode) {
                            error
                        } else {
                            current.lastCleanupError
                        },
                )
            if (isTerminalState(state)) updated.withoutActivePeer() else updated
        }
    }

    fun updateNetworkStatus(networkStatus: NetworkPolicyStatus) {
        updateStatus { current -> current.copy(networkStatus = networkStatus) }
    }

    fun updateSessionMeteredAllowance(allowForCurrentSession: Boolean) {
        updateStatus { current -> current.copy(allowMeteredForCurrentSession = allowForCurrentSession) }
    }
}

private fun isPolicyPausedState(state: ServiceState): Boolean =
    state == ServiceState.PausedMeteredBlocked || state == ServiceState.NoNetwork

// P1-010: Terminal states clear the remote peer (no active connection).
private fun isTerminalState(state: ServiceState): Boolean =
    state == ServiceState.Stopped ||
        state == ServiceState.Error ||
        state == ServiceState.PausedMeteredBlocked ||
        state == ServiceState.NoNetwork ||
        state == ServiceState.ConfigInvalid

// P1-007: Clears active peer for terminal states (no active connection).
private fun TunnelStatus.withoutActivePeer(): TunnelStatus =
    copy(
        remotePeerId = null,
        activeSessionCount = 0,
        mqttConnected = false,
    )

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

// P1-009: Returns true when the raw listen state value was unrecognized, so callers
// can surface explicit diagnosis instead of silently mapping to Error.
private fun isUnknownListenState(state: String): Boolean = !KNOWN_LISTEN_STATES.contains(state.lowercase())

private val KNOWN_LISTEN_STATES =
    setOf("listening", "stopped", "error", "disabled", "paused")

private fun NativeRuntimeStatusDto.toTunnelStatus(previous: TunnelStatus): TunnelStatus {
    // P1-008: Reject unknown native mode explicitly.
    val modeValue = resolveNativeMode(mode)
    if (modeValue == null) {
        // Unknown mode: retain previous mode, surface as schema error.
        return previous.copy(
            serviceState = ServiceState.Error,
            lastError =
                TunnelError(
                    code = "native_status_schema_error",
                    message = "Unknown native mode: ${SensitiveDataRedactor.redactText(mode ?: "null")}",
                ),
        )
    }
    val stateValue = mapNativeServiceState(state, modeValue, activeSessionCount)
    val uptimeSeconds = calculateUptimeSeconds(stateValue, startedAtUnixMs)
    val mappedForwards = mapForwards()
    val base =
        previous.copy(
            serviceState = stateValue,
            mode = modeValue,
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
    return if (isTerminalState(stateValue)) {
        base.copy(
            remotePeerId = null,
            activeSessionCount = 0,
            mqttConnected = false,
        )
    } else {
        base
    }
}

// P1-008: Resolve native mode, returning null for unknown modes.
// P1-003: null mode is a schema error (missing field), not a fallback to Offer.
private fun resolveNativeMode(mode: String?): TunnelMode? =
    when (mode) {
        null -> null
        "offer" -> TunnelMode.Offer
        "answer" -> TunnelMode.Answer
        else -> null
    }

// Calculate uptime seconds only while a run is in progress.
private fun calculateUptimeSeconds(
    stateValue: ServiceState,
    startedAtUnixMs: Long?,
): Long? =
    if (stateValue.isTunnelActiveOrStarting()) {
        startedAtUnixMs?.let { startedAt ->
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            elapsedMs / MILLIS_PER_SECOND
        }
    } else {
        null
    }

// Map forwards with explicit diagnosis for unknown listen states (P1-009).
private fun NativeRuntimeStatusDto.mapForwards(): List<ForwardStatus> =
    forwards.map { forward ->
        val configurationError = forward.configurationError?.let(SensitiveDataRedactor::redactText)
        val mappedState = mapNativeListenState(forward.listenState)
        // P1-009: Surface raw value for unknown listen states.
        val listenStateError =
            if (configurationError != null) {
                configurationError
            } else if (isUnknownListenState(forward.listenState)) {
                "Unknown listen state: ${SensitiveDataRedactor.redactText(forward.listenState)}"
            } else {
                forward.lastError?.let(SensitiveDataRedactor::redactText)
            }
        ForwardStatus(
            id = forward.id,
            name = forward.id,
            localHost = forward.localHost,
            localPort = forward.localPort,
            remoteForwardId = forward.id,
            enabled = forward.listenState.lowercase() != "disabled",
            listenState =
                if (configurationError != null) {
                    ListenState.Error
                } else {
                    mappedState
                },
            lastError = listenStateError,
            configurationError = configurationError,
        )
    }
