use std::fs;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{AppConfig, MsgId, PeerId, SessionId};
use p2p_crypto::{
    AuthorizedKey, AuthorizedKeys, IdentityFile, decrypt_message, derive_aead_key,
    derive_aead_key_from_shared_secret, encrypt_message, generate_ephemeral_secret,
    kid_from_signing_key, random_nonce, sign_message, verify_message,
};
use rumqttc::{AsyncClient, Event, EventLoop, MqttOptions, QoS, Transport};
use x25519_dalek::PublicKey as X25519PublicKey;

use crate::ack::AckTracker;
use crate::envelope::{EnvelopeFlags, OuterEnvelope};
use crate::error::SignalingError;
use crate::messages::{InnerMessage, InnerMessageBuilder};
use crate::replay::{ReplayCache, ReplayCheck};

pub fn signal_topic(prefix: &str, peer_id: &PeerId) -> String {
    format!("{prefix}/v1/nodes/{peer_id}/signal")
}

pub struct SignalCodec<'a> {
    local_identity: &'a IdentityFile,
    authorized_keys: &'a AuthorizedKeys,
    max_clock_skew_secs: u64,
    max_message_age_secs: u64,
}

impl<'a> SignalCodec<'a> {
    pub fn new(
        local_identity: &'a IdentityFile,
        authorized_keys: &'a AuthorizedKeys,
        max_clock_skew_secs: u64,
        max_message_age_secs: u64,
    ) -> Self {
        Self { local_identity, authorized_keys, max_clock_skew_secs, max_message_age_secs }
    }

    pub fn encode_for_peer(
        &self,
        recipient: &AuthorizedKey,
        message: &InnerMessage,
        response: bool,
    ) -> Result<(OuterEnvelope, Vec<u8>), SignalingError> {
        let msg_id = MsgId::random();
        let sender_kid = self.local_identity.signing_kid();
        let recipient_kid = kid_from_signing_key(&recipient.public_identity.sign_public);
        let eph_secret = generate_ephemeral_secret();
        let eph_public = X25519PublicKey::from(&eph_secret);
        let nonce = random_nonce();
        let key = derive_aead_key(
            &eph_secret,
            &recipient.public_identity.kex_public,
            &sender_kid,
            &recipient_kid,
            &msg_id,
        )?;

        let plaintext = message.encode()?;
        let placeholder = OuterEnvelope {
            flags: EnvelopeFlags { ack_required: message.message_type.requires_ack(), response },
            sender_kid,
            recipient_kid,
            msg_id,
            eph_x25519_pub: *eph_public.as_bytes(),
            aead_nonce: nonce,
            ciphertext: vec![0_u8; plaintext.len() + 16],
            signature: [0_u8; 64],
        };
        let aad = placeholder.aad_bytes()?;
        let ciphertext = encrypt_message(&key, &nonce, &aad, &plaintext)?;
        let mut envelope = OuterEnvelope {
            flags: placeholder.flags,
            sender_kid: placeholder.sender_kid,
            recipient_kid: placeholder.recipient_kid,
            msg_id: placeholder.msg_id,
            eph_x25519_pub: placeholder.eph_x25519_pub,
            aead_nonce: placeholder.aead_nonce,
            ciphertext,
            signature: [0_u8; 64],
        };
        let signature = sign_message(&self.local_identity.signing_key, &envelope.signed_bytes()?);
        envelope.signature = signature;
        let encoded = envelope.encode()?;
        Ok((envelope, encoded))
    }

    pub fn decode(
        &self,
        payload: &[u8],
        replay_cache: &mut ReplayCache,
        expected_session: Option<SessionId>,
    ) -> Result<(OuterEnvelope, InnerMessage, AuthorizedKey), SignalingError> {
        let envelope = OuterEnvelope::decode(payload)?;
        let local_kid = self.local_identity.signing_kid();
        if envelope.recipient_kid != local_kid {
            return Err(SignalingError::Protocol(
                "envelope recipient_kid does not match the local identity".to_owned(),
            ));
        }

        let sender = self
            .authorized_keys
            .get_by_kid(&envelope.sender_kid)
            .cloned()
            .ok_or_else(|| SignalingError::Protocol("sender is not authorized".to_owned()))?;
        verify_message(
            &sender.public_identity.sign_public,
            &envelope.signed_bytes()?,
            &envelope.signature,
        )?;

        let sender_ephemeral_public = X25519PublicKey::from(envelope.eph_x25519_pub);
        let shared_secret = self
            .local_identity
            .kex_static_secret()
            .diffie_hellman(&sender_ephemeral_public)
            .to_bytes();
        let key = derive_aead_key_from_shared_secret(
            &shared_secret,
            &envelope.sender_kid,
            &envelope.recipient_kid,
            &envelope.msg_id,
        )?;
        let plaintext = decrypt_message(
            &key,
            &envelope.aead_nonce,
            &envelope.aad_bytes()?,
            &envelope.ciphertext,
        )?;
        let message = InnerMessage::decode(&plaintext)?;
        if message.version != 1 {
            return Err(SignalingError::Protocol("inner message version must be 1".to_owned()));
        }
        if message.sender_peer_id != sender.peer_id {
            return Err(SignalingError::Protocol(
                "inner sender peer_id does not match authorized sender".to_owned(),
            ));
        }
        if message.recipient_peer_id != self.local_identity.peer_id {
            return Err(SignalingError::Protocol(
                "inner recipient peer_id does not match local peer_id".to_owned(),
            ));
        }
        replay_cache.check_and_record(
            envelope.sender_kid,
            envelope.msg_id,
            ReplayCheck {
                session_id: message.session_id,
                timestamp_ms: message.timestamp_ms,
                now_ms: current_time_ms(),
                max_clock_skew_secs: self.max_clock_skew_secs,
                max_message_age_secs: self.max_message_age_secs,
                expected_session,
            },
        )?;

        Ok((envelope, message, sender))
    }

