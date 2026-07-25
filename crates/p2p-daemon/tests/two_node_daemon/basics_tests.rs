use std::collections::HashMap;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use std::time::Duration;

use p2p_core::{MessageType, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{
    DaemonSignalingTransport, run_answer_daemon_with_transport,
    run_offer_daemon_with_transport_and_test_hook,
};
use p2p_signaling::SignalCodec;
use p2p_webrtc::IceConnectionState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::{mpsc, oneshot};
use tokio::time::timeout;

use crate::harness::*;

#[test]
fn decrement_fault_counts_down_and_removes_exhausted_route() {
    let route = RouteKey::new("offer-home", "answer-office");
    let unrelated = RouteKey::new("offer-home", "other-answer");
    let mut faults = HashMap::new();
    faults.insert(route.clone(), 2);
    faults.insert(unrelated.clone(), 1);

    assert!(decrement_fault(&mut faults, &route));
    assert_eq!(faults.get(&route), Some(&1));
    assert_eq!(faults.get(&unrelated), Some(&1));
    assert!(decrement_fault(&mut faults, &route));
    assert!(!faults.contains_key(&route));
    assert!(decrement_fault(&mut faults, &unrelated));
    assert!(!faults.contains_key(&unrelated));
    assert!(!decrement_fault(&mut faults, &RouteKey::new("missing", "route")));
}

#[tokio::test]
async fn in_memory_transport_trace_records_success_and_publish_failure() {
    let mesh = InMemoryTransportMesh::new();
    let mut offer_transport = mesh.add_transport("offer-home");
    let mut answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();
    let trace = mesh.trace();
    let answer_peer: p2p_core::PeerId = "answer-office".parse().expect("answer peer id");

    offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"first".to_vec())
        .await
        .expect("first publish should deliver");
    assert_eq!(
        answer_transport.poll_signal_payload().await.expect("poll should succeed"),
        Some(b"first".to_vec())
    );

    control.fail_next_publish("offer-home", "answer-office", 1);
    let error = offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"second".to_vec())
        .await
        .expect_err("second publish should fail");
    assert!(error.to_string().contains("injected publish failure"));

    let attempts = trace.attempts();
    assert_eq!(attempts.len(), 2);
    assert_eq!(attempts[0].from_peer_id, "offer-home");
    assert_eq!(attempts[0].to_peer_id, "answer-office");
    assert_eq!(attempts[0].payload, b"first");
    assert!(attempts[0].delivered);
    assert_eq!(attempts[1].payload, b"second");
    assert!(!attempts[1].delivered);
    assert_eq!(trace.payloads_for("answer-office"), vec![b"first".to_vec(), b"second".to_vec()]);
}

#[tokio::test]
async fn in_memory_transport_faults_are_route_scoped() {
    let mesh = InMemoryTransportMesh::new();
    let mut offer_transport = mesh.add_transport("offer-home");
    let mut answer_transport = mesh.add_transport("answer-office");
    let mut other_transport = mesh.add_transport("other-answer");
    let control = mesh.control();
    let answer_peer: p2p_core::PeerId = "answer-office".parse().expect("answer peer id");
    let other_peer: p2p_core::PeerId = "other-answer".parse().expect("other peer id");

    control.drop_next_delivery("offer-home", "answer-office", 1);
    offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"dropped".to_vec())
        .await
        .expect("dropped delivery still reports publish success");
    assert!(
        timeout(Duration::from_millis(50), answer_transport.poll_signal_payload()).await.is_err(),
        "dropped answer route should not receive payload"
    );

    offer_transport
        .publish_signal(&other_peer, "p2ptunnel-tests", b"other".to_vec())
        .await
        .expect("unrelated route should deliver");
    assert_eq!(
        other_transport.poll_signal_payload().await.expect("poll should succeed"),
        Some(b"other".to_vec())
    );

    control.duplicate_next_delivery("offer-home", "answer-office", 1);
    offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"dupe".to_vec())
        .await
        .expect("duplicate delivery should publish");
    assert_eq!(
        answer_transport.poll_signal_payload().await.expect("first poll should succeed"),
        Some(b"dupe".to_vec())
    );
    assert_eq!(
        answer_transport.poll_signal_payload().await.expect("duplicate poll should succeed"),
        Some(b"dupe".to_vec())
    );
}

