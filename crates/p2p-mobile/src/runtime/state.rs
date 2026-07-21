//! Controller-owned mutable runtime state (`RuntimeInner`) and the helpers that
//! maintain it: overlaying the live daemon status onto the lifecycle snapshot,
//! clearing measured metadata between runs, and recording start failures.

use std::sync::atomic::AtomicU64;

use p2p_core::{resolve_optional_unix_ms, unix_time_ms};
use p2p_daemon::{DaemonStatus, ShutdownToken};
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

use super::log_bridge::LogBuffer;
use super::types::{
    AndroidForwardRuntimeStatus, AndroidLogEvent, AndroidRuntimeState, AndroidRuntimeStatus,
    android_state_from_daemon, forward_listen_state_str,
};

#[derive(Default)]
pub(crate) struct RuntimeInner {
    pub(crate) state: AndroidRuntimeStatus,
    pub(crate) logs: LogBuffer,
    pub(crate) task: Option<JoinHandle<()>>,
    pub(crate) runtime: Option<Runtime>,
    /// Cooperative shutdown handle for the running daemon task. `stop()` requests
    /// shutdown through this instead of aborting the task outright, so the daemon
    /// reaches its normal cleanup path (WebRTC peer close, listener release) before
    /// the FFI caller's stop() call returns.
    pub(crate) shutdown: Option<ShutdownToken>,
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
                status.remote_peer_id =
                    daemon.sessions.first().map(|session| session.remote_peer_id.to_string());
                status.state = android_state_from_daemon(daemon.current_state);
                status.forwards = daemon
                    .forwards
                    .iter()
                    .map(|forward| {
                        match self.forward_config.iter().find(|(id, _, _)| id == &forward.id) {
                            Some((_, host, port)) => AndroidForwardRuntimeStatus {
                                id: forward.id.clone(),
                                local_host: Some(host.clone()),
                                local_port: Some(*port),
                                listen_state: forward_listen_state_str(forward.listen_state),
                                last_error: forward.last_error.clone(),
                                configuration_error: None,
                            },
                            None => {
                                let message = format!(
                                    "daemon reported forward '{}' but no matching configured \
                                     endpoint exists",
                                    forward.id
                                );
                                tracing::error!(forward_id = %forward.id, "{message}");
                                AndroidForwardRuntimeStatus {
                                    id: forward.id.clone(),
                                    local_host: None,
                                    local_port: None,
                                    listen_state: forward_listen_state_str(forward.listen_state),
                                    last_error: forward.last_error.clone(),
                                    configuration_error: Some(message),
                                }
                            }
                        }
                    })
                    .collect();
            }
            _ => {
                status.mqtt_connected = false;
                status.active_session_count = 0;
                status.remote_peer_id = None;
                status.forwards = Vec::new();
            }
        }
        status
    }
}

/// Clear measured/uptime metadata that must not outlive an active run, so the UI does
/// not show stale uptime, MQTT/session counts, or per-forward state after the run ends.
pub(crate) fn reset_runtime_metadata(state: &mut AndroidRuntimeStatus) {
    state.started_at_unix_ms = None;
    state.mqtt_connected = false;
    state.active_session_count = 0;
    state.session_capacity = None;
    state.remote_peer_id = None;
    state.forwards = Vec::new();
}

pub(crate) fn record_start_error(inner: &mut RuntimeInner, message: String) -> String {
    // FIX7 P0-010-E: the primary runtime state above is set unconditionally — a clock failure
    // only ever skips the optional log entry below, never the authoritative error state.
    inner.state.state = AndroidRuntimeState::Error;
    inner.state.active = false;
    inner.state.last_error = Some(message.clone());
    if let Some(unix_ms) = unix_ms() {
        push_log(
            &inner.logs,
            AndroidLogEvent { unix_ms, level: "error".to_owned(), message: message.clone() },
        );
    }
    message
}

/// Append `event` to `logs`, surfacing (rather than silently discarding) a poisoned
/// log-buffer mutex. The lifecycle event this accompanies is already recorded in
/// `RuntimeInner::state.last_error`/`status()`, so a lost log entry here is a
/// secondary diagnostics-only failure, not the primary one.
pub(crate) fn push_log(logs: &LogBuffer, event: AndroidLogEvent) {
    if let Err(reason) = logs.push(event) {
        tracing::error!(%reason, "failed to append to the Android log buffer");
    }
}

/// Last known-good Unix ms, reused if the clock ever reads before the epoch so a diagnostics
/// timestamp degrades to a real prior value instead of an invented zero (FIX6 P2-002).
static LAST_UNIX_MS: AtomicU64 = AtomicU64::new(0);

/// Diagnostics-only timestamp (FIX7 P0-010-E): `None` when the clock has never once succeeded
/// (no invented zero), otherwise the fresh reading or the last known-good value on a subsequent
/// failure. Callers must skip the optional log entry / leave `started_at_unix_ms` unset on
/// `None` rather than substitute zero — runtime state and `last_error` are unaffected either
/// way, since neither depends on this value.
pub(crate) fn unix_ms() -> Option<u64> {
    let fresh = match unix_time_ms() {
        Ok(ms) => Some(ms),
        Err(err) => {
            tracing::error!(%err, "system clock is before the unix epoch; reusing last known timestamp");
            None
        }
    };
    resolve_optional_unix_ms(fresh, &LAST_UNIX_MS)
}
