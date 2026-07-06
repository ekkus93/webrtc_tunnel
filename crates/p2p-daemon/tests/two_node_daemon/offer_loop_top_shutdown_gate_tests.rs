//! P0-005/P0-010/P0-002: proves the offer daemon's top-of-loop shutdown gate. An
//! ordinary (non-infrastructure) session outcome brings the run loop back to its
//! top more than once before ever blocking in the select again — e.g. every time
//! a session fails a quick, recoverable publish — and shutdown can be requested
//! in the exact window between that and the next steady-state write.
//! `OfferLoopTopBarrier` (production code, not a test-harness transport hook)
//! forces that ordering deterministically instead of racing real scheduler
//! timing. The assertion source is a non-coalescing `StatusAuditLog`, not a
//! `watch` stream: a `watch` receiver could coalesce an illegal intermediate
//! write together with the terminal `Closed` write and never let this test see
//! it, so it is not valid proof that the write never happened (P0-002).

use std::time::Duration;

use p2p_core::{DaemonState, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{
    OfferLoopTopBarrier, ShutdownToken, StatusAuditLog,
    run_offer_daemon_with_loop_top_barrier_and_status_audit_and_shutdown,
};
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn offer_admits_no_ordinary_write_when_shutdown_lands_between_session_outcome_and_loop_top() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);

    let offer_status_path = unique_path("offer-status-loop-top-gate");
    let offer_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);

    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let _answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();

    // The offer session's very first outbound message is an unrequired-ack Hello;
    // failing its publish ends the session quickly with an ordinary (non-fatal)
    // error, without needing a real answer daemon or WebRTC negotiation.
    control.fail_next_publish("offer-home", "answer-office", 1);

    let audit = StatusAuditLog::default();

    let (barrier, mut barrier_entered, barrier_release) = OfferLoopTopBarrier::new();
    let offer_shutdown = ShutdownToken::new();
    let mut offer_task =
        tokio::spawn(run_offer_daemon_with_loop_top_barrier_and_status_audit_and_shutdown(
            offer_config,
            clone_identity(&offer_identity.identity),
            offer_keys,
            offer_transport,
            barrier,
            audit.clone(),
            offer_shutdown.clone(),
        ));

    // First loop iteration: nothing has happened yet. Let it through untouched.
    timeout(Duration::from_secs(10), barrier_entered.wait())
        .await
        .expect("the offer daemon should reach the loop-top barrier for the first iteration");
    barrier_release.release().await;

    // Connect a client; its session fails quickly (injected publish failure) with
    // an ordinary error, bringing the loop back to its top for a second iteration.
    let _client = connect_with_retry(offer_port).await;

    // Second loop iteration: shutdown lands in the exact window the barrier is
    // holding open here — the ordinary session outcome has already returned
    // control to the top of the loop, but the shutdown gate/steady-state write
    // has not run yet. The boundary is captured immediately before the request,
    // not inferred from any later state value.
    timeout(Duration::from_secs(10), barrier_entered.wait()).await.expect(
        "the offer daemon should reach the loop-top barrier again after the session outcome",
    );
    let boundary = audit.len();
    offer_shutdown.request_shutdown();
    barrier_release.release().await;

    let result = timeout(Duration::from_secs(10), &mut offer_task)
        .await
        .expect("offer daemon should drain and stop instead of hanging")
        .expect("offer daemon task should not panic");
    assert!(result.is_ok(), "a clean shutdown should return Ok, got {result:?}");

    let events = audit.snapshot();
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
    assert!(
        events[boundary..].iter().any(|status| status.current_state == DaemonState::Closed),
        "terminal Closed status was not emitted after the shutdown boundary, got {events:?}"
    );

    let _ = tokio::fs::remove_file(&offer_status_path).await;
}
