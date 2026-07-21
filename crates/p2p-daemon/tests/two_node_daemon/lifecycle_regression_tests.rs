//! P0-016: deterministic regression coverage for the offer channel-close/shutdown
//! race and for "no ordinary status write survives a shutdown request."

use std::sync::{Arc, Mutex};
use std::time::Duration;

use p2p_core::{DaemonState, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{
    DaemonStatus, ShutdownToken, run_offer_daemon_with_transport_and_shutdown,
    run_offer_daemon_with_transport_and_status_and_shutdown,
};
use tokio::time::timeout;

use crate::harness::*;

/// Repeats the idle offer daemon start/request-shutdown/join cycle many times in
/// one test. Before P0-002's finalizer refactor, the outer select's
/// `accepted_clients.recv()` branch racing the shutdown branch could occasionally
/// win and bypass listener cleanup/the terminal status write; a single run rarely
/// caught that, so this repeats enough iterations to catch a reintroduced
/// select-order regression instead of relying on probabilistic luck.
#[tokio::test]
async fn offer_idle_shutdown_is_race_free_under_repeated_iterations() {
    for iteration in 0..20 {
        let offer_identity = generate_identity("offer-home").expect("offer identity should build");
        let answer_identity =
            generate_identity("answer-office").expect("answer identity should build");
        let offer_keys = authorized_keys_for(&answer_identity);

        let offer_status_path = unique_path(&format!("offer-status-race-{iteration}"));
        let offer_port = unused_local_port();
        let target_port = unused_local_port();

        let offer_config =
            sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
        let (offer_transport, _answer_transport, _trace) = transport_pair(0, 0);

        let offer_shutdown = ShutdownToken::new();
        let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_shutdown(
            offer_config,
            clone_identity(&offer_identity.identity),
            offer_keys,
            offer_transport,
            offer_shutdown.clone(),
        ));

        wait_for_status(&offer_status_path, "waiting_for_local_client").await;
        offer_shutdown.request_shutdown();

        let result = timeout(Duration::from_secs(5), offer_task)
            .await
            .unwrap_or_else(|_| panic!("iteration {iteration}: offer daemon should stop promptly"))
            .unwrap_or_else(|_| {
                panic!("iteration {iteration}: offer daemon task should not panic")
            });
        assert!(
            result.is_ok(),
            "iteration {iteration}: graceful offer shutdown should return Ok, got {result:?}"
        );

        let final_status = read_status_file(&offer_status_path).await;
        assert_eq!(
            final_status["current_state"], "closed",
            "iteration {iteration}: final status should be closed"
        );
        assert_eq!(
            final_status["forwards"][0]["listen_state"], "stopped",
            "iteration {iteration}: listener should be released"
        );

        let _ = tokio::fs::remove_file(&offer_status_path).await;
    }
}

/// FIX7 P0-008/spec §6.11: the offer daemon must return `Ok(())` when shutdown is
/// requested while Listening/waiting for a local client with no peer/session, and
/// no primary failure preceded shutdown. Named to match P0-008-D's required test
/// list exactly (a single run, not the repeated-iteration race regression above).
#[tokio::test]
async fn offer_shutdown_while_listening_without_peer_returns_ok() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);

    let offer_status_path = unique_path("offer-status-shutdown-no-peer-ok");
    let offer_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let (offer_transport, _answer_transport, _trace) = transport_pair(0, 0);

    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        offer_shutdown.clone(),
    ));

    wait_for_status(&offer_status_path, "waiting_for_local_client").await;
    offer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should stop promptly")
        .expect("offer daemon task should not panic");
    assert!(
        result.is_ok(),
        "a cooperative shutdown while Listening with no peer must return Ok, got {result:?}"
    );

    let _ = tokio::fs::remove_file(&offer_status_path).await;
}

