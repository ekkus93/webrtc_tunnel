//! Shared signaling I/O and status-writing layer used by both daemon roles.
//!
//! Wraps the [`DaemonSignalingTransport`] poll/publish calls with truthful local
//! status updates (transport usable/unusable transitions), encodes and publishes
//! outgoing messages, drives ack retries, and bridges answer-session publish
//! requests through the session event channel.

use std::collections::HashMap;

use p2p_core::{DaemonState, PeerId, SessionId};
use p2p_crypto::AuthorizedKey;
use p2p_signaling::{
    EndOfCandidatesBody, IceCandidateBody, InnerMessageBuilder, MessageBody, SignalCodec,
    SignalingError, SignalingSession,
};
use p2p_webrtc::IceCandidateSignal;
use tokio::sync::{mpsc, oneshot};
use tokio::time::sleep;

use crate::DaemonError;
use crate::config::steady_state_for_role;
use crate::messages::current_time_ms;
use crate::status::{DaemonStatus, ForwardRuntimeStatus, StatusWriter};
use crate::types::{
    ANSWER_SESSION_CAPACITY, ActiveSession, AnswerSessionEvent, AnswerSessionHandle,
    AnswerStatusSnapshot, DAEMON_RUNTIME_RETRY_DELAY, DaemonSignalingTransport, OutgoingSignal,
    PublishRequest, PublishedSignal, RuntimeContext, SessionStatusSnapshot, StatusSnapshot,
};

/// Ordinary (non-terminal) status is only truthful while the daemon is fully
/// `Running` and the shared shutdown token has not been requested: before
/// `Running`, startup has not actually finished; once shutdown is requested
/// (even if the daemon loop hasn't yet locally observed it and moved to
/// `Draining`), it must not resurrect a "serving"/"waiting" status. Terminal
/// `Closed` writes intentionally bypass this gate (see
/// `write_daemon_status_unchecked`/`write_answer_status_unchecked`).
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    ctx.runtime.normal_status_allowed()
}

pub(crate) async fn write_daemon_status(ctx: &RuntimeContext<'_>, snapshot: StatusSnapshot) {
    if !runtime_status_allowed(ctx) {
        tracing::debug!(
            phase = ?ctx.runtime.phase,
            state = ?snapshot.current_state,
            "suppressing nonterminal status outside Running phase",
        );
        return;
    }
    write_daemon_status_unchecked(ctx, snapshot).await;
}

/// Pair the active session id with the real remote peer (the offer's configured
/// `[peer].remote_peer_id`). If the remote is somehow unknown (no `[peer]`), report
/// no session rather than fabricating a self-targeted one.
fn build_daemon_status(ctx: &RuntimeContext<'_>, snapshot: StatusSnapshot) -> DaemonStatus {
    let active_session = snapshot
        .active_session_id
        .and_then(|id| ctx.config.peer.as_ref().map(|peer| (id, peer.remote_peer_id.clone())));
    DaemonStatus::new(
        ctx.config.node.peer_id.clone(),
        ctx.config.node.role.clone(),
        ctx.runtime.mqtt_connected,
        active_session,
        snapshot.current_state,
        ctx.config.forwards.iter().map(|forward| forward.id.clone()).collect(),
    )
    .with_forward_statuses(ctx.runtime.forward_statuses.clone())
}

async fn write_daemon_status_unchecked(ctx: &RuntimeContext<'_>, snapshot: StatusSnapshot) {
    write_status_or_log(ctx.status, build_daemon_status(ctx, snapshot)).await;
}

pub(crate) async fn write_answer_status(ctx: &RuntimeContext<'_>, snapshot: AnswerStatusSnapshot) {
    if !runtime_status_allowed(ctx) {
        tracing::debug!(
            phase = ?ctx.runtime.phase,
            state = ?snapshot.current_state,
            "suppressing nonterminal answer status outside Running phase",
        );
        return;
    }
    write_answer_status_unchecked(ctx, snapshot).await;
}

fn build_answer_status(ctx: &RuntimeContext<'_>, snapshot: AnswerStatusSnapshot) -> DaemonStatus {
    DaemonStatus::with_sessions(
        ctx.config.node.peer_id.clone(),
        ctx.config.node.role.clone(),
        ctx.runtime.mqtt_connected,
        snapshot.current_state,
        ctx.config.forwards.iter().map(|forward| forward.id.clone()).collect(),
        ANSWER_SESSION_CAPACITY,
        snapshot.sessions.iter().map(SessionStatusSnapshot::to_status).collect(),
    )
    .with_forward_statuses(ctx.runtime.forward_statuses.clone())
}

