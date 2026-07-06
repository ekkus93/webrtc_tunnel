//! P0-006: proves the answer daemon's post-payload shutdown admission gate.
//! `poll_idle_signal_payload`'s future can become ready with a genuine incoming
//! offer before shutdown is requested, but shutdown can then be requested before
//! that ready payload's branch body actually runs its own admission check — this
//! forces that exact ordering deterministically via `PayloadAdmissionBarrier`
//! (production code, not a test-harness transport hook) instead of racing real
//! time against the scheduler.

use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{
    PayloadAdmissionBarrier, ShutdownToken,
    run_answer_daemon_with_payload_admission_barrier_and_shutdown, run_offer_daemon_with_transport,
};
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn answer_admits_no_new_session_when_shutdown_lands_between_payload_ready_and_admission() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-status.json");
    let answer_status_path = unique_path("answer-status.json");
    let offer_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);

    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_task = tokio::spawn(run_offer_daemon_with_transport(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
    ));

    let (barrier, mut barrier_entered, barrier_release) = PayloadAdmissionBarrier::new();
    let answer_shutdown = ShutdownToken::new();
    let mut answer_task =
        tokio::spawn(run_answer_daemon_with_payload_admission_barrier_and_shutdown(
            answer_config,
            clone_identity(&answer_identity.identity),
            answer_keys,
            answer_transport,
            barrier,
            answer_shutdown.clone(),
        ));

    // The offer sends its opening Offer message as soon as a local client
    // connects; nothing needs to fully tunnel for this test, since we are
    // proving the session is never admitted at all.
    let _client = connect_with_retry(offer_port).await;

    // The very first payload the answer daemon sees is an unrelated Hello (sent
    // before the real SDP Offer as part of every offer session's opening
    // handshake) — the barrier fires for it too since the production gate checks
    // shutdown for every payload, not just offers. Let it straight through
    // untouched; the race under test is about the offer that follows.
    timeout(Duration::from_secs(10), barrier_entered.wait()).await.expect(
        "the answer daemon should reach the payload-admission barrier for the Hello in time",
    );
    barrier_release.release().await;

    // Second payload: the actual SDP Offer. Shutdown lands in the exact window
    // the barrier is holding open here — the payload is already in hand, but the
    // admission check has not run yet.
    timeout(Duration::from_secs(10), barrier_entered.wait()).await.expect(
        "the answer daemon should reach the payload-admission barrier for the offer in time",
    );
    answer_shutdown.request_shutdown();
    barrier_release.release().await;

    let result = timeout(Duration::from_secs(10), &mut answer_task)
        .await
        .expect("answer daemon should drain and stop instead of hanging")
        .expect("answer daemon task should not panic");
    assert!(
        result.is_ok(),
        "a clean shutdown with no admitted session should return Ok, got {result:?}"
    );

    // A wrongly-admitted session sends its Ack and SDP Answer synchronously,
    // inside `start_answer_session_from_offer`, before the session task is even
    // spawned — so unlike polling the status file (which a session that admits
    // and then immediately self-terminates on the already-cancelled shutdown
    // token can race past unnoticed), the transport trace permanently records
    // whether that admission path ever ran at all.
    let sent_to_offer = mesh.trace().payloads_for("offer-home");
    assert!(
        sent_to_offer.is_empty(),
        "no Ack or Answer may be sent for the in-hand payload once shutdown was requested, \
         even though the barrier had already let it through as ready; got {} message(s) sent",
        sent_to_offer.len()
    );

    let final_status = read_status_file(&answer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(
        final_status["active_session_count"], 0,
        "the in-hand payload must not have been admitted into a new session"
    );
    assert!(final_status["sessions"].as_array().expect("sessions array").is_empty());

    offer_task.abort();
    let _ = offer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}
