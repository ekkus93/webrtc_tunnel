//! Pure signal/message construction and decode helpers. These build outgoing
//! `InnerMessage`s (hello, error, duplicate-ack), translate ICE candidate bodies,
//! and decode idle inbound payloads. They hold no daemon runtime state.

use std::time::{SystemTime, UNIX_EPOCH};

use p2p_core::{FailureCode, MsgId, PeerId, SessionId};
use p2p_crypto::{AuthorizedKey, kid_from_signing_key};
use p2p_signaling::{
    ErrorBody, IceCandidateBody, InnerMessage, InnerMessageBuilder, MessageBody, OuterEnvelope,
    SignalCodec, SignalingError,
};
use p2p_webrtc::IceCandidateSignal;

use crate::DaemonError;
pub(crate) fn duplicate_active_session_ack_message(
    codec: &SignalCodec<'_>,
    session_id: SessionId,
    remote_authorized: &AuthorizedKey,
    remote_peer_id: &PeerId,
    payload: &[u8],
    error: &SignalingError,
) -> Option<(MsgId, InnerMessage)> {
    let SignalingError::Protocol(message) = error else {
        return None;
    };
    if message != "duplicate message detected" {
        return None;
    }

    let envelope = OuterEnvelope::decode(payload).ok()?;
    if !envelope.flags.ack_required {
        return None;
    }

    let expected_sender_kid = kid_from_signing_key(&remote_authorized.public_identity.sign_public);
    if envelope.sender_kid != expected_sender_kid {
        return None;
    }

    Some((envelope.msg_id, codec.build_ack(remote_peer_id.clone(), session_id, envelope.msg_id)))
}

pub(crate) fn decode_idle_signaling_message<'a>(
    codec: &SignalCodec<'a>,
    payload: &[u8],
    replay_cache: &mut p2p_signaling::ReplayCache,
) -> Result<(p2p_signaling::OuterEnvelope, InnerMessage, AuthorizedKey), DaemonError> {
    Ok(codec.decode(payload, replay_cache, None)?)
}

pub(crate) fn candidate_from_body(body: &IceCandidateBody) -> IceCandidateSignal {
    IceCandidateSignal {
        candidate: body.candidate.clone(),
        sdp_mid: body.sdp_mid.clone(),
        sdp_mline_index: body.sdp_mline_index,
    }
}

pub(crate) fn build_hello_message(
    sender_peer_id: &PeerId,
    recipient_peer_id: &PeerId,
    session_id: SessionId,
    role: &str,
) -> InnerMessage {
    InnerMessageBuilder::new(session_id, sender_peer_id.clone(), recipient_peer_id.clone()).build(
        MessageBody::Hello(p2p_signaling::HelloBody {
            role: role.to_owned(),
            caps: vec!["trickle_ice".to_owned(), "ice_restart".to_owned()],
        }),
    )
}

pub(crate) fn build_error_message(
    sender_peer_id: &PeerId,
    recipient_peer_id: &PeerId,
    session_id: SessionId,
    code: FailureCode,
    message: &str,
) -> InnerMessage {
    InnerMessageBuilder::new(session_id, sender_peer_id.clone(), recipient_peer_id.clone()).build(
        MessageBody::Error(ErrorBody {
            code: code.as_str().to_owned(),
            message: message.to_owned(),
            fatal: true,
        }),
    )
}

pub(crate) fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time is before unix epoch")
        .as_millis() as u64
}
