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
    /// Real remote peer id of the active offer session (offer role has at most one),
    /// so the UI can show who the offer is talking to. `None` when no session is active.
    pub remote_peer_id: Option<String>,
    /// Per-forward runtime status (offer role). Empty unless the daemon is running
    /// and reporting forwards.
    pub forwards: Vec<AndroidForwardRuntimeStatus>,
    /// The ICE path decision (requested mode, selected path, fallback) for the current run,
    /// captured at start so the UI can show which path is active without reading logs.
    /// `None` before a run starts.
    pub ice: Option<AndroidIceInfo>,
}

/// Serializable mirror of [`p2p_webrtc::IceDecisionInfo`] surfaced in the Android status.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AndroidIceInfo {
    pub requested_mode: String,
    pub selected_path: String,
    pub fallback: bool,
    pub reason: String,
    pub advertised_local_ipv4: Option<String>,
}

impl AndroidIceInfo {
    pub(crate) fn from_decision(info: p2p_webrtc::IceDecisionInfo) -> Self {
        Self {
            requested_mode: info.requested_mode.to_owned(),
            selected_path: info.selected_path.to_owned(),
            fallback: info.fallback,
            reason: info.reason.to_owned(),
            advertised_local_ipv4: info.advertised_local_ipv4,
        }
    }
}

/// Per-forward runtime status surfaced to the Android UI. Joins the daemon's
/// per-forward listen state with the configured local host/port. Carries no secrets.
///
/// `local_host`/`local_port` are `None`, and `configuration_error` is `Some(..)`, when the
/// daemon reports a forward id that has no matching entry in the controller's own
/// `forward_config` — this should never happen in practice (both come from the same
/// loaded config), but if it ever does, the UI must not display a fabricated `:0`
/// endpoint as if it were real.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AndroidForwardRuntimeStatus {
    pub id: String,
    pub local_host: Option<String>,
    pub local_port: Option<u16>,
    pub listen_state: String,
    pub last_error: Option<String>,
    pub configuration_error: Option<String>,
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
        | DaemonState::ProbingDataPlane
        | DaemonState::IceRestarting
        | DaemonState::Renegotiating
        | DaemonState::Backoff => AndroidRuntimeState::Starting,
        DaemonState::Closed => AndroidRuntimeState::Stopped,
    }
}
