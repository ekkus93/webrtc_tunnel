//! Incoming signaling payload handling for the answer daemon: routes an
//! authenticated message to its owning session, admits/rejects a fresh offer
//! (busy reply vs. spawning a new session), and dispatches session events
//! (publish requests, status updates, same-peer replacement) back out to the
//! transport/registry.

use std::collections::HashMap;
use std::sync::Arc;

use p2p_core::{AppConfig, DaemonState, FailureCode, PeerId, SessionId};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile};
use p2p_signaling::{
    AnswerBody, DecodedSignal, InnerMessage, InnerMessageBuilder, MessageBody, OfferBody,
    OuterEnvelope, ReplayStatus, SignalCodec,
};
use p2p_webrtc::WebRtcPeer;
use tokio::sync::mpsc;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::busy::*;
use crate::messages::*;
use crate::predicates::*;
use crate::signaling::*;
use crate::types::*;

use super::session::run_answer_session_task;
use super::test_support::AnswerSessionPanicArm;

pub(crate) struct AnswerDeps<'a> {
    pub(crate) config: &'a Arc<AppConfig>,
    pub(crate) local_identity: &'a Arc<IdentityFile>,
    pub(crate) authorized_keys: &'a Arc<AuthorizedKeys>,
    pub(crate) event_tx: &'a mpsc::Sender<AnswerSessionEvent>,
    pub(crate) shutdown: &'a ShutdownToken,
}

pub(crate) struct AnswerSessionRegistry<'a> {
    pub(crate) replay_cache: &'a mut p2p_signaling::ReplayCache,
    pub(crate) sessions_by_id: &'a mut HashMap<SessionId, AnswerSessionHandle>,
    pub(crate) session_by_peer: &'a mut HashMap<PeerId, SessionId>,
    pub(crate) session_completions: &'a mut AnswerSessionCompletions,
    pub(crate) next_generation: &'a mut u64,
    /// Taken (if present) by the next session admitted from an incoming offer and
    /// handed to that session's real spawned task, so it can be made to panic on
    /// command (P0-009).
    pub(crate) session_panic_trigger: &'a mut Option<AnswerSessionPanicArm>,
}

pub(crate) struct IncomingOffer<'a> {
    pub(crate) envelope: OuterEnvelope,
    pub(crate) message: InnerMessage,
    pub(crate) sender: AuthorizedKey,
    pub(crate) offer: &'a OfferBody,
}

