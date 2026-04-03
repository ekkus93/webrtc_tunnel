mod error;
mod logging;
mod status;

use std::env;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{AppConfig, DaemonState, FailureCode, PeerId, SessionId};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile};
use p2p_signaling::{
    AckBody, AnswerBody, CloseBody, EndOfCandidatesBody, ErrorBody, IceCandidateBody, InnerMessage,
    InnerMessageBuilder, MessageBody, MqttSignalingTransport, OfferBody, SignalCodec,
    SignalingSession,
};
use p2p_tunnel::{AnswerTargetConnector, OfferClient, OfferListener, TunnelBridge};
use p2p_webrtc::{
    DataChannelEvent, DataChannelHandle, IceCandidateSignal, IceConnectionState, WebRtcPeer,
};
use tokio::task::JoinHandle;
use tokio::time::interval;

pub use error::DaemonError;
pub use logging::{redact_candidate, redact_sdp, redact_secret, setup_logging};
pub use status::{DaemonStatus, StatusWriter};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum BridgeSessionState {
    Pending,
    Active,
    Reconnecting,
    Closed,
}

pub struct ActiveSession {
    pub session_id: SessionId,
    pub remote_peer_id: PeerId,
    pub state: DaemonState,
    remote_authorized: AuthorizedKey,
    peer: WebRtcPeer,
    data_channel: Option<DataChannelHandle>,
    bridge_handle: Option<JoinHandle<Result<(), p2p_tunnel::TunnelError>>>,
    bridge_state: BridgeSessionState,
    signaling: SignalingSession,
}

impl ActiveSession {
    fn new(
        session_id: SessionId,
        remote_authorized: AuthorizedKey,
        peer: WebRtcPeer,
        replay_cache_size: usize,
    ) -> Self {
        Self {
            session_id,
            remote_peer_id: remote_authorized.peer_id.clone(),
            state: DaemonState::Negotiating,
            remote_authorized,
            peer,
            data_channel: None,
            bridge_handle: None,
            bridge_state: BridgeSessionState::Pending,
            signaling: SignalingSession::new(replay_cache_size),
        }
    }
}

pub async fn run_offer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    let mut transport = MqttSignalingTransport::connect(&config)?;
    transport.subscribe_own_topic().await?;

    let status = StatusWriter::new(&config);
    status
        .write(DaemonStatus::new(
            config.node.peer_id.clone(),
            config.node.role.clone(),
            true,
            None,
            DaemonState::WaitingForLocalClient,
        ))
        .await?;

    let listener = OfferListener::bind(&config.tunnel.offer).await?;
    tracing::info!("listening for local clients on {}", listener.local_addr()?);

    loop {
        status
            .write(DaemonStatus::new(
                config.node.peer_id.clone(),
                config.node.role.clone(),
                true,
                None,
                DaemonState::WaitingForLocalClient,
            ))
            .await?;

        let client = listener.accept_client().await?;
        let remote = authorized_keys
            .get_by_peer_id(&config.tunnel.offer.remote_peer_id)
            .cloned()
            .ok_or_else(|| {
            DaemonError::MissingAuthorizedPeer(config.tunnel.offer.remote_peer_id.to_string())
        })?;

        run_offer_session(&config, &codec, &mut transport, &status, client, &remote).await?;
    }
}

