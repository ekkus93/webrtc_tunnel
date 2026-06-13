use std::path::PathBuf;

use p2p_core::{
    AppConfig, BrokerConfig, BrokerTlsConfig, ForwardAnswerConfig, ForwardRule, HealthConfig,
    LoggingConfig, MsgId, NodeConfig, NodeRole, ReconnectConfig, SecurityConfig, TunnelConfig,
    WebRtcConfig,
};
use p2p_core::{MessageType, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use rumqttc::mqttbytes::v4::{Publish, SubAck, SubscribeReasonCode};
use rumqttc::{AsyncClient, Event, MqttOptions, Packet, QoS, Transport};

use super::{
    EnvelopeFlags, InnerMessageBuilder, MqttSignalingTransport, OuterEnvelope, ReplayCache,
    ReplayStatus, SignalCodec, buffer_pending_own_topic_publish, build_mqtt_options,
    default_roots_tls_config, own_topic_publish_payload, signal_topic,
};
use crate::{ErrorBody, MessageBody, OfferBody, SignalingError};

fn codecs()
-> (p2p_crypto::GeneratedIdentity, p2p_crypto::GeneratedIdentity, AuthorizedKeys, AuthorizedKeys) {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer auth");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer auth");
    (offer, answer, offer_keys, answer_keys)
}

#[test]
fn envelope_round_trip_encode_decode() {
    let envelope = OuterEnvelope {
        flags: EnvelopeFlags { ack_required: true, response: false },
        sender_kid: p2p_core::Kid::new([1_u8; 32]),
        recipient_kid: p2p_core::Kid::new([2_u8; 32]),
        msg_id: p2p_core::MsgId::new([3_u8; 16]),
        eph_x25519_pub: [4_u8; 32],
        aead_nonce: [5_u8; 24],
        ciphertext: vec![6_u8; 12],
        signature: [7_u8; 64],
    };
    let encoded = envelope.encode().expect("encode");
    let decoded = OuterEnvelope::decode(&encoded).expect("decode");
    assert_eq!(decoded, envelope);
}

#[test]
fn inner_message_encrypt_decrypt_round_trip() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");

    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let (_decoded_envelope, decoded_message, _sender) =
        answer_codec.decode(&payload, &mut replay_cache, None).expect("decode");
    assert_eq!(decoded_message.message_type, MessageType::Offer);
}

#[test]
fn reject_wrong_recipient_kid() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    let (mut envelope, _) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");
    envelope.recipient_kid = p2p_core::Kid::new([9_u8; 32]);
    let payload = envelope.encode().expect("encode payload");
    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    assert!(answer_codec.decode(&payload, &mut replay_cache, None).is_err());
}

#[test]
fn reject_invalid_signature() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    let (mut envelope, _) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");
    envelope.signature[0] ^= 0x01;
    let payload = envelope.encode().expect("payload");
    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    assert!(answer_codec.decode(&payload, &mut replay_cache, None).is_err());
}

#[test]
fn reject_duplicate_msg_id() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: "busy".to_owned(),
        message: "already in use".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");
    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    answer_codec.decode(&payload, &mut replay_cache, None).expect("first decode");
    assert!(answer_codec.decode(&payload, &mut replay_cache, None).is_err());
}

#[test]
fn decode_with_replay_status_reports_fresh_and_duplicate_same_session() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let session_id = SessionId::random();
    let message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: "busy".to_owned(),
        message: "already in use".to_owned(),
        fatal: true,
    }));
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let mut replay_cache = ReplayCache::new(32);

    let fresh = answer_codec
        .decode_with_replay_status(&payload, &mut replay_cache, None)
        .expect("fresh decode");
    assert_eq!(fresh.replay_status, ReplayStatus::Fresh);
    assert_eq!(fresh.sender.peer_id, offer.identity.peer_id);
    assert_eq!(fresh.message.session_id, session_id);

    let duplicate = answer_codec
        .decode_with_replay_status(&payload, &mut replay_cache, None)
        .expect("duplicate decode still authenticates");
    assert_eq!(duplicate.replay_status, ReplayStatus::DuplicateSameSession);
    assert_eq!(duplicate.sender.peer_id, offer.identity.peer_id);
    assert_eq!(duplicate.message.session_id, session_id);

    let mut legacy_replay = ReplayCache::new(32);
    answer_codec.decode(&payload, &mut legacy_replay, None).expect("legacy first decode");
    assert!(matches!(
        answer_codec.decode(&payload, &mut legacy_replay, None),
        Err(SignalingError::Protocol(message)) if message.contains("duplicate message detected")
    ));
}

