//! Daemon lifetime is intentionally longer than session lifetime in v0.3.
//!
//! Each daemon process stays alive and repeatedly returns to its steady state
//! (`Serving` for answer, `WaitingForLocalClient` for offer) after ordinary
//! session failures. Answer daemons can serve multiple authorized peers, while
//! each offer-side peer session may carry multiple multiplexed TCP streams.
//! Session-owned streams are cleaned up deterministically before the daemon
//! accepts follow-on work.
//! Startup and security initialization failures remain fatal, while recoverable
//! runtime transport turbulence updates local status truthfully before the
//! daemon retries and returns to service.

mod busy;
mod config;
mod error;
mod logging;
mod messages;
mod predicates;
mod signaling;
mod status;
mod types;

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use p2p_core::{
    AppConfig, ConfigError, DaemonState, FailureCode, ForwardOfferConfig, ForwardTable, MsgId,
    PeerId, SessionId,
};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile, kid_from_signing_key};
use p2p_signaling::{
    AckBody, AnswerBody, CloseBody, DecodedSignal, EndOfCandidatesBody, IceCandidateBody,
    InnerMessage, InnerMessageBuilder, MessageBody, MqttSignalingTransport, OfferBody,
    OuterEnvelope, ReplayStatus, SignalCodec, SignalingError,
};
use p2p_tunnel::{OfferClient, OfferListener};
use p2p_webrtc::{DataChannelHandle, IceCandidateSignal, IceConnectionState, WebRtcPeer};
use tokio::sync::mpsc;
use tokio::time::{interval, sleep};

#[cfg(test)]
pub(crate) use busy::{
    ActiveBusyOfferAction, ActiveBusyOfferCache, classify_active_busy_offer,
    replayed_active_busy_offer_key,
};
pub(crate) use busy::{ActiveBusyOfferKey, is_peer_allowed_for_active_busy_reply};
pub use config::{
    apply_answer_overrides, apply_env_overrides, apply_offer_overrides, compute_backoff_delay,
};
#[cfg(test)]
pub(crate) use config::{
    apply_override_pairs, first_answer_forward, first_answer_forward_mut, first_offer_forward,
    first_offer_forward_mut, steady_state_for_role,
};
pub(crate) use config::{offer_remote_peer_id, validate_config_authorized_peers};
pub use error::DaemonError;
pub use logging::{redact_candidate, redact_sdp, redact_secret, setup_logging};
pub(crate) use messages::{
    build_error_message, build_hello_message, candidate_from_body, current_time_ms,
    decode_idle_signaling_message, duplicate_active_session_ack_message,
};
pub(crate) use predicates::{
    can_attempt_same_session_ice_restart, should_ack_idle_offer, should_attempt_offer_reconnect,
    should_continue_reconnect_attempt,
};
#[cfg(test)]
pub(crate) use signaling::mark_transport_usable;
pub(crate) use signaling::{
    mark_transport_unusable, mark_transport_usable_after_publish, poll_idle_signal_payload,
    poll_session_signal_payload, publish_answer_session_request, publish_message,
    recover_daemon_after_session, request_raw_session_publish, request_session_publish,
    retry_pending_acks, send_local_candidate, write_answer_registry_status, write_daemon_status,
    write_steady_state_status,
};
pub use status::{
    DaemonStatus, ForwardListenState, ForwardRuntimeStatus, SessionStatus, StatusWriter,
};
pub(crate) use types::{
    ANSWER_SESSION_CAPACITY, AnswerSessionEvent, AnswerSessionHandle, BridgeSessionState,
    DAEMON_RUNTIME_RETRY_DELAY, DaemonRuntimeState, OfferSessionPayloadOutcome, OutgoingSignal,
    RuntimeContext, SessionGeneration, SessionStatusSnapshot, StatusSnapshot,
};
pub use types::{ActiveSession, DaemonSignalingTransport};

#[cfg(any(test, debug_assertions))]
#[derive(Clone)]
pub struct OfferSessionTestHandle {
    pub session_id: SessionId,
    pub ice_state_injector: p2p_webrtc::IceStateInjectorForTests,
}

struct OfferSessionIo<'a> {
    client: OfferClient,
    accepted_clients: &'a mut mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    remote: &'a AuthorizedKey,
    #[cfg(any(test, debug_assertions))]
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
}

type OfferAcceptedClients<'a> =
    &'a mut mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>;
type OfferBridgeFuture<'a> = Pin<
    Box<
        dyn Future<Output = (Result<(), p2p_tunnel::TunnelError>, OfferAcceptedClients<'a>)>
            + Send
            + 'a,
    >,
>;

pub async fn run_offer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    let transport = MqttSignalingTransport::connect(&config)?;
    run_offer_daemon_with_transport(config, local_identity, authorized_keys, transport).await
}

pub async fn run_offer_daemon_with_transport<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
) -> Result<(), DaemonError> {
    #[cfg(any(test, debug_assertions))]
    {
        run_offer_daemon_with_transport_and_test_hook(
            config,
            local_identity,
            authorized_keys,
            transport,
            None,
        )
        .await
    }

    #[cfg(not(any(test, debug_assertions)))]
    {
        let mut transport = transport;
        run_offer_daemon_inner(config, local_identity, authorized_keys, &mut transport, None, None)
            .await
    }
}

