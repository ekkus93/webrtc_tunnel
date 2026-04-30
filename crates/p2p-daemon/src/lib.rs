//! Daemon lifetime is intentionally longer than session lifetime in v1.
//!
//! Each daemon process stays alive and repeatedly returns to its steady state
//! (`Idle` for answer, `WaitingForLocalClient` for offer) after ordinary
//! session failures. Sessions are single-use, single-stream, and are cleaned up
//! deterministically before the daemon accepts the next session.
//! Startup and security initialization failures remain fatal, while recoverable
//! runtime transport turbulence updates local status truthfully before the
//! daemon retries and returns to service.

mod error;
mod logging;
mod status;

use std::collections::{HashSet, VecDeque};
use std::env;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{AppConfig, DaemonState, FailureCode, Kid, MsgId, NodeRole, PeerId, SessionId};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile, kid_from_signing_key};
use p2p_signaling::{
    AckBody, AnswerBody, CloseBody, EndOfCandidatesBody, ErrorBody, IceCandidateBody, InnerMessage,
    InnerMessageBuilder, MessageBody, MqttSignalingTransport, OfferBody, OuterEnvelope,
    SignalCodec, SignalingError, SignalingSession,
};
use p2p_tunnel::{AnswerTargetConnector, OfferClient, OfferListener, TunnelBridge};
use p2p_webrtc::{
    DataChannelEvent, DataChannelHandle, IceCandidateSignal, IceConnectionState, WebRtcPeer,
};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::{interval, sleep};

pub use error::DaemonError;
pub use logging::{redact_candidate, redact_sdp, redact_secret, setup_logging};
pub use status::{DaemonStatus, StatusWriter};

#[cfg(any(test, debug_assertions))]
#[derive(Clone)]
pub struct OfferSessionTestHandle {
    pub session_id: SessionId,
    pub ice_state_injector: p2p_webrtc::IceStateInjectorForTests,
}

const DAEMON_RUNTIME_RETRY_DELAY: Duration = Duration::from_secs(1);

#[allow(async_fn_in_trait)]
pub trait DaemonSignalingTransport {
    async fn subscribe_own_topic(&mut self) -> Result<(), SignalingError>;

    async fn publish_signal(
        &mut self,
        peer_id: &PeerId,
        topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), SignalingError>;

    async fn poll_signal_payload(&mut self) -> Result<Option<Vec<u8>>, SignalingError>;
}

impl DaemonSignalingTransport for MqttSignalingTransport {
    async fn subscribe_own_topic(&mut self) -> Result<(), SignalingError> {
        MqttSignalingTransport::subscribe_own_topic(self).await
    }

    async fn publish_signal(
        &mut self,
        peer_id: &PeerId,
        topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), SignalingError> {
        MqttSignalingTransport::publish_signal(self, peer_id, topic_prefix, payload).await
    }

