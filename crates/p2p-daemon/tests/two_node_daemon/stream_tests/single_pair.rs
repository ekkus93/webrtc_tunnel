use std::time::Duration;

use p2p_core::{ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, MessageType, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{run_answer_daemon_with_transport, run_offer_daemon_with_transport_and_test_hook};
use p2p_signaling::SignalCodec;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn signaling_turbulence_does_not_interrupt_active_tcp_stream() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let offer_status_path = unique_path("offer-stream-turbulence-status.json");
    let answer_status_path = unique_path("answer-stream-turbulence-status.json");
    let offer_port = unused_local_port();
    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr").port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");

    let target_task = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept");
        for expected in [*b"a001", *b"a002", *b"a003"] {
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).await.expect("target read");
            assert_eq!(request, expected);
            stream.write_all(&request).await.expect("target write");
        }
        stream.shutdown().await.expect("target shutdown");
    });
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let mut client = connect_with_retry(offer_port).await;
    for payload in [*b"a001", *b"a002", *b"a003"] {
        if payload == *b"a002" {
            wait_for_mqtt_disconnected_after_poll_failure(
                &control,
                "answer-office",
                &answer_status_path,
                "answer mqtt disconnected while stream remains open",
                Duration::from_secs(20),
            )
            .await;
        }
        client.write_all(&payload).await.expect("client write");
        let mut response = [0_u8; 4];
        timeout(Duration::from_secs(10), client.read_exact(&mut response))
            .await
            .expect("client should receive response")
            .expect("client read");
        assert_eq!(response, payload);
        if payload == *b"a002" {
            control.inject_payload("answer-office", vec![0_u8]);
            wait_for_status_matching_with_timeout(
                &answer_status_path,
                "answer mqtt recovered while stream remains open",
                mqtt_connected_is(true),
                Duration::from_secs(20),
            )
            .await;
        }
    }
    client.shutdown().await.expect("client shutdown");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");

    let status =
        wait_for_status_matching(&answer_status_path, "serving after turbulence", |status| {
            current_state_is("serving")(status) && mqtt_connected_is(true)(status)
        })
        .await;
    assert_status_schema_is_consistent(&status);
    let offer_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert_eq!(
        count_records_from(&offer_records, "offer-home", MessageType::Offer),
        1,
        "signaling-only turbulence must not create a duplicate session"
    );

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[tokio::test]
async fn offer_and_answer_daemons_handle_two_forwards_concurrently() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let offer_identity_for_task = clone_identity(&offer_identity.identity);
    let answer_identity_for_task = clone_identity(&answer_identity.identity);
    let offer_keys_for_task = offer_keys.clone();
    let answer_keys_for_task = answer_keys.clone();

    let offer_status_path = unique_path("offer-multi-status.json");
    let answer_status_path = unique_path("answer-multi-status.json");
    let ssh_offer_port = unused_local_port();
    let web_offer_port = unused_local_port();

    let ssh_target =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh target listener should bind");
    let web_target =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("web target listener should bind");
    let ssh_target_port = ssh_target.local_addr().expect("ssh target addr").port();
    let web_target_port = web_target.local_addr().expect("web target addr").port();

    let mut offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), ssh_offer_port, ssh_target_port);
    offer_config.forwards.push(ForwardRule {
        id: "web-ui".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: web_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: web_target_port,
            allow_remote_peers: vec!["offer-home".parse().expect("offer peer id")],
        }),
    });
    let mut answer_config = sample_config(
        NodeRole::Answer,
        answer_status_path.clone(),
        ssh_offer_port,
        ssh_target_port,
    );
    answer_config.forwards.push(ForwardRule {
        id: "web-ui".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: web_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: web_target_port,
            allow_remote_peers: vec!["offer-home".parse().expect("offer peer id")],
        }),
    });

    let (offer_transport, answer_transport, _trace) = transport_pair(0, 0);
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        offer_identity_for_task,
        offer_keys_for_task,
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        answer_identity_for_task,
        answer_keys_for_task,
        answer_transport,
    ));

    let ssh_target_task = tokio::spawn(async move {
        let (mut stream, _) = ssh_target.accept().await.expect("ssh target accept");
        let mut request = [0_u8; 3];
        stream.read_exact(&mut request).await.expect("ssh target read");
        assert_eq!(&request, b"ssh");
        stream.write_all(b"SSH").await.expect("ssh target write");
        stream.shutdown().await.expect("ssh target shutdown");
    });
    let web_target_task = tokio::spawn(async move {
        let (mut stream, _) = web_target.accept().await.expect("web target accept");
        let mut request = [0_u8; 3];
        stream.read_exact(&mut request).await.expect("web target read");
        assert_eq!(&request, b"web");
        stream.write_all(b"WEB").await.expect("web target write");
        stream.shutdown().await.expect("web target shutdown");
    });

    let ssh_client_task = tokio::spawn(async move {
        let mut client = connect_with_retry(ssh_offer_port).await;
        client.write_all(b"ssh").await.expect("ssh client write");
        let mut response = [0_u8; 3];
        client.read_exact(&mut response).await.expect("ssh client read");
        assert_eq!(&response, b"SSH");
        client.shutdown().await.expect("ssh client shutdown");
    });
    let web_client_task = tokio::spawn(async move {
        let mut client = connect_with_retry(web_offer_port).await;
        client.write_all(b"web").await.expect("web client write");
        let mut response = [0_u8; 3];
        client.read_exact(&mut response).await.expect("web client read");
        assert_eq!(&response, b"WEB");
        client.shutdown().await.expect("web client shutdown");
    });

    timeout(Duration::from_secs(15), ssh_client_task)
        .await
        .expect("ssh client should finish")
        .expect("ssh client should succeed");
    timeout(Duration::from_secs(15), web_client_task)
        .await
        .expect("web client should finish")
        .expect("web client should succeed");
    timeout(Duration::from_secs(15), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target should succeed");
    timeout(Duration::from_secs(15), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target should succeed");

    let offer_status = wait_for_status(&offer_status_path, "tunnel_open").await;
    let forwards = offer_status["configured_forwards"].as_array().expect("configured forwards");
    assert!(forwards.iter().any(|forward| forward == "ssh"));
    assert!(forwards.iter().any(|forward| forward == "web-ui"));
    let _ = wait_for_status(&answer_status_path, "serving").await;

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}