/// Offer daemon entry point that streams live `DaemonStatus` to `status_sink` in
/// addition to the usual status-file behavior. Used by the Android runtime so the
/// UI reflects real daemon/connection state. Behaves identically to
/// [`run_offer_daemon`] otherwise.
pub async fn run_offer_daemon_with_status(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
) -> Result<(), DaemonError> {
    let mut transport = MqttSignalingTransport::connect(&config)?;
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        None,
        Some(status_sink),
    )
    .await
}

#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_test_hook<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        session_hook,
        None,
    )
    .await
}

async fn run_offer_daemon_inner<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: &mut T,
    #[cfg(any(test, debug_assertions))] session_hook: Option<
        mpsc::UnboundedSender<OfferSessionTestHandle>,
    >,
    #[cfg(not(any(test, debug_assertions)))] _session_hook: Option<()>,
    status_sink: Option<tokio::sync::watch::Sender<DaemonStatus>>,
) -> Result<(), DaemonError> {
    validate_config_authorized_peers(&config, &authorized_keys)?;
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    transport.subscribe_own_topic().await?;

    let status = match status_sink {
        Some(sink) => StatusWriter::with_sink(&config, sink),
        None => StatusWriter::new(&config),
    };
    let mut runtime = DaemonRuntimeState::new_connected();
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    write_steady_state_status(&ctx).await;

    let (listeners, forward_statuses) = bind_offer_listeners(&config).await?;
    ctx.runtime.forward_statuses = forward_statuses;
    write_steady_state_status(&ctx).await;
    let mut accepted_clients = spawn_offer_accept_loops(listeners);
    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);
    let remote_peer_id = offer_remote_peer_id(&config)?;
    let remote = authorized_keys
        .get_by_peer_id(&remote_peer_id)
        .cloned()
        .ok_or_else(|| DaemonError::MissingAuthorizedPeer(remote_peer_id.to_string()))?;

    loop {
        write_steady_state_status(&ctx).await;
        tokio::select! {
            client = accepted_clients.recv() => {
                let client = client
                    .ok_or_else(|| DaemonError::Logging("offer accept loop stopped".to_owned()))??;
                tracing::info!("accepted local client and entering busy offer session state");
                let result =
                    run_offer_session(
                        &config,
                        &codec,
                        transport,
                        &mut ctx,
                        OfferSessionIo {
                            client,
                            accepted_clients: &mut accepted_clients,
                            remote: &remote,
                            #[cfg(any(test, debug_assertions))]
                            session_hook: session_hook.clone(),
                        },
                    )
                    .await;
                recover_daemon_after_session(&ctx, result).await;
                tracing::info!("offer daemon returned to waiting state");
            }
            payload = poll_idle_signal_payload(&mut ctx, transport) => {
                let Some(payload) = payload else {
                    continue;
                };

                tracing::debug!(
                    payload_len = payload.len(),
                    role = ?config.node.role,
                    "received signaling payload while waiting for local client"
                );

                let decode_result =
                    decode_idle_signaling_message(&codec, &payload, &mut replay_cache);
                let (envelope, message, sender) = match decode_result {
                    Ok(decoded) => decoded,
                    Err(error) => {
                        tracing::warn!(reason = %error, "rejecting signaling message");
                        continue;
                    }
                };

                tracing::debug!(
                    session_id = %message.session_id,
                    sender_peer_id = %sender.peer_id,
                    sender_kid = %envelope.sender_kid,
                    message_type = ?message.message_type,
                    role = ?config.node.role,
                    "decoded idle signaling message"
                );

                match &message.body {
                    MessageBody::Hello(_) => {
                        tracing::info!("received optional hello from {}", sender.peer_id);
                    }
                    _ => {
                        tracing::warn!("ignoring unexpected idle message {:?}", message.message_type);
                    }
                }
            }
        }
    }
}

pub async fn run_answer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    let transport = MqttSignalingTransport::connect(&config)?;
    run_answer_daemon_with_transport(config, local_identity, authorized_keys, transport).await
}

pub async fn run_answer_daemon_with_transport<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
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
    let mut next_generation = 1_u64;
    write_answer_registry_status(&ctx, &sessions_by_id).await;

    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);

    loop {
        tokio::select! {
            payload = poll_idle_signal_payload(&mut ctx, &mut transport) => {
                let Some(payload) = payload else {
                    continue;
                };
                handle_answer_daemon_payload(
                    &AnswerDeps {
                        config: &config,
                        local_identity: &local_identity,
                        authorized_keys: &authorized_keys,
                        event_tx: &event_tx,
                    },
                    &codec,
                    &mut transport,
                    &mut ctx,
                    &mut AnswerSessionRegistry {
                        replay_cache: &mut replay_cache,
                        sessions_by_id: &mut sessions_by_id,
                        session_by_peer: &mut session_by_peer,
                        next_generation: &mut next_generation,
                    },
                    payload,
                )
                .await;
            }
            event = event_rx.recv() => {
                let Some(event) = event else {
                    return Err(DaemonError::Logging("answer session event channel closed".to_owned()));
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
}

// Long-lived borrows shared across answer-daemon signaling handling.
struct AnswerDeps<'a> {
    config: &'a Arc<AppConfig>,
    local_identity: &'a Arc<IdentityFile>,
    authorized_keys: &'a Arc<AuthorizedKeys>,
    event_tx: &'a mpsc::Sender<AnswerSessionEvent>,
}

// Mutable answer-session registry state owned by the daemon loop.
struct AnswerSessionRegistry<'a> {
    replay_cache: &'a mut p2p_signaling::ReplayCache,
    sessions_by_id: &'a mut HashMap<SessionId, AnswerSessionHandle>,
    session_by_peer: &'a mut HashMap<PeerId, SessionId>,
    next_generation: &'a mut u64,
}

// A decoded inbound offer with the envelope and authenticated sender it arrived with.
struct IncomingOffer<'a> {
    envelope: OuterEnvelope,
    message: InnerMessage,
    sender: AuthorizedKey,
    offer: &'a OfferBody,
}