    async fn poll_signal_payload(&mut self) -> Result<Option<Vec<u8>>, SignalingError> {
        MqttSignalingTransport::poll_signal_payload(self).await
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum BridgeSessionState {
    Pending,
    Active,
    Reconnecting,
    Closed,
}

#[derive(Clone, Debug)]
enum ActiveBusyOfferAction {
    Ignore,
    ReplyBusy { key: ActiveBusyOfferKey, session_id: SessionId, sender: Box<AuthorizedKey> },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
struct ActiveBusyOfferKey {
    sender_kid: Kid,
    msg_id: MsgId,
}

#[derive(Debug)]
struct ActiveBusyOfferCache {
    capacity: usize,
    order: VecDeque<ActiveBusyOfferKey>,
    seen: HashSet<ActiveBusyOfferKey>,
}

impl ActiveBusyOfferCache {
    fn new(capacity: usize) -> Self {
        Self { capacity: capacity.max(1), order: VecDeque::new(), seen: HashSet::new() }
    }

    fn record_if_new(&mut self, key: ActiveBusyOfferKey) -> bool {
        if self.seen.contains(&key) {
            return false;
        }
        if self.order.len() == self.capacity {
            if let Some(expired) = self.order.pop_front() {
                self.seen.remove(&expired);
            }
        }
        self.order.push_back(key);
        self.seen.insert(key);
        true
    }

    fn contains(&self, key: &ActiveBusyOfferKey) -> bool {
        self.seen.contains(key)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct DaemonRuntimeState {
    mqtt_connected: bool,
}

impl DaemonRuntimeState {
    fn new_connected() -> Self {
        Self { mqtt_connected: true }
    }
}

#[derive(Clone, Copy, Debug)]
struct StatusSnapshot {
    active_session_id: Option<SessionId>,
    current_state: DaemonState,
}

struct RuntimeContext<'a> {
    config: &'a AppConfig,
    status: &'a StatusWriter,
    runtime: &'a mut DaemonRuntimeState,
}

struct OutgoingSignal {
    message: InnerMessage,
    response: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum OfferSessionPayloadOutcome {
    Ignored,
    Handled,
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
    active_busy_offers: ActiveBusyOfferCache,
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
            active_busy_offers: ActiveBusyOfferCache::new(replay_cache_size),
            signaling: SignalingSession::new(replay_cache_size),
        }
    }
}

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
        run_offer_daemon_inner(config, local_identity, authorized_keys, &mut transport, None).await
    }
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
) -> Result<(), DaemonError> {
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
    write_steady_state_status(&ctx).await;

    let listener = OfferListener::bind(&config.tunnel.offer).await?;
    tracing::info!("listening for local clients on {}", listener.local_addr()?);
    // Keep the accept loop alive even while a session is active so extra local
    // clients are accepted and immediately closed instead of being left waiting
    // in the kernel backlog until the current session ends.
    let mut accepted_clients = spawn_offer_accept_loop(listener);
    let remote =
        authorized_keys.get_by_peer_id(&config.tunnel.offer.remote_peer_id).cloned().ok_or_else(
            || DaemonError::MissingAuthorizedPeer(config.tunnel.offer.remote_peer_id.to_string()),
        )?;

    loop {
        write_steady_state_status(&ctx).await;

        let client = accepted_clients
            .recv()
            .await
            .ok_or_else(|| DaemonError::Logging("offer accept loop stopped".to_owned()))??;
        tracing::info!("accepted local client and entering busy offer session state");
        let result =
            run_offer_session(
                &config,
                &codec,
                transport,
                &mut ctx,
                client,
                &remote,
                #[cfg(any(test, debug_assertions))]
                session_hook.clone(),
            )
            .await;
        recover_daemon_after_session(&ctx, result).await;
        tracing::info!("offer daemon returned to waiting state");
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
    write_steady_state_status(&ctx).await;

    let connector = AnswerTargetConnector::new(&config.tunnel.answer);
    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);

    loop {
        let Some(payload) = poll_idle_signal_payload(&mut ctx, &mut transport).await else {
            continue;
        };

        tracing::debug!(
            payload_len = payload.len(),
            role = ?config.node.role,
            "received signaling payload while idle"
        );

        let decode_result = decode_idle_signaling_message(&codec, &payload, &mut replay_cache);

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
            MessageBody::Offer(offer) => {
                let peer_allowed =
                    config.tunnel.answer.allow_remote_peers.contains(&sender.peer_id);
                tracing::debug!(
                    session_id = %message.session_id,
                    sender_peer_id = %sender.peer_id,
                    peer_allowed,
                    sdp_len = offer.sdp.len(),
                    "received idle offer"
                );
                if !peer_allowed {
                    tracing::warn!(peer_id = %sender.peer_id, "rejecting unauthorized peer");
                    continue;
                }
                let session_result = async {
                    if should_ack_idle_offer(peer_allowed, message.message_type.requires_ack()) {
                        publish_message(
                            &mut ctx,
                            &codec,
                            &mut transport,
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
                        &mut ctx,
                        &codec,
                        &mut transport,
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
                    run_answer_session(
                        &config,
                        &codec,
                        &mut transport,
                        &connector,
                        &mut ctx,
                        session,
                    )
                    .await
                }
                .await;
                recover_daemon_after_session(&ctx, session_result).await;
                tracing::info!("answer daemon returned to idle state");
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

async fn run_offer_session<T: DaemonSignalingTransport>(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    mut client: OfferClient,
    remote: &AuthorizedKey,
    #[cfg(any(test, debug_assertions))] session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
) -> Result<(), DaemonError> {
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
    if let Some(session_hook) = session_hook {
        let _ = session_hook.send(OfferSessionTestHandle {
            session_id: session.session_id,
            ice_state_injector: session.peer.ice_state_injector_for_tests(),
        });
    }

    let mut tick = interval(Duration::from_secs(1));
    let local_stream = client.take_stream()?;
    let mut pending_stream = Some(local_stream);
    let result = async {
        loop {
            maybe_start_offer_bridge(&mut session, &mut pending_stream, &config.tunnel);
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
                            if should_attempt_offer_reconnect(config, pending_stream.is_some(), session.bridge_state)
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
                data_event = async {
                    if let Some(channel) = session.data_channel.as_ref() {
                        channel.next_event().await
                    } else {
                        None
                    }
                }, if session.bridge_handle.is_none() => {
                    if let Some(DataChannelEvent::Open) = data_event {
                        write_daemon_status(
                            ctx,
                            StatusSnapshot {
                                active_session_id: Some(session.session_id),
                                current_state: DaemonState::TunnelOpen,
                            },
                        )
                        .await;
                        maybe_start_offer_bridge(&mut session, &mut pending_stream, &config.tunnel);
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

async fn handle_active_answer_offer<T: DaemonSignalingTransport>(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    session: &mut ActiveSession,
    offer: &OfferBody,
) -> Result<(), DaemonError> {
    session.state = DaemonState::Negotiating;
    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: session.state,
        },
    )
    .await;

    session.peer.apply_remote_offer(&offer.sdp).await?;
    let answer_sdp = session.peer.create_answer().await?;
    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: session.state,
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
    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: session.state,
        },
    )
    .await;

    Ok(())
}

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

async fn run_answer_session<T: DaemonSignalingTransport>(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    connector: &AnswerTargetConnector,
    ctx: &mut RuntimeContext<'_>,
    mut session: ActiveSession,
) -> Result<(), DaemonError> {
    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: session.state,
        },
    )
    .await;

    let mut tick = interval(Duration::from_secs(1));
    let result = async {
        loop {
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
                        let decoded = match codec.decode(
                            &payload,
                            &mut session.signaling.replay_cache,
                            Some(session.session_id),
                        ) {
                            Ok(decoded) => decoded,
                            Err(error) => {
                                if maybe_ack_duplicate_active_session_message(
                                    ctx,
                                    codec,
                                    transport,
                                    &session,
                                    &payload,
                                    &error,
                                )
                                .await?
                                {
                                    continue;
                                }
                                if maybe_replace_pending_answer_session(
                                    config,
                                    codec,
                                    transport,
                                    ctx,
                                    &mut session,
                                    &payload,
                                )
                                .await?
                                {
                                    continue;
                                }
                                if maybe_handle_active_busy_offer(
                                    ctx,
                                    codec,
                                    transport,
                                    &mut session.active_busy_offers,
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
                            ).await?;
                        }
                        if let MessageBody::Offer(offer) = &message.body {
                            handle_active_answer_offer(
                                config,
                                codec,
                                transport,
                                ctx,
                                &mut session,
                                offer,
                            )
                            .await?;
                        } else {
                            handle_answer_session_message(&message, &mut session).await?;
                        }
                    }
                }
                candidate = session.peer.next_local_candidate() => {
                    if let Some(candidate) = candidate {
                        let remote = session.remote_authorized.clone();
                        send_local_candidate(
                            ctx,
                            codec,
                            transport,
                            &mut session,
                            &remote,
                            candidate,
                        ).await?;
                    }
                }
                incoming = session.peer.next_incoming_data_channel(), if session.data_channel.is_none() => {
                    handle_answer_incoming_data_channel(&mut session, incoming, connector, &config.tunnel)?;
                }
                ice_state = session.peer.next_ice_state() => {
                    if let Some(ice_state) = ice_state {
                        if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                            publish_message(
                                ctx,
                                codec,
                                transport,
                                StatusSnapshot {
                                    active_session_id: Some(session.session_id),
                                    current_state: session.state,
                                },
                                Some(&mut session.signaling),
                                &session.remote_authorized,
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
                    if let Err(p2p_tunnel::TunnelError::TargetConnectFailed(message)) = &result {
                        let _ = publish_message(
                            ctx,
                            codec,
                            transport,
                            StatusSnapshot {
                                active_session_id: Some(session.session_id),
                                current_state: session.state,
                            },
                            Some(&mut session.signaling),
                            &session.remote_authorized,
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
                        )
                        .await;
                    }
                    let _ = publish_message(
                        ctx,
                        codec,
                        transport,
                        StatusSnapshot {
                            active_session_id: Some(session.session_id),
                            current_state: session.state,
                        },
                        Some(&mut session.signaling),
                        &session.remote_authorized,
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
            }
        }
    }
    .await;

    if let Err(error) = &result {
        tracing::warn!(reason = %error, session_id = %session.session_id, "answer session failed");
    }
    cleanup_active_session(&mut session).await;
    result
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

fn maybe_start_offer_bridge(
    session: &mut ActiveSession,
    pending_stream: &mut Option<TcpStream>,
    tunnel: &p2p_core::TunnelConfig,
) {
    if session.bridge_handle.is_some() {
        return;
    }

    let Some(channel) = session.data_channel.clone() else {
        return;
    };

    if !channel.is_open() {
        return;
    }

    let Some(stream) = pending_stream.take() else {
        return;
    };

    let bridge = TunnelBridge::new(channel, tunnel);
    session.bridge_state = BridgeSessionState::Active;
    session.bridge_handle = Some(tokio::spawn(async move { bridge.run_offer(stream).await }));
}

fn handle_answer_incoming_data_channel(
    session: &mut ActiveSession,
    incoming: Option<Result<DataChannelHandle, p2p_webrtc::WebRtcError>>,
    connector: &AnswerTargetConnector,
    tunnel: &p2p_core::TunnelConfig,
) -> Result<(), DaemonError> {
    if let Some(channel) = incoming {
        let channel = channel?;
        session.data_channel = Some(channel.clone());
        let bridge = TunnelBridge::new(channel, tunnel);
        let connector = connector.clone();
        session.bridge_state = BridgeSessionState::Active;
        session.bridge_handle = Some(tokio::spawn(async move { bridge.run_answer(&connector).await }));
    }
    Ok(())
}

fn spawn_offer_accept_loop(
    listener: OfferListener,
) -> mpsc::Receiver<Result<OfferClient, DaemonError>> {
    let (tx, rx) = mpsc::channel(1);
    tokio::spawn(async move {
        loop {
            match listener.accept_client().await {
                Ok(accepted) => {
                    if tx.send(Ok(accepted)).await.is_err() {
                        return;
                    }
                }
                Err(error) => {
                    tracing::warn!(reason = %error, "offer accept loop hit recoverable listener error");
                    sleep(DAEMON_RUNTIME_RETRY_DELAY).await;
                }
            }
        }
    });
    rx
}

fn steady_state_for_role(role: &NodeRole) -> DaemonState {
    match role {
        NodeRole::Offer => DaemonState::WaitingForLocalClient,
        NodeRole::Answer => DaemonState::Idle,
    }
}

async fn write_daemon_status(ctx: &RuntimeContext<'_>, snapshot: StatusSnapshot) {
    write_status_or_log(
        ctx.status,
        DaemonStatus::new(
            ctx.config.node.peer_id.clone(),
            ctx.config.node.role.clone(),
            ctx.runtime.mqtt_connected,
            snapshot.active_session_id,
            snapshot.current_state,
        ),
    )
    .await;
}

async fn write_steady_state_status(ctx: &RuntimeContext<'_>) {
    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: None,
            current_state: steady_state_for_role(&ctx.config.node.role),
        },
    )
    .await;
}

async fn recover_daemon_after_session(ctx: &RuntimeContext<'_>, result: Result<(), DaemonError>) {
    write_steady_state_status(ctx).await;
    if let Err(error) = result {
        tracing::warn!(
            reason = %error,
            role = ?ctx.config.node.role,
            "daemon recovered from session failure"
        );
    }
}

async fn write_status_or_log(status: &StatusWriter, daemon_status: DaemonStatus) {
    if let Err(error) = status.write(daemon_status).await {
        tracing::warn!(reason = %error, "status write failed; continuing without status update");
    }
}

async fn mark_transport_unusable(
    ctx: &mut RuntimeContext<'_>,
    snapshot: StatusSnapshot,
    error: &SignalingError,
) {
    ctx.runtime.mqtt_connected = false;
    write_daemon_status(ctx, snapshot).await;
    tracing::warn!(
        reason = %error,
        role = ?ctx.config.node.role,
        state = ?snapshot.current_state,
        session_id = snapshot.active_session_id.as_ref().map(ToString::to_string),
        "signaling transport is currently unusable"
    );
}

async fn mark_transport_usable(ctx: &mut RuntimeContext<'_>, snapshot: StatusSnapshot) {
    if ctx.runtime.mqtt_connected {
        return;
    }
    ctx.runtime.mqtt_connected = true;
    write_daemon_status(ctx, snapshot).await;
    tracing::info!(
        role = ?ctx.config.node.role,
        state = ?snapshot.current_state,
        session_id = snapshot.active_session_id.as_ref().map(ToString::to_string),
        "signaling transport recovered"
    );
}

async fn poll_session_signal_payload<T: DaemonSignalingTransport>(
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

async fn poll_idle_signal_payload<T: DaemonSignalingTransport>(
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

async fn send_local_candidate<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
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
            .build(body),
            response: false,
        },
    )
    .await
}

async fn publish_message<T: DaemonSignalingTransport>(
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
            current_time_ms(),
        );
    }
    Ok(())
}

async fn retry_pending_acks<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    transport: &mut T,
    snapshot: StatusSnapshot,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    let mut retries = session.signaling.ack_tracker.retry_due(current_time_ms());
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
                ctx,
                codec,
                transport,
                session,
                payload,
                &error,
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
                message: codec.build_ack(remote.peer_id.clone(), session.session_id, envelope.msg_id),
                response: true,
            },
        )
        .await?;
    }
    handle_offer_session_message(&message, session).await?;
    Ok(OfferSessionPayloadOutcome::Handled)
}

