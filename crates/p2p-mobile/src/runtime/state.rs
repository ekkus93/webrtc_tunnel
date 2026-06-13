//! Controller-owned mutable runtime state (`RuntimeInner`) and the helpers that
//! maintain it: overlaying the live daemon status onto the lifecycle snapshot,
//! clearing measured metadata between runs, and recording start failures.

use std::collections::VecDeque;
use std::time::{SystemTime, UNIX_EPOCH};

use p2p_daemon::DaemonStatus;
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

use super::types::{
    AndroidForwardRuntimeStatus, AndroidLogEvent, AndroidRuntimeState, AndroidRuntimeStatus,
    android_state_from_daemon, forward_listen_state_str,
};

pub(crate) struct RuntimeInner {
    pub(crate) state: AndroidRuntimeStatus,
    pub(crate) logs: VecDeque<AndroidLogEvent>,
    pub(crate) task: Option<JoinHandle<()>>,
    pub(crate) runtime: Option<Runtime>,
    /// Latest daemon status from the running offer daemon, if any. Overlaid onto the
    /// controller-owned lifecycle state in [`RuntimeInner::snapshot_status`].
    pub(crate) status_rx: Option<tokio::sync::watch::Receiver<DaemonStatus>>,
    /// Configured offer forwards `(id, local_host, local_port)` captured at start,
    /// used to enrich the daemon's per-forward status with host/port for the UI.
    pub(crate) forward_config: Vec<(String, String, u16)>,
}

impl RuntimeInner {
    /// Merge the controller-owned lifecycle state with the latest measured daemon
    /// status. Measured fields are only trusted while the controller considers the
    /// runtime active; once stopped/errored they reset to a quiescent view.
    pub(crate) fn snapshot_status(&self) -> AndroidRuntimeStatus {
        let mut status = self.state.clone();
        match (&self.status_rx, status.active) {
            (Some(rx), true) => {
                let daemon = rx.borrow();
                status.mqtt_connected = daemon.mqtt_connected;
                status.active_session_count = daemon.active_session_count;
                status.session_capacity = Some(daemon.session_capacity);
                status.state = android_state_from_daemon(daemon.current_state);
                status.forwards = daemon
                    .forwards
                    .iter()
                    .map(|forward| {
                        let (local_host, local_port) = self
                            .forward_config
                            .iter()
                            .find(|(id, _, _)| id == &forward.id)
                            .map(|(_, host, port)| (host.clone(), *port))
                            .unwrap_or_default();
                        AndroidForwardRuntimeStatus {
                            id: forward.id.clone(),
                            local_host,
                            local_port,
                            listen_state: forward_listen_state_str(forward.listen_state),
                            last_error: forward.last_error.clone(),
                        }
                    })
                    .collect();
            }
            _ => {
                status.mqtt_connected = false;
                status.active_session_count = 0;
                status.forwards = Vec::new();
            }
        }
        status
    }
}

impl Default for RuntimeInner {
    fn default() -> Self {
        Self {
            state: AndroidRuntimeStatus::default(),
            logs: VecDeque::with_capacity(256),
            task: None,
            runtime: None,
            status_rx: None,
            forward_config: Vec::new(),
        }
    }
}

/// Clear measured/uptime metadata that must not outlive an active run, so the UI does
/// not show stale uptime, MQTT/session counts, or per-forward state after the run ends.
pub(crate) fn reset_runtime_metadata(state: &mut AndroidRuntimeStatus) {
    state.started_at_unix_ms = None;
    state.mqtt_connected = false;
    state.active_session_count = 0;
    state.session_capacity = None;
    state.forwards = Vec::new();
}

pub(crate) fn record_start_error(inner: &mut RuntimeInner, message: String) -> String {
    inner.state.state = AndroidRuntimeState::Error;
    inner.state.active = false;
    inner.state.last_error = Some(message.clone());
    inner.logs.push_back(AndroidLogEvent {
        unix_ms: unix_ms(),
        level: "error".to_owned(),
        message: message.clone(),
    });
    while inner.logs.len() > 256 {
        inner.logs.pop_front();
    }
    message
}

pub(crate) fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}
