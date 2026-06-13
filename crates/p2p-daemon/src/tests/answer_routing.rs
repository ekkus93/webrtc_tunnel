//! Answer-daemon signaling routing: authenticated sender/session checks and dispatch of known/unknown session messages.

use std::collections::HashMap;
use std::sync::Arc;

use p2p_core::{FailureCode, MsgId, NodeRole, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{
    AckBody, CloseBody, ErrorBody, IceCandidateBody, InnerMessageBuilder, MessageBody, OfferBody,
    ReplayCache, SignalCodec,
};
use tokio::sync::mpsc;

use super::support::*;

#[tokio::test]
async fn answer_daemon_routes_only_authenticated_sender_and_session() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.health.write_status_file = false;
    config.forwards[0].answer.as_mut().expect("answer forward").allow_remote_peers =
        vec!["offer-a".parse().expect("peer a"), "offer-b".parse().expect("peer b")];
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_a = generate_identity("offer-a").expect("offer a identity");
    let offer_b = generate_identity("offer-b").expect("offer b identity");
    let answer_keys = AuthorizedKeys::parse(&format!(
        "{}\n{}",
        offer_a.public_identity.render(),
        offer_b.public_identity.render()
    ))
    .expect("answer keys");
    let offer_b_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer b keys");
    let local_identity = Arc::new(answer.identity);
    let authorized_keys = Arc::new(answer_keys);
    let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
    let offer_b_codec = SignalCodec::new(&offer_b.identity, &offer_b_keys, 120, 300);
    let mut transport = RecordingTransport::default();
    let status = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, _event_rx) = mpsc::channel(4);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let session_a = SessionId::random();
    let session_b = SessionId::random();
    let (handle_a, mut rx_a) = test_answer_handle(
        session_a,
        SessionGeneration(1),
        offer_a.identity.peer_id.clone(),
        DaemonState::TunnelOpen,
    );
    let (handle_b, mut rx_b) = test_answer_handle(
        session_b,
        SessionGeneration(2),
        offer_b.identity.peer_id.clone(),
        DaemonState::TunnelOpen,
    );
    sessions_by_id.insert(session_a, handle_a);
    sessions_by_id.insert(session_b, handle_b);
    session_by_peer.insert(offer_a.identity.peer_id.clone(), session_a);
    session_by_peer.insert(offer_b.identity.peer_id.clone(), session_b);

    let message = InnerMessageBuilder::new(
        session_b,
        offer_b.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "route me".to_owned(),
        fatal: false,
    }));
    let (_envelope, payload) = offer_b_codec
        .encode_for_peer(
            offer_b_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            next_generation: &mut next_generation,
        },
        payload,
    )
    .await;

    assert!(rx_a.try_recv().is_err());
    let routed = rx_b.try_recv().expect("session b should receive authenticated signal");
    assert_eq!(routed.sender.peer_id, offer_b.identity.peer_id);
    assert_eq!(routed.message.session_id, session_b);
}

#[tokio::test]
async fn forged_outer_sender_kid_is_not_routed_to_matching_peer_session() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.health.write_status_file = false;
    config.forwards[0].answer.as_mut().expect("answer forward").allow_remote_peers =
        vec!["offer-a".parse().expect("peer a"), "offer-b".parse().expect("peer b")];
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_a = generate_identity("offer-a").expect("offer a identity");
    let offer_b = generate_identity("offer-b").expect("offer b identity");
    let authorized_keys = Arc::new(
        AuthorizedKeys::parse(&format!(
            "{}\n{}",
            offer_a.public_identity.render(),
            offer_b.public_identity.render()
        ))
        .expect("answer keys"),
    );
    let offer_b_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer b keys");
    let local_identity = Arc::new(answer.identity);
    let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
    let offer_b_codec = SignalCodec::new(&offer_b.identity, &offer_b_keys, 120, 300);
    let mut transport = RecordingTransport::default();
    let status = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, _event_rx) = mpsc::channel(4);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let session_a = SessionId::random();
    let (handle_a, mut rx_a) = test_answer_handle(
        session_a,
        SessionGeneration(1),
        offer_a.identity.peer_id.clone(),
        DaemonState::TunnelOpen,
    );
    sessions_by_id.insert(session_a, handle_a);
    session_by_peer.insert(offer_a.identity.peer_id.clone(), session_a);

    let message = InnerMessageBuilder::new(
        session_a,
        offer_b.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "forged route".to_owned(),
        fatal: false,
    }));
    let (mut envelope, _payload) = offer_b_codec
        .encode_for_peer(
            offer_b_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");
    envelope.sender_kid = p2p_crypto::kid_from_signing_key(&offer_a.public_identity.sign_public);
    let forged_payload = envelope.encode().expect("forged envelope encodes");

    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            next_generation: &mut next_generation,
        },
        forged_payload,
    )
    .await;

    assert!(rx_a.try_recv().is_err());
}

#[tokio::test]
async fn answer_daemon_ignores_unknown_authenticated_non_offer() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.health.write_status_file = false;
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer = generate_identity("offer-home").expect("offer identity");
    let authorized_keys =
        Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let local_identity = Arc::new(answer.identity);
    let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let mut transport = RecordingTransport::default();
    let status = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, _event_rx) = mpsc::channel(4);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "unknown session".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            next_generation: &mut next_generation,
        },
        payload,
    )
    .await;

    assert!(sessions_by_id.is_empty());
    assert!(session_by_peer.is_empty());
    assert!(transport.published.lock().await.is_empty());
}