async fn maybe_handle_active_busy_offer<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    active_busy_offers: &mut ActiveBusyOfferCache,
    payload: &[u8],
    active_session_id: SessionId,
    replay_cache_size: usize,
) -> Result<bool, DaemonError> {
    if let Some(key) = replayed_active_busy_offer_key(payload, active_busy_offers) {
        tracing::info!(
            active_session_id = %active_session_id,
            duplicate_msg_id = %key.msg_id,
            "suppressing replayed offer before active-session busy reclassification"
        );
        return Ok(true);
    }
    let Some(action) = classify_active_busy_offer(
        ctx.config,
        codec,
        payload,
        active_session_id,
        replay_cache_size,
    ) else {
        return Ok(false);
    };
    match action {
        ActiveBusyOfferAction::Ignore => {}
        ActiveBusyOfferAction::ReplyBusy { key, session_id, sender } => {
            if !active_busy_offers.record_if_new(key) {
                tracing::info!(
                    peer_id = %sender.peer_id,
                    active_session_id = %active_session_id,
                    duplicate_msg_id = %key.msg_id,
                    "suppressing duplicate busy reply for replayed offer during active answer session"
                );
                return Ok(true);
            }
            tracing::info!(
                peer_id = %sender.peer_id,
                active_session_id = %active_session_id,
                rejected_session_id = %session_id,
                "rejecting new offer with busy because answer daemon already has an active allowed session"
            );
            publish_message(
                ctx,
                codec,
                transport,
                StatusSnapshot {
                    active_session_id: Some(active_session_id),
                    current_state: DaemonState::ConnectingDataChannel,
                },
                None,
                &sender,
                OutgoingSignal {
                    message: build_error_message(
                        &ctx.config.node.peer_id,
                        &sender.peer_id,
                        session_id,
                        FailureCode::Busy,
                        "answer daemon already has an active session",
                    ),
                    response: true,
                },
            )
            .await?;
        }
    }
    Ok(true)
}

