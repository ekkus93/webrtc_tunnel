use std::collections::{HashMap, VecDeque};

use p2p_core::{FailureCode, SessionId};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{
    AnswerBody, EndOfCandidatesBody, ErrorBody, IceCandidateBody, InnerMessageBuilder, MessageBody,
    OfferBody, ReplayCache, SignalCodec, SignalingError,
};

struct MockBroker {
    queues: HashMap<String, VecDeque<Vec<u8>>>,
}

impl MockBroker {
    fn publish(&mut self, peer_id: &p2p_core::PeerId, payload: Vec<u8>) {
        self.queues.entry(peer_id.to_string()).or_default().push_back(payload);
    }

    fn recv(&mut self, peer_id: &p2p_core::PeerId) -> Option<Vec<u8>> {
        self.queues.get_mut(peer_id.as_str()).and_then(VecDeque::pop_front)
    }
}

fn codecs()
-> (p2p_crypto::GeneratedIdentity, p2p_crypto::GeneratedIdentity, AuthorizedKeys, AuthorizedKeys) {
    let offer = generate_identity("offer-home").expect("offer identity");
    let answer = generate_identity("answer-office").expect("answer identity");
    let offer_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer auth");
    let answer_keys = AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer auth");
    (offer, answer, offer_keys, answer_keys)
}

#[test]
fn two_node_signaling_round_trip_over_mocked_mqtt() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let session_id = SessionId::random();
    let mut broker = MockBroker { queues: HashMap::new() };

    let offer_message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() }))
    .expect("test message construction");
    let (_env, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &offer_message,
            false,
        )
        .expect("offer encodes");
    broker.publish(&answer.identity.peer_id, payload);

    let mut answer_replay = ReplayCache::new(64);
    let received = broker.recv(&answer.identity.peer_id).expect("answer receives offer");
    let (_env, decoded_offer, sender) =
        answer_codec.decode(&received, &mut answer_replay, None).expect("answer decodes offer");
    assert_eq!(sender.peer_id, offer.identity.peer_id);
    assert_eq!(decoded_offer.body, offer_message.body);

    let answer_message = InnerMessageBuilder::new(
        session_id,
        answer.identity.peer_id.clone(),
        offer.identity.peer_id.clone(),
    )
    .build(MessageBody::Answer(AnswerBody { sdp: "answer-sdp".to_owned() }))
    .expect("test message construction");
    let (_env, payload) = answer_codec
        .encode_for_peer(
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &answer_message,
            false,
        )
        .expect("answer encodes");
    broker.publish(&offer.identity.peer_id, payload);

    let mut offer_replay = ReplayCache::new(64);
    let received = broker.recv(&offer.identity.peer_id).expect("offer receives answer");
    let (_env, decoded_answer, sender) =
        offer_codec.decode(&received, &mut offer_replay, None).expect("offer decodes answer");
    assert_eq!(sender.peer_id, answer.identity.peer_id);
    assert_eq!(decoded_answer.body, answer_message.body);
}

#[test]
fn offer_answer_session_setup_and_candidate_exchange() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let session_id = SessionId::random();

    let offer_candidate = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::IceCandidate(IceCandidateBody {
        candidate: Some("candidate:1 1 udp 123 192.0.2.10 4567 typ host".to_owned()),
        sdp_mid: Some("data".to_owned()),
        sdp_mline_index: Some(0),
    }))
    .expect("test message construction");
    let (_env, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &offer_candidate,
            false,
        )
        .expect("candidate encodes");
    let mut replay = ReplayCache::new(64);
    let (_env, decoded_candidate, _) =
        answer_codec.decode(&payload, &mut replay, Some(session_id)).expect("candidate decodes");
    assert_eq!(decoded_candidate.body, offer_candidate.body);

    let end_of_candidates = InnerMessageBuilder::new(
        session_id,
        answer.identity.peer_id.clone(),
        offer.identity.peer_id.clone(),
    )
    .build(MessageBody::EndOfCandidates(EndOfCandidatesBody::default()))
    .expect("test message construction");
    let (_env, payload) = answer_codec
        .encode_for_peer(
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &end_of_candidates,
            false,
        )
        .expect("end-of-candidates encodes");
    let mut replay = ReplayCache::new(64);
    let (_env, decoded_end, _) = offer_codec
        .decode(&payload, &mut replay, Some(session_id))
        .expect("end-of-candidates decodes");
    assert_eq!(decoded_end.body, end_of_candidates.body);
}

#[test]
fn ice_failure_path_sends_encrypted_error() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let session_id = SessionId::random();
    let error_message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Error(ErrorBody {
        code: FailureCode::IceFailed.as_str().to_owned(),
        message: "ice connection failed".to_owned(),
        fatal: true,
    }))
    .expect("test message construction");
    let (_env, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &error_message,
            false,
        )
        .expect("error encodes");
    let mut replay = ReplayCache::new(64);
    let (_env, decoded, _) =
        answer_codec.decode(&payload, &mut replay, Some(session_id)).expect("error decodes");
    assert_eq!(
        decoded.body,
        MessageBody::Error(ErrorBody {
            code: FailureCode::IceFailed.as_str().to_owned(),
            message: "ice connection failed".to_owned(),
            fatal: true,
        })
    );
}

#[test]
fn unauthorized_peer_is_rejected() {
    let (_offer, answer, _offer_keys, answer_keys) = codecs();
    let rogue = generate_identity("rogue-peer").expect("rogue identity");
    let rogue_keys = AuthorizedKeys::parse(&answer.public_identity.render()).expect("rogue auth");
    let rogue_codec = SignalCodec::new(&rogue.identity, &rogue_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let session_id = SessionId::random();
    let rogue_offer = InnerMessageBuilder::new(
        session_id,
        rogue.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "rogue".to_owned() }))
    .expect("test message construction");
    let (_env, payload) = rogue_codec
        .encode_for_peer(
            rogue_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &rogue_offer,
            false,
        )
        .expect("rogue offer encodes");
    let mut replay = ReplayCache::new(64);
    assert!(matches!(
        answer_codec.decode(&payload, &mut replay, None),
        Err(SignalingError::Protocol(message)) if message.contains("sender is not authorized")
    ));
}

#[test]
fn replayed_message_is_rejected() {
    let (offer, answer, offer_keys, answer_keys) = codecs();
    let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
    let session_id = SessionId::random();
    let offer_message = InnerMessageBuilder::new(
        session_id,
        offer.identity.peer_id.clone(),
        answer.identity.peer_id.clone(),
    )
    .build(MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() }))
    .expect("test message construction");
    let (_env, payload) = offer_codec
        .encode_for_peer(
            offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
            &offer_message,
            false,
        )
        .expect("offer encodes");
    let mut replay = ReplayCache::new(64);
    answer_codec.decode(&payload, &mut replay, None).expect("first decode succeeds");
    assert!(matches!(
        answer_codec.decode(&payload, &mut replay, None),
        Err(SignalingError::Protocol(message)) if message.contains("duplicate")
    ));
}