async fn write_answer_status_unchecked(ctx: &RuntimeContext<'_>, snapshot: AnswerStatusSnapshot) {
    write_status_or_log(ctx.status, build_answer_status(ctx, snapshot)).await;
}

pub(crate) async fn write_answer_registry_status(
    ctx: &RuntimeContext<'_>,
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
) {
    let mut session_statuses =
        sessions.values().map(|session| session.status.clone()).collect::<Vec<_>>();
    session_statuses.sort_by_key(|status| status.session_id.to_string());
    let current_state = DaemonState::Serving;
    write_answer_status(ctx, AnswerStatusSnapshot { current_state, sessions: session_statuses })
        .await;
}

/// Truthful terminal answer status: the session registry has fully drained and the
/// daemon is about to return. Unlike [`write_answer_registry_status`], this does not
/// hardcode `Serving` — the daemon is no longer serving anything.
///
/// Unlike ordinary status writes (best-effort, logged on failure), this is strict:
/// the daemon's caller must know whether the terminal state was actually
/// persisted, so a write failure here is returned rather than swallowed.
pub(crate) async fn write_answer_closed_status(
    ctx: &mut RuntimeContext<'_>,
) -> Result<(), DaemonError> {
    ctx.runtime.mqtt_connected = false;
    let status = build_answer_status(
        ctx,
        AnswerStatusSnapshot { current_state: DaemonState::Closed, sessions: Vec::new() },
    );
    ctx.status.write(status).await
}

pub(crate) async fn write_steady_state_status(ctx: &RuntimeContext<'_>) {
    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: None,
            current_state: steady_state_for_role(&ctx.config.node.role),
        },
    )
    .await;
}

/// Truthful terminal offer status: listener tasks have been stopped/joined and any
/// active session has been cleaned up. Every configured offer forward is reported
/// `Stopped`; a forward's pre-existing `last_error` (e.g. it never bound) is kept
/// rather than erased, since `Stopped` answers "is this running now?" while
/// `last_error` answers "what most recently went wrong?" — shutting down doesn't
/// change the answer to the second question.
///
/// Unlike ordinary status writes (best-effort, logged on failure), this is strict:
/// the daemon's caller must know whether the terminal state was actually
/// persisted, so a write failure here is returned rather than swallowed.
pub(crate) async fn write_offer_closed_status(
    ctx: &mut RuntimeContext<'_>,
) -> Result<(), DaemonError> {
    ctx.runtime.mqtt_connected = false;
    ctx.runtime.forward_statuses = ctx
        .runtime
        .forward_statuses
        .iter()
        .map(ForwardRuntimeStatus::stopped_preserving_error)
        .collect();
    let status = build_daemon_status(
        ctx,
        StatusSnapshot { active_session_id: None, current_state: DaemonState::Closed },
    );
    ctx.status.write(status).await
}

pub(crate) async fn recover_daemon_after_session(
    ctx: &RuntimeContext<'_>,
    result: Result<(), DaemonError>,
) {
    write_steady_state_status(ctx).await;
    if let Err(error) = result {
        tracing::warn!(
            reason = %error,
            role = ?ctx.config.node.role,
            "daemon recovered from session failure"
        );
    }
}

pub(crate) async fn write_status_or_log(status: &StatusWriter, daemon_status: DaemonStatus) {
    if let Err(error) = status.write(daemon_status).await {
        tracing::warn!(reason = %error, "status write failed; continuing without status update");
    }
}

pub(crate) async fn mark_transport_unusable(
    ctx: &mut RuntimeContext<'_>,
    snapshot: StatusSnapshot,
    error: &SignalingError,
) {
    ctx.runtime.mqtt_connected = false;
    // FIX7 P0-010-A: this internal backoff-suppression bookkeeping (not a wire-protocol
    // timestamp, not verified by any peer) is diagnostics-only — a clock failure degrades to
    // `None` ("failure time unknown") rather than propagating a hard error through this
    // non-`Result` status-transition helper. `None` here is safe, not stale: it simply means
    // `mark_transport_usable_after_publish` cannot apply its suppression window and lets
    // recovery proceed immediately, which is the conservative direction to fail in.
    ctx.runtime.last_transport_failure_at_ms = current_time_ms().ok();
    write_daemon_status(ctx, snapshot).await;
    tracing::warn!(
        reason = %error,
        role = ?ctx.config.node.role,
        state = ?snapshot.current_state,
        session_id = snapshot.active_session_id.as_ref().map(ToString::to_string),
        "signaling transport is currently unusable"
    );
}