async fn handle_answer_daemon_payload<T: DaemonSignalingTransport>(
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
                let _ = publish_message(
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
                .await;
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
    let &AnswerDeps { config, local_identity, authorized_keys, event_tx } = deps;
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
        Arc::clone(config),
        Arc::clone(local_identity),
        Arc::clone(authorized_keys),
        event_tx.clone(),
        inbound_rx,
        generation,
        session,
    ));
    registry.sessions_by_id.insert(
        session_id,
        AnswerSessionHandle {
            generation,
            remote_peer_id: remote_peer_id.clone(),
            inbound: inbound_tx,
            status,
            task,
        },
    );
    registry.session_by_peer.insert(remote_peer_id, session_id);
    Ok(())
}

async fn handle_answer_session_event<T: DaemonSignalingTransport>(
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
        AnswerSessionEvent::Ended { session_id, generation, remote_peer_id, result } => {
            if let Some(handle) = sessions_by_id.get(&session_id) {
                if handle.generation == generation && handle.remote_peer_id == remote_peer_id {
                    let handle = sessions_by_id.remove(&session_id).expect("checked above");
                    handle.task.abort();
                    session_by_peer.remove(&handle.remote_peer_id);
                    recover_daemon_after_session(ctx, result).await;
                } else {
                    tracing::warn!(
                        session_id = %session_id,
                        remote_peer_id = %remote_peer_id,
                        "ignoring stale answer-session end event"
                    );
                }
            }
            write_answer_registry_status(ctx, sessions_by_id).await;
        }
    }
}

