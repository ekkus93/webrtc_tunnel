//! Forward binding, CLI/env override application, authorized-peer validation, backoff/reconnect predicates, and idle-path signaling decode.

use p2p_core::{FailureCode, ForwardOfferConfig, ForwardRule, NodeRole, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{
    ErrorBody, InnerMessageBuilder, MessageBody, OfferBody, ReplayCache, SignalCodec,
    SignalingError,
};
use tokio::net::TcpListener;

use super::support::*;

#[tokio::test]
async fn bind_offer_listeners_soft_fails_individual_forward() {
    // Occupy a port so one forward fails to bind while another succeeds.
    let occupied = TcpListener::bind("127.0.0.1:0").await.expect("occupy port");
    let occupied_port = occupied.local_addr().expect("occupied addr").port();
    let free = TcpListener::bind("127.0.0.1:0").await.expect("probe free port");
    let free_port = free.local_addr().expect("free addr").port();
    drop(free);

    let mut config = sample_config();
    config.forwards = vec![
        ForwardRule {
            id: "ok".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: free_port,
            }),
            answer: None,
        },
        ForwardRule {
            id: "busy".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: occupied_port,
            }),
            answer: None,
        },
    ];

    let (listeners, statuses) =
        bind_offer_listeners(&config).await.expect("soft-fail, not daemon error");
    assert_eq!(listeners.len(), 1, "only the bindable forward should listen");
    let ok = statuses.iter().find(|s| s.id == "ok").expect("ok status");
    assert_eq!(ok.listen_state, ForwardListenState::Listening);
    assert!(ok.last_error.is_none());
    let busy = statuses.iter().find(|s| s.id == "busy").expect("busy status");
    assert_eq!(busy.listen_state, ForwardListenState::Error);
    assert!(busy.last_error.is_some());
}

#[test]
fn apply_offer_cli_overrides() {
    let mut config = sample_config();
    let original_port = super::first_offer_forward(&config).expect("offer").1.listen_port;
    apply_offer_overrides(&mut config, Some("mqtts://override".to_owned()));
    assert_eq!(config.broker.url, "mqtts://override");
    assert_eq!(super::first_offer_forward(&config).expect("offer").1.listen_port, original_port);
}

#[test]
fn apply_answer_cli_overrides() {
    let mut config = sample_config();
    let original_answer = super::first_answer_forward(&config).expect("answer").clone();
    apply_answer_overrides(&mut config, Some("mqtts://override".to_owned()));
    assert_eq!(config.broker.url, "mqtts://override");
    let answer = super::first_answer_forward(&config).expect("answer");
    assert_eq!(answer.target_host, original_answer.target_host);
    assert_eq!(answer.target_port, original_answer.target_port);
}

#[test]
fn env_overrides_update_global_config() {
    let mut config = sample_config();

    apply_override_pairs(
        &mut config,
        [("P2PTUNNEL_BROKER_URL".to_owned(), "mqtts://env".to_owned())],
    )
    .expect("global env override should apply");
    assert_eq!(config.broker.url, "mqtts://env");
}

#[test]
fn legacy_first_forward_env_overrides_are_rejected() {
    for key in ["P2PTUNNEL_LISTEN_PORT", "P2PTUNNEL_TARGET_HOST", "P2PTUNNEL_TARGET_PORT"] {
        let mut config = sample_config();
        let error = apply_override_pairs(&mut config, [(key.to_owned(), "ignored".to_owned())])
            .expect_err("legacy first-forward env override should fail");
        assert!(error.to_string().contains("no longer supported in v0.2 config"));
    }
}

#[test]
fn offer_remote_peer_must_exist_in_authorized_keys() {
    let config = sample_config();
    let authorized_keys = AuthorizedKeys::parse("").expect("empty authorized keys");

    assert!(matches!(
        super::validate_config_authorized_peers(&config, &authorized_keys),
        Err(DaemonError::MissingAuthorizedPeer(peer)) if peer == "answer-office"
    ));
}

#[test]
fn answer_allowlist_peers_must_exist_in_authorized_keys() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let authorized_keys = AuthorizedKeys::parse("").expect("empty authorized keys");

    assert!(matches!(
        super::validate_config_authorized_peers(&config, &authorized_keys),
        Err(DaemonError::MissingAuthorizedPeer(peer)) if peer == "offer-home"
    ));
}

#[test]
fn backoff_grows_with_attempts() {
    let config = sample_config();
    let first = compute_backoff_delay(&config, 0);
    let second = compute_backoff_delay(&config, 1);
    assert!(second >= first);
}