pub(crate) async fn mark_transport_usable(ctx: &mut RuntimeContext<'_>, snapshot: StatusSnapshot) {
    if ctx.runtime.mqtt_connected {
        return;
    }
    ctx.runtime.mqtt_connected = true;
    ctx.runtime.last_transport_failure_at_ms = None;
    write_daemon_status(ctx, snapshot).await;
    tracing::info!(
        role = ?ctx.config.node.role,
        state = ?snapshot.current_state,
        session_id = snapshot.active_session_id.as_ref().map(ToString::to_string),
        "signaling transport recovered"
    );
}

pub(crate) async fn mark_transport_usable_after_publish(
    ctx: &mut RuntimeContext<'_>,
    snapshot: StatusSnapshot,
) {
    // A clock failure while checking elapsed time degrades the same way as above: treat the
    // suppression window as not (verifiably) in effect and let recovery proceed.
    if ctx.runtime.last_transport_failure_at_ms.is_some_and(|failure_at| {
        current_time_ms().ok().is_some_and(|now| {
            now.saturating_sub(failure_at) < DAEMON_RUNTIME_RETRY_DELAY.as_millis() as u64
        })
    }) {
        return;
    }
    mark_transport_usable(ctx, snapshot).await;
}

pub(crate) async fn poll_session_signal_payload<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    transport: &mut T,
    snapshot: StatusSnapshot,
) -> Result<Option<Vec<u8>>, DaemonError> {
    match transport.poll_signal_payload().await {
        Ok(payload) => {
            mark_transport_usable(ctx, snapshot).await;
            Ok(payload)
        }
        Err(error) => {
            mark_transport_unusable(ctx, snapshot, &error).await;
            Err(error.into())
        }
    }
}

pub(crate) async fn poll_idle_signal_payload<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    transport: &mut T,
) -> Option<Vec<u8>> {
    match poll_session_signal_payload(
        ctx,
        transport,
        StatusSnapshot {
            active_session_id: None,
            current_state: steady_state_for_role(&ctx.config.node.role),
        },
    )
    .await
    {
        Ok(payload) => payload,
        Err(error) => {
            tracing::warn!(
                reason = %error,
                role = ?ctx.config.node.role,
                "recoverable signaling transport error while idle; backing off before retry"
            );
            sleep(DAEMON_RUNTIME_RETRY_DELAY).await;
            None
        }
    }
}

pub(crate) async fn send_local_candidate<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    candidate: IceCandidateSignal,
) -> Result<(), DaemonError> {
    let body = if let Some(candidate_line) = candidate.candidate {
        tracing::debug!(
            target: "ice",
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            candidate = %crate::candidate_log_summary(&ctx.config.logging, &candidate_line),
            "gathered local ICE candidate",
        );
        MessageBody::IceCandidate(IceCandidateBody {
            candidate: Some(candidate_line),
            sdp_mid: candidate.sdp_mid,
            sdp_mline_index: candidate.sdp_mline_index,
        })
    } else {
        tracing::debug!(
            target: "ice",
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            "local ICE gathering complete (end-of-candidates)",
        );
        MessageBody::EndOfCandidates(EndOfCandidatesBody::default())
    };

    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: session.state,
        },
        Some(&mut session.signaling),
        remote,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                ctx.config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(body)?,
            response: false,
        },
    )
    .await
}

pub(crate) async fn publish_message<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    snapshot: StatusSnapshot,
    signaling: Option<&mut SignalingSession>,
    recipient: &AuthorizedKey,
    outgoing: OutgoingSignal,
) -> Result<(), DaemonError> {
    let message_type = outgoing.message.message_type;
    let session_id = outgoing.message.session_id;
    let recipient_peer_id = recipient.peer_id.clone();
    let (envelope, payload) =
        codec.encode_for_peer(recipient, &outgoing.message, outgoing.response)?;
    tracing::debug!(
        session_id = %session_id,
        recipient_peer_id = %recipient_peer_id,
        sender_kid = %envelope.sender_kid,
        recipient_kid = %envelope.recipient_kid,
        msg_id = %envelope.msg_id,
        message_type = ?message_type,
        payload_len = payload.len(),
        response = outgoing.response,
        "publishing signaling message"
    );
    match transport
        .publish_signal(&recipient.peer_id, &ctx.config.broker.topic_prefix, payload.clone())
        .await
    {
        Ok(()) => {
            tracing::debug!(
                session_id = %session_id,
                recipient_peer_id = %recipient_peer_id,
                msg_id = %envelope.msg_id,
                message_type = ?message_type,
                "published signaling message"
            );
            mark_transport_usable(ctx, snapshot).await;
        }
        Err(error) => {
            mark_transport_unusable(ctx, snapshot, &error).await;
            return Err(error.into());
        }
    }
    if let Some(signaling) = signaling {
        signaling.ack_tracker.register(
            envelope.msg_id,
            outgoing.message.message_type,
            payload,
            current_time_ms()?,
        );
    }
    Ok(())
}

