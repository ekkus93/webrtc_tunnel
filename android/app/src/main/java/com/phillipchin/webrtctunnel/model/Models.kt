package com.phillipchin.webrtctunnel.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TunnelMode { Offer, Answer }

@Serializable
enum class ServiceState {
    Stopped,
    Starting,
    Serving,
    Listening,
    Connecting,
    Connected,
    Reconnecting,
    PausedMeteredBlocked,
    NoNetwork,
    Error,
    Stopping,
    ConfigInvalid,
}

/**
 * A run is in progress: either actively connecting/starting or running
 * (listening/serving/connected). Used for duplicate-start prevention,
 * network-policy pause decisions, status polling, and uptime display.
 */
fun ServiceState.isTunnelActiveOrStarting(): Boolean =
    this == ServiceState.Starting ||
        this == ServiceState.Connecting ||
        this == ServiceState.Reconnecting ||
        this == ServiceState.Listening ||
        this == ServiceState.Serving ||
        this == ServiceState.Connected

/** The tunnel is up (listening/serving/connected), as opposed to starting or stopped. */
fun ServiceState.isTunnelRunning(): Boolean =
    this == ServiceState.Listening ||
        this == ServiceState.Serving ||
        this == ServiceState.Connected

@Serializable
enum class NetworkType { UnmeteredWifi, MeteredWifi, Cellular, NoNetwork, Unknown }

@Serializable
enum class ListenState { Listening, Stopped, Error, Disabled, Paused }

@Serializable
data class ForwardConfig(
    val id: String,
    val name: String,
    val localHost: String = "127.0.0.1",
    val localPort: Int,
    val remoteForwardId: String,
    val enabled: Boolean = true,
)

@Serializable
data class ForwardStatus(
    val id: String,
    val name: String,
    // Null when the daemon reported this forward but the app has no matching configured
    // endpoint for it — a mismatch that should never happen in practice, but must never be
    // displayed as a fabricated "null:0"/":0" address. Check `configurationError` first.
    val localHost: String?,
    val localPort: Int?,
    val remoteForwardId: String,
    val enabled: Boolean,
    val listenState: ListenState,
    val lastError: String? = null,
    val configurationError: String? = null,
)

@Serializable
data class NetworkPolicyStatus(
    val networkType: NetworkType,
    val isMetered: Boolean,
    val allowedByDefault: Boolean,
    val allowedByUserPolicy: Boolean,
    val tunnelAllowed: Boolean,
    val blockReason: String? = null,
)

@Serializable
data class TunnelError(
    val code: String,
    val message: String,
    val details: String? = null,
)

@Serializable
data class LogEvent(
    val unixMs: Long,
    val level: String,
    val message: String,
)

@Serializable
data class NativeRuntimeStatusDto(
    val state: String,
    val mode: String? = null,
    @SerialName("config_path") val configPath: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("started_at_unix_ms") val startedAtUnixMs: Long? = null,
    val active: Boolean = false,
    // Measured runtime fields from the native daemon status channel. Defaulted so
    // older/native status JSON without them still decodes.
    @SerialName("mqtt_connected") val mqttConnected: Boolean = false,
    @SerialName("active_session_count") val activeSessionCount: Int = 0,
    @SerialName("session_capacity") val sessionCapacity: Int? = null,
    // Per-forward runtime status (offer role). Defaulted for backward compatibility
    // with native status JSON that predates per-forward reporting.
    val forwards: List<NativeRuntimeForwardStatusDto> = emptyList(),
    // Real remote peer of the active offer session, surfaced so the UI shows who the
    // offer is talking to instead of "Not configured". Null when no session is active.
    @SerialName("remote_peer_id") val remotePeerId: String? = null,
    // ICE path decision (requested mode, selected path, fallback) captured at start, so the
    // UI can show which path is active without reading logs. Null before a run starts.
    val ice: NativeIceInfoDto? = null,
)

@Serializable
data class NativeIceInfoDto(
    @SerialName("requested_mode") val requestedMode: String? = null,
    @SerialName("selected_path") val selectedPath: String? = null,
    val fallback: Boolean = false,
    val reason: String? = null,
    @SerialName("advertised_local_ipv4") val advertisedLocalIpv4: String? = null,
)

@Serializable
data class NativeRuntimeForwardStatusDto(
    val id: String,
    // Null (rather than defaulted to a real-looking "127.0.0.1"/0) when the native side
    // reports a forward with no matching configured endpoint. See `configurationError`.
    @SerialName("local_host") val localHost: String? = null,
    @SerialName("local_port") val localPort: Int? = null,
    @SerialName("listen_state") val listenState: String = "stopped",
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("configuration_error") val configurationError: String? = null,
)

@Serializable
data class NativeLogEventDto(
    @SerialName("unix_ms") val unixMs: Long,
    val level: String,
    val message: String,
)

@Serializable
data class AndroidAppPreferences(
    val allowMetered: Boolean = false,
    val resumeOnUnmetered: Boolean = true,
    val showMeteredWarning: Boolean = true,
    val debugLogsEnabled: Boolean = false,
    val advancedSettingsEnabled: Boolean = false,
    // Mirrors data.DEFAULT_ANDROID_ICE_MODE; the data layer normalizes on read/write so a
    // stale or empty value can never produce an invalid config.
    val androidIceMode: String = "vnet_mux",
)

@Serializable
data class TunnelStatus(
    val serviceState: ServiceState,
    val mode: TunnelMode,
    val localPeerId: String,
    // P1-001: the peer of the CURRENT active session, or null when no session is active. Never a
    // stale/previous peer — the mapping clears it whenever activeSessionCount == 0, including
    // non-terminal zero-session states.
    val remotePeerId: String? = null,
    val mqttConnected: Boolean = false,
    val activeSessionCount: Int = 0,
    val sessionCapacity: Int? = null,
    val uptimeSeconds: Long? = null,
    val networkStatus: NetworkPolicyStatus =
        NetworkPolicyStatus(
            networkType = NetworkType.NoNetwork,
            isMetered = false,
            allowedByDefault = false,
            allowedByUserPolicy = false,
            tunnelAllowed = false,
        ),
    val allowMeteredForCurrentSession: Boolean = false,
    val forwards: List<ForwardStatus> = emptyList(),
    val lastError: TunnelError? = null,
    // Sticky diagnostic history, distinct from [lastError]: a tunnel-stop/cleanup failure is
    // recorded here and never auto-cleared by a later status refresh or successful retry (a
    // later successful stop legitimately reports Stopped via [serviceState]/[lastError], but
    // this field keeps the earlier failure visible instead of silently erasing it) (P1-005).
    val lastCleanupError: TunnelError? = null,
)

@Serializable
data class ValidationResult(
    val valid: Boolean,
    val message: String? = null,
)

@Serializable
data class IdentityValidationResult(
    val valid: Boolean,
    val message: String? = null,
    @SerialName("canonical_public_identity") val canonicalPublicIdentity: String? = null,
    @SerialName("canonical_private_identity") val canonicalPrivateIdentity: String? = null,
    @SerialName("peer_id") val peerId: String? = null,
)

@Serializable
data class SetupConfigInput(
    val localPeerId: String = "android-phone",
    val brokerHost: String = "broker.emqx.io",
    val brokerPort: Int = 8883,
    val brokerUseTls: Boolean = true,
    val brokerUsername: String = "",
    val brokerPassword: String = "",
    val brokerPasswordFile: String = "",
    val topicPrefix: String = "p2ptunnel",
    val remotePeerId: String = "",
    val allowMetered: Boolean = false,
    val resumeOnUnmetered: Boolean = true,
)