pub async fn run_answer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    let mut transport = MqttSignalingTransport::connect(&config)?;
    transport.subscribe_own_topic().await?;
    let status = StatusWriter::new(&config);
    status
        .write(DaemonStatus::new(
            config.node.peer_id.clone(),
            config.node.role.clone(),
            true,
            None,
            DaemonState::Idle,
        ))
        .await?;

    let connector = AnswerTargetConnector::new(&config.tunnel.answer);
    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);

    loop {
        let Some(payload) = transport.poll_signal_payload().await? else {
            continue;
        };

        let decode_result = decode_idle_signaling_message(&codec, &payload, &mut replay_cache);

        let (envelope, message, sender) = match decode_result {
            Ok(decoded) => decoded,
            Err(error) => {
                tracing::warn!(reason = %error, "rejecting signaling message");
                continue;
            }
        };

        match &message.body {
            MessageBody::Hello(_) => {
                tracing::info!("received optional hello from {}", sender.peer_id);
            }
            MessageBody::Offer(offer) => {
                let peer_allowed =
                    config.tunnel.answer.allow_remote_peers.contains(&sender.peer_id);
                if !peer_allowed {
                    tracing::warn!(peer_id = %sender.peer_id, "rejecting unauthorized peer");
                    continue;
                }
                if should_ack_idle_offer(peer_allowed, message.message_type.requires_ack()) {
                    publish_message(
                        &config,
                        &codec,
                        &mut transport,
                        None,
                        &sender,
                        codec.build_ack(
                            sender.peer_id.clone(),
                            message.session_id,
                            envelope.msg_id,
                        ),
                        true,
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
                    &config,
                    &codec,
                    &mut transport,
                    Some(&mut session.signaling),
                    &session.remote_authorized,
                    InnerMessageBuilder::new(
                        session.session_id,
                        config.node.peer_id.clone(),
                        session.remote_peer_id.clone(),
                    )
                    .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
                    false,
                )
                .await?;

                session.state = DaemonState::ConnectingDataChannel;
                run_answer_session(&config, &codec, &mut transport, &connector, &status, session)
                    .await?;
                status
                    .write(DaemonStatus::new(
                        config.node.peer_id.clone(),
                        config.node.role.clone(),
                        true,
                        None,
                        DaemonState::Idle,
                    ))
                    .await?;
            }
            _ => {
                tracing::warn!("ignoring unexpected idle message {:?}", message.message_type);
            }
        }
    }
}

pub fn apply_env_overrides(config: &mut AppConfig) {
    apply_override_pairs(config, env::vars());
}

pub fn apply_offer_overrides(
    config: &mut AppConfig,
    broker_url: Option<String>,
    listen_port: Option<u16>,
) {
    if let Some(broker_url) = broker_url {
        config.broker.url = broker_url;
    }
    if let Some(listen_port) = listen_port {
        config.tunnel.offer.listen_port = listen_port;
    }
}

pub fn apply_answer_overrides(
    config: &mut AppConfig,
    broker_url: Option<String>,
    target_host: Option<String>,
    target_port: Option<u16>,
) {
    if let Some(broker_url) = broker_url {
        config.broker.url = broker_url;
    }
    if let Some(target_host) = target_host {
        config.tunnel.answer.target_host = target_host;
    }
    if let Some(target_port) = target_port {
        config.tunnel.answer.target_port = target_port;
    }
}

pub fn compute_backoff_delay(config: &AppConfig, attempt: u32) -> Duration {
    let base_ms = if attempt == 0 {
        config.reconnect.backoff_initial_ms
    } else {
        let multiplier =
            config.reconnect.backoff_multiplier.powi(i32::try_from(attempt).unwrap_or(i32::MAX));
        (config.reconnect.backoff_initial_ms as f64 * multiplier)
            .min(config.reconnect.backoff_max_ms as f64) as u64
    };
    let jitter_window = ((base_ms as f64) * config.reconnect.jitter_ratio).round() as i64;
    let jitter = if jitter_window == 0 {
        0
    } else {
        let mut rng = rand_core::OsRng;
        use rand_core::RngCore;
        let span = u64::try_from(jitter_window * 2 + 1).unwrap_or(1);
        i64::try_from(rng.next_u64() % span).unwrap_or(0) - jitter_window
    };
    Duration::from_millis(base_ms.saturating_add_signed(jitter))
}

