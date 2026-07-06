//! Answer session-registry status events (rekey/remove/replace) and serving-status reporting.

use std::collections::HashMap;
use std::sync::Arc;

use p2p_core::{FailureCode, NodeRole, PeerId, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::SignalCodec;

use super::support::*;

#[tokio::test]
async fn answer_status_event_does_not_rekey_by_peer_or_cross_generation() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, status_writer) = status_writer_for_test(&mut config, "stale-status");
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let authorized_keys = AuthorizedKeys::parse("").expect("empty keys");
    let codec = SignalCodec::new(&answer.identity, &authorized_keys, 120, 300);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let mut transport = RecordingTransport::default();
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let remote: PeerId = "offer-home".parse().expect("remote peer");
    let old_session = SessionId::random();
    let current_session = SessionId::random();
    let generation = SessionGeneration(7);
    let (handle, _rx) =
        test_answer_handle(current_session, generation, remote.clone(), DaemonState::TunnelOpen);
    sessions_by_id.insert(current_session, handle);
    session_by_peer.insert(remote.clone(), current_session);

    handle_answer_session_event(
        &mut ctx,
        &codec,
        &mut transport,
        &mut sessions_by_id,
        &mut session_by_peer,
        AnswerSessionEvent::Status(test_session_status(
            old_session,
            generation,
            remote.clone(),
            DaemonState::Negotiating,
        )),
    )
    .await;

    assert!(sessions_by_id.contains_key(&current_session));
    assert!(!sessions_by_id.contains_key(&old_session));
    assert_eq!(session_by_peer.get(&remote), Some(&current_session));

    handle_answer_session_event(
        &mut ctx,
        &codec,
        &mut transport,
        &mut sessions_by_id,
        &mut session_by_peer,
        AnswerSessionEvent::Status(test_session_status(
            current_session,
            SessionGeneration(8),
            remote.clone(),
            DaemonState::Negotiating,
        )),
    )
    .await;

    assert_eq!(sessions_by_id[&current_session].status.state, DaemonState::TunnelOpen);
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn stale_answer_task_completion_cannot_remove_newer_same_peer_session() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (_path, status_writer) = status_writer_for_test(&mut config, "stale-ended");
    let config = Arc::new(config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let remote: PeerId = "offer-home".parse().expect("remote peer");
    let current_session = SessionId::random();
    let generation = SessionGeneration(3);
    let (handle, _rx) =
        test_answer_handle(current_session, generation, remote.clone(), DaemonState::TunnelOpen);
    sessions_by_id.insert(current_session, handle);
    session_by_peer.insert(remote.clone(), current_session);
    let shutdown = ShutdownToken::new();
    let mut primary_error = None;

    let stale_session_id = SessionId::random();
    handle_answer_task_completion(
        &mut ctx,
        &mut sessions_by_id,
        &mut session_by_peer,
        AnswerTaskCompletion {
            initial_session_id: stale_session_id,
            generation: SessionGeneration(2),
            remote_peer_id: remote.clone(),
            outcome: Ok(AnswerSessionTaskResult {
                final_session_id: stale_session_id,
                result: Ok(()),
            }),
        },
        &shutdown,
        &mut primary_error,
    )
    .await;

    assert!(sessions_by_id.contains_key(&current_session));
    assert_eq!(session_by_peer.get(&remote), Some(&current_session));
    assert!(!shutdown.is_shutdown_requested());

    handle_answer_task_completion(
        &mut ctx,
        &mut sessions_by_id,
        &mut session_by_peer,
        AnswerTaskCompletion {
            initial_session_id: current_session,
            generation: SessionGeneration(4),
            remote_peer_id: remote.clone(),
            outcome: Ok(AnswerSessionTaskResult {
                final_session_id: current_session,
                result: Ok(()),
            }),
        },
        &shutdown,
        &mut primary_error,
    )
    .await;

    assert!(sessions_by_id.contains_key(&current_session));
    assert_eq!(session_by_peer.get(&remote), Some(&current_session));
    assert!(!shutdown.is_shutdown_requested());
    assert!(primary_error.is_none());
}