async fn maybe_ack_duplicate_active_session_message<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &ActiveSession,
    payload: &[u8],
    error: &SignalingError,
) -> Result<bool, DaemonError> {
    let Some(ack_message) = duplicate_active_session_ack_message(
        codec,
        session.session_id,
        &session.remote_authorized,
        &session.remote_peer_id,
        payload,
        error,
    ) else {
        return Ok(false);
    };

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

fn duplicate_active_session_ack_message(
    codec: &SignalCodec<'_>,
    session_id: SessionId,
    remote_authorized: &AuthorizedKey,
    remote_peer_id: &PeerId,
    payload: &[u8],
    error: &SignalingError,
) -> Option<InnerMessage> {
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

    Some(codec.build_ack(remote_peer_id.clone(), session_id, envelope.msg_id))
}

fn replayed_active_busy_offer_key(
    payload: &[u8],
    active_busy_offers: &ActiveBusyOfferCache,
) -> Option<ActiveBusyOfferKey> {
    let envelope = OuterEnvelope::decode(payload).ok()?;
    let key = ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id };
    active_busy_offers.contains(&key).then_some(key)
}

fn classify_active_busy_offer(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    payload: &[u8],
    active_session_id: SessionId,
    replay_cache_size: usize,
) -> Option<ActiveBusyOfferAction> {
    let mut replay_cache = p2p_signaling::ReplayCache::new(replay_cache_size);
    let Ok((envelope, message, sender)) = codec.decode(payload, &mut replay_cache, None) else {
        return None;
    };
    if !matches!(message.body, MessageBody::Offer(_)) || message.session_id == active_session_id {
        return None;
    }
    if !is_peer_allowed_for_active_busy_reply(config, &sender.peer_id) {
        tracing::warn!(
            peer_id = %sender.peer_id,
            active_session_id = %active_session_id,
            "ignoring new offer during active answer session because peer is not allowlisted"
        );
        return Some(ActiveBusyOfferAction::Ignore);
    }
    Some(ActiveBusyOfferAction::ReplyBusy {
        key: ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id },
        session_id: message.session_id,
        sender: Box::new(sender),
    })
}