pub(crate) async fn publish_answer_session_request<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    request: PublishRequest,
) {
    let message_type = request.outgoing.message.message_type;
    let session_id = request.outgoing.message.session_id;
    let recipient_peer_id = request.recipient.peer_id.clone();
    let encoded = codec.encode_for_peer(
        &request.recipient,
        &request.outgoing.message,
        request.outgoing.response,
    );
    let result = match encoded {
        Ok((envelope, payload)) => {
            tracing::debug!(
                session_id = %session_id,
                recipient_peer_id = %recipient_peer_id,
                sender_kid = %envelope.sender_kid,
                recipient_kid = %envelope.recipient_kid,
                msg_id = %envelope.msg_id,
                message_type = ?message_type,
                payload_len = payload.len(),
                response = request.outgoing.response,
                "publishing answer-session signaling message"
            );
            match transport
                .publish_signal(
                    &recipient_peer_id,
                    &ctx.config.broker.topic_prefix,
                    payload.clone(),
                )
                .await
            {
                Ok(()) => {
                    mark_transport_usable_after_publish(
                        ctx,
                        StatusSnapshot {
                            active_session_id: Some(request.status.session_id),
                            current_state: request.status.state,
                        },
                    )
                    .await;
                    Ok(PublishedSignal { msg_id: envelope.msg_id, message_type, payload })
                }
                Err(error) => {
                    mark_transport_unusable(
                        ctx,
                        StatusSnapshot {
                            active_session_id: Some(request.status.session_id),
                            current_state: request.status.state,
                        },
                        &error,
                    )
                    .await;
                    Err(error.into())
                }
            }
        }
        Err(error) => Err(error.into()),
    };
    // A transport failure is already logged by mark_transport_unusable above; a failed
    // send here just means the caller stopped waiting on this reply.
    let _ = request.result.send(result);
}

pub(crate) async fn request_session_publish(
    tx: &mpsc::Sender<AnswerSessionEvent>,
    recipient: AuthorizedKey,
    outgoing: OutgoingSignal,
    track_ack: bool,
    status: SessionStatusSnapshot,
) -> Result<Option<PublishedSignal>, DaemonError> {
    let (result_tx, result_rx) = oneshot::channel();
    tx.send(AnswerSessionEvent::Publish(Box::new(PublishRequest {
        recipient,
        outgoing,
        status,
        result: result_tx,
    })))
    .await
    .map_err(|_| DaemonError::Logging("answer session event loop stopped".to_owned()))?;
    let published = result_rx.await.map_err(|_| {
        DaemonError::Logging("answer session publish response dropped".to_owned())
    })??;
    Ok(track_ack.then_some(published))
}

pub(crate) async fn request_raw_session_publish(
    tx: &mpsc::Sender<AnswerSessionEvent>,
    peer_id: PeerId,
    payload: Vec<u8>,
    status: SessionStatusSnapshot,
) -> Result<(), DaemonError> {
    let (result_tx, result_rx) = oneshot::channel();
    tx.send(AnswerSessionEvent::RawPublish { peer_id, payload, status, result: result_tx })
        .await
        .map_err(|_| DaemonError::Logging("answer session event loop stopped".to_owned()))?;
    result_rx
        .await
        .map_err(|_| DaemonError::Logging("answer raw publish response dropped".to_owned()))?
}

pub(crate) async fn retry_pending_acks<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    transport: &mut T,
    snapshot: StatusSnapshot,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    let mut retries = session.signaling.ack_tracker.retry_due(current_time_ms()?);
    while let Some((_msg_id, payload)) = retries.pop() {
        match transport
            .publish_signal(&session.remote_peer_id, &ctx.config.broker.topic_prefix, payload)
            .await
        {
            Ok(()) => mark_transport_usable(ctx, snapshot).await,
            Err(error) => {
                mark_transport_unusable(ctx, snapshot, &error).await;
                return Err(error.into());
            }
        }
    }
    Ok(())
}
