use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use std::time::Duration;

use p2p_core::{ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, MessageType, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{run_answer_daemon_with_transport, run_offer_daemon_with_transport_and_test_hook};
use p2p_signaling::{AnswerBody, InnerMessageBuilder, MessageBody, PingBody, SignalCodec};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::{sleep, timeout};

use crate::harness::*;

#[tokio::test]
async fn same_peer_connection_pressure_during_negotiation_does_not_affect_other_peer() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-pressure-status.json");
    let offer_desktop_status = unique_path("offer-desktop-pressure-status.json");
    let answer_status = unique_path("answer-pressure-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_counting_echo_target().await;

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
    let control = mesh.control();
    let answer_transport = mesh.add_transport("answer-office");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));

    assert_client_round_trip(offer_desktop_port, b"pb00", b"pb00").await;
    wait_for_status_matching(&answer_status, "peer B active", has_remote_peer("offer-desktop"))
        .await;

    control.delay_next_delivery("answer-office", "offer-home", 500);
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        offer_home_transport,
        None,
    ));

    let pressured_clients = [
        tokio::spawn(assert_client_round_trip_owned(offer_home_port, *b"pa01", *b"pa01")),
        tokio::spawn(assert_client_round_trip_owned(offer_home_port, *b"pa02", *b"pa02")),
        tokio::spawn(assert_client_round_trip_owned(offer_home_port, *b"pa03", *b"pa03")),
    ];
    assert_client_round_trip(offer_desktop_port, b"pb01", b"pb01").await;
    let mut completed_pressure_clients = 0_usize;
    for client in pressured_clients {
        if timeout(Duration::from_secs(15), client).await.is_ok_and(|result| result.is_ok()) {
            completed_pressure_clients += 1;
        }
    }
    assert!(
        completed_pressure_clients >= 1,
        "at least one same-peer client should complete after pending negotiation"
    );
    assert_client_round_trip(offer_desktop_port, b"pb02", b"pb02").await;
    assert_client_round_trip(offer_home_port, b"pa04", b"pa04").await;
    let status =
        wait_for_status_matching(&answer_status, "both peers active after pressure", |status| {
            has_remote_peer("offer-home")(status) && has_remote_peer("offer-desktop")(status)
        })
        .await;
    assert_status_schema_is_consistent(&status);
    assert!(accepted.load(Ordering::SeqCst) >= 5);

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