async fn run_offer_session<'a, T: DaemonSignalingTransport>(
    config: &'a AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    io: OfferSessionIo<'a>,
) -> Result<(), DaemonError> {
    let remote = io.remote;
    let peer = WebRtcPeer::new(&config.webrtc).await?;
    let session_id = SessionId::random();
    let mut session =
        ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);

    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
    )
    .await;

    tracing::debug!(
        session_id = %session.session_id,
        remote_peer_id = %remote.peer_id,
        "starting offer session and publishing hello"
    );

    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
        None,
        remote,
        OutgoingSignal {
            message: build_hello_message(
                &config.node.peer_id,
                &remote.peer_id,
                session.session_id,
                "offer",
            ),
            response: false,
        },
    )
    .await?;

    let data_channel = session.peer.create_data_channel().await?;
    session.data_channel = Some(data_channel.clone());
    let offer_sdp = session.peer.create_offer().await?;
    tracing::debug!(
        session_id = %session.session_id,
        remote_peer_id = %remote.peer_id,
        sdp_len = offer_sdp.len(),
        "created local offer and publishing signaling offer"
    );
    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
        Some(&mut session.signaling),
        remote,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
            response: false,
        },
    )
    .await?;

    #[cfg(any(test, debug_assertions))]
    if let Some(session_hook) = io.session_hook {
        let _ = session_hook.send(OfferSessionTestHandle {
            session_id: session.session_id,
            ice_state_injector: session.peer.ice_state_injector_for_tests(),
        });
    }

    let mut tick = interval(Duration::from_secs(1));
    let mut pending_client = Some(io.client);
    let mut accepted_clients = Some(io.accepted_clients);
    let mut offer_bridge: Option<OfferBridgeFuture<'a>> = None;
    let result = async {
        loop {
            if pending_client.is_some()
                && session.data_channel.as_ref().is_some_and(|channel| channel.is_open())
                && offer_bridge.is_none()
            {
                write_daemon_status(
                    ctx,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: DaemonState::TunnelOpen,
                    },
                )
                .await;
                session.bridge_state = BridgeSessionState::Active;
                let channel =
                    session.data_channel.clone().ok_or(DaemonError::MissingDataChannel)?;
                let active_clients = accepted_clients.take().ok_or_else(|| {
                    DaemonError::Logging(
                        "offer session lost accepted-client queue while bridge was starting"
                            .to_owned(),
                    )
                })?;
                let client = pending_client.take().ok_or(DaemonError::MissingDataChannel)?;
                offer_bridge = Some(Box::pin(async move {
                    let result =
                        p2p_tunnel::run_multiplex_offer(channel, &config.tunnel, client, active_clients)
                            .await;
                    (result, active_clients)
                }));
            }
            tokio::select! {
                _ = tick.tick() => {
                    retry_pending_acks(
                        ctx,
                        transport,
                        StatusSnapshot {
                            active_session_id: Some(session.session_id),
                            current_state: session.state,
                        },
                        &mut session,
                    )
                    .await?;
                    if !session.signaling.ack_tracker.expired().is_empty() {
                        return Err(DaemonError::AckTimeout);
                    }
                }
                payload = poll_session_signal_payload(
                    ctx,
                    transport,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: session.state,
                    },
                ) => {
                    if let Some(payload) = payload? {
                        process_offer_session_payload(
                            ctx,
                            codec,
                            transport,
                            remote,
                            &mut session,
                            &payload,
                        )
                        .await?;
                    }
                }
                candidate = session.peer.next_local_candidate() => {
                    if let Some(candidate) = candidate {
                        send_local_candidate(
                            ctx,
                            codec,
                            transport,
                            &mut session,
                            remote,
                            candidate,
                        )
                        .await?;
                    }
                }
                ice_state = session.peer.next_ice_state() => {
                    if let Some(ice_state) = ice_state {
                        if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                            offer_bridge = None;
                            if let Some(handle) = session.bridge_handle.take() {
                                handle.abort();
                            }
                            if session.bridge_state == BridgeSessionState::Active {
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
                                        message: build_error_message(
                                            &config.node.peer_id,
                                            &session.remote_peer_id,
                                            session.session_id,
                                            FailureCode::IceFailed,
                                            "ice connection failed",
                                        ),
                                        response: false,
                                    },
                                ).await?;
                                // In v1 a live tunnel failure ends the current local client/session.
                                session.bridge_state = BridgeSessionState::Closed;
                                return Err(DaemonError::IceFailed(ice_state));
                            }
                            session.bridge_state = BridgeSessionState::Reconnecting;
                            if should_attempt_offer_reconnect(config, pending_client.is_some(), session.bridge_state)
                                && attempt_offer_reconnect(
                                    ctx,
                                    codec,
                                    transport,
                                    &mut session,
                                    remote,
                                )
                                .await?
                            {
                                session.bridge_state = BridgeSessionState::Pending;
                                continue;
                            }
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
                                    message: build_error_message(
                                        &config.node.peer_id,
                                        &session.remote_peer_id,
                                        session.session_id,
                                        FailureCode::IceFailed,
                                        "ice connection failed",
                                    ),
                                    response: false,
                                },
                            ).await?;
                            session.bridge_state = BridgeSessionState::Closed;
                            return Err(DaemonError::IceFailed(ice_state));
                        }
                    }
                }
                bridge_result = async {
                    let handle = session.bridge_handle.as_mut().expect("guarded by select");
                    handle.await
                }, if session.bridge_handle.is_some() => {
                    let result = bridge_result
                        .map_err(|error| DaemonError::Logging(format!("bridge task join error: {error}")))?;
                    session.bridge_handle = None;
                    session.bridge_state = BridgeSessionState::Closed;
                    let _ = publish_message(
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
                                config.node.peer_id.clone(),
                                session.remote_peer_id.clone(),
                            )
                            .build(MessageBody::Close(CloseBody {
                                reason_code: "session_closed".to_owned(),
                                message: None,
                            })),
                            response: false,
                        },
                    )
                    .await;
                    result?;
                    return Ok(());
                }
                bridge_result = async {
                    let bridge = offer_bridge.as_mut().expect("guarded by select");
                    bridge.as_mut().await
                }, if offer_bridge.is_some() => {
                    offer_bridge = None;
                    let (bridge_result, returned_clients) = bridge_result;
                    accepted_clients = Some(returned_clients);
                    session.bridge_state = BridgeSessionState::Closed;
                    let _ = publish_message(
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
                                config.node.peer_id.clone(),
                                session.remote_peer_id.clone(),
                            )
                            .build(MessageBody::Close(CloseBody {
                                reason_code: "session_closed".to_owned(),
                                message: None,
                            })),
                            response: false,
                        },
                    )
                    .await;
                    bridge_result?;
                    return Ok(());
                }
            }
        }
    }
    .await;

    if let Err(error) = &result {
        tracing::warn!(reason = %error, session_id = %session.session_id, "offer session failed");
    }
    cleanup_active_session(&mut session).await;
    result
}