pub(crate) async fn handle_answer_daemon_payload<T: DaemonSignalingTransport>(
    deps: &AnswerDeps<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    registry: &mut AnswerSessionRegistry<'_>,
    payload: Vec<u8>,
) {
    let &AnswerDeps { config, .. } = deps;
    tracing::debug!(
        payload_len = payload.len(),
        role = ?config.node.role,
        "received signaling payload in answer daemon"
    );

    let decoded = match codec.decode_with_replay_status(&payload, registry.replay_cache, None) {
        Ok(decoded) => decoded,
        Err(error) => {
            tracing::warn!(reason = %error, "rejecting signaling message");
            return;
        }
    };

    tracing::debug!(
        session_id = %decoded.message.session_id,
        sender_peer_id = %decoded.sender.peer_id,
        sender_kid = %decoded.envelope.sender_kid,
        message_type = ?decoded.message.message_type,
        replay_status = ?decoded.replay_status,
        role = ?config.node.role,
        "decoded answer-daemon signaling message"
    );

    if let Some(handle) = registry.sessions_by_id.get(&decoded.message.session_id) {
        if handle.remote_peer_id != decoded.sender.peer_id {
            tracing::warn!(
                session_id = %decoded.message.session_id,
                sender_peer_id = %decoded.sender.peer_id,
                expected_peer_id = %handle.remote_peer_id,
                "ignoring signaling message whose authenticated sender does not own the session"
            );
            return;
        }
        route_authenticated_signal(handle, decoded).await;
        return;
    }

    if matches!(decoded.message.body, MessageBody::Offer(_))
        && let Some(existing_session_id) =
            registry.session_by_peer.get(&decoded.sender.peer_id).copied()
        && let Some(handle) = registry.sessions_by_id.get(&existing_session_id)
    {
        route_authenticated_signal(handle, decoded).await;
        return;
    }

    match &decoded.message.body {
        MessageBody::Hello(_) => {
            tracing::info!("received optional hello from {}", decoded.sender.peer_id);
        }
        MessageBody::Offer(offer) => {
            let offer = offer.clone();
            if decoded.replay_status != ReplayStatus::Fresh {
                tracing::info!(
                    session_id = %decoded.message.session_id,
                    sender_peer_id = %decoded.sender.peer_id,
                    "ignoring replayed offer for unknown session"
                );
                return;
            }
            if !is_peer_allowed_for_active_busy_reply(config, &decoded.sender.peer_id) {
                tracing::warn!(peer_id = %decoded.sender.peer_id, "rejecting unauthorized peer");
                return;
            }
            if registry.session_by_peer.contains_key(&decoded.sender.peer_id)
                || registry.sessions_by_id.len() >= ANSWER_SESSION_CAPACITY
            {
                let busy_message = match build_error_message(
                    &config.node.peer_id,
                    &decoded.sender.peer_id,
                    decoded.message.session_id,
                    FailureCode::Busy,
                    "answer daemon session capacity reached",
                ) {
                    Ok(message) => message,
                    // FIX7 P0-010-D: a clock failure here only skips this one best-effort
                    // rejection (matching the existing failed-to-publish handling just below,
                    // not a daemon-fatal outcome) rather than inventing a timestamp.
                    Err(error) => {
                        tracing::warn!(
                            reason = %error,
                            session_id = %decoded.message.session_id,
                            sender_peer_id = %decoded.sender.peer_id,
                            "failed to build best-effort busy rejection message",
                        );
                        return;
                    }
                };
                if let Err(error) = publish_message(
                    ctx,
                    codec,
                    transport,
                    StatusSnapshot {
                        active_session_id: Some(decoded.message.session_id),
                        current_state: DaemonState::ConnectingDataChannel,
                    },
                    None,
                    &decoded.sender,
                    OutgoingSignal { message: busy_message, response: true },
                )
                .await
                {
                    tracing::warn!(
                        reason = %error,
                        session_id = %decoded.message.session_id,
                        sender_peer_id = %decoded.sender.peer_id,
                        "failed to publish best-effort busy rejection",
                    );
                }
                return;
            }
            let generation = SessionGeneration(*registry.next_generation);
            *registry.next_generation = registry.next_generation.saturating_add(1);
            if let Err(error) = start_answer_session_from_offer(
                deps,
                codec,
                transport,
                ctx,
                registry,
                generation,
                IncomingOffer {
                    envelope: decoded.envelope,
                    message: decoded.message,
                    sender: decoded.sender,
                    offer: &offer,
                },
            )
            .await
            {
                recover_daemon_after_session(ctx, Err(error)).await;
            }
            write_answer_registry_status(ctx, registry.sessions_by_id).await;
        }
        _ => {
            tracing::warn!(
                "ignoring unexpected answer-daemon message {:?}",
                decoded.message.message_type
            );
        }
    }
}

async fn route_authenticated_signal(handle: &AnswerSessionHandle, decoded: DecodedSignal) {
    if let Err(error) = handle.inbound.send(decoded).await {
        tracing::warn!(
            reason = %error,
            session_id = %handle.status.session_id,
            peer_id = %handle.remote_peer_id,
            "failed to route authenticated signaling message to answer session"
        );
    }
}

