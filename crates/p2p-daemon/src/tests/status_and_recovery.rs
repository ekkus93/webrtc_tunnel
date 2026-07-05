//! Session/transport recovery transitions and status-writer behavior, plus the offer accept loop and idle-waiting poll path.

use std::time::Duration;

use p2p_core::{FailureCode, NodeRole, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::SignalingError;
use tokio::io::AsyncReadExt;
use tokio::sync::mpsc;
use tokio::time::timeout;

use super::support::*;

#[tokio::test]
async fn offer_recovery_returns_to_waiting_after_remote_error() {
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "offer-recovery");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(
        &ctx,
        Err(DaemonError::RemoteError(
            FailureCode::ProtocolError.as_str().to_owned(),
            "remote rejected session".to_owned(),
        )),
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "waiting_for_local_client");
    assert_eq!(status["role"], "offer");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn offer_recovery_returns_to_waiting_after_remote_close() {
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "offer-remote-close");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(&ctx, Err(DaemonError::RemoteClosed("session_closed".to_owned())))
        .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "waiting_for_local_client");
    assert_eq!(status["role"], "offer");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_recovery_returns_to_serving_after_target_connect_failure() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-target-connect");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(
        &ctx,
        Err(DaemonError::Tunnel(p2p_tunnel::TunnelError::TargetConnectFailed(
            "connection refused".to_owned(),
        ))),
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_recovery_returns_to_serving_after_remote_close() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-remote-close");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(&ctx, Err(DaemonError::RemoteClosed("session_closed".to_owned())))
        .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_recovery_returns_to_serving_after_bridge_task_failure() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-bridge-failure");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(
        &ctx,
        Err(DaemonError::Logging("bridge task join error: task 7 panicked".to_owned())),
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_recovery_returns_to_serving_after_ice_failure() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-ice-failure");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(&ctx, Err(DaemonError::IceFailed(IceConnectionState::Failed)))
        .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn steady_state_writer_uses_role_defaults() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "steady-state");
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    write_steady_state_status(&ctx).await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    assert_eq!(status["mqtt_connected"], true);
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn status_write_failure_is_recoverable() {
    let blocking_file =
        std::env::temp_dir().join(format!("p2ptunnel-status-blocker-{}", SessionId::random()));
    tokio::fs::write(&blocking_file, b"occupied".as_slice())
        .await
        .expect("blocking file should exist");

    let mut config = sample_config();
    config.health.write_status_file = true;
    config.health.status_file = blocking_file.join("status.json");
    let writer = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    write_steady_state_status(&ctx).await;

    assert!(!config.health.status_file.exists(), "status write failure should be ignored");
    let _ = tokio::fs::remove_file(&blocking_file).await;
}

