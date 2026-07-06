//! P0-009: proves a *real* spawned `run_answer_session_task` panic propagates all
//! the way through supervision — `JoinHandle` -> `FuturesUnordered` completion ->
//! registry cleanup -> primary error -> shutdown -> other sessions drain ->
//! terminal status -> daemon returns `Err` — rather than fabricating an
//! `AnswerTaskCompletion` directly (the existing handler-level test in
//! `src/tests/answer_registry.rs` proves the handler's own logic but is not a
//! substitute for this end-to-end proof).

use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{
    AnswerSessionPanicTrigger, ShutdownToken,
    run_answer_daemon_with_session_panic_trigger_and_shutdown, run_offer_daemon_with_transport,
};

use crate::harness::*;

#[tokio::test]
async fn answer_session_real_panic_drains_registry_and_other_sessions_then_returns_err() {
    let offer_a_identity = generate_identity("offer-a").expect("offer identity should build");
    let offer_b_identity = generate_identity("offer-b").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let answer_keys = authorized_keys_for_many(&[&offer_a_identity, &offer_b_identity]);
    let offer_a_keys = authorized_keys_for(&answer_identity);
    let offer_b_keys = authorized_keys_for(&answer_identity);

    let answer_status_path = unique_path("answer-status.json");
    let offer_a_status_path = unique_path("offer-a-status.json");
    let offer_b_status_path = unique_path("offer-b-status.json");

    let offer_a_port = unused_local_port();
    let offer_b_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_a_config = sample_config_for(
        NodeRole::Offer,
        offer_a_status_path.clone(),
        offer_a_port,
        target_port,
        "offer-a",
        vec!["offer-a"],
    );
    let offer_b_config = sample_config_for(
        NodeRole::Offer,
        offer_b_status_path.clone(),
        offer_b_port,
        target_port,
        "offer-b",
        vec!["offer-b"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status_path.clone(),
        offer_a_port,
        target_port,
        "answer-office",
        vec!["offer-a", "offer-b"],
    );

    let mesh = InMemoryTransportMesh::new();
    let offer_a_transport = mesh.add_transport("offer-a");
    let offer_b_transport = mesh.add_transport("offer-b");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_a_task = tokio::spawn(run_offer_daemon_with_transport(
        offer_a_config,
        clone_identity(&offer_a_identity.identity),
        offer_a_keys,
        offer_a_transport,
    ));
    let offer_b_task = tokio::spawn(run_offer_daemon_with_transport(
        offer_b_config,
        clone_identity(&offer_b_identity.identity),
        offer_b_keys,
        offer_b_transport,
    ));

    let (panic_trigger, panic_arm) = AnswerSessionPanicTrigger::new();
    let answer_shutdown = ShutdownToken::new();
    let mut answer_task = tokio::spawn(run_answer_daemon_with_session_panic_trigger_and_shutdown(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
        panic_arm,
        answer_shutdown.clone(),
    ));

    // Offer A connects and is admitted first, so its real spawned session task is
    // the one that receives the panic arm.
    let _client_a = connect_with_retry(offer_a_port).await;
    wait_for_status_matching_with_timeout(
        &answer_status_path,
        "offer A session admitted",
        session_count_is(1),
        Duration::from_secs(10),
    )
    .await;

    // Offer B connects second: its session must survive the coming panic and
    // drain cooperatively as part of the daemon's shutdown.
    let _client_b = connect_with_retry(offer_b_port).await;
    wait_for_status_matching_with_timeout(
        &answer_status_path,
        "offer B session admitted",
        session_count_is(2),
        Duration::from_secs(10),
    )
    .await;

    // This panics for real, inside offer A's genuinely spawned
    // `run_answer_session_task`, not a fabricated `AnswerTaskCompletion`.
    panic_trigger.fire();

    let result = tokio::time::timeout(Duration::from_secs(10), &mut answer_task)
        .await
        .expect("answer daemon should observe the panic and shut down instead of hanging")
        .expect("the daemon task itself must not panic -- only the injected session task should");
    assert!(
        result.is_err(),
        "a real session task panic must become the daemon's primary error, got {result:?}"
    );

    let final_status = read_status_file(&answer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(
        final_status["active_session_count"], 0,
        "both the panicked session and offer B's drained session must be gone"
    );
    assert!(final_status["sessions"].as_array().expect("sessions array").is_empty());

    offer_a_task.abort();
    offer_b_task.abort();
    let _ = offer_a_task.await;
    let _ = offer_b_task.await;
    let _ = tokio::fs::remove_file(&offer_a_status_path).await;
    let _ = tokio::fs::remove_file(&offer_b_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}
