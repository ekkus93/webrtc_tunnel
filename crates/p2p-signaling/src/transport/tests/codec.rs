use super::support::*;

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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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

/// A genuine `SystemTimeError`, synthesized without touching the real system clock: asking for
/// the duration since a point strictly in the future always fails this way (FIX7 P0-010-F —
/// "do not mutate system clock in tests").
fn synthetic_clock_error() -> std::time::SystemTimeError {
    let future = std::time::SystemTime::now() + std::time::Duration::from_secs(3600);
    std::time::SystemTime::now()
        .duration_since(future)
        .expect_err("a point strictly in the future must make duration_since fail")
}

fn failing_clock() -> Result<u64, std::time::SystemTimeError> {
    Err(synthetic_clock_error())
}

// FIX7 P0-010-G: a decode-time clock failure must return the typed error rather than panic.
#[test]
fn signaling_decode_clock_failure_returns_typed_error_and_does_not_panic() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer peer exists"),
            &message,
            false,
        )
        .expect("encode");

    let mut replay_cache = ReplayCache::new(32);
    let result = answer_codec.decode_with_replay_status_and_clock(
        &payload,
        &mut replay_cache,
        None,
        failing_clock,
    );

    match result {
        Err(SignalingError::Clock(_)) => {}
        Ok(_) => panic!("expected a typed clock error, got Ok"),
        Err(other) => panic!("expected a typed clock error, got a different error: {other}"),
    }
}

// FIX7 P0-010-G: a decode aborted by a clock failure must never record a replay entry — the
// `now_ms: clock()?` read happens as part of constructing the `ReplayCheck` argument, so
// `check_and_record_status` is never even called when the clock fails. Proven here by decoding
// the SAME payload again with a real clock afterward and confirming it is still accepted as
// fresh, not rejected as a duplicate.
#[test]
fn signaling_decode_clock_failure_does_not_record_replay_entry() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer peer exists"),
            &message,
            false,
        )
        .expect("encode");

    let mut replay_cache = ReplayCache::new(32);
    let first = answer_codec.decode_with_replay_status_and_clock(
        &payload,
        &mut replay_cache,
        None,
        failing_clock,
    );
    assert!(matches!(first, Err(SignalingError::Clock(_))));

    let second = answer_codec.decode(&payload, &mut replay_cache, None);
    match second {
        Ok(_) => {}
        Err(error) => panic!(
            "a decode aborted by a clock failure must not have recorded a replay entry, got {error}"
        ),
    }
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
    }))
    .expect("test message construction");
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
    }))
    .expect("test message construction");
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
    }))
    .expect("test message construction");
    let second = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: "busy".to_owned(),
        message: "second".to_owned(),
        fatal: true,
    }))
    .expect("test message construction");
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
fn reject_unsupported_inner_message_version() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let mut message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
    message.version = 2;
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
            if message.contains("inner message version must be 1")
    ));
}

#[test]
fn reject_wrong_recipient_peer_id() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let mut message = InnerMessageBuilder::new(
        SessionId::random(),
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
    message.recipient_peer_id = "some-other-peer".parse().expect("peer id");
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
            if message.contains("inner recipient peer_id does not match")
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
    .build(MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }))
    .expect("test message construction");
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