async fn run_offer_session(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    status: &StatusWriter,
    client: OfferClient,
    remote: &AuthorizedKey,
) -> Result<(), DaemonError> {
    let peer = WebRtcPeer::new(&config.webrtc).await?;
    let session_id = SessionId::random();
    let mut session =
        ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);

    status
        .write(DaemonStatus::new(
            config.node.peer_id.clone(),
            config.node.role.clone(),
            true,
            Some(session.session_id),
            DaemonState::Negotiating,
        ))
        .await?;

    publish_message(
        config,
        codec,
        transport,
        None,
        remote,
        build_hello_message(&config.node.peer_id, &remote.peer_id, session.session_id, "offer"),
        false,
    )
    .await?;

    let data_channel = session.peer.create_data_channel().await?;
    session.data_channel = Some(data_channel.clone());
    let offer_sdp = session.peer.create_offer().await?;
    publish_message(
        config,
        codec,
        transport,
        Some(&mut session.signaling),
        remote,
        InnerMessageBuilder::new(
            session.session_id,
            config.node.peer_id.clone(),
            session.remote_peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
        false,
    )
    .await?;

    let mut tick = interval(Duration::from_secs(1));
    let local_stream = client.into_stream()?;
    let mut pending_stream = Some(local_stream);

    loop {
        tokio::select! {
            _ = tick.tick() => {
                retry_pending_acks(config, transport, &mut session).await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Err(DaemonError::AckTimeout);
                }
            }
            payload = transport.poll_signal_payload() => {
                if let Some(payload) = payload? {
                    let (envelope, message, sender) = codec.decode(
                        &payload,
                        &mut session.signaling.replay_cache,
                        Some(session.session_id),
                    )?;
                    if sender.peer_id != session.remote_peer_id {
                        tracing::warn!(
                            peer_id = %sender.peer_id,
                            expected_peer_id = %session.remote_peer_id,
                            "ignoring message from unexpected peer"
                        );
                        continue;
                    }
                    if message.message_type.requires_ack() {
                        publish_message(
                            config,
                            codec,
                            transport,
                            None,
                            remote,
                            codec.build_ack(remote.peer_id.clone(), session.session_id, envelope.msg_id),
                            true,
                        ).await?;
                    }
                    handle_offer_session_message(&message, &mut session).await?;
                }
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    send_local_candidate(config, codec, transport, &mut session, remote, candidate).await?;
                }
            }
            ice_state = session.peer.next_ice_state() => {
                if let Some(ice_state) = ice_state {
                    if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                        publish_message(
                            config,
                            codec,
                            transport,
                            Some(&mut session.signaling),
                            remote,
                            build_error_message(
                                &config.node.peer_id,
                                &session.remote_peer_id,
                                session.session_id,
                                FailureCode::IceFailed,
                                "ice connection failed",
                            ),
                            false,
                        ).await?;
                        if let Some(handle) = session.bridge_handle.take() {
                            handle.abort();
                        }
                        if session.bridge_state == BridgeSessionState::Active {
                            session.bridge_state = BridgeSessionState::Closed;
                            return Err(DaemonError::IceFailed(ice_state));
                        }
                        session.bridge_state = BridgeSessionState::Reconnecting;
                        if should_attempt_offer_reconnect(config, pending_stream.is_some(), session.bridge_state)
                            && attempt_offer_reconnect(
                                config,
                                codec,
                                transport,
                                status,
                                &mut session,
                                remote,
                            )
                            .await?
                        {
                            session.bridge_state = BridgeSessionState::Pending;
                            continue;
                        }
                        session.bridge_state = BridgeSessionState::Closed;
                        return Err(DaemonError::IceFailed(ice_state));
                    }
                }
            }
            data_event = async {
                if let Some(channel) = session.data_channel.as_ref() {
                    channel.next_event().await
                } else {
                    None
                }
            }, if session.bridge_handle.is_none() => {
                if let Some(DataChannelEvent::Open) = data_event {
                    status
                        .write(DaemonStatus::new(
                            config.node.peer_id.clone(),
                            config.node.role.clone(),
                            true,
                            Some(session.session_id),
                            DaemonState::TunnelOpen,
                        ))
                        .await?;
                    let bridge = TunnelBridge::new(
                        session.data_channel.clone().ok_or(DaemonError::MissingDataChannel)?,
                        &config.tunnel,
                    );
                    if let Some(stream) = pending_stream.take() {
                        session.bridge_state = BridgeSessionState::Active;
                        session.bridge_handle =
                            Some(tokio::spawn(async move { bridge.run_offer(stream).await }));
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
                    config,
                    codec,
                    transport,
                    Some(&mut session.signaling),
                    remote,
                    InnerMessageBuilder::new(
                        session.session_id,
                        config.node.peer_id.clone(),
                        session.remote_peer_id.clone(),
                    )
                    .build(MessageBody::Close(CloseBody {
                        reason_code: "session_closed".to_owned(),
                        message: None,
                    })),
                    false,
                )
                .await;
                result?;
                session.peer.close().await?;
                return Ok(());
            }
        }
    }
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