#[tokio::test]
async fn transport_failure_updates_status_to_disconnected_before_retry() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "transport-disconnected");
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    mark_transport_unusable(
        &mut ctx,
        StatusSnapshot { active_session_id: None, current_state: DaemonState::Serving },
        &SignalingError::Protocol("poll failed".to_owned()),
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["mqtt_connected"], false);
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["active_session_count"], 0);
    assert!(status["active_session_id"].is_null());
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_zero_session_transport_recovery_stays_serving() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-zero-transport-recovered");
    let mut runtime = connected_runtime();
    runtime.mqtt_connected = false;
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    mark_transport_usable(
        &mut ctx,
        StatusSnapshot { active_session_id: None, current_state: DaemonState::Serving },
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["mqtt_connected"], true);
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["active_session_count"], 0);
    assert!(status["active_session_id"].is_null());
    assert!(status["sessions"].as_array().expect("sessions should be an array").is_empty());
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn transport_recovery_updates_status_back_to_connected() {
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "transport-recovered");
    let mut runtime = connected_runtime();
    runtime.mqtt_connected = false;
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    mark_transport_usable(
        &mut ctx,
        StatusSnapshot {
            active_session_id: Some(SessionId::random()),
            current_state: DaemonState::Negotiating,
        },
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["mqtt_connected"], true);
    assert_eq!(status["current_state"], "negotiating");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn session_recovery_preserves_disconnected_transport_status() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "recovery-keeps-disconnect");
    let mut runtime = connected_runtime();
    runtime.mqtt_connected = false;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    recover_daemon_after_session(
        &ctx,
        Err(DaemonError::RemoteError(
            FailureCode::ProtocolError.as_str().to_owned(),
            "session failed".to_owned(),
        )),
    )
    .await;

    let status = read_status_file(&path).await;
    assert_eq!(status["mqtt_connected"], false);
    assert_eq!(status["current_state"], "serving");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn offer_accept_loop_accepts_multiple_clients_for_session_queue() {
    let mut config = sample_config();
    super::first_offer_forward_mut(&mut config).expect("offer forward").listen_port = 0;
    let (forward_id, offer_config) = super::first_offer_forward(&config).expect("offer");
    let listener =
        OfferListener::bind(forward_id, offer_config).await.expect("listener should bind");
    let addr = listener.local_addr().expect("listener should have local addr");
    let mut accepted_clients = spawn_offer_accept_loop(listener);

    let mut first_client =
        tokio::net::TcpStream::connect(addr).await.expect("first client should connect");
    let first_session = timeout(Duration::from_secs(1), accepted_clients.recv())
        .await
        .expect("accept loop should yield first session")
        .expect("accept loop should stay alive")
        .expect("first session should be accepted");

    let mut second_client = tokio::net::TcpStream::connect(addr)
        .await
        .expect("second client should connect for queueing");
    let second_session = timeout(Duration::from_secs(1), accepted_clients.recv())
        .await
        .expect("accept loop should yield second session")
        .expect("accept loop should stay alive")
        .expect("second session should be accepted");

    let mut first_buffer = [0_u8; 1];
    assert!(
        timeout(Duration::from_millis(100), first_client.read(&mut first_buffer)).await.is_err(),
        "active session client should remain connected while busy clients are rejected"
    );
    let mut second_buffer = [0_u8; 1];
    assert!(
        timeout(Duration::from_millis(100), second_client.read(&mut second_buffer)).await.is_err(),
        "queued session client should remain connected"
    );

    drop(first_session);
    drop(second_session);

    let _third_client = tokio::net::TcpStream::connect(addr)
        .await
        .expect("third client should connect after release");
    let third_session = timeout(Duration::from_secs(1), accepted_clients.recv())
        .await
        .expect("accept loop should yield next session")
        .expect("accept loop should stay alive")
        .expect("third session should be accepted");
    drop(third_session);
}

#[tokio::test]
async fn offer_waiting_state_polls_idle_transport_and_recovers_status() {
    let mut config = sample_config();
    super::first_offer_forward_mut(&mut config).expect("offer forward").listen_port = 0;
    let status_path = std::env::temp_dir()
        .join(format!("p2ptunnel-daemon-status-offer-idle-{}.json", SessionId::random()));
    config.health.write_status_file = true;
    config.health.status_file = status_path.clone();

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let authorized_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");

    let (outcomes_tx, outcomes_rx) = mpsc::unbounded_channel();
    let transport = ScriptedPollingTransport { outcomes: outcomes_rx };

    let daemon = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        config,
        offer.identity,
        authorized_keys,
        transport,
        None,
    ));

    let initial = wait_for_status(&status_path, |status| {
        status["role"] == "offer"
            && status["current_state"] == "waiting_for_local_client"
            && status["mqtt_connected"] == true
    })
    .await;
    assert_eq!(initial["mqtt_connected"], true);

    outcomes_tx
        .send(Err(SignalingError::Protocol("idle poll failed".to_owned())))
        .expect("idle poll failure should be delivered");
    let disconnected = wait_for_status(&status_path, |status| {
        status["current_state"] == "waiting_for_local_client" && status["mqtt_connected"] == false
    })
    .await;
    assert_eq!(disconnected["mqtt_connected"], false);

    outcomes_tx.send(Ok(None)).expect("idle transport recovery should be delivered");
    let recovered = wait_for_status(&status_path, |status| {
        status["current_state"] == "waiting_for_local_client" && status["mqtt_connected"] == true
    })
    .await;
    assert_eq!(recovered["mqtt_connected"], true);

    daemon.abort();
    let _ = daemon.await;
    let _ = tokio::fs::remove_file(&status_path).await;
}

