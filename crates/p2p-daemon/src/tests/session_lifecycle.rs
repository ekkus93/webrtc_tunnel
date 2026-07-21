//! Active-session lifecycle: data-channel handoff, duplicate-then-valid handling, remote-driven reconnect, and pending-session replacement.

use std::time::Duration;

use p2p_core::{NodeRole, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{InnerMessageBuilder, MessageBody, OfferBody, ReplayCache, SignalCodec};
use p2p_tunnel::OfferClient;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::time::timeout;

use super::support::*;

#[tokio::test]
async fn answer_incoming_data_channel_handoff_starts_bridge_without_open_event_branch() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let remote =
        answer_keys.get_by_peer_id(&offer.identity.peer_id).cloned().expect("offer authorized key");

    let (offer_peer, answer_peer, offer_channel, answer_channel) =
        connected_channels(&config.webrtc).await;
    let mut session = ActiveSession::new(
        SessionId::random(),
        remote,
        answer_peer,
        config.security.replay_cache_size,
    );

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    super::first_answer_forward_mut(&mut config).expect("answer forward").target_port =
        target_listener.local_addr().expect("target local addr").port();

    handle_answer_incoming_data_channel(&mut session, Some(Ok(answer_channel)), &config)
        .expect("incoming data channel should hand off to answer bridge");

    assert!(session.data_channel.is_some(), "answer session should retain the incoming channel");
    assert!(session.bridge_handle.is_some(), "answer session should start the bridge immediately");
    assert_eq!(session.bridge_state, BridgeSessionState::Active);

    let target_task = tokio::spawn(async move {
        let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
        let mut received = [0_u8; 4];
        target_stream.read_exact(&mut received).await.expect("target read");
        assert_eq!(&received, b"ping");
        target_stream.write_all(b"pong").await.expect("target write");
        target_stream.shutdown().await.expect("target shutdown");
    });

    let local_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
    let local_addr = local_listener.local_addr().expect("local addr");
    let client_task = tokio::spawn(async move {
        let mut client = TcpStream::connect(local_addr).await.expect("client connect");
        client.write_all(b"ping").await.expect("client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("client read");
        assert_eq!(&response, b"pong");
        client.shutdown().await.expect("client shutdown");
    });
    let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

    let offer_task = tokio::spawn(async move {
        let (tx, mut rx) = mpsc::channel(1);
        drop(tx);
        p2p_tunnel::run_multiplex_offer(
            offer_channel,
            &config.tunnel,
            OfferClient::new("ssh", offer_stream),
            &mut rx,
        )
        .await
    });

    timeout(Duration::from_secs(10), client_task)
        .await
        .expect("client task should finish in time")
        .expect("client task should succeed");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish in time")
        .expect("target task should succeed");
    timeout(Duration::from_secs(10), offer_task)
        .await
        .expect("offer bridge should finish in time")
        .expect("offer bridge join should succeed")
        .expect("offer bridge should succeed");
    offer_peer.close().await.expect("offer peer should close");
    session.peer.close().await.expect("answer peer should close");
    session.bridge_handle.take().expect("answer bridge handle should exist").abort();
}

#[tokio::test]
async fn active_offer_session_ignores_duplicate_signal_and_processes_later_valid_ack() {
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
    let (path, writer) = status_writer_for_test(&mut config, "offer-duplicate-survival");
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    let mut transport = RecordingTransport::default();

    let outbound_message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "pending-offer".to_owned() }))
    .expect("test message construction");
    let (outbound_envelope, outbound_payload) =
        offer_codec.encode_for_peer(&remote, &outbound_message, false).expect("offer encodes");
    session.signaling.ack_tracker.register(
        outbound_envelope.msg_id,
        outbound_message.message_type,
        outbound_payload,
        0,
    );

    let duplicate_ack = answer_codec
        .build_ack(offer.identity.peer_id.clone(), session_id, p2p_core::MsgId::random())
        .expect("test message construction");
    let (_duplicate_envelope, duplicate_payload) = answer_codec
        .encode_for_peer(
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &duplicate_ack,
            false,
        )
        .expect("duplicate ack encodes");

    let first = process_offer_session_payload(
        &mut ctx,
        &offer_codec,
        &mut transport,
        &remote,
        &mut session,
        &duplicate_payload,
    )
    .await
    .expect("first ack should process cleanly");
    assert_eq!(first, OfferSessionPayloadOutcome::Handled);

    let duplicate = process_offer_session_payload(
        &mut ctx,
        &offer_codec,
        &mut transport,
        &remote,
        &mut session,
        &duplicate_payload,
    )
    .await
    .expect("duplicate ack should be ignored rather than abort the session");
    assert_eq!(duplicate, OfferSessionPayloadOutcome::Ignored);

    let valid_ack = answer_codec
        .build_ack(offer.identity.peer_id.clone(), session_id, outbound_envelope.msg_id)
        .expect("test message construction");
    let (_valid_envelope, valid_payload) = answer_codec
        .encode_for_peer(
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &valid_ack,
            false,
        )
        .expect("valid ack encodes");
    let processed = process_offer_session_payload(
        &mut ctx,
        &offer_codec,
        &mut transport,
        &remote,
        &mut session,
        &valid_payload,
    )
    .await
    .expect("later valid ack should still be processed");
    assert_eq!(processed, OfferSessionPayloadOutcome::Handled);
    assert!(
        session.signaling.ack_tracker.retry_due(u64::MAX).is_empty(),
        "later valid ack should retire the pending outbound offer"
    );
    assert!(session.signaling.ack_tracker.expired().is_empty());

    let _ = tokio::fs::remove_file(&path).await;
    session.peer.close().await.expect("offer peer should close");
}