async fn handle_offer_session_message(
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

async fn handle_answer_session_message(
    message: &InnerMessage,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    match &message.body {
        MessageBody::Ack(AckBody { ack_msg_id }) => {
            session.signaling.ack_tracker.acknowledge(&p2p_core::MsgId::new(*ack_msg_id));
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
            tracing::warn!("ignoring unexpected session message {:?}", message.message_type);
        }
    }
    Ok(())
}

#[cfg(test)]
async fn maybe_replace_pending_answer_session<T: DaemonSignalingTransport>(
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
        let _ = handle.await;
    }
    session.data_channel = None;
    let _ = session.peer.close().await;

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

async fn run_answer_session_task(
    config: Arc<AppConfig>,
    local_identity: Arc<IdentityFile>,
    authorized_keys: Arc<AuthorizedKeys>,
    event_tx: mpsc::Sender<AnswerSessionEvent>,
    mut inbound: mpsc::Receiver<DecodedSignal>,
    generation: SessionGeneration,
    mut session: ActiveSession,
) {
    let result = run_answer_session_task_inner(
        &config,
        &local_identity,
        &authorized_keys,
        &event_tx,
        &mut inbound,
        generation,
        &mut session,
    )
    .await;
    if let Err(error) = &result {
        tracing::warn!(
            reason = %error,
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            "answer session failed"
        );
    }
    cleanup_active_session(&mut session).await;
    let _ = event_tx
        .send(AnswerSessionEvent::Ended {
            session_id: session.session_id,
            generation,
            remote_peer_id: session.remote_peer_id.clone(),
            result,
        })
        .await;
}

async fn run_answer_session_task_inner(
    config: &AppConfig,
    local_identity: &IdentityFile,
    authorized_keys: &AuthorizedKeys,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    inbound: &mut mpsc::Receiver<DecodedSignal>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    let codec = SignalCodec::new(
        local_identity,
        authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    send_answer_session_status(config, event_tx, generation, session).await?;

    let mut tick = interval(Duration::from_secs(1));
    loop {
        tokio::select! {
            _ = tick.tick() => {
                retry_pending_answer_session_acks(config, event_tx, generation, session).await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Err(DaemonError::AckTimeout);
                }
            }
            signal = inbound.recv() => {
                let Some(signal) = signal else {
                    return Ok(());
                };
                process_answer_session_signal(config, &codec, event_tx, generation, session, signal).await?;
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    send_answer_session_local_candidate(config, event_tx, generation, session, candidate).await?;
                }
            }
            incoming = session.peer.next_incoming_data_channel(), if session.data_channel.is_none() => {
                handle_answer_incoming_data_channel(session, incoming, config)?;
                send_answer_session_status(config, event_tx, generation, session).await?;
            }
            ice_state = session.peer.next_ice_state() => {
                if let Some(ice_state) = ice_state {
                    if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                        publish_from_answer_session(
                            config,
                            event_tx,
                            session,
                            generation,
                            session.remote_authorized.clone(),
                            OutgoingSignal {
                                message: build_error_message(
                                    &config.node.peer_id,
                                    &session.remote_peer_id,
                                    session.session_id,
                                    FailureCode::IceFailed,
                                    "ice connection failed",
                                ),
                                response: false,
                            },
                            true,
                        )
                        .await?;
                        if let Some(handle) = session.bridge_handle.take() {
                            handle.abort();
                        }
                        session.bridge_state = BridgeSessionState::Closed;
                        return Err(DaemonError::IceFailed(ice_state));
                    }
                }
            }
            bridge_result = async {
                let handle = session.bridge_handle.as_mut().expect("guarded by select");
                handle.await
            }, if session.bridge_handle.is_some() => {
                let result = bridge_result
                    .map_err(|error| DaemonError::Logging(format!("bridge task join error: {error}")))?;
                session.bridge_handle = None;
                session.bridge_state = BridgeSessionState::Closed;
                send_answer_session_status(config, event_tx, generation, session).await?;
                if let Err(p2p_tunnel::TunnelError::TargetConnectFailed(message)) = &result {
                    let _ = publish_from_answer_session(
                        config,
                        event_tx,
                        session,
                        generation,
                        session.remote_authorized.clone(),
                        OutgoingSignal {
                            message: build_error_message(
                                &config.node.peer_id,
                                &session.remote_peer_id,
                                session.session_id,
                                FailureCode::TargetConnectFailed,
                                message,
                            ),
                            response: false,
                        },
                        true,
                    )
                    .await;
                }
                let _ = publish_from_answer_session(
                    config,
                    event_tx,
                    session,
                    generation,
                    session.remote_authorized.clone(),
                    OutgoingSignal {
                        message: InnerMessageBuilder::new(
                            session.session_id,
                            config.node.peer_id.clone(),
                            session.remote_peer_id.clone(),
                        )
                        .build(MessageBody::Close(CloseBody {
                            reason_code: "session_closed".to_owned(),
                            message: None,
                        })),
                        response: false,
                    },
                    true,
                )
                .await;
                result?;
                return Ok(());
            }
        }
    }
}

async fn cleanup_active_session(session: &mut ActiveSession) {
    if let Some(handle) = session.bridge_handle.take() {
        handle.abort();
        let _ = handle.await;
    }
    session.bridge_state = BridgeSessionState::Closed;
    session.data_channel = None;
    if let Err(error) = session.peer.close().await {
        tracing::warn!(
            reason = %error,
            session_id = %session.session_id,
            "failed to close session peer during cleanup"
        );
    }
}

async fn send_answer_session_status(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &ActiveSession,
) -> Result<(), DaemonError> {
    event_tx
        .send(AnswerSessionEvent::Status(SessionStatusSnapshot::from_session(
            config, session, generation,
        )))
        .await
        .map_err(|_| DaemonError::Logging("answer session event loop stopped".to_owned()))
}

async fn publish_from_answer_session(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    session: &mut ActiveSession,
    generation: SessionGeneration,
    recipient: AuthorizedKey,
    outgoing: OutgoingSignal,
    track_ack: bool,
) -> Result<(), DaemonError> {
    if let Some(published) = request_session_publish(
        event_tx,
        recipient,
        outgoing,
        track_ack,
        SessionStatusSnapshot::from_session(config, session, generation),
    )
    .await?
    {
        session.signaling.ack_tracker.register(
            published.msg_id,
            published.message_type,
            published.payload,
            current_time_ms(),
        );
    }
    Ok(())
}