async fn start_answer_session_from_offer<T: DaemonSignalingTransport>(
    deps: &AnswerDeps<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    registry: &mut AnswerSessionRegistry<'_>,
    generation: SessionGeneration,
    incoming: IncomingOffer<'_>,
) -> Result<(), DaemonError> {
    let &AnswerDeps { config, local_identity, authorized_keys, event_tx, shutdown } = deps;
    let IncomingOffer { envelope, message, sender, offer } = incoming;
    if should_ack_idle_offer(true, message.message_type.requires_ack()) {
        publish_message(
            ctx,
            codec,
            transport,
            StatusSnapshot {
                active_session_id: Some(message.session_id),
                current_state: DaemonState::Negotiating,
            },
            None,
            &sender,
            OutgoingSignal {
                message: codec.build_ack(
                    sender.peer_id.clone(),
                    message.session_id,
                    envelope.msg_id,
                )?,
                response: true,
            },
        )
        .await?;
    }

    let peer = WebRtcPeer::new(&config.webrtc).await?;
    peer.apply_remote_offer(&offer.sdp).await?;
    let mut session = ActiveSession::new(
        message.session_id,
        sender.clone(),
        peer,
        config.security.replay_cache_size,
    );
    let answer_sdp = session.peer.create_answer().await?;
    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
        Some(&mut session.signaling),
        &session.remote_authorized,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp }))?,
            response: false,
        },
    )
    .await?;

    session.state = DaemonState::ConnectingDataChannel;
    let (inbound_tx, inbound_rx) = mpsc::channel(128);
    let status = SessionStatusSnapshot::from_session(config, &session, generation);
    let session_id = session.session_id;
    let remote_peer_id = session.remote_peer_id.clone();
    let session_panic_trigger = registry.session_panic_trigger.take();
    let task = tokio::spawn(run_answer_session_task(
        AnswerSessionTaskDeps {
            config: Arc::clone(config),
            local_identity: Arc::clone(local_identity),
            authorized_keys: Arc::clone(authorized_keys),
            event_tx: event_tx.clone(),
        },
        inbound_rx,
        generation,
        session,
        shutdown.clone(),
        session_panic_trigger,
    ));
    let completion_remote_peer_id = remote_peer_id.clone();
    registry.session_completions.push(Box::pin(async move {
        let outcome = task.await.map_err(|error| error.to_string());
        AnswerTaskCompletion {
            initial_session_id: session_id,
            generation,
            remote_peer_id: completion_remote_peer_id,
            outcome,
        }
    }));
    registry.sessions_by_id.insert(
        session_id,
        AnswerSessionHandle {
            generation,
            remote_peer_id: remote_peer_id.clone(),
            inbound: inbound_tx,
            status,
        },
    );
    registry.session_by_peer.insert(remote_peer_id, session_id);
    Ok(())
}