#[test]
fn decode_with_replay_status_reports_duplicate_different_session() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_key =
        offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer peer exists").clone();
    let reused_msg_id = MsgId::new([7_u8; 16]);
    let first = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: "busy".to_owned(),
        message: "first".to_owned(),
        fatal: true,
    }));
    let second = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: "busy".to_owned(),
        message: "second".to_owned(),
        fatal: true,
    }));
    let (_first_envelope, first_payload) = codec
        .encode_for_peer_with_msg_id(&answer_key, &first, false, reused_msg_id)
        .expect("first encodes");
    let (_second_envelope, second_payload) = codec
        .encode_for_peer_with_msg_id(&answer_key, &second, false, reused_msg_id)
        .expect("second encodes");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let mut replay_cache = ReplayCache::new(32);

    let fresh = answer_codec
        .decode_with_replay_status(&first_payload, &mut replay_cache, None)
        .expect("first decode");
    assert_eq!(fresh.replay_status, ReplayStatus::Fresh);
    let duplicate = answer_codec
        .decode_with_replay_status(&second_payload, &mut replay_cache, None)
        .expect("second decode");
    assert_eq!(duplicate.replay_status, ReplayStatus::DuplicateDifferentSession);

    let mut legacy_replay = ReplayCache::new(32);
    answer_codec.decode(&first_payload, &mut legacy_replay, None).expect("legacy first decode");
    assert!(matches!(
        answer_codec.decode(&second_payload, &mut legacy_replay, None),
        Err(SignalingError::Protocol(message))
            if message.contains("different session")
    ));
}

#[test]
fn decode_with_replay_status_rejects_expected_session_mismatch_before_status() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let stale_session = SessionId::random();
    let expected_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        stale_session,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let mut replay_cache = ReplayCache::new(32);

    assert!(matches!(
        answer_codec.decode_with_replay_status(
            &payload,
            &mut replay_cache,
            Some(expected_session)
        ),
        Err(SignalingError::Protocol(message)) if message.contains("active session")
    ));
}

#[test]
fn reject_wrong_sender_peer_id() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let mut message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    message.sender_peer_id = "wrong-sender".parse().expect("peer id");
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");

    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    assert!(matches!(
        answer_codec.decode(&payload, &mut replay_cache, None),
        Err(SignalingError::Protocol(message))
            if message.contains("inner sender peer_id does not match")
    ));
}

#[test]
fn reject_unauthorized_sender() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let intruder = generate_identity("intruder-peer").expect("intruder identity");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer auth");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer auth");
    let codec = SignalCodec::new(&intruder.identity, &offer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        intruder.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");

    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    assert!(matches!(
        answer_codec.decode(&payload, &mut replay_cache, None),
        Err(SignalingError::Protocol(message)) if message.contains("not authorized")
    ));
}

#[test]
fn reject_stale_session_when_expected_session_is_set() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let expected_session = SessionId::random();
    let stale_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        stale_session,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");

    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    assert!(matches!(
        answer_codec.decode(&payload, &mut replay_cache, Some(expected_session)),
        Err(SignalingError::Protocol(message)) if message.contains("active session")
    ));
}

#[test]
fn reject_stale_timestamp() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 0, 0);
    let mut message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }));
    message.timestamp_ms = 1;
    let (_envelope, payload) = codec
        .encode_for_peer(
            &offer_keys
                .get_by_peer_id(&answer.identity.peer_id)
                .expect("answer peer exists")
                .clone(),
            &message,
            false,
        )
        .expect("encode");
    let mut replay_cache = ReplayCache::new(32);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 0, 0);
    assert!(answer_codec.decode(&payload, &mut replay_cache, None).is_err());
}

#[test]
fn topic_generation_matches_spec() {
    let peer_id: p2p_core::PeerId = "answer-office".parse().expect("peer id");
    assert_eq!(signal_topic("p2ptunnel", &peer_id), "p2ptunnel/v1/nodes/answer-office/signal");
}

#[test]
fn transport_type_exists() {
    let _ = std::mem::size_of::<MqttSignalingTransport>();
}

