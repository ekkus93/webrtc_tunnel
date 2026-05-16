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

mod error;
mod logging;
mod status;

use std::collections::{HashMap, HashSet, VecDeque};
use std::env;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{
    AppConfig, ConfigError, DaemonState, FailureCode, ForwardOfferConfig, ForwardTable, Kid, MsgId,
    NodeRole, PeerId, SessionId,
};
use p2p_crypto::{AuthorizedKey, AuthorizedKeys, IdentityFile, kid_from_signing_key};
use p2p_signaling::{
    AckBody, AnswerBody, CloseBody, DecodedSignal, EndOfCandidatesBody, ErrorBody,
    IceCandidateBody, InnerMessage, InnerMessageBuilder, MessageBody, MqttSignalingTransport,
    OfferBody, OuterEnvelope, ReplayStatus, SignalCodec, SignalingError, SignalingSession,
};
use p2p_tunnel::{OfferClient, OfferListener};
use p2p_webrtc::{DataChannelHandle, IceCandidateSignal, IceConnectionState, WebRtcPeer};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tokio::time::{interval, sleep};

pub use error::DaemonError;
pub use logging::{redact_candidate, redact_sdp, redact_secret, setup_logging};
pub use status::{DaemonStatus, SessionStatus, StatusWriter};

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

const DAEMON_RUNTIME_RETRY_DELAY: Duration = Duration::from_secs(1);
const ANSWER_SESSION_CAPACITY: usize = 16;

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
#[cfg(test)]
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

    #[cfg(test)]
    fn contains(&self, key: &ActiveBusyOfferKey) -> bool {
        self.seen.contains(key)
    }
}

#[derive(Debug)]
struct DuplicateActiveAckCache {
    capacity: usize,
    order: VecDeque<MsgId>,
    seen: HashSet<MsgId>,
}

impl DuplicateActiveAckCache {
    fn new(capacity: usize) -> Self {
        Self { capacity: capacity.max(1), order: VecDeque::new(), seen: HashSet::new() }
    }

