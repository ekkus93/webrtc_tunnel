use std::collections::VecDeque;
use std::fs;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{AppConfig, MsgId, PeerId, SessionId};
use p2p_crypto::{
    AuthorizedKey, AuthorizedKeys, IdentityFile, decrypt_message, derive_aead_key,
    derive_aead_key_from_shared_secret, encrypt_message, generate_ephemeral_secret,
    kid_from_signing_key, random_nonce, sign_message, verify_message,
};
use rumqttc::tokio_rustls::rustls::{ClientConfig, RootCertStore};
use rumqttc::{
    AsyncClient, Event, EventLoop, MqttOptions, Packet, QoS, TlsConfiguration, Transport,
};
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
        self.encode_for_peer_with_msg_id(recipient, message, response, MsgId::random())
    }

    fn encode_for_peer_with_msg_id(
        &self,
        recipient: &AuthorizedKey,
        message: &InnerMessage,
        response: bool,
        msg_id: MsgId,
    ) -> Result<(OuterEnvelope, Vec<u8>), SignalingError> {
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

    let ca = if config.broker.tls.ca_file.as_os_str().is_empty() {
        None
    } else {
        Some(
            fs::read(&config.broker.tls.ca_file)
                .map_err(|error| SignalingError::io_path(&config.broker.tls.ca_file, error))?,
        )
    };
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

    if let Some(ca) = ca {
        return Ok(Transport::tls(ca, client_auth, None));
    }
    if client_auth.is_some() {
        return Err(SignalingError::Protocol(
            "broker TLS client certificate auth requires broker.tls.ca_file in v1".to_owned(),
        ));
    }
    // No explicit CA: trust the compiled-in Mozilla root set (webpki-roots) instead
    // of rumqttc's default, whose OS-native trust store is empty on Android. This
    // keeps default trust working cross-platform; private CAs still use `ca_file`.
    Ok(Transport::tls_with_config(TlsConfiguration::Rustls(Arc::new(default_roots_tls_config()))))
}

/// rustls client config trusting the webpki-roots Mozilla CA set, with no client
/// auth. Mirrors how rumqttc builds its config (`ClientConfig::builder()`), so it
/// resolves the same process crypto provider.
fn default_roots_tls_config() -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    ClientConfig::builder().with_root_certificates(roots).with_no_client_auth()
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
mod tests;