    pub fn build_ack(
        &self,
        recipient_peer_id: PeerId,
        session_id: SessionId,
        ack_msg_id: MsgId,
    ) -> InnerMessage {
        InnerMessageBuilder::new(session_id, self.local_identity.peer_id.clone(), recipient_peer_id)
            .ack(ack_msg_id)
    }
}

pub struct MqttSignalingTransport {
    client: AsyncClient,
    event_loop: EventLoop,
    own_topic: String,
    qos: QoS,
}

impl MqttSignalingTransport {
    pub fn connect(config: &AppConfig) -> Result<Self, SignalingError> {
        if config.security.require_mqtt_tls && !config.broker.url.starts_with("mqtts://") {
            return Err(SignalingError::Protocol(
                "broker.url must use mqtts:// when TLS is required".to_owned(),
            ));
        }

        let password = fs::read_to_string(&config.broker.password_file)?.trim().to_owned();
        let separator = if config.broker.url.contains('?') { '&' } else { '?' };
        let url =
            format!("{}{}client_id={}", config.broker.url, separator, config.broker.client_id);
        let mut options = MqttOptions::parse_url(url)?;
        options.set_keep_alive(Duration::from_secs(u64::from(config.broker.keepalive_secs)));
        options.set_clean_session(config.broker.clean_session);
        options.set_credentials(config.broker.username.clone(), password);
        if config.broker.url.starts_with("mqtts://") {
            options.set_transport(Transport::tls_with_default_config());
        }

        let qos = qos_from_u8(config.broker.qos)?;
        let own_topic = signal_topic(&config.broker.topic_prefix, &config.node.peer_id);
        let (client, event_loop) = AsyncClient::new(options, 10);

        Ok(Self { client, event_loop, own_topic, qos })
    }

    pub async fn subscribe_own_topic(&self) -> Result<(), SignalingError> {
        self.client.subscribe(self.own_topic.clone(), self.qos).await.map_err(SignalingError::from)
    }

    pub async fn publish_signal(
        &self,
        peer_id: &PeerId,
        topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), SignalingError> {
        self.client
            .publish(signal_topic(topic_prefix, peer_id), self.qos, false, payload)
            .await
            .map_err(SignalingError::from)
    }

    pub async fn poll(&mut self) -> Result<Event, SignalingError> {
        self.event_loop.poll().await.map_err(SignalingError::from)
    }
}

#[derive(Debug)]
pub struct SignalingSession {
    pub replay_cache: ReplayCache,
    pub ack_tracker: AckTracker,
}

impl SignalingSession {
    pub fn new(replay_cache_size: usize) -> Self {
        Self {
            replay_cache: ReplayCache::new(replay_cache_size),
            ack_tracker: AckTracker::default(),
        }
    }
}

fn qos_from_u8(value: u8) -> Result<QoS, SignalingError> {
    match value {
        0 => Ok(QoS::AtMostOnce),
        1 => Ok(QoS::AtLeastOnce),
        2 => Ok(QoS::ExactlyOnce),
        _ => Err(SignalingError::Protocol(format!("unsupported MQTT QoS {value}"))),
    }
}

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock is before unix epoch")
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use p2p_core::{MessageType, SessionId};
    use p2p_crypto::{AuthorizedKeys, generate_identity};

    use super::{
        EnvelopeFlags, InnerMessageBuilder, MqttSignalingTransport, OuterEnvelope, ReplayCache,
        SignalCodec, signal_topic,
    };
    use crate::{ErrorBody, MessageBody, OfferBody};

    fn codecs() -> (
        p2p_crypto::GeneratedIdentity,
        p2p_crypto::GeneratedIdentity,
        AuthorizedKeys,
        AuthorizedKeys,
    ) {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer auth");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer auth");
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
}