#[tokio::test]
async fn answer_session_does_not_initiate_reconnect_from_remote_requests() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let remote =
        answer_keys.get_by_peer_id(&offer.identity.peer_id).cloned().expect("offer authorized key");
    let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
    let session_id = SessionId::random();
    let mut session =
        ActiveSession::new(session_id, remote, peer, config.security.replay_cache_size);
    let original_state = session.state;

    let ice_restart_request = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::IceRestartRequest)
    .expect("test message construction");
    handle_answer_session_message(&ice_restart_request, &mut session)
        .await
        .expect("answer session should ignore remote ice restart request");

    let renegotiate_request = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::RenegotiateRequest(p2p_signaling::RenegotiateRequestBody {
        reason: "offer-side recovery only".to_owned(),
    }))
    .expect("test message construction");
    handle_answer_session_message(&renegotiate_request, &mut session)
        .await
        .expect("answer session should ignore remote renegotiate request");

    assert_eq!(session.session_id, session_id);
    assert_eq!(session.state, original_state);
    assert!(session.data_channel.is_none(), "answer session should not create a data channel");
    assert!(session.bridge_handle.is_none(), "answer session should not start a new bridge task");
    assert_eq!(session.bridge_state, BridgeSessionState::Pending);

    session.peer.close().await.expect("answer peer should close");
}

#[tokio::test]
async fn pending_answer_session_is_replaced_by_same_peer_offer() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
    super::first_answer_forward_mut(&mut config).expect("answer forward").allow_remote_peers =
        vec!["offer-home".parse().expect("offer peer id")];

    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);

    let remote =
        answer_keys.get_by_peer_id(&offer.identity.peer_id).cloned().expect("offer authorized key");
    let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
    let original_session_id = SessionId::random();
    let mut session =
        ActiveSession::new(original_session_id, remote, peer, config.security.replay_cache_size);

    let (status_path, writer) = status_writer_for_test(&mut config, "pending-replacement");
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    let mut transport = RecordingTransport::default();

    let replacement_offer_peer =
        WebRtcPeer::new(&config.webrtc).await.expect("replacement offer peer should build");
    let _replacement_channel = replacement_offer_peer
        .create_data_channel()
        .await
        .expect("replacement offer data channel should build");
    let replacement_session_id = SessionId::random();
    let replacement_offer_sdp =
        replacement_offer_peer.create_offer().await.expect("replacement offer should build SDP");
    let replacement_offer = InnerMessageBuilder::new(
        replacement_session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: replacement_offer_sdp }))
    .expect("test message construction");
    let (_envelope, replacement_payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &replacement_offer,
            false,
        )
        .expect("replacement offer encodes");

    let replaced = maybe_replace_pending_answer_session(
        &config,
        &answer_codec,
        &mut transport,
        &mut ctx,
        &mut session,
        &replacement_payload,
    )
    .await
    .expect("pending answer session should accept replacement offer");

    assert!(replaced);
    assert_eq!(session.session_id, replacement_session_id);
    assert_eq!(session.remote_peer_id, offer.identity.peer_id);
    assert_eq!(session.state, DaemonState::ConnectingDataChannel);
    assert_eq!(session.bridge_state, BridgeSessionState::Pending);
    assert!(session.data_channel.is_none());
    assert!(session.bridge_handle.is_none());

    let published = transport.published.lock().await.clone();
    assert_eq!(published.len(), 2, "replacement flow should publish an ack and a fresh answer");
    assert!(published.iter().all(|(peer_id, _)| *peer_id == offer.identity.peer_id));

    let mut replay_cache = ReplayCache::new(config.security.replay_cache_size);
    let decoded_types = published
        .iter()
        .map(|(_peer_id, payload)| {
            let (_envelope, message, _sender) = offer_codec
                .decode(payload, &mut replay_cache, None)
                .expect("published replacement payload should decode");
            message.message_type
        })
        .collect::<Vec<_>>();
    assert_eq!(decoded_types, vec![p2p_core::MessageType::Ack, p2p_core::MessageType::Answer]);

    let status = read_status_file(&status_path).await;
    assert_eq!(status["current_state"], "connecting_data_channel");
    assert_eq!(status["active_session_id"], replacement_session_id.to_string());

    replacement_offer_peer.close().await.expect("replacement offer peer should close");
    session.peer.close().await.expect("answer session peer should close");
    let _ = tokio::fs::remove_file(&status_path).await;
}