async fn run_answer_session(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    connector: &AnswerTargetConnector,
    status: &StatusWriter,
    mut session: ActiveSession,
) -> Result<(), DaemonError> {
    status
        .write(DaemonStatus::new(
            config.node.peer_id.clone(),
            config.node.role.clone(),
            true,
            Some(session.session_id),
            session.state,
        ))
        .await?;

    let mut tick = interval(Duration::from_secs(1));
    loop {
        tokio::select! {
            _ = tick.tick() => {
                retry_pending_acks(config, transport, &mut session).await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Err(DaemonError::AckTimeout);
                }
            }
            payload = transport.poll_signal_payload() => {
                if let Some(payload) = payload? {
                    let decoded = match codec.decode(
                        &payload,
                        &mut session.signaling.replay_cache,
                        Some(session.session_id),
                    ) {
                        Ok(decoded) => decoded,
                        Err(error) => {
                            if maybe_reject_busy_offer(
                                config,
                                codec,
                                transport,
                                &payload,
                                session.session_id,
                                config.security.replay_cache_size,
                            )
                            .await?
                            {
                                continue;
                            }
                            tracing::warn!(
                                reason = %error,
                                session_id = %session.session_id,
                                "rejecting signaling message during active answer session"
                            );
                            continue;
                        }
                    };
                    let (envelope, message, sender) = decoded;
                    if sender.peer_id != session.remote_peer_id {
                        tracing::warn!(
                            peer_id = %sender.peer_id,
                            expected_peer_id = %session.remote_peer_id,
                            "ignoring message from unexpected peer"
                        );
                        continue;
                    }
                    if message.message_type.requires_ack() {
                        publish_message(
                            config,
                            codec,
                            transport,
                            None,
                            &sender,
                            codec.build_ack(sender.peer_id.clone(), message.session_id, envelope.msg_id),
                            true,
                        ).await?;
                    }
                    handle_answer_session_message(&message, &mut session).await?;
                }
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    let remote = session.remote_authorized.clone();
                    send_local_candidate(
                        config,
                        codec,
                        transport,
                        &mut session,
                        &remote,
                        candidate,
                    ).await?;
                }
            }
            incoming = session.peer.next_incoming_data_channel(), if session.data_channel.is_none() => {
                if let Some(channel) = incoming {
                    session.data_channel = Some(channel?);
                }
            }
            ice_state = session.peer.next_ice_state() => {
                if let Some(ice_state) = ice_state {
                    if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                        publish_message(
                            config,
                            codec,
                            transport,
                            Some(&mut session.signaling),
                            &session.remote_authorized,
                            build_error_message(
                                &config.node.peer_id,
                                &session.remote_peer_id,
                                session.session_id,
                                FailureCode::IceFailed,
                                "ice connection failed",
                            ),
                            false,
                        ).await?;
                        if let Some(handle) = session.bridge_handle.take() {
                            handle.abort();
                        }
                        session.bridge_state = BridgeSessionState::Closed;
                        return Err(DaemonError::IceFailed(ice_state));
                    }
                }
            }
            data_event = async {
                if let Some(channel) = session.data_channel.as_ref() {
                    channel.next_event().await
                } else {
                    None
                }
            }, if should_poll_answer_data_events(session.data_channel.is_some(), session.bridge_handle.is_some()) => {
                if let Some(DataChannelEvent::Open) = data_event {
                    status
                        .write(DaemonStatus::new(
                            config.node.peer_id.clone(),
                            config.node.role.clone(),
                            true,
                            Some(session.session_id),
                            DaemonState::TunnelOpen,
                        ))
                        .await?;
                    let bridge = TunnelBridge::new(
                        session.data_channel.clone().ok_or(DaemonError::MissingDataChannel)?,
                        &config.tunnel,
                    );
                    let connector = connector.clone();
                    session.bridge_state = BridgeSessionState::Active;
                    session.bridge_handle = Some(tokio::spawn(async move {
                        bridge.run_answer(&connector).await
                    }));
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
                if let Err(p2p_tunnel::TunnelError::TargetConnectFailed(message)) = &result {
                    let _ = publish_message(
                        config,
                        codec,
                        transport,
                        Some(&mut session.signaling),
                        &session.remote_authorized,
                        build_error_message(
                            &config.node.peer_id,
                            &session.remote_peer_id,
                            session.session_id,
                            FailureCode::TargetConnectFailed,
                            message,
                        ),
                        false,
                    )
                    .await;
                }
                let _ = publish_message(
                    config,
                    codec,
                    transport,
                    Some(&mut session.signaling),
                    &session.remote_authorized,
                    InnerMessageBuilder::new(
                        session.session_id,
                        config.node.peer_id.clone(),
                        session.remote_peer_id.clone(),
                    )
                    .build(MessageBody::Close(CloseBody {
                        reason_code: "session_closed".to_owned(),
                        message: None,
                    })),
                    false,
                )
                .await;
                result?;
                session.peer.close().await?;
                return Ok(());
            }
        }
    }
}

