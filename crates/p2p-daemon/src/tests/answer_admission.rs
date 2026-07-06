//! Answer-daemon session admission: admitting new offers, rejecting owner mismatches, cross-session isolation, and busy replies.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use p2p_core::{FailureCode, NodeRole, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{
    ErrorBody, InnerMessageBuilder, MessageBody, OfferBody, ReplayCache, SignalCodec,
};
use tokio::sync::mpsc;
use tokio::time::{sleep, timeout};

use super::support::*;

#[tokio::test]
async fn answer_daemon_admits_unknown_authenticated_offer() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
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
    let (event_tx, _event_rx) = mpsc::channel(8);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let mut session_completions: AnswerSessionCompletions =
        futures_util::stream::FuturesUnordered::new();
    let offer_peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer");
    let _data_channel = offer_peer.create_data_channel().await.expect("data channel");
    let offer_sdp = offer_peer.create_offer().await.expect("offer sdp");
    let session_id = SessionId::random();
    let message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: offer_sdp }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    let shutdown = ShutdownToken::new();
    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
            shutdown: &shutdown,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            session_completions: &mut session_completions,
            next_generation: &mut next_generation,
            session_panic_trigger: &mut None,
        },
        payload,
    )
    .await;

    assert!(sessions_by_id.contains_key(&session_id));
    assert_eq!(session_by_peer.get(&offer.identity.peer_id), Some(&session_id));
    assert!(
        transport.published.lock().await.len() >= 2,
        "offer admission should publish ack and answer"
    );
    // No explicit task cleanup needed: the session task is now tracked only via
    // `session_completions`, and this test's `#[tokio::test]` runtime aborts every
    // task it spawned (including the one behind that completion future) on drop.
}

#[tokio::test]
async fn answer_daemon_rejects_sender_session_owner_mismatch() {
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
    let mut session_completions: AnswerSessionCompletions =
        futures_util::stream::FuturesUnordered::new();
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
        message: "wrong owner".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = offer_b_codec
        .encode_for_peer(
            offer_b_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    let shutdown = ShutdownToken::new();
    handle_answer_daemon_payload(
        &AnswerDeps {
            config: &config,
            local_identity: &local_identity,
            authorized_keys: &authorized_keys,
            event_tx: &event_tx,
            shutdown: &shutdown,
        },
        &codec,
        &mut transport,
        &mut ctx,
        &mut AnswerSessionRegistry {
            replay_cache: &mut replay_cache,
            sessions_by_id: &mut sessions_by_id,
            session_by_peer: &mut session_by_peer,
            session_completions: &mut session_completions,
            next_generation: &mut next_generation,
            session_panic_trigger: &mut None,
        },
        payload,
    )
    .await;

    assert!(rx_a.try_recv().is_err());
    assert_eq!(sessions_by_id[&session_a].status.state, DaemonState::TunnelOpen);
}

#[tokio::test]
async fn duplicate_signal_for_one_session_does_not_route_to_another_session() {
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
    let offer_a_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer a keys");
    let local_identity = Arc::new(answer.identity);
    let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
    let offer_a_codec = SignalCodec::new(&offer_a.identity, &offer_a_keys, 120, 300);
    let mut transport = RecordingTransport::default();
    let status = StatusWriter::new(&config);
    let mut runtime = connected_runtime();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, _event_rx) = mpsc::channel(4);
    let mut replay_cache = ReplayCache::new(64);
    let mut sessions_by_id = HashMap::new();
    let mut session_by_peer = HashMap::new();
    let mut next_generation = 1_u64;
    let mut session_completions: AnswerSessionCompletions =
        futures_util::stream::FuturesUnordered::new();
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
        session_a,
        offer_a.identity.peer_id.clone(),
        local_identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::ProtocolError.as_str().to_owned(),
        message: "duplicate me".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = offer_a_codec
        .encode_for_peer(
            offer_a_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("payload encodes");

    for _ in 0..2 {
        let shutdown = ShutdownToken::new();
        handle_answer_daemon_payload(
            &AnswerDeps {
                config: &config,
                local_identity: &local_identity,
                authorized_keys: &authorized_keys,
                event_tx: &event_tx,
                shutdown: &shutdown,
            },
            &codec,
            &mut transport,
            &mut ctx,
            &mut AnswerSessionRegistry {
                replay_cache: &mut replay_cache,
                sessions_by_id: &mut sessions_by_id,
                session_by_peer: &mut session_by_peer,
                session_completions: &mut session_completions,
                next_generation: &mut next_generation,
                session_panic_trigger: &mut None,
            },
            payload.clone(),
        )
        .await;
    }

    assert_eq!(rx_a.try_recv().expect("first routed").message.session_id, session_a);
    assert_eq!(rx_a.try_recv().expect("duplicate routed").message.session_id, session_a);
    assert!(rx_b.try_recv().is_err());
    assert_eq!(sessions_by_id[&session_b].status.state, DaemonState::TunnelOpen);
}

#[tokio::test]
async fn active_same_peer_unrelated_offer_gets_encrypted_busy() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    config.node.peer_id = "answer-office".parse().expect("answer peer id");
    config.webrtc.stun_urls = Vec::new();
    config.webrtc.enable_trickle_ice = false;
    config.health.write_status_file = false;
    let config = Arc::new(config);
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer = generate_identity("offer-home").expect("offer identity");
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

    let peer = WebRtcPeer::new(&config.webrtc).await.expect("peer should build");
    let active_id = SessionId::random();
    let mut session = ActiveSession::new(
        active_id,
        answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
        peer,
        config.security.replay_cache_size,
    );
    session.bridge_state = BridgeSessionState::Active;
    session.state = DaemonState::TunnelOpen;
    let replacement_id = SessionId::random();
    let message = InnerMessageBuilder::new(
        replacement_id,
        offer.identity.peer_id.clone(),
        answer_identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "new unrelated offer".to_owned() }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer_identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("offer encodes");
    let mut replay_cache = ReplayCache::new(64);
    let decoded = answer_codec
        .decode_with_replay_status(&payload, &mut replay_cache, None)
        .expect("offer decodes");

    process_answer_session_signal(
        &config,
        &answer_codec,
        &event_tx,
        SessionGeneration(1),
        &mut session,
        decoded,
    )
    .await
    .expect("active unrelated offer should be handled");

    timeout(Duration::from_secs(5), async {
        loop {
            if transport.published.lock().await.len() >= 2 {
                break;
            }
            sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .expect("ack and busy should publish");

    let published = transport.published.lock().await.clone();
    let mut offer_replay = ReplayCache::new(64);
    let decoded = published
        .iter()
        .filter_map(|(_peer, payload)| {
            offer_codec.decode(payload, &mut offer_replay, None).ok().map(|(_, message, _)| message)
        })
        .collect::<Vec<_>>();
    assert!(decoded.iter().any(|message| matches!(message.body, MessageBody::Ack(_))));
    assert!(decoded.iter().any(|message| {
        matches!(
            &message.body,
            MessageBody::Error(ErrorBody { code, .. }) if code == FailureCode::Busy.as_str()
        )
    }));
    assert_eq!(session.session_id, active_id);
    assert_eq!(session.bridge_state, BridgeSessionState::Active);
    event_task.abort();
    let _ = event_task.await;
}
