use std::time::Duration;

use p2p_core::NodeRole;
use p2p_crypto::generate_identity;
use p2p_daemon::{run_answer_daemon_with_transport, run_offer_daemon_with_transport_and_test_hook};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn answer_daemon_serves_two_offer_peers_concurrently() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-status.json");
    let offer_desktop_status = unique_path("offer-desktop-status.json");
    let answer_status = unique_path("answer-v03-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target addr").port();

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let target_task = tokio::spawn(async move {
        for _ in 0..2 {
            let (mut stream, _) = target_listener.accept().await.expect("target accept");
            tokio::spawn(async move {
                let mut request = [0_u8; 4];
                stream.read_exact(&mut request).await.expect("target read");
                stream.write_all(&request).await.expect("target write");
                stream.shutdown().await.expect("target shutdown");
            });
        }
    });

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let home_client = tokio::spawn(async move {
        let mut client = connect_with_retry(offer_home_port).await;
        client.write_all(b"home").await.expect("home client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("home client read");
        assert_eq!(&response, b"home");
    });
    let desktop_client = tokio::spawn(async move {
        let mut client = connect_with_retry(offer_desktop_port).await;
        client.write_all(b"desk").await.expect("desktop client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("desktop client read");
        assert_eq!(&response, b"desk");
    });

    timeout(Duration::from_secs(20), home_client)
        .await
        .expect("home client should finish")
        .expect("home client should succeed");
    timeout(Duration::from_secs(20), desktop_client)
        .await
        .expect("desktop client should finish")
        .expect("desktop client should succeed");

    let status = wait_for_session_count(&answer_status, 2).await;
    let sessions = status["sessions"].as_array().expect("sessions array");
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-home"));
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-desktop"));

    target_task.abort();
    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = target_task.await;
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}