#[test]
fn own_topic_publish_is_buffered_during_subscribe_handshake() {
    let own_topic = "p2ptunnel/v1/nodes/answer-office/signal";
    let event = Event::Incoming(Packet::Publish(Publish::new(
        own_topic,
        QoS::AtLeastOnce,
        b"hello".to_vec(),
    )));
    let mut pending = std::collections::VecDeque::new();

    assert!(buffer_pending_own_topic_publish(&event, own_topic, &mut pending));
    assert_eq!(pending.pop_front(), Some(b"hello".to_vec()));
}

#[test]
fn unrelated_events_are_not_buffered_as_pending_payloads() {
    let own_topic = "p2ptunnel/v1/nodes/answer-office/signal";
    let foreign_publish = Event::Incoming(Packet::Publish(Publish::new(
        "p2ptunnel/v1/nodes/offer-home/signal",
        QoS::AtLeastOnce,
        b"foreign".to_vec(),
    )));
    let suback = Event::Incoming(Packet::SubAck(SubAck::new(
        7,
        vec![SubscribeReasonCode::Success(QoS::AtLeastOnce)],
    )));
    let mut pending = std::collections::VecDeque::new();

    assert!(!buffer_pending_own_topic_publish(&foreign_publish, own_topic, &mut pending));
    assert!(!buffer_pending_own_topic_publish(&suback, own_topic, &mut pending));
    assert!(pending.is_empty());
}

#[test]
fn own_topic_publish_payload_extracts_only_matching_topic_payloads() {
    let own_topic = "p2ptunnel/v1/nodes/answer-office/signal";
    let matching_publish = Event::Incoming(Packet::Publish(Publish::new(
        own_topic,
        QoS::AtLeastOnce,
        b"match".to_vec(),
    )));
    let foreign_publish = Event::Incoming(Packet::Publish(Publish::new(
        "p2ptunnel/v1/nodes/offer-home/signal",
        QoS::AtLeastOnce,
        b"foreign".to_vec(),
    )));
    let suback = Event::Incoming(Packet::SubAck(SubAck::new(
        9,
        vec![SubscribeReasonCode::Success(QoS::AtLeastOnce)],
    )));

    assert_eq!(own_topic_publish_payload(&matching_publish, own_topic), Some(b"match".to_vec()));
    assert_eq!(own_topic_publish_payload(&foreign_publish, own_topic), None);
    assert_eq!(own_topic_publish_payload(&suback, own_topic), None);
}

#[tokio::test]
async fn poll_signal_payload_returns_buffered_payload_before_polling_network() {
    let options = MqttOptions::new("test-client", "localhost", 1883);
    let (client, event_loop) = AsyncClient::new(options, 10);
    let mut transport = MqttSignalingTransport {
        client,
        event_loop,
        own_topic: "p2ptunnel/v1/nodes/answer-office/signal".to_owned(),
        qos: QoS::AtLeastOnce,
        pending_payloads: std::collections::VecDeque::from([b"buffered".to_vec()]),
    };

    let payload = transport
        .poll_signal_payload()
        .await
        .expect("buffered payload should be returned without polling the network");

    assert_eq!(payload, Some(b"buffered".to_vec()));
    assert!(transport.pending_payloads.is_empty());
}

