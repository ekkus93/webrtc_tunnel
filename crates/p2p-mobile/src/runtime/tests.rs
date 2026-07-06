use std::fs;

use super::*;
use p2p_crypto::generate_identity;

#[test]
fn android_state_mapping_covers_connection_phases() {
    assert_eq!(android_state_from_daemon(DaemonState::TunnelOpen), AndroidRuntimeState::Running);
    assert_eq!(
        android_state_from_daemon(DaemonState::WaitingForLocalClient),
        AndroidRuntimeState::Running,
    );
    assert_eq!(android_state_from_daemon(DaemonState::Negotiating), AndroidRuntimeState::Starting,);
    assert_eq!(android_state_from_daemon(DaemonState::Backoff), AndroidRuntimeState::Starting);
    assert_eq!(android_state_from_daemon(DaemonState::Closed), AndroidRuntimeState::Stopped);
}

#[test]
fn snapshot_status_overlays_daemon_status_when_active() {
    let mut inner = RuntimeInner::default();
    inner.state.active = true;
    let connected = DaemonStatus::with_sessions(
        "offer-home".parse().expect("peer id"),
        NodeRole::Offer,
        true,
        DaemonState::TunnelOpen,
        vec!["ssh".to_owned()],
        16,
        vec![p2p_daemon::SessionStatus::new(
            p2p_core::SessionId::new([7_u8; 16]),
            "answer-office".parse().expect("remote peer id"),
            DaemonState::TunnelOpen,
            true,
            vec!["ssh".to_owned()],
        )],
    );
    let (tx, rx) = tokio::sync::watch::channel(connected);
    inner.status_rx = Some(rx);

    let snapshot = inner.snapshot_status();
    assert!(snapshot.mqtt_connected);
    assert_eq!(snapshot.session_capacity, Some(16));
    assert_eq!(snapshot.state, AndroidRuntimeState::Running);
    // The active session's real remote peer is surfaced (never the local peer id).
    assert_eq!(snapshot.remote_peer_id.as_deref(), Some("answer-office"));
    drop(tx);
}

#[test]
fn snapshot_status_is_quiescent_when_inactive() {
    let mut inner = RuntimeInner::default();
    inner.state.active = false;
    let connected = DaemonStatus::new(
        "offer-home".parse().expect("peer id"),
        NodeRole::Offer,
        true,
        None,
        DaemonState::TunnelOpen,
        Vec::new(),
    );
    let (tx, rx) = tokio::sync::watch::channel(connected);
    inner.status_rx = Some(rx);

    let snapshot = inner.snapshot_status();
    assert!(!snapshot.mqtt_connected);
    assert_eq!(snapshot.active_session_count, 0);
    drop(tx);
}

#[test]
fn validate_config_reports_missing_file() {
    let result = AndroidTunnelController::validate_config("/definitely/missing/config.toml");
    assert!(!result.valid);
    assert!(result.message.is_some());
}

#[test]
fn status_before_start_is_stopped() {
    let controller = AndroidTunnelController::new();
    let status = controller.status();
    assert_eq!(status.state, AndroidRuntimeState::Stopped);
    assert!(!status.active);
}

#[test]
fn stop_before_start_is_safe() {
    let controller = AndroidTunnelController::new();
    assert_eq!(controller.stop(), Ok(()));
    assert_eq!(controller.status().state, AndroidRuntimeState::Stopped);
}

#[test]
fn double_stop_is_safe() {
    let controller = AndroidTunnelController::new();
    assert_eq!(controller.stop(), Ok(()));
    assert_eq!(controller.stop(), Ok(()));
    assert_eq!(controller.status().state, AndroidRuntimeState::Stopped);
}

