use std::path::PathBuf;
use std::sync::atomic::AtomicU64;

use p2p_core::{DaemonState, NodeRole};
use tokio::io::AsyncWriteExt;

use super::atomic::{open_unique_temp_file, write_atomic};
use super::types::{
    DaemonStatus, ForwardListenState, ForwardRuntimeStatus, SessionStatus, StatusAuditLog,
};
use super::writer::StatusWriter;

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
    let writer = StatusWriter { enabled: false, path: PathBuf::new(), sink: Some(tx), audit: None };
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
    let writer = StatusWriter { enabled: true, path: temp_path.clone(), sink: None, audit: None };
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
    let temp_path =
        std::env::temp_dir().join(format!("p2ptunnel-status-multi-{}.json", std::process::id()));
    let writer = StatusWriter { enabled: true, path: temp_path.clone(), sink: None, audit: None };
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
    let writer = StatusWriter { enabled: true, path: temp_path.clone(), sink: None, audit: None };
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

/// Regression test for P1-006: a stale temp file left behind at the exact name a
/// writer's first sequence draw would produce (e.g. debris from a crashed prior
/// process reusing this PID) must not fail the whole write. Uses a private,
/// freshly-zeroed sequence counter rather than the real, process-shared
/// `STATUS_TEMP_SEQUENCE` — this file's own stress tests below perform hundreds of
/// `write_atomic` calls that run concurrently with every other `#[tokio::test]` in
/// this module, so predicting the shared counter's live value here would be
/// inherently racy.
#[tokio::test]
async fn open_unique_temp_file_skips_a_stale_collision_and_leaves_it_untouched() {
    let dir =
        std::env::temp_dir().join(format!("p2ptunnel-atomic-collision-{}", std::process::id()));
    tokio::fs::create_dir_all(&dir).await.expect("create test dir");
    let path = dir.join("status.json");
    let local_sequence = AtomicU64::new(0);

    // Occupies the exact path the first sequence draw (0) will produce.
    let stale_temp_path = dir.join(format!(".status.json.tmp-{}-0", std::process::id()));
    tokio::fs::write(&stale_temp_path, b"stale debris from a crashed prior process")
        .await
        .expect("pre-create stale temp file");

    let (mut file, temp_path) = open_unique_temp_file(&dir, "status.json", &local_sequence)
        .await
        .expect("should skip the collision and open a fresh temp file instead of failing");
    assert_ne!(temp_path, stale_temp_path, "must not reuse the colliding stale name");

    file.write_all(b"{\"ok\":true}").await.expect("write should succeed");
    file.flush().await.expect("flush should succeed");
    drop(file);
    tokio::fs::rename(&temp_path, &path).await.expect("rename into place should succeed");

    // Stale file remains untouched.
    assert_eq!(
        tokio::fs::read(&stale_temp_path).await.expect("stale file should still exist"),
        b"stale debris from a crashed prior process",
    );
    // Target JSON valid.
    assert_eq!(tokio::fs::read(&path).await.expect("read target"), b"{\"ok\":true}");
    // New temp cleaned (renamed away): only the target and the untouched stale file remain.
    let mut entries = tokio::fs::read_dir(&dir).await.expect("read dir");
    let mut names = Vec::new();
    while let Some(entry) = entries.next_entry().await.expect("dir entry") {
        names.push(entry.file_name());
    }
    assert_eq!(
        names.len(),
        2,
        "expected only status.json and the untouched stale file, got {names:?}"
    );

    let _ = tokio::fs::remove_dir_all(&dir).await;
}

#[tokio::test]
async fn concurrent_writes_and_reads_never_observe_partial_json() {
    let path =
        std::env::temp_dir().join(format!("p2ptunnel-atomic-stress-{}.json", std::process::id()));
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
    write_atomic(&path, br#"{"writer":"seed","seq":0}"#).await.expect("seed write should succeed");

    const WRITER_COUNT: u32 = 8;
    const ITERATIONS: u32 = 50;

    let writers = (0..WRITER_COUNT).map(|writer_id| {
        let writer_path = path.clone();
        tokio::spawn(async move {
            for seq in 0..ITERATIONS {
                let body = format!(r#"{{"writer":"{writer_id}","seq":{seq}}}"#);
                write_atomic(&writer_path, body.as_bytes()).await.expect("write should succeed");
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
                assert!(parsed["writer"].is_string(), "every observed document must be complete");
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
