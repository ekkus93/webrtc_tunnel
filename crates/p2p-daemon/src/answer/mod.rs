//! Answer-role daemon: serves multiple authorized peers concurrently. Accepts
//! inbound offers, spawns per-peer answer sessions, routes authenticated signals
//! to the owning session, and keeps local status truthful across session churn.

use std::collections::HashMap;
use std::sync::Arc;

use futures_util::StreamExt;
use p2p_core::{AppConfig, DaemonState, FailureCode, PeerId, SessionId};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile};
use p2p_signaling::{
    AnswerBody, DecodedSignal, InnerMessage, InnerMessageBuilder, MessageBody,
    MqttSignalingTransport, OfferBody, OuterEnvelope, ReplayStatus, SignalCodec,
};
use p2p_webrtc::WebRtcPeer;
use tokio::sync::mpsc;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::busy::*;
use crate::config::*;
use crate::messages::*;
use crate::predicates::*;
use crate::signaling::*;
use crate::status::*;
use crate::types::*;

mod session;

use session::run_answer_session_task;

// Session helpers the daemon unit tests reach through `super::` (via the crate-root
// cfg(test) re-export glob).
#[cfg(test)]
pub(crate) use session::{
    handle_answer_incoming_data_channel, handle_answer_session_message,
    process_answer_session_signal,
};

pub async fn run_answer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    run_answer_daemon_with_shutdown(config, local_identity, authorized_keys, ShutdownToken::new())
        .await
}

pub async fn run_answer_daemon_with_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let transport = MqttSignalingTransport::connect(&config)?;
    run_answer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        shutdown,
    )
    .await
}

pub async fn run_answer_daemon_with_transport<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
) -> Result<(), DaemonError> {
    run_answer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        ShutdownToken::new(),
    )
    .await
}

pub async fn run_answer_daemon_with_transport_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    validate_config_authorized_peers(&config, &authorized_keys)?;
    let config = Arc::new(config);
    let local_identity = Arc::new(local_identity);
    let authorized_keys = Arc::new(authorized_keys);
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    transport.subscribe_own_topic().await?;
    let status = StatusWriter::new(&config);
    let mut runtime = DaemonRuntimeState::new_connected();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, mut event_rx) = mpsc::channel(128);
    let mut sessions_by_id: HashMap<SessionId, AnswerSessionHandle> = HashMap::new();
    let mut session_by_peer: HashMap<PeerId, SessionId> = HashMap::new();
    let mut session_completions: AnswerSessionCompletions =
        futures_util::stream::FuturesUnordered::new();
    let mut next_generation = 1_u64;

    // Startup is only truthfully complete once the broker is subscribed and the
    // required remote peers are authorized (both already validated above); only
    // past this point may ordinary status writes report Serving.
    ctx.runtime.phase = DaemonRuntimePhase::Running;
    write_answer_registry_status(&ctx, &sessions_by_id).await;

    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);
    let mut shutting_down = false;
    let mut primary_error: Option<DaemonError> = None;

    loop {
        if shutting_down && sessions_by_id.is_empty() {
            break;
        }

        tokio::select! {
            biased;

            _ = shutdown.cancelled(), if !shutting_down => {
                tracing::info!(
                    active_session_count = sessions_by_id.len(),
                    "answer daemon shutdown requested; draining active sessions"
                );
                shutting_down = true;
                begin_answer_drain(&mut ctx, &shutdown, &mut primary_error, None);
            }

            completion = session_completions.next(), if !session_completions.is_empty() => {
                let completion = completion.expect("guarded by is_empty");
                handle_answer_task_completion(
                    &mut ctx,
                    &mut sessions_by_id,
                    &mut session_by_peer,
                    completion,
                    &shutdown,
                    &mut primary_error,
                )
                .await;
                if !shutting_down && shutdown.is_shutdown_requested() {
                    shutting_down = true;
                }
            }

            payload = poll_idle_signal_payload(&mut ctx, &mut transport), if !shutting_down => {
                let Some(payload) = payload else {
                    continue;
                };
                handle_answer_daemon_payload(
                    &AnswerDeps {
                        config: &config,
                        local_identity: &local_identity,
                        authorized_keys: &authorized_keys,
                        event_tx: &event_tx,
                        shutdown: &shutdown,
                    },
                    &codec,
                    &mut transport,
                    &mut ctx,
                    &mut AnswerSessionRegistry {
                        replay_cache: &mut replay_cache,
                        sessions_by_id: &mut sessions_by_id,
                        session_by_peer: &mut session_by_peer,
                        session_completions: &mut session_completions,
                        next_generation: &mut next_generation,
                    },
                    payload,
                )
                .await;
            }

            event = event_rx.recv() => {
                let Some(event) = event else {
                    // Fatal, but must not bypass drain: enter Draining, request
                    // shutdown, and keep consuming task completions below until the
                    // registry empties, so in-flight sessions still unwind cleanly.
                    tracing::error!("answer session event channel closed unexpectedly");
                    shutting_down = true;
                    begin_answer_drain(
                        &mut ctx,
                        &shutdown,
                        &mut primary_error,
                        Some(DaemonError::Logging(
                            "answer session event channel closed".to_owned(),
                        )),
                    );
                    continue;
                };
                handle_answer_session_event(
                    &mut ctx,
                    &codec,
                    &mut transport,
                    &mut sessions_by_id,
                    &mut session_by_peer,
                    event,
                )
                .await;
            }
        }
    }

    ctx.runtime.phase = DaemonRuntimePhase::Closed;
    let closed_result = write_answer_closed_status(&mut ctx).await;

    match primary_error {
        Some(error) => {
            if let Err(close_error) = closed_result {
                tracing::error!(reason = %close_error, "answer terminal status also failed");
            }
            Err(error)
        }
        None => closed_result,
    }
}

