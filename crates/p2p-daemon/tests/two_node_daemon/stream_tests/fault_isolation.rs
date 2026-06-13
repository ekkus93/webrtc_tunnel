use std::sync::atomic::Ordering;
use std::time::Duration;

use p2p_core::{MessageType, NodeRole};
use p2p_crypto::generate_identity;
use p2p_daemon::{run_answer_daemon_with_transport, run_offer_daemon_with_transport_and_test_hook};
use p2p_signaling::{CloseBody, InnerMessageBuilder, MessageBody, SignalCodec};
use tokio::time::timeout;

use crate::harness::*;

#[tokio::test]
async fn delayed_and_duplicate_delivery_do_not_cross_mutate_active_sessions() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-delay-status.json");
    let offer_desktop_status = unique_path("offer-desktop-dup-status.json");
    let answer_status = unique_path("answer-delay-dup-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;

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
    control.delay_next_delivery("answer-office", "offer-home", 250);
    control.drop_next_delivery("answer-office", "offer-home", 1);
    control.duplicate_next_delivery("answer-office", "offer-desktop", 1);
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

    let home_client = tokio::spawn(assert_client_round_trip(offer_home_port, b"h001", b"h001"));
    let desktop_client =
        tokio::spawn(assert_client_round_trip(offer_desktop_port, b"d001", b"d001"));
    timeout(Duration::from_secs(20), home_client)
        .await
        .expect("home client should finish")
        .expect("home client should succeed");
    timeout(Duration::from_secs(20), desktop_client)
        .await
        .expect("desktop client should finish")
        .expect("desktop client should succeed");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    let status = wait_for_status_matching(&answer_status, "two active sessions", |status| {
        session_count_is(2)(status)
            && has_remote_peer("offer-home")(status)
            && has_remote_peer("offer-desktop")(status)
    })
    .await;
    let sessions = status["sessions"].as_array().expect("sessions array");
    assert_eq!(sessions.len(), 2);

    for attempt in mesh.trace().attempts() {
        assert!(
            !attempt.payload.starts_with(b"{"),
            "signaling payloads must remain encrypted binary envelopes"
        );
    }

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
async fn route_scoped_drop_duplicate_stress_is_peer_isolated() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let offer_home_status = unique_path("offer-home-retransmit-status.json");
    let offer_desktop_status = unique_path("offer-desktop-retransmit-status.json");
    let answer_status = unique_path("answer-retransmit-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;

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
    control.drop_next_delivery("offer-home", "answer-office", 1);
    control.duplicate_next_delivery("answer-office", "offer-desktop", 1);
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

    let home_client = tokio::spawn(assert_client_round_trip(offer_home_port, b"rt01", b"rt01"));
    let desktop_client =
        tokio::spawn(assert_client_round_trip(offer_desktop_port, b"rt02", b"rt02"));
    timeout(Duration::from_secs(20), home_client)
        .await
        .expect("home client should finish")
        .expect("home client should succeed");
    timeout(Duration::from_secs(20), desktop_client)
        .await
        .expect("desktop client should finish")
        .expect("desktop client should succeed");
    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    let status =
        wait_for_status_matching(&answer_status, "two active sessions after retry", |status| {
            session_count_is(2)(status)
                && has_remote_peer("offer-home")(status)
                && has_remote_peer("offer-desktop")(status)
        })
        .await;
    assert_status_schema_is_consistent(&status);
    let offer_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert!(
        count_records_from(&offer_records, "offer-home", MessageType::Offer) >= 1,
        "home route should publish at least one offer"
    );
    assert_eq!(
        count_records_from(&offer_records, "offer-desktop", MessageType::Offer),
        1,
        "desktop duplicate handling must not create another offer-side session"
    );

    let attempts = mesh.trace().attempts();
    let _dropped_home_payload = attempts
        .iter()
        .find(|attempt| {
            attempt.from_peer_id == "offer-home"
                && attempt.to_peer_id == "answer-office"
                && !attempt.delivered
        })
        .expect("home route should record a dropped offer-side publish");
    assert!(
        attempts.iter().any(|attempt| {
            attempt.from_peer_id == "offer-home"
                && attempt.to_peer_id == "answer-office"
                && attempt.delivered
        }),
        "home route should recover with a later delivered publish"
    );
    assert!(
        attempts.iter().any(|attempt| {
            attempt.from_peer_id == "answer-office"
                && attempt.to_peer_id == "offer-desktop"
                && attempt.delivered
        }),
        "desktop route should keep delivering while home route retries"
    );

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
async fn route_scoped_publish_failure_does_not_break_other_active_peer() {
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

    let offer_home_status = unique_path("offer-home-publish-failure-status.json");
    let offer_desktop_status = unique_path("offer-desktop-publish-ok-status.json");
    let answer_status = unique_path("answer-publish-failure-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(3).await;

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

    assert_client_round_trip(offer_home_port, b"h100", b"h100").await;
    assert_client_round_trip(offer_desktop_port, b"d100", b"d100").await;
    wait_for_status_matching(
        &answer_status,
        "two active sessions before failure",
        session_count_is(2),
    )
    .await;

    let home_session_id =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec)
            .into_iter()
            .find(|record| {
                record.sender_peer_id.as_str() == "offer-home"
                    && record.message_type == MessageType::Offer
            })
            .expect("home offer should be recorded")
            .session_id;
    let close = InnerMessageBuilder::new(
        home_session_id,
        "offer-home".parse().expect("home peer id"),
        "answer-office".parse().expect("answer peer id"),
    )
    .build(MessageBody::Close(CloseBody {
        reason_code: "test_route_scoped_failure".to_owned(),
        message: None,
    }));
    let (_envelope, payload) =
        home_codec.encode_for_peer(&answer_recipient, &close, false).expect("close should encode");

    control.fail_next_publish("answer-office", "offer-home", 1);
    control.inject_payload("answer-office", payload);
    wait_for_failed_publish_attempt(&mesh.trace(), "answer-office", "offer-home").await;
    assert!(
        mesh.trace().attempts().iter().any(|attempt| {
            attempt.from_peer_id == "answer-office"
                && attempt.to_peer_id == "offer-home"
                && !attempt.delivered
        }),
        "failed publish attempt should be route-scoped and recorded"
    );

    control.inject_payload("answer-office", vec![0_u8]);
    let status =
        wait_for_status_matching(&answer_status, "transport recovered", mqtt_connected_is(true))
            .await;
    assert_status_schema_is_consistent(&status);
    assert_client_round_trip(offer_desktop_port, b"d101", b"d101").await;

    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 3);

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
