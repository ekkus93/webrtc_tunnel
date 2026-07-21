//! Status data model: [`DaemonStatus`] and its constituents, plus the test/debug-only
//! [`StatusAuditLog`].

use p2p_core::{DaemonState, NodeRole, PeerId, SessionId};
use serde::{Deserialize, Serialize};

/// A non-coalescing, append-only record of every status write *attempt* (before
/// any optional file I/O). A `watch::Sender<DaemonStatus>` is deliberately
/// latest-value-only for its real (Android/UI) consumers — that's correct for
/// them, but it means a test that only samples the `watch` receiver can never
/// prove an illegal intermediate state was *not* emitted, only that the final
/// observed value doesn't happen to be it. This gives a regression test an exact,
/// trustworthy shutdown boundary: `let boundary = audit.len(); shutdown.request_shutdown();
/// ...; assert none of audit.snapshot()[boundary..] is illegal`.
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Default)]
pub struct StatusAuditLog {
    events: std::sync::Arc<std::sync::Mutex<Vec<DaemonStatus>>>,
}

#[cfg(any(test, debug_assertions))]
impl StatusAuditLog {
    pub fn len(&self) -> usize {
        self.events.lock().expect("status audit log mutex poisoned").len()
    }

    pub fn is_empty(&self) -> bool {
        self.events.lock().expect("status audit log mutex poisoned").is_empty()
    }

    pub fn snapshot(&self) -> Vec<DaemonStatus> {
        self.events.lock().expect("status audit log mutex poisoned").clone()
    }

    pub(super) fn record(&self, status: DaemonStatus) {
        self.events.lock().expect("status audit log mutex poisoned").push(status);
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct DaemonStatus {
    pub peer_id: PeerId,
    pub role: NodeRole,
    pub mqtt_connected: bool,
    pub active_session_id: Option<String>,
    pub current_state: DaemonState,
    pub active_session_count: usize,
    pub session_capacity: usize,
    pub sessions: Vec<SessionStatus>,
    pub configured_forwards: Vec<String>,
    /// Per-forward runtime state (offer role). Empty unless populated by the daemon.
    pub forwards: Vec<ForwardRuntimeStatus>,
}

/// Runtime state of a single configured forward's local listener (offer role).
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForwardListenState {
    /// Local TCP listener is actually bound and accepting connections.
    Listening,
    /// Not currently listening (daemon stopped or forward torn down).
    #[default]
    Stopped,
    /// Local listener failed to bind.
    Error,
}

/// Per-forward runtime status surfaced to clients (e.g. the Android UI). Only the
/// offer role binds local listeners, so this reflects the offer side; it never
/// carries secret material.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ForwardRuntimeStatus {
    pub id: String,
    pub listen_state: ForwardListenState,
    pub last_error: Option<String>,
}

impl ForwardRuntimeStatus {
    pub fn listening(id: impl Into<String>) -> Self {
        Self { id: id.into(), listen_state: ForwardListenState::Listening, last_error: None }
    }

    pub fn error(id: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            listen_state: ForwardListenState::Error,
            last_error: Some(message.into()),
        }
    }

    /// A forward with no prior runtime status, reported as stopped (e.g. before any
    /// listener has been bound). Has no `last_error` since it never ran.
    pub fn stopped(id: impl Into<String>) -> Self {
        Self { id: id.into(), listen_state: ForwardListenState::Stopped, last_error: None }
    }

    /// Terminal shutdown status for a forward that already has a runtime status.
    /// Always reports `Stopped` (shutdown is unconditional), but keeps the existing
    /// `last_error` rather than nulling it: `listen_state` answers "is this running
    /// now?" and `last_error` separately answers "what most recently went wrong?" —
    /// a forward that never successfully bound should still show that diagnostic
    /// after shutdown.
    pub fn stopped_preserving_error(existing: &Self) -> Self {
        Self {
            id: existing.id.clone(),
            listen_state: ForwardListenState::Stopped,
            last_error: existing.last_error.clone(),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SessionStatus {
    pub session_id: String,
    pub remote_peer_id: PeerId,
    pub state: DaemonState,
    pub data_channel_open: bool,
    pub configured_forward_ids: Vec<String>,
}

impl SessionStatus {
    pub fn new(
        session_id: SessionId,
        remote_peer_id: PeerId,
        state: DaemonState,
        data_channel_open: bool,
        configured_forward_ids: Vec<String>,
    ) -> Self {
        Self {
            session_id: session_id.to_string(),
            remote_peer_id,
            state,
            data_channel_open,
            configured_forward_ids,
        }
    }
}

impl DaemonStatus {
    pub fn new(
        peer_id: PeerId,
        role: NodeRole,
        mqtt_connected: bool,
        active_session: Option<(SessionId, PeerId)>,
        current_state: DaemonState,
        configured_forwards: Vec<String>,
    ) -> Self {
        // The session's `remote_peer_id` must be the actual remote peer, never the
        // local `peer_id`. Bundling the id with its remote here makes the historical
        // "stamp the local peer as the session remote" bug structurally impossible.
        let sessions = active_session
            .map(|(id, remote_peer_id)| {
                vec![SessionStatus::new(
                    id,
                    remote_peer_id,
                    current_state,
                    matches!(current_state, DaemonState::TunnelOpen),
                    configured_forwards.clone(),
                )]
            })
            .unwrap_or_default();
        Self::with_sessions(
            peer_id,
            role,
            mqtt_connected,
            current_state,
            configured_forwards,
            1,
            sessions,
        )
    }

    pub fn with_sessions(
        peer_id: PeerId,
        role: NodeRole,
        mqtt_connected: bool,
        current_state: DaemonState,
        configured_forwards: Vec<String>,
        session_capacity: usize,
        sessions: Vec<SessionStatus>,
    ) -> Self {
        let active_session_count = sessions.len();
        let active_session_id = (sessions.len() == 1).then(|| sessions[0].session_id.clone());
        Self {
            peer_id,
            role,
            mqtt_connected,
            active_session_id,
            current_state,
            active_session_count,
            session_capacity,
            sessions,
            configured_forwards,
            forwards: Vec::new(),
        }
    }

    /// Attach per-forward runtime statuses, returning the updated status.
    pub fn with_forward_statuses(mut self, forwards: Vec<ForwardRuntimeStatus>) -> Self {
        self.forwards = forwards;
        self
    }
}
