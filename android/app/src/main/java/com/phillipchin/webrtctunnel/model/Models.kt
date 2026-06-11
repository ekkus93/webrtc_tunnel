package com.phillipchin.webrtctunnel.model

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
    val localHost: String,
    val localPort: Int,
    val remoteForwardId: String,
    val enabled: Boolean,
    val listenState: ListenState,
    val lastError: String? = null,
)

@Serializable
data class NetworkStatus(
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
    val config_path: String? = null,
    val last_error: String? = null,
    val started_at_unix_ms: Long? = null,
    val active: Boolean = false,
    // Measured runtime fields from the native daemon status channel. Defaulted so
    // older/native status JSON without them still decodes.
    val mqtt_connected: Boolean = false,
    val active_session_count: Int = 0,
    val session_capacity: Int? = null,
    // Per-forward runtime status (offer role). Defaulted for backward compatibility
    // with native status JSON that predates per-forward reporting.
    val forwards: List<NativeRuntimeForwardStatusDto> = emptyList(),
)

@Serializable
data class NativeRuntimeForwardStatusDto(
    val id: String,
    val local_host: String = "127.0.0.1",
    val local_port: Int = 0,
    val listen_state: String = "stopped",
    val last_error: String? = null,
)

@Serializable
data class NativeLogEventDto(
    val unix_ms: Long,
    val level: String,
    val message: String,
)

@Serializable
data class AndroidAppPreferences(
    val allowMetered: Boolean = false,
    val resumeOnUnmetered: Boolean = true,
    val showMeteredWarning: Boolean = true,
    val startTunnelWhenAppOpens: Boolean = false,
    val debugLogsEnabled: Boolean = false,
    val advancedSettingsEnabled: Boolean = false,
)

@Serializable
data class TunnelStatus(
    val serviceState: ServiceState,
    val mode: TunnelMode,
    val localPeerId: String,
    val remotePeerId: String? = null,
    val mqttConnected: Boolean = false,
    val activeSessionCount: Int = 0,
    val sessionCapacity: Int? = null,
    val uptimeSeconds: Long? = null,
    val networkStatus: NetworkStatus =
        NetworkStatus(
            networkType = NetworkType.NoNetwork,
            isMetered = false,
            allowedByDefault = false,
            allowedByUserPolicy = false,
            tunnelAllowed = false,
        ),
    val allowMeteredForCurrentSession: Boolean = false,
    val forwards: List<ForwardStatus> = emptyList(),
    val lastError: TunnelError? = null,
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
    val canonical_public_identity: String? = null,
    val canonical_private_identity: String? = null,
    val peer_id: String? = null,
)

@Serializable
data class SetupConfigInput(
    val localPeerId: String = "android-phone",
    val brokerHost: String = "",
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