#[tokio::test]
async fn answer_daemon_does_not_peer_fallback_route_unknown_non_offer() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.health.write_status_file = false;
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer = generate_identity("offer-home").expect("offer identity");
    let authorized_keys =
        Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let local_identity = Arc::new(answer.identity);
    let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let mut transport = RecordingTransport::default();
    let status = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, _event_rx) = mpsc::channel(4);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let active_session = SessionId::random();
    let (handle, mut rx) = test_answer_handle(
        active_session,
        SessionGeneration(1),
        offer.identity.peer_id.clone(),
        DaemonState::TunnelOpen,
    );
    sessions_by_id.insert(active_session, handle);
    session_by_peer.insert(offer.identity.peer_id.clone(), active_session);

    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "unknown session must not fallback-route".to_owned(),
        fatal: false,
    }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            next_generation: &mut next_generation,
        },
        payload,
    )
    .await;

    assert!(rx.try_recv().is_err(), "unknown-session non-offer must not route by peer");
    assert!(
        transport.published.lock().await.is_empty(),
        "unknown-session non-offer must not receive accepted-message ACK"
    );
    assert_eq!(sessions_by_id[&active_session].status.state, DaemonState::TunnelOpen);
}

#[tokio::test]
async fn answer_daemon_ignores_every_unknown_session_non_offer_without_ack() {
    for (name, body) in AnswerRoutingFixture::unknown_session_non_offer_bodies() {
        let mut fixture = AnswerRoutingFixture::new();
        let original_session = fixture.active_session;
        let payload = fixture.encode_from_offer(SessionId::random(), body);

        fixture.handle_payload(payload).await;

        assert!(fixture.receiver.try_recv().is_err(), "{name} must not fallback-route by peer");
        assert_eq!(
            fixture.published_len().await,
            0,
            "{name} must not receive accepted-message ACK"
        );
        assert_eq!(fixture.sessions_by_id.len(), 1, "{name} must not create a session");
        assert!(
            fixture.sessions_by_id.contains_key(&original_session),
            "{name} must leave the active session map unchanged"
        );
        assert_eq!(
            fixture.sessions_by_id[&original_session].status.state,
            DaemonState::TunnelOpen,
            "{name} must leave active session status unchanged"
        );
        assert_eq!(
            fixture.session_by_peer.get(&fixture.offer_identity.identity.peer_id),
            Some(&original_session),
            "{name} must leave the peer index unchanged"
        );
    }
}

#[tokio::test]
async fn answer_daemon_routes_representative_known_session_messages() {
    let cases = [
        ("ack", MessageBody::Ack(AckBody { ack_msg_id: MsgId::new([9_u8; 16]).into_bytes() })),
        (
            "ice_candidate",
            MessageBody::IceCandidate(IceCandidateBody {
                candidate: Some("candidate:1 1 UDP 1 127.0.0.1 9 typ host".to_owned()),
                sdp_mid: Some("0".to_owned()),
                sdp_mline_index: Some(0),
            }),
        ),
        (
            "close",
            MessageBody::Close(CloseBody {
                reason_code: "done".to_owned(),
                message: Some("test close".to_owned()),
            }),
        ),
    ];

    for (name, body) in cases {
        let mut fixture = AnswerRoutingFixture::new();
        let payload = fixture.encode_from_offer(fixture.active_session, body);

        fixture.handle_payload(payload).await;

        let routed = fixture.receiver.try_recv().expect("known-session message should route");
        assert_eq!(routed.message.session_id, fixture.active_session, "{name} routed session");
        assert!(
            fixture.sessions_by_id.contains_key(&fixture.active_session),
            "{name} must leave the session registered"
        );
    }
}

#[tokio::test]
async fn answer_daemon_unknown_same_peer_offer_enters_session_policy() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.health.write_status_file = false;
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer = generate_identity("offer-home").expect("offer identity");
    let authorized_keys =
        Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let local_identity = Arc::new(answer.identity);
    let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let mut transport = RecordingTransport::default();
    let status = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, _event_rx) = mpsc::channel(4);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let active_session = SessionId::random();
    let (handle, mut rx) = test_answer_handle(
        active_session,
        SessionGeneration(1),
        offer.identity.peer_id.clone(),
        DaemonState::TunnelOpen,
    );
    sessions_by_id.insert(active_session, handle);
    session_by_peer.insert(offer.identity.peer_id.clone(), active_session);

    let rejected_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        rejected_session,
        offer.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "unrelated second offer".to_owned() }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            next_generation: &mut next_generation,
        },
        payload,
    )
    .await;

    let routed = rx.try_recv().expect("same-peer offer should enter session policy handling");
    assert_eq!(routed.message.session_id, rejected_session);
    assert!(matches!(routed.message.body, MessageBody::Offer(_)));
    assert!(transport.published.lock().await.is_empty());
    assert_eq!(session_by_peer.get(&offer.identity.peer_id), Some(&active_session));
}