async fn send_local_candidate(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
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

    publish_message(
        config,
        codec,
        transport,
        Some(&mut session.signaling),
        remote,
        InnerMessageBuilder::new(
            session.session_id,
            config.node.peer_id.clone(),
            session.remote_peer_id.clone(),
        )
        .build(body),
        false,
    )
    .await
}

async fn publish_message(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    signaling: Option<&mut SignalingSession>,
    recipient: &AuthorizedKey,
    message: InnerMessage,
    response: bool,
) -> Result<(), DaemonError> {
    let (envelope, payload) = codec.encode_for_peer(recipient, &message, response)?;
    transport
        .publish_signal(&recipient.peer_id, &config.broker.topic_prefix, payload.clone())
        .await?;
    if let Some(signaling) = signaling {
        signaling.ack_tracker.register(
            envelope.msg_id,
            message.message_type,
            payload,
            current_time_ms(),
        );
    }
    Ok(())
}

async fn retry_pending_acks(
    config: &AppConfig,
    transport: &mut MqttSignalingTransport,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    let mut retries = session.signaling.ack_tracker.retry_due(current_time_ms());
    while let Some((_msg_id, payload)) = retries.pop() {
        transport
            .publish_signal(&session.remote_peer_id, &config.broker.topic_prefix, payload)
            .await?;
    }
    Ok(())
}

async fn maybe_reject_busy_offer(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    payload: &[u8],
    active_session_id: SessionId,
    replay_cache_size: usize,
) -> Result<bool, DaemonError> {
    let mut replay_cache = p2p_signaling::ReplayCache::new(replay_cache_size);
    let Ok((_envelope, message, sender)) = codec.decode(payload, &mut replay_cache, None) else {
        return Ok(false);
    };
    if !matches!(message.body, MessageBody::Offer(_)) || message.session_id == active_session_id {
        return Ok(false);
    }
    publish_message(
        config,
        codec,
        transport,
        None,
        &sender,
        build_error_message(
            &config.node.peer_id,
            &sender.peer_id,
            message.session_id,
            FailureCode::Busy,
            "answer daemon already has an active session",
        ),
        true,
    )
    .await?;
    Ok(true)
}

fn decode_idle_signaling_message<'a>(
    codec: &SignalCodec<'a>,
    payload: &[u8],
    replay_cache: &mut p2p_signaling::ReplayCache,
) -> Result<(p2p_signaling::OuterEnvelope, InnerMessage, AuthorizedKey), DaemonError> {
    Ok(codec.decode(payload, replay_cache, None)?)
}

fn should_attempt_offer_reconnect(
    config: &AppConfig,
    pending_stream_present: bool,
    bridge_state: BridgeSessionState,
) -> bool {
    config.reconnect.enable_auto_reconnect
        && pending_stream_present
        && matches!(bridge_state, BridgeSessionState::Pending | BridgeSessionState::Reconnecting)
}

fn should_poll_answer_data_events(data_channel_present: bool, bridge_handle_present: bool) -> bool {
    data_channel_present && !bridge_handle_present
}

fn should_ack_idle_offer(peer_allowed: bool, requires_ack: bool) -> bool {
    peer_allowed && requires_ack
}

fn should_continue_reconnect_attempt(max_attempts: u32, attempt: u32) -> bool {
    max_attempts == 0 || attempt < max_attempts
}

fn apply_override_pairs(
    config: &mut AppConfig,
    overrides: impl IntoIterator<Item = (String, String)>,
) {
    for (key, value) in overrides {
        match key.as_str() {
            "P2PTUNNEL_BROKER_URL" => config.broker.url = value,
            "P2PTUNNEL_BROKER_USERNAME" => config.broker.username = value,
            "P2PTUNNEL_BROKER_PASSWORD_FILE" => config.broker.password_file = value.into(),
            "P2PTUNNEL_LISTEN_PORT" => {
                if let Ok(port) = value.parse() {
                    config.tunnel.offer.listen_port = port;
                }
            }
            "P2PTUNNEL_TARGET_HOST" => config.tunnel.answer.target_host = value,
            "P2PTUNNEL_TARGET_PORT" => {
                if let Ok(port) = value.parse() {
                    config.tunnel.answer.target_port = port;
                }
            }
            _ => {}
        }
    }
}