async fn retry_pending_answer_session_acks(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    let mut retries = session.signaling.ack_tracker.retry_due(current_time_ms());
    while let Some((_msg_id, payload)) = retries.pop() {
        request_raw_session_publish(
            event_tx,
            session.remote_peer_id.clone(),
            payload,
            SessionStatusSnapshot::from_session(config, session, generation),
        )
        .await?;
    }
    Ok(())
}

async fn send_answer_session_local_candidate(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    candidate: IceCandidateSignal,
) -> Result<(), DaemonError> {
    let body = if candidate.candidate.is_some() {
        MessageBody::IceCandidate(IceCandidateBody {
            candidate: candidate.candidate,
            sdp_mid: candidate.sdp_mid,
            sdp_mline_index: candidate.sdp_mline_index,
        })
    } else {
        MessageBody::EndOfCandidates(EndOfCandidatesBody::default())
    };
    publish_from_answer_session(
        config,
        event_tx,
        session,
        generation,
        session.remote_authorized.clone(),
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(body),
            response: false,
        },
        true,
    )
    .await
}

async fn process_answer_session_signal(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    signal: DecodedSignal,
) -> Result<(), DaemonError> {
    let DecodedSignal { envelope, message, sender, replay_status } = signal;
    if sender.peer_id != session.remote_peer_id {
        tracing::warn!(
            peer_id = %sender.peer_id,
            expected_peer_id = %session.remote_peer_id,
            session_id = %session.session_id,
            "ignoring message from unexpected peer"
        );
        return Ok(());
    }
    if replay_status == ReplayStatus::DuplicateDifferentSession {
        tracing::warn!(
            session_id = %message.session_id,
            remote_peer_id = %session.remote_peer_id,
            "ignoring signaling message with duplicate msg_id for a different session"
        );
        return Ok(());
    }
    if replay_status == ReplayStatus::DuplicateSameSession
        && !session.duplicate_active_acks.record_if_new(envelope.msg_id)
    {
        tracing::info!(
            session_id = %message.session_id,
            duplicate_msg_id = %envelope.msg_id,
            "suppressing repeated duplicate active-session re-ack"
        );
        return Ok(());
    }
    if message.message_type.requires_ack() {
        publish_from_answer_session(
            config,
            event_tx,
            session,
            generation,
            sender.clone(),
            OutgoingSignal {
                message: codec.build_ack(
                    sender.peer_id.clone(),
                    message.session_id,
                    envelope.msg_id,
                ),
                response: true,
            },
            false,
        )
        .await?;
    }
    if replay_status == ReplayStatus::DuplicateSameSession {
        tracing::info!(
            session_id = %message.session_id,
            duplicate_msg_id = %envelope.msg_id,
            "re-acknowledged duplicate active-session signaling message"
        );
        return Ok(());
    }
    if let MessageBody::Offer(offer) = message.body.clone() {
        if message.session_id == session.session_id {
            handle_active_answer_offer_via_events(config, event_tx, generation, session, &offer)
                .await?;
        } else {
            maybe_replace_pending_same_peer_session(
                config,
                event_tx,
                generation,
                session,
                IncomingOffer { envelope, message, sender, offer: &offer },
            )
            .await?;
        }
    } else {
        if message.session_id != session.session_id {
            tracing::warn!(
                session_id = %message.session_id,
                active_session_id = %session.session_id,
                "ignoring non-offer signaling message for a different session"
            );
            return Ok(());
        }
        handle_answer_session_message(&message, session).await?;
    }
    send_answer_session_status(config, event_tx, generation, session).await?;
    Ok(())
}

async fn maybe_replace_pending_same_peer_session(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    incoming: IncomingOffer<'_>,
) -> Result<(), DaemonError> {
    let IncomingOffer { envelope, message, sender, offer } = incoming;
    // v0.3 permits same-peer replacement only while the existing session has not
    // reached data-channel/tunnel activity. Unrelated second active sessions are
    // rejected with encrypted busy and must not disturb other peers.
    if session.bridge_state != BridgeSessionState::Pending {
        publish_busy_for_same_peer_offer(
            config,
            event_tx,
            generation,
            session,
            &sender,
            message.session_id,
            envelope.msg_id,
        )
        .await?;
        return Ok(());
    }

    if let Some(handle) = session.bridge_handle.take() {
        handle.abort();
        let _ = handle.await;
    }
    session.data_channel = None;
    let _ = session.peer.close().await;

    let peer = WebRtcPeer::new(&config.webrtc).await?;
    peer.apply_remote_offer(&offer.sdp).await?;
    let mut replacement = ActiveSession::new(
        message.session_id,
        sender.clone(),
        peer,
        config.security.replay_cache_size,
    );
    let answer_sdp = replacement.peer.create_answer().await?;
    let replacement_session_id = replacement.session_id;
    let replacement_remote = replacement.remote_authorized.clone();
    let replacement_remote_peer_id = replacement.remote_peer_id.clone();
    publish_from_answer_session(
        config,
        event_tx,
        &mut replacement,
        generation,
        replacement_remote,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                replacement_session_id,
                config.node.peer_id.clone(),
                replacement_remote_peer_id,
            )
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
            response: false,
        },
        true,
    )
    .await?;
    replacement.state = DaemonState::ConnectingDataChannel;
    let old_session_id = session.session_id;
    *session = replacement;
    let status = SessionStatusSnapshot::from_session(config, session, generation);
    event_tx
        .send(AnswerSessionEvent::Replaced {
            old_session_id,
            new_session_id: session.session_id,
            remote_peer_id: session.remote_peer_id.clone(),
            generation,
            status,
        })
        .await
        .map_err(|_| DaemonError::Logging("answer session event loop stopped".to_owned()))?;
    Ok(())
}

