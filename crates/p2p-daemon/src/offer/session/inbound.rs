//! Inbound signaling dispatch for an active offer session: decoding/verifying
//! session-scoped payloads, acking when required, applying remote answers/ICE
//! candidates to the peer, and re-acking duplicate active-session messages so a
//! lost ack does not stall the remote.

use p2p_crypto::AuthorizedKey;
use p2p_signaling::{
    AckBody, AnswerBody, InnerMessage, MessageBody, OuterEnvelope, SignalCodec, SignalingError,
};

use crate::DaemonError;
use crate::messages::*;
use crate::signaling::*;
use crate::types::*;

pub(crate) async fn handle_offer_session_message(
    message: &InnerMessage,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    match &message.body {
        MessageBody::Ack(AckBody { ack_msg_id }) => {
            session.signaling.ack_tracker.acknowledge(&p2p_core::MsgId::new(*ack_msg_id));
        }
        MessageBody::Answer(AnswerBody { sdp }) => {
            session.peer.apply_remote_answer(sdp).await?;
        }
        MessageBody::IceCandidate(body) => {
            session.peer.add_remote_candidate(candidate_from_body(body)).await?;
        }
        MessageBody::EndOfCandidates(_) => {}
        MessageBody::Close(body) => {
            return Err(DaemonError::RemoteClosed(body.reason_code.clone()));
        }
        MessageBody::Error(body) => {
            return Err(DaemonError::RemoteError(body.code.clone(), body.message.clone()));
        }
        _ => {
            tracing::warn!("ignoring unexpected message {:?}", message.message_type);
        }
    }
    Ok(())
}

pub(crate) async fn process_offer_session_payload<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    remote: &AuthorizedKey,
    session: &mut ActiveSession,
    payload: &[u8],
) -> Result<OfferSessionPayloadOutcome, DaemonError> {
    let (envelope, message, sender) = match codec.decode(
        payload,
        &mut session.signaling.replay_cache,
        Some(session.session_id),
    ) {
        Ok(decoded) => decoded,
        Err(error) => {
            if maybe_ack_duplicate_active_session_message(
                ctx, codec, transport, session, payload, &error,
            )
            .await?
            {
                return Ok(OfferSessionPayloadOutcome::Ignored);
            }
            tracing::warn!(
                reason = %error,
                session_id = %session.session_id,
                "rejecting signaling message during active offer session"
            );
            return Ok(OfferSessionPayloadOutcome::Ignored);
        }
    };
    if sender.peer_id != session.remote_peer_id {
        tracing::warn!(
            peer_id = %sender.peer_id,
            expected_peer_id = %session.remote_peer_id,
            "ignoring message from unexpected peer"
        );
        return Ok(OfferSessionPayloadOutcome::Ignored);
    }
    if message.message_type.requires_ack() {
        publish_message(
            ctx,
            codec,
            transport,
            StatusSnapshot {
                active_session_id: Some(session.session_id),
                current_state: session.state,
            },
            None,
            remote,
            OutgoingSignal {
                message: codec.build_ack(
                    remote.peer_id.clone(),
                    session.session_id,
                    envelope.msg_id,
                ),
                response: true,
            },
        )
        .await?;
    }
    handle_offer_session_message(&message, session).await?;
    Ok(OfferSessionPayloadOutcome::Handled)
}

pub(crate) async fn maybe_ack_duplicate_active_session_message<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    payload: &[u8],
    error: &SignalingError,
) -> Result<bool, DaemonError> {
    let Some((duplicate_msg_id, ack_message)) = duplicate_active_session_ack_message(
        codec,
        session.session_id,
        &session.remote_authorized,
        &session.remote_peer_id,
        payload,
        error,
    ) else {
        return Ok(false);
    };

    if !session.duplicate_active_acks.record_if_new(duplicate_msg_id) {
        tracing::info!(
            session_id = %session.session_id,
            duplicate_msg_id = %duplicate_msg_id,
            role = ?ctx.config.node.role,
            "suppressing repeated duplicate active-session re-ack"
        );
        return Ok(true);
    }

    let envelope = OuterEnvelope::decode(payload)
        .map_err(|error| DaemonError::Signaling(SignalingError::Protocol(error.to_string())))?;

    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: session.state,
        },
        None,
        &session.remote_authorized,
        OutgoingSignal { message: ack_message, response: true },
    )
    .await?;

    tracing::info!(
        session_id = %session.session_id,
        duplicate_msg_id = %envelope.msg_id,
        role = ?ctx.config.node.role,
        "re-acknowledged duplicate active-session signaling message"
    );
    Ok(true)
}
