//! P0-003/P0-016: an offer accept-worker that dies unexpectedly (panic, or here a
//! forced abort — the monitor observes both identically as `Err(JoinError)`) must
//! be treated as a daemon-fatal infrastructure failure, both while the daemon is
//! idle (waiting for a local client) and while a session is actively bridging.
//! Before this supervision existed, nothing observed a dead accept-worker task
//! until final shutdown, so the daemon could sit forever reporting a normal
//! waiting/serving status over a listener nothing was actually feeding anymore.

use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{
    OfferAcceptWorkerTestHandle, ShutdownToken, run_answer_daemon_with_transport,
    run_offer_daemon_with_worker_fault_hook_and_shutdown,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn offer_idle_accept_worker_failure_is_daemon_fatal() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);

    let offer_status_path = unique_path("offer-status.json");
    let offer_port = unused_local_port();
    let target_port = unused_local_port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let (offer_transport, _answer_transport, _trace) = transport_pair(0, 0);

    let (fault_tx, mut fault_rx) = mpsc::unbounded_channel();
    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_worker_fault_hook_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        fault_tx,
        offer_shutdown.clone(),
    ));

    wait_for_status(&offer_status_path, "waiting_for_local_client").await;

    let worker_handles: Vec<OfferAcceptWorkerTestHandle> =
        timeout(Duration::from_secs(5), fault_rx.recv())
            .await
            .expect("worker fault hook should fire once the accept runtime starts")
            .expect("worker fault hook channel should stay open");
    assert_eq!(worker_handles.len(), 1, "single-forward config should spawn exactly one worker");
    worker_handles[0].abort_handle.abort();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should observe the dead worker and exit instead of hanging")
        .expect("offer daemon task should not panic");
    assert!(
        result.is_err(),
        "an unsupervised accept-worker death must be daemon-fatal, got {result:?}"
    );

    let final_status = read_status_file(&offer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");
    assert_eq!(final_status["forwards"][0]["listen_state"], "stopped");

    let _ = tokio::fs::remove_file(&offer_status_path).await;
}

#[tokio::test]
async fn offer_active_session_accept_worker_failure_is_daemon_fatal() {
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
    });

    let (fault_tx, mut fault_rx) = mpsc::unbounded_channel();
    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_worker_fault_hook_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        fault_tx,
        offer_shutdown.clone(),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let worker_handles: Vec<OfferAcceptWorkerTestHandle> =
        timeout(Duration::from_secs(5), fault_rx.recv())
            .await
            .expect("worker fault hook should fire once the accept runtime starts")
            .expect("worker fault hook channel should stay open");
    assert_eq!(worker_handles.len(), 1, "single-forward config should spawn exactly one worker");

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

    wait_for_status(&offer_status_path, "tunnel_open").await;

    // The worker died while a session is actively bridging, not while idle — proves
    // supervision reaches the session-level select loop too (`OfferSessionIo`'s own
    // `worker_exits` branch), not just the outer idle-daemon loop.
    worker_handles[0].abort_handle.abort();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect("offer daemon should observe the dead worker and exit instead of hanging")
        .expect("offer daemon task should not panic");
    assert!(
        result.is_err(),
        "an unsupervised accept-worker death during an active session must be daemon-fatal, got {result:?}"
    );

    let final_status = read_status_file(&offer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");

    let _ = client.shutdown().await;
    answer_task.abort();
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}

/// P0-002: a one-worker test cannot distinguish "the active-session classification
/// fix actually works" from "the worker-exit channel happened to close entirely
/// because it was the only worker" — with a single forward, killing the only
/// worker also closes `worker_exits` outright, which was *already* daemon-fatal via
/// a separate, unrelated code path (the supervisor-channel-closed branch), so that
/// test would pass even if the active-session classification in P0-001 were
/// reverted. With a second, still-alive worker, `worker_exits` stays open after the
/// first worker dies, so this test can only pass if the active-session result
/// itself is correctly classified as an infrastructure failure and breaks the run
/// loop — proving a surviving worker cannot mask another worker's death.
#[tokio::test]
async fn offer_active_session_second_worker_alive_cannot_mask_first_workers_death() {
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
    // A second offer forward with its own independent listener/worker. Nothing ever
    // connects through it — it only needs to exist and stay alive so its worker
    // surviving the first worker's death cannot mask that death.
    let second_offer_port = unused_local_port();
    let second_target_port = unused_local_port();
    add_offer_forward(&mut offer_config, "web", second_offer_port, second_target_port);

    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let (offer_transport, answer_transport, _trace) = transport_pair(0, 0);

    let answer_server = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept should succeed");
        let mut received = [0_u8; 4];
        stream.read_exact(&mut received).await.expect("target should read request bytes");
        assert_eq!(&received, b"ping");
        stream.write_all(b"pong").await.expect("target should write response bytes");
    });

    let (fault_tx, mut fault_rx) = mpsc::unbounded_channel();
    let offer_shutdown = ShutdownToken::new();
    let offer_task = tokio::spawn(run_offer_daemon_with_worker_fault_hook_and_shutdown(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        fault_tx,
        offer_shutdown.clone(),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let worker_handles: Vec<OfferAcceptWorkerTestHandle> =
        timeout(Duration::from_secs(5), fault_rx.recv())
            .await
            .expect("worker fault hook should fire once the accept runtime starts")
            .expect("worker fault hook channel should stay open");
    assert_eq!(worker_handles.len(), 2, "two-forward config should spawn exactly two workers");
    // Selected by forward ID rather than spawn order (P1-002), so this test does not
    // depend on `bind_offer_listeners`/`spawn_offer_accept_loops` iterating
    // `config.forwards` in any particular order.
    let ssh_worker = &worker_handles
        .iter()
        .find(|worker| worker.forward_id == "ssh")
        .expect("ssh worker test handle")
        .abort_handle;
    let web_worker = &worker_handles
        .iter()
        .find(|worker| worker.forward_id == "web")
        .expect("web worker test handle")
        .abort_handle;
    assert!(!ssh_worker.is_finished(), "ssh worker should still be alive before the fault");
    assert!(!web_worker.is_finished(), "web worker should still be alive before the fault");

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

    wait_for_status(&offer_status_path, "tunnel_open").await;

    // Kill only the "ssh" worker; "web" stays alive throughout.
    ssh_worker.abort();

    let result = timeout(Duration::from_secs(5), offer_task)
        .await
        .expect(
            "offer daemon should observe the dead ssh worker and exit instead of being masked \
             by the still-alive web worker",
        )
        .expect("offer daemon task should not panic");
    assert!(
        result.is_err(),
        "a dead worker on one forward must be daemon-fatal even while another forward's worker \
         is still alive, got {result:?}"
    );

    // The finalizer's cooperative shutdown must have stopped the surviving "web"
    // worker too — it must not be left running after the daemon has exited.
    assert!(
        web_worker.is_finished(),
        "the still-alive web worker must receive cooperative shutdown during finalization, \
         not be abandoned running"
    );

    let final_status = read_status_file(&offer_status_path).await;
    assert_eq!(final_status["current_state"], "closed");

    let _ = client.shutdown().await;
    answer_task.abort();
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(&offer_status_path).await;
    let _ = tokio::fs::remove_file(&answer_status_path).await;
}
