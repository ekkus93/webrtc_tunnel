//! Active-answer busy-offer classification: allow/deny policy, per-session de-duplication, and pre-decode replay detection.

use p2p_core::SessionId;
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{InnerMessageBuilder, MessageBody, OfferBody, OuterEnvelope, SignalCodec};

use super::support::*;

#[test]
fn active_answer_busy_offer_replies_only_to_allowed_peers() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys =
        AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys parse");
    let offer_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys parse");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let active_session = SessionId::random();
    let new_offer_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        new_offer_session,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "second-offer".to_owned() }))
    .expect("test message construction");
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("offer encodes");

    match classify_active_busy_offer(&sample_config(), &answer_codec, &payload, active_session, 64)
    {
        Some(ActiveBusyOfferAction::ReplyBusy { key: _, session_id, sender }) => {
            assert_eq!(session_id, new_offer_session);
            assert_eq!(sender.peer_id, offer.identity.peer_id);
        }
        other => panic!("expected busy reply for allowed peer, got {other:?}"),
    }
}

#[test]
fn active_answer_busy_offer_duplicate_is_suppressed_per_session() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys =
        AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys parse");
    let offer_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys parse");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let active_session = SessionId::random();
    let new_offer_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        new_offer_session,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "second-offer".to_owned() }))
    .expect("test message construction");
    let (_envelope, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("offer encodes");

    let first =
        classify_active_busy_offer(&sample_config(), &answer_codec, &payload, active_session, 64)
            .expect("first foreign offer should classify");
    let second =
        classify_active_busy_offer(&sample_config(), &answer_codec, &payload, active_session, 64)
            .expect("duplicate foreign offer should still classify");
    let mut dedupe = ActiveBusyOfferCache::new(64);

    let first_key = match first {
        ActiveBusyOfferAction::ReplyBusy { key, .. } => key,
        other => panic!("expected busy reply for first offer, got {other:?}"),
    };
    let second_key = match second {
        ActiveBusyOfferAction::ReplyBusy { key, .. } => key,
        other => panic!("expected busy reply for duplicate offer, got {other:?}"),
    };

    assert_eq!(first_key, second_key);
    assert!(dedupe.record_if_new(first_key), "first offer should be new");
    assert!(!dedupe.record_if_new(second_key), "duplicate offer should be suppressed");
}

#[test]
fn replayed_active_busy_offer_is_detected_before_full_decode() {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys parse");
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let new_offer_session = SessionId::random();
    let message = InnerMessageBuilder::new(
        new_offer_session,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "second-offer".to_owned() }))
    .expect("test message construction");
    let (envelope, _payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("offer encodes");
    let mut dedupe = ActiveBusyOfferCache::new(64);
    let key = ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id };
    assert!(dedupe.record_if_new(key), "authenticated busy offer should seed dedupe");

    let tampered_payload =
        OuterEnvelope { ciphertext: vec![0_u8; envelope.ciphertext.len()], ..envelope }
            .encode()
            .expect("tampered envelope should encode");

    assert_eq!(
        replayed_active_busy_offer_key(&tampered_payload, &dedupe),
        Some(key),
        "replayed duplicate should be suppressed from outer-envelope metadata before decode"
    );
}

#[test]
fn active_answer_busy_offer_ignores_authorized_but_disallowed_peer() {
    let allowed = generate_identity("offer-home").expect("allowed identity");
    let disallowed = generate_identity("offer-guest").expect("disallowed identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let answer_keys = AuthorizedKeys::parse(&format!(
        "{}\n{}\n",
        allowed.public_identity.render(),
        disallowed.public_identity.render()
    ))
    .expect("answer keys parse");
    let disallowed_keys =
        AuthorizedKeys::parse(&answer.public_identity.render()).expect("disallowed keys parse");
    let disallowed_codec = SignalCodec::new(&disallowed.identity, &disallowed_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        disallowed.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "guest-offer".to_owned() }))
    .expect("test message construction");
    let (_envelope, payload) = disallowed_codec
        .encode_for_peer(
            disallowed_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("disallowed offer encodes");

    assert!(matches!(
        classify_active_busy_offer(
            &sample_config(),
            &answer_codec,
            &payload,
            SessionId::random(),
            64
        ),
        Some(ActiveBusyOfferAction::Ignore)
    ));
}

#[test]
fn active_answer_busy_offer_ignores_unauthorized_peer() {
    let allowed = generate_identity("offer-home").expect("allowed identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let rogue = generate_identity("rogue-peer").expect("rogue identity");
    let answer_keys =
        AuthorizedKeys::parse(&allowed.public_identity.render()).expect("answer keys parse");
    let rogue_keys = AuthorizedKeys::parse(&answer.public_identity.render())
        .expect("rogue recipient keys parse");
    let rogue_codec = SignalCodec::new(&rogue.identity, &rogue_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let message = InnerMessageBuilder::new(
        SessionId::random(),
        rogue.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "rogue-offer".to_owned() }))
    .expect("test message construction");
    let (_envelope, payload) = rogue_codec
        .encode_for_peer(
            rogue_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &message,
            false,
        )
        .expect("rogue offer encodes");

    assert!(
        classify_active_busy_offer(
            &sample_config(),
            &answer_codec,
            &payload,
            SessionId::random(),
            64
        )
        .is_none()
    );
}