/// Enters `Draining`, requests cooperative shutdown, and records `error` as the
/// primary daemon failure — unless a primary error is already set, in which case
/// `error` is logged as an additional (secondary) failure during drain. Used by
/// every fatal answer-daemon path (event-channel closure, session task panic) so
/// none of them can bypass drain/finalization by returning directly.
fn begin_answer_drain(
    ctx: &mut RuntimeContext<'_>,
    shutdown: &ShutdownToken,
    primary_error: &mut Option<DaemonError>,
    error: Option<DaemonError>,
) {
    ctx.runtime.phase = DaemonRuntimePhase::Draining;
    if primary_error.is_none() {
        *primary_error = error;
    } else if let Some(error) = error {
        tracing::error!(reason = %error, "additional answer daemon failure during drain");
    }
    shutdown.request_shutdown();
}

/// Locates a session's current registry key by its stable identity (generation +
/// remote peer), not by session id — a same-peer pending-session replacement can
/// change the map key out from under an in-flight task.
fn find_session_id_by_generation_and_peer(
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
    generation: SessionGeneration,
    remote_peer_id: &PeerId,
) -> Option<SessionId> {
    sessions.iter().find_map(|(session_id, handle)| {
        (handle.generation == generation && &handle.remote_peer_id == remote_peer_id)
            .then_some(*session_id)
    })
}

/// Resolves a completed task's current registry key: `candidate_session_id` (the
/// task's own final/initial session id) is tried first as a fast path, but is only
/// trusted if the registry entry at that key still has the *same* generation and
/// remote peer — otherwise a same-peer replacement raced ahead of this completion
/// (its `Replaced` event may still be queued, unprocessed) and the entry now lives
/// under a different key. Falling back to the stable generation+peer search finds
/// it regardless of which key it currently sits under.
fn resolve_completion_registry_session_id(
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
    candidate_session_id: SessionId,
    generation: SessionGeneration,
    remote_peer_id: &PeerId,
) -> Option<SessionId> {
    if sessions.get(&candidate_session_id).is_some_and(|handle| {
        handle.generation == generation && &handle.remote_peer_id == remote_peer_id
    }) {
        return Some(candidate_session_id);
    }

    find_session_id_by_generation_and_peer(sessions, generation, remote_peer_id)
}

/// Handles one independently-observed answer session task completion (normal end,
/// remote/ICE failure, or — critically — a panic/join failure that the task never
/// self-reported). A panic can no longer strand the registry entry: this removes
/// it by stable identity and, on panic, enters drain via [`begin_answer_drain`]
/// instead of leaving the daemon silently unaware that a session vanished.
pub(crate) async fn handle_answer_task_completion(
    ctx: &mut RuntimeContext<'_>,
    sessions_by_id: &mut HashMap<SessionId, AnswerSessionHandle>,
    session_by_peer: &mut HashMap<PeerId, SessionId>,
    completion: AnswerTaskCompletion,
    shutdown: &ShutdownToken,
    primary_error: &mut Option<DaemonError>,
) {
    let AnswerTaskCompletion { initial_session_id, generation, remote_peer_id, outcome } =
        completion;

    // Both arms use the same stable-identity resolution: the task's own reported
    // session id (final on success, initial on join failure) is only a fast-path
    // hint, never trusted blindly, because a same-peer replacement can change the
    // registry key out from under an in-flight task before its completion is
    // observed here (see `resolve_completion_registry_session_id`).
    let candidate_session_id = match &outcome {
        Ok(result) => result.final_session_id,
        Err(_) => initial_session_id,
    };
    let lookup_session_id = resolve_completion_registry_session_id(
        sessions_by_id,
        candidate_session_id,
        generation,
        &remote_peer_id,
    )
    .unwrap_or(candidate_session_id);

    let matched = sessions_by_id.get(&lookup_session_id).is_some_and(|handle| {
        handle.generation == generation && handle.remote_peer_id == remote_peer_id
    });

    if matched {
        sessions_by_id.remove(&lookup_session_id);
        session_by_peer.remove(&remote_peer_id);
    } else {
        tracing::warn!(
            session_id = %lookup_session_id,
            generation = generation.0,
            remote_peer_id = %remote_peer_id,
            "ignoring stale or already-removed answer session completion"
        );
    }

    match outcome {
        Ok(result) => {
            if matched {
                recover_daemon_after_session(ctx, result.result).await;
            }
        }
        Err(join_reason) => {
            tracing::error!(
                session_id = %lookup_session_id,
                remote_peer_id = %remote_peer_id,
                reason = %join_reason,
                "answer session task panicked or was aborted unexpectedly; entering drain",
            );
            begin_answer_drain(
                ctx,
                shutdown,
                primary_error,
                Some(DaemonError::Logging(format!("answer session task panicked: {join_reason}"))),
            );
        }
    }

    write_answer_registry_status(ctx, sessions_by_id).await;
}

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
                    OutgoingSignal {
                        message: build_error_message(
                            &config.node.peer_id,
                            &decoded.sender.peer_id,
                            decoded.message.session_id,
                            FailureCode::Busy,
                            "answer daemon session capacity reached",
                        ),
                        response: true,
                    },
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
                ),
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
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
            response: false,
        },
    )
    .await?;

    session.state = DaemonState::ConnectingDataChannel;
    let (inbound_tx, inbound_rx) = mpsc::channel(128);
    let status = SessionStatusSnapshot::from_session(config, &session, generation);
    let session_id = session.session_id;
    let remote_peer_id = session.remote_peer_id.clone();
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
                ),
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
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
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
