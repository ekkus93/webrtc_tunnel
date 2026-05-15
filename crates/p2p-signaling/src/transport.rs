use std::collections::VecDeque;
use std::fs;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{AppConfig, MsgId, PeerId, SessionId};
use p2p_crypto::{
    AuthorizedKey, AuthorizedKeys, IdentityFile, decrypt_message, derive_aead_key,
    derive_aead_key_from_shared_secret, encrypt_message, generate_ephemeral_secret,
    kid_from_signing_key, random_nonce, sign_message, verify_message,
};
use rumqttc::{AsyncClient, Event, EventLoop, MqttOptions, Packet, QoS, Transport};
use x25519_dalek::PublicKey as X25519PublicKey;

use crate::ack::AckTracker;
use crate::envelope::{EnvelopeFlags, OuterEnvelope};
use crate::error::SignalingError;
use crate::messages::{InnerMessage, InnerMessageBuilder};
use crate::replay::{ReplayCache, ReplayCheck, ReplayStatus};

pub fn signal_topic(prefix: &str, peer_id: &PeerId) -> String {
    format!("{prefix}/v1/nodes/{peer_id}/signal")
}

pub struct SignalCodec<'a> {
    local_identity: &'a IdentityFile,
    authorized_keys: &'a AuthorizedKeys,
    max_clock_skew_secs: u64,
    max_message_age_secs: u64,
}

