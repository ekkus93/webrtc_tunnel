use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use p2p_core::{AppConfig, DaemonState, NodeRole, PeerId, SessionId};
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;

use crate::DaemonError;

/// Distinguishes concurrent same-process writers to the same status path (e.g.
/// two forwards, or a session status update racing a steady-state one), so their
/// temp files never collide even though `std::process::id()` alone is identical
/// for all of them.
static STATUS_TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);

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

    fn record(&self, status: DaemonStatus) {
        self.events.lock().expect("status audit log mutex poisoned").push(status);
    }
}

/// Replaces `path`'s contents atomically: writes to a same-directory temporary
/// file, flushes it, then renames it over `path`. A reader can therefore only
/// ever see the previous complete content or the new complete content — never a
/// partially-written file — even under concurrent writer/reader stress. Staying
/// in the same directory keeps the rename on one filesystem (required for
/// `rename` to be atomic on Linux/macOS).
async fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), std::io::Error> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    tokio::fs::create_dir_all(parent).await?;

    let file_name = path.file_name().and_then(|name| name.to_str()).unwrap_or("status.json");
    let sequence = STATUS_TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let temp_path = parent.join(format!(".{file_name}.tmp-{}-{sequence}", std::process::id()));

    let write_result = async {
        // create_new (O_EXCL) rather than create/truncate: if two writers ever did
        // compute the same temp path, this fails loudly instead of one silently
        // truncating the other's in-flight write.
        let mut file =
            tokio::fs::OpenOptions::new().write(true).create_new(true).open(&temp_path).await?;
        file.write_all(bytes).await?;
        file.flush().await?;
        drop(file);
        tokio::fs::rename(&temp_path, path).await
    }
    .await;

    if write_result.is_err()
        && let Err(cleanup_error) = tokio::fs::remove_file(&temp_path).await
        && cleanup_error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(
            reason = %cleanup_error,
            path = %temp_path.display(),
            "failed to remove status temporary file",
        );
    }

    write_result
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

pub struct StatusWriter {
    enabled: bool,
    path: PathBuf,
    /// Optional latest-value sink. When present, every `DaemonStatus` is broadcast
    /// here in addition to (or instead of) being written to the status file. This
    /// is how the Android runtime observes real daemon status; the desktop CLI
    /// leaves it `None` and is unaffected.
    sink: Option<tokio::sync::watch::Sender<DaemonStatus>>,
    /// Optional non-coalescing test/debug audit recorder — see [`StatusAuditLog`].
    /// Always `None` in ordinary production use.
    #[cfg(any(test, debug_assertions))]
    audit: Option<StatusAuditLog>,
}

