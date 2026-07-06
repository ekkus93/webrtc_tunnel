//! Lifecycle shutdown tests over the two-node in-memory-transport harness:
//! draining an active answer session without deadlocking the outer event loop,
//! an active offer session reaching cleanup, and shutdown interrupting an
//! in-progress offer reconnect/backoff wait.

use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{
    OfferSessionTestEvent, OfferSessionTestHandle, ShutdownToken, run_answer_daemon_with_transport,
    run_answer_daemon_with_transport_and_shutdown, run_offer_daemon_with_transport,
    run_offer_daemon_with_transport_and_shutdown,
    run_offer_daemon_with_transport_and_test_hook_and_shutdown,
};
use p2p_webrtc::IceConnectionState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn answer_active_session_shutdown_drains_without_deadlock() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-status.json");
    let answer_status_path = unique_path("answer-status.json");
    let offer_port = unused_local_port();

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr should exist").port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let (offer_transport, answer_transport, _trace) = transport_pair(0, 0);

    let answer_server = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept should succeed");
        let mut received = [0_u8; 4];
        stream.read_exact(&mut received).await.expect("target should read request bytes");
        assert_eq!(&received, b"ping");
        stream.write_all(b"pong").await.expect("target should write response bytes");
        stream.shutdown().await.expect("target should shutdown cleanly");
    });

    let offer_task = tokio::spawn(run_offer_daemon_with_transport(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
    ));

    let answer_shutdown = ShutdownToken::new();
    let answer_task = tokio::spawn(run_answer_daemon_with_transport_and_shutdown(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
        answer_shutdown.clone(),
    ));

    let mut client = connect_with_retry(offer_port).await;
    client.write_all(b"ping").await.expect("client should write request bytes");
    let mut response = [0_u8; 4];
    timeout(Duration::from_secs(15), client.read_exact(&mut response))
        .await
        .expect("client should receive tunnel response in time")
        .expect("client should read response bytes");
    assert_eq!(&response, b"pong");
    timeout(Duration::from_secs(15), answer_server)
        .await
        .expect("target server should finish in time")
        .expect("target server task should succeed");

    // Confirm the session is actually registered before draining it — otherwise this
    // test would not exercise the drain path the spec is worried about (an outer
    // event loop that stops servicing session publish requests before the session
    // has finished unwinding, which would deadlock instead of completing).
    wait_for_status_matching(&answer_status_path, "one active answer session", |status| {
        session_count_is(1)(status) && current_state_is("serving")(status)
    })
    .await;

    answer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(5), answer_task)
        .await
        .expect("answer daemon should drain and stop without deadlocking")
        .expect("answer daemon task should not panic");
    assert!(result.is_ok(), "graceful answer shutdown should return Ok, got {result:?}");

    let final_status = read_status_file(&answer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(final_status["mqtt_connected"], false);
    assert_eq!(final_status["active_session_count"], 0);
    assert!(final_status["sessions"].as_array().expect("sessions array").is_empty());

    client.shutdown().await.expect("client should shutdown cleanly");
    offer_task.abort();
    let _ = offer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}

#[tokio::test]
async fn offer_active_session_shutdown_reaches_cleanup() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-status.json");
    let answer_status_path = unique_path("answer-status.json");
    let offer_port = unused_local_port();

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr should exist").port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let (offer_transport, answer_transport, _trace) = transport_pair(0, 0);

    let answer_server = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept should succeed");
        let mut received = [0_u8; 4];
        stream.read_exact(&mut received).await.expect("target should read request bytes");
        assert_eq!(&received, b"ping");
        stream.write_all(b"pong").await.expect("target should write response bytes");
        // Deliberately leave this end of the target connection open (no shutdown) so
        // the local client's connection staying open depends on the offer session,
        // not on the target having already closed its half.
    });

    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        offer_shutdown.clone(),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let mut client = connect_with_retry(offer_port).await;
    client.write_all(b"ping").await.expect("client should write request bytes");
    let mut response = [0_u8; 4];
    timeout(Duration::from_secs(15), client.read_exact(&mut response))
        .await
        .expect("client should receive tunnel response in time")
        .expect("client should read response bytes");
    assert_eq!(&response, b"pong");
    answer_server.abort();
    let _ = answer_server.await;

    wait_for_status(&offer_status_path, "tunnel_open").await;

    offer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should stop before the test timeout")
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "graceful offer shutdown should return Ok, got {result:?}");

    // The bridge/data-channel/peer close during cleanup must close the local client
    // connection even though the client never initiated its own shutdown.
    let mut trailing = [0_u8; 1];
    let closed = timeout(Duration::from_secs(5), client.read(&mut trailing))
        .await
        .expect("client connection should close promptly after offer shutdown");
    assert!(
        matches!(closed, Ok(0)) || closed.is_err(),
        "client connection should observe EOF or an error after cleanup, got {closed:?}"
    );

    let final_status = read_status_file(&offer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(final_status["forwards"][0]["listen_state"], "stopped");

    answer_task.abort();
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}

#[tokio::test]
async fn offer_shutdown_during_reconnect_interrupts_backoff() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

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
    offer_config.webrtc.enable_ice_restart = true;
    answer_config.webrtc.enable_ice_restart = true;
    // Long enough that "shutdown interrupted the wait" is unambiguous against
    // "the reconnect attempt happened to finish quickly on its own", but this test
    // never waits out the full duration — shutdown fires long before it elapses.
    offer_config.reconnect.backoff_initial_ms = 5000;

    let (offer_transport, answer_transport, _trace) = transport_pair(0, 0);

    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let (hook_tx, mut hook_rx) = mpsc::unbounded_channel();
    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        Some(hook_tx),
        offer_shutdown.clone(),
    ));

    let mut client = connect_with_retry(offer_port).await;
    let OfferSessionTestHandle { ice_state_injector, mut test_events, .. } =
        timeout(Duration::from_secs(10), hook_rx.recv())
            .await
            .expect("offer session hook should arrive in time")
            .expect("offer session hook should contain a handle");

    ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("offer-side ice fault injection should succeed");

    // Wait for the actual reconnect/backoff transition instead of guessing it with a
    // sleep — otherwise the outer session-level shutdown branch could win the race
    // first and this test would not exercise the reconnect-specific interruption
    // at all (nor prove shutdown fired *during* the backoff wait, not before it).
    let event = timeout(Duration::from_secs(5), test_events.recv())
        .await
        .expect("reconnect backoff event should arrive in time")
        .expect("offer session test-event channel should stay open");
    assert!(
        matches!(event, OfferSessionTestEvent::ReconnectBackoffStarted { .. }),
        "expected a ReconnectBackoffStarted event, got {event:?}"
    );

    offer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(3), offer_task)
        .await
        .expect(
            "offer daemon should exit well before the 5s backoff completes; a hang here means \
             shutdown did not interrupt the reconnect wait",
        )
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "graceful offer shutdown should return Ok, got {result:?}");

    let final_status = read_status_file(&offer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");

    let _ = client.shutdown().await;
    answer_task.abort();
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}