async fn publish_busy_for_same_peer_offer(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    sender: &AuthorizedKey,
    rejected_session_id: SessionId,
    msg_id: MsgId,
) -> Result<(), DaemonError> {
    let key = ActiveBusyOfferKey {
        sender_kid: kid_from_signing_key(&sender.public_identity.sign_public),
        msg_id,
    };
    if !session.active_busy_offers.record_if_new(key) {
        return Ok(());
    }
    publish_from_answer_session(
        config,
        event_tx,
        session,
        generation,
        sender.clone(),
        OutgoingSignal {
            message: build_error_message(
                &config.node.peer_id,
                &sender.peer_id,
                rejected_session_id,
                FailureCode::Busy,
                "answer daemon already has an active session for this peer",
            ),
            response: true,
        },
        false,
    )
    .await
}

async fn handle_active_answer_offer_via_events(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    offer: &OfferBody,
) -> Result<(), DaemonError> {
    session.state = DaemonState::Negotiating;
    send_answer_session_status(config, event_tx, generation, session).await?;
    session.peer.apply_remote_offer(&offer.sdp).await?;
    let answer_sdp = session.peer.create_answer().await?;
    publish_from_answer_session(
        config,
        event_tx,
        session,
        generation,
        session.remote_authorized.clone(),
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
            response: false,
        },
        true,
    )
    .await?;
    session.state = DaemonState::ConnectingDataChannel;
    send_answer_session_status(config, event_tx, generation, session).await?;
    Ok(())
}

fn handle_answer_incoming_data_channel(
    session: &mut ActiveSession,
    incoming: Option<Result<DataChannelHandle, p2p_webrtc::WebRtcError>>,
    config: &AppConfig,
) -> Result<(), DaemonError> {
    if let Some(channel) = incoming {
        let channel = channel?;
        session.data_channel = Some(channel.clone());
        let tunnel = config.tunnel.clone();
        let forward_table = ForwardTable::new(&config.forwards);
        let remote_peer_id = session.remote_peer_id.clone();
        session.bridge_state = BridgeSessionState::Active;
        session.bridge_handle = Some(tokio::spawn(async move {
            p2p_tunnel::run_multiplex_answer(channel, &tunnel, forward_table, remote_peer_id).await
        }));
    }
    Ok(())
}

#[cfg(test)]
fn spawn_offer_accept_loop(
    listener: OfferListener,
) -> mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>> {
    spawn_offer_accept_loops(vec![listener])
}

/// Bind a local TCP listener for each configured offer forward. Individual forwards
/// that fail to bind are recorded as `Error` (soft-fail) so one bad forward does not
/// take down the others; the per-forward outcomes are returned alongside the bound
/// listeners. It is still a daemon-level error if forwards are configured but none
/// could bind.
async fn bind_offer_listeners(
    config: &AppConfig,
) -> Result<(Vec<OfferListener>, Vec<ForwardRuntimeStatus>), DaemonError> {
    let table = ForwardTable::new(&config.forwards);
    let mut listeners = Vec::new();
    let mut statuses = Vec::new();
    for bind in table.offer_listeners().map_err(|error| {
        DaemonError::Config(ConfigError::InvalidConfig(format!(
            "invalid offer forward listeners: {error:?}"
        )))
    })? {
        let forward_id = bind.forward_id.to_string();
        let offer =
            ForwardOfferConfig { listen_host: bind.listen_host, listen_port: bind.listen_port };
        match OfferListener::bind(bind.forward_id, &offer).await {
            Ok(listener) => {
                tracing::info!(
                    forward_id = listener.forward_id(),
                    local_addr = %listener.local_addr()?,
                    "listening for local forward clients"
                );
                statuses.push(ForwardRuntimeStatus::listening(forward_id));
                listeners.push(listener);
            }
            Err(error) => {
                tracing::warn!(
                    forward_id = %forward_id,
                    reason = %error,
                    "failed to bind local forward listener; marking forward as error"
                );
                statuses.push(ForwardRuntimeStatus::error(forward_id, error.to_string()));
            }
        }
    }
    if !statuses.is_empty() && listeners.is_empty() {
        return Err(DaemonError::Config(ConfigError::InvalidConfig(
            "no offer forward listeners could be bound".to_owned(),
        )));
    }
    Ok((listeners, statuses))
}

fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
) -> mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>> {
    let (tx, rx) = mpsc::channel(64);
    for listener in listeners {
        let tx = tx.clone();
        tokio::spawn(async move {
            loop {
                match listener.accept_client().await {
                    Ok(accepted) => match tx.try_send(Ok(accepted)) {
                        Ok(()) => {}
                        Err(mpsc::error::TrySendError::Full(Ok(dropped))) => {
                            tracing::warn!(
                                forward_id = dropped.forward_id(),
                                "offer pending client queue is full; closing local client"
                            );
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => return,
                        Err(mpsc::error::TrySendError::Full(Err(_))) => {}
                    },
                    Err(error) => {
                        tracing::warn!(reason = %error, "offer accept loop hit recoverable listener error");
                        sleep(DAEMON_RUNTIME_RETRY_DELAY).await;
                    }
                }
            }
        });
    }
    drop(tx);
    rx
}

async fn process_offer_session_payload<T: DaemonSignalingTransport>(
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

async fn maybe_ack_duplicate_active_session_message<T: DaemonSignalingTransport>(
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

async fn attempt_offer_reconnect<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
) -> Result<bool, DaemonError> {
    if !ctx.config.reconnect.enable_auto_reconnect {
        return Ok(false);
    }

    let max_attempts = ctx.config.reconnect.max_attempts;
    let mut attempt = 0;
    while should_continue_reconnect_attempt(max_attempts, attempt) {
        session.state = DaemonState::Backoff;
        write_daemon_status(
            ctx,
            StatusSnapshot {
                active_session_id: Some(session.session_id),
                current_state: session.state,
            },
        )
        .await;
        tokio::time::sleep(compute_backoff_delay(ctx.config, attempt)).await;

        if ctx.config.webrtc.enable_ice_restart && can_attempt_same_session_ice_restart(session) {
            session.state = DaemonState::IceRestarting;
            write_daemon_status(
                ctx,
                StatusSnapshot {
                    active_session_id: Some(session.session_id),
                    current_state: session.state,
                },
            )
            .await;
            if reconnect_with_offer(ctx, codec, transport, session, remote, true).await? {
                session.state = DaemonState::ConnectingDataChannel;
                return Ok(true);
            }
        }

        session.state = DaemonState::Renegotiating;
        write_daemon_status(
            ctx,
            StatusSnapshot {
                active_session_id: Some(session.session_id),
                current_state: session.state,
            },
        )
        .await;
        if reconnect_with_offer(ctx, codec, transport, session, remote, false).await? {
            session.state = DaemonState::ConnectingDataChannel;
            return Ok(true);
        }
        attempt = attempt.saturating_add(1);
    }

    Ok(false)
}

async fn reconnect_with_offer<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    ice_restart: bool,
) -> Result<bool, DaemonError> {
    if ice_restart {
        let offer_sdp = session.peer.create_offer_with_restart(true).await?;
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
                .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
                response: false,
            },
        )
        .await?;
        wait_for_offer_reconnect_response(
            ctx,
            codec,
            transport,
            session,
            remote,
            Duration::from_secs(u64::from(ctx.config.reconnect.ice_restart_timeout_secs)),
        )
        .await
    } else {
        let peer = WebRtcPeer::new(&ctx.config.webrtc).await?;
        let data_channel = peer.create_data_channel().await?;
        let new_session_id = SessionId::random();
        let mut replacement = ActiveSession::new(
            new_session_id,
            remote.clone(),
            peer,
            ctx.config.security.replay_cache_size,
        );
        replacement.data_channel = Some(data_channel);
        let offer_sdp = replacement.peer.create_offer().await?;
        publish_message(
            ctx,
            codec,
            transport,
            StatusSnapshot {
                active_session_id: Some(replacement.session_id),
                current_state: session.state,
            },
            Some(&mut replacement.signaling),
            remote,
            OutgoingSignal {
                message: InnerMessageBuilder::new(
                    replacement.session_id,
                    ctx.config.node.peer_id.clone(),
                    replacement.remote_peer_id.clone(),
                )
                .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
                response: false,
            },
        )
        .await?;
        if wait_for_offer_reconnect_response(
            ctx,
            codec,
            transport,
            &mut replacement,
            remote,
            Duration::from_secs(u64::from(ctx.config.reconnect.renegotiate_timeout_secs)),
        )
        .await?
        {
            let _ = session.peer.close().await;
            *session = replacement;
            return Ok(true);
        }
        Ok(false)
    }
}

async fn wait_for_offer_reconnect_response<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    timeout: Duration,
) -> Result<bool, DaemonError> {
    let deadline = tokio::time::Instant::now() + timeout;
    let mut tick = interval(Duration::from_millis(250));
    loop {
        if session.data_channel.as_ref().is_some_and(|channel| channel.is_open()) {
            return Ok(true);
        }
        if tokio::time::Instant::now() >= deadline {
            return Ok(false);
        }
        tokio::select! {
            _ = tick.tick() => {
                retry_pending_acks(
                    ctx,
                    transport,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: session.state,
                    },
                    session,
                )
                .await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Ok(false);
                }
            }
            payload = poll_session_signal_payload(
                ctx,
                transport,
                StatusSnapshot {
                    active_session_id: Some(session.session_id),
                    current_state: session.state,
                },
            ) => {
                if let Some(payload) = payload? {
                    process_offer_session_payload(
                        ctx,
                        codec,
                        transport,
                        remote,
                        session,
                        &payload,
                    )
                    .await?;
                    if session
                        .data_channel
                        .as_ref()
                        .is_some_and(|channel| channel.is_open())
                    {
                        return Ok(true);
                    }
                }
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    send_local_candidate(ctx, codec, transport, session, remote, candidate).await?;
                }
            }
            ice_state = session.peer.next_ice_state() => {
                if let Some(ice_state) = ice_state {
                    match ice_state {
                        IceConnectionState::Connected | IceConnectionState::Completed => return Ok(true),
                        IceConnectionState::Failed => return Ok(false),
                        _ => {}
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests;