#[test]
fn stop_requests_cooperative_shutdown_and_waits_for_task() {
    use std::sync::atomic::{AtomicBool, Ordering};

    let controller = AndroidTunnelController::new();
    let runtime = Runtime::new().expect("tokio runtime");
    let shutdown = ShutdownToken::new();
    let completed = Arc::new(AtomicBool::new(false));
    let completed_for_task = Arc::clone(&completed);
    let mut task_shutdown = shutdown.clone();
    let task = runtime.spawn(async move {
        // Only finishes once it observes the shutdown request — proves stop()
        // drives the task to a cooperative finish rather than aborting it.
        task_shutdown.cancelled().await;
        completed_for_task.store(true, Ordering::SeqCst);
    });
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.task = Some(task);
        inner.runtime = Some(runtime);
        inner.shutdown = Some(shutdown);
        inner.state.active = true;
    }

    let result = controller.stop();

    assert!(completed.load(Ordering::SeqCst), "task should complete cooperatively, not be aborted");
    assert_eq!(result, Ok(()), "a graceful stop must report success");
    assert_eq!(controller.status().state, AndroidRuntimeState::Stopped);
}

#[test]
fn stop_forces_abort_after_grace_period_when_task_ignores_shutdown() {
    let controller = AndroidTunnelController::new();
    let runtime = Runtime::new().expect("tokio runtime");
    let shutdown = ShutdownToken::new();
    // Deliberately never checks `shutdown` — simulates a wedged/misbehaving task,
    // so stop() must fall back to a forced abort rather than hanging forever.
    let task = runtime.spawn(async { std::future::pending::<()>().await });
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.task = Some(task);
        inner.runtime = Some(runtime);
        inner.shutdown = Some(shutdown);
        inner.state.active = true;
    }

    let (done_tx, done_rx) = std::sync::mpsc::channel();
    let controller_for_thread = controller.clone();
    std::thread::spawn(move || {
        let result = controller_for_thread.stop_with_grace_period(Duration::from_millis(50));
        let _ = done_tx.send(result);
    });
    let result = done_rx
        .recv_timeout(Duration::from_secs(5))
        .expect("stop should force-abort and return instead of hanging on a wedged task");

    // A forced abort is not a clean stop: it must be visible as an error, both
    // through the returned outcome and through the persisted runtime state —
    // a forced abort must never be reported to Kotlin as a successful stop().
    assert_eq!(result, Ok(StopOutcome::ForcedAbort { grace_period: Duration::from_millis(50) }));
    let status = controller.status();
    assert_eq!(status.state, AndroidRuntimeState::Error);
    assert!(!status.active);
    assert!(
        status.last_error.as_deref().is_some_and(|message| message.contains("forced abort")),
        "last_error should explain the forced abort, got {:?}",
        status.last_error
    );
}

#[test]
fn stop_reports_task_join_failure_as_an_error() {
    let controller = AndroidTunnelController::new();
    let runtime = Runtime::new().expect("tokio runtime");
    let shutdown = ShutdownToken::new();
    // A task that panics immediately, before ever observing shutdown, must
    // surface as a join failure rather than being silently treated as a clean
    // stop just because the abort-after-timeout path wasn't taken.
    let task = runtime.spawn(async { panic!("simulated wedged task panic") });
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.task = Some(task);
        inner.runtime = Some(runtime);
        inner.shutdown = Some(shutdown);
        inner.state.active = true;
    }

    let result = controller.stop();

    assert!(
        matches!(result, Err(ref message) if message.contains("task join failed")),
        "expected a task-join-failure error, got {result:?}"
    );
    let status = controller.status();
    assert_eq!(status.state, AndroidRuntimeState::Error);
    assert!(!status.active);
    assert!(status.last_error.is_some());
}