fn sample_config(base: &std::path::Path) -> AppConfig {
    AppConfig {
        format: "p2ptunnel-config-v3".to_owned(),
        node: NodeConfig {
            peer_id: "answer-office".parse().expect("peer id"),
            role: NodeRole::Answer,
        },
        peer: None,
        paths: p2p_core::PathConfig {
            identity: base.join("identity"),
            authorized_keys: base.join("authorized_keys"),
            state_dir: base.join("state"),
            log_dir: base.join("state/log"),
        },
        broker: BrokerConfig {
            url: "mqtts://broker.example:8883".to_owned(),
            client_id: "answer-office".to_owned(),
            topic_prefix: "p2ptunnel".to_owned(),
            username: "answer-office".to_owned(),
            password_file: base.join("password"),
            qos: 1,
            keepalive_secs: 30,
            clean_session: true,
            connect_timeout_secs: 5,
            session_expiry_secs: 0,
            tls: BrokerTlsConfig {
                ca_file: base.join("ca.pem"),
                client_cert_file: PathBuf::new(),
                client_key_file: PathBuf::new(),
                insecure_skip_verify: false,
            },
        },
        webrtc: WebRtcConfig {
            stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            enable_trickle_ice: true,
            enable_ice_restart: true,
        },
        tunnel: TunnelConfig {
            read_chunk_size: 1024,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
        },
        forwards: vec![ForwardRule {
            id: "ssh".to_owned(),
            offer: None,
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 22,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            }),
        }],
        reconnect: ReconnectConfig {
            enable_auto_reconnect: true,
            strategy: "ice_then_renegotiate".to_owned(),
            ice_restart_timeout_secs: 8,
            renegotiate_timeout_secs: 20,
            backoff_initial_ms: 1000,
            backoff_max_ms: 30_000,
            backoff_multiplier: 2.0,
            jitter_ratio: 0.2,
            max_attempts: 0,
            hold_local_client_during_reconnect: false,
            local_client_hold_secs: 0,
        },
        security: SecurityConfig {
            require_mqtt_tls: true,
            require_message_encryption: true,
            require_message_signatures: true,
            require_authorized_keys: true,
            max_clock_skew_secs: 120,
            max_message_age_secs: 300,
            replay_cache_size: 64,
            reject_unknown_config_keys: true,
            refuse_world_readable_identity: true,
            refuse_world_writable_paths: true,
        },
        logging: LoggingConfig {
            level: "info".to_owned(),
            format: "text".to_owned(),
            file_logging: false,
            stdout_logging: true,
            log_file: base.join("state/p2ptunnel.log"),
            redact_secrets: true,
            redact_sdp: true,
            redact_candidates: true,
            log_rotation: "none".to_owned(),
        },
        health: HealthConfig {
            status_socket: PathBuf::new(),
            write_status_file: true,
            status_file: base.join("state/status.json"),
        },
    }
}

#[test]
fn build_mqtt_options_uses_custom_tls_transport() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let config = sample_config(temp_dir.path());

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    assert!(matches!(options.transport(), Transport::Tls(_)));
}

#[test]
fn build_mqtt_options_supports_anonymous_broker_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.username.clear();
    config.broker.password_file = PathBuf::new();

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    assert!(options.credentials().is_none());
}

#[test]
fn build_mqtt_options_supports_username_only_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.password_file = PathBuf::new();

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    let credentials = options.credentials().expect("credentials");
    assert_eq!(credentials.username, "answer-office");
    assert!(credentials.password.is_empty());
}

#[test]
fn build_mqtt_options_supports_default_tls_roots_when_ca_file_is_empty() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    let mut config = sample_config(temp_dir.path());
    config.broker.tls.ca_file = PathBuf::new();

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    assert!(matches!(options.transport(), Transport::Tls(_)));
}

#[test]
fn default_roots_tls_config_trusts_nonempty_webpki_root_set() {
    // Guards against shipping an empty trust store (the Android UnknownIssuer
    // bug): the compiled-in Mozilla root set must be present.
    assert!(!webpki_roots::TLS_SERVER_ROOTS.is_empty());
    // Building the config must not panic (resolves a crypto provider).
    let _config = default_roots_tls_config();
}

#[test]
fn build_mqtt_options_rejects_client_cert_without_ca_when_using_default_roots() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(temp_dir.path().join("client.crt"), "client cert").expect("client cert");
    std::fs::write(temp_dir.path().join("client.key"), "client key").expect("client key");
    let mut config = sample_config(temp_dir.path());
    config.broker.tls.ca_file = PathBuf::new();
    config.broker.tls.client_cert_file = temp_dir.path().join("client.crt");
    config.broker.tls.client_key_file = temp_dir.path().join("client.key");

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("requires broker.tls.ca_file")
    ));
}

#[test]
fn build_mqtt_options_rejects_password_without_username() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.username.clear();

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("password_file requires broker.username")
    ));
}

#[test]
fn build_mqtt_options_rejects_unsupported_connect_timeout() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.connect_timeout_secs = 10;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("connect_timeout_secs")
    ));
}

#[test]
fn build_mqtt_options_rejects_unsupported_session_expiry() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.session_expiry_secs = 30;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("session_expiry_secs")
    ));
}

#[test]
fn build_mqtt_options_missing_password_file_names_path() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    let missing_password = temp_dir.path().join("missing-password");
    config.broker.password_file = missing_password.clone();

    let error = build_mqtt_options(&config).expect_err("missing password file should fail");

    assert!(error.to_string().contains(missing_password.to_string_lossy().as_ref()));
}