#[test]
fn unused_local_port_returns_distinct_bindable_ports() {
    let first = unused_local_port();
    let second = unused_local_port();
    let third = unused_local_port();

    assert_ne!(first, second);
    assert_ne!(second, third);
    assert_ne!(first, third);
    let _first_listener =
        std::net::TcpListener::bind(("127.0.0.1", first)).expect("first port should bind");
    let _second_listener =
        std::net::TcpListener::bind(("127.0.0.1", second)).expect("second port should bind");
    let _third_listener =
        std::net::TcpListener::bind(("127.0.0.1", third)).expect("third port should bind");
}

#[tokio::test]
async fn offer_and_answer_daemons_complete_one_in_memory_session() {
    run_one_in_memory_session(0, false, true, true).await;
}

#[tokio::test]
async fn active_offer_session_survives_duplicate_answer_payload_and_completes() {
    run_one_in_memory_session(1, false, true, true).await;
}

#[tokio::test]
async fn offer_side_drives_reconnect_after_injected_disconnect() {
    run_one_in_memory_session(0, true, false, true).await;
}

#[tokio::test]
async fn active_session_ice_restart_recovers_pending_local_client() {
    run_one_in_memory_session(0, true, true, true).await;
}

#[tokio::test]
async fn offer_daemon_accepts_next_client_after_active_connection_loss() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-active-drop-status.json");
    let answer_status_path = unique_path("answer-active-drop-status.json");
    let offer_port = unused_local_port();
    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr").port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let (hook_tx, mut hook_rx) = mpsc::unbounded_channel();
    let (release_first_target, release_first_target_rx) = oneshot::channel();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_for_target = Arc::clone(&accepted);

    let target_task = tokio::spawn(async move {
        let (mut first_stream, _) = target_listener.accept().await.expect("first target accept");
        let accepted_for_first = Arc::clone(&accepted_for_target);
        let first_task = tokio::spawn(async move {
            let mut request = [0_u8; 4];
            first_stream.read_exact(&mut request).await.expect("first target read");
            assert_eq!(&request, b"hold");
            first_stream.write_all(&request).await.expect("first target write");
            accepted_for_first.fetch_add(1, Ordering::SeqCst);
            let _ = release_first_target_rx.await;
            let _ = first_stream.shutdown().await;
        });

        let (mut second_stream, _) = target_listener.accept().await.expect("second target accept");
        let mut request = [0_u8; 4];
        second_stream.read_exact(&mut request).await.expect("second target read");
        assert_eq!(&request, b"next");
        second_stream.write_all(&request).await.expect("second target write");
        second_stream.shutdown().await.expect("second target shutdown");
        accepted_for_target.fetch_add(1, Ordering::SeqCst);
        first_task.await.expect("first target task should join");
    });

    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        Some(hook_tx),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let mut first_client = connect_with_retry(offer_port).await;
    first_client.write_all(b"hold").await.expect("first client write");
    let mut first_response = [0_u8; 4];
    timeout(Duration::from_secs(25), first_client.read_exact(&mut first_response))
        .await
        .expect("first client should receive response")
        .expect("first client read");
    assert_eq!(&first_response, b"hold");
    wait_for_status(&offer_status_path, "tunnel_open").await;

    let first_handle = timeout(Duration::from_secs(10), hook_rx.recv())
        .await
        .expect("first offer hook should arrive")
        .expect("first offer hook should contain handle");
    first_handle
        .ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("active ICE disconnect should inject");
    wait_for_status(&offer_status_path, "waiting_for_local_client").await;

    let mut second_client = connect_with_retry(offer_port).await;
    second_client.write_all(b"next").await.expect("second client write");
    let mut second_response = [0_u8; 4];
    let second_read =
        timeout(Duration::from_secs(20), second_client.read_exact(&mut second_response)).await;
    if !(matches!(second_read, Ok(Ok(_))) && second_response == *b"next") {
        assert_client_round_trip_eventually(
            offer_port,
            *b"next",
            *b"next",
            "second local client should recover after active ICE drop",
        )
        .await;
    }
    second_client.shutdown().await.expect("second client shutdown");
    wait_for_status(&offer_status_path, "tunnel_open").await;
    assert!(!offer_task.is_finished(), "offer daemon should remain alive after active drop");
    assert!(!answer_task.is_finished(), "answer daemon should remain alive after active drop");

    let _ = release_first_target.send(());
    first_client.shutdown().await.expect("first client shutdown");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should join");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[tokio::test]