#[test]
fn forced_abort_diagnostic_survives_a_subsequent_duplicate_stop() {
    let controller = AndroidTunnelController::new();
    let runtime = Runtime::new().expect("tokio runtime");
    let shutdown = ShutdownToken::new();
    let task = runtime.spawn(async { std::future::pending::<()>().await });
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.task = Some(task);
        inner.runtime = Some(runtime);
        inner.shutdown = Some(shutdown);
        inner.state.active = true;
    }

    let first = controller.stop_with_grace_period(Duration::from_millis(50));
    assert_eq!(first, Ok(StopOutcome::ForcedAbort { grace_period: Duration::from_millis(50) }));
    let error_after_first_stop = controller.status().last_error.clone();
    assert!(error_after_first_stop.is_some());

    // Nothing is running anymore, so this is a no-op duplicate stop — it must
    // not silently clear the forced-abort diagnostic the first call recorded.
    assert_eq!(controller.stop(), Ok(()));
    assert_eq!(controller.status().last_error, error_after_first_stop);
    assert_eq!(controller.status().state, AndroidRuntimeState::Error);
}

#[test]
fn recent_logs_json_shape_is_stable() {
    let controller = AndroidTunnelController::new();
    let logs = controller.recent_logs(10).expect("state mutex is not poisoned");
    assert!(logs.is_empty());
    let _ = generate_identity("android-test").expect("identity");
}

#[test]
fn recent_logs_reports_explicit_error_when_state_mutex_is_poisoned() {
    let controller = AndroidTunnelController::new();
    controller.poison_state_mutex_for_test();
    assert_eq!(
        controller.recent_logs(10).expect_err("mutex is poisoned"),
        "runtime mutex poisoned"
    );
}

#[test]
fn last_error_reports_poison_instead_of_none_when_state_mutex_is_poisoned() {
    let controller = AndroidTunnelController::new();
    controller.poison_state_mutex_for_test();
    assert_eq!(controller.last_error(), Some("runtime mutex poisoned".to_owned()));
}

#[test]
fn record_bridge_error_reports_explicit_error_when_state_mutex_is_poisoned() {
    let controller = AndroidTunnelController::new();
    controller.poison_state_mutex_for_test();
    assert_eq!(
        controller.record_bridge_error("some error".to_owned()),
        Err("runtime mutex poisoned".to_owned())
    );
}

#[test]
fn reset_runtime_metadata_clears_measured_fields() {
    let mut state = AndroidRuntimeStatus {
        started_at_unix_ms: Some(123),
        mqtt_connected: true,
        active_session_count: 3,
        session_capacity: Some(16),
        remote_peer_id: Some("answer-office".to_owned()),
        forwards: vec![AndroidForwardRuntimeStatus::default()],
        ..AndroidRuntimeStatus::default()
    };
    reset_runtime_metadata(&mut state);
    assert_eq!(state.started_at_unix_ms, None);
    assert!(!state.mqtt_connected);
    assert_eq!(state.active_session_count, 0);
    assert_eq!(state.session_capacity, None);
    assert_eq!(state.remote_peer_id, None);
    assert!(state.forwards.is_empty());
}

#[test]
fn clean_stop_clears_uptime_session_and_error() {
    let controller = AndroidTunnelController::new();
    let runtime = Runtime::new().expect("tokio runtime");
    let shutdown = ShutdownToken::new();
    // A task that finishes on its own the moment shutdown is requested — a real
    // (if trivial) task/runtime pair is needed so stop() takes the Graceful
    // path, not the no-op NotRunning path a bare state-field flag would hit.
    let mut task_shutdown = shutdown.clone();
    let task = runtime.spawn(async move {
        task_shutdown.cancelled().await;
    });
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.task = Some(task);
        inner.runtime = Some(runtime);
        inner.shutdown = Some(shutdown);
        inner.state.state = AndroidRuntimeState::Running;
        inner.state.active = true;
        inner.state.mode = Some(AndroidTunnelMode::Offer);
        inner.state.config_path = Some("/tmp/config.toml".to_owned());
        inner.state.started_at_unix_ms = Some(123);
        inner.state.last_error = Some("transient".to_owned());
        inner.state.active_session_count = 2;
        inner.state.session_capacity = Some(16);
    }
    assert_eq!(controller.stop(), Ok(()));
    let status = controller.status();
    assert_eq!(status.state, AndroidRuntimeState::Stopped);
    assert!(!status.active);
    assert_eq!(status.started_at_unix_ms, None);
    assert_eq!(status.last_error, None);
    assert_eq!(status.config_path, None);
    assert_eq!(status.active_session_count, 0);
    assert_eq!(status.session_capacity, None);
}

