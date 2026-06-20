//! Shared fixtures and helpers for the daemon unit-test suite.
//!
//! Re-exports the crate-internal items the test groups exercise and defines the
//! transports, config builders, status helpers, and routing fixtures they share.

use std::collections::HashMap;
use std::future::pending;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use p2p_core::AppConfig;
use p2p_core::{
    BrokerConfig, BrokerTlsConfig, FailureCode, ForwardAnswerConfig, ForwardOfferConfig,
    ForwardRule, HealthConfig, LoggingConfig, MsgId, NodeConfig, NodeRole, PeerConfig, PeerId,
    ReconnectConfig, SecurityConfig, SessionId, TunnelConfig, WebRtcConfig,
};
use p2p_crypto::{AuthorizedKeys, generate_identity};
use p2p_signaling::{
    AckBody, AnswerBody, CloseBody, EndOfCandidatesBody, ErrorBody, IceCandidateBody,
    InnerMessageBuilder, MessageBody, OfferBody, PingBody, RenegotiateRequestBody, ReplayCache,
    SignalCodec, SignalingError,
};
use serde_json::Value;
use tokio::sync::{Mutex, mpsc};
use tokio::time::{sleep, timeout};

pub(crate) use crate::{
    ActiveBusyOfferAction, ActiveBusyOfferCache, ActiveBusyOfferKey, ActiveSession, AnswerDeps,
    AnswerSessionEvent, AnswerSessionHandle, AnswerSessionRegistry, BridgeSessionState,
    DaemonError, DaemonRuntimeState, DaemonSignalingTransport, DaemonState, ForwardListenState,
    IceConnectionState, OfferListener, OfferSessionPayloadOutcome, RuntimeContext,
    SessionGeneration, SessionStatusSnapshot, StatusSnapshot, StatusWriter, WebRtcPeer,
    apply_answer_overrides, apply_offer_overrides, apply_override_pairs, bind_offer_listeners,
    classify_active_busy_offer, compute_backoff_delay, decode_idle_signaling_message,
    duplicate_active_session_ack_message, handle_answer_daemon_payload,
    handle_answer_incoming_data_channel, handle_answer_session_event,
    handle_answer_session_message, handle_offer_session_message, mark_transport_unusable,
    mark_transport_usable, maybe_ack_duplicate_active_session_message,
    maybe_replace_pending_answer_session, process_answer_session_signal,
    process_offer_session_payload, recover_daemon_after_session, replayed_active_busy_offer_key,
    run_offer_daemon_with_transport_and_test_hook, should_ack_idle_offer,
    should_attempt_offer_reconnect, should_continue_reconnect_attempt, spawn_offer_accept_loop,
    steady_state_for_role, write_answer_registry_status, write_steady_state_status,
};

pub(super) type PublishedSignals = std::sync::Arc<Mutex<Vec<(PeerId, Vec<u8>)>>>;

#[derive(Clone, Default)]
pub(super) struct RecordingTransport {
    pub(super) published: PublishedSignals,
}

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

pub(super) struct ScriptedPollingTransport {
    pub(super) outcomes: mpsc::UnboundedReceiver<Result<Option<Vec<u8>>, SignalingError>>,
}

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