fn candidate_from_body(body: &IceCandidateBody) -> IceCandidateSignal {
    IceCandidateSignal {
        candidate: body.candidate.clone(),
        sdp_mid: body.sdp_mid.clone(),
        sdp_mline_index: body.sdp_mline_index,
    }
}

async fn attempt_offer_reconnect(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    status: &StatusWriter,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
) -> Result<bool, DaemonError> {
    if !config.reconnect.enable_auto_reconnect {
        return Ok(false);
    }

    let max_attempts = config.reconnect.max_attempts;
    let mut attempt = 0;
    while should_continue_reconnect_attempt(max_attempts, attempt) {
        session.state = DaemonState::Backoff;
        status
            .write(DaemonStatus::new(
                config.node.peer_id.clone(),
                config.node.role.clone(),
                true,
                Some(session.session_id),
                session.state,
            ))
            .await?;
        tokio::time::sleep(compute_backoff_delay(config, attempt)).await;

        if config.webrtc.enable_ice_restart {
            session.state = DaemonState::IceRestarting;
            status
                .write(DaemonStatus::new(
                    config.node.peer_id.clone(),
                    config.node.role.clone(),
                    true,
                    Some(session.session_id),
                    session.state,
                ))
                .await?;
            if reconnect_with_offer(config, codec, transport, session, remote, true).await? {
                session.state = DaemonState::ConnectingDataChannel;
                return Ok(true);
            }
        }

        session.state = DaemonState::Renegotiating;
        status
            .write(DaemonStatus::new(
                config.node.peer_id.clone(),
                config.node.role.clone(),
                true,
                Some(session.session_id),
                session.state,
            ))
            .await?;
        if reconnect_with_offer(config, codec, transport, session, remote, false).await? {
            session.state = DaemonState::ConnectingDataChannel;
            return Ok(true);
        }
        attempt = attempt.saturating_add(1);
    }

    Ok(false)
}