#[test]
fn duplicate_start_preserves_running_state() {
    let controller = AndroidTunnelController::new();
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.state.state = AndroidRuntimeState::Running;
        inner.state.active = true;
        inner.state.mode = Some(AndroidTunnelMode::Offer);
        inner.state.started_at_unix_ms = Some(123);
        inner.state.active_session_count = 1;
    }

    let result = controller.start_offer("/tmp/whatever.toml");

    assert_eq!(result, Err("runtime already running".to_owned()));
    let inner = controller.inner.lock().expect("lock");
    assert_eq!(inner.state.state, AndroidRuntimeState::Running);
    assert!(inner.state.active);
    assert_eq!(inner.state.started_at_unix_ms, Some(123));
    assert_eq!(inner.state.active_session_count, 1);
}

#[test]
fn error_state_preserves_last_error_through_status() {
    let controller = AndroidTunnelController::new();
    {
        let mut inner = controller.inner.lock().expect("lock");
        inner.state.state = AndroidRuntimeState::Error;
        inner.state.active = false;
        inner.state.last_error = Some("native start failed".to_owned());
    }
    let status = controller.status();
    assert_eq!(status.state, AndroidRuntimeState::Error);
    assert_eq!(status.last_error, Some("native start failed".to_owned()));
}

#[test]
fn validate_config_with_identity_accepts_missing_identity_path() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    fs::write(config_dir.join("authorized_keys"), "").expect("auth keys");
    fs::write(
        config_dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(
        &config_path,
        format!(
            r#"
format = "p2ptunnel-config-v3"

[node]
peer_id = "android-test"
role = "offer"

[peer]
remote_peer_id = "answer-office"

[paths]
identity = "{identity}"
authorized_keys = "{authorized_keys}"
state_dir = "{state_dir}"
log_dir = "{log_dir}"

[broker]
url = "mqtts://mqtt.example.com:8883"
client_id = "android-test"
topic_prefix = "p2ptunnel"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "{ca_file}"
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false

[webrtc]
stun_urls = ["stun:stun.l.google.com:19302"]
enable_trickle_ice = true
enable_ice_restart = true

[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250

[[forwards]]
id = "llama"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 8080

[reconnect]
enable_auto_reconnect = true
strategy = "ice_then_renegotiate"
ice_restart_timeout_secs = 8
renegotiate_timeout_secs = 20
backoff_initial_ms = 1000
backoff_max_ms = 30000
backoff_multiplier = 2.0
jitter_ratio = 0.20
max_attempts = 0
hold_local_client_during_reconnect = false
local_client_hold_secs = 0

[security]
require_mqtt_tls = true
require_message_encryption = true
require_message_signatures = true
require_authorized_keys = true
max_clock_skew_secs = 120
max_message_age_secs = 300
replay_cache_size = 10000
reject_unknown_config_keys = true
refuse_world_readable_identity = true
refuse_world_writable_paths = true

[logging]
level = "info"
format = "text"
file_logging = true
stdout_logging = true
log_file = "{log_file}"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "{status_file}"
"#,
            identity = config_dir.join("missing_identity.toml").display(),
            authorized_keys = config_dir.join("authorized_keys").display(),
            state_dir = state_dir.display(),
            log_dir = state_dir.join("log").display(),
            ca_file = config_dir.join("ca.crt").display(),
            log_file = state_dir.join("log/p2ptunnel.log").display(),
            status_file = state_dir.join("status.json").display(),
        ),
    )
    .expect("config");

    let generated = generate_identity("android-test").expect("generate");
    let result = AndroidTunnelController::validate_config_with_identity(
        &config_path,
        &generated.identity.render_toml(),
    );
    assert!(result.valid, "{:?}", result.message);
}
