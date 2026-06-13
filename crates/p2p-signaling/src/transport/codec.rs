//! Signal codec: encodes outgoing [`InnerMessage`]s into authenticated, encrypted
//! [`OuterEnvelope`]s for a recipient and decodes/verifies inbound envelopes
//! (signature, AEAD decryption, freshness, and replay status) back into a
//! [`DecodedSignal`].

use std::time::{SystemTime, UNIX_EPOCH};

use p2p_core::{MsgId, PeerId, SessionId};
use p2p_crypto::{
    AuthorizedKey, AuthorizedKeys, IdentityFile, decrypt_message, derive_aead_key,
    derive_aead_key_from_shared_secret, encrypt_message, generate_ephemeral_secret,
    kid_from_signing_key, random_nonce, sign_message, verify_message,
};
use x25519_dalek::PublicKey as X25519PublicKey;

use crate::envelope::{EnvelopeFlags, OuterEnvelope};
use crate::error::SignalingError;
use crate::messages::{InnerMessage, InnerMessageBuilder};
use crate::replay::{ReplayCache, ReplayCheck, ReplayStatus};
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

    pub(crate) fn encode_for_peer_with_msg_id(
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

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock is before unix epoch")
        .as_millis() as u64
}