    fn record_if_new(&mut self, msg_id: MsgId) -> bool {
        if self.seen.contains(&msg_id) {
            return false;
        }
        if self.order.len() == self.capacity {
            if let Some(expired) = self.order.pop_front() {
                self.seen.remove(&expired);
            }
        }
        self.order.push_back(msg_id);
        self.seen.insert(msg_id);
        true
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

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
struct SessionGeneration(u64);

#[derive(Clone, Debug)]
struct SessionStatusSnapshot {
    session_id: SessionId,
    generation: SessionGeneration,
    remote_peer_id: PeerId,
    state: DaemonState,
    data_channel_open: bool,
    configured_forward_ids: Vec<String>,
}

impl SessionStatusSnapshot {
    fn from_session(
        config: &AppConfig,
        session: &ActiveSession,
        generation: SessionGeneration,
    ) -> Self {
        Self {
            session_id: session.session_id,
            generation,
            remote_peer_id: session.remote_peer_id.clone(),
            state: session.state,
            data_channel_open: session
                .data_channel
                .as_ref()
                .is_some_and(|channel| channel.is_open()),
            configured_forward_ids: config
                .forwards
                .iter()
                .map(|forward| forward.id.clone())
                .collect(),
        }
    }

    fn to_status(&self) -> SessionStatus {
        SessionStatus::new(
            self.session_id,
            self.remote_peer_id.clone(),
            self.state,
            self.data_channel_open,
            self.configured_forward_ids.clone(),
        )
    }
}

#[derive(Clone, Debug)]
struct AnswerStatusSnapshot {
    current_state: DaemonState,
    sessions: Vec<SessionStatusSnapshot>,
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

struct PublishRequest {
    recipient: AuthorizedKey,
    outgoing: OutgoingSignal,
    status: SessionStatusSnapshot,
    result: oneshot::Sender<Result<PublishedSignal, DaemonError>>,
}

struct PublishedSignal {
    msg_id: MsgId,
    message_type: p2p_core::MessageType,
    payload: Vec<u8>,
}

enum AnswerSessionEvent {
    Publish(Box<PublishRequest>),
    RawPublish {
        peer_id: PeerId,
        payload: Vec<u8>,
        status: SessionStatusSnapshot,
        result: oneshot::Sender<Result<(), DaemonError>>,
    },
    Status(SessionStatusSnapshot),
    Replaced {
        old_session_id: SessionId,
        new_session_id: SessionId,
        remote_peer_id: PeerId,
        generation: SessionGeneration,
        status: SessionStatusSnapshot,
    },
    Ended {
        session_id: SessionId,
        generation: SessionGeneration,
        remote_peer_id: PeerId,
        result: Result<(), DaemonError>,
    },
}

struct AnswerSessionHandle {
    generation: SessionGeneration,
    remote_peer_id: PeerId,
    inbound: mpsc::Sender<DecodedSignal>,
    status: SessionStatusSnapshot,
    task: JoinHandle<()>,
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
    duplicate_active_acks: DuplicateActiveAckCache,
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
            duplicate_active_acks: DuplicateActiveAckCache::new(replay_cache_size),
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
        let mut transport = transport;
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
    run_offer_daemon_inner(config, local_identity, authorized_keys, &mut transport, session_hook)
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
    validate_config_authorized_peers(&config, &authorized_keys)?;
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

    let listeners = bind_offer_listeners(&config).await?;
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
                    &config,
                    &local_identity,
                    &authorized_keys,
                    &codec,
                    &mut transport,
                    &mut ctx,
                    &event_tx,
                    &mut replay_cache,
                    &mut sessions_by_id,
                    &mut session_by_peer,
                    &mut next_generation,
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

pub fn apply_env_overrides(config: &mut AppConfig) -> Result<(), ConfigError> {
    apply_override_pairs(config, env::vars())
}

#[allow(clippy::too_many_arguments)]
async fn handle_answer_daemon_payload<T: DaemonSignalingTransport>(
    config: &Arc<AppConfig>,
    local_identity: &Arc<IdentityFile>,
    authorized_keys: &Arc<AuthorizedKeys>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    replay_cache: &mut p2p_signaling::ReplayCache,
    sessions_by_id: &mut HashMap<SessionId, AnswerSessionHandle>,
    session_by_peer: &mut HashMap<PeerId, SessionId>,
    next_generation: &mut u64,
    payload: Vec<u8>,
) {
    tracing::debug!(
        payload_len = payload.len(),
        role = ?config.node.role,
        "received signaling payload in answer daemon"
    );

    let decoded = match codec.decode_with_replay_status(&payload, replay_cache, None) {
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

    if let Some(handle) = sessions_by_id.get(&decoded.message.session_id) {
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
        && let Some(existing_session_id) = session_by_peer.get(&decoded.sender.peer_id).copied()
        && let Some(handle) = sessions_by_id.get(&existing_session_id)
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
            if session_by_peer.contains_key(&decoded.sender.peer_id)
                || sessions_by_id.len() >= ANSWER_SESSION_CAPACITY
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
            let generation = SessionGeneration(*next_generation);
            *next_generation = next_generation.saturating_add(1);
            if let Err(error) = start_answer_session_from_offer(
                config,
                local_identity,
                authorized_keys,
                codec,
                transport,
                ctx,
                event_tx,
                sessions_by_id,
                session_by_peer,
                generation,
                decoded.envelope,
                decoded.message,
                decoded.sender,
                &offer,
            )
            .await
            {
                recover_daemon_after_session(ctx, Err(error)).await;
            }
            write_answer_registry_status(ctx, sessions_by_id).await;
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

#[allow(clippy::too_many_arguments)]
async fn start_answer_session_from_offer<T: DaemonSignalingTransport>(
    config: &Arc<AppConfig>,
    local_identity: &Arc<IdentityFile>,
    authorized_keys: &Arc<AuthorizedKeys>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    sessions_by_id: &mut HashMap<SessionId, AnswerSessionHandle>,
    session_by_peer: &mut HashMap<PeerId, SessionId>,
    generation: SessionGeneration,
    envelope: OuterEnvelope,
    message: InnerMessage,
    sender: AuthorizedKey,
    offer: &OfferBody,
) -> Result<(), DaemonError> {
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
    sessions_by_id.insert(
        session_id,
        AnswerSessionHandle {
            generation,
            remote_peer_id: remote_peer_id.clone(),
            inbound: inbound_tx,
            status,
            task,
        },
    );
    session_by_peer.insert(remote_peer_id, session_id);
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
                    mark_transport_usable(
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

pub fn apply_offer_overrides(config: &mut AppConfig, broker_url: Option<String>) {
    if let Some(broker_url) = broker_url {
        config.broker.url = broker_url;
    }
}

pub fn apply_answer_overrides(config: &mut AppConfig, broker_url: Option<String>) {
    if let Some(broker_url) = broker_url {
        config.broker.url = broker_url;
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
                config, event_tx, generation, session, envelope, message, sender, &offer,
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

#[allow(clippy::too_many_arguments)]
async fn maybe_replace_pending_same_peer_session(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    envelope: OuterEnvelope,
    message: InnerMessage,
    sender: AuthorizedKey,
    offer: &OfferBody,
) -> Result<(), DaemonError> {
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

async fn bind_offer_listeners(config: &AppConfig) -> Result<Vec<OfferListener>, DaemonError> {
    let table = ForwardTable::new(&config.forwards);
    let mut listeners = Vec::new();
    for bind in table.offer_listeners().map_err(|error| {
        DaemonError::Config(ConfigError::InvalidConfig(format!(
            "invalid offer forward listeners: {error:?}"
        )))
    })? {
        let offer =
            ForwardOfferConfig { listen_host: bind.listen_host, listen_port: bind.listen_port };
        let listener = OfferListener::bind(bind.forward_id, &offer).await?;
        tracing::info!(
            forward_id = listener.forward_id(),
            local_addr = %listener.local_addr()?,
            "listening for local forward clients"
        );
        listeners.push(listener);
    }
    Ok(listeners)
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

fn steady_state_for_role(role: &NodeRole) -> DaemonState {
    match role {
        NodeRole::Offer => DaemonState::WaitingForLocalClient,
        NodeRole::Answer => DaemonState::Serving,
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
            ctx.config.forwards.iter().map(|forward| forward.id.clone()).collect(),
        ),
    )
    .await;
}

async fn write_answer_status(ctx: &RuntimeContext<'_>, snapshot: AnswerStatusSnapshot) {
    write_status_or_log(
        ctx.status,
        DaemonStatus::with_sessions(
            ctx.config.node.peer_id.clone(),
            ctx.config.node.role.clone(),
            ctx.runtime.mqtt_connected,
            snapshot.current_state,
            ctx.config.forwards.iter().map(|forward| forward.id.clone()).collect(),
            ANSWER_SESSION_CAPACITY,
            snapshot.sessions.iter().map(SessionStatusSnapshot::to_status).collect(),
        ),
    )
    .await;
}

async fn write_answer_registry_status(
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

async fn publish_answer_session_request<T: DaemonSignalingTransport>(
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
                    mark_transport_usable(
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
    let _ = request.result.send(result);
}

async fn request_session_publish(
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

async fn request_raw_session_publish(
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

fn duplicate_active_session_ack_message(
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

#[cfg(test)]
fn replayed_active_busy_offer_key(
    payload: &[u8],
    active_busy_offers: &ActiveBusyOfferCache,
) -> Option<ActiveBusyOfferKey> {
    let envelope = OuterEnvelope::decode(payload).ok()?;
    let key = ActiveBusyOfferKey { sender_kid: envelope.sender_kid, msg_id: envelope.msg_id };
    active_busy_offers.contains(&key).then_some(key)
}

#[cfg(test)]
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
    config
        .forwards
        .iter()
        .filter_map(|forward| forward.answer.as_ref())
        .any(|answer| answer.allow_remote_peers.contains(sender_peer_id))
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
    session.data_channel.as_ref().is_some_and(|channel| channel.is_open())
}

fn offer_remote_peer_id(config: &AppConfig) -> Result<PeerId, DaemonError> {
    config.peer.as_ref().map(|peer| peer.remote_peer_id.clone()).ok_or_else(|| {
        DaemonError::Config(ConfigError::InvalidConfig(
            "[peer].remote_peer_id must be set for offer role".to_owned(),
        ))
    })
}

fn validate_config_authorized_peers(
    config: &AppConfig,
    authorized_keys: &AuthorizedKeys,
) -> Result<(), DaemonError> {
    match config.node.role {
        NodeRole::Offer => {
            let remote_peer_id = offer_remote_peer_id(config)?;
            if authorized_keys.get_by_peer_id(&remote_peer_id).is_none() {
                return Err(DaemonError::MissingAuthorizedPeer(remote_peer_id.to_string()));
            }
        }
        NodeRole::Answer => {
            for forward in &config.forwards {
                if let Some(answer) = &forward.answer {
                    for peer_id in &answer.allow_remote_peers {
                        if authorized_keys.get_by_peer_id(peer_id).is_none() {
                            return Err(DaemonError::MissingAuthorizedPeer(peer_id.to_string()));
                        }
                    }
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
fn first_offer_forward(config: &AppConfig) -> Result<(&str, &ForwardOfferConfig), DaemonError> {
    config
        .forwards
        .iter()
        .find_map(|forward| forward.offer.as_ref().map(|offer| (forward.id.as_str(), offer)))
        .ok_or_else(|| {
            DaemonError::Config(ConfigError::InvalidConfig(
                "at least one [forwards.offer] rule is required".to_owned(),
            ))
        })
}

#[cfg(test)]
fn first_answer_forward(config: &AppConfig) -> Result<&p2p_core::ForwardAnswerConfig, DaemonError> {
    config.forwards.iter().find_map(|forward| forward.answer.as_ref()).ok_or_else(|| {
        DaemonError::Config(ConfigError::InvalidConfig(
            "at least one [forwards.answer] rule is required".to_owned(),
        ))
    })
}

#[cfg(test)]
fn first_offer_forward_mut(config: &mut AppConfig) -> Option<&mut ForwardOfferConfig> {
    config.forwards.iter_mut().find_map(|forward| forward.offer.as_mut())
}

#[cfg(test)]
fn first_answer_forward_mut(config: &mut AppConfig) -> Option<&mut p2p_core::ForwardAnswerConfig> {
    config.forwards.iter_mut().find_map(|forward| forward.answer.as_mut())
}

fn apply_override_pairs(
    config: &mut AppConfig,
    overrides: impl IntoIterator<Item = (String, String)>,
) -> Result<(), ConfigError> {
    for (key, value) in overrides {
        match key.as_str() {
            "P2PTUNNEL_BROKER_URL" => config.broker.url = value,
            "P2PTUNNEL_BROKER_USERNAME" => config.broker.username = value,
            "P2PTUNNEL_BROKER_PASSWORD_FILE" => config.broker.password_file = value.into(),
            "P2PTUNNEL_LISTEN_PORT" => {
                return Err(legacy_forward_env_error(&key, "[forwards.offer].listen_port"));
            }
            "P2PTUNNEL_TARGET_HOST" => {
                return Err(legacy_forward_env_error(&key, "[forwards.answer].target_host"));
            }
            "P2PTUNNEL_TARGET_PORT" => {
                return Err(legacy_forward_env_error(&key, "[forwards.answer].target_port"));
            }
            _ => {}
        }
    }
    Ok(())
}

fn legacy_forward_env_error(name: &str, replacement: &str) -> ConfigError {
    ConfigError::InvalidConfig(format!(
        "{name} is no longer supported in v0.2 config. Use {replacement} in config.toml instead."
    ))
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
    use std::collections::HashMap;
    use std::future::pending;
    use std::path::Path;
    use std::path::PathBuf;
    use std::sync::Arc;
    use std::time::Duration;

    use p2p_core::AppConfig;
    use p2p_core::{
        ACK_RETRY_TIMEOUT_SECS, BrokerConfig, BrokerTlsConfig, FailureCode, ForwardAnswerConfig,
        ForwardOfferConfig, ForwardRule, HealthConfig, LoggingConfig, MsgId, NodeConfig, NodeRole,
        PeerConfig, PeerId, ReconnectConfig, SecurityConfig, SessionId, TunnelConfig, WebRtcConfig,
    };
    use p2p_crypto::{AuthorizedKeys, generate_identity};
    use p2p_signaling::{
        AckBody, AnswerBody, CloseBody, EndOfCandidatesBody, ErrorBody, IceCandidateBody,
        InnerMessageBuilder, MessageBody, OfferBody, OuterEnvelope, PingBody,
        RenegotiateRequestBody, ReplayCache, ReplayStatus, SignalCodec, SignalingError,
    };
    use p2p_tunnel::OfferClient;
    use serde_json::Value;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};
    use tokio::sync::{Mutex, mpsc};
    use tokio::time::{sleep, timeout};

    use super::{
        ActiveBusyOfferAction, ActiveBusyOfferCache, ActiveBusyOfferKey, ActiveSession,
        AnswerSessionEvent, AnswerSessionHandle, BridgeSessionState, DaemonError,
        DaemonRuntimeState, DaemonSignalingTransport, DaemonState, IceConnectionState,
        OfferListener, OfferSessionPayloadOutcome, RuntimeContext, SessionGeneration,
        SessionStatusSnapshot, StatusSnapshot, StatusWriter, WebRtcPeer, apply_answer_overrides,
        apply_offer_overrides, apply_override_pairs, classify_active_busy_offer,
        compute_backoff_delay, decode_idle_signaling_message, duplicate_active_session_ack_message,
        handle_answer_daemon_payload, handle_answer_incoming_data_channel,
        handle_answer_session_event, handle_answer_session_message, handle_offer_session_message,
        mark_transport_unusable, mark_transport_usable, maybe_ack_duplicate_active_session_message,
        maybe_replace_pending_answer_session, process_answer_session_signal,
        process_offer_session_payload, recover_daemon_after_session,
        replayed_active_busy_offer_key, run_offer_daemon_with_transport_and_test_hook,
        should_ack_idle_offer, should_attempt_offer_reconnect, should_continue_reconnect_attempt,
        spawn_offer_accept_loop, steady_state_for_role, write_answer_registry_status,
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

    struct ScriptedPollingTransport {
        outcomes: mpsc::UnboundedReceiver<Result<Option<Vec<u8>>, SignalingError>>,
    }

    #[allow(async_fn_in_trait)]
    impl DaemonSignalingTransport for ScriptedPollingTransport {
        async fn subscribe_own_topic(&mut self) -> Result<(), SignalingError> {
            Ok(())
        }

        async fn publish_signal(
            &mut self,
            _peer_id: &PeerId,
            _topic_prefix: &str,
            _payload: Vec<u8>,
        ) -> Result<(), SignalingError> {
            Ok(())
        }

        async fn poll_signal_payload(&mut self) -> Result<Option<Vec<u8>>, SignalingError> {
            match self.outcomes.recv().await {
                Some(outcome) => outcome,
                None => pending().await,
            }
        }
    }

    fn sample_config() -> AppConfig {
        AppConfig {
            format: "p2ptunnel-config-v2".to_owned(),
            node: NodeConfig {
                peer_id: "offer-home".parse().expect("peer id"),
                role: NodeRole::Offer,
            },
            peer: Some(PeerConfig { remote_peer_id: "answer-office".parse().expect("peer id") }),
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
                read_chunk_size: 1024,
                local_eof_grace_ms: 250,
                remote_eof_grace_ms: 250,
            },
            forwards: vec![ForwardRule {
                id: "ssh".to_owned(),
                offer: Some(ForwardOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 5000,
                }),
                answer: Some(ForwardAnswerConfig {
                    target_host: "127.0.0.1".to_owned(),
                    target_port: 22,
                    allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
                }),
            }],
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

    async fn wait_for_status<P>(path: &Path, predicate: P) -> Value
    where
        P: Fn(&Value) -> bool,
    {
        timeout(Duration::from_secs(5), async {
            loop {
                if path.exists() {
                    if let Ok(content) = tokio::fs::read_to_string(path).await {
                        if let Ok(status) = serde_json::from_str::<Value>(&content) {
                            if predicate(&status) {
                                return status;
                            }
                        }
                    }
                }
                sleep(Duration::from_millis(25)).await;
            }
        })
        .await
        .expect("status should reach expected state")
    }

    fn connected_runtime() -> DaemonRuntimeState {
        DaemonRuntimeState::new_connected()
    }

    fn test_session_status(
        session_id: SessionId,
        generation: SessionGeneration,
        remote_peer_id: PeerId,
        state: DaemonState,
    ) -> SessionStatusSnapshot {
        SessionStatusSnapshot {
            session_id,
            generation,
            remote_peer_id,
            state,
            data_channel_open: matches!(state, DaemonState::TunnelOpen),
            configured_forward_ids: vec!["ssh".to_owned()],
        }
    }

    fn test_answer_handle(
        session_id: SessionId,
        generation: SessionGeneration,
        remote_peer_id: PeerId,
        state: DaemonState,
    ) -> (AnswerSessionHandle, mpsc::Receiver<p2p_signaling::DecodedSignal>) {
        let (tx, rx) = mpsc::channel(4);
        let status = test_session_status(session_id, generation, remote_peer_id.clone(), state);
        let task = tokio::spawn(async { pending::<()>().await });
        (AnswerSessionHandle { generation, remote_peer_id, inbound: tx, status, task }, rx)
    }

    struct AnswerRoutingFixture {
        config: Arc<AppConfig>,
        local_identity: Arc<p2p_crypto::IdentityFile>,
        authorized_keys: Arc<AuthorizedKeys>,
        offer_identity: p2p_crypto::GeneratedIdentity,
        offer_keys: AuthorizedKeys,
        active_session: SessionId,
        sessions_by_id: HashMap<SessionId, AnswerSessionHandle>,
        session_by_peer: HashMap<PeerId, SessionId>,
        receiver: mpsc::Receiver<p2p_signaling::DecodedSignal>,
        transport: RecordingTransport,
        replay_cache: ReplayCache,
        next_generation: u64,
    }

    impl AnswerRoutingFixture {
        fn new() -> Self {
            let mut config = sample_config();
            config.node.role = NodeRole::Answer;
            config.node.peer_id = "answer-office".parse().expect("answer peer id");
            config.health.write_status_file = false;
            let config = Arc::new(config);
            let answer = generate_identity("answer-office").expect("answer identity");
            let offer_identity = generate_identity("offer-home").expect("offer identity");
            let authorized_keys = Arc::new(
                AuthorizedKeys::parse(&offer_identity.public_identity.render())
                    .expect("answer keys"),
            );
            let offer_keys =
                AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
            let local_identity = Arc::new(answer.identity);
            let active_session = SessionId::random();
            let (handle, receiver) = test_answer_handle(
                active_session,
                SessionGeneration(1),
                offer_identity.identity.peer_id.clone(),
                DaemonState::TunnelOpen,
            );
            let mut sessions_by_id = HashMap::new();
            sessions_by_id.insert(active_session, handle);
            let mut session_by_peer = HashMap::new();
            session_by_peer.insert(offer_identity.identity.peer_id.clone(), active_session);
            Self {
                config,
                local_identity,
                authorized_keys,
                offer_identity,
                offer_keys,
                active_session,
                sessions_by_id,
                session_by_peer,
                receiver,
                transport: RecordingTransport::default(),
                replay_cache: ReplayCache::new(64),
                next_generation: 1,
            }
        }

        fn unknown_session_non_offer_bodies() -> Vec<(&'static str, MessageBody)> {
            vec![
                ("answer", MessageBody::Answer(AnswerBody { sdp: "answer-sdp".to_owned() })),
                (
                    "ice_candidate",
                    MessageBody::IceCandidate(IceCandidateBody {
                        candidate: None,
                        sdp_mid: None,
                        sdp_mline_index: None,
                    }),
                ),
                ("ack", MessageBody::Ack(AckBody { ack_msg_id: MsgId::random().into_bytes() })),
                ("ping", MessageBody::Ping(PingBody { seq: 1 })),
                ("pong", MessageBody::Pong(PingBody { seq: 2 })),
                (
                    "close",
                    MessageBody::Close(CloseBody {
                        reason_code: "test_close".to_owned(),
                        message: None,
                    }),
                ),
                (
                    "error",
                    MessageBody::Error(ErrorBody {
                        code: FailureCode::ProtocolError.as_str().to_owned(),
                        message: "test error".to_owned(),
                        fatal: false,
                    }),
                ),
                ("ice_restart_request", MessageBody::IceRestartRequest),
                (
                    "renegotiate_request",
                    MessageBody::RenegotiateRequest(RenegotiateRequestBody {
                        reason: "test".to_owned(),
                    }),
                ),
                ("end_of_candidates", MessageBody::EndOfCandidates(EndOfCandidatesBody::default())),
            ]
        }

        fn ack_required_duplicate_bodies() -> Vec<(&'static str, MessageBody)> {
            vec![
                ("offer", MessageBody::Offer(OfferBody { sdp: "offer-sdp".to_owned() })),
                ("answer", MessageBody::Answer(AnswerBody { sdp: "answer-sdp".to_owned() })),
                (
                    "ice_candidate",
                    MessageBody::IceCandidate(IceCandidateBody {
                        candidate: None,
                        sdp_mid: None,
                        sdp_mline_index: None,
                    }),
                ),
                (
                    "close",
                    MessageBody::Close(CloseBody { reason_code: "done".to_owned(), message: None }),
                ),
                (
                    "error",
                    MessageBody::Error(ErrorBody {
                        code: FailureCode::ProtocolError.as_str().to_owned(),
                        message: "duplicate retry".to_owned(),
                        fatal: true,
                    }),
                ),
                ("ice_restart_request", MessageBody::IceRestartRequest),
                (
                    "renegotiate_request",
                    MessageBody::RenegotiateRequest(RenegotiateRequestBody {
                        reason: "duplicate retry".to_owned(),
                    }),
                ),
            ]
        }

        fn non_ack_required_duplicate_bodies() -> Vec<(&'static str, MessageBody)> {
            vec![
                ("ack", MessageBody::Ack(AckBody { ack_msg_id: MsgId::random().into_bytes() })),
                ("ping", MessageBody::Ping(PingBody { seq: 1 })),
                ("pong", MessageBody::Pong(PingBody { seq: 2 })),
                ("end_of_candidates", MessageBody::EndOfCandidates(EndOfCandidatesBody::default())),
            ]
        }

        fn encode_from_offer(&self, session_id: SessionId, body: MessageBody) -> Vec<u8> {
            let offer_codec =
                SignalCodec::new(&self.offer_identity.identity, &self.offer_keys, 120, 300);
            let message = InnerMessageBuilder::new(
                session_id,
                self.offer_identity.identity.peer_id.clone(),
                self.local_identity.peer_id.clone(),
            )
            .build(body);
            let (_envelope, payload) = offer_codec
                .encode_for_peer(
                    self.offer_keys
                        .get_by_peer_id(&self.local_identity.peer_id)
                        .expect("answer key"),
                    &message,
                    false,
                )
                .expect("payload encodes");
            payload
        }

        async fn handle_payload(&mut self, payload: Vec<u8>) {
            let codec = SignalCodec::new(&self.local_identity, &self.authorized_keys, 120, 300);
            let status = StatusWriter::new(&self.config);
            let mut runtime = connected_runtime();
            let mut ctx =
                RuntimeContext { config: &self.config, status: &status, runtime: &mut runtime };
            let (event_tx, _event_rx) = mpsc::channel(4);
            handle_answer_daemon_payload(
                &self.config,
                &self.local_identity,
                &self.authorized_keys,
                &codec,
                &mut self.transport,
                &mut ctx,
                &event_tx,
                &mut self.replay_cache,
                &mut self.sessions_by_id,
                &mut self.session_by_peer,
                &mut self.next_generation,
                payload,
            )
            .await;
        }

        async fn published_len(&self) -> usize {
            self.transport.published.lock().await.len()
        }
    }

    async fn connected_channels(
        webrtc: &WebRtcConfig,
    ) -> (WebRtcPeer, WebRtcPeer, p2p_webrtc::DataChannelHandle, p2p_webrtc::DataChannelHandle)
    {
        let offer_peer = WebRtcPeer::new(webrtc).await.expect("offer peer should build");
        let answer_peer = WebRtcPeer::new(webrtc).await.expect("answer peer should build");

        let offer_channel =
            offer_peer.create_data_channel().await.expect("offer data channel should build");
        let offer_sdp = offer_peer.create_offer().await.expect("offer SDP should build");
        answer_peer.apply_remote_offer(&offer_sdp).await.expect("answer should accept offer");
        let answer_sdp = answer_peer.create_answer().await.expect("answer SDP should build");
        offer_peer.apply_remote_answer(&answer_sdp).await.expect("offer should accept answer");

        let answer_channel =
            timeout(Duration::from_secs(10), answer_peer.next_incoming_data_channel())
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
        let original_port = super::first_offer_forward(&config).expect("offer").1.listen_port;
        apply_offer_overrides(&mut config, Some("mqtts://override".to_owned()));
        assert_eq!(config.broker.url, "mqtts://override");
        assert_eq!(
            super::first_offer_forward(&config).expect("offer").1.listen_port,
            original_port
        );
    }

    #[test]
    fn apply_answer_cli_overrides() {
        let mut config = sample_config();
        let original_answer = super::first_answer_forward(&config).expect("answer").clone();
        apply_answer_overrides(&mut config, Some("mqtts://override".to_owned()));
        assert_eq!(config.broker.url, "mqtts://override");
        let answer = super::first_answer_forward(&config).expect("answer");
        assert_eq!(answer.target_host, original_answer.target_host);
        assert_eq!(answer.target_port, original_answer.target_port);
    }

    #[test]
    fn env_overrides_update_global_config() {
        let mut config = sample_config();

        apply_override_pairs(
            &mut config,
            [("P2PTUNNEL_BROKER_URL".to_owned(), "mqtts://env".to_owned())],
        )
        .expect("global env override should apply");
        assert_eq!(config.broker.url, "mqtts://env");
    }

    #[test]
    fn legacy_first_forward_env_overrides_are_rejected() {
        for key in ["P2PTUNNEL_LISTEN_PORT", "P2PTUNNEL_TARGET_HOST", "P2PTUNNEL_TARGET_PORT"] {
            let mut config = sample_config();
            let error = apply_override_pairs(&mut config, [(key.to_owned(), "ignored".to_owned())])
                .expect_err("legacy first-forward env override should fail");
            assert!(error.to_string().contains("no longer supported in v0.2 config"));
        }
    }

    #[test]
    fn offer_remote_peer_must_exist_in_authorized_keys() {
        let config = sample_config();
        let authorized_keys = AuthorizedKeys::parse("").expect("empty authorized keys");

        assert!(matches!(
            super::validate_config_authorized_peers(&config, &authorized_keys),
            Err(DaemonError::MissingAuthorizedPeer(peer)) if peer == "answer-office"
        ));
    }

    #[test]
    fn answer_allowlist_peers_must_exist_in_authorized_keys() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let authorized_keys = AuthorizedKeys::parse("").expect("empty authorized keys");

        assert!(matches!(
            super::validate_config_authorized_peers(&config, &authorized_keys),
            Err(DaemonError::MissingAuthorizedPeer(peer)) if peer == "offer-home"
        ));
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

    #[tokio::test]
    async fn answer_daemon_routes_only_authenticated_sender_and_session() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        config.forwards[0].answer.as_mut().expect("answer forward").allow_remote_peers =
            vec!["offer-a".parse().expect("peer a"), "offer-b".parse().expect("peer b")];
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_a = generate_identity("offer-a").expect("offer a identity");
        let offer_b = generate_identity("offer-b").expect("offer b identity");
        let answer_keys = AuthorizedKeys::parse(&format!(
            "{}\n{}",
            offer_a.public_identity.render(),
            offer_b.public_identity.render()
        ))
        .expect("answer keys");
        let offer_b_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer b keys");
        let local_identity = Arc::new(answer.identity);
        let authorized_keys = Arc::new(answer_keys);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_b_codec = SignalCodec::new(&offer_b.identity, &offer_b_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let session_a = SessionId::random();
        let session_b = SessionId::random();
        let (handle_a, mut rx_a) = test_answer_handle(
            session_a,
            SessionGeneration(1),
            offer_a.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        let (handle_b, mut rx_b) = test_answer_handle(
            session_b,
            SessionGeneration(2),
            offer_b.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(session_a, handle_a);
        sessions_by_id.insert(session_b, handle_b);
        session_by_peer.insert(offer_a.identity.peer_id.clone(), session_a);
        session_by_peer.insert(offer_b.identity.peer_id.clone(), session_b);

        let message = InnerMessageBuilder::new(
            session_b,
            offer_b.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "route me".to_owned(),
            fatal: false,
        }));
        let (_envelope, payload) = offer_b_codec
            .encode_for_peer(
                offer_b_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            payload,
        )
        .await;

        assert!(rx_a.try_recv().is_err());
        let routed = rx_b.try_recv().expect("session b should receive authenticated signal");
        assert_eq!(routed.sender.peer_id, offer_b.identity.peer_id);
        assert_eq!(routed.message.session_id, session_b);
    }

    #[tokio::test]
    async fn forged_outer_sender_kid_is_not_routed_to_matching_peer_session() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        config.forwards[0].answer.as_mut().expect("answer forward").allow_remote_peers =
            vec!["offer-a".parse().expect("peer a"), "offer-b".parse().expect("peer b")];
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_a = generate_identity("offer-a").expect("offer a identity");
        let offer_b = generate_identity("offer-b").expect("offer b identity");
        let authorized_keys = Arc::new(
            AuthorizedKeys::parse(&format!(
                "{}\n{}",
                offer_a.public_identity.render(),
                offer_b.public_identity.render()
            ))
            .expect("answer keys"),
        );
        let offer_b_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer b keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_b_codec = SignalCodec::new(&offer_b.identity, &offer_b_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let session_a = SessionId::random();
        let (handle_a, mut rx_a) = test_answer_handle(
            session_a,
            SessionGeneration(1),
            offer_a.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(session_a, handle_a);
        session_by_peer.insert(offer_a.identity.peer_id.clone(), session_a);

        let message = InnerMessageBuilder::new(
            session_a,
            offer_b.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "forged route".to_owned(),
            fatal: false,
        }));
        let (mut envelope, _payload) = offer_b_codec
            .encode_for_peer(
                offer_b_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");
        envelope.sender_kid =
            p2p_crypto::kid_from_signing_key(&offer_a.public_identity.sign_public);
        let forged_payload = envelope.encode().expect("forged envelope encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            forged_payload,
        )
        .await;

        assert!(rx_a.try_recv().is_err());
    }

    #[test]
    fn steady_state_matches_v1_role_policy() {
        assert_eq!(steady_state_for_role(&NodeRole::Offer), DaemonState::WaitingForLocalClient);
        assert_eq!(steady_state_for_role(&NodeRole::Answer), DaemonState::Serving);
    }

    #[test]
    fn canonical_specs_do_not_present_stale_single_session_rules_as_current() {
        let specs = include_str!("../../../docs/SPECS.md");
        assert!(
            !specs.contains("One active peer tunnel session at a time"),
            "canonical specs must not present the old global single-session rule as current"
        );
        assert!(
            !specs.contains("Multiple simultaneous WebRTC peer sessions"),
            "canonical specs must not list current v0.3 multi-peer sessions as out of scope"
        );
        assert!(
            specs.contains("One active peer tunnel session per authenticated `peer_id`."),
            "canonical specs should document the current per-peer session limit"
        );
        assert!(
            specs.contains("multiple simultaneous authorized `p2p-offer` peers")
                || specs.contains("Multiple simultaneous authorized offer peer sessions"),
            "canonical specs should document multiple authorized offer peers per answer daemon"
        );
        assert!(
            specs.contains("If the `session_id` is unknown and the message is not an `offer`"),
            "canonical specs should document unknown-session non-offer routing policy"
        );
        assert!(
            specs.contains(
                "daemon-level `current_state` reports `serving` with zero or more active sessions"
            ),
            "canonical specs should document answer Serving status semantics"
        );
    }

    #[test]
    fn canonical_readme_documents_current_multi_peer_answer_behavior() {
        let readme = include_str!("../../../README.md");
        assert!(
            readme.contains(
                "One answer daemon can serve multiple authorized offer peers concurrently"
            ),
            "README should document current multi-peer answer behavior"
        );
        assert!(
            readme.contains("at most one active WebRTC session per `peer_id`"),
            "README should document the per-peer active session limit"
        );
        assert!(
            !readme.contains("Multiple simultaneous WebRTC peer sessions"),
            "README must not present multi-peer sessions as out of scope"
        );
        assert!(
            !readme.contains("One active peer tunnel session at a time"),
            "README must not present the stale global single-session rule as current"
        );
    }

    #[test]
    fn canonical_v03_spec_documents_current_answer_routing_and_status_policy() {
        let spec = include_str!("../../../docs/V03_SPEC.md");
        assert!(
            spec.contains(
                "one `p2p-answer` process to host multiple simultaneous active peer sessions"
            ),
            "V03 spec should retain multi-session answer behavior"
        );
        assert!(
            spec.contains(
                "daemon-level `current_state` reports `serving` with zero or more active sessions"
            ),
            "V03 spec should document answer serving with zero or more sessions"
        );
        assert!(
            spec.contains(
                "If it does not match an existing session and the message type is `offer`"
            ) && spec.contains("If it does not match and is not a valid new-session entry point"),
            "V03 spec should document unknown-session non-offer routing policy"
        );
    }

    #[tokio::test]
    async fn answer_status_event_does_not_rekey_by_peer_or_cross_generation() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, status_writer) = status_writer_for_test(&mut config, "stale-status");
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let authorized_keys = AuthorizedKeys::parse("").expect("empty keys");
        let codec = SignalCodec::new(&answer.identity, &authorized_keys, 120, 300);
        let mut runtime = connected_runtime();
        let mut ctx =
            RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let mut transport = RecordingTransport::default();
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let remote: PeerId = "offer-home".parse().expect("remote peer");
        let old_session = SessionId::random();
        let current_session = SessionId::random();
        let generation = SessionGeneration(7);
        let (handle, _rx) = test_answer_handle(
            current_session,
            generation,
            remote.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(current_session, handle);
        session_by_peer.insert(remote.clone(), current_session);

        handle_answer_session_event(
            &mut ctx,
            &codec,
            &mut transport,
            &mut sessions_by_id,
            &mut session_by_peer,
            AnswerSessionEvent::Status(test_session_status(
                old_session,
                generation,
                remote.clone(),
                DaemonState::Negotiating,
            )),
        )
        .await;

        assert!(sessions_by_id.contains_key(&current_session));
        assert!(!sessions_by_id.contains_key(&old_session));
        assert_eq!(session_by_peer.get(&remote), Some(&current_session));

        handle_answer_session_event(
            &mut ctx,
            &codec,
            &mut transport,
            &mut sessions_by_id,
            &mut session_by_peer,
            AnswerSessionEvent::Status(test_session_status(
                current_session,
                SessionGeneration(8),
                remote.clone(),
                DaemonState::Negotiating,
            )),
        )
        .await;

        assert_eq!(sessions_by_id[&current_session].status.state, DaemonState::TunnelOpen);
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn stale_answer_end_event_cannot_remove_newer_same_peer_session() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (_path, status_writer) = status_writer_for_test(&mut config, "stale-ended");
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let authorized_keys = AuthorizedKeys::parse("").expect("empty keys");
        let codec = SignalCodec::new(&answer.identity, &authorized_keys, 120, 300);
        let mut runtime = connected_runtime();
        let mut ctx =
            RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let mut transport = RecordingTransport::default();
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let remote: PeerId = "offer-home".parse().expect("remote peer");
        let current_session = SessionId::random();
        let generation = SessionGeneration(3);
        let (handle, _rx) = test_answer_handle(
            current_session,
            generation,
            remote.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(current_session, handle);
        session_by_peer.insert(remote.clone(), current_session);

        handle_answer_session_event(
            &mut ctx,
            &codec,
            &mut transport,
            &mut sessions_by_id,
            &mut session_by_peer,
            AnswerSessionEvent::Ended {
                session_id: SessionId::random(),
                generation: SessionGeneration(2),
                remote_peer_id: remote.clone(),
                result: Ok(()),
            },
        )
        .await;

        assert!(sessions_by_id.contains_key(&current_session));
        assert_eq!(session_by_peer.get(&remote), Some(&current_session));

        handle_answer_session_event(
            &mut ctx,
            &codec,
            &mut transport,
            &mut sessions_by_id,
            &mut session_by_peer,
            AnswerSessionEvent::Ended {
                session_id: current_session,
                generation: SessionGeneration(4),
                remote_peer_id: remote.clone(),
                result: Ok(()),
            },
        )
        .await;

        assert!(sessions_by_id.contains_key(&current_session));
        assert_eq!(session_by_peer.get(&remote), Some(&current_session));
    }

    #[tokio::test]
    async fn failed_session_end_events_remove_only_that_session() {
        let failures = vec![
            ("ack-timeout", DaemonError::AckTimeout),
            ("remote-close", DaemonError::RemoteClosed("session_closed".to_owned())),
            (
                "remote-error",
                DaemonError::RemoteError(
                    FailureCode::ProtocolError.as_str().to_owned(),
                    "remote error".to_owned(),
                ),
            ),
            ("reconnect-failure", DaemonError::IceFailed(IceConnectionState::Failed)),
        ];

        for (label, failure) in failures {
            let mut config = sample_config();
            config.node.role = NodeRole::Answer;
            let (path, status_writer) = status_writer_for_test(&mut config, label);
            let config = Arc::new(config);
            let answer = generate_identity("answer-office").expect("answer identity");
            let authorized_keys = AuthorizedKeys::parse("").expect("empty keys");
            let codec = SignalCodec::new(&answer.identity, &authorized_keys, 120, 300);
            let mut runtime = connected_runtime();
            let mut ctx =
                RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
            let mut transport = RecordingTransport::default();
            let mut sessions_by_id = HashMap::new();
            let mut session_by_peer = HashMap::new();
            let peer_a: PeerId = "offer-a".parse().expect("peer a");
            let peer_b: PeerId = "offer-b".parse().expect("peer b");
            let session_a = SessionId::random();
            let session_b = SessionId::random();
            let generation_a = SessionGeneration(21);
            let generation_b = SessionGeneration(22);
            let (handle_a, _rx_a) = test_answer_handle(
                session_a,
                generation_a,
                peer_a.clone(),
                DaemonState::TunnelOpen,
            );
            let (handle_b, _rx_b) = test_answer_handle(
                session_b,
                generation_b,
                peer_b.clone(),
                DaemonState::TunnelOpen,
            );
            sessions_by_id.insert(session_a, handle_a);
            sessions_by_id.insert(session_b, handle_b);
            session_by_peer.insert(peer_a.clone(), session_a);
            session_by_peer.insert(peer_b.clone(), session_b);

            handle_answer_session_event(
                &mut ctx,
                &codec,
                &mut transport,
                &mut sessions_by_id,
                &mut session_by_peer,
                AnswerSessionEvent::Ended {
                    session_id: session_a,
                    generation: generation_a,
                    remote_peer_id: peer_a.clone(),
                    result: Err(failure),
                },
            )
            .await;

            assert!(!sessions_by_id.contains_key(&session_a), "{label}: peer A removed");
            assert!(sessions_by_id.contains_key(&session_b), "{label}: peer B remains");
            assert_eq!(session_by_peer.get(&peer_a), None, "{label}: peer A mapping removed");
            assert_eq!(
                session_by_peer.get(&peer_b),
                Some(&session_b),
                "{label}: peer B mapping remains"
            );
            let status = read_status_file(&path).await;
            assert_eq!(status["current_state"], "serving", "{label}: daemon still serving");
            assert_eq!(status["active_session_count"], 1, "{label}: only peer B remains active");
            assert_eq!(status["sessions"][0]["remote_peer_id"], "offer-b");
            let _ = tokio::fs::remove_file(&path).await;
        }
    }

    #[tokio::test]
    async fn replacement_event_remaps_only_replaced_peer_session() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, status_writer) = status_writer_for_test(&mut config, "replacement-isolation");
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let authorized_keys = AuthorizedKeys::parse("").expect("empty keys");
        let codec = SignalCodec::new(&answer.identity, &authorized_keys, 120, 300);
        let mut runtime = connected_runtime();
        let mut ctx =
            RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let mut transport = RecordingTransport::default();
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let peer_a: PeerId = "offer-a".parse().expect("peer a");
        let peer_b: PeerId = "offer-b".parse().expect("peer b");
        let old_a = SessionId::random();
        let new_a = SessionId::random();
        let session_b = SessionId::random();
        let generation_a = SessionGeneration(11);
        let generation_b = SessionGeneration(12);
        let (handle_a, _rx_a) =
            test_answer_handle(old_a, generation_a, peer_a.clone(), DaemonState::Negotiating);
        let (handle_b, mut rx_b) =
            test_answer_handle(session_b, generation_b, peer_b.clone(), DaemonState::TunnelOpen);
        let b_status_before = handle_b.status.clone();
        sessions_by_id.insert(old_a, handle_a);
        sessions_by_id.insert(session_b, handle_b);
        session_by_peer.insert(peer_a.clone(), old_a);
        session_by_peer.insert(peer_b.clone(), session_b);

        handle_answer_session_event(
            &mut ctx,
            &codec,
            &mut transport,
            &mut sessions_by_id,
            &mut session_by_peer,
            AnswerSessionEvent::Replaced {
                old_session_id: old_a,
                new_session_id: new_a,
                remote_peer_id: peer_a.clone(),
                generation: generation_a,
                status: test_session_status(
                    new_a,
                    generation_a,
                    peer_a.clone(),
                    DaemonState::ConnectingDataChannel,
                ),
            },
        )
        .await;

        assert!(!sessions_by_id.contains_key(&old_a));
        assert!(sessions_by_id.contains_key(&new_a));
        assert_eq!(session_by_peer.get(&peer_a), Some(&new_a));
        assert_eq!(session_by_peer.get(&peer_b), Some(&session_b));
        assert_eq!(sessions_by_id[&session_b].generation, generation_b);
        assert_eq!(sessions_by_id[&session_b].status.session_id, b_status_before.session_id);
        assert_eq!(sessions_by_id[&session_b].status.state, b_status_before.state);
        assert!(rx_b.try_recv().is_err());
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_registry_reports_serving_when_sessions_are_active() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, status_writer) = status_writer_for_test(&mut config, "serving-registry");
        let config = Arc::new(config);
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let mut sessions_by_id = HashMap::new();
        let (handle, _rx) = test_answer_handle(
            SessionId::random(),
            SessionGeneration(1),
            "offer-home".parse().expect("remote peer"),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(handle.status.session_id, handle);

        write_answer_registry_status(&ctx, &sessions_by_id).await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["active_session_count"], 1);
        assert_eq!(
            status["active_session_id"],
            sessions_by_id.keys().next().expect("one session").to_string()
        );
        assert!(status["active_stream_count"].is_null());
        assert!(status["sessions"][0]["configured_forward_ids"].is_array());
        assert!(status["sessions"][0]["open_forward_ids"].is_null());
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_registry_reports_serving_with_zero_sessions() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, status_writer) = status_writer_for_test(&mut config, "serving-zero-registry");
        let config = Arc::new(config);
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let sessions_by_id = HashMap::new();

        write_answer_registry_status(&ctx, &sessions_by_id).await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["role"], "answer");
        assert_eq!(status["active_session_count"], 0);
        assert!(status["active_session_id"].is_null());
        assert!(status["sessions"].as_array().expect("sessions should be an array").is_empty());
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_registry_reports_serving_with_multiple_sessions() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, status_writer) = status_writer_for_test(&mut config, "serving-multi-registry");
        let config = Arc::new(config);
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &status_writer, runtime: &mut runtime };
        let mut sessions_by_id = HashMap::new();
        for (idx, peer_id) in ["offer-a", "offer-b"].into_iter().enumerate() {
            let (handle, _rx) = test_answer_handle(
                SessionId::random(),
                SessionGeneration(idx as u64 + 1),
                peer_id.parse().expect("remote peer"),
                DaemonState::TunnelOpen,
            );
            sessions_by_id.insert(handle.status.session_id, handle);
        }

        write_answer_registry_status(&ctx, &sessions_by_id).await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["role"], "answer");
        assert_eq!(
            status["active_session_count"],
            status["sessions"].as_array().expect("sessions should be an array").len()
        );
        assert_eq!(status["active_session_count"], 2);
        assert!(status["active_session_id"].is_null());
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_daemon_ignores_unknown_authenticated_non_offer() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer = generate_identity("offer-home").expect("offer identity");
        let authorized_keys =
            Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let message = InnerMessageBuilder::new(
            SessionId::random(),
            offer.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "unknown session".to_owned(),
            fatal: true,
        }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            payload,
        )
        .await;

        assert!(sessions_by_id.is_empty());
        assert!(session_by_peer.is_empty());
        assert!(transport.published.lock().await.is_empty());
    }

    #[tokio::test]
    async fn answer_daemon_does_not_peer_fallback_route_unknown_non_offer() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer = generate_identity("offer-home").expect("offer identity");
        let authorized_keys =
            Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let active_session = SessionId::random();
        let (handle, mut rx) = test_answer_handle(
            active_session,
            SessionGeneration(1),
            offer.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(active_session, handle);
        session_by_peer.insert(offer.identity.peer_id.clone(), active_session);

        let message = InnerMessageBuilder::new(
            SessionId::random(),
            offer.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "unknown session must not fallback-route".to_owned(),
            fatal: false,
        }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            payload,
        )
        .await;

        assert!(rx.try_recv().is_err(), "unknown-session non-offer must not route by peer");
        assert!(
            transport.published.lock().await.is_empty(),
            "unknown-session non-offer must not receive accepted-message ACK"
        );
        assert_eq!(sessions_by_id[&active_session].status.state, DaemonState::TunnelOpen);
    }

    #[tokio::test]
    async fn answer_daemon_ignores_every_unknown_session_non_offer_without_ack() {
        for (name, body) in AnswerRoutingFixture::unknown_session_non_offer_bodies() {
            let mut fixture = AnswerRoutingFixture::new();
            let original_session = fixture.active_session;
            let payload = fixture.encode_from_offer(SessionId::random(), body);

            fixture.handle_payload(payload).await;

            assert!(fixture.receiver.try_recv().is_err(), "{name} must not fallback-route by peer");
            assert_eq!(
                fixture.published_len().await,
                0,
                "{name} must not receive accepted-message ACK"
            );
            assert_eq!(fixture.sessions_by_id.len(), 1, "{name} must not create a session");
            assert!(
                fixture.sessions_by_id.contains_key(&original_session),
                "{name} must leave the active session map unchanged"
            );
            assert_eq!(
                fixture.sessions_by_id[&original_session].status.state,
                DaemonState::TunnelOpen,
                "{name} must leave active session status unchanged"
            );
            assert_eq!(
                fixture.session_by_peer.get(&fixture.offer_identity.identity.peer_id),
                Some(&original_session),
                "{name} must leave the peer index unchanged"
            );
        }
    }

    #[tokio::test]
    async fn answer_daemon_routes_representative_known_session_messages() {
        let cases = [
            ("ack", MessageBody::Ack(AckBody { ack_msg_id: MsgId::new([9_u8; 16]).into_bytes() })),
            (
                "ice_candidate",
                MessageBody::IceCandidate(IceCandidateBody {
                    candidate: Some("candidate:1 1 UDP 1 127.0.0.1 9 typ host".to_owned()),
                    sdp_mid: Some("0".to_owned()),
                    sdp_mline_index: Some(0),
                }),
            ),
            (
                "close",
                MessageBody::Close(CloseBody {
                    reason_code: "done".to_owned(),
                    message: Some("test close".to_owned()),
                }),
            ),
        ];

        for (name, body) in cases {
            let mut fixture = AnswerRoutingFixture::new();
            let payload = fixture.encode_from_offer(fixture.active_session, body);

            fixture.handle_payload(payload).await;

            let routed = fixture.receiver.try_recv().expect("known-session message should route");
            assert_eq!(routed.message.session_id, fixture.active_session, "{name} routed session");
            assert!(
                fixture.sessions_by_id.contains_key(&fixture.active_session),
                "{name} must leave the session registered"
            );
        }
    }

    #[tokio::test]
    async fn answer_daemon_unknown_same_peer_offer_enters_session_policy() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer = generate_identity("offer-home").expect("offer identity");
        let authorized_keys =
            Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let active_session = SessionId::random();
        let (handle, mut rx) = test_answer_handle(
            active_session,
            SessionGeneration(1),
            offer.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(active_session, handle);
        session_by_peer.insert(offer.identity.peer_id.clone(), active_session);

        let rejected_session = SessionId::random();
        let message = InnerMessageBuilder::new(
            rejected_session,
            offer.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "unrelated second offer".to_owned() }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            payload,
        )
        .await;

        let routed = rx.try_recv().expect("same-peer offer should enter session policy handling");
        assert_eq!(routed.message.session_id, rejected_session);
        assert!(matches!(routed.message.body, MessageBody::Offer(_)));
        assert!(transport.published.lock().await.is_empty());
        assert_eq!(session_by_peer.get(&offer.identity.peer_id), Some(&active_session));
    }

    #[tokio::test]
    async fn answer_daemon_admits_unknown_authenticated_offer() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.health.write_status_file = false;
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer = generate_identity("offer-home").expect("offer identity");
        let authorized_keys =
            Arc::new(AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys"));
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(8);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let offer_peer = WebRtcPeer::new(&config.webrtc).await.expect("offer peer");
        let _data_channel = offer_peer.create_data_channel().await.expect("data channel");
        let offer_sdp = offer_peer.create_offer().await.expect("offer sdp");
        let session_id = SessionId::random();
        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: offer_sdp }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            payload,
        )
        .await;

        assert!(sessions_by_id.contains_key(&session_id));
        assert_eq!(session_by_peer.get(&offer.identity.peer_id), Some(&session_id));
        assert!(
            transport.published.lock().await.len() >= 2,
            "offer admission should publish ack and answer"
        );
        for handle in sessions_by_id.into_values() {
            handle.task.abort();
        }
    }

    #[tokio::test]
    async fn answer_daemon_rejects_sender_session_owner_mismatch() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        config.forwards[0].answer.as_mut().expect("answer forward").allow_remote_peers =
            vec!["offer-a".parse().expect("peer a"), "offer-b".parse().expect("peer b")];
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_a = generate_identity("offer-a").expect("offer a identity");
        let offer_b = generate_identity("offer-b").expect("offer b identity");
        let authorized_keys = Arc::new(
            AuthorizedKeys::parse(&format!(
                "{}\n{}",
                offer_a.public_identity.render(),
                offer_b.public_identity.render()
            ))
            .expect("answer keys"),
        );
        let offer_b_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer b keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_b_codec = SignalCodec::new(&offer_b.identity, &offer_b_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let session_a = SessionId::random();
        let (handle_a, mut rx_a) = test_answer_handle(
            session_a,
            SessionGeneration(1),
            offer_a.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(session_a, handle_a);
        session_by_peer.insert(offer_a.identity.peer_id.clone(), session_a);
        let message = InnerMessageBuilder::new(
            session_a,
            offer_b.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "wrong owner".to_owned(),
            fatal: true,
        }));
        let (_envelope, payload) = offer_b_codec
            .encode_for_peer(
                offer_b_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        handle_answer_daemon_payload(
            &config,
            &local_identity,
            &authorized_keys,
            &codec,
            &mut transport,
            &mut ctx,
            &event_tx,
            &mut replay_cache,
            &mut sessions_by_id,
            &mut session_by_peer,
            &mut next_generation,
            payload,
        )
        .await;

        assert!(rx_a.try_recv().is_err());
        assert_eq!(sessions_by_id[&session_a].status.state, DaemonState::TunnelOpen);
    }

    #[tokio::test]
    async fn duplicate_signal_for_one_session_does_not_route_to_another_session() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        config.forwards[0].answer.as_mut().expect("answer forward").allow_remote_peers =
            vec!["offer-a".parse().expect("peer a"), "offer-b".parse().expect("peer b")];
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_a = generate_identity("offer-a").expect("offer a identity");
        let offer_b = generate_identity("offer-b").expect("offer b identity");
        let authorized_keys = Arc::new(
            AuthorizedKeys::parse(&format!(
                "{}\n{}",
                offer_a.public_identity.render(),
                offer_b.public_identity.render()
            ))
            .expect("answer keys"),
        );
        let offer_a_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer a keys");
        let local_identity = Arc::new(answer.identity);
        let codec = SignalCodec::new(&local_identity, &authorized_keys, 120, 300);
        let offer_a_codec = SignalCodec::new(&offer_a.identity, &offer_a_keys, 120, 300);
        let mut transport = RecordingTransport::default();
        let status = StatusWriter::new(&config);
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        let mut replay_cache = ReplayCache::new(64);
        let mut sessions_by_id = HashMap::new();
        let mut session_by_peer = HashMap::new();
        let mut next_generation = 1_u64;
        let session_a = SessionId::random();
        let session_b = SessionId::random();
        let (handle_a, mut rx_a) = test_answer_handle(
            session_a,
            SessionGeneration(1),
            offer_a.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        let (handle_b, mut rx_b) = test_answer_handle(
            session_b,
            SessionGeneration(2),
            offer_b.identity.peer_id.clone(),
            DaemonState::TunnelOpen,
        );
        sessions_by_id.insert(session_a, handle_a);
        sessions_by_id.insert(session_b, handle_b);
        session_by_peer.insert(offer_a.identity.peer_id.clone(), session_a);
        session_by_peer.insert(offer_b.identity.peer_id.clone(), session_b);
        let message = InnerMessageBuilder::new(
            session_a,
            offer_a.identity.peer_id.clone(),
            local_identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "duplicate me".to_owned(),
            fatal: true,
        }));
        let (_envelope, payload) = offer_a_codec
            .encode_for_peer(
                offer_a_keys.get_by_peer_id(&local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");

        for _ in 0..2 {
            handle_answer_daemon_payload(
                &config,
                &local_identity,
                &authorized_keys,
                &codec,
                &mut transport,
                &mut ctx,
                &event_tx,
                &mut replay_cache,
                &mut sessions_by_id,
                &mut session_by_peer,
                &mut next_generation,
                payload.clone(),
            )
            .await;
        }

        assert_eq!(rx_a.try_recv().expect("first routed").message.session_id, session_a);
        assert_eq!(rx_a.try_recv().expect("duplicate routed").message.session_id, session_a);
        assert!(rx_b.try_recv().is_err());
        assert_eq!(sessions_by_id[&session_b].status.state, DaemonState::TunnelOpen);
    }

    #[tokio::test]
    async fn active_same_peer_unrelated_offer_gets_encrypted_busy() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.health.write_status_file = false;
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer = generate_identity("offer-home").expect("offer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_identity = Arc::new(answer.identity);
        let answer_keys = Arc::new(answer_keys);
        let answer_codec = SignalCodec::new(&answer_identity, &answer_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let transport = RecordingTransport::default();
        let transport_for_task = transport.clone();
        let config_for_task = Arc::clone(&config);
        let answer_identity_for_task = Arc::clone(&answer_identity);
        let answer_keys_for_task = Arc::clone(&answer_keys);
        let (event_tx, mut event_rx) = mpsc::channel(8);
        let event_task = tokio::spawn(async move {
            let status = StatusWriter::new(&config_for_task);
            let mut runtime = connected_runtime();
            let mut ctx =
                RuntimeContext { config: &config_for_task, status: &status, runtime: &mut runtime };
            let codec =
                SignalCodec::new(&answer_identity_for_task, &answer_keys_for_task, 120, 300);
            let mut transport = transport_for_task;
            let mut sessions_by_id = HashMap::new();
            let mut session_by_peer = HashMap::new();
            while let Some(event) = event_rx.recv().await {
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
        });

        let peer = WebRtcPeer::new(&config.webrtc).await.expect("peer should build");
        let active_id = SessionId::random();
        let mut session = ActiveSession::new(
            active_id,
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
            peer,
            config.security.replay_cache_size,
        );
        session.bridge_state = BridgeSessionState::Active;
        session.state = DaemonState::TunnelOpen;
        let replacement_id = SessionId::random();
        let message = InnerMessageBuilder::new(
            replacement_id,
            offer.identity.peer_id.clone(),
            answer_identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "new unrelated offer".to_owned() }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("offer encodes");
        let mut replay_cache = ReplayCache::new(64);
        let decoded = answer_codec
            .decode_with_replay_status(&payload, &mut replay_cache, None)
            .expect("offer decodes");

        process_answer_session_signal(
            &config,
            &answer_codec,
            &event_tx,
            SessionGeneration(1),
            &mut session,
            decoded,
        )
        .await
        .expect("active unrelated offer should be handled");

        timeout(Duration::from_secs(5), async {
            loop {
                if transport.published.lock().await.len() >= 2 {
                    break;
                }
                sleep(Duration::from_millis(25)).await;
            }
        })
        .await
        .expect("ack and busy should publish");

        let published = transport.published.lock().await.clone();
        let mut offer_replay = ReplayCache::new(64);
        let decoded = published
            .iter()
            .filter_map(|(_peer, payload)| {
                offer_codec
                    .decode(payload, &mut offer_replay, None)
                    .ok()
                    .map(|(_, message, _)| message)
            })
            .collect::<Vec<_>>();
        assert!(decoded.iter().any(|message| matches!(message.body, MessageBody::Ack(_))));
        assert!(decoded.iter().any(|message| {
            matches!(
                &message.body,
                MessageBody::Error(ErrorBody { code, .. }) if code == FailureCode::Busy.as_str()
            )
        }));
        assert_eq!(session.session_id, active_id);
        assert_eq!(session.bridge_state, BridgeSessionState::Active);
        event_task.abort();
        let _ = event_task.await;
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

        let (_duplicate_msg_id, ack) = duplicate_active_session_ack_message(
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

    #[test]
    fn duplicate_active_session_message_ack_policy_matches_message_type() {
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
        let answer_remote = answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key");
        let offer_remote = offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key");

        for (name, body) in AnswerRoutingFixture::ack_required_duplicate_bodies() {
            let message = InnerMessageBuilder::new(
                session_id,
                offer.identity.peer_id.clone(),
                answer.identity.peer_id.clone(),
            )
            .build(body);
            let (envelope, payload) = offer_codec
                .encode_for_peer(offer_remote, &message, false)
                .expect("message encodes");

            let (_duplicate_msg_id, ack) = duplicate_active_session_ack_message(
                &answer_codec,
                session_id,
                answer_remote,
                &offer.identity.peer_id,
                &payload,
                &duplicate_error,
            )
            .unwrap_or_else(|| panic!("{name} duplicate should be re-acknowledged"));

            assert!(matches!(
                ack.body,
                MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == envelope.msg_id.into_bytes()
            ));
        }

        for (name, body) in AnswerRoutingFixture::non_ack_required_duplicate_bodies() {
            let message = InnerMessageBuilder::new(
                session_id,
                offer.identity.peer_id.clone(),
                answer.identity.peer_id.clone(),
            )
            .build(body);
            let (_envelope, payload) = offer_codec
                .encode_for_peer(offer_remote, &message, false)
                .expect("message encodes");

            let ack = duplicate_active_session_ack_message(
                &answer_codec,
                session_id,
                answer_remote,
                &offer.identity.peer_id,
                &payload,
                &duplicate_error,
            );

            assert!(ack.is_none(), "{name} duplicate must not be re-acknowledged");
        }
    }

    #[tokio::test]
    async fn answer_session_reacks_duplicate_same_session_ack_required_messages() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.health.write_status_file = false;
        let config = Arc::new(config);

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_identity = Arc::new(answer.identity);
        let answer_keys = Arc::new(answer_keys);
        let answer_codec = SignalCodec::new(&answer_identity, &answer_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let transport = RecordingTransport::default();
        let transport_for_task = transport.clone();
        let config_for_task = Arc::clone(&config);
        let answer_identity_for_task = Arc::clone(&answer_identity);
        let answer_keys_for_task = Arc::clone(&answer_keys);
        let (event_tx, mut event_rx) = mpsc::channel(8);
        let event_task = tokio::spawn(async move {
            let status = StatusWriter::new(&config_for_task);
            let mut runtime = connected_runtime();
            let mut ctx =
                RuntimeContext { config: &config_for_task, status: &status, runtime: &mut runtime };
            let codec =
                SignalCodec::new(&answer_identity_for_task, &answer_keys_for_task, 120, 300);
            let mut transport = transport_for_task;
            let mut sessions_by_id = HashMap::new();
            let mut session_by_peer = HashMap::new();
            while let Some(event) = event_rx.recv().await {
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
        });

        let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
        let session_id = SessionId::random();
        let mut session = ActiveSession::new(
            session_id,
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
            peer,
            config.security.replay_cache_size,
        );
        session.state = DaemonState::TunnelOpen;
        session.bridge_state = BridgeSessionState::Active;
        let original_state = session.state;
        let original_bridge_state = session.bridge_state;
        let mut replay_cache = ReplayCache::new(64);

        for (name, body) in AnswerRoutingFixture::ack_required_duplicate_bodies() {
            let message = InnerMessageBuilder::new(
                session_id,
                offer.identity.peer_id.clone(),
                answer_identity.peer_id.clone(),
            )
            .build(body);
            let (envelope, payload) = offer_codec
                .encode_for_peer(
                    offer_keys.get_by_peer_id(&answer_identity.peer_id).expect("answer key"),
                    &message,
                    false,
                )
                .expect("message encodes");
            let mut decoded = answer_codec
                .decode_with_replay_status(&payload, &mut replay_cache, None)
                .expect("message decodes");
            decoded.replay_status = ReplayStatus::DuplicateSameSession;

            process_answer_session_signal(
                &config,
                &answer_codec,
                &event_tx,
                SessionGeneration(1),
                &mut session,
                decoded,
            )
            .await
            .unwrap_or_else(|_| panic!("{name} duplicate should be handled"));

            let published = transport.published.lock().await.clone();
            let (_peer, ack_payload) = published.last().expect("duplicate should publish ACK");
            let mut offer_replay = ReplayCache::new(64);
            let (_ack_envelope, ack_message, _sender) = offer_codec
                .decode(ack_payload, &mut offer_replay, None)
                .expect("offer should decode ACK");
            assert!(matches!(
                ack_message.body,
                MessageBody::Ack(AckBody { ack_msg_id }) if ack_msg_id == envelope.msg_id.into_bytes()
            ));
            assert_eq!(session.state, original_state, "{name} duplicate must not mutate state");
            assert_eq!(
                session.bridge_state, original_bridge_state,
                "{name} duplicate must not mutate bridge state"
            );
        }

        event_task.abort();
        let _ = event_task.await;
        session.peer.close().await.expect("answer peer should close");
    }

    #[tokio::test]
    async fn answer_session_ignores_duplicate_different_session_before_ack() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.health.write_status_file = false;

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
        let session_id = SessionId::random();
        let mut session = ActiveSession::new(
            session_id,
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
            peer,
            config.security.replay_cache_size,
        );
        session.state = DaemonState::TunnelOpen;
        session.bridge_state = BridgeSessionState::Active;
        let original_state = session.state;
        let original_bridge_state = session.bridge_state;
        let (event_tx, mut event_rx) = mpsc::channel(1);

        let message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Error(ErrorBody {
            code: FailureCode::ProtocolError.as_str().to_owned(),
            message: "different-session duplicate".to_owned(),
            fatal: true,
        }));
        let (_envelope, payload) = offer_codec
            .encode_for_peer(
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("message encodes");
        let mut replay_cache = ReplayCache::new(64);
        let mut decoded = answer_codec
            .decode_with_replay_status(&payload, &mut replay_cache, None)
            .expect("message decodes");
        decoded.replay_status = ReplayStatus::DuplicateDifferentSession;

        process_answer_session_signal(
            &config,
            &answer_codec,
            &event_tx,
            SessionGeneration(1),
            &mut session,
            decoded,
        )
        .await
        .expect("different-session duplicate should be ignored");

        assert!(event_rx.try_recv().is_err(), "different-session duplicate must not ACK");
        assert_eq!(session.state, original_state);
        assert_eq!(session.bridge_state, original_bridge_state);
        session.peer.close().await.expect("answer peer should close");
    }

    #[tokio::test]
    async fn answer_session_ping_pong_do_not_emit_normal_acks() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.webrtc.stun_urls = Vec::new();
        config.webrtc.enable_trickle_ice = false;
        config.health.write_status_file = false;

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let answer_keys =
            AuthorizedKeys::parse(&offer.public_identity.render()).expect("answer keys");
        let offer_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");
        let answer_codec = SignalCodec::new(&answer.identity, &answer_keys, 120, 300);
        let offer_codec = SignalCodec::new(&offer.identity, &offer_keys, 120, 300);
        let peer = WebRtcPeer::new(&config.webrtc).await.expect("answer peer should build");
        let session_id = SessionId::random();
        let mut session = ActiveSession::new(
            session_id,
            answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key").clone(),
            peer,
            config.security.replay_cache_size,
        );
        session.state = DaemonState::TunnelOpen;
        let original_state = session.state;
        let (event_tx, mut event_rx) = mpsc::channel(1);
        let mut replay_cache = ReplayCache::new(64);

        for body in [MessageBody::Ping(PingBody { seq: 1 }), MessageBody::Pong(PingBody { seq: 2 })]
        {
            let message = InnerMessageBuilder::new(
                session_id,
                offer.identity.peer_id.clone(),
                answer.identity.peer_id.clone(),
            )
            .build(body);
            let (_envelope, payload) = offer_codec
                .encode_for_peer(
                    offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
                    &message,
                    false,
                )
                .expect("message encodes");
            let decoded = answer_codec
                .decode_with_replay_status(&payload, &mut replay_cache, None)
                .expect("message decodes");
            assert!(
                !decoded.message.message_type.requires_ack(),
                "ping/pong must remain non-ACK-required"
            );

            timeout(
                Duration::from_secs(5),
                process_answer_session_signal(
                    &config,
                    &answer_codec,
                    &event_tx,
                    SessionGeneration(1),
                    &mut session,
                    decoded,
                ),
            )
            .await
            .expect("ping/pong handling should finish")
            .expect("ping/pong should be ignored without ACK");
            assert!(
                matches!(event_rx.try_recv(), Ok(AnswerSessionEvent::Status(_))),
                "ping/pong should only emit status updates"
            );
        }

        assert!(event_rx.try_recv().is_err(), "ping/pong must not publish normal ACKs");
        assert_eq!(session.state, original_state);
        assert!(session.signaling.ack_tracker.expired().is_empty());
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

        let (_duplicate_msg_id, reack) = duplicate_active_session_ack_message(
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
    async fn duplicate_active_session_message_is_reacked_only_once_per_msg_id() {
        let mut config = sample_config();
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
        let (path, writer) = status_writer_for_test(&mut config, "offer-duplicate-reack-once");
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
        let mut transport = RecordingTransport::default();

        let duplicate_inbound = InnerMessageBuilder::new(
            session_id,
            answer.identity.peer_id.clone(),
            offer.identity.peer_id.clone(),
        )
        .build(MessageBody::IceCandidate(p2p_signaling::IceCandidateBody {
            candidate: Some("candidate:1 1 udp 2130706431 127.0.0.1 3478 typ host".to_owned()),
            sdp_mid: Some("0".to_owned()),
            sdp_mline_index: Some(0),
        }));
        let (_duplicate_envelope, duplicate_payload) = answer_codec
            .encode_for_peer(
                answer_keys.get_by_peer_id(&offer.identity.peer_id).expect("offer key"),
                &duplicate_inbound,
                false,
            )
            .expect("duplicate inbound encodes");
        let duplicate_error = SignalingError::Protocol("duplicate message detected".to_owned());

        let first = maybe_ack_duplicate_active_session_message(
            &mut ctx,
            &offer_codec,
            &mut transport,
            &mut session,
            &duplicate_payload,
            &duplicate_error,
        )
        .await
        .expect("first duplicate should be re-acknowledged");
        assert!(first);

        let second = maybe_ack_duplicate_active_session_message(
            &mut ctx,
            &offer_codec,
            &mut transport,
            &mut session,
            &duplicate_payload,
            &duplicate_error,
        )
        .await
        .expect("second duplicate should be suppressed");
        assert!(second);

        let published = transport.published.lock().await.clone();
        assert_eq!(
            published.len(),
            1,
            "only one re-ack should be published for the same duplicate msg_id"
        );

        let _ = tokio::fs::remove_file(&path).await;
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

        let target_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
        super::first_answer_forward_mut(&mut config).expect("answer forward").target_port =
            target_listener.local_addr().expect("target local addr").port();

        handle_answer_incoming_data_channel(&mut session, Some(Ok(answer_channel)), &config)
            .expect("incoming data channel should hand off to answer bridge");

        assert!(
            session.data_channel.is_some(),
            "answer session should retain the incoming channel"
        );
        assert!(
            session.bridge_handle.is_some(),
            "answer session should start the bridge immediately"
        );
        assert_eq!(session.bridge_state, BridgeSessionState::Active);

        let target_task = tokio::spawn(async move {
            let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
            let mut received = [0_u8; 4];
            target_stream.read_exact(&mut received).await.expect("target read");
            assert_eq!(&received, b"ping");
            target_stream.write_all(b"pong").await.expect("target write");
            target_stream.shutdown().await.expect("target shutdown");
        });

        let local_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
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
            let (tx, mut rx) = mpsc::channel(1);
            drop(tx);
            p2p_tunnel::run_multiplex_offer(
                offer_channel,
                &config.tunnel,
                OfferClient::new("ssh", offer_stream),
                &mut rx,
            )
            .await
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
        offer_peer.close().await.expect("offer peer should close");
        session.peer.close().await.expect("answer peer should close");
        session.bridge_handle.take().expect("answer bridge handle should exist").abort();
    }

    #[tokio::test]
    async fn active_offer_session_ignores_duplicate_signal_and_processes_later_valid_ack() {
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
        let (path, writer) = status_writer_for_test(&mut config, "offer-duplicate-survival");
        let mut runtime = connected_runtime();
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
        let mut transport = RecordingTransport::default();

        let outbound_message = InnerMessageBuilder::new(
            session_id,
            offer.identity.peer_id.clone(),
            answer.identity.peer_id.clone(),
        )
        .build(MessageBody::Offer(OfferBody { sdp: "pending-offer".to_owned() }));
        let (outbound_envelope, outbound_payload) =
            offer_codec.encode_for_peer(&remote, &outbound_message, false).expect("offer encodes");
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
        let mut session =
            ActiveSession::new(session_id, remote, peer, config.security.replay_cache_size);
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
        assert!(
            session.bridge_handle.is_none(),
            "answer session should not start a new bridge task"
        );
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
        super::first_answer_forward_mut(&mut config).expect("answer forward").allow_remote_peers =
            vec!["offer-home".parse().expect("offer peer id")];

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

        let replacement_offer_peer =
            WebRtcPeer::new(&config.webrtc).await.expect("replacement offer peer should build");
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
                offer_keys.get_by_peer_id(&answer.identity.peer_id).expect("answer key"),
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

        replacement_offer_peer.close().await.expect("replacement offer peer should close");
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
    async fn answer_recovery_returns_to_serving_after_target_connect_failure() {
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
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_serving_after_remote_close() {
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
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_serving_after_bridge_task_failure() {
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
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["role"], "answer");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_recovery_returns_to_serving_after_ice_failure() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "answer-ice-failure");
        let mut runtime = connected_runtime();
        let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        recover_daemon_after_session(&ctx, Err(DaemonError::IceFailed(IceConnectionState::Failed)))
            .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["current_state"], "serving");
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
        assert_eq!(status["current_state"], "serving");
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
            StatusSnapshot { active_session_id: None, current_state: DaemonState::Serving },
            &SignalingError::Protocol("poll failed".to_owned()),
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["mqtt_connected"], false);
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["active_session_count"], 0);
        assert!(status["active_session_id"].is_null());
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn answer_zero_session_transport_recovery_stays_serving() {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        let (path, writer) = status_writer_for_test(&mut config, "answer-zero-transport-recovered");
        let mut runtime = connected_runtime();
        runtime.mqtt_connected = false;
        let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

        mark_transport_usable(
            &mut ctx,
            StatusSnapshot { active_session_id: None, current_state: DaemonState::Serving },
        )
        .await;

        let status = read_status_file(&path).await;
        assert_eq!(status["mqtt_connected"], true);
        assert_eq!(status["current_state"], "serving");
        assert_eq!(status["active_session_count"], 0);
        assert!(status["active_session_id"].is_null());
        assert!(status["sessions"].as_array().expect("sessions should be an array").is_empty());
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
        assert_eq!(status["current_state"], "serving");
        let _ = tokio::fs::remove_file(&path).await;
    }

    #[tokio::test]
    async fn offer_accept_loop_accepts_multiple_clients_for_session_queue() {
        let mut config = sample_config();
        super::first_offer_forward_mut(&mut config).expect("offer forward").listen_port = 0;
        let (forward_id, offer_config) = super::first_offer_forward(&config).expect("offer");
        let listener =
            OfferListener::bind(forward_id, offer_config).await.expect("listener should bind");
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
            .expect("second client should connect for queueing");
        let second_session = timeout(Duration::from_secs(1), accepted_clients.recv())
            .await
            .expect("accept loop should yield second session")
            .expect("accept loop should stay alive")
            .expect("second session should be accepted");

        let mut first_buffer = [0_u8; 1];
        assert!(
            timeout(Duration::from_millis(100), first_client.read(&mut first_buffer))
                .await
                .is_err(),
            "active session client should remain connected while busy clients are rejected"
        );
        let mut second_buffer = [0_u8; 1];
        assert!(
            timeout(Duration::from_millis(100), second_client.read(&mut second_buffer))
                .await
                .is_err(),
            "queued session client should remain connected"
        );

        drop(first_session);
        drop(second_session);

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

    #[tokio::test]
    async fn offer_waiting_state_polls_idle_transport_and_recovers_status() {
        let mut config = sample_config();
        super::first_offer_forward_mut(&mut config).expect("offer forward").listen_port = 0;
        let status_path = std::env::temp_dir()
            .join(format!("p2ptunnel-daemon-status-offer-idle-{}.json", SessionId::random()));
        config.health.write_status_file = true;
        config.health.status_file = status_path.clone();

        let offer = generate_identity("offer-home").expect("offer identity");
        let answer = generate_identity("answer-office").expect("answer identity");
        let authorized_keys =
            AuthorizedKeys::parse(&answer.public_identity.render()).expect("offer keys");

        let (outcomes_tx, outcomes_rx) = mpsc::unbounded_channel();
        let transport = ScriptedPollingTransport { outcomes: outcomes_rx };

        let daemon = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
            config,
            offer.identity,
            authorized_keys,
            transport,
            None,
        ));

        let initial = wait_for_status(&status_path, |status| {
            status["role"] == "offer"
                && status["current_state"] == "waiting_for_local_client"
                && status["mqtt_connected"] == true
        })
        .await;
        assert_eq!(initial["mqtt_connected"], true);

        outcomes_tx
            .send(Err(SignalingError::Protocol("idle poll failed".to_owned())))
            .expect("idle poll failure should be delivered");
        let disconnected = wait_for_status(&status_path, |status| {
            status["current_state"] == "waiting_for_local_client"
                && status["mqtt_connected"] == false
        })
        .await;
        assert_eq!(disconnected["mqtt_connected"], false);

        outcomes_tx.send(Ok(None)).expect("idle transport recovery should be delivered");
        let recovered = wait_for_status(&status_path, |status| {
            status["current_state"] == "waiting_for_local_client"
                && status["mqtt_connected"] == true
        })
        .await;
        assert_eq!(recovered["mqtt_connected"], true);

        daemon.abort();
        let _ = daemon.await;
        let _ = tokio::fs::remove_file(&status_path).await;
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