fn is_peer_allowed_for_active_busy_reply(config: &AppConfig, sender_peer_id: &PeerId) -> bool {
    config.tunnel.answer.allow_remote_peers.contains(sender_peer_id)
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

fn should_ack_idle_offer(peer_allowed: bool, requires_ack: bool) -> bool {
    peer_allowed && requires_ack
}

fn should_continue_reconnect_attempt(max_attempts: u32, attempt: u32) -> bool {
    max_attempts == 0 || attempt < max_attempts
}

fn can_attempt_same_session_ice_restart(session: &ActiveSession) -> bool {
    session
        .data_channel
        .as_ref()
        .is_some_and(|channel| channel.is_open())
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
        if session
            .data_channel
            .as_ref()
            .is_some_and(|channel| channel.is_open())
        {
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
    use std::path::Path;
    use std::path::PathBuf;
    use std::time::Duration;

    use p2p_core::AppConfig;
    use p2p_core::{
        ACK_RETRY_TIMEOUT_SECS, BrokerConfig, BrokerTlsConfig, FailureCode, HealthConfig,
        LoggingConfig, NodeConfig, NodeRole, PeerId, ReconnectConfig, SecurityConfig, SessionId,
        TunnelAnswerConfig, TunnelConfig, TunnelOfferConfig, WebRtcConfig,
    };
    use p2p_crypto::{AuthorizedKeys, generate_identity};
    use p2p_signaling::{
        AckBody, ErrorBody, InnerMessageBuilder, MessageBody, OfferBody, OuterEnvelope,
        ReplayCache, SignalCodec, SignalingError,
    };
    use serde_json::Value;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};
    use tokio::sync::Mutex;
    use tokio::time::timeout;

    use super::{
        ActiveBusyOfferAction, ActiveBusyOfferCache, ActiveBusyOfferKey, BridgeSessionState,
        ActiveSession, AnswerTargetConnector, DaemonError, DaemonRuntimeState, DaemonState,
        DaemonSignalingTransport, IceConnectionState, MqttSignalingTransport, OfferListener, OfferSessionPayloadOutcome,
        RuntimeContext, StatusSnapshot, StatusWriter, TunnelBridge, WebRtcPeer,
        apply_answer_overrides,
        apply_offer_overrides, apply_override_pairs, classify_active_busy_offer,
        compute_backoff_delay, decode_idle_signaling_message,
        duplicate_active_session_ack_message, mark_transport_unusable, mark_transport_usable,
        handle_answer_incoming_data_channel, handle_answer_session_message,
        handle_offer_session_message, maybe_replace_pending_answer_session,
        process_offer_session_payload, recover_daemon_after_session,
        replayed_active_busy_offer_key, should_ack_idle_offer, should_attempt_offer_reconnect,
        should_continue_reconnect_attempt, spawn_offer_accept_loop, steady_state_for_role,
        write_steady_state_status,
    };

    type PublishedSignals = std::sync::Arc<Mutex<Vec<(PeerId, Vec<u8>)>>>;

    #[derive(Clone, Default)]
    struct RecordingTransport {
        published: PublishedSignals,
    }

    #[allow(async_fn_in_trait)]
    impl DaemonSignalingTransport for RecordingTransport {
        async fn subscribe_own_topic(&mut self) -> Result<(), SignalingError> {
            Ok(())
        }

        async fn publish_signal(
            &mut self,
            peer_id: &PeerId,
            _topic_prefix: &str,
            payload: Vec<u8>,
        ) -> Result<(), SignalingError> {
            self.published.lock().await.push((peer_id.clone(), payload));
            Ok(())
        }

        async fn poll_signal_payload(&mut self) -> Result<Option<Vec<u8>>, SignalingError> {
            Ok(None)
        }
    }

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
                    insecure_skip_verify: false,
                },
            },
            webrtc: WebRtcConfig {
                stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
                enable_trickle_ice: true,
                enable_ice_restart: true,
            },
            tunnel: TunnelConfig {
                stream_id: 1,
                read_chunk_size: 1024,
                local_eof_grace_ms: 250,
                remote_eof_grace_ms: 250,
                offer: TunnelOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 5000,
                    remote_peer_id: "answer-office".parse().expect("peer id"),
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
                status_socket: PathBuf::new(),
                write_status_file: true,
                status_file: PathBuf::from("/tmp/status.json"),
            },
        }
    }

    fn status_writer_for_test(config: &mut AppConfig, label: &str) -> (PathBuf, StatusWriter) {
        let path = std::env::temp_dir()
            .join(format!("p2ptunnel-daemon-status-{label}-{}.json", SessionId::random()));
        config.health.write_status_file = true;
        config.health.status_file = path.clone();
        (path, StatusWriter::new(config))
    }

    async fn read_status_file(path: &Path) -> Value {
        let content = tokio::fs::read_to_string(path).await.expect("status file should exist");
        serde_json::from_str(&content).expect("valid status json")
    }

    fn connected_runtime() -> DaemonRuntimeState {
        DaemonRuntimeState::new_connected()
    }

    fn answer_connector(port: u16) -> AnswerTargetConnector {
        AnswerTargetConnector::new(&TunnelAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: port,
            allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
        })
    }

    async fn connected_channels(
        webrtc: &WebRtcConfig,
    ) -> (WebRtcPeer, WebRtcPeer, p2p_webrtc::DataChannelHandle, p2p_webrtc::DataChannelHandle) {
        let offer_peer = WebRtcPeer::new(webrtc).await.expect("offer peer should build");
        let answer_peer = WebRtcPeer::new(webrtc).await.expect("answer peer should build");

        let offer_channel = offer_peer
            .create_data_channel()
            .await
            .expect("offer data channel should build");
        let offer_sdp = offer_peer.create_offer().await.expect("offer SDP should build");
        answer_peer
            .apply_remote_offer(&offer_sdp)
            .await
            .expect("answer should accept offer");
        let answer_sdp = answer_peer.create_answer().await.expect("answer SDP should build");
        offer_peer
            .apply_remote_answer(&answer_sdp)
            .await
            .expect("offer should accept answer");

        let answer_channel = timeout(Duration::from_secs(10), answer_peer.next_incoming_data_channel())
            .await
            .expect("incoming data channel should arrive")
            .expect("incoming data channel stream should yield")
            .expect("incoming data channel should be accepted");

        offer_channel
            .wait_for_open(Duration::from_secs(10))
            .await
            .expect("offer data channel should open");

        (offer_peer, answer_peer, offer_channel, answer_channel)
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

    #[test]
    fn steady_state_matches_v1_role_policy() {
        assert_eq!(steady_state_for_role(&NodeRole::Offer), DaemonState::WaitingForLocalClient);
        assert_eq!(steady_state_for_role(&NodeRole::Answer), DaemonState::Idle);
    }

    #[test]
    fn duplicate_active_session_message_builds_re_ack_for_original_msg_id() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let session_id = SessionId::random();
        let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());
        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "duplicate retry".to_owned(),
            fatal: true,
        }));
        let (envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("message encodes");

        let ack = duplicate_active_session_ack_message(
            &answer_codec,
            session_id,
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
            &offer.identity.peer_id,
            &payload,
            &duplicate_error,
        )
        .expect("duplicate active-session message should be re-acknowledged");

        assert_eq!(ack.session_id, session_id);
        assert_eq!(ack.sender_peer_id, answer.identity.peer_id);
        assert_eq!(ack.recipient_peer_id, offer.identity.peer_id);
        assert!(matches!(
            ack.body,
            MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == envelope.msg_id.into_bytes()
        ));
    }

    #[tokio::test]
    async fn active_session_retry_and_duplicate_reack_flow_retires_pending_ack() {
        let mut config = sample_config();
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let remote = offer_keys
            .get_by_peer_id(&answer.identity.peer_id)
            .cloned()
            .expect("answer authorized key");
        let peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer should build");
        let session_id = SessionId::random();
        let mut session =
            ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);

        let outbound_message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "retry me".to_owned(),
            fatal: true,
        }));
        let (outbound_envelope, outbound_payload) = offer_codec
            .encode_for_peer(&remote, &outbound_message, false)
            .expect("outbound message encodes");
        session.signaling.ack_tracker.register(
            outbound_envelope.msg_id,
            outbound_message.message_type,
            outbound_payload.clone(),
            0,
        );

        let retries = session.signaling.ack_tracker.retry_due(ACK_RETRY_TIMEOUT_SECS * 1_000);
        assert_eq!(retries.len(), 1, "pending outbound message should be retried once due");
        assert_eq!(retries[0].0, outbound_envelope.msg_id);
        assert_eq!(retries[0].1, outbound_payload);

        let duplicate_inbound = InnerMessageBuilder::new(
            session_id,
            answer.identity.peer_id.clone(),
            offer.identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "duplicate inbound".to_owned(),
            fatal: true,
        }));
        let (duplicate_envelope, duplicate_payload) = answer_codec
            .encode_for_peer(
                answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
                &duplicate_inbound,
                false,
            )
            .expect("duplicate inbound encodes");
        let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());

        let reack = duplicate_active_session_ack_message(
            &offer_codec,
            session_id,
            &session.remote_authorized,
            &session.remote_peer_id,
            &duplicate_payload,
            &duplicate_error,
        )
        .expect("duplicate inbound payload should be re-acknowledged");

        assert!(matches!(
            reack.body,
            MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == duplicate_envelope.msg_id.into_bytes()
        ));

        let inbound_ack = answer_codec.build_ack(
            offer.identity.peer_id.clone(),
            session_id,
            outbound_envelope.msg_id,
        );
        handle_offer_session_message(&inbound_ack, &mut session)
            .await
            .expect("inbound ack should retire pending outbound message");

        assert!(
            session.signaling.ack_tracker.retry_due(u64::MAX).is_empty(),
            "inbound ack should clear the pending outbound retry"
        );
        assert!(
            session.signaling.ack_tracker.expired().is_empty(),
            "retired pending message should not linger as expired"
        );

        session.peer.close().await.expect("offer peer should close");
    }

    #[tokio::test]
    async fn answer_incoming_data_channel_handoff_starts_bridge_without_open_event_branch() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let remote = answer_keys
            .get_by_peer_id(&offer.identity.peer_id)
            .cloned()
            .expect("offer authorized key");

        let (offer_peer, answer_peer, offer_channel, answer_channel) =
            connected_channels(&config.webrtc).await;
        let mut session = ActiveSession::new(
            SessionId::random(),
            remote,
            answer_peer,
            config.security.replay_cache_size,
        );

        let target_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("target listener should bind");
        let connector = answer_connector(
            target_listener
                .local_addr()
                .expect("target local addr")
                .port(),
        );

        handle_answer_incoming_data_channel(
            &mut session,
            Some(Ok(answer_channel)),
            &connector,
            &config.tunnel,
        )
        .expect("incoming data channel should hand off to answer bridge");

        assert!(session.data_channel.is_some(), "answer session should retain the incoming channel");
        assert!(session.bridge_handle.is_some(), "answer session should start the bridge immediately");
        assert_eq!(session.bridge_state, BridgeSessionState::Active);

        let target_task = tokio::spawn(async move {
            let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
            let mut received = [0_u8; 4];
            target_stream.read_exact(&mut received).await.expect("target read");
            assert_eq!(&received, b"ping");
            target_stream.write_all(b"pong").await.expect("target write");
            target_stream.shutdown().await.expect("target shutdown");
        });

        let local_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("local listener should bind");
        let local_addr = local_listener.local_addr().expect("local addr");
        let client_task = tokio::spawn(async move {
            let mut client = TcpStream::connect(local_addr).await.expect("client connect");
            client.write_all(b"ping").await.expect("client write");
            let mut response = [0_u8; 4];
            client.read_exact(&mut response).await.expect("client read");
            assert_eq!(&response, b"pong");
            client.shutdown().await.expect("client shutdown");
        });
        let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

        let offer_task = tokio::spawn(async move {
            TunnelBridge::new(offer_channel, &config.tunnel).run_offer(offer_stream).await
        });

        timeout(Duration::from_secs(10), client_task)
            .await
            .expect("client task should finish in time")
            .expect("client task should succeed");
        timeout(Duration::from_secs(10), target_task)
            .await
            .expect("target task should finish in time")
            .expect("target task should succeed");
        timeout(Duration::from_secs(10), offer_task)
            .await
            .expect("offer bridge should finish in time")
            .expect("offer bridge join should succeed")
            .expect("offer bridge should succeed");
        timeout(
            Duration::from_secs(10),
            session.bridge_handle.take().expect("answer bridge handle should exist"),
        )
        .await
        .expect("answer bridge should finish in time")
        .expect("answer bridge join should succeed")
        .expect("answer bridge should succeed");

        offer_peer.close().await.expect("offer peer should close");
        session.peer.close().await.expect("answer peer should close");
    }

    #[tokio::test]
    async fn active_offer_session_ignores_duplicate_signal_and_processes_later_valid_ack() {
        let mut config = sample_config();
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.broker.username.clear();
        config.broker.password_file = PathBuf::new();
        config.broker.tls.ca_file = PathBuf::from("/etc/ssl/certs/ca-certificates.crt");
        config.broker.tls.client_cert_file = PathBuf::new();
        config.broker.tls.client_key_file = PathBuf::new();

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let remote = offer_keys
            .get_by_peer_id(&answer.identity.peer_id)
            .cloned()
            .expect("answer authorized key");
        let peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer should build");
        let session_id = SessionId::random();
        let mut session =
            ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);
        let (path, writer) = status_writer_for_test(&mut config, "offer-duplicate-survival");
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
        let mut transport = MqttSignalingTransport::connect(&config).expect("transport should build");

        let outbound_message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "pending-offer".to_owned() }));
        let (outbound_envelope, outbound_payload) = offer_codec
            .encode_for_peer(&remote, &outbound_message, false)
            .expect("offer encodes");
        session.signaling.ack_tracker.register(
            outbound_envelope.msg_id,
            outbound_message.message_type,
            outbound_payload,
            0,
        );

        let duplicate_ack = answer_codec.build_ack(
            offer.identity.peer_id.clone(),
            session_id,
            p2p_core::MsgId::random(),
        );
        let (_duplicate_envelope, duplicate_payload) = answer_codec
            .encode_for_peer(
                answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
                &duplicate_ack,
                false,
            )
            .expect("duplicate ack encodes");

        let first = process_offer_session_payload(
            &mut ctx,
            &offer_codec,
            &mut transport,
            &remote,
            &mut session,
            &duplicate_payload,
        )
        .await
        .expect("first ack should process cleanly");
        assert_eq!(first, OfferSessionPayloadOutcome::Handled);

        let duplicate = process_offer_session_payload(
            &mut ctx,
            &offer_codec,
            &mut transport,
            &remote,
            &mut session,
            &duplicate_payload,
        )
        .await
        .expect("duplicate ack should be ignored rather than abort the session");
        assert_eq!(duplicate, OfferSessionPayloadOutcome::Ignored);

        let valid_ack = answer_codec.build_ack(
            offer.identity.peer_id.clone(),
            session_id,
            outbound_envelope.msg_id,
        );
        let (_valid_envelope, valid_payload) = answer_codec
            .encode_for_peer(
                answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
                &valid_ack,
                false,
            )
            .expect("valid ack encodes");
        let processed = process_offer_session_payload(
            &mut ctx,
            &offer_codec,
            &mut transport,
            &remote,
            &mut session,
            &valid_payload,
        )
        .await
        .expect("later valid ack should still be processed");
        assert_eq!(processed, OfferSessionPayloadOutcome::Handled);
        assert!(
            session.signaling.ack_tracker.retry_due(u64::MAX).is_empty(),
            "later valid ack should retire the pending outbound offer"
        );
        assert!(session.signaling.ack_tracker.expired().is_empty());

        let _ = tokio::fs::remove_file(&path).await;
        session.peer.close().await.expect("offer peer should close");
    }

    #[tokio::test]
    async fn answer_session_does_not_initiate_reconnect_from_remote_requests() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let remote = answer_keys
            .get_by_peer_id(&offer.identity.peer_id)
            .cloned()
            .expect("offer authorized key");
        let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
        let session_id = SessionId::random();
        let mut session = ActiveSession::new(
            session_id,
            remote,
            peer,
            config.security.replay_cache_size,
        );
        let original_state = session.state;

        let ice_restart_request = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::IceRestartRequest);
        handle_answer_session_message(&ice_restart_request, &mut session)
            .await
            .expect("answer session should ignore remote ice restart request");

        let renegotiate_request = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::RenegotiateRequest(p2p_signaling::RenegotiateRequestBody {
            reason: "offer-side recovery only".to_owned(),
        }));
        handle_answer_session_message(&renegotiate_request, &mut session)
            .await
            .expect("answer session should ignore remote renegotiate request");

        assert_eq!(session.session_id, session_id);
        assert_eq!(session.state, original_state);
        assert!(session.data_channel.is_none(), "answer session should not create a data channel");
        assert!(session.bridge_handle.is_none(), "answer session should not start a new bridge task");
        assert_eq!(session.bridge_state, BridgeSessionState::Pending);

        session.peer.close().await.expect("answer peer should close");
    }

    #[tokio::test]
    async fn pending_answer_session_is_replaced_by_same_peer_offer() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.tunnel.answer.allow_remote_peers = vec!["offer-home".parse().expect("offer peer id")];

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);

        let remote = answer_keys
            .get_by_peer_id(&offer.identity.peer_id)
            .cloned()
            .expect("offer authorized key");
        let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
        let original_session_id = SessionId::random();
        let mut session = ActiveSession::new(
            original_session_id,
            remote,
            peer,
            config.security.replay_cache_size,
        );

        let (status_path, writer) = status_writer_for_test(&mut config, "pending-replacement");
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
        let mut transport = RecordingTransport::default();

        let replacement_offer_peer = WebRtcPeer::new(&config.webrtc)
            .await
            .expect("replacement offer peer should build");
        let _replacement_channel = replacement_offer_peer
            .create_data_channel()
            .await
            .expect("replacement offer data channel should build");
        let replacement_session_id = SessionId::random();
        let replacement_offer_sdp = replacement_offer_peer
            .create_offer()
            .await
            .expect("replacement offer should build SDP");
        let replacement_offer = InnerMessageBuilder::new(
            replacement_session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: replacement_offer_sdp }));
        let (_envelope, replacement_payload) = offer_codec
            .encode_for_peer(
                offer_keys
                    .get_by_peer_id(&answer.identity.peer_id)
                    .expect("answer key"),
                &replacement_offer,
                false,
            )
            .expect("replacement offer encodes");

        let replaced = maybe_replace_pending_answer_session(
            &config,
            &answer_codec,
            &mut transport,
            &mut ctx,
            &mut session,
            &replacement_payload,
        )
        .await
        .expect("pending answer session should accept replacement offer");

        assert!(replaced);
        assert_eq!(session.session_id, replacement_session_id);
        assert_eq!(session.remote_peer_id, offer.identity.peer_id);
        assert_eq!(session.state, DaemonState::ConnectingDataChannel);
        assert_eq!(session.bridge_state, BridgeSessionState::Pending);
        assert!(session.data_channel.is_none());
        assert!(session.bridge_handle.is_none());

        let published = transport.published.lock().await.clone();
        assert_eq!(published.len(), 2, "replacement flow should publish an ack and a fresh answer");
        assert!(published.iter().all(|(peer_id, _)| *peer_id == offer.identity.peer_id));

        let mut replay_cache = ReplayCache::new(config.security.replay_cache_size);
        let decoded_types = published
            .iter()
            .map(|(_peer_id, payload)| {
                let (_envelope, message, _sender) = offer_codec
                    .decode(payload, &mut replay_cache, None)
                    .expect("published replacement payload should decode");
                message.message_type
            })
            .collect::<Vec<_>>();
        assert_eq!(decoded_types, vec![p2p_core::MessageType::Ack, p2p_core::MessageType::Answer]);

        let status = read_status_file(&status_path).await;
        assert_eq!(status["current_state"], "connecting_data_channel");
        assert_eq!(status["active_session_id"], replacement_session_id.to_string());

        replacement_offer_peer
            .close()
            .await
            .expect("replacement offer peer should close");
        session.peer.close().await.expect("answer session peer should close");
        let _ = tokio::fs::remove_file(&status_path).await;
    }

    #[tokio::test]
    async fn offer_recovery_returns_to_waiting_after_remote_error() {
        let mut config = sample_config();
        let (path, writer) = status_writer_for_test(&mut config, "offer-recovery");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(
            &ctx,
            Err(DaemonError::RemoteError(
                FailureCode::ProtocolError.as_str().to_owned(),
                "remote rejected session".to_owned(),
            )),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "waiting_for_local_client");
        assert_eq!(status["role"], "offer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn offer_recovery_returns_to_waiting_after_remote_close() {
        let mut config = sample_config();
        let (path, writer) = status_writer_for_test(&mut config, "offer-remote-close");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(
            &ctx,
            Err(DaemonError::RemoteClosed("session_closed".to_owned())),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "waiting_for_local_client");
        assert_eq!(status["role"], "offer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_idle_after_target_connect_failure() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "answer-target-connect");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(
            &ctx,
            Err(DaemonError::Tunnel(p2p_tunnel::TunnelError::TargetConnectFailed(
                "connection refused".to_owned(),
            ))),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "idle");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_idle_after_remote_close() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "answer-remote-close");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(
            &ctx,
            Err(DaemonError::RemoteClosed("session_closed".to_owned())),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "idle");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_idle_after_bridge_task_failure() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "answer-bridge-failure");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(
            &ctx,
            Err(DaemonError::Logging("bridge task join error: task 7 panicked".to_owned())),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "idle");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_idle_after_ice_failure() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "answer-ice-failure");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(&ctx, Err(DaemonError::IceFailed(IceConnectionState::Failed)))
            .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "idle");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn steady_state_writer_uses_role_defaults() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "steady-state");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        write_steady_state_status(&ctx).await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "idle");
        assert_eq!(status["role"], "answer");
        assert_eq!(status["mqtt_connected"], true);
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn status_write_failure_is_recoverable() {
        let blocking_file =
            std::env::temp_dir().join(format!("p2ptunnel-status-blocker-{}", SessionId::random()));
        tokio::fs::write(&blocking_file, b"occupied".as_slice())
            .await
            .expect("blocking file should exist");

        let mut config = sample_config();
        config.health.write_status_file = true;
        config.health.status_file = blocking_file.join("status.json");
        let writer = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        write_steady_state_status(&ctx).await;

        assert!(!config.health.status_file.exists(), "status write failure should be ignored");
        let _ = tokio::fs::remove_file(&blocking_file).await;
    }

    #[tokio::test]
    async fn transport_failure_updates_status_to_disconnected_before_retry() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "transport-disconnected");
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        mark_transport_unusable(
            &mut ctx,
            StatusSnapshot { active_session_id: None, current_state: DaemonState::Idle },
            &SignalingError::Protocol("poll failed".to_owned()),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["mqtt_connected"], false);
        assert_eq!(status["current_state"], "idle");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn transport_recovery_updates_status_back_to_connected() {
        let mut config = sample_config();
        let (path, writer) = status_writer_for_test(&mut config, "transport-recovered");
        let mut runtime = connected_runtime();
        runtime.mqtt_connected = false;
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        mark_transport_usable(
            &mut ctx,
            StatusSnapshot {
                active_session_id: Some(SessionId::random()),
                current_state: DaemonState::Negotiating,
            },
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["mqtt_connected"], true);
        assert_eq!(status["current_state"], "negotiating");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn session_recovery_preserves_disconnected_transport_status() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "recovery-keeps-disconnect");
        let mut runtime = connected_runtime();
        runtime.mqtt_connected = false;
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(
            &ctx,
            Err(DaemonError::RemoteError(
                FailureCode::ProtocolError.as_str().to_owned(),
                "session failed".to_owned(),
            )),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["mqtt_connected"], false);
        assert_eq!(status["current_state"], "idle");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn offer_accept_loop_rejects_extra_clients_while_session_is_active() {
        let mut config = sample_config();
        config.tunnel.offer.listen_port = 0;
        let listener =
            OfferListener::bind(&config.tunnel.offer).await.expect("listener should bind");
        let addr = listener.local_addr().expect("listener should have local addr");
        let mut accepted_clients = spawn_offer_accept_loop(listener);

        let mut first_client =
            tokio::net::TcpStream::connect(addr).await.expect("first client should connect");
        let first_session = timeout(Duration::from_secs(1), accepted_clients.recv())
            .await
            .expect("accept loop should yield first session")
            .expect("accept loop should stay alive")
            .expect("first session should be accepted");

        let mut second_client = tokio::net::TcpStream::connect(addr)
            .await
            .expect("second client should connect before prompt close");
        let mut second_buffer = [0_u8; 1];
        let second_read = timeout(Duration::from_secs(1), second_client.read(&mut second_buffer))
            .await
            .expect("second client should be closed promptly")
            .expect("second client read should complete");
        assert_eq!(second_read, 0, "busy client should see immediate close");

        let mut first_buffer = [0_u8; 1];
        assert!(
            timeout(Duration::from_millis(100), first_client.read(&mut first_buffer))
                .await
                .is_err(),
            "active session client should remain connected while busy clients are rejected"
        );

        drop(first_session);

        let _third_client = tokio::net::TcpStream::connect(addr)
            .await
            .expect("third client should connect after release");
        let third_session = timeout(Duration::from_secs(1), accepted_clients.recv())
            .await
            .expect("accept loop should yield next session")
            .expect("accept loop should stay alive")
            .expect("third session should be accepted");
        drop(third_session);
    }

    #[test]
    fn active_answer_busy_offer_replies_only_to_allowed_peers() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys parse");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys parse");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let active_session = SessionId::random();
        let new_offer_session = SessionId::random();
        let message = InnerMessageBuilder::new(
            new_offer_session,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "second-offer".to_owned() }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("offer encodes");

        match classify_active_busy_offer(
            &sample_config(),
            &answer_codec,
            &payload,
            active_session,
            64,
        ) {
            Some(ActiveBusyOfferAction::ReplyBusy { key: _, session_id, sender }) => {
                assert_eq!(session_id, new_offer_session);
                assert_eq!(sender.peer_id, offer.identity.peer_id);
            }
            other => panic!("expected busy reply for allowed peer, got {other:?}"),
        }
    }

    #[test]
    fn active_answer_busy_offer_duplicate_is_suppressed_per_session() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys parse");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys parse");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let active_session = SessionId::random();
        let new_offer_session = SessionId::random();
        let message = InnerMessageBuilder::new(
            new_offer_session,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "second-offer".to_owned() }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("offer encodes");

        let first = classify_active_busy_offer(
            &sample_config(),
            &answer_codec,
            &payload,
            active_session,
            64,
        )
        .expect("first foreign offer should classify");
        let second = classify_active_busy_offer(
            &sample_config(),
            &answer_codec,
            &payload,
            active_session,
            64,
        )
        .expect("duplicate foreign offer should still classify");
        let mut dedupe = ActiveBusyOfferCache::new(64);

        let first_key = match first {
            ActiveBusyOfferAction::ReplyBusy { key, .. } => key,
            other => panic!("expected busy reply for first offer, got {other:?}"),
        };
        let second_key = match second {
            ActiveBusyOfferAction::ReplyBusy { key, .. } => key,
            other => panic!("expected busy reply for duplicate offer, got {other:?}"),
        };

        assert_eq!(first_key, second_key);
        assert!(dedupe.record_if_new(first_key), "first offer should be new");
        assert!(!dedupe.record_if_new(second_key), "duplicate offer should be suppressed");
    }

    #[test]
    fn replayed_active_busy_offer_is_detected_before_full_decode() {
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys parse");
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let new_offer_session = SessionId::random();
        let message = InnerMessageBuilder::new(
            new_offer_session,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "second-offer".to_owned() }));
        let (envelope, _payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("offer encodes");
        let mut dedupe = ActiveBusyOfferCache::new(64);
        let key = ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id };
        assert!(dedupe.record_if_new(key), "authenticated busy offer should seed dedupe");

        let tampered_payload =
            OuterEnvelope { ciphertext: vec![0_u8; envelope.ciphertext.len()], ..envelope }
                .encode()
                .expect("tampered envelope should encode");

        assert_eq!(
            replayed_active_busy_offer_key(&tampered_payload, &dedupe),
            Some(key),
            "replayed duplicate should be suppressed from outer-envelope metadata before decode"
        );
    }

    #[test]
    fn active_answer_busy_offer_ignores_authorized_but_disallowed_peer() {
        let allowed = generate_identity("offer-home").expect("allowed identity");
        let disallowed = generate_identity("offer-guest").expect("disallowed identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys = AuthorizedKeys::parse(&format!(
            "{}\n{}\n",
            allowed.public_identity.render(),
            disallowed.public_identity.render()
        ))
        .expect("answer keys parse");
        let disallowed_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("disallowed keys parse");
        let disallowed_codec = SignalCodec::new(&disallowed.identity, &disallowed_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let message = InnerMessageBuilder::new(
            SessionId::random(),
            disallowed.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "guest-offer".to_owned() }));
        let (_envelope, payload) = disallowed_codec
            .encode_for_peer(
                disallowed_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("disallowed offer encodes");

        assert!(matches!(
            classify_active_busy_offer(
                &sample_config(),
                &answer_codec,
                &payload,
                SessionId::random(),
                64
            ),
            Some(ActiveBusyOfferAction::Ignore)
        ));
    }

    #[test]
    fn active_answer_busy_offer_ignores_unauthorized_peer() {
        let allowed = generate_identity("offer-home").expect("allowed identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let rogue = generate_identity("rogue-peer").expect("rogue identity");
        let answer_keys =
            AuthorizedKeys::parse(&allowed.public_identity.render()).expect("answer keys parse");
        let rogue_keys = AuthorizedKeys::parse(&answer.public_identity.render())
            .expect("rogue recipient keys parse");
        let rogue_codec = SignalCodec::new(&rogue.identity, &rogue_keys, 120, 300);
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let message = InnerMessageBuilder::new(
            SessionId::random(),
            rogue.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "rogue-offer".to_owned() }));
        let (_envelope, payload) = rogue_codec
            .encode_for_peer(
                rogue_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("rogue offer encodes");

        assert!(
            classify_active_busy_offer(
                &sample_config(),
                &answer_codec,
                &payload,
                SessionId::random(),
                64
            )
            .is_none()
        );
    }
}