#[tokio::test]
async fn answer_task_panic_removes_session_and_enters_drain_leaving_other_sessions_intact() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, status_writer) = status_writer_for_test(&mut config, "panicked-ended");
    let config = Arc::new(config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let peer_a: PeerId = "offer-a".parse().expect("peer a");
    let peer_b: PeerId = "offer-b".parse().expect("peer b");
    let session_a = SessionId::random();
    let session_b = SessionId::random();
    let generation_a = SessionGeneration(9);
    let generation_b = SessionGeneration(10);
    let (handle_a, _rx_a) =
        test_answer_handle(session_a, generation_a, peer_a.clone(), DaemonState::TunnelOpen);
    let (handle_b, _rx_b) =
        test_answer_handle(session_b, generation_b, peer_b.clone(), DaemonState::TunnelOpen);
    sessions_by_id.insert(session_a, handle_a);
    sessions_by_id.insert(session_b, handle_b);
    session_by_peer.insert(peer_a.clone(), session_a);
    session_by_peer.insert(peer_b.clone(), session_b);
    let shutdown = ShutdownToken::new();
    let mut primary_error = None;

    // A join failure (panic or abort) never carries an `AnswerSessionTaskResult`,
    // so the registry lookup falls back to generation+peer instead of a returned
    // final_session_id.
    handle_answer_task_completion(
        &mut ctx,
        &mut sessions_by_id,
        &mut session_by_peer,
        AnswerTaskCompletion {
            initial_session_id: session_a,
            generation: generation_a,
            remote_peer_id: peer_a.clone(),
            outcome: Err("panicked at 'boom'".to_owned()),
        },
        &shutdown,
        &mut primary_error,
    )
    .await;

    assert!(!sessions_by_id.contains_key(&session_a), "panicked session should be removed");
    assert_eq!(session_by_peer.get(&peer_a), None, "panicked peer mapping should be removed");
    assert!(shutdown.is_shutdown_requested(), "a task panic must trigger daemon shutdown");
    assert!(primary_error.is_some(), "a task panic must become the primary daemon error");

    // The unrelated session is untouched by the panic and would still drain
    // normally through its own eventual completion — the real daemon loop's
    // `shutting_down && sessions_by_id.is_empty()` gate (unchanged by this
    // refactor) is what keeps the daemon alive until it does.
    assert!(sessions_by_id.contains_key(&session_b), "unrelated session must remain to drain");
    assert_eq!(session_by_peer.get(&peer_b), Some(&session_b));

    // The registry-status write at the end of `handle_answer_task_completion` is
    // itself an ordinary (non-terminal) write, so P0-001's phase gate correctly
    // suppresses it once `begin_answer_drain` has moved the phase to `Draining` —
    // no file is written here; only the eventual terminal `Closed` write will be.
    assert!(
        tokio::fs::metadata(&path).await.is_err(),
        "ordinary status write must stay suppressed once drain has begun"
    );
}

#[tokio::test]
async fn failed_session_end_events_remove_only_that_session() {
    let failures = vec![
        ("ack-timeout", DaemonError::AckTimeout),
        ("remote-close", DaemonError::RemoteClosed("session_closed".to_owned())),
        (
            "remote-error",
            DaemonError::RemoteError(
                FailureCode::ProtocolError.as_str().to_owned(),
                "remote error".to_owned(),
            ),
        ),
        ("reconnect-failure", DaemonError::IceFailed(IceConnectionState::Failed)),
    ];

    for (label, failure) in failures {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, status_writer) = status_writer_for_test(&mut config, label);
        let config = Arc::new(config);
        let mut runtime = connected_runtime();
        let mut ctx =
            RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let peer_a: PeerId = "offer-a".parse().expect("peer a");
        let peer_b: PeerId = "offer-b".parse().expect("peer b");
        let session_a = SessionId::random();
        let session_b = SessionId::random();
        let generation_a = SessionGeneration(21);
        let generation_b = SessionGeneration(22);
        let (handle_a, _rx_a) =
            test_answer_handle(session_a, generation_a, peer_a.clone(), DaemonState::TunnelOpen);
        let (handle_b, _rx_b) =
            test_answer_handle(session_b, generation_b, peer_b.clone(), DaemonState::TunnelOpen);
        sessions_by_id.insert(session_a, handle_a);
        sessions_by_id.insert(session_b, handle_b);
        session_by_peer.insert(peer_a.clone(), session_a);
        session_by_peer.insert(peer_b.clone(), session_b);
        let shutdown = ShutdownToken::new();
        let mut primary_error = None;

        handle_answer_task_completion(
            &mut ctx,
            &mut sessions_by_id,
            &mut session_by_peer,
            AnswerTaskCompletion {
                initial_session_id: session_a,
                generation: generation_a,
                remote_peer_id: peer_a.clone(),
                outcome: Ok(AnswerSessionTaskResult {
                    final_session_id: session_a,
                    result: Err(failure),
                }),
            },
            &shutdown,
            &mut primary_error,
        )
        .await;

        assert!(!sessions_by_id.contains_key(&session_a), "{label}: peer A removed");
        assert!(sessions_by_id.contains_key(&session_b), "{label}: peer B remains");
        assert_eq!(session_by_peer.get(&peer_a), None, "{label}: peer A mapping removed");
        assert_eq!(
            session_by_peer.get(&peer_b),
            Some(&session_b),
            "{label}: peer B mapping remains"
        );
        // A session's own failure (as opposed to a task join failure) is not
        // daemon-fatal: no drain should be triggered.
        assert!(!shutdown.is_shutdown_requested(), "{label}: no daemon-wide shutdown");
        assert!(primary_error.is_none(), "{label}: no primary daemon error");
        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "serving", "{label}: daemon still serving");
        assert_eq!(status["active_session_count"], 1, "{label}: only peer B remains active");
        assert_eq!(status["sessions"][0]["remote_peer_id"], "offer-b");
        let _ = tokio::fs::remove_file(&path).await;
    }
}