#[tokio::test]
async fn status_file_remains_consistent_during_churn_and_session_failure() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-status-churn.json");
    let offer_desktop_status = unique_path("offer-desktop-status-churn.json");
    let answer_status = unique_path("answer-status-churn.json");
    let bad_offer_port = unused_local_port();
    let good_offer_port = unused_local_port();
    let bad_target_port = unused_local_port();
    let (good_target_port, good_target_task, accepted) = spawn_counting_echo_target().await;

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

    let mesh = InMemoryTransportMesh::new();
    let bad_offer_transport = mesh.add_transport("offer-home");
    let good_offer_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let stop_reader = Arc::new(AtomicBool::new(false));
    let parsed_statuses = Arc::new(Mutex::new(Vec::new()));
    let reader_stop = Arc::clone(&stop_reader);
    let reader_statuses = Arc::clone(&parsed_statuses);
    let reader_path = answer_status.clone();
    let status_reader = tokio::spawn(async move {
        while !reader_stop.load(Ordering::SeqCst) {
            match tokio::fs::read_to_string(&reader_path).await {
                Ok(content) => match serde_json::from_str::<serde_json::Value>(&content) {
                    Ok(status) => {
                        assert_status_schema_is_consistent(&status);
                        reader_statuses.lock().expect("status mutex should lock").push(status);
                    }
                    Err(_) if content.is_empty() => {}
                    Err(error) => panic!("visible status file should parse as JSON: {error}"),
                },
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => panic!("status read should not fail unexpectedly: {error}"),
            }
            sleep(Duration::from_millis(20)).await;
        }
    });

    let bad_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        bad_offer_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        bad_offer_transport,
        None,
    ));
    let good_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        good_offer_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        good_offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    for index in 0..3_u8 {
        let payload = [b'g', b'o', b'0' + index, b'd'];
        assert_client_round_trip_owned(good_offer_port, payload, payload).await;
    }
    let mut bad_client = connect_with_retry(bad_offer_port).await;
    bad_client.write_all(b"fail").await.expect("bad client write");
    let mut bad_response = [0_u8; 4];
    let bad_error = timeout(Duration::from_secs(15), bad_client.read_exact(&mut bad_response))
        .await
        .expect("bad client should fail in time")
        .expect_err("bad client should not receive bytes");
    assert_eq!(bad_error.kind(), std::io::ErrorKind::ConnectionReset);
    assert_client_round_trip(good_offer_port, b"live", b"live").await;
    let final_status = wait_for_status_matching(
        &answer_status,
        "surviving session status",
        has_remote_peer("offer-desktop"),
    )
    .await;
    assert_status_schema_is_consistent(&final_status);

    stop_reader.store(true, Ordering::SeqCst);
    timeout(Duration::from_secs(5), status_reader)
        .await
        .expect("status reader should stop")
        .expect("status reader should succeed");
    assert!(
        !parsed_statuses.lock().expect("status mutex should lock").is_empty(),
        "status reader should capture parseable snapshots"
    );
    assert!(accepted.load(Ordering::SeqCst) >= 4);

    good_target_task.abort();
    bad_offer_task.abort();
    good_offer_task.abort();
    answer_task.abort();
    let _ = good_target_task.await;
    let _ = bad_offer_task.await;
    let _ = good_offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn long_lived_multi_peer_multi_forward_stream_churn_keeps_sessions_usable() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-soak-status.json");
    let offer_desktop_status = unique_path("offer-desktop-soak-status.json");
    let answer_status = unique_path("answer-soak-status.json");
    let home_ssh_port = unused_local_port();
    let home_web_port = unused_local_port();
    let desktop_ssh_port = unused_local_port();
    let desktop_web_port = unused_local_port();
    let cycles = 5_usize;
    let (ssh_target_port, ssh_target_task, ssh_accepts) = spawn_echo_target(cycles * 2).await;
    let (web_target_port, web_target_task, web_accepts) = spawn_echo_target(cycles * 2).await;

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
        vec!["offer-home", "offer-desktop"],
    );
    answer_config.forwards.push(ForwardRule {
        id: "web-ui".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: home_web_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: web_target_port,
            allow_remote_peers: vec![
                "offer-home".parse().expect("home peer id"),
                "offer-desktop".parse().expect("desktop peer id"),
            ],
        }),
    });

    let mesh = InMemoryTransportMesh::new();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

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

    for cycle in 0..cycles {
        let home_ssh = [b'h', b's', b'0' + cycle as u8, b'a'];
        let home_web = [b'h', b'w', b'0' + cycle as u8, b'b'];
        let desktop_ssh = [b'd', b's', b'0' + cycle as u8, b'c'];
        let desktop_web = [b'd', b'w', b'0' + cycle as u8, b'd'];
        if cycle == 2 {
            let tasks = [
                tokio::spawn(assert_client_round_trip_owned(home_ssh_port, home_ssh, home_ssh)),
                tokio::spawn(assert_client_round_trip_owned(home_web_port, home_web, home_web)),
                tokio::spawn(assert_client_round_trip_owned(
                    desktop_ssh_port,
                    desktop_ssh,
                    desktop_ssh,
                )),
                tokio::spawn(assert_client_round_trip_owned(
                    desktop_web_port,
                    desktop_web,
                    desktop_web,
                )),
            ];
            for task in tasks {
                timeout(Duration::from_secs(15), task)
                    .await
                    .expect("concurrent stream should finish")
                    .expect("concurrent stream should succeed");
            }
        } else {
            assert_client_round_trip_owned(home_ssh_port, home_ssh, home_ssh).await;
            assert_client_round_trip_owned(home_web_port, home_web, home_web).await;
            assert_client_round_trip_owned(desktop_ssh_port, desktop_ssh, desktop_ssh).await;
            assert_client_round_trip_owned(desktop_web_port, desktop_web, desktop_web).await;
        }
        let status = wait_for_status_matching(&answer_status, "soak sessions active", |status| {
            session_count_is(2)(status)
                && configured_forwards_include("ssh")(status)
                && configured_forwards_include("web-ui")(status)
        })
        .await;
        assert_status_schema_is_consistent(&status);
    }

    timeout(Duration::from_secs(15), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target should succeed");
    timeout(Duration::from_secs(15), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target should succeed");
    assert_eq!(ssh_accepts.load(Ordering::SeqCst), cycles * 2);
    assert_eq!(web_accepts.load(Ordering::SeqCst), cycles * 2);

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

#[tokio::test]
async fn malformed_authenticated_signaling_is_rejected_without_cross_session_damage() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);
    let answer_recipient = offer_home_keys
        .get_by_peer_id(&"answer-office".parse().expect("answer peer id"))
        .expect("answer key should be authorized")
        .clone();

    let offer_home_status = unique_path("offer-home-malformed-status.json");
    let offer_desktop_status = unique_path("offer-desktop-malformed-status.json");
    let answer_status = unique_path("answer-malformed-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(8).await;

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
    let control = mesh.control();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

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

    assert_client_round_trip(offer_home_port, b"mh00", b"mh00").await;
    assert_client_round_trip(offer_desktop_port, b"md00", b"md00").await;
    wait_for_status_matching(&answer_status, "both sessions active", session_count_is(2)).await;

    let records = decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    let home_session_id = records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
        })
        .expect("home offer should be recorded")
        .session_id;
    let desktop_session_id = records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
        })
        .expect("desktop offer should be recorded")
        .session_id;

    let malformed_messages = [
        InnerMessageBuilder::new(
            p2p_core::SessionId::random(),
            "offer-home".parse().expect("home peer id"),
            "answer-office".parse().expect("answer peer id"),
        )
        .build(MessageBody::Ping(PingBody { seq: 1 }))
        .expect("test message construction"),
        InnerMessageBuilder::new(
            desktop_session_id,
            "offer-home".parse().expect("home peer id"),
            "answer-office".parse().expect("answer peer id"),
        )
        .build(MessageBody::Ping(PingBody { seq: 2 }))
        .expect("test message construction"),
        InnerMessageBuilder::new(
            home_session_id,
            "offer-home".parse().expect("home peer id"),
            "answer-office".parse().expect("answer peer id"),
        )
        .build(MessageBody::Answer(AnswerBody { sdp: "not-valid-for-answer-state".to_owned() }))
        .expect("test message construction"),
    ];

    for (index, message) in malformed_messages.iter().enumerate() {
        let (_envelope, payload) = home_codec
            .encode_for_peer(&answer_recipient, message, false)
            .expect("malformed authenticated signal should encode");
        control.inject_payload("answer-office", payload);
        assert_client_round_trip_owned(
            offer_home_port,
            [b'm', b'h', b'1' + index as u8, b'x'],
            [b'm', b'h', b'1' + index as u8, b'x'],
        )
        .await;
        assert_client_round_trip_owned(
            offer_desktop_port,
            [b'm', b'd', b'1' + index as u8, b'y'],
            [b'm', b'd', b'1' + index as u8, b'y'],
        )
        .await;
        let status = wait_for_status_matching(
            &answer_status,
            "sessions survive malformed signal",
            |status| {
                session_count_is(2)(status)
                    && has_remote_peer("offer-home")(status)
                    && has_remote_peer("offer-desktop")(status)
                    && lacks_remote_peer("offer-unknown")(status)
            },
        )
        .await;
        assert_status_schema_is_consistent(&status);
    }

    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 8);

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
