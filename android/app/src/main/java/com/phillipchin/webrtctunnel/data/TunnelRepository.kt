package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
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

class TunnelRepository internal constructor(
    // FIX8 P0-009: shared, application-scoped quarantine/stop-verification owner (see
    // NativeRuntimeSafetyState) — internal constructor because it's an internal type, same
    // pattern as BrokerSecretRepository's internal constructor.
    private val safetyState: NativeRuntimeSafetyState,
    bridgeFactory: () -> TunnelNativeBridge = { RustTunnelBridge() },
) {
    internal constructor(
        safetyState: NativeRuntimeSafetyState,
        bridge: TunnelNativeBridge,
    ) : this(safetyState, { bridge })

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
     * real owner's stop is still in flight or has actually failed into `Error`. Success requires
     * observing [ServiceState.Stopped] from a fresh native status read.
     *
     * FIX8 P0-009-B/D: verification always reads the *raw* native truth, unaffected by any
     * pre-existing quarantine — otherwise a quarantined runtime could never be verified stopped,
     * and explicit-STOP recovery (the whole point of quarantine) would be impossible. Whether a
     * verified stop may then clear the shared quarantine depends entirely on [explicitVerifiedStop]:
     * only a genuine, user-facing explicit STOP ([explicitVerifiedStop] = true) may clear it — a
     * pause, destroy-time fallback stop, or start-verification cleanup stop (the default, false)
     * still records the observed stop (so a redundant second stop isn't attempted) without
     * releasing quarantine from a different, still-unresolved failure. The published [status] is
     * committed only after this decision, so it reflects the safety state atomically with the
     * verification outcome — no window where a concurrent start could observe one without the
     * other.
     *
     * FIX8 P0-009-E: [ifGenerationUnchanged], when supplied (only by the destroy-time fallback
     * stop), skips the observed-stop transition if the shared generation has already advanced
     * past it — a stale caller (e.g. an old service instance's fallback still in flight after a
     * newer instance changed this shared state) must not overwrite that newer transition with
     * outdated information. Not applied when [explicitVerifiedStop] is true: a deliberate,
     * user-facing explicit STOP always wins.
     */
    @CheckResult
    fun stop(
        explicitVerifiedStop: Boolean = false,
        ifGenerationUnchanged: Long? = null,
    ): Result<Unit> {
        val bridgeStopFailure = bridge.stop().exceptionOrNull()
        // Single-return `if`/`else` because ReturnCount caps at 2 (the other return is the
        // decode-failure early exit below, which cannot become a plain value: it must skip the
        // rest of this function entirely).
        return if (bridgeStopFailure != null) {
            Result.failure(bridgeStopFailure)
        } else {
            val native =
                try {
                    Json.decodeFromString<NativeRuntimeStatusDto>(bridge.getStatusJson())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    updateStatus { current ->
                        SensitiveDataRedactor.redactStatus(
                            current
                                .asInvalidNativeStatus(
                                    "status_decode_failed",
                                    "Native status decode failed",
                                    SensitiveDataRedactor.redactText(error.message ?: "unknown status decode error"),
                                ).withNativeRuntimeSafetyOverlay(safetyState.state.value),
                        )
                    }
                    return Result.failure(
                        StopStatusVerificationException(
                            "Native stop returned success but final status could not be verified",
                            error,
                        ),
                    )
                }
            val verifiedStopped = native.reportsVerifiedStop
            if (verifiedStopped) {
                when {
                    explicitVerifiedStop -> safetyState.markVerifiedExplicitStop()
                    ifGenerationUnchanged != null ->
                        safetyState.markObservedStopWithoutRecoveryIfGenerationUnchanged(ifGenerationUnchanged)
                    else -> safetyState.markObservedStopWithoutRecovery()
                }
            }
            updateStatus { current ->
                SensitiveDataRedactor.redactStatus(
                    native.toTunnelStatus(current).withNativeRuntimeSafetyOverlay(safetyState.state.value),
                )
            }
            if (verifiedStopped) {
                Result.success(Unit)
            } else {
                Result.failure(
                    StopStatusVerificationException(
                        "Native stop returned success but final state was not verified as Stopped",
                    ),
                )
            }
        }
    }

    fun refreshStatus() {
        // FIX7 P2-002-A: refreshStatusResult() is @CheckResult — both outcomes are already
        // published into [status] as a side effect inside it, so this caller deliberately has
        // nothing further to do with the returned value; .also {} consumes it explicitly
        // instead of discarding it as a bare statement.
        refreshStatusResult().also { }
    }

    /**
     * Same native-status refresh as [refreshStatus], but returns the outcome instead of only
     * publishing it into [status] — used by [stop] to verify the native runtime actually
     * reached [ServiceState.Stopped] rather than trusting a bare JNI success code (P0-003).
     * Callers that only need "publish error into status, no direct result needed" should keep
     * using [refreshStatus] instead.
     */
    @CheckResult
    fun refreshStatusResult(preservePolicyPaused: Boolean = true): Result<TunnelStatus> {
        // Expensive native/JSON work happens once, outside the atomic mutation (P0-002):
        // only the merge decision (which depends on whatever the *latest* status turns out
        // to be at commit time, not this stale-by-the-time-we-commit snapshot) runs inside
        // updateStatus's retry loop.
        // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this reads
        // through the native JNI bridge, so a fatal Error must not be silently swallowed.
        val native =
            try {
                Json.decodeFromString<NativeRuntimeStatusDto>(bridge.getStatusJson())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateStatus { current ->
                    SensitiveDataRedactor.redactStatus(
                        current
                            .asInvalidNativeStatus(
                                "status_decode_failed",
                                "Native status decode failed",
                                SensitiveDataRedactor.redactText(
                                    error.message ?: "unknown status decode error",
                                ),
                            ).withNativeRuntimeSafetyOverlay(safetyState.state.value),
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
                // FIX8 P0-009-D: a native status refresh alone (any state, including Stopped)
                // must never overwrite a quarantined Error state — only a verified explicit STOP
                // (see [stop]) may clear it.
                SensitiveDataRedactor.redactStatus(resolved.withNativeRuntimeSafetyOverlay(safetyState.state.value))
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
        } catch (error: Exception) {
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

// P1-002-A: shared helper so every "the native status itself cannot be trusted" branch
// (decode failure, unknown native mode, missing required field) clears stale live-connection
// truth the same way a terminal state does, and cannot diverge from that clearing by only
// updating serviceState/lastError while leaving a previous Connected status's remote peer,
// active session count, or MQTT-connected flag still showing.
// An extension property holding a lambda (not a function): this file is at detekt's
// TooManyFunctions threshold.
private val TunnelStatus.asInvalidNativeStatus: (code: String, message: String, details: String?) -> TunnelStatus
    get() = { code, message, details ->
        copy(
            serviceState = ServiceState.Error,
            lastError = TunnelError(code = code, message = message, details = details),
        ).withoutActivePeer()
    }

// FIX8 P0-009-D: applied as the last step of every status commit so a quarantined Error state
// (from a different, unresolved stop-like failure) is never overwritten by a status read's own
// mapped state — regardless of what that mapped state is, including a genuine Stopped. Always
// uses the fixed canonical code/message (RESPONSES item 2), never `safety.code`/`safety.message`
// (the narrower per-failure diagnostic) — a concurrent status poll racing
// TunnelForegroundService.enterNativeRuntimeQuarantine's own two setLocalError calls must not be
// able to leave the narrower code as the final, durably-published one. A property (not a
// function): this file is at detekt's TooManyFunctions threshold.
private val TunnelStatus.withNativeRuntimeSafetyOverlay: (NativeRuntimeSafetySnapshot) -> TunnelStatus
    get() = { safety ->
        if (safety.quarantined) {
            copy(
                serviceState = ServiceState.Error,
                lastError =
                    TunnelError(
                        code = "native_runtime_quarantined",
                        message = "Native runtime state is uncertain; a verified stop is required",
                    ),
            ).withoutActivePeer()
        } else {
            this
        }
    }

// FIX8 P0-009: whether a native status genuinely reports a verified Stopped runtime — computed
// from the raw native fields alone (never overlaid), so [stop]'s own verification can tell a
// true stop apart from a pre-existing quarantine that would otherwise mask it. A property (not
// a function) for the same detekt-budget reason as [withNativeRuntimeSafetyOverlay].
private val NativeRuntimeStatusDto.reportsVerifiedStop: Boolean
    get() =
        resolveNativeMode(mode)?.let { modeValue ->
            mapNativeServiceState(state, modeValue, activeSessionCount) == ServiceState.Stopped
        } ?: false

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
        // Unknown mode: retain previous mode, surface as schema error. P1-002-A: this native
        // status cannot be trusted, so it must clear stale live-connection truth exactly like
        // any other invalid-status branch — never resurrect a previous Connected status's peer.
        return previous.asInvalidNativeStatus(
            "native_status_schema_error",
            "Unknown native mode: ${SensitiveDataRedactor.redactText(mode ?: "null")}",
            null,
        )
    }
    val stateValue = mapNativeServiceState(state, modeValue, activeSessionCount)
    val uptimeSeconds = calculateUptimeSeconds(stateValue, startedAtUnixMs)
    val mappedForwards = mapForwards()
    val base =
        previous.copy(
            serviceState = stateValue,
            mode = modeValue,
            // P1-001: the current remote peer is only truthful while a session is active. Never
            // fall back to the previous peer — a zero-session status (even a non-terminal
            // "running" that maps to Listening) must clear it, not display a stale peer.
            remotePeerId = remotePeerId.takeIf { activeSessionCount > 0 },
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