#[tokio::test]
async fn replacement_event_remaps_only_replaced_peer_session() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, status_writer) = status_writer_for_test(&mut config, "replacement-isolation");
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let authorized_keys = AuthorizedKeys::parse("").expect("empty keys");
    let codec = SignalCodec::new(&answer.identity, &authorized_keys, 120, 300);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let mut transport = RecordingTransport::default();
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let peer_a: PeerId = "offer-a".parse().expect("peer a");
    let peer_b: PeerId = "offer-b".parse().expect("peer b");
    let old_a = SessionId::random();
    let new_a = SessionId::random();
    let session_b = SessionId::random();
    let generation_a = SessionGeneration(11);
    let generation_b = SessionGeneration(12);
    let (handle_a, _rx_a) =
        test_answer_handle(old_a, generation_a, peer_a.clone(), DaemonState::Negotiating);
    let (handle_b, mut rx_b) =
        test_answer_handle(session_b, generation_b, peer_b.clone(), DaemonState::TunnelOpen);
    let b_status_before = handle_b.status.clone();
    sessions_by_id.insert(old_a, handle_a);
    sessions_by_id.insert(session_b, handle_b);
    session_by_peer.insert(peer_a.clone(), old_a);
    session_by_peer.insert(peer_b.clone(), session_b);

    handle_answer_session_event(
        &mut ctx,
        &codec,
        &mut transport,
        &mut sessions_by_id,
        &mut session_by_peer,
        AnswerSessionEvent::Replaced {
            old_session_id: old_a,
            new_session_id: new_a,
            remote_peer_id: peer_a.clone(),
            generation: generation_a,
            status: test_session_status(
                new_a,
                generation_a,
                peer_a.clone(),
                DaemonState::ConnectingDataChannel,
            ),
        },
    )
    .await;

    assert!(!sessions_by_id.contains_key(&old_a));
    assert!(sessions_by_id.contains_key(&new_a));
    assert_eq!(session_by_peer.get(&peer_a), Some(&new_a));
    assert_eq!(session_by_peer.get(&peer_b), Some(&session_b));
    assert_eq!(sessions_by_id[&session_b].generation, generation_b);
    assert_eq!(sessions_by_id[&session_b].status.session_id, b_status_before.session_id);
    assert_eq!(sessions_by_id[&session_b].status.state, b_status_before.state);
    assert!(rx_b.try_recv().is_err());
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_registry_reports_serving_when_sessions_are_active() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, status_writer) = status_writer_for_test(&mut config, "serving-registry");
    let config = Arc::new(config);
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let mut sessions_by_id = HashMap::new();
    let (handle, _rx) = test_answer_handle(
        SessionId::random(),
        SessionGeneration(1),
        "offer-home".parse().expect("remote peer"),
        DaemonState::TunnelOpen,
    );
    sessions_by_id.insert(handle.status.session_id, handle);

    write_answer_registry_status(&ctx, &sessions_by_id).await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["active_session_count"], 1);
    assert_eq!(
        status["active_session_id"],
        sessions_by_id.keys().next().expect("one session").to_string()
    );
    assert!(status["active_stream_count"].is_null());
    assert!(status["sessions"][0]["configured_forward_ids"].is_array());
    assert!(status["sessions"][0]["open_forward_ids"].is_null());
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_registry_reports_serving_with_zero_sessions() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, status_writer) = status_writer_for_test(&mut config, "serving-zero-registry");
    let config = Arc::new(config);
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let sessions_by_id = HashMap::new();

    write_answer_registry_status(&ctx, &sessions_by_id).await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    assert_eq!(status["active_session_count"], 0);
    assert!(status["active_session_id"].is_null());
    assert!(status["sessions"].as_array().expect("sessions should be an array").is_empty());
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_registry_reports_serving_with_multiple_sessions() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, status_writer) = status_writer_for_test(&mut config, "serving-multi-registry");
    let config = Arc::new(config);
    let mut runtime = connected_runtime();
    let ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
    let mut sessions_by_id = HashMap::new();
    for (idx, peer_id) in ["offer-a", "offer-b"].into_iter().enumerate() {
        let (handle, _rx) = test_answer_handle(
            SessionId::random(),
            SessionGeneration(idx as u64 + 1),
            peer_id.parse().expect("remote peer"),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(handle.status.session_id, handle);
    }

    write_answer_registry_status(&ctx, &sessions_by_id).await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    assert_eq!(status["role"], "answer");
    assert_eq!(
        status["active_session_count"],
        status["sessions"].as_array().expect("sessions should be an array").len()
    );
    assert_eq!(status["active_session_count"], 2);
    assert!(status["active_session_id"].is_null());
    let _ = tokio::fs::remove_file(&path).await;
}