pub(super) fn sample_config() -> AppConfig {
    AppConfig {
        format: "p2ptunnel-config-v3".to_owned(),
        node: NodeConfig { peer_id: "offer-home".parse().expect("peer id"), role: NodeRole::Offer },
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
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
        },
        tunnel: TunnelConfig {
            read_chunk_size: 1024,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
            data_plane_probe_timeout_ms: 5000,
            data_plane_heartbeat_interval_ms: 5000,
            data_plane_heartbeat_max_misses: 3,
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

pub(super) fn status_writer_for_test(
    config: &mut AppConfig,
    label: &str,
) -> (PathBuf, StatusWriter) {
    let path = std::env::temp_dir()
        .join(format!("p2ptunnel-daemon-status-{label}-{}.json", SessionId::random()));
    config.health.write_status_file = true;
    config.health.status_file = path.clone();
    (path, StatusWriter::new(config))
}

pub(super) async fn read_status_file(path: &Path) -> Value {
    let content = tokio::fs::read_to_string(path).await.expect("status file should exist");
    serde_json::from_str(&content).expect("valid status json")
}

pub(super) async fn wait_for_status<P>(path: &Path, predicate: P) -> Value
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

pub(super) fn connected_runtime() -> DaemonRuntimeState {
    DaemonRuntimeState::new_connected()
}

pub(super) fn test_session_status(
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

pub(super) fn test_answer_handle(
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

pub(super) struct AnswerRoutingFixture {
    pub(super) config: Arc<AppConfig>,
    pub(super) local_identity: Arc<p2p_crypto::IdentityFile>,
    pub(super) authorized_keys: Arc<AuthorizedKeys>,
    pub(super) offer_identity: p2p_crypto::GeneratedIdentity,
    pub(super) offer_keys: AuthorizedKeys,
    pub(super) active_session: SessionId,
    pub(super) sessions_by_id: HashMap<SessionId, AnswerSessionHandle>,
    pub(super) session_by_peer: HashMap<PeerId, SessionId>,
    pub(super) receiver: mpsc::Receiver<p2p_signaling::DecodedSignal>,
    pub(super) transport: RecordingTransport,
    pub(super) replay_cache: ReplayCache,
    pub(super) next_generation: u64,
}

impl AnswerRoutingFixture {
    pub(super) fn new() -> Self {
        let mut config = sample_config();
        config.node.role = NodeRole::Answer;
        config.node.peer_id = "answer-office".parse().expect("answer peer id");
        config.health.write_status_file = false;
        let config = Arc::new(config);
        let answer = generate_identity("answer-office").expect("answer identity");
        let offer_identity = generate_identity("offer-home").expect("offer identity");
        let authorized_keys = Arc::new(
            AuthorizedKeys::parse(&offer_identity.public_identity.render()).expect("answer keys"),
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

    pub(super) fn unknown_session_non_offer_bodies() -> Vec<(&'static str, MessageBody)> {
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

    pub(super) fn ack_required_duplicate_bodies() -> Vec<(&'static str, MessageBody)> {
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

    pub(super) fn non_ack_required_duplicate_bodies() -> Vec<(&'static str, MessageBody)> {
        vec![
            ("ack", MessageBody::Ack(AckBody { ack_msg_id: MsgId::random().into_bytes() })),
            ("ping", MessageBody::Ping(PingBody { seq: 1 })),
            ("pong", MessageBody::Pong(PingBody { seq: 2 })),
            ("end_of_candidates", MessageBody::EndOfCandidates(EndOfCandidatesBody::default())),
        ]
    }

    pub(super) fn encode_from_offer(&self, session_id: SessionId, body: MessageBody) -> Vec<u8> {
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
                self.offer_keys.get_by_peer_id(&self.local_identity.peer_id).expect("answer key"),
                &message,
                false,
            )
            .expect("payload encodes");
        payload
    }

    pub(super) async fn handle_payload(&mut self, payload: Vec<u8>) {
        let codec = SignalCodec::new(&self.local_identity, &self.authorized_keys, 120, 300);
        let status = StatusWriter::new(&self.config);
        let mut runtime = connected_runtime();
        let mut ctx =
            RuntimeContext { config: &self.config, status: &status, runtime: &mut runtime };
        let (event_tx, _event_rx) = mpsc::channel(4);
        handle_answer_daemon_payload(
            &AnswerDeps {
                config: &self.config,
                local_identity: &self.local_identity,
                authorized_keys: &self.authorized_keys,
                event_tx: &event_tx,
            },
            &codec,
            &mut self.transport,
            &mut ctx,
            &mut AnswerSessionRegistry {
                replay_cache: &mut self.replay_cache,
                sessions_by_id: &mut self.sessions_by_id,
                session_by_peer: &mut self.session_by_peer,
                next_generation: &mut self.next_generation,
            },
            payload,
        )
        .await;
    }

    pub(super) async fn published_len(&self) -> usize {
        self.transport.published.lock().await.len()
    }
}

pub(super) async fn connected_channels(
    webrtc: &WebRtcConfig,
) -> (WebRtcPeer, WebRtcPeer, p2p_webrtc::DataChannelHandle, p2p_webrtc::DataChannelHandle) {
    let offer_peer = WebRtcPeer::new(webrtc).await.expect("offer peer should build");
    let answer_peer = WebRtcPeer::new(webrtc).await.expect("answer peer should build");

    let offer_channel =
        offer_peer.create_data_channel().await.expect("offer data channel should build");
    let offer_sdp = offer_peer.create_offer().await.expect("offer SDP should build");
    answer_peer.apply_remote_offer(&offer_sdp).await.expect("answer should accept offer");
    let answer_sdp = answer_peer.create_answer().await.expect("answer SDP should build");
    offer_peer.apply_remote_answer(&answer_sdp).await.expect("offer should accept answer");

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