/// FIX7 P0-008/spec §6.11: the same cooperative-shutdown-while-Listening scenario
/// as above, but asserting the final published status is the terminal stopped
/// state (daemon-level `closed`, with the listener released) rather than just the
/// task's `Result`. Named to match P0-008-D's required test list exactly.
#[tokio::test]
async fn offer_shutdown_while_listening_publishes_final_stopped_status() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);

    let offer_status_path = unique_path("offer-status-shutdown-no-peer-stopped");
    let offer_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let (offer_transport, _answer_transport, _trace) = transport_pair(0, 0);

    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        offer_shutdown.clone(),
    ));

    wait_for_status(&offer_status_path, "waiting_for_local_client").await;
    offer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should stop promptly")
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "a cooperative shutdown must return Ok, got {result:?}");

    let final_status = read_status_file(&offer_status_path).await;
    assert_eq!(
        final_status["current_state"], "closed",
        "final status must be the terminal stopped state"
    );
    assert_eq!(
        final_status["forwards"][0]["listen_state"], "stopped",
        "the bound listener must be released"
    );

    let _ = tokio::fs::remove_file(&offer_status_path).await;
}

/// Observes every live status transition (via the `watch`-backed status sink, not
/// file polling) spanning the shutdown request, proving the daemon runtime-phase
/// gate (P0-001) and the top-of-loop shutdown gate (P0-005) both hold under a real
/// running daemon: the boundary is the exact program-order moment
/// `shutdown.request_shutdown()` is called (P0-010), not an inferred "last
/// waiting_for_local_client" sample from noisy polling — so nothing that happens
/// to land between that call and the terminal `closed` write can silently escape
/// the check.
#[tokio::test]
async fn offer_no_normal_status_write_survives_shutdown_request() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);

    let offer_status_path = unique_path("offer-status-no-resurrection");
    let offer_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let (offer_transport, _answer_transport, _trace) = transport_pair(0, 0);

    let (status_tx, mut status_rx) = tokio::sync::watch::channel(DaemonStatus {
        peer_id: offer_identity.identity.peer_id.clone(),
        role: NodeRole::Offer,
        mqtt_connected: false,
        active_session_id: None,
        current_state: DaemonState::WaitingForLocalClient,
        active_session_count: 0,
        session_capacity: 1,
        sessions: Vec::new(),
        configured_forwards: Vec::new(),
        forwards: Vec::new(),
    });
    let observed = Arc::new(Mutex::new(Vec::<DaemonStatus>::new()));
    let observer_events = observed.clone();
    let observer = tokio::spawn(async move {
        loop {
            if status_rx.changed().await.is_err() {
                return;
            }
            observer_events.lock().expect("observed status lock").push(status_rx.borrow().clone());
        }
    });

    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_status_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        status_tx,
        offer_shutdown.clone(),
    ));

    wait_for_status(&offer_status_path, "waiting_for_local_client").await;

    // Captured synchronously, in the same task, immediately before the shutdown
    // request that follows on the next line: this is the exact boundary, not an
    // inference from a later sample.
    let boundary = observed.lock().expect("observed status lock").len();
    offer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should stop promptly")
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "graceful offer shutdown should return Ok, got {result:?}");

    // The terminal write already landed before `offer_task` joined above; wait
    // (bounded, but generously so this doesn't flake under CI/parallel-test
    // scheduling contention) for the observer to actually get scheduled and see it.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        if observed.lock().expect("observed status lock").last().map(|status| status.current_state)
            == Some(DaemonState::Closed)
        {
            break;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "observer never saw the terminal closed status in time"
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    observer.abort();
    let _ = observer.await;

    let events = observed.lock().expect("observed status lock").clone();
    for status in &events[boundary..] {
        assert!(
            !matches!(
                status.current_state,
                DaemonState::WaitingForLocalClient
                    | DaemonState::Serving
                    | DaemonState::Negotiating
                    | DaemonState::TunnelOpen
            ),
            "normal state emitted after the shutdown boundary: {:?} in {:?}",
            status.current_state,
            &events[boundary..],
        );
    }
    assert_eq!(
        events.last().map(|status| status.current_state),
        Some(DaemonState::Closed),
        "final observed state should be closed, got {events:?}"
    );

    let _ = tokio::fs::remove_file(&offer_status_path).await;
}
