//! P0-016: deterministic regression coverage for the offer channel-close/shutdown
//! race and for "no ordinary status write survives a shutdown request."

use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{ShutdownToken, run_offer_daemon_with_transport_and_shutdown};
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

/// Polls the offer status file at a tight interval spanning the shutdown request,
/// proving the daemon runtime-phase gate (P0-001) holds under a real running
/// daemon: once shutdown is requested, the only states ever observed are the
/// last pre-shutdown state (untouched, because ordinary writes are now
/// suppressed while Draining) and the terminal `closed` — never a resurrected
/// `waiting_for_local_client`/`serving`/`negotiating`/`tunnel_open`.
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

    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        offer_shutdown.clone(),
    ));

    wait_for_status(&offer_status_path, "waiting_for_local_client").await;

    let poll_path = offer_status_path.clone();
    let samples = std::sync::Arc::new(tokio::sync::Mutex::new(Vec::<String>::new()));
    let samples_writer = samples.clone();
    let poller = tokio::spawn(async move {
        loop {
            if let Ok(content) = tokio::fs::read_to_string(&poll_path).await
                && let Ok(json) = serde_json::from_str::<serde_json::Value>(&content)
                && let Some(state) = json["current_state"].as_str()
            {
                let mut samples = samples_writer.lock().await;
                if samples.last().map(String::as_str) != Some(state) {
                    samples.push(state.to_owned());
                }
            }
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    });

    offer_shutdown.request_shutdown();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should stop promptly")
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "graceful offer shutdown should return Ok, got {result:?}");

    // The terminal write already landed on disk before `offer_task` joined above;
    // wait (bounded, but generously so this doesn't flake under CI/parallel-test
    // scheduling contention) for the poller to actually get scheduled and observe
    // it, rather than assuming a fixed short sleep is always enough CPU time.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        if samples.lock().await.last().map(String::as_str) == Some("closed") {
            break;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "poller never observed the terminal closed status in time"
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    poller.abort();
    let _ = poller.await;

    let samples = samples.lock().await;
    assert_eq!(
        samples.last().map(String::as_str),
        Some("closed"),
        "final observed state should be closed, got {samples:?}"
    );
    // Every transition after the last waiting_for_local_client sample must go
    // straight to closed — no serving/negotiating/tunnel_open resurrection.
    let last_waiting_index = samples
        .iter()
        .rposition(|state| state == "waiting_for_local_client")
        .expect("waiting_for_local_client should have been observed at least once");
    for state in &samples[last_waiting_index + 1..] {
        assert_eq!(
            state, "closed",
            "no ordinary status write should survive the shutdown request, saw {samples:?}"
        );
    }

    let _ = tokio::fs::remove_file(&offer_status_path).await;
}