async fn reconnect_with_offer(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    ice_restart: bool,
) -> Result<bool, DaemonError> {
    if ice_restart {
        let offer_sdp = session.peer.create_offer_with_restart(true).await?;
        publish_message(
            config,
            codec,
            transport,
            Some(&mut session.signaling),
            remote,
            InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
            false,
        )
        .await?;
        wait_for_offer_reconnect_response(
            config,
            codec,
            transport,
            session,
            remote,
            Duration::from_secs(u64::from(config.reconnect.ice_restart_timeout_secs)),
        )
        .await
    } else {
        let peer = WebRtcPeer::new(&config.webrtc).await?;
        let data_channel = peer.create_data_channel().await?;
        let new_session_id = SessionId::random();
        let mut replacement = ActiveSession::new(
            new_session_id,
            remote.clone(),
            peer,
            config.security.replay_cache_size,
        );
        replacement.data_channel = Some(data_channel);
        let offer_sdp = replacement.peer.create_offer().await?;
        publish_message(
            config,
            codec,
            transport,
            Some(&mut replacement.signaling),
            remote,
            InnerMessageBuilder::new(
                replacement.session_id,
                config.node.peer_id.clone(),
                replacement.remote_peer_id.clone(),
            )
            .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
            false,
        )
        .await?;
        if wait_for_offer_reconnect_response(
            config,
            codec,
            transport,
            &mut replacement,
            remote,
            Duration::from_secs(u64::from(config.reconnect.renegotiate_timeout_secs)),
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

async fn wait_for_offer_reconnect_response(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut MqttSignalingTransport,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    timeout: Duration,
) -> Result<bool, DaemonError> {
    let deadline = tokio::time::Instant::now() + timeout;
    let mut tick = interval(Duration::from_millis(250));
    loop {
        if tokio::time::Instant::now() >= deadline {
            return Ok(false);
        }
        tokio::select! {
            _ = tick.tick() => {
                retry_pending_acks(config, transport, session).await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Ok(false);
                }
            }
            payload = transport.poll_signal_payload() => {
                if let Some(payload) = payload? {
                    let (envelope, message, sender) = codec.decode(
                        &payload,
                        &mut session.signaling.replay_cache,
                        Some(session.session_id),
                    )?;
                    if sender.peer_id != session.remote_peer_id {
                        continue;
                    }
                    if message.message_type.requires_ack() {
                        publish_message(
                            config,
                            codec,
                            transport,
                            None,
                            remote,
                            codec.build_ack(remote.peer_id.clone(), session.session_id, envelope.msg_id),
                            true,
                        ).await?;
                    }
                    handle_offer_session_message(&message, session).await?;
                }
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    send_local_candidate(config, codec, transport, session, remote, candidate).await?;
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

fn build_hello_message(
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

fn build_error_message(
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

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time is before unix epoch")
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use p2p_core::AppConfig;
    use p2p_core::{
        BrokerConfig, BrokerTlsConfig, FailureCode, HealthConfig, LoggingConfig, NodeConfig,
        NodeRole, ReconnectConfig, SecurityConfig, SessionId, TunnelAnswerConfig, TunnelConfig,
        TunnelOfferConfig, WebRtcConfig,
    };
    use p2p_crypto::{AuthorizedKeys, generate_identity};
    use p2p_signaling::{
        ErrorBody, InnerMessageBuilder, MessageBody, OfferBody, ReplayCache, SignalCodec,
        SignalingError,
    };

    use super::{
        BridgeSessionState, DaemonError, apply_answer_overrides, apply_offer_overrides,
        apply_override_pairs, compute_backoff_delay, decode_idle_signaling_message,
        should_ack_idle_offer, should_attempt_offer_reconnect, should_continue_reconnect_attempt,
        should_poll_answer_data_events,
    };

    fn sample_config() -> AppConfig {
        AppConfig {
            format: "p2ptunnel-config-v1".to_owned(),
            node: NodeConfig {
                peer_id: "offer-home".parse().expect("peer id"),
                role: NodeRole::Offer,
            },
            paths: p2p_core::PathConfig {
                identity: PathBuf::from("/tmp/identity"),
                authorized_keys: PathBuf::from("/tmp/authorized_keys"),
                state_dir: PathBuf::from("/tmp/state"),
                log_dir: PathBuf::from("/tmp/logs"),
            },
            broker: BrokerConfig {
                url: "mqtts://broker.example".to_owned(),
                client_id: "client".to_owned(),
                topic_prefix: "prefix".to_owned(),
                username: "user".to_owned(),
                password_file: PathBuf::from("/tmp/password"),
                qos: 1,
                keepalive_secs: 30,
                clean_session: true,
                connect_timeout_secs: 5,
                session_expiry_secs: 0,
                tls: BrokerTlsConfig {
                    ca_file: PathBuf::from("/tmp/ca"),
                    client_cert_file: PathBuf::from("/tmp/cert"),
                    client_key_file: PathBuf::from("/tmp/key"),
                    server_name: "broker.example".to_owned(),
                    insecure_skip_verify: false,
                },
            },
            webrtc: WebRtcConfig {
                stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
                ice_gather_timeout_secs: 10,
                ice_connection_timeout_secs: 10,
                enable_trickle_ice: true,
                enable_ice_restart: true,
                max_message_size: 262_144,
            },
            tunnel: TunnelConfig {
                stream_id: 1,
                frame_version: 1,
                read_chunk_size: 1024,
                write_buffer_limit: 262_144,
                local_eof_grace_ms: 250,
                remote_eof_grace_ms: 250,
                offer: TunnelOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 5000,
                    remote_peer_id: "answer-office".parse().expect("peer id"),
                    auto_open: true,
                    max_concurrent_clients: 1,
                    deny_when_busy: true,
                },
                answer: TunnelAnswerConfig {
                    target_host: "127.0.0.1".to_owned(),
                    target_port: 22,
                    allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
                },
            },
            reconnect: ReconnectConfig {
                enable_auto_reconnect: true,
                strategy: "exponential".to_owned(),
                ice_restart_timeout_secs: 8,
                renegotiate_timeout_secs: 20,
                backoff_initial_ms: 1000,
                backoff_max_ms: 30_000,
                backoff_multiplier: 2.0,
                jitter_ratio: 0.2,
                max_attempts: 3,
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
                log_file: PathBuf::from("/tmp/p2ptunnel.log"),
                redact_secrets: true,
                redact_sdp: true,
                redact_candidates: true,
                log_rotation: "none".to_owned(),
            },
            health: HealthConfig {
                heartbeat_interval_secs: 10,
                ping_timeout_secs: 30,
                status_socket: PathBuf::new(),
                write_status_file: true,
                status_file: PathBuf::from("/tmp/status.json"),
            },
        }
    }

    #[test]
    fn apply_offer_cli_overrides() {
        let mut config = sample_config();
        apply_offer_overrides(&mut config, Some("mqtts://override".to_owned()), Some(7777));
        assert_eq!(config.broker.url, "mqtts://override");
        assert_eq!(config.tunnel.offer.listen_port, 7777);
    }

    #[test]
    fn apply_answer_cli_overrides() {
        let mut config = sample_config();
        apply_answer_overrides(
            &mut config,
            Some("mqtts://override".to_owned()),
            Some("10.0.0.5".to_owned()),
            Some(2222),
        );
        assert_eq!(config.broker.url, "mqtts://override");
        assert_eq!(config.tunnel.answer.target_host, "10.0.0.5");
        assert_eq!(config.tunnel.answer.target_port, 2222);
    }

    #[test]
    fn env_overrides_update_config() {
        let mut config = sample_config();
        apply_override_pairs(
            &mut config,
            [
                ("P2PTUNNEL_BROKER_URL".to_owned(), "mqtts://env".to_owned()),
                ("P2PTUNNEL_LISTEN_PORT".to_owned(), "6000".to_owned()),
                ("P2PTUNNEL_TARGET_PORT".to_owned(), "2022".to_owned()),
            ],
        );
        assert_eq!(config.broker.url, "mqtts://env");
        assert_eq!(config.tunnel.offer.listen_port, 6000);
        assert_eq!(config.tunnel.answer.target_port, 2022);
    }

    #[test]
    fn backoff_grows_with_attempts() {
        let config = sample_config();
        let first = compute_backoff_delay(&config, 0);
        let second = compute_backoff_delay(&config, 1);
        assert!(second >= first);
    }

    #[test]
    fn idle_replay_cache_rejects_replayed_offer_across_iterations() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let message = InnerMessageBuilder::new(
            SessionId::random(),
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("offer encodes");

        let mut replay_cache = ReplayCache::new(64);
        decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache)
            .expect("first decode succeeds");
        assert!(matches!(
            decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache),
            Err(DaemonError::Signaling(SignalingError::Protocol(message)))
                if message.contains("duplicate")
        ));
    }

    #[test]
    fn idle_replay_cache_rejects_replayed_ack_required_message_across_iterations() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let message = InnerMessageBuilder::new(
            SessionId::random(),
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::IceFailed.as_str().to_owned(),
            message: "ice failed".to_owned(),
            fatal: true,
        }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("error encodes");

        let mut replay_cache = ReplayCache::new(64);
        decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache)
            .expect("first decode succeeds");
        assert!(matches!(
            decode_idle_signaling_message(&answer_codec, &payload, &mut replay_cache),
            Err(DaemonError::Signaling(SignalingError::Protocol(message)))
                if message.contains("duplicate")
        ));
    }

    #[test]
    fn active_offer_bridge_does_not_attempt_reconnect() {
        let config = sample_config();
        assert!(!should_attempt_offer_reconnect(&config, false, BridgeSessionState::Pending));
        assert!(!should_attempt_offer_reconnect(&config, true, BridgeSessionState::Active));
        assert!(should_attempt_offer_reconnect(&config, true, BridgeSessionState::Reconnecting));
    }

    #[test]
    fn answer_bridge_task_disables_data_event_polling() {
        assert!(should_poll_answer_data_events(true, false));
        assert!(!should_poll_answer_data_events(true, true));
        assert!(!should_poll_answer_data_events(false, false));
    }

    #[test]
    fn unauthorized_idle_offer_does_not_ack() {
        assert!(!should_ack_idle_offer(false, true));
        assert!(!should_ack_idle_offer(false, false));
        assert!(should_ack_idle_offer(true, true));
    }

    #[test]
    fn max_attempts_zero_means_unlimited() {
        assert!(should_continue_reconnect_attempt(0, 0));
        assert!(should_continue_reconnect_attempt(0, 25));
        assert!(should_continue_reconnect_attempt(3, 2));
        assert!(!should_continue_reconnect_attempt(3, 3));
    }

    #[test]
    fn strict_active_session_decode_rejects_foreign_offer() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let active_session = SessionId::random();
        let foreign_session = SessionId::random();
        let message = InnerMessageBuilder::new(
            foreign_session,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("offer encodes");

        let mut replay_cache = ReplayCache::new(64);
        assert!(matches!(
            answer_codec.decode(&payload, &mut replay_cache, Some(active_session)),
            Err(SignalingError::Protocol(message))
                if message.contains("active session")
        ));
    }
}
