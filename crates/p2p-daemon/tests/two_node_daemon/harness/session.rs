//! The `run_one_in_memory_session` scenario driver: stands up an offer+answer
//! daemon pair over the in-memory transport, drives one client round trip with
//! optional ICE-disconnect injection, then asserts the resulting status and
//! signaling trace. Shared by several single-session tests.

use std::time::Duration;

use p2p_core::{MessageType, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{
    OfferSessionTestHandle, run_answer_daemon_with_transport,
    run_offer_daemon_with_transport_and_test_hook,
};
use p2p_signaling::SignalCodec;
use p2p_webrtc::IceConnectionState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time::timeout;

use super::*;

pub(crate) async fn run_one_in_memory_session(
    duplicate_answer_to_offer_payloads: usize,
    inject_offer_disconnect: bool,
    enable_ice_restart: bool,
    expect_success: bool,
) {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let offer_codec = SignalCodec::new(&offer_identity.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);
    let offer_identity_for_task = clone_identity(&offer_identity.identity);
    let answer_identity_for_task = clone_identity(&answer_identity.identity);
    let offer_keys_for_task = offer_keys.clone();
    let answer_keys_for_task = answer_keys.clone();

    let offer_status_path = unique_path("offer-status.json");
    let answer_status_path = unique_path("answer-status.json");
    let offer_port = unused_local_port();

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr should exist").port();

    let mut offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let mut answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    offer_config.webrtc.enable_ice_restart = enable_ice_restart;
    answer_config.webrtc.enable_ice_restart = enable_ice_restart;
    let (offer_transport, answer_transport, trace) = transport_pair(
        duplicate_answer_to_offer_payloads,
        if inject_offer_disconnect { 300 } else { 0 },
    );
    let (hook_tx, mut hook_rx) = mpsc::unbounded_channel();
    let mut injected_session_id = None;

    let answer_server = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept should succeed");
        let mut received = [0_u8; 4];
        stream.read_exact(&mut received).await.expect("target should read request bytes");
        assert_eq!(&received, b"ping");
        stream.write_all(b"pong").await.expect("target should write response bytes");
        stream.shutdown().await.expect("target should shutdown cleanly");
    });

    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        offer_identity_for_task,
        offer_keys_for_task,
        offer_transport,
        Some(hook_tx),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        answer_identity_for_task,
        answer_keys_for_task,
        answer_transport,
    ));

    let mut client = connect_with_retry(offer_port).await;
    if inject_offer_disconnect {
        let OfferSessionTestHandle { session_id, ice_state_injector, .. } =
            timeout(Duration::from_secs(10), hook_rx.recv())
                .await
                .expect("offer session hook should arrive in time")
                .expect("offer session hook should contain a handle");
        injected_session_id = Some(session_id);
        ice_state_injector
            .inject(IceConnectionState::Disconnected)
            .await
            .expect("offer-side ice fault injection should succeed");
    }
    client.write_all(b"ping").await.expect("client should write request bytes");
    let mut response = [0_u8; 4];
    let client_result = timeout(Duration::from_secs(15), client.read_exact(&mut response)).await;

    if expect_success {
        let first_round_trip_succeeded = matches!(client_result, Ok(Ok(_))) && response == *b"pong";
        if first_round_trip_succeeded {
            client.shutdown().await.expect("client should shutdown cleanly");
        } else if inject_offer_disconnect && enable_ice_restart {
            assert_client_round_trip_eventually(
                offer_port,
                *b"ping",
                *b"pong",
                "offer-side reconnect should recover local client after injected ICE drop",
            )
            .await;
        } else {
            client_result
                .expect("client should receive tunnel response in time")
                .expect("client should read response bytes");
            assert_eq!(&response, b"pong");
            client.shutdown().await.expect("client should shutdown cleanly");
        }

        timeout(Duration::from_secs(15), answer_server)
            .await
            .expect("target server should finish in time")
            .expect("target server task should succeed");
    } else {
        let error = client_result
            .expect("client failure should arrive in time")
            .expect_err("client should not receive a successful tunnel response");
        assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
        answer_server.abort();
        let _ = answer_server.await;
    }

    let offer_status = wait_for_status(&offer_status_path, "tunnel_open").await;
    let answer_status = wait_for_status(&answer_status_path, "serving").await;
    assert_eq!(offer_status["current_state"], "tunnel_open");
    assert_eq!(offer_status["role"], "offer");
    assert_eq!(offer_status["mqtt_connected"], true);
    assert_eq!(answer_status["current_state"], "serving");
    assert_eq!(answer_status["role"], "answer");
    assert_eq!(answer_status["mqtt_connected"], true);

    if inject_offer_disconnect {
        let offer_to_answer =
            decode_signal_records(&trace.payloads_for("answer-office"), &answer_codec);
        let answer_to_offer =
            decode_signal_records(&trace.payloads_for("offer-home"), &offer_codec);
        assert!(
            offer_to_answer
                .iter()
                .filter(|record| record.message_type == MessageType::Offer)
                .count()
                >= 2,
            "offer side should publish a replacement offer after the injected disconnect"
        );
        assert!(
            !answer_to_offer.iter().any(|record| matches!(
                record.message_type,
                MessageType::Offer
                    | MessageType::IceRestartRequest
                    | MessageType::RenegotiateRequest
            )),
            "answer side must not initiate reconnect signaling"
        );
        if enable_ice_restart {
            assert!(
                offer_to_answer.iter().any(|record| {
                    record.message_type == MessageType::Offer
                        && Some(record.session_id) != injected_session_id
                }),
                "offer side should fall back to a replacement session when ICE fails before the data channel opens"
            );
        }
    }

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}
