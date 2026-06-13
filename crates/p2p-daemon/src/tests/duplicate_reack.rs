//! Duplicate active-session re-ack behavior across offer and answer sessions.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use p2p_core::{ACK_RETRY_TIMEOUT_SECS, FailureCode, NodeRole, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{
    AckBody, ErrorBody, InnerMessageBuilder, MessageBody, PingBody, ReplayCache, ReplayStatus,
    SignalCodec, SignalingError,
};
use tokio::sync::mpsc;
use tokio::time::timeout;

use super::support::*;

#[test]
fn duplicate_active_session_message_builds_re_ack_for_original_msg_id() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let session_id = SessionId::random();
    let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());
    let message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "duplicate retry".to_owned(),
        fatal: true,
    }));
    let (envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("message encodes");

    let (_duplicate_msg_id, ack) = duplicate_active_session_ack_message(
        &answer_codec,
        session_id,
        answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
        &offer.identity.peer_id,
        &payload,
        &duplicate_error,
    )
    .expect("duplicate active-session message should be re-acknowledged");

    assert_eq!(ack.session_id, session_id);
    assert_eq!(ack.sender_peer_id, answer.identity.peer_id);
    assert_eq!(ack.recipient_peer_id, offer.identity.peer_id);
    assert!(matches!(
        ack.body,
        MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == envelope.msg_id.into_bytes()
    ));
}

#[test]
fn duplicate_active_session_message_ack_policy_matches_message_type() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let session_id = SessionId::random();
    let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());
    let answer_remote = answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key");
    let offer_remote = offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key");

    for (name, body) in AnswerRoutingFixture::ack_required_duplicate_bodies() {
        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(body);
        let (envelope, payload) =
            offer_codec.encode_for_peer(offer_remote, &message, false).expect("message encodes");

        let (_duplicate_msg_id, ack) = duplicate_active_session_ack_message(
            &answer_codec,
            session_id,
            answer_remote,
            &offer.identity.peer_id,
            &payload,
            &duplicate_error,
        )
        .unwrap_or_else(|| panic!("{name} duplicate should be re-acknowledged"));

        assert!(matches!(
            ack.body,
            MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == envelope.msg_id.into_bytes()
        ));
    }

    for (name, body) in AnswerRoutingFixture::non_ack_required_duplicate_bodies() {
        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(body);
        let (_envelope, payload) =
            offer_codec.encode_for_peer(offer_remote, &message, false).expect("message encodes");

        let ack = duplicate_active_session_ack_message(
            &answer_codec,
            session_id,
            answer_remote,
            &offer.identity.peer_id,
            &payload,
            &duplicate_error,
        );

        assert!(ack.is_none(), "{name} duplicate must not be re-acknowledged");
    }
}

#[tokio::test]
async fn answer_session_reacks_duplicate_same_session_ack_required_messages() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
    config.health.write_status_file = false;
    let config = Arc::new(config);

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_identity = Arc::new(answer.identity);
    let answer_keys = Arc::new(answer_keys);
    let answer_codec = SignalCodec::new(&answer_identity, &answer_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let transport = RecordingTransport::default();
    let transport_for_task = transport.clone();
    let config_for_task = Arc::clone(&config);
    let answer_identity_for_task = Arc::clone(&answer_identity);
    let answer_keys_for_task = Arc::clone(&answer_keys);
    let (event_tx, mut event_rx) = mpsc::channel(8);
    let event_task = tokio::spawn(async move {
        let status = StatusWriter::new(&config_for_task);
        let mut runtime = connected_runtime();
        let mut ctx =
            RuntimeContext { config: &config_for_task, status: &status, runtime: &mut runtime };
        let codec = SignalCodec::new(&answer_identity_for_task, &answer_keys_for_task, 120, 300);
        let mut transport = transport_for_task;
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        while let Some(event) = event_rx.recv().await {
            handle_answer_session_event(
                &mut ctx,
                &codec,
                &mut transport,
                &mut sessions_by_id,
                &mut session_by_peer,
                event,
            )
            .await;
        }
    });

    let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
    let session_id = SessionId::random();
    let mut session = ActiveSession::new(
        session_id,
        answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
        peer,
        config.security.replay_cache_size,
    );
    session.state = DaemonState::TunnelOpen;
    session.bridge_state = BridgeSessionState::Active;
    let original_state = session.state;
    let original_bridge_state = session.bridge_state;
    let mut replay_cache = ReplayCache::new(64);

    for (name, body) in AnswerRoutingFixture::ack_required_duplicate_bodies() {
        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer_identity.peer_id.clone(),
        )
        .build(body);
        let (envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("message encodes");
        let mut decoded = answer_codec
            .decode_with_replay_status(&payload, &mut replay_cache, None)
            .expect("message decodes");
        decoded.replay_status = ReplayStatus::DuplicateSameSession;

        process_answer_session_signal(
            &config,
            &answer_codec,
            &event_tx,
            SessionGeneration(1),
            &mut session,
            decoded,
        )
        .await
        .unwrap_or_else(|_| panic!("{name} duplicate should be handled"));

        let published = transport.published.lock().await.clone();
        let (_peer, ack_payload) = published.last().expect("duplicate should publish ACK");
        let mut offer_replay = ReplayCache::new(64);
        let (_ack_envelope, ack_message, _sender) = offer_codec
            .decode(ack_payload, &mut offer_replay, None)
            .expect("offer should decode ACK");
        assert!(matches!(
            ack_message.body,
            MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == envelope.msg_id.into_bytes()
        ));
        assert_eq!(session.state, original_state, "{name} duplicate must not mutate state");
        assert_eq!(
            session.bridge_state, original_bridge_state,
            "{name} duplicate must not mutate bridge state"
        );
    }

    event_task.abort();
    let _ = event_task.await;
    session.peer.close().await.expect("answer peer should close");
}

