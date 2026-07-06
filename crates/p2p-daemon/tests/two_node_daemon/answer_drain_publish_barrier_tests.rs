//! P0-009: proves the answer daemon's outer event loop stays alive and drains
//! correctly while a session-originated signaling publish is genuinely in
//! flight (blocked on the transport) at the moment shutdown is requested —
//! rather than assuming the event loop and the blocked session task can never
//! interact badly under shutdown.

use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{
    ShutdownToken, run_answer_daemon_with_transport_and_shutdown, run_offer_daemon_with_transport,
};
use p2p_signaling::{ReplayCache, SignalCodec};
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn answer_drain_completes_while_a_session_publish_is_in_flight() {
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
    let control = mesh.control();
    let trace = mesh.trace();

    let offer_task = tokio::spawn(run_offer_daemon_with_transport(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
    ));
    let answer_shutdown = ShutdownToken::new();
    let mut answer_task = tokio::spawn(run_answer_daemon_with_transport_and_shutdown(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
        answer_shutdown.clone(),
    ));

    // The offer only starts a session once a local client connects; the target
    // side of the tunnel is never accepted and the tunnel never needs to fully
    // open — we only need the answer session registered (initial ack + SDP
    // answer already published).
    let _client = connect_with_retry(offer_port).await;
    wait_for_status_matching(
        &answer_status_path,
        "one answer session registered",
        session_count_is(1),
    )
    .await;

    // Find an already-processed offer -> answer message that requires an ack
    // (Offer/IceCandidate, not Hello) so replaying it deterministically triggers
    // exactly one fresh session-task-originated re-ack publish, reaching the
    // transport via the same AnswerSessionEvent::Publish -> outer-loop ->
    // transport.publish_signal path any in-session signaling action takes. This
    // is deterministic regardless of platform/network ICE-gathering timing,
    // unlike waiting for a real ICE candidate to be gathered and published.
    let offer_public_keys = authorized_keys_for(&offer_identity);
    let decode_codec = SignalCodec::new(&answer_identity.identity, &offer_public_keys, 120, 300);
    let mut inspect_replay_cache = ReplayCache::new(64);
    let replayable_payload = trace
        .payloads_for("answer-office")
        .into_iter()
        .find(|payload| {
            decode_codec
                .decode(payload, &mut inspect_replay_cache, None)
                .is_ok_and(|(_, message, _)| message.message_type.requires_ack())
        })
        .expect("offer should have sent at least one ack-requiring message to the answer by now");

    let (barrier_entered, barrier_release) =
        control.block_next_publish("answer-office", "offer-home");

    control.inject_payload("answer-office", replayable_payload);

    timeout(Duration::from_secs(10), barrier_entered.wait())
        .await
        .expect("the duplicate message's re-ack publish should reach the transport in time");

    answer_shutdown.request_shutdown();

    // The publish is still blocked, so the daemon must not have already
    // returned — proving the event loop is genuinely stuck mid-publish (not
    // that this test raced past the interesting window) before we prove it
    // can still finish once unblocked.
    let premature = timeout(Duration::from_millis(150), &mut answer_task).await;
    assert!(
        premature.is_err(),
        "answer daemon must still be blocked on the in-flight publish, not already returned"
    );

    barrier_release.release();

    let result = timeout(Duration::from_secs(10), answer_task)
        .await
        .expect("answer daemon should drain and stop once the publish is released")
        .expect("answer daemon task should not panic");
    assert!(result.is_ok(), "graceful answer shutdown should return Ok, got {result:?}");

    let final_status = read_status_file(&answer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(final_status["active_session_count"], 0);
    assert!(final_status["sessions"].as_array().expect("sessions array").is_empty());

    offer_task.abort();
    let _ = offer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}