pub struct DecodedSignal {
    pub envelope: OuterEnvelope,
    pub message: InnerMessage,
    pub sender: AuthorizedKey,
    pub replay_status: ReplayStatus,
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
        let decoded = self.decode_with_replay_status(payload, replay_cache, expected_session)?;
        match decoded.replay_status {
            ReplayStatus::Fresh => Ok((decoded.envelope, decoded.message, decoded.sender)),
            ReplayStatus::DuplicateSameSession => {
                Err(SignalingError::Protocol("duplicate message detected".to_owned()))
            }
            ReplayStatus::DuplicateDifferentSession => Err(SignalingError::Protocol(
                "duplicate msg_id received for a different session".to_owned(),
            )),
        }
    }

    pub fn decode_with_replay_status(
        &self,
        payload: &[u8],
        replay_cache: &mut ReplayCache,
        expected_session: Option<SessionId>,
    ) -> Result<DecodedSignal, SignalingError> {
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
        let replay_status = replay_cache.check_and_record_status(
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

        Ok(DecodedSignal { envelope, message, sender, replay_status })
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
    pending_payloads: VecDeque<Vec<u8>>,
}

impl MqttSignalingTransport {
    pub fn connect(config: &AppConfig) -> Result<Self, SignalingError> {
        let (options, qos, own_topic) = build_mqtt_options(config)?;
        let (client, event_loop) = AsyncClient::new(options, 10);

        Ok(Self { client, event_loop, own_topic, qos, pending_payloads: VecDeque::new() })
    }

    pub async fn subscribe_own_topic(&mut self) -> Result<(), SignalingError> {
        self.client
            .subscribe(self.own_topic.clone(), self.qos)
            .await
            .map_err(SignalingError::from)?;

        loop {
            let event = self.poll().await?;
            if matches!(event, Event::Incoming(Packet::SubAck(_))) {
                return Ok(());
            }
            buffer_pending_own_topic_publish(&event, &self.own_topic, &mut self.pending_payloads);
        }
    }

    pub async fn publish_signal(
        &mut self,
        peer_id: &PeerId,
        topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), SignalingError> {
        self.client
            .publish(signal_topic(topic_prefix, peer_id), self.qos, false, payload)
            .await
            .map_err(SignalingError::from)?;
        self.pump_once().await?;
        Ok(())
    }

    pub async fn poll(&mut self) -> Result<Event, SignalingError> {
        self.event_loop.poll().await.map_err(SignalingError::from)
    }

    pub async fn poll_signal_payload(&mut self) -> Result<Option<Vec<u8>>, SignalingError> {
        loop {
            if let Some(payload) = self.pending_payloads.pop_front() {
                return Ok(Some(payload));
            }

            let event = self.poll().await?;
            if let Some(payload) = own_topic_publish_payload(&event, &self.own_topic) {
                return Ok(Some(payload));
            }
        }
    }

    async fn pump_once(&mut self) -> Result<(), SignalingError> {
        let event = self.poll().await?;
        buffer_pending_own_topic_publish(&event, &self.own_topic, &mut self.pending_payloads);
        Ok(())
    }
}

fn buffer_pending_own_topic_publish(
    event: &Event,
    own_topic: &str,
    pending_payloads: &mut VecDeque<Vec<u8>>,
) -> bool {
    let Some(payload) = own_topic_publish_payload(event, own_topic) else {
        return false;
    };
    pending_payloads.push_back(payload);
    true
}

fn own_topic_publish_payload(event: &Event, own_topic: &str) -> Option<Vec<u8>> {
    match event {
        Event::Incoming(Packet::Publish(publish)) if publish.topic == own_topic => {
            Some(publish.payload.to_vec())
        }
        _ => None,
    }
}

fn build_mqtt_options(config: &AppConfig) -> Result<(MqttOptions, QoS, String), SignalingError> {
    if !config.security.require_mqtt_tls {
        return Err(SignalingError::Protocol(
            "security.require_mqtt_tls must remain enabled in v1".to_owned(),
        ));
    }
    if !config.broker.url.starts_with("mqtts://") {
        return Err(SignalingError::Protocol(
            "broker.url must use mqtts:// when TLS is required".to_owned(),
        ));
    }
    if config.broker.connect_timeout_secs != 5 {
        return Err(SignalingError::Protocol(
            "broker.connect_timeout_secs must remain 5 in v1 because the current MQTT transport does not expose a configurable connect timeout".to_owned(),
        ));
    }
    if config.broker.session_expiry_secs != 0 {
        return Err(SignalingError::Protocol(
            "broker.session_expiry_secs must remain 0 in v1 because the current signaling transport uses MQTT v4 semantics".to_owned(),
        ));
    }

    let separator = if config.broker.url.contains('?') { '&' } else { '?' };
    let url = format!("{}{}client_id={}", config.broker.url, separator, config.broker.client_id);
    let mut options = MqttOptions::parse_url(url)?;
    options.set_keep_alive(Duration::from_secs(u64::from(config.broker.keepalive_secs)));
    options.set_clean_session(config.broker.clean_session);
    match (config.broker.username.is_empty(), config.broker.password_file.as_os_str().is_empty()) {
        (true, true) => {}
        (false, true) => {
            options.set_credentials(config.broker.username.clone(), String::new());
        }
        (false, false) => {
            let password = fs::read_to_string(&config.broker.password_file)
                .map_err(|error| SignalingError::io_path(&config.broker.password_file, error))?
                .trim()
                .to_owned();
            options.set_credentials(config.broker.username.clone(), password);
        }
        (true, false) => {
            return Err(SignalingError::Protocol(
                "broker.password_file requires broker.username in v1".to_owned(),
            ));
        }
    }

    if config.broker.url.starts_with("mqtts://") {
        options.set_transport(build_tls_transport(config)?);
    }

    let qos = qos_from_u8(config.broker.qos)?;
    let own_topic = signal_topic(&config.broker.topic_prefix, &config.node.peer_id);
    Ok((options, qos, own_topic))
}

fn build_tls_transport(config: &AppConfig) -> Result<Transport, SignalingError> {
    if config.broker.tls.insecure_skip_verify {
        return Err(SignalingError::Protocol(
            "broker.tls.insecure_skip_verify is unsupported in v1".to_owned(),
        ));
    }

    let ca = fs::read(&config.broker.tls.ca_file)
        .map_err(|error| SignalingError::io_path(&config.broker.tls.ca_file, error))?;
    let client_cert_set = !config.broker.tls.client_cert_file.as_os_str().is_empty();
    let client_key_set = !config.broker.tls.client_key_file.as_os_str().is_empty();
    let client_auth = match (client_cert_set, client_key_set) {
        (false, false) => None,
        (true, true) => Some((
            fs::read(&config.broker.tls.client_cert_file).map_err(|error| {
                SignalingError::io_path(&config.broker.tls.client_cert_file, error)
            })?,
            fs::read(&config.broker.tls.client_key_file).map_err(|error| {
                SignalingError::io_path(&config.broker.tls.client_key_file, error)
            })?,
        )),
        _ => {
            return Err(SignalingError::Protocol(
                "broker TLS client certificate and key must be configured together".to_owned(),
            ));
        }
    };

    Ok(Transport::tls(ca, client_auth, None))
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
    use std::path::PathBuf;

    use p2p_core::{
        AppConfig, BrokerConfig, BrokerTlsConfig, ForwardAnswerConfig, ForwardRule, HealthConfig,
        LoggingConfig, NodeConfig, NodeRole, ReconnectConfig, SecurityConfig, TunnelConfig,
        WebRtcConfig,
    };
    use p2p_core::{MessageType, SessionId};
    use p2p_crypto::{AuthorizedKeys, generate_identity};
    use rumqttc::mqttbytes::v4::{Publish, SubAck, SubscribeReasonCode};
    use rumqttc::{AsyncClient, Event, MqttOptions, Packet, QoS, Transport};

    use super::{
        EnvelopeFlags, InnerMessageBuilder, MqttSignalingTransport, OuterEnvelope, ReplayCache,
        SignalCodec, buffer_pending_own_topic_publish, build_mqtt_options,
        own_topic_publish_payload, signal_topic,
    };
    use crate::{ErrorBody, MessageBody, OfferBody, SignalingError};

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
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer auth");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer auth");
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

        assert_eq!(
            own_topic_publish_payload(&matching_publish, own_topic),
            Some(b"match".to_vec())
        );
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
            format: "p2ptunnel-config-v2".to_owned(),
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
}
