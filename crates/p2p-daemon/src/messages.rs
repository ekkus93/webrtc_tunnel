//! Pure signal/message construction and decode helpers. These build outgoing
//! `InnerMessage`s (hello, error, duplicate-ack), translate ICE candidate bodies,
//! and decode idle inbound payloads. They hold no daemon runtime state.

use p2p_core::{FailureCode, MsgId, PeerId, SessionId, unix_time_ms};
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
) -> Result<Option<(MsgId, InnerMessage)>, DaemonError> {
    let SignalingError::Protocol(message) = error else {
        return Ok(None);
    };
    if message != "duplicate message detected" {
        return Ok(None);
    }

    let Ok(envelope) = OuterEnvelope::decode(payload) else {
        return Ok(None);
    };
    if !envelope.flags.ack_required {
        return Ok(None);
    }

    let expected_sender_kid = kid_from_signing_key(&remote_authorized.public_identity.sign_public);
    if envelope.sender_kid != expected_sender_kid {
        return Ok(None);
    }

    let ack = codec.build_ack(remote_peer_id.clone(), session_id, envelope.msg_id)?;
    Ok(Some((envelope.msg_id, ack)))
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
) -> Result<InnerMessage, DaemonError> {
    Ok(InnerMessageBuilder::new(session_id, sender_peer_id.clone(), recipient_peer_id.clone())
        .build(MessageBody::Hello(p2p_signaling::HelloBody {
            role: role.to_owned(),
            caps: vec!["trickle_ice".to_owned(), "ice_restart".to_owned()],
        }))?)
}

pub(crate) fn build_error_message(
    sender_peer_id: &PeerId,
    recipient_peer_id: &PeerId,
    session_id: SessionId,
    code: FailureCode,
    message: &str,
) -> Result<InnerMessage, DaemonError> {
    Ok(InnerMessageBuilder::new(session_id, sender_peer_id.clone(), recipient_peer_id.clone())
        .build(MessageBody::Error(ErrorBody {
            code: code.as_str().to_owned(),
            message: message.to_owned(),
            fatal: true,
        }))?)
}

/// Retry timing is correctness-sensitive (FIX7 P0-010-A/P0-010-D): a stale or invented
/// timestamp could corrupt ack-retry deadlines or the transport-failure backoff window, so a
/// clock failure here is propagated as a typed error to the caller rather than degrading to a
/// reused/zero value — unlike the mobile diagnostic-log timestamp (`p2p-mobile`'s `unix_ms`),
/// which may safely degrade since it gates no decision.
pub(crate) fn current_time_ms() -> Result<u64, DaemonError> {
    current_time_ms_from(unix_time_ms)
}

/// FIX7 P0-010-F: injectable clock seam so tests can deterministically exercise a clock failure
/// without mutating the real system clock. `pub(crate)` — only this crate's own tests need it;
/// [`current_time_ms`] is the call site every real caller uses, and always sees the real clock.
pub(crate) fn current_time_ms_from(
    clock: fn() -> Result<u64, std::time::SystemTimeError>,
) -> Result<u64, DaemonError> {
    clock().map_err(DaemonError::Clock)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A genuine `SystemTimeError`, synthesized without touching the real system clock: asking
    /// for the duration since a point strictly in the future always fails this way (FIX7
    /// P0-010-F — "do not mutate system clock in tests").
    fn synthetic_clock_error() -> std::time::SystemTimeError {
        let future = std::time::SystemTime::now() + std::time::Duration::from_secs(3600);
        std::time::SystemTime::now()
            .duration_since(future)
            .expect_err("a point strictly in the future must make duration_since fail")
    }

    fn failing_clock() -> Result<u64, std::time::SystemTimeError> {
        Err(synthetic_clock_error())
    }

    // FIX7 P0-010-G: a retry-timing clock failure must return the typed error, never a
    // reused/zero deadline. Every real caller (ack_tracker registration/retry_due,
    // last_transport_failure_at_ms) reads this value through `current_time_ms()?` as part of
    // constructing its own argument/field, so `?`'s short-circuit means a failing read can never
    // reach the code that would compute or use a retry deadline at all — proven here at the
    // pure clock-mapping level, which is exactly the point `?` is evaluated.
    #[test]
    fn daemon_retry_clock_failure_does_not_use_zero_deadline() {
        let result = current_time_ms_from(failing_clock);

        assert!(
            matches!(result, Err(DaemonError::Clock(_))),
            "expected a typed clock error, never a zero deadline, got {result:?}"
        );
    }
}