async fn simultaneous_offer_peer_reconnects_stay_session_local_and_answer_passive() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let desktop_codec = SignalCodec::new(&offer_desktop.identity, &offer_desktop_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let offer_home_status = unique_path("offer-home-simultaneous-reconnect-status.json");
    let offer_desktop_status = unique_path("offer-desktop-simultaneous-reconnect-status.json");
    let answer_status = unique_path("answer-simultaneous-reconnect-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;

    let mut offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let mut offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );
    offer_home_config.webrtc.enable_ice_restart = true;
    offer_desktop_config.webrtc.enable_ice_restart = true;
    answer_config.webrtc.enable_ice_restart = true;

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    control.delay_next_delivery("answer-office", "offer-home", 300);
    control.delay_next_delivery("answer-office", "offer-desktop", 300);
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");
    let (home_hook_tx, mut home_hook_rx) = mpsc::unbounded_channel();
    let (desktop_hook_tx, mut desktop_hook_rx) = mpsc::unbounded_channel();

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        Some(home_hook_tx),
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys.clone(),
        offer_desktop_transport,
        Some(desktop_hook_tx),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let mut home_client = connect_with_retry(offer_home_port).await;
    let mut desktop_client = connect_with_retry(offer_desktop_port).await;
    let home_handle = timeout(Duration::from_secs(10), home_hook_rx.recv())
        .await
        .expect("home hook should arrive")
        .expect("home hook should include handle");
    let desktop_handle = timeout(Duration::from_secs(10), desktop_hook_rx.recv())
        .await
        .expect("desktop hook should arrive")
        .expect("desktop hook should include handle");
    home_handle
        .ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("home ICE disconnect should inject");
    desktop_handle
        .ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("desktop ICE disconnect should inject");

    home_client.write_all(b"home").await.expect("home client write");
    desktop_client.write_all(b"desk").await.expect("desktop client write");
    let mut home_response = [0_u8; 4];
    let mut desktop_response = [0_u8; 4];
    let home_read =
        timeout(Duration::from_secs(20), home_client.read_exact(&mut home_response)).await;
    let desktop_read =
        timeout(Duration::from_secs(20), desktop_client.read_exact(&mut desktop_response)).await;
    if !(matches!(home_read, Ok(Ok(_))) && home_response == *b"home") {
        assert_client_round_trip_eventually(
            offer_home_port,
            *b"home",
            *b"home",
            "home peer reconnect round-trip",
        )
        .await;
    }
    if !(matches!(desktop_read, Ok(Ok(_))) && desktop_response == *b"desk") {
        assert_client_round_trip_eventually(
            offer_desktop_port,
            *b"desk",
            *b"desk",
            "desktop peer reconnect round-trip",
        )
        .await;
    }
    let status = wait_for_status_matching(&answer_status, "two recovered sessions", |status| {
        session_count_is(2)(status)
            && has_remote_peer("offer-home")(status)
            && has_remote_peer("offer-desktop")(status)
    })
    .await;
    assert_status_schema_is_consistent(&status);
    wait_for_status(&offer_home_status, "tunnel_open").await;
    wait_for_status(&offer_desktop_status, "tunnel_open").await;

    let _ = home_client.shutdown().await;
    let _ = desktop_client.shutdown().await;

    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    let offer_to_answer =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert!(
        count_records_from(&offer_to_answer, "offer-home", MessageType::Offer) >= 2,
        "home offer side should publish a replacement offer"
    );
    assert!(
        count_records_from(&offer_to_answer, "offer-desktop", MessageType::Offer) >= 2,
        "desktop offer side should publish a replacement offer"
    );
    assert!(
        offer_to_answer.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
                && record.session_id != home_handle.session_id
        }),
        "home recovery should use a replacement session id"
    );
    assert!(
        offer_to_answer.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
                && record.session_id != desktop_handle.session_id
        }),
        "desktop recovery should use a replacement session id"
    );
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-home"),
        &home_codec,
    ));
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-desktop"),
        &desktop_codec,
    ));

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn active_answer_poll_failure_flips_status_and_recovers() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-poll-failure-status.json");
    let answer_status_path = unique_path("answer-poll-failure-status.json");
    let offer_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(1).await;

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();

    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys.clone(),
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    assert_client_round_trip(offer_port, b"ping", b"ping").await;
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 1);
    wait_for_status_matching(&answer_status_path, "serving and connected", |status| {
        current_state_is("serving")(status) && mqtt_connected_is(true)(status)
    })
    .await;

    wait_for_mqtt_disconnected_after_poll_failure(
        &control,
        "answer-office",
        &answer_status_path,
        "mqtt disconnected",
        Duration::from_secs(20),
    )
    .await;
    control.inject_payload("answer-office", vec![0_u8]);
    wait_for_status_matching_with_timeout(
        &answer_status_path,
        "mqtt recovered",
        mqtt_connected_is(true),
        Duration::from_secs(20),
    )
    .await;
    assert!(!answer_task.is_finished(), "answer daemon should remain alive");

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}
