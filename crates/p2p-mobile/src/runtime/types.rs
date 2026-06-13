//! Serializable runtime data model surfaced to the Android UI, plus the small
//! mappings from daemon-side enums onto the coarse mobile-facing equivalents.
//! These carry no secrets and are the shapes serialized across the bridge.

use p2p_core::DaemonState;
use p2p_daemon::ForwardListenState;
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidTunnelMode {
    #[default]
    Offer,
    Answer,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidRuntimeState {
    #[default]
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AndroidRuntimeStatus {
    pub state: AndroidRuntimeState,
    pub mode: Option<AndroidTunnelMode>,
    pub config_path: Option<String>,
    pub last_error: Option<String>,
    pub started_at_unix_ms: Option<u64>,
    pub active: bool,
    // Measured runtime fields, sourced from the live daemon status channel rather
    // than guessed at task-spawn time. Default to "not connected" before a snapshot.
    pub mqtt_connected: bool,
    pub active_session_count: usize,
    pub session_capacity: Option<usize>,
    /// Per-forward runtime status (offer role). Empty unless the daemon is running
    /// and reporting forwards.
    pub forwards: Vec<AndroidForwardRuntimeStatus>,
}

/// Per-forward runtime status surfaced to the Android UI. Joins the daemon's
/// per-forward listen state with the configured local host/port. Carries no secrets.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AndroidForwardRuntimeStatus {
    pub id: String,
    pub local_host: String,
    pub local_port: u16,
    pub listen_state: String,
    pub last_error: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AndroidLogEvent {
    pub unix_ms: u64,
    pub level: String,
    pub message: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AndroidValidationResult {
    pub valid: bool,
    pub message: Option<String>,
}

pub(crate) fn forward_listen_state_str(state: ForwardListenState) -> String {
    match state {
        ForwardListenState::Listening => "listening",
        ForwardListenState::Stopped => "stopped",
        ForwardListenState::Error => "error",
    }
    .to_owned()
}

/// Map the daemon's connection state machine onto the coarse mobile runtime state
/// the UI understands. Negotiation/reconnect phases surface as `Starting`.
pub(crate) fn android_state_from_daemon(state: DaemonState) -> AndroidRuntimeState {
    match state {
        DaemonState::Idle
        | DaemonState::Serving
        | DaemonState::WaitingForLocalClient
        | DaemonState::TunnelOpen => AndroidRuntimeState::Running,
        DaemonState::Negotiating
        | DaemonState::ConnectingDataChannel
        | DaemonState::IceRestarting
        | DaemonState::Renegotiating
        | DaemonState::Backoff => AndroidRuntimeState::Starting,
        DaemonState::Closed => AndroidRuntimeState::Stopped,
    }
}