pub(crate) async fn handle_answer_session_event<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    sessions_by_id: &mut HashMap<SessionId, AnswerSessionHandle>,
    session_by_peer: &mut HashMap<PeerId, SessionId>,
    event: AnswerSessionEvent,
) {
    match event {
        AnswerSessionEvent::Publish(request) => {
            publish_answer_session_request(ctx, codec, transport, *request).await;
        }
        AnswerSessionEvent::RawPublish { peer_id, payload, status, result } => {
            let publish_result = match transport
                .publish_signal(&peer_id, &ctx.config.broker.topic_prefix, payload)
                .await
            {
                Ok(()) => {
                    mark_transport_usable_after_publish(
                        ctx,
                        StatusSnapshot {
                            active_session_id: Some(status.session_id),
                            current_state: status.state,
                        },
                    )
                    .await;
                    Ok(())
                }
                Err(error) => {
                    mark_transport_unusable(
                        ctx,
                        StatusSnapshot {
                            active_session_id: Some(status.session_id),
                            current_state: status.state,
                        },
                        &error,
                    )
                    .await;
                    Err(error.into())
                }
            };
            // Already logged by mark_transport_unusable/usable above; a failed send here
            // just means the caller stopped waiting (e.g. its session already ended).
            let _ = result.send(publish_result);
        }
        AnswerSessionEvent::Status(status) => {
            if let Some(handle) = sessions_by_id.get_mut(&status.session_id) {
                if handle.generation == status.generation {
                    handle.status = status;
                } else {
                    tracing::warn!(
                        session_id = %status.session_id,
                        "ignoring stale answer-session status event"
                    );
                }
            }
            write_answer_registry_status(ctx, sessions_by_id).await;
        }
        AnswerSessionEvent::Replaced {
            old_session_id,
            new_session_id,
            remote_peer_id,
            generation,
            status,
        } => {
            if let Some(mut handle) = sessions_by_id.remove(&old_session_id) {
                if handle.generation == generation && handle.remote_peer_id == remote_peer_id {
                    session_by_peer.insert(remote_peer_id.clone(), new_session_id);
                    handle.status = status;
                    sessions_by_id.insert(new_session_id, handle);
                } else {
                    sessions_by_id.insert(old_session_id, handle);
                    tracing::warn!(
                        old_session_id = %old_session_id,
                        new_session_id = %new_session_id,
                        "ignoring stale answer-session replacement event"
                    );
                }
            }
            write_answer_registry_status(ctx, sessions_by_id).await;
        }
    }
}

#[cfg(test)]
pub(crate) async fn maybe_replace_pending_answer_session<T: DaemonSignalingTransport>(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    session: &mut ActiveSession,
    payload: &[u8],
) -> Result<bool, DaemonError> {
    if session.bridge_state != BridgeSessionState::Pending {
        return Ok(false);
    }

    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);
    let Ok((envelope, message, sender)) = codec.decode(payload, &mut replay_cache, None) else {
        return Ok(false);
    };

    let MessageBody::Offer(offer) = &message.body else {
        return Ok(false);
    };

    if message.session_id == session.session_id || sender.peer_id != session.remote_peer_id {
        return Ok(false);
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
            &sender,
            OutgoingSignal {
                message: codec.build_ack(
                    sender.peer_id.clone(),
                    message.session_id,
                    envelope.msg_id,
                )?,
                response: true,
            },
        )
        .await?;
    }

    if let Some(handle) = session.bridge_handle.take() {
        handle.abort();
        match handle.await {
            Ok(Ok(())) => {}
            Ok(Err(error)) => {
                tracing::warn!(
                    reason = %error,
                    session_id = %session.session_id,
                    "bridge task ended with an error while superseding session with a new offer"
                );
            }
            Err(error) if error.is_cancelled() => {}
            Err(error) => {
                tracing::warn!(
                    reason = %error,
                    session_id = %session.session_id,
                    "aborted bridge task failed unexpectedly while superseding session with a new offer"
                );
            }
        }
    }
    session.data_channel = None;
    if let Err(error) = session.peer.close().await {
        tracing::warn!(
            reason = %error,
            session_id = %session.session_id,
            "failed to close superseded session's peer connection"
        );
    }

    let peer = WebRtcPeer::new(&config.webrtc).await?;
    peer.apply_remote_offer(&offer.sdp).await?;
    let mut replacement = ActiveSession::new(
        message.session_id,
        sender.clone(),
        peer,
        config.security.replay_cache_size,
    );
    let answer_sdp = replacement.peer.create_answer().await?;
    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(replacement.session_id),
            current_state: DaemonState::Negotiating,
        },
        Some(&mut replacement.signaling),
        &replacement.remote_authorized,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                replacement.session_id,
                config.node.peer_id.clone(),
                replacement.remote_peer_id.clone(),
            )
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp }))?,
            response: false,
        },
    )
    .await?;
    replacement.state = DaemonState::ConnectingDataChannel;
    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: Some(replacement.session_id),
            current_state: replacement.state,
        },
    )
    .await;
    *session = replacement;

    Ok(true)
}