#[tokio::test]
async fn answer_idle_shutdown_writes_closed_status() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("peer id");
    let status_path = std::env::temp_dir()
        .join(format!("p2ptunnel-daemon-status-answer-idle-{}.json", SessionId::random()));
    config.health.write_status_file = true;
    config.health.status_file = status_path.clone();

    let answer = generate_identity("answer-office").expect("answer identity");
    let offer = generate_identity("offer-home").expect("offer identity");
    let authorized_keys =
        AuthorizedKeys::parse(&offer.public_identity.render()).expect("authorized keys");

    let (_outcomes_tx, outcomes_rx) = mpsc::unbounded_channel();
    let transport = ScriptedPollingTransport { outcomes: outcomes_rx };

    let shutdown = ShutdownToken::new();
    let daemon = tokio::spawn(run_answer_daemon_with_transport_and_shutdown(
        config,
        answer.identity,
        authorized_keys,
        transport,
        shutdown.clone(),
    ));

    let _initial = wait_for_status(&status_path, |status| {
        status["role"] == "answer" && status["current_state"] == "serving"
    })
    .await;

    shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(2), daemon)
        .await
        .expect("answer daemon should stop before the test timeout")
        .expect("answer daemon task should not panic");
    assert!(result.is_ok(), "graceful shutdown should return Ok, got {result:?}");

    let final_status = read_status_file(&status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(final_status["mqtt_connected"], false);
    assert_eq!(final_status["active_session_count"], 0);
    assert!(final_status["sessions"].as_array().expect("sessions array").is_empty());

    let _ = tokio::fs::remove_file(&status_path).await;
}

#[tokio::test]
async fn offer_idle_shutdown_releases_listener_port() {
    // Reserve a specific free port up front so the daemon binds a known address we
    // can immediately try to rebind after shutdown, proving listener ownership was
    // actually released rather than merely dropped from a status label.
    let reserved = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("should reserve an ephemeral port");
    let addr = reserved.local_addr().expect("reserved listener should have a local addr");
    drop(reserved);

    let mut config = sample_config();
    let offer_forward = super::first_offer_forward_mut(&mut config).expect("offer forward");
    offer_forward.listen_host = addr.ip().to_string();
    offer_forward.listen_port = addr.port();

    let status_path = std::env::temp_dir()
        .join(format!("p2ptunnel-daemon-status-offer-shutdown-{}.json", SessionId::random()));
    config.health.write_status_file = true;
    config.health.status_file = status_path.clone();

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let authorized_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("authorized keys");

    let (_outcomes_tx, outcomes_rx) = mpsc::unbounded_channel();
    let transport = ScriptedPollingTransport { outcomes: outcomes_rx };

    let shutdown = ShutdownToken::new();
    let daemon = tokio::spawn(run_offer_daemon_with_transport_and_shutdown(
        config,
        offer.identity,
        authorized_keys,
        transport,
        shutdown.clone(),
    ));

    wait_for_status(&status_path, |status| {
        status["role"] == "offer" && status["forwards"][0]["listen_state"] == "listening"
    })
    .await;

    shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(2), daemon)
        .await
        .expect("offer daemon should stop before the test timeout")
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "graceful shutdown should return Ok, got {result:?}");

    let final_status = read_status_file(&status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(final_status["forwards"][0]["listen_state"], "stopped");

    let rebound = tokio::net::TcpListener::bind(addr)
        .await
        .expect("offer listener port should be released after shutdown");
    drop(rebound);

    let _ = tokio::fs::remove_file(&status_path).await;
}