#[tokio::test]
async fn answer_session_ignores_duplicate_different_session_before_ack() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
    config.health.write_status_file = false;

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
    let session_id = SessionId::random();
    let mut session = ActiveSession::new(
        session_id,
        answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
        peer,
        config.security.replay_cache_size,
    );
    session.state = DaemonState::TunnelOpen;
    session.bridge_state = BridgeSessionState::Active;
    let original_state = session.state;
    let original_bridge_state = session.bridge_state;
    let (event_tx, mut event_rx) = mpsc::channel(1);

    let message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "different-session duplicate".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("message encodes");
    let mut replay_cache = ReplayCache::new(64);
    let mut decoded = answer_codec
        .decode_with_replay_status(&payload, &mut replay_cache, None)
        .expect("message decodes");
    decoded.replay_status = ReplayStatus::DuplicateDifferentSession;

    process_answer_session_signal(
        &config,
        &answer_codec,
        &event_tx,
        SessionGeneration(1),
        &mut session,
        decoded,
    )
    .await
    .expect("different-session duplicate should be ignored");

    assert!(event_rx.try_recv().is_err(), "different-session duplicate must not ACK");
    assert_eq!(session.state, original_state);
    assert_eq!(session.bridge_state, original_bridge_state);
    session.peer.close().await.expect("answer peer should close");
}

#[tokio::test]
async fn answer_session_ping_pong_do_not_emit_normal_acks() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
    config.health.write_status_file = false;

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
    let session_id = SessionId::random();
    let mut session = ActiveSession::new(
        session_id,
        answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
        peer,
        config.security.replay_cache_size,
    );
    session.state = DaemonState::TunnelOpen;
    let original_state = session.state;
    let (event_tx, mut event_rx) = mpsc::channel(1);
    let mut replay_cache = ReplayCache::new(64);

    for body in [MessageBody::Ping(PingBody { seq: 1 }), MessageBody::Pong(PingBody { seq: 2 })] {
        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(body);
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("message encodes");
        let decoded = answer_codec
            .decode_with_replay_status(&payload, &mut replay_cache, None)
            .expect("message decodes");
        assert!(
            !decoded.message.message_type.requires_ack(),
            "ping/pong must remain non-ACK-required"
        );

        timeout(
            Duration::from_secs(5),
            process_answer_session_signal(
                &config,
                &answer_codec,
                &event_tx,
                SessionGeneration(1),
                &mut session,
                decoded,
            ),
        )
        .await
        .expect("ping/pong handling should finish")
        .expect("ping/pong should be ignored without ACK");
        assert!(
            matches!(event_rx.try_recv(), Ok(AnswerSessionEvent::Status(_))),
            "ping/pong should only emit status updates"
        );
    }

    assert!(event_rx.try_recv().is_err(), "ping/pong must not publish normal ACKs");
    assert_eq!(session.state, original_state);
    assert!(session.signaling.ack_tracker.expired().is_empty());
}

