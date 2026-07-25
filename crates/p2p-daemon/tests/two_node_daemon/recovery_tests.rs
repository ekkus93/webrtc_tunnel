use std::sync::atomic::Ordering;
use std::time::Duration;

use p2p_core::{ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, MessageType, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{run_answer_daemon_with_transport, run_offer_daemon_with_transport_and_test_hook};
use p2p_signaling::SignalCodec;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::{sleep, timeout};

use crate::harness::*;

#[tokio::test]
async fn answer_daemon_restart_with_same_identity_accepts_fresh_offer_side_session() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let offer_codec = SignalCodec::new(&offer_identity.identity, &offer_keys, 120, 300);

    let offer_status = unique_path("offer-restart-status.json");
    let answer_status = unique_path("answer-restart-status.json");
    let offer_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;
    let offer_config =
        sample_config(NodeRole::Offer, offer_status.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status.clone(), offer_port, target_port);

    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config.clone(),
        clone_identity(&offer_identity.identity),
        offer_keys.clone(),
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config.clone(),
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));
    assert_client_round_trip(offer_port, b"r001", b"r001").await;
    wait_for_status_matching(&answer_status, "first restarted-session status", session_count_is(1))
        .await;

    answer_task.abort();
    offer_task.abort();
    let _ = answer_task.await;
    let _ = offer_task.await;

    let restarted_offer_port = unused_local_port();
    let restarted_offer_config =
        sample_config(NodeRole::Offer, offer_status.clone(), restarted_offer_port, target_port);
    let restarted_answer_config =
        sample_config(NodeRole::Answer, answer_status.clone(), restarted_offer_port, target_port);
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let restarted_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        restarted_offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys.clone(),
        offer_transport,
        None,
    ));
    let restarted_answer_task = tokio::spawn(run_answer_daemon_with_transport(
        restarted_answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));
    assert_client_round_trip(restarted_offer_port, b"r002", b"r002").await;
    let status = wait_for_status_matching(
        &answer_status,
        "post-restart session status",
        session_count_is(1),
    )
    .await;
    assert_status_schema_is_consistent(&status);

    let answer_to_offer =
        decode_signal_records(&mesh.trace().payloads_for("offer-home"), &offer_codec);
    assert!(
        !answer_to_offer.iter().any(|record| matches!(
            record.message_type,
            MessageType::Offer | MessageType::IceRestartRequest | MessageType::RenegotiateRequest
        )),
        "answer side must not initiate reconnect or fresh-session signaling"
    );
    for attempt in mesh.trace().attempts() {
        assert!(!attempt.payload.starts_with(b"{"));
    }

    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    restarted_offer_task.abort();
    restarted_answer_task.abort();
    let _ = restarted_offer_task.await;
    let _ = restarted_answer_task.await;
    let _ = tokio::fs::remove_file(offer_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn multi_peer_answer_restart_accepts_fresh_offer_side_sessions() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let desktop_codec = SignalCodec::new(&offer_desktop.identity, &offer_desktop_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let home_status = unique_path("offer-home-multi-restart-status.json");
    let desktop_status = unique_path("offer-desktop-multi-restart-status.json");
    let answer_status = unique_path("answer-multi-restart-status.json");
    let home_port = unused_local_port();
    let desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(4).await;

    let home_config = sample_config_for(
        NodeRole::Offer,
        home_status.clone(),
        home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let desktop_config = sample_config_for(
        NodeRole::Offer,
        desktop_status.clone(),
        desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let home_transport = mesh.add_transport("offer-home");
    let desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");
    let home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        home_config.clone(),
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        home_transport,
        None,
    ));
    let desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        desktop_config.clone(),
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys.clone(),
        desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config.clone(),
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    assert_client_round_trip(home_port, b"hr01", b"hr01").await;
    assert_client_round_trip(desktop_port, b"dr01", b"dr01").await;
    wait_for_session_count(&answer_status, 2).await;
    let first_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    let first_home_session = first_records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
        })
        .expect("initial home offer should be recorded")
        .session_id;
    let first_desktop_session = first_records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
        })
        .expect("initial desktop offer should be recorded")
        .session_id;

    answer_task.abort();
    home_task.abort();
    desktop_task.abort();
    let _ = answer_task.await;
    let _ = home_task.await;
    let _ = desktop_task.await;

    let restarted_home_port = unused_local_port();
    let restarted_desktop_port = unused_local_port();
    let restarted_home_config = sample_config_for(
        NodeRole::Offer,
        home_status.clone(),
        restarted_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let restarted_desktop_config = sample_config_for(
        NodeRole::Offer,
        desktop_status.clone(),
        restarted_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let restarted_answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        restarted_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );
    let restarted_home_transport = mesh.add_transport("offer-home");
    let restarted_desktop_transport = mesh.add_transport("offer-desktop");
    let restarted_answer_transport = mesh.add_transport("answer-office");
    let restarted_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        restarted_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        restarted_home_transport,
        None,
    ));
    let restarted_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        restarted_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys.clone(),
        restarted_desktop_transport,
        None,
    ));
    let restarted_answer_task = tokio::spawn(run_answer_daemon_with_transport(
        restarted_answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        restarted_answer_transport,
    ));

    assert_client_round_trip(restarted_home_port, b"hr02", b"hr02").await;
    assert_client_round_trip(restarted_desktop_port, b"dr02", b"dr02").await;
    let status = wait_for_session_count(&answer_status, 2).await;
    assert_status_schema_is_consistent(&status);

    let all_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert!(
        all_records.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
                && record.session_id != first_home_session
        }),
        "home peer should establish a fresh post-restart session"
    );
    assert!(
        all_records.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
                && record.session_id != first_desktop_session
        }),
        "desktop peer should establish a fresh post-restart session"
    );
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-home"),
        &home_codec,
    ));
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-desktop"),
        &desktop_codec,
    ));
    for attempt in mesh.trace().attempts() {
        assert!(!attempt.payload.starts_with(b"{"));
    }

    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 4);

    restarted_home_task.abort();
    restarted_desktop_task.abort();
    restarted_answer_task.abort();
    let _ = restarted_home_task.await;
    let _ = restarted_desktop_task.await;
    let _ = restarted_answer_task.await;
    let _ = tokio::fs::remove_file(home_status).await;
    let _ = tokio::fs::remove_file(desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn target_connect_failure_for_one_peer_does_not_break_another_peer() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-fail-status.json");
    let offer_desktop_status = unique_path("offer-desktop-ok-status.json");
    let answer_status = unique_path("answer-failure-isolation-status.json");
    let bad_offer_port = unused_local_port();
    let good_offer_port = unused_local_port();
    let bad_target_port = unused_local_port();
    let good_target =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("good target listener should bind");
    let good_target_port = good_target.local_addr().expect("good target addr").port();

    let mut bad_offer_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        bad_offer_port,
        bad_target_port,
        "offer-home",
        vec!["offer-home"],
    );
    bad_offer_config.forwards[0].id = "bad".to_owned();
    let mut good_offer_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        good_offer_port,
        good_target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    good_offer_config.forwards[0].id = "good".to_owned();
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        bad_offer_port,
        bad_target_port,
        "answer-office",
        vec!["offer-home"],
    );
    answer_config.forwards[0].id = "bad".to_owned();
    answer_config.forwards.push(ForwardRule {
        id: "good".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: good_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: good_target_port,
            allow_remote_peers: vec!["offer-desktop".parse().expect("desktop peer id")],
        }),
    });

    let mut transports = transport_mesh(&["offer-home", "offer-desktop", "answer-office"]);
    let offer_home_transport = transports.remove("offer-home").expect("offer-home transport");
    let offer_desktop_transport =
        transports.remove("offer-desktop").expect("offer-desktop transport");
    let answer_transport = transports.remove("answer-office").expect("answer transport");

    let good_target_task = tokio::spawn(async move {
        let (mut stream, _) = good_target.accept().await.expect("good target accept");
        let mut request = [0_u8; 4];
        stream.read_exact(&mut request).await.expect("good target read");
        assert_eq!(&request, b"good");
        stream.write_all(b"GOOD").await.expect("good target write");
        stream.shutdown().await.expect("good target shutdown");
    });

    let bad_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        bad_offer_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        offer_home_transport,
        None,
    ));
    let good_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        good_offer_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let mut bad_client = connect_with_retry(bad_offer_port).await;
    bad_client.write_all(b"fail").await.expect("bad client write");
    let mut bad_response = [0_u8; 4];
    let bad_error = timeout(Duration::from_secs(15), bad_client.read_exact(&mut bad_response))
        .await
        .expect("bad client should fail in time")
        .expect_err("bad client should not receive bytes");
    assert_client_connection_abandoned_without_response(&bad_error);

    let mut good_client = connect_with_retry(good_offer_port).await;
    good_client.write_all(b"good").await.expect("good client write");
    let mut good_response = [0_u8; 4];
    timeout(Duration::from_secs(15), good_client.read_exact(&mut good_response))
        .await
        .expect("good client should receive response in time")
        .expect("good client should read response");
    assert_eq!(&good_response, b"GOOD");

    timeout(Duration::from_secs(15), good_target_task)
        .await
        .expect("good target should finish")
        .expect("good target should succeed");

    let status = {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            if let Ok(content) = tokio::fs::read_to_string(&answer_status).await
                && let Ok(status) = serde_json::from_str::<serde_json::Value>(&content)
            {
                let sessions = status["sessions"].as_array().expect("sessions array");
                if sessions.iter().any(|session| session["remote_peer_id"] == "offer-desktop") {
                    break status;
                }
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "answer status did not retain the surviving peer"
            );
            sleep(Duration::from_millis(50)).await;
        }
    };
    assert_eq!(status["current_state"], "serving");

    bad_offer_task.abort();
    good_offer_task.abort();
    answer_task.abort();
    let _ = bad_offer_task.await;
    let _ = good_offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn per_forward_allowlists_are_isolated_across_simultaneous_sessions() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-allowlist-status.json");
    let offer_desktop_status = unique_path("offer-desktop-allowlist-status.json");
    let answer_status = unique_path("answer-allowlist-status.json");
    let home_ssh_port = unused_local_port();
    let home_web_port = unused_local_port();
    let desktop_ssh_port = unused_local_port();
    let desktop_web_port = unused_local_port();

    let ssh_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh target should bind");
    let web_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("web target should bind");
    let ssh_target_port = ssh_target.local_addr().expect("ssh target addr").port();
    let web_target_port = web_target.local_addr().expect("web target addr").port();

    let ssh_target_task = tokio::spawn(async move {
        for expected in [b"ha01", b"ha02"] {
            let (mut stream, _) = ssh_target.accept().await.expect("ssh target accept");
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).await.expect("ssh target read");
            assert_eq!(&request, expected);
            stream.write_all(b"SSH!").await.expect("ssh target write");
            stream.shutdown().await.expect("ssh target shutdown");
        }
    });
    let web_target_task = tokio::spawn(async move {
        for expected in [b"db01", b"db02"] {
            let (mut stream, _) = web_target.accept().await.expect("web target accept");
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).await.expect("web target read");
            assert_eq!(&request, expected);
            stream.write_all(b"WEB!").await.expect("web target write");
            stream.shutdown().await.expect("web target shutdown");
        }
    });

    let mut offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        home_ssh_port,
        ssh_target_port,
        "offer-home",
        vec!["offer-home"],
    );
    add_offer_forward(&mut offer_home_config, "web-ui", home_web_port, web_target_port);
    let mut offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        desktop_ssh_port,
        ssh_target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    add_offer_forward(&mut offer_desktop_config, "web-ui", desktop_web_port, web_target_port);
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        home_ssh_port,
        ssh_target_port,
        "answer-office",
        vec!["offer-home"],
    );
    add_answer_forward(&mut answer_config, "web-ui", web_target_port, "offer-desktop");

    let mut transports = transport_mesh(&["offer-home", "offer-desktop", "answer-office"]);
    let offer_home_transport = transports.remove("offer-home").expect("offer-home transport");
    let offer_desktop_transport =
        transports.remove("offer-desktop").expect("offer-desktop transport");
    let answer_transport = transports.remove("answer-office").expect("answer transport");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
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
        answer_keys,
        answer_transport,
    ));

    assert_client_round_trip(home_ssh_port, b"ha01", b"SSH!").await;
    assert_client_stream_fails(home_web_port, b"deny").await;
    assert_client_round_trip(desktop_web_port, b"db01", b"WEB!").await;
    assert_client_stream_fails(desktop_ssh_port, b"nope").await;
    assert_client_round_trip(home_ssh_port, b"ha02", b"SSH!").await;
    assert_client_round_trip(desktop_web_port, b"db02", b"WEB!").await;

    timeout(Duration::from_secs(15), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target task should succeed");
    timeout(Duration::from_secs(15), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target task should succeed");

    let status = wait_for_session_count(&answer_status, 2).await;
    let sessions = status["sessions"].as_array().expect("sessions array");
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-home"));
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-desktop"));

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}
