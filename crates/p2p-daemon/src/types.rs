//! Shared daemon data model: the signaling-transport trait, runtime/session
//! state, status snapshots, and the answer-session event/command types. These
//! are referenced by the offer, answer, and signaling modules, so they live in
//! one place with crate-internal visibility.

use std::future::Future;
use std::time::Duration;

use p2p_core::{AppConfig, DaemonState, MsgId, PeerId, SessionId};
use p2p_crypto::AuthorizedKey;
use p2p_signaling::{
    DecodedSignal, InnerMessage, MqttSignalingTransport, SignalingError, SignalingSession,
};
use p2p_webrtc::{DataChannelHandle, WebRtcPeer};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::DaemonError;
use crate::busy::{ActiveBusyOfferCache, DuplicateActiveAckCache};
use crate::status::{ForwardRuntimeStatus, SessionStatus, StatusWriter};

pub(crate) const DAEMON_RUNTIME_RETRY_DELAY: Duration = Duration::from_secs(1);

pub(crate) const ANSWER_SESSION_CAPACITY: usize = 16;

pub trait DaemonSignalingTransport {
    fn subscribe_own_topic(&mut self) -> impl Future<Output = Result<(), SignalingError>> + Send;

    fn publish_signal(
        &mut self,
        peer_id: &PeerId,
        topic_prefix: &str,
        payload: Vec<u8>,
    ) -> impl Future<Output = Result<(), SignalingError>> + Send;

    fn poll_signal_payload(
        &mut self,
    ) -> impl Future<Output = Result<Option<Vec<u8>>, SignalingError>> + Send;
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
pub(crate) enum BridgeSessionState {
    Pending,
    Active,
    Reconnecting,
    Closed,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct DaemonRuntimeState {
    pub(crate) mqtt_connected: bool,
    pub(crate) last_transport_failure_at_ms: Option<u64>,
    /// Per-forward runtime status (offer role). Populated after binding local
    /// listeners; included in every emitted `DaemonStatus`.
    pub(crate) forward_statuses: Vec<ForwardRuntimeStatus>,
}

impl DaemonRuntimeState {
    pub(crate) fn new_connected() -> Self {
        Self {
            mqtt_connected: true,
            last_transport_failure_at_ms: None,
            forward_statuses: Vec::new(),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct StatusSnapshot {
    pub(crate) active_session_id: Option<SessionId>,
    pub(crate) current_state: DaemonState,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub(crate) struct SessionGeneration(pub(crate) u64);

#[derive(Clone, Debug)]
pub(crate) struct SessionStatusSnapshot {
    pub(crate) session_id: SessionId,
    pub(crate) generation: SessionGeneration,
    pub(crate) remote_peer_id: PeerId,
    pub(crate) state: DaemonState,
    pub(crate) data_channel_open: bool,
    pub(crate) configured_forward_ids: Vec<String>,
}

impl SessionStatusSnapshot {
    pub(crate) fn from_session(
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

    pub(crate) fn to_status(&self) -> SessionStatus {
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
pub(crate) struct AnswerStatusSnapshot {
    pub(crate) current_state: DaemonState,
    pub(crate) sessions: Vec<SessionStatusSnapshot>,
}

pub(crate) struct RuntimeContext<'a> {
    pub(crate) config: &'a AppConfig,
    pub(crate) status: &'a StatusWriter,
    pub(crate) runtime: &'a mut DaemonRuntimeState,
}

pub(crate) struct OutgoingSignal {
    pub(crate) message: InnerMessage,
    pub(crate) response: bool,
}

pub(crate) struct PublishRequest {
    pub(crate) recipient: AuthorizedKey,
    pub(crate) outgoing: OutgoingSignal,
    pub(crate) status: SessionStatusSnapshot,
    pub(crate) result: oneshot::Sender<Result<PublishedSignal, DaemonError>>,
}

pub(crate) struct PublishedSignal {
    pub(crate) msg_id: MsgId,
    pub(crate) message_type: p2p_core::MessageType,
    pub(crate) payload: Vec<u8>,
}

pub(crate) enum AnswerSessionEvent {
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

pub(crate) struct AnswerSessionHandle {
    pub(crate) generation: SessionGeneration,
    pub(crate) remote_peer_id: PeerId,
    pub(crate) inbound: mpsc::Sender<DecodedSignal>,
    pub(crate) status: SessionStatusSnapshot,
    pub(crate) task: JoinHandle<()>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum OfferSessionPayloadOutcome {
    Ignored,
    Handled,
}

pub struct ActiveSession {
    pub session_id: SessionId,
    pub remote_peer_id: PeerId,
    pub state: DaemonState,
    pub(crate) remote_authorized: AuthorizedKey,
    pub(crate) peer: WebRtcPeer,
    pub(crate) data_channel: Option<DataChannelHandle>,
    pub(crate) bridge_handle: Option<JoinHandle<Result<(), p2p_tunnel::TunnelError>>>,
    pub(crate) bridge_state: BridgeSessionState,
    pub(crate) active_busy_offers: ActiveBusyOfferCache,
    pub(crate) duplicate_active_acks: DuplicateActiveAckCache,
    pub(crate) signaling: SignalingSession,
}

impl ActiveSession {
    pub(crate) fn new(
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