#[tokio::test]
async fn active_session_retry_and_duplicate_reack_flow_retires_pending_ack() {
    let mut config = sample_config();
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let remote = offer_keys
        .get_by_peer_id(&answer.identity.peer_id)
        .cloned()
        .expect("answer authorized key");
    let peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer should build");
    let session_id = SessionId::random();
    let mut session =
        ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);

    let outbound_message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "retry me".to_owned(),
        fatal: true,
    }));
    let (outbound_envelope, outbound_payload) = offer_codec
        .encode_for_peer(&remote, &outbound_message, false)
        .expect("outbound message encodes");
    session.signaling.ack_tracker.register(
        outbound_envelope.msg_id,
        outbound_message.message_type,
        outbound_payload.clone(),
        0,
    );

    let retries = session.signaling.ack_tracker.retry_due(ACK_RETRY_TIMEOUT_SECS * 1_000);
    assert_eq!(retries.len(), 1, "pending outbound message should be retried once due");
    assert_eq!(retries[0].0, outbound_envelope.msg_id);
    assert_eq!(retries[0].1, outbound_payload);

    let duplicate_inbound = InnerMessageBuilder::new(
        session_id,
        answer.identity.peer_id.clone(),
        offer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "duplicate inbound".to_owned(),
        fatal: true,
    }));
    let (duplicate_envelope, duplicate_payload) = answer_codec
        .encode_for_peer(
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &duplicate_inbound,
            false,
        )
        .expect("duplicate inbound encodes");
    let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());

    let (_duplicate_msg_id, reack) = duplicate_active_session_ack_message(
        &offer_codec,
        session_id,
        &session.remote_authorized,
        &session.remote_peer_id,
        &duplicate_payload,
        &duplicate_error,
    )
    .expect("duplicate inbound payload should be re-acknowledged");

    assert!(matches!(
        reack.body,
        MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == duplicate_envelope.msg_id.into_bytes()
    ));

    let inbound_ack = answer_codec.build_ack(
        offer.identity.peer_id.clone(),
        session_id,
        outbound_envelope.msg_id,
    );
    handle_offer_session_message(&inbound_ack, &mut session)
        .await
        .expect("inbound ack should retire pending outbound message");

    assert!(
        session.signaling.ack_tracker.retry_due(u64::MAX).is_empty(),
        "inbound ack should clear the pending outbound retry"
    );
    assert!(
        session.signaling.ack_tracker.expired().is_empty(),
        "retired pending message should not linger as expired"
    );

    session.peer.close().await.expect("offer peer should close");
}

#[tokio::test]
async fn duplicate_active_session_message_is_reacked_only_once_per_msg_id() {
    let mut config = sample_config();
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let remote = offer_keys
        .get_by_peer_id(&answer.identity.peer_id)
        .cloned()
        .expect("answer authorized key");
    let peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer should build");
    let session_id = SessionId::random();
    let mut session =
        ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);
    let (path, writer) = status_writer_for_test(&mut config, "offer-duplicate-reack-once");
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    let mut transport = RecordingTransport::default();

    let duplicate_inbound = InnerMessageBuilder::new(
        session_id,
        answer.identity.peer_id.clone(),
        offer.identity.peer_id.clone(),
    )
    .build(MessageBody::IceCandidate(p2p_signaling::IceCandidateBody {
        candidate: Some("candidate:1 1 udp 2130706431 127.0.0.1 3478 typ host".to_owned()),
        sdp_mid: Some("0".to_owned()),
        sdp_mline_index: Some(0),
    }));
    let (_duplicate_envelope, duplicate_payload) = answer_codec
        .encode_for_peer(
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &duplicate_inbound,
            false,
        )
        .expect("duplicate inbound encodes");
    let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());

    let first = maybe_ack_duplicate_active_session_message(
        &mut ctx,
        &offer_codec,
        &mut transport,
        &mut session,
        &duplicate_payload,
        &duplicate_error,
    )
    .await
    .expect("first duplicate should be re-acknowledged");
    assert!(first);

    let second = maybe_ack_duplicate_active_session_message(
        &mut ctx,
        &offer_codec,
        &mut transport,
        &mut session,
        &duplicate_payload,
        &duplicate_error,
    )
    .await
    .expect("second duplicate should be suppressed");
    assert!(second);

    let published = transport.published.lock().await.clone();
    assert_eq!(
        published.len(),
        1,
        "only one re-ack should be published for the same duplicate msg_id"
    );

    let _ = tokio::fs::remove_file(&path).await;
    session.peer.close().await.expect("offer peer should close");
}