impl StatusWriter {
    pub fn new(config: &AppConfig) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: None,
            #[cfg(any(test, debug_assertions))]
            audit: None,
        }
    }

    pub fn with_sink(config: &AppConfig, sink: tokio::sync::watch::Sender<DaemonStatus>) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: Some(sink),
            #[cfg(any(test, debug_assertions))]
            audit: None,
        }
    }

    /// Test/debug-only: records every write attempt to `audit` in addition to the
    /// usual file/sink behavior. See [`StatusAuditLog`].
    #[cfg(any(test, debug_assertions))]
    pub fn with_audit(config: &AppConfig, audit: StatusAuditLog) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: None,
            audit: Some(audit),
        }
    }

    /// Test/debug-only: both a latest-state `watch` sink and a non-coalescing
    /// audit recorder, for tests that need to assert on both.
    #[cfg(any(test, debug_assertions))]
    pub fn with_sink_and_audit(
        config: &AppConfig,
        sink: tokio::sync::watch::Sender<DaemonStatus>,
        audit: StatusAuditLog,
    ) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: Some(sink),
            audit: Some(audit),
        }
    }

    pub async fn write(&self, status: DaemonStatus) -> Result<(), DaemonError> {
        // Record every attempted write before anything else, so the audit log is
        // a complete history regardless of whether the sink/file steps below
        // succeed, get skipped (disabled), or coalesce.
        #[cfg(any(test, debug_assertions))]
        if let Some(audit) = &self.audit {
            audit.record(status.clone());
        }
        // Broadcast to the sink first so observers see updates even when status-file
        // writing is disabled. A closed receiver is not an error for the daemon.
        if let Some(sink) = &self.sink {
            let _ = sink.send(status.clone());
        }
        if !self.enabled {
            return Ok(());
        }
        let json = serde_json::to_vec_pretty(&status)
            .map_err(|error| DaemonError::Logging(error.to_string()))?;
        write_atomic(&self.path, &json).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use p2p_core::{DaemonState, NodeRole};

    use super::{
        DaemonStatus, ForwardListenState, ForwardRuntimeStatus, SessionStatus, StatusAuditLog,
        StatusWriter, write_atomic,
    };

    #[test]
    fn forward_runtime_status_serializes_snake_case_without_secrets() {
        let listening = ForwardRuntimeStatus::listening("web");
        assert_eq!(listening.listen_state, ForwardListenState::Listening);
        let json = serde_json::to_value(&listening).expect("serialize");
        assert_eq!(json["id"], "web");
        assert_eq!(json["listen_state"], "listening");
        assert!(json["last_error"].is_null());

        let errored = ForwardRuntimeStatus::error("ssh", "Address already in use");
        let json = serde_json::to_value(&errored).expect("serialize");
        assert_eq!(json["listen_state"], "error");
        assert_eq!(json["last_error"], "Address already in use");
    }

    #[test]
    fn stopped_forward_status_has_no_error() {
        let status = ForwardRuntimeStatus::stopped("ssh");
        assert_eq!(status.listen_state, ForwardListenState::Stopped);
        assert!(status.last_error.is_none());
    }

    #[test]
    fn stopped_forward_status_serializes_truthfully() {
        let status = ForwardRuntimeStatus::stopped("ssh");
        let json = serde_json::to_value(&status).expect("serialize");
        assert_eq!(json["id"], "ssh");
        assert_eq!(json["listen_state"], "stopped");
        assert!(json["last_error"].is_null());
    }

    #[test]
    fn stopped_preserving_error_keeps_prior_error_but_reports_stopped() {
        let errored = ForwardRuntimeStatus::error("ssh", "Address already in use");
        let stopped = ForwardRuntimeStatus::stopped_preserving_error(&errored);
        assert_eq!(stopped.id, "ssh");
        assert_eq!(stopped.listen_state, ForwardListenState::Stopped);
        assert_eq!(stopped.last_error.as_deref(), Some("Address already in use"));

        let listening = ForwardRuntimeStatus::listening("web");
        let stopped_clean = ForwardRuntimeStatus::stopped_preserving_error(&listening);
        assert_eq!(stopped_clean.listen_state, ForwardListenState::Stopped);
        assert!(stopped_clean.last_error.is_none());
    }

    #[test]
    fn daemon_status_forwards_default_empty_and_attachable() {
        let base = DaemonStatus::new(
            "offer-home".parse().expect("peer id"),
            NodeRole::Offer,
            true,
            None,
            DaemonState::Idle,
            vec!["web".to_owned()],
        );
        assert!(base.forwards.is_empty());
        let with = base.with_forward_statuses(vec![ForwardRuntimeStatus::listening("web")]);
        let json = serde_json::to_value(&with).expect("serialize");
        assert_eq!(json["forwards"][0]["id"], "web");
        assert_eq!(json["forwards"][0]["listen_state"], "listening");
        assert!(json["forwards"][0]["last_error"].is_null());
    }

    #[tokio::test]
    async fn write_broadcasts_to_sink_even_when_file_disabled() {
        let seed = DaemonStatus::new(
            "offer-home".parse().expect("peer id"),
            NodeRole::Offer,
            false,
            None,
            DaemonState::Idle,
            vec!["ssh".to_owned()],
        );
        let (tx, rx) = tokio::sync::watch::channel(seed);
        // File writing disabled: the sink must still receive updates.
        let writer =
            StatusWriter { enabled: false, path: PathBuf::new(), sink: Some(tx), audit: None };
        let updated = DaemonStatus::new(
            "offer-home".parse().expect("peer id"),
            NodeRole::Offer,
            true,
            None,
            DaemonState::TunnelOpen,
            vec!["ssh".to_owned()],
        );
        writer.write(updated.clone()).await.expect("write should succeed");
        assert_eq!(*rx.borrow(), updated);
    }

    #[tokio::test]
    async fn writes_status_json_without_secrets() {
        let temp_path =
            std::env::temp_dir().join(format!("p2ptunnel-status-{}.json", std::process::id()));
        let writer =
            StatusWriter { enabled: true, path: temp_path.clone(), sink: None, audit: None };
        writer
            .write(DaemonStatus::new(
                "offer-home".parse().expect("peer id"),
                NodeRole::Offer,
                true,
                Some((
                    p2p_core::SessionId::new([7_u8; 16]),
                    "answer-office".parse().expect("remote peer id"),
                )),
                DaemonState::Idle,
                vec!["ssh".to_owned(), "web-ui".to_owned()],
            ))
            .await
            .expect("status file should write");
        let content = tokio::fs::read_to_string(&temp_path).await.expect("status file should read");
        assert!(content.contains("\"peer_id\""));
        assert!(content.contains("\"configured_forwards\""));
        assert!(content.contains("\"active_session_count\""));
        assert!(content.contains("\"sessions\""));
        assert!(content.contains("\"ssh\""));
        // Regression guard: the session's remote_peer_id must be the actual remote,
        // never the local peer_id (the old self-targeting display bug).
        let json: serde_json::Value = serde_json::from_str(&content).expect("status json");
        assert_eq!(json["sessions"][0]["remote_peer_id"], "answer-office");
        assert_ne!(json["sessions"][0]["remote_peer_id"], "offer-home");
        assert!(!content.contains("\"active_stream_count\""));
        assert!(!content.contains("\"open_forward_ids\""));
        assert!(!content.contains("private"));
        let _ = tokio::fs::remove_file(PathBuf::from(&temp_path)).await;
    }

    #[tokio::test]
    async fn writes_multi_session_status_json() {
        let temp_path = std::env::temp_dir()
            .join(format!("p2ptunnel-status-multi-{}.json", std::process::id()));
        let writer =
            StatusWriter { enabled: true, path: temp_path.clone(), sink: None, audit: None };
        writer
            .write(DaemonStatus::with_sessions(
                "answer-office".parse().expect("peer id"),
                NodeRole::Answer,
                true,
                DaemonState::Idle,
                vec!["ssh".to_owned()],
                16,
                vec![SessionStatus::new(
                    p2p_core::SessionId::new([8_u8; 16]),
                    "offer-home".parse().expect("remote peer id"),
                    DaemonState::TunnelOpen,
                    true,
                    vec!["ssh".to_owned()],
                )],
            ))
            .await
            .expect("status file should write");
        let content = tokio::fs::read_to_string(&temp_path).await.expect("status file should read");
        let json: serde_json::Value = serde_json::from_str(&content).expect("status json");
        assert_eq!(json["active_session_count"], 1);
        assert_eq!(json["session_capacity"], 16);
        assert_eq!(json["active_session_id"], p2p_core::SessionId::new([8_u8; 16]).to_string());
        assert_eq!(json["sessions"][0]["remote_peer_id"], "offer-home");
        assert_eq!(json["sessions"][0]["configured_forward_ids"][0], "ssh");
        assert!(json["sessions"][0]["active_stream_count"].is_null());
        assert!(json["sessions"][0]["open_forward_ids"].is_null());
        let _ = tokio::fs::remove_file(PathBuf::from(&temp_path)).await;
    }

    #[tokio::test]
    async fn writes_multi_session_aggregate_without_single_active_session_id() {
        let temp_path = std::env::temp_dir()
            .join(format!("p2ptunnel-status-aggregate-{}.json", std::process::id()));
        let writer =
            StatusWriter { enabled: true, path: temp_path.clone(), sink: None, audit: None };
        writer
            .write(DaemonStatus::with_sessions(
                "answer-office".parse().expect("peer id"),
                NodeRole::Answer,
                true,
                DaemonState::Serving,
                vec!["ssh".to_owned(), "web-ui".to_owned()],
                16,
                vec![
                    SessionStatus::new(
                        p2p_core::SessionId::new([8_u8; 16]),
                        "offer-home".parse().expect("remote peer id"),
                        DaemonState::TunnelOpen,
                        true,
                        vec!["ssh".to_owned()],
                    ),
                    SessionStatus::new(
                        p2p_core::SessionId::new([9_u8; 16]),
                        "offer-desktop".parse().expect("remote peer id"),
                        DaemonState::ConnectingDataChannel,
                        false,
                        vec!["web-ui".to_owned()],
                    ),
                ],
            ))
            .await
            .expect("status file should write");

        let content = tokio::fs::read_to_string(&temp_path).await.expect("status file should read");
        let json: serde_json::Value = serde_json::from_str(&content).expect("status json");
        let sessions = json["sessions"].as_array().expect("sessions");
        assert_eq!(json["current_state"], "serving");
        assert_eq!(json["active_session_count"], sessions.len());
        assert!(json["active_session_id"].is_null());
        assert_eq!(sessions.len(), 2);
        assert!(content.contains("\"configured_forward_ids\""));
        assert!(!content.contains("\"active_stream_count\""));
        assert!(!content.contains("\"open_forward_ids\""));
        let _ = tokio::fs::remove_file(PathBuf::from(&temp_path)).await;
    }

    #[test]
    fn current_status_schema_exposes_only_stable_public_fields() {
        let status = DaemonStatus::with_sessions(
            "answer-office".parse().expect("peer id"),
            NodeRole::Answer,
            true,
            DaemonState::Serving,
            vec!["ssh".to_owned(), "web-ui".to_owned()],
            16,
            vec![SessionStatus::new(
                p2p_core::SessionId::new([8_u8; 16]),
                "offer-home".parse().expect("remote peer id"),
                DaemonState::TunnelOpen,
                true,
                vec!["ssh".to_owned()],
            )],
        );

        let json = serde_json::to_value(status).expect("status should serialize");
        for field in [
            "peer_id",
            "role",
            "mqtt_connected",
            "active_session_id",
            "current_state",
            "active_session_count",
            "session_capacity",
            "sessions",
            "configured_forwards",
            "forwards",
        ] {
            assert!(json.get(field).is_some(), "missing status field {field}");
        }
        assert!(json.get("active_stream_count").is_none());
        assert!(json.get("open_forward_ids").is_none());

        let session = &json["sessions"][0];
        for field in
            ["session_id", "remote_peer_id", "state", "data_channel_open", "configured_forward_ids"]
        {
            assert!(session.get(field).is_some(), "missing session field {field}");
        }
        assert!(session.get("active_stream_count").is_none());
        assert!(session.get("open_forward_ids").is_none());
    }

    #[test]
    fn active_session_id_is_only_populated_for_exactly_one_session() {
        let zero = DaemonStatus::with_sessions(
            "answer-office".parse().expect("peer id"),
            NodeRole::Answer,
            true,
            DaemonState::Serving,
            vec!["ssh".to_owned()],
            16,
            Vec::new(),
        );
        assert!(zero.active_session_id.is_none());
        assert_eq!(zero.active_session_count, 0);

        let one_session_id = p2p_core::SessionId::new([8_u8; 16]);
        let one = DaemonStatus::with_sessions(
            "answer-office".parse().expect("peer id"),
            NodeRole::Answer,
            true,
            DaemonState::Serving,
            vec!["ssh".to_owned()],
            16,
            vec![SessionStatus::new(
                one_session_id,
                "offer-home".parse().expect("remote peer id"),
                DaemonState::TunnelOpen,
                true,
                vec!["ssh".to_owned()],
            )],
        );
        let one_session_id_text = one_session_id.to_string();
        assert_eq!(one.active_session_id.as_deref(), Some(one_session_id_text.as_str()));
        assert_eq!(one.active_session_count, 1);

        let many = DaemonStatus::with_sessions(
            "answer-office".parse().expect("peer id"),
            NodeRole::Answer,
            true,
            DaemonState::Serving,
            vec!["ssh".to_owned()],
            16,
            vec![
                SessionStatus::new(
                    p2p_core::SessionId::new([8_u8; 16]),
                    "offer-home".parse().expect("remote peer id"),
                    DaemonState::TunnelOpen,
                    true,
                    vec!["ssh".to_owned()],
                ),
                SessionStatus::new(
                    p2p_core::SessionId::new([9_u8; 16]),
                    "offer-desktop".parse().expect("remote peer id"),
                    DaemonState::TunnelOpen,
                    true,
                    vec!["ssh".to_owned()],
                ),
            ],
        );
        assert!(many.active_session_id.is_none());
        assert_eq!(many.active_session_count, 2);
    }

    #[test]
    fn status_schema_handles_zero_forwards_and_disconnected_active_sessions() {
        let zero_forwards = DaemonStatus::with_sessions(
            "answer-office".parse().expect("peer id"),
            NodeRole::Answer,
            true,
            DaemonState::Serving,
            Vec::new(),
            16,
            Vec::new(),
        );
        let json = serde_json::to_value(zero_forwards).expect("status should serialize");
        assert!(
            json["configured_forwards"]
                .as_array()
                .expect("configured_forwards should be an array")
                .is_empty()
        );
        assert_eq!(json["active_session_count"], 0);

        let disconnected = DaemonStatus::with_sessions(
            "answer-office".parse().expect("peer id"),
            NodeRole::Answer,
            false,
            DaemonState::TunnelOpen,
            vec!["ssh".to_owned()],
            16,
            vec![SessionStatus::new(
                p2p_core::SessionId::new([8_u8; 16]),
                "offer-home".parse().expect("remote peer id"),
                DaemonState::TunnelOpen,
                true,
                vec!["ssh".to_owned()],
            )],
        );
        let json = serde_json::to_value(disconnected).expect("status should serialize");
        assert_eq!(json["mqtt_connected"], false);
        assert_eq!(json["active_session_count"], 1);
        assert_eq!(json["sessions"][0]["configured_forward_ids"][0], "ssh");
    }

    #[tokio::test]
    async fn write_atomic_creates_parent_directories_and_replaces_content() {
        let dir = std::env::temp_dir().join(format!("p2ptunnel-atomic-{}", std::process::id()));
        let path = dir.join("nested").join("status.json");

        write_atomic(&path, b"first").await.expect("first write should succeed");
        assert_eq!(tokio::fs::read(&path).await.expect("read first"), b"first");

        write_atomic(&path, b"second-and-longer").await.expect("second write should succeed");
        assert_eq!(tokio::fs::read(&path).await.expect("read second"), b"second-and-longer");

        // No leftover temp file from either write.
        let mut entries = tokio::fs::read_dir(&dir.join("nested")).await.expect("read dir");
        let mut names = Vec::new();
        while let Some(entry) = entries.next_entry().await.expect("dir entry") {
            names.push(entry.file_name());
        }
        assert_eq!(names, vec![std::ffi::OsString::from("status.json")]);

        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    #[tokio::test]
    async fn write_atomic_fails_when_parent_cannot_be_created() {
        let blocking_file =
            std::env::temp_dir().join(format!("p2ptunnel-atomic-blocker-{}", std::process::id()));
        tokio::fs::write(&blocking_file, b"occupied").await.expect("blocking file should exist");
        let path = blocking_file.join("status.json");

        let result = write_atomic(&path, b"unused").await;

        assert!(result.is_err(), "cannot create a directory where a file already exists");
        let _ = tokio::fs::remove_file(&blocking_file).await;
    }

    #[tokio::test]
    async fn concurrent_writes_and_reads_never_observe_partial_json() {
        let path = std::env::temp_dir()
            .join(format!("p2ptunnel-atomic-stress-{}.json", std::process::id()));
        write_atomic(&path, b"{\"seq\":0}").await.expect("seed write should succeed");

        let writer_path = path.clone();
        let writer = tokio::spawn(async move {
            for seq in 1..200_u32 {
                let body = format!("{{\"seq\":{seq}}}");
                write_atomic(&writer_path, body.as_bytes()).await.expect("write should succeed");
            }
        });

        let reader_path = path.clone();
        let reader = tokio::spawn(async move {
            for _ in 0..400 {
                if let Ok(bytes) = tokio::fs::read(&reader_path).await {
                    let parsed: serde_json::Value =
                        serde_json::from_slice(&bytes).unwrap_or_else(|error| {
                            panic!(
                                "reader observed invalid/partial JSON: {error} (bytes: {:?})",
                                String::from_utf8_lossy(&bytes)
                            )
                        });
                    assert!(parsed["seq"].is_u64());
                }
            }
        });

        let (writer_result, reader_result) = tokio::join!(writer, reader);
        writer_result.expect("writer task should not panic");
        reader_result.expect("reader task should not panic");
        let _ = tokio::fs::remove_file(&path).await;
    }

    /// Regression test for P1-008: with several *genuinely concurrent* writers (not
    /// one task writing sequentially, which can never exercise a temp-path
    /// collision), the old `.{file_name}.tmp-{pid}` temp path — identical for
    /// every writer in this same process — let one writer's `File::create`
    /// truncate another's in-flight temp file. Each writer here emits a distinct
    /// document (its own id) so a reader observing a torn write would very likely
    /// see a mismatched pair.
    #[tokio::test]
    async fn concurrent_multi_writer_stress_never_produces_malformed_json_or_stale_temp_files() {
        let path = std::env::temp_dir()
            .join(format!("p2ptunnel-atomic-multiwriter-{}.json", std::process::id()));
        write_atomic(&path, br#"{"writer":"seed","seq":0}"#)
            .await
            .expect("seed write should succeed");

        const WRITER_COUNT: u32 = 8;
        const ITERATIONS: u32 = 50;

        let writers = (0..WRITER_COUNT).map(|writer_id| {
            let writer_path = path.clone();
            tokio::spawn(async move {
                for seq in 0..ITERATIONS {
                    let body = format!(r#"{{"writer":"{writer_id}","seq":{seq}}}"#);
                    write_atomic(&writer_path, body.as_bytes())
                        .await
                        .expect("write should succeed");
                }
            })
        });

        let reader_path = path.clone();
        let reader = tokio::spawn(async move {
            for _ in 0..(WRITER_COUNT * ITERATIONS * 2) {
                if let Ok(bytes) = tokio::fs::read(&reader_path).await {
                    let parsed: serde_json::Value =
                        serde_json::from_slice(&bytes).unwrap_or_else(|error| {
                            panic!(
                                "reader observed invalid/partial JSON: {error} (bytes: {:?})",
                                String::from_utf8_lossy(&bytes)
                            )
                        });
                    assert!(
                        parsed["writer"].is_string(),
                        "every observed document must be complete"
                    );
                    assert!(parsed["seq"].is_u64(), "every observed document must be complete");
                }
            }
        });

        for writer in writers {
            writer.await.expect("writer task should not panic");
        }
        reader.await.expect("reader task should not panic");

        let parent = path.parent().expect("status path should have a parent");
        let file_name = path.file_name().and_then(|name| name.to_str()).expect("status file name");
        let mut entries = tokio::fs::read_dir(parent).await.expect("temp dir should be readable");
        let mut stale_temp_files = Vec::new();
        while let Some(entry) = entries.next_entry().await.expect("dir entry should read") {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if name.starts_with(&format!(".{file_name}.tmp-")) {
                stale_temp_files.push(name.into_owned());
            }
        }
        assert!(
            stale_temp_files.is_empty(),
            "no stale temp files may remain after every writer succeeds, found {stale_temp_files:?}"
        );

        let _ = tokio::fs::remove_file(&path).await;
    }

    fn sample_status(state: DaemonState) -> DaemonStatus {
        DaemonStatus::new(
            "offer-home".parse().expect("peer id"),
            NodeRole::Offer,
            true,
            None,
            state,
            vec!["ssh".to_owned()],
        )
    }

    #[tokio::test]
    async fn status_audit_log_retains_every_write_in_order() {
        let audit = StatusAuditLog::default();
        let writer = StatusWriter {
            enabled: false,
            path: PathBuf::new(),
            sink: None,
            audit: Some(audit.clone()),
        };

        let a = sample_status(DaemonState::WaitingForLocalClient);
        let b = sample_status(DaemonState::Negotiating);
        let c = sample_status(DaemonState::TunnelOpen);
        writer.write(a.clone()).await.expect("write A should succeed");
        writer.write(b.clone()).await.expect("write B should succeed");
        writer.write(c.clone()).await.expect("write C should succeed");

        assert_eq!(audit.len(), 3);
        assert_eq!(audit.snapshot(), vec![a, b, c]);
    }

    #[tokio::test]
    async fn watch_coalescing_does_not_affect_audit() {
        // This test documents why StatusAuditLog exists alongside the watch sink:
        // a watch::Receiver is deliberately latest-value-only for its real
        // (Android/UI) consumers, so sampling it after a burst of writes can never
        // prove every intermediate state was actually emitted — only that the
        // final one isn't illegal. The audit log is the only trustworthy source
        // for that proof.
        let audit = StatusAuditLog::default();
        let seed = sample_status(DaemonState::Idle);
        let (tx, rx) = tokio::sync::watch::channel(seed.clone());
        let writer = StatusWriter {
            enabled: false,
            path: PathBuf::new(),
            sink: Some(tx),
            audit: Some(audit.clone()),
        };

        let a = sample_status(DaemonState::WaitingForLocalClient);
        let b = sample_status(DaemonState::Negotiating);
        let c = sample_status(DaemonState::TunnelOpen);
        // No polling of `rx` between writes: the watch channel only ever holds its
        // single latest value, so it necessarily coalesces A and B away.
        writer.write(a.clone()).await.expect("write A should succeed");
        writer.write(b.clone()).await.expect("write B should succeed");
        writer.write(c.clone()).await.expect("write C should succeed");

        assert_eq!(*rx.borrow(), c, "watch must show only the latest write");
        assert_eq!(
            audit.snapshot(),
            vec![a, b, c],
            "audit must retain every write in order despite watch coalescing"
        );
    }

    #[tokio::test]
    async fn status_audit_log_clone_shares_same_log() {
        let audit = StatusAuditLog::default();
        let audit_clone = audit.clone();
        let writer =
            StatusWriter { enabled: false, path: PathBuf::new(), sink: None, audit: Some(audit) };

        let status = sample_status(DaemonState::Serving);
        writer.write(status.clone()).await.expect("write should succeed");

        assert_eq!(audit_clone.snapshot(), vec![status]);
    }
}