#[test]
fn backoff_without_jitter_is_deterministic_and_caps_at_max() {
    let mut config = sample_config();
    config.reconnect.backoff_initial_ms = 1000;
    config.reconnect.backoff_max_ms = 5000;
    config.reconnect.backoff_multiplier = 2.0;
    config.reconnect.jitter_ratio = 0.0; // disable jitter for exact assertions
    assert_eq!(compute_backoff_delay(&config, 0).as_millis(), 1000);
    assert_eq!(compute_backoff_delay(&config, 1).as_millis(), 2000);
    assert_eq!(compute_backoff_delay(&config, 2).as_millis(), 4000);
    // 1000 * 2^3 = 8000, clamped to backoff_max_ms.
    assert_eq!(compute_backoff_delay(&config, 3).as_millis(), 5000);
    assert_eq!(compute_backoff_delay(&config, 20).as_millis(), 5000);
}

#[test]
fn backoff_attempt_zero_stays_within_the_jitter_window() {
    let mut config = sample_config();
    config.reconnect.backoff_initial_ms = 1000;
    config.reconnect.backoff_max_ms = 30_000;
    config.reconnect.backoff_multiplier = 2.0;
    config.reconnect.jitter_ratio = 0.2; // ±200ms around the 1000ms base
    for _ in 0..256 {
        let delay = compute_backoff_delay(&config, 0).as_millis() as i64;
        assert!((800..=1200).contains(&delay), "attempt-0 delay {delay}ms outside jitter window");
    }
}

#[test]
fn backoff_jitter_stays_within_ratio_of_the_capped_base() {
    let mut config = sample_config();
    config.reconnect.backoff_initial_ms = 1000;
    config.reconnect.backoff_max_ms = 4000;
    config.reconnect.backoff_multiplier = 2.0;
    config.reconnect.jitter_ratio = 0.25; // base capped at 4000 -> ±1000ms
    for _ in 0..256 {
        let delay = compute_backoff_delay(&config, 6).as_millis() as i64;
        assert!((3000..=5000).contains(&delay), "capped+jitter delay {delay}ms out of bounds");
    }
}

#[test]
fn idle_replay_cache_rejects_replayed_offer_across_iterations() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("offer encodes");

    let mut replay_cache = ReplayCache::new(64);
    decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache)
        .expect("first decode succeeds");
    assert!(matches!(
        decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache),
        Err(DaemonError::Signaling(SignalingError::Protocol(message)))
            if message.contains("duplicate")
    ));
}

#[test]
fn idle_replay_cache_rejects_replayed_ack_required_message_across_iterations() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::IceFailed.as_str().to_owned(),
        message: "ice failed".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("error encodes");

    let mut replay_cache = ReplayCache::new(64);
    decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache)
        .expect("first decode succeeds");
    assert!(matches!(
        decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache),
        Err(DaemonError::Signaling(SignalingError::Protocol(message)))
            if message.contains("duplicate")
    ));
}

#[test]
fn active_offer_bridge_does_not_attempt_reconnect() {
    let config = sample_config();
    assert!(!should_attempt_offer_reconnect(&config, false, BridgeSessionState::Pending));
    assert!(!should_attempt_offer_reconnect(&config, true, BridgeSessionState::Active));
    assert!(should_attempt_offer_reconnect(&config, true, BridgeSessionState::Reconnecting));
}

#[test]
fn unauthorized_idle_offer_does_not_ack() {
    assert!(!should_ack_idle_offer(false, true));
    assert!(!should_ack_idle_offer(false, false));
    assert!(should_ack_idle_offer(true, true));
}

#[test]
fn max_attempts_zero_means_unlimited() {
    assert!(should_continue_reconnect_attempt(0, 0));
    assert!(should_continue_reconnect_attempt(0, 25));
    assert!(should_continue_reconnect_attempt(3, 2));
    assert!(!should_continue_reconnect_attempt(3, 3));
}

#[test]
fn strict_active_session_decode_rejects_foreign_offer() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let active_session = SessionId::random();
    let foreign_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        foreign_session,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() }));
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("offer encodes");

    let mut replay_cache = ReplayCache::new(64);
    assert!(matches!(
        answer_codec.decode(&payload, &mut replay_cache, Some(active_session)),
        Err(SignalingError::Protocol(message))
            if message.contains("active session")
    ));
}
