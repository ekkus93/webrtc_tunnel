use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, AtomicU16, AtomicUsize, Ordering},
};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use p2p_core::{
    AppConfig, BrokerConfig, BrokerTlsConfig, ForwardAnswerConfig, ForwardOfferConfig, ForwardRule,
    HealthConfig, LoggingConfig, MessageType, NodeConfig, NodeRole, PathConfig, PeerConfig,
    ReconnectConfig, SecurityConfig, TunnelConfig, WebRtcConfig,
};
use p2p_crypto::{AuthorizedKeys, GeneratedIdentity, IdentityFile, generate_identity};
use p2p_daemon::{
    DaemonSignalingTransport, OfferSessionTestHandle, run_answer_daemon_with_transport,
    run_offer_daemon_with_transport_and_test_hook,
};
use p2p_signaling::{
    AnswerBody, CloseBody, InnerMessageBuilder, MessageBody, PingBody, ReplayCache, SignalCodec,
};
use p2p_webrtc::IceConnectionState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tokio::time::{sleep, timeout};

#[derive(Clone, Default)]
struct TransportTrace {
    attempts: Arc<Mutex<Vec<TransportAttempt>>>,
    payloads_by_recipient: Arc<Mutex<HashMap<String, Vec<Vec<u8>>>>>,
}

impl TransportTrace {
    fn record(
        &self,
        from_peer_id: &str,
        peer_id: &p2p_core::PeerId,
        payload: &[u8],
        delivered: bool,
    ) {
        self.attempts.lock().expect("trace mutex should lock").push(TransportAttempt {
            from_peer_id: from_peer_id.to_owned(),
            to_peer_id: peer_id.to_string(),
            payload: payload.to_vec(),
            delivered,
        });
        let mut payloads = self.payloads_by_recipient.lock().expect("trace mutex should lock");
        payloads.entry(peer_id.to_string()).or_default().push(payload.to_vec());
    }

    fn payloads_for(&self, peer_id: &str) -> Vec<Vec<u8>> {
        self.payloads_by_recipient
            .lock()
            .expect("trace mutex should lock")
            .get(peer_id)
            .cloned()
            .unwrap_or_default()
    }

    fn attempts(&self) -> Vec<TransportAttempt> {
        self.attempts.lock().expect("trace mutex should lock").clone()
    }
}

#[derive(Clone)]
struct TransportAttempt {
    from_peer_id: String,
    to_peer_id: String,
    payload: Vec<u8>,
    delivered: bool,
}

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct RouteKey {
    from_peer_id: String,
    to_peer_id: String,
}

impl RouteKey {
    fn new(from_peer_id: impl Into<String>, to_peer_id: impl Into<String>) -> Self {
        Self { from_peer_id: from_peer_id.into(), to_peer_id: to_peer_id.into() }
    }
}

#[derive(Default)]
struct TransportFaults {
    publish_failures: HashMap<RouteKey, usize>,
    dropped_deliveries: HashMap<RouteKey, usize>,
    duplicate_deliveries: HashMap<RouteKey, usize>,
    delayed_deliveries_ms: HashMap<RouteKey, u64>,
}

#[derive(Clone, Default)]
struct TransportFaultControl {
    faults: Arc<Mutex<TransportFaults>>,
    routes: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<InMemoryEvent>>>>,
}

impl TransportFaultControl {
    fn fail_next_publish(&self, from_peer_id: &str, to_peer_id: &str, count: usize) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .publish_failures
            .insert(RouteKey::new(from_peer_id, to_peer_id), count);
    }

    fn drop_next_delivery(&self, from_peer_id: &str, to_peer_id: &str, count: usize) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .dropped_deliveries
            .insert(RouteKey::new(from_peer_id, to_peer_id), count);
    }

    fn duplicate_next_delivery(&self, from_peer_id: &str, to_peer_id: &str, count: usize) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .duplicate_deliveries
            .insert(RouteKey::new(from_peer_id, to_peer_id), count);
    }

    fn delay_next_delivery(&self, from_peer_id: &str, to_peer_id: &str, delay_ms: u64) {
        self.faults
            .lock()
            .expect("fault mutex should lock")
            .delayed_deliveries_ms
            .insert(RouteKey::new(from_peer_id, to_peer_id), delay_ms);
    }

    fn inject_poll_failure(&self, peer_id: &str) {
        let sender = self
            .routes
            .lock()
            .expect("routes mutex should lock")
            .get(peer_id)
            .cloned()
            .expect("poll failure route should exist");
        sender
            .send(InMemoryEvent::PollFailure("injected in-memory poll failure".to_owned()))
            .expect("poll failure receiver should be alive");
    }

    fn inject_payload(&self, peer_id: &str, payload: Vec<u8>) {
        let sender = self
            .routes
            .lock()
            .expect("routes mutex should lock")
            .get(peer_id)
            .cloned()
            .expect("payload route should exist");
        sender.send(InMemoryEvent::Payload(payload)).expect("payload receiver should be alive");
    }
}

#[derive(Clone)]
enum InMemoryEvent {
    Payload(Vec<u8>),
    PollFailure(String),
}

struct InMemoryTransport {
    peer_id: String,
    inbox: mpsc::UnboundedReceiver<InMemoryEvent>,
    routes: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<InMemoryEvent>>>>,
    faults: Arc<Mutex<TransportFaults>>,
    trace: TransportTrace,
}

#[allow(async_fn_in_trait)]
impl DaemonSignalingTransport for InMemoryTransport {
    async fn subscribe_own_topic(&mut self) -> Result<(), p2p_signaling::SignalingError> {
        Ok(())
    }

    async fn publish_signal(
        &mut self,
        peer_id: &p2p_core::PeerId,
        _topic_prefix: &str,
        payload: Vec<u8>,
    ) -> Result<(), p2p_signaling::SignalingError> {
        let route = self
            .routes
            .lock()
            .expect("routes mutex should lock")
            .get(peer_id.as_str())
            .cloned()
            .ok_or_else(|| {
                p2p_signaling::SignalingError::Protocol(format!(
                    "missing in-memory route for {}",
                    peer_id
                ))
            })?;
        let route_key = RouteKey::new(self.peer_id.clone(), peer_id.to_string());
        let (fail_publish, drop_delivery, duplicate_count, delay_ms) = {
            let mut faults = self.faults.lock().expect("fault mutex should lock");
            let fail_publish = decrement_fault(&mut faults.publish_failures, &route_key);
            let drop_delivery = decrement_fault(&mut faults.dropped_deliveries, &route_key);
            let duplicate_count =
                faults.duplicate_deliveries.remove(&route_key).unwrap_or_default();
            let delay_ms = faults.delayed_deliveries_ms.remove(&route_key).unwrap_or_default();
            (fail_publish, drop_delivery, duplicate_count, delay_ms)
        };
        if fail_publish {
            self.trace.record(&self.peer_id, peer_id, &payload, false);
            return Err(p2p_signaling::SignalingError::Protocol(format!(
                "injected publish failure from {} to {}",
                self.peer_id, peer_id
            )));
        }
        self.trace.record(&self.peer_id, peer_id, &payload, !drop_delivery);
        if delay_ms > 0 {
            sleep(Duration::from_millis(delay_ms)).await;
        }
        if !drop_delivery {
            route.send(InMemoryEvent::Payload(payload.clone())).map_err(|_| {
                p2p_signaling::SignalingError::Protocol(format!(
                    "in-memory route for {} is closed",
                    peer_id
                ))
            })?;
            for _ in 0..duplicate_count {
                route.send(InMemoryEvent::Payload(payload.clone())).map_err(|_| {
                    p2p_signaling::SignalingError::Protocol(format!(
                        "in-memory duplicate route for {} is closed",
                        peer_id
                    ))
                })?;
            }
        }
        Ok(())
    }

    async fn poll_signal_payload(
        &mut self,
    ) -> Result<Option<Vec<u8>>, p2p_signaling::SignalingError> {
        match self.inbox.recv().await {
            Some(InMemoryEvent::Payload(payload)) => Ok(Some(payload)),
            Some(InMemoryEvent::PollFailure(error)) => {
                Err(p2p_signaling::SignalingError::Protocol(error))
            }
            None => Ok(None),
        }
    }
}

fn decrement_fault(faults: &mut HashMap<RouteKey, usize>, route_key: &RouteKey) -> bool {
    match faults.get_mut(route_key) {
        Some(remaining) if *remaining > 0 => {
            *remaining -= 1;
            if *remaining == 0 {
                faults.remove(route_key);
            }
            true
        }
        _ => false,
    }
}

struct InMemoryTransportMesh {
    routes: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<InMemoryEvent>>>>,
    faults: Arc<Mutex<TransportFaults>>,
    trace: TransportTrace,
}

impl InMemoryTransportMesh {
    fn new() -> Self {
        Self {
            routes: Arc::new(Mutex::new(HashMap::new())),
            faults: Arc::new(Mutex::new(TransportFaults::default())),
            trace: TransportTrace::default(),
        }
    }

    fn add_transport(&self, peer_id: &str) -> InMemoryTransport {
        let (tx, rx) = mpsc::unbounded_channel();
        self.routes.lock().expect("routes mutex should lock").insert(peer_id.to_owned(), tx);
        InMemoryTransport {
            peer_id: peer_id.to_owned(),
            inbox: rx,
            routes: Arc::clone(&self.routes),
            faults: Arc::clone(&self.faults),
            trace: self.trace.clone(),
        }
    }

    fn control(&self) -> TransportFaultControl {
        TransportFaultControl { faults: Arc::clone(&self.faults), routes: Arc::clone(&self.routes) }
    }

    fn trace(&self) -> TransportTrace {
        self.trace.clone()
    }
}

fn transport_pair(
    duplicate_answer_to_offer_payloads: usize,
    delay_first_answer_to_offer_ms: u64,
) -> (InMemoryTransport, InMemoryTransport, TransportTrace) {
    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();
    if duplicate_answer_to_offer_payloads > 0 {
        control.duplicate_next_delivery(
            "answer-office",
            "offer-home",
            duplicate_answer_to_offer_payloads,
        );
    }
    if delay_first_answer_to_offer_ms > 0 {
        control.delay_next_delivery("answer-office", "offer-home", delay_first_answer_to_offer_ms);
    }
    (offer_transport, answer_transport, mesh.trace())
}

fn transport_mesh(peer_ids: &[&str]) -> HashMap<String, InMemoryTransport> {
    let mesh = InMemoryTransportMesh::new();
    peer_ids.iter().map(|peer_id| ((*peer_id).to_owned(), mesh.add_transport(peer_id))).collect()
}

fn unique_path(name: &str) -> PathBuf {
    let suffix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time should be after unix epoch")
        .as_nanos();
    std::env::temp_dir().join(format!("p2ptunnel-{name}-{suffix}"))
}

fn sample_config(
    role: NodeRole,
    status_file: PathBuf,
    listen_port: u16,
    target_port: u16,
) -> AppConfig {
    let peer_id = match role {
        NodeRole::Offer => "offer-home",
        NodeRole::Answer => "answer-office",
    };
    sample_config_for(role, status_file, listen_port, target_port, peer_id, vec!["offer-home"])
}

fn sample_config_for(
    role: NodeRole,
    status_file: PathBuf,
    listen_port: u16,
    target_port: u16,
    peer_id: &str,
    allow_remote_peers: Vec<&str>,
) -> AppConfig {
    let peer_id: p2p_core::PeerId = peer_id.parse().expect("peer id");
    let client_id = peer_id.to_string();

    AppConfig {
        format: "p2ptunnel-config-v3".to_owned(),
        node: NodeConfig { peer_id, role },
        peer: Some(PeerConfig { remote_peer_id: "answer-office".parse().expect("answer peer id") }),
        paths: PathConfig {
            identity: PathBuf::from("/tmp/identity"),
            authorized_keys: PathBuf::from("/tmp/authorized_keys"),
            state_dir: PathBuf::from("/tmp/p2ptunnel-state"),
            log_dir: PathBuf::from("/tmp/p2ptunnel-log"),
        },
        broker: BrokerConfig {
            url: "mqtts://in-memory.invalid:8883".to_owned(),
            client_id,
            topic_prefix: "p2ptunnel-tests".to_owned(),
            username: String::new(),
            password_file: PathBuf::new(),
            qos: 1,
            keepalive_secs: 30,
            clean_session: false,
            connect_timeout_secs: 5,
            session_expiry_secs: 0,
            tls: BrokerTlsConfig {
                ca_file: PathBuf::from("/etc/ssl/certs/ca-certificates.crt"),
                client_cert_file: PathBuf::new(),
                client_key_file: PathBuf::new(),
                insecure_skip_verify: false,
            },
        },
        webrtc: WebRtcConfig {
            stun_urls: Vec::new(),
            enable_trickle_ice: false,
            enable_ice_restart: true,
        },
        tunnel: TunnelConfig {
            read_chunk_size: 16_384,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
        },
        forwards: vec![ForwardRule {
            id: "ssh".to_owned(),
            offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port,
                allow_remote_peers: allow_remote_peers
                    .into_iter()
                    .map(|peer_id| peer_id.parse().expect("allowed peer id"))
                    .collect(),
            }),
        }],
        reconnect: ReconnectConfig {
            enable_auto_reconnect: true,
            strategy: "ice_then_renegotiate".to_owned(),
            ice_restart_timeout_secs: 8,
            renegotiate_timeout_secs: 20,
            backoff_initial_ms: 1000,
            backoff_max_ms: 30_000,
            backoff_multiplier: 2.0,
            jitter_ratio: 0.20,
            max_attempts: 0,
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
            replay_cache_size: 10_000,
            reject_unknown_config_keys: true,
            refuse_world_readable_identity: true,
            refuse_world_writable_paths: true,
        },
        logging: LoggingConfig {
            level: "info".to_owned(),
            format: "text".to_owned(),
            file_logging: false,
            stdout_logging: false,
            log_file: PathBuf::from("/tmp/p2ptunnel.log"),
            redact_secrets: true,
            redact_sdp: true,
            redact_candidates: true,
            log_rotation: "none".to_owned(),
        },
        health: HealthConfig {
            status_socket: PathBuf::new(),
            write_status_file: true,
            status_file,
        },
    }
}

fn authorized_keys_for(remote: &GeneratedIdentity) -> AuthorizedKeys {
    AuthorizedKeys::parse(&remote.public_identity.render()).expect("authorized keys should parse")
}

fn authorized_keys_for_many(remotes: &[&GeneratedIdentity]) -> AuthorizedKeys {
    let content = remotes
        .iter()
        .map(|identity| identity.public_identity.render())
        .collect::<Vec<_>>()
        .join("\n");
    AuthorizedKeys::parse(&content).expect("authorized keys should parse")
}

fn unused_local_port() -> u16 {
    static NEXT_TEST_PORT: AtomicU16 = AtomicU16::new(30_000);
    loop {
        let port = NEXT_TEST_PORT.fetch_add(1, Ordering::SeqCst);
        assert!(port < 60_000, "test port range exhausted");
        if std::net::TcpListener::bind(("127.0.0.1", port)).is_ok() {
            return port;
        }
    }
}

async fn connect_with_retry(port: u16) -> TcpStream {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        match TcpStream::connect(("127.0.0.1", port)).await {
            Ok(stream) => return stream,
            Err(error) if tokio::time::Instant::now() < deadline => {
                let _ = error;
                sleep(Duration::from_millis(50)).await;
            }
            Err(error) => panic!("offer listener did not start in time: {error}"),
        }
    }
}

async fn assert_client_round_trip(
    port: u16,
    request: &'static [u8; 4],
    response: &'static [u8; 4],
) {
    let mut client = connect_with_retry(port).await;
    client.write_all(request).await.expect("client write");
    let mut received = [0_u8; 4];
    timeout(Duration::from_secs(10), client.read_exact(&mut received))
        .await
        .expect("client should receive response in time")
        .expect("client should read response");
    assert_eq!(&received, response);
    client.shutdown().await.expect("client shutdown");
}

async fn assert_client_round_trip_owned(port: u16, request: [u8; 4], response: [u8; 4]) {
    let mut client = connect_with_retry(port).await;
    client.write_all(&request).await.expect("client write");
    let mut received = [0_u8; 4];
    timeout(Duration::from_secs(10), client.read_exact(&mut received))
        .await
        .expect("client should receive response in time")
        .expect("client should read response");
    assert_eq!(received, response);
    client.shutdown().await.expect("client shutdown");
}

async fn assert_client_stream_fails(port: u16, request: &'static [u8; 4]) {
    let mut client = connect_with_retry(port).await;
    client.write_all(request).await.expect("client write");
    let mut received = [0_u8; 4];
    let result = timeout(Duration::from_secs(5), client.read_exact(&mut received)).await;
    assert!(
        !matches!(result, Ok(Ok(_))),
        "denied stream unexpectedly returned bytes: {received:?}"
    );
}

async fn spawn_echo_target(expected_connections: usize) -> (u16, JoinHandle<()>, Arc<AtomicUsize>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let port = listener.local_addr().expect("target addr").port();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_for_task = Arc::clone(&accepted);
    let task = tokio::spawn(async move {
        for _ in 0..expected_connections {
            let (mut stream, _) = listener.accept().await.expect("target accept");
            let accepted_for_stream = Arc::clone(&accepted_for_task);
            tokio::spawn(async move {
                let mut request = [0_u8; 4];
                stream.read_exact(&mut request).await.expect("target read");
                stream.write_all(&request).await.expect("target write");
                stream.shutdown().await.expect("target shutdown");
                accepted_for_stream.fetch_add(1, Ordering::SeqCst);
            });
        }
    });
    (port, task, accepted)
}

async fn spawn_counting_echo_target() -> (u16, JoinHandle<()>, Arc<AtomicUsize>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let port = listener.local_addr().expect("target addr").port();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_for_task = Arc::clone(&accepted);
    let task = tokio::spawn(async move {
        loop {
            let (mut stream, _) = listener.accept().await.expect("target accept");
            let accepted_for_stream = Arc::clone(&accepted_for_task);
            tokio::spawn(async move {
                let mut request = [0_u8; 4];
                if stream.read_exact(&mut request).await.is_ok() {
                    let _ = stream.write_all(&request).await;
                    let _ = stream.shutdown().await;
                    accepted_for_stream.fetch_add(1, Ordering::SeqCst);
                }
            });
        }
    });
    (port, task, accepted)
}

fn add_offer_forward(config: &mut AppConfig, id: &str, listen_port: u16, target_port: u16) {
    config.forwards.push(ForwardRule {
        id: id.to_owned(),
        offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port,
            allow_remote_peers: vec![config.node.peer_id.clone()],
        }),
    });
}

fn add_answer_forward(config: &mut AppConfig, id: &str, target_port: u16, allow_remote_peer: &str) {
    config.forwards.push(ForwardRule {
        id: id.to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: unused_local_port(),
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port,
            allow_remote_peers: vec![allow_remote_peer.parse().expect("allowed peer id")],
        }),
    });
}

async fn wait_for_status(path: &Path, expected_state: &str) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                if json["current_state"] == expected_state {
                    return json;
                }
            }
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "status {expected_state} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

async fn wait_for_session_count(path: &Path, expected_count: usize) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                if json["active_session_count"] == expected_count {
                    return json;
                }
            }
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "active_session_count {expected_count} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

async fn wait_for_status_matching(
    path: &Path,
    description: &str,
    predicate: impl Fn(&serde_json::Value) -> bool,
) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await
            && let Ok(json) = serde_json::from_str::<serde_json::Value>(&content)
            && predicate(&json)
        {
            return json;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "status condition {description} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

async fn wait_for_failed_publish_attempt(
    trace: &TransportTrace,
    from_peer_id: &str,
    to_peer_id: &str,
) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if trace.attempts().iter().any(|attempt| {
            attempt.from_peer_id == from_peer_id
                && attempt.to_peer_id == to_peer_id
                && !attempt.delivered
        }) {
            return;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "failed publish attempt from {from_peer_id} to {to_peer_id} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

fn session_count_is(expected_count: usize) -> impl Fn(&serde_json::Value) -> bool {
    move |status| status["active_session_count"] == expected_count
}

fn mqtt_connected_is(expected: bool) -> impl Fn(&serde_json::Value) -> bool {
    move |status| status["mqtt_connected"] == expected
}

fn has_remote_peer(remote_peer_id: &'static str) -> impl Fn(&serde_json::Value) -> bool {
    move |status| {
        status["sessions"].as_array().is_some_and(|sessions| {
            sessions.iter().any(|session| session["remote_peer_id"] == remote_peer_id)
        })
    }
}

fn lacks_remote_peer(remote_peer_id: &'static str) -> impl Fn(&serde_json::Value) -> bool {
    move |status| {
        status["sessions"].as_array().is_some_and(|sessions| {
            !sessions.iter().any(|session| session["remote_peer_id"] == remote_peer_id)
        })
    }
}

fn current_state_is(expected_state: &'static str) -> impl Fn(&serde_json::Value) -> bool {
    move |status| status["current_state"] == expected_state
}

fn configured_forwards_include(
    expected_forward_id: &'static str,
) -> impl Fn(&serde_json::Value) -> bool {
    move |status| {
        status["configured_forwards"]
            .as_array()
            .is_some_and(|forwards| forwards.iter().any(|forward| forward == expected_forward_id))
    }
}

fn assert_status_schema_is_consistent(status: &serde_json::Value) {
    let sessions = status["sessions"].as_array().expect("sessions should be an array");
    assert_eq!(status["active_session_count"], sessions.len());
    assert!(
        status.get("active_stream_count").is_none(),
        "status must not expose misleading active_stream_count"
    );
    assert!(
        status.get("open_forward_ids").is_none(),
        "status must not expose misleading open_forward_ids"
    );
    assert!(matches!(
        status["current_state"].as_str(),
        Some(
            "idle"
                | "listening"
                | "connecting_signaling"
                | "connecting_webrtc"
                | "connecting_data_channel"
                | "tunnel_open"
                | "serving"
                | "failed"
                | "closed"
        )
    ));
}

#[derive(Clone)]
struct DecodedSignalRecord {
    session_id: p2p_core::SessionId,
    sender_peer_id: p2p_core::PeerId,
    message_type: MessageType,
}

fn decode_signal_records(
    payloads: &[Vec<u8>],
    codec: &SignalCodec<'_>,
) -> Vec<DecodedSignalRecord> {
    payloads
        .iter()
        .map(|payload| {
            let mut replay_cache = ReplayCache::new(64);
            let (_envelope, message, _sender) = codec
                .decode(payload, &mut replay_cache, None)
                .expect("recorded signaling payload should decode");
            DecodedSignalRecord {
                session_id: message.session_id,
                sender_peer_id: message.sender_peer_id,
                message_type: message.message_type,
            }
        })
        .collect()
}

fn count_records_from(
    records: &[DecodedSignalRecord],
    sender_peer_id: &str,
    message_type: MessageType,
) -> usize {
    records
        .iter()
        .filter(|record| {
            record.sender_peer_id.as_str() == sender_peer_id && record.message_type == message_type
        })
        .count()
}

fn assert_answer_trace_is_passive(records: &[DecodedSignalRecord]) {
    assert!(
        !records.iter().any(|record| matches!(
            record.message_type,
            MessageType::Offer | MessageType::IceRestartRequest | MessageType::RenegotiateRequest
        )),
        "answer side must not initiate fresh-session or reconnect signaling"
    );
}

fn clone_identity(identity: &IdentityFile) -> IdentityFile {
    IdentityFile::from_toml(&identity.render_toml()).expect("identity clone should parse")
}

async fn run_one_in_memory_session(
    duplicate_answer_to_offer_payloads: usize,
    inject_offer_disconnect: bool,
    enable_ice_restart: bool,
    expect_success: bool,
) {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let offer_codec = SignalCodec::new(&offer_identity.identity, &offer_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);
    let offer_identity_for_task = clone_identity(&offer_identity.identity);
    let answer_identity_for_task = clone_identity(&answer_identity.identity);
    let offer_keys_for_task = offer_keys.clone();
    let answer_keys_for_task = answer_keys.clone();

    let offer_status_path = unique_path("offer-status.json");
    let answer_status_path = unique_path("answer-status.json");
    let offer_port = unused_local_port();

    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr should exist").port();

    let mut offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let mut answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    offer_config.webrtc.enable_ice_restart = enable_ice_restart;
    answer_config.webrtc.enable_ice_restart = enable_ice_restart;
    let (offer_transport, answer_transport, trace) = transport_pair(
        duplicate_answer_to_offer_payloads,
        if inject_offer_disconnect { 300 } else { 0 },
    );
    let (hook_tx, mut hook_rx) = mpsc::unbounded_channel();
    let mut injected_session_id = None;

    let answer_server = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept should succeed");
        let mut received = [0_u8; 4];
        stream.read_exact(&mut received).await.expect("target should read request bytes");
        assert_eq!(&received, b"ping");
        stream.write_all(b"pong").await.expect("target should write response bytes");
        stream.shutdown().await.expect("target should shutdown cleanly");
    });

    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        offer_identity_for_task,
        offer_keys_for_task,
        offer_transport,
        Some(hook_tx),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        answer_identity_for_task,
        answer_keys_for_task,
        answer_transport,
    ));

    let mut client = connect_with_retry(offer_port).await;
    if inject_offer_disconnect {
        let OfferSessionTestHandle { session_id, ice_state_injector } =
            timeout(Duration::from_secs(10), hook_rx.recv())
                .await
                .expect("offer session hook should arrive in time")
                .expect("offer session hook should contain a handle");
        injected_session_id = Some(session_id);
        ice_state_injector
            .inject(IceConnectionState::Disconnected)
            .await
            .expect("offer-side ice fault injection should succeed");
    }
    client.write_all(b"ping").await.expect("client should write request bytes");
    let mut response = [0_u8; 4];
    let client_result = timeout(Duration::from_secs(15), client.read_exact(&mut response)).await;

    if expect_success {
        client_result
            .expect("client should receive tunnel response in time")
            .expect("client should read response bytes");
        assert_eq!(&response, b"pong");
        client.shutdown().await.expect("client should shutdown cleanly");

        timeout(Duration::from_secs(15), answer_server)
            .await
            .expect("target server should finish in time")
            .expect("target server task should succeed");
    } else {
        let error = client_result
            .expect("client failure should arrive in time")
            .expect_err("client should not receive a successful tunnel response");
        assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
        answer_server.abort();
        let _ = answer_server.await;
    }

    let offer_status = wait_for_status(&offer_status_path, "tunnel_open").await;
    let answer_status = wait_for_status(&answer_status_path, "serving").await;
    assert_eq!(offer_status["current_state"], "tunnel_open");
    assert_eq!(offer_status["role"], "offer");
    assert_eq!(offer_status["mqtt_connected"], true);
    assert_eq!(answer_status["current_state"], "serving");
    assert_eq!(answer_status["role"], "answer");
    assert_eq!(answer_status["mqtt_connected"], true);

    if inject_offer_disconnect {
        let offer_to_answer =
            decode_signal_records(&trace.payloads_for("answer-office"), &answer_codec);
        let answer_to_offer =
            decode_signal_records(&trace.payloads_for("offer-home"), &offer_codec);
        assert!(
            offer_to_answer
                .iter()
                .filter(|record| record.message_type == MessageType::Offer)
                .count()
                >= 2,
            "offer side should publish a replacement offer after the injected disconnect"
        );
        assert!(
            !answer_to_offer.iter().any(|record| matches!(
                record.message_type,
                MessageType::Offer
                    | MessageType::IceRestartRequest
                    | MessageType::RenegotiateRequest
            )),
            "answer side must not initiate reconnect signaling"
        );
        if enable_ice_restart {
            assert!(
                offer_to_answer.iter().any(|record| {
                    record.message_type == MessageType::Offer
                        && Some(record.session_id) != injected_session_id
                }),
                "offer side should fall back to a replacement session when ICE fails before the data channel opens"
            );
        }
    }

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[test]
fn decrement_fault_counts_down_and_removes_exhausted_route() {
    let route = RouteKey::new("offer-home", "answer-office");
    let unrelated = RouteKey::new("offer-home", "other-answer");
    let mut faults = HashMap::new();
    faults.insert(route.clone(), 2);
    faults.insert(unrelated.clone(), 1);

    assert!(decrement_fault(&mut faults, &route));
    assert_eq!(faults.get(&route), Some(&1));
    assert_eq!(faults.get(&unrelated), Some(&1));
    assert!(decrement_fault(&mut faults, &route));
    assert!(!faults.contains_key(&route));
    assert!(decrement_fault(&mut faults, &unrelated));
    assert!(!faults.contains_key(&unrelated));
    assert!(!decrement_fault(&mut faults, &RouteKey::new("missing", "route")));
}

#[tokio::test]
async fn in_memory_transport_trace_records_success_and_publish_failure() {
    let mesh = InMemoryTransportMesh::new();
    let mut offer_transport = mesh.add_transport("offer-home");
    let mut answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();
    let trace = mesh.trace();
    let answer_peer: p2p_core::PeerId = "answer-office".parse().expect("answer peer id");

    offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"first".to_vec())
        .await
        .expect("first publish should deliver");
    assert_eq!(
        answer_transport.poll_signal_payload().await.expect("poll should succeed"),
        Some(b"first".to_vec())
    );

    control.fail_next_publish("offer-home", "answer-office", 1);
    let error = offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"second".to_vec())
        .await
        .expect_err("second publish should fail");
    assert!(error.to_string().contains("injected publish failure"));

    let attempts = trace.attempts();
    assert_eq!(attempts.len(), 2);
    assert_eq!(attempts[0].from_peer_id, "offer-home");
    assert_eq!(attempts[0].to_peer_id, "answer-office");
    assert_eq!(attempts[0].payload, b"first");
    assert!(attempts[0].delivered);
    assert_eq!(attempts[1].payload, b"second");
    assert!(!attempts[1].delivered);
    assert_eq!(trace.payloads_for("answer-office"), vec![b"first".to_vec(), b"second".to_vec()]);
}

#[tokio::test]
async fn in_memory_transport_faults_are_route_scoped() {
    let mesh = InMemoryTransportMesh::new();
    let mut offer_transport = mesh.add_transport("offer-home");
    let mut answer_transport = mesh.add_transport("answer-office");
    let mut other_transport = mesh.add_transport("other-answer");
    let control = mesh.control();
    let answer_peer: p2p_core::PeerId = "answer-office".parse().expect("answer peer id");
    let other_peer: p2p_core::PeerId = "other-answer".parse().expect("other peer id");

    control.drop_next_delivery("offer-home", "answer-office", 1);
    offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"dropped".to_vec())
        .await
        .expect("dropped delivery still reports publish success");
    assert!(
        timeout(Duration::from_millis(50), answer_transport.poll_signal_payload()).await.is_err(),
        "dropped answer route should not receive payload"
    );

    offer_transport
        .publish_signal(&other_peer, "p2ptunnel-tests", b"other".to_vec())
        .await
        .expect("unrelated route should deliver");
    assert_eq!(
        other_transport.poll_signal_payload().await.expect("poll should succeed"),
        Some(b"other".to_vec())
    );

    control.duplicate_next_delivery("offer-home", "answer-office", 1);
    offer_transport
        .publish_signal(&answer_peer, "p2ptunnel-tests", b"dupe".to_vec())
        .await
        .expect("duplicate delivery should publish");
    assert_eq!(
        answer_transport.poll_signal_payload().await.expect("first poll should succeed"),
        Some(b"dupe".to_vec())
    );
    assert_eq!(
        answer_transport.poll_signal_payload().await.expect("duplicate poll should succeed"),
        Some(b"dupe".to_vec())
    );
}

#[test]
fn unused_local_port_returns_distinct_bindable_ports() {
    let first = unused_local_port();
    let second = unused_local_port();
    let third = unused_local_port();

    assert_ne!(first, second);
    assert_ne!(second, third);
    assert_ne!(first, third);
    let _first_listener =
        std::net::TcpListener::bind(("127.0.0.1", first)).expect("first port should bind");
    let _second_listener =
        std::net::TcpListener::bind(("127.0.0.1", second)).expect("second port should bind");
    let _third_listener =
        std::net::TcpListener::bind(("127.0.0.1", third)).expect("third port should bind");
}

#[tokio::test]
async fn offer_and_answer_daemons_complete_one_in_memory_session() {
    run_one_in_memory_session(0, false, true, true).await;
}

#[tokio::test]
async fn active_offer_session_survives_duplicate_answer_payload_and_completes() {
    run_one_in_memory_session(1, false, true, true).await;
}

#[tokio::test]
async fn offer_side_drives_reconnect_after_injected_disconnect() {
    run_one_in_memory_session(0, true, false, true).await;
}

#[tokio::test]
async fn active_session_ice_restart_recovers_pending_local_client() {
    run_one_in_memory_session(0, true, true, true).await;
}

#[tokio::test]
async fn offer_daemon_accepts_next_client_after_active_connection_loss() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-active-drop-status.json");
    let answer_status_path = unique_path("answer-active-drop-status.json");
    let offer_port = unused_local_port();
    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr").port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let (hook_tx, mut hook_rx) = mpsc::unbounded_channel();
    let (release_first_target, release_first_target_rx) = oneshot::channel();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_for_target = Arc::clone(&accepted);

    let target_task = tokio::spawn(async move {
        let (mut first_stream, _) = target_listener.accept().await.expect("first target accept");
        let accepted_for_first = Arc::clone(&accepted_for_target);
        let first_task = tokio::spawn(async move {
            let mut request = [0_u8; 4];
            first_stream.read_exact(&mut request).await.expect("first target read");
            assert_eq!(&request, b"hold");
            first_stream.write_all(&request).await.expect("first target write");
            accepted_for_first.fetch_add(1, Ordering::SeqCst);
            let _ = release_first_target_rx.await;
            let _ = first_stream.shutdown().await;
        });

        let (mut second_stream, _) = target_listener.accept().await.expect("second target accept");
        let mut request = [0_u8; 4];
        second_stream.read_exact(&mut request).await.expect("second target read");
        assert_eq!(&request, b"next");
        second_stream.write_all(&request).await.expect("second target write");
        second_stream.shutdown().await.expect("second target shutdown");
        accepted_for_target.fetch_add(1, Ordering::SeqCst);
        first_task.await.expect("first target task should join");
    });

    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        Some(hook_tx),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let mut first_client = connect_with_retry(offer_port).await;
    first_client.write_all(b"hold").await.expect("first client write");
    let mut first_response = [0_u8; 4];
    timeout(Duration::from_secs(10), first_client.read_exact(&mut first_response))
        .await
        .expect("first client should receive response")
        .expect("first client read");
    assert_eq!(&first_response, b"hold");
    wait_for_status(&offer_status_path, "tunnel_open").await;

    let first_handle = timeout(Duration::from_secs(10), hook_rx.recv())
        .await
        .expect("first offer hook should arrive")
        .expect("first offer hook should contain handle");
    first_handle
        .ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("active ICE disconnect should inject");
    wait_for_status(&offer_status_path, "waiting_for_local_client").await;

    let mut second_client = connect_with_retry(offer_port).await;
    second_client.write_all(b"next").await.expect("second client write");
    let mut second_response = [0_u8; 4];
    timeout(Duration::from_secs(10), second_client.read_exact(&mut second_response))
        .await
        .expect("second client should receive response")
        .expect("second client read");
    assert_eq!(&second_response, b"next");
    second_client.shutdown().await.expect("second client shutdown");
    wait_for_status(&offer_status_path, "tunnel_open").await;
    assert!(!offer_task.is_finished(), "offer daemon should remain alive after active drop");
    assert!(!answer_task.is_finished(), "answer daemon should remain alive after active drop");

    let _ = release_first_target.send(());
    first_client.shutdown().await.expect("first client shutdown");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should join");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[tokio::test]
async fn simultaneous_offer_peer_reconnects_stay_session_local_and_answer_passive() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let desktop_codec = SignalCodec::new(&offer_desktop.identity, &offer_desktop_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let offer_home_status = unique_path("offer-home-simultaneous-reconnect-status.json");
    let offer_desktop_status = unique_path("offer-desktop-simultaneous-reconnect-status.json");
    let answer_status = unique_path("answer-simultaneous-reconnect-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;

    let mut offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let mut offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );
    offer_home_config.webrtc.enable_ice_restart = true;
    offer_desktop_config.webrtc.enable_ice_restart = true;
    answer_config.webrtc.enable_ice_restart = true;

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    control.delay_next_delivery("answer-office", "offer-home", 300);
    control.delay_next_delivery("answer-office", "offer-desktop", 300);
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");
    let (home_hook_tx, mut home_hook_rx) = mpsc::unbounded_channel();
    let (desktop_hook_tx, mut desktop_hook_rx) = mpsc::unbounded_channel();

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        Some(home_hook_tx),
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys.clone(),
        offer_desktop_transport,
        Some(desktop_hook_tx),
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let mut home_client = connect_with_retry(offer_home_port).await;
    let mut desktop_client = connect_with_retry(offer_desktop_port).await;
    let home_handle = timeout(Duration::from_secs(10), home_hook_rx.recv())
        .await
        .expect("home hook should arrive")
        .expect("home hook should include handle");
    let desktop_handle = timeout(Duration::from_secs(10), desktop_hook_rx.recv())
        .await
        .expect("desktop hook should arrive")
        .expect("desktop hook should include handle");
    home_handle
        .ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("home ICE disconnect should inject");
    desktop_handle
        .ice_state_injector
        .inject(IceConnectionState::Disconnected)
        .await
        .expect("desktop ICE disconnect should inject");

    home_client.write_all(b"home").await.expect("home client write");
    desktop_client.write_all(b"desk").await.expect("desktop client write");
    let mut home_response = [0_u8; 4];
    let mut desktop_response = [0_u8; 4];
    timeout(Duration::from_secs(20), home_client.read_exact(&mut home_response))
        .await
        .expect("home client should receive response")
        .expect("home client should read response");
    timeout(Duration::from_secs(20), desktop_client.read_exact(&mut desktop_response))
        .await
        .expect("desktop client should receive response")
        .expect("desktop client should read response");
    assert_eq!(&home_response, b"home");
    assert_eq!(&desktop_response, b"desk");
    home_client.shutdown().await.expect("home client shutdown");
    desktop_client.shutdown().await.expect("desktop client shutdown");

    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    let status = wait_for_status_matching(&answer_status, "two recovered sessions", |status| {
        session_count_is(2)(status)
            && has_remote_peer("offer-home")(status)
            && has_remote_peer("offer-desktop")(status)
    })
    .await;
    assert_status_schema_is_consistent(&status);
    wait_for_status(&offer_home_status, "tunnel_open").await;
    wait_for_status(&offer_desktop_status, "tunnel_open").await;

    let offer_to_answer =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert!(
        count_records_from(&offer_to_answer, "offer-home", MessageType::Offer) >= 2,
        "home offer side should publish a replacement offer"
    );
    assert!(
        count_records_from(&offer_to_answer, "offer-desktop", MessageType::Offer) >= 2,
        "desktop offer side should publish a replacement offer"
    );
    assert!(
        offer_to_answer.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
                && record.session_id != home_handle.session_id
        }),
        "home recovery should use a replacement session id"
    );
    assert!(
        offer_to_answer.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
                && record.session_id != desktop_handle.session_id
        }),
        "desktop recovery should use a replacement session id"
    );
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-home"),
        &home_codec,
    ));
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-desktop"),
        &desktop_codec,
    ));

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn active_answer_poll_failure_flips_status_and_recovers() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);

    let offer_status_path = unique_path("offer-poll-failure-status.json");
    let answer_status_path = unique_path("answer-poll-failure-status.json");
    let offer_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(1).await;

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let control = mesh.control();

    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys.clone(),
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    assert_client_round_trip(offer_port, b"ping", b"ping").await;
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target task should finish")
        .expect("target task should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 1);
    wait_for_status_matching(&answer_status_path, "serving and connected", |status| {
        current_state_is("serving")(status) && mqtt_connected_is(true)(status)
    })
    .await;

    control.inject_poll_failure("answer-office");
    wait_for_status_matching(&answer_status_path, "mqtt disconnected", mqtt_connected_is(false))
        .await;
    control.inject_payload("answer-office", vec![0_u8]);
    wait_for_status_matching(&answer_status_path, "mqtt recovered", mqtt_connected_is(true)).await;
    assert!(!answer_task.is_finished(), "answer daemon should remain alive");

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[tokio::test]
async fn signaling_turbulence_does_not_interrupt_active_tcp_stream() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let offer_status_path = unique_path("offer-stream-turbulence-status.json");
    let answer_status_path = unique_path("answer-stream-turbulence-status.json");
    let offer_port = unused_local_port();
    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target local addr").port();

    let offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status_path.clone(), offer_port, target_port);
    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");

    let target_task = tokio::spawn(async move {
        let (mut stream, _) = target_listener.accept().await.expect("target accept");
        for expected in [*b"a001", *b"a002", *b"a003"] {
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).await.expect("target read");
            assert_eq!(request, expected);
            stream.write_all(&request).await.expect("target write");
        }
        stream.shutdown().await.expect("target shutdown");
    });
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys,
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let mut client = connect_with_retry(offer_port).await;
    for payload in [*b"a001", *b"a002", *b"a003"] {
        if payload == *b"a002" {
            control.inject_poll_failure("answer-office");
            wait_for_status_matching(
                &answer_status_path,
                "answer mqtt disconnected while stream remains open",
                mqtt_connected_is(false),
            )
            .await;
        }
        client.write_all(&payload).await.expect("client write");
        let mut response = [0_u8; 4];
        timeout(Duration::from_secs(10), client.read_exact(&mut response))
            .await
            .expect("client should receive response")
            .expect("client read");
        assert_eq!(response, payload);
        if payload == *b"a002" {
            control.inject_payload("answer-office", vec![0_u8]);
            wait_for_status_matching(
                &answer_status_path,
                "answer mqtt recovered while stream remains open",
                mqtt_connected_is(true),
            )
            .await;
        }
    }
    client.shutdown().await.expect("client shutdown");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");

    let status =
        wait_for_status_matching(&answer_status_path, "serving after turbulence", |status| {
            current_state_is("serving")(status) && mqtt_connected_is(true)(status)
        })
        .await;
    assert_status_schema_is_consistent(&status);
    let offer_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert_eq!(
        count_records_from(&offer_records, "offer-home", MessageType::Offer),
        1,
        "signaling-only turbulence must not create a duplicate session"
    );

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[tokio::test]
async fn offer_and_answer_daemons_handle_two_forwards_concurrently() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let offer_identity_for_task = clone_identity(&offer_identity.identity);
    let answer_identity_for_task = clone_identity(&answer_identity.identity);
    let offer_keys_for_task = offer_keys.clone();
    let answer_keys_for_task = answer_keys.clone();

    let offer_status_path = unique_path("offer-multi-status.json");
    let answer_status_path = unique_path("answer-multi-status.json");
    let ssh_offer_port = unused_local_port();
    let web_offer_port = unused_local_port();

    let ssh_target =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh target listener should bind");
    let web_target =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("web target listener should bind");
    let ssh_target_port = ssh_target.local_addr().expect("ssh target addr").port();
    let web_target_port = web_target.local_addr().expect("web target addr").port();

    let mut offer_config =
        sample_config(NodeRole::Offer, offer_status_path.clone(), ssh_offer_port, ssh_target_port);
    offer_config.forwards.push(ForwardRule {
        id: "web-ui".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: web_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: web_target_port,
            allow_remote_peers: vec!["offer-home".parse().expect("offer peer id")],
        }),
    });
    let mut answer_config = sample_config(
        NodeRole::Answer,
        answer_status_path.clone(),
        ssh_offer_port,
        ssh_target_port,
    );
    answer_config.forwards.push(ForwardRule {
        id: "web-ui".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: web_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: web_target_port,
            allow_remote_peers: vec!["offer-home".parse().expect("offer peer id")],
        }),
    });

    let (offer_transport, answer_transport, _trace) = transport_pair(0, 0);
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config,
        offer_identity_for_task,
        offer_keys_for_task,
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        answer_identity_for_task,
        answer_keys_for_task,
        answer_transport,
    ));

    let ssh_target_task = tokio::spawn(async move {
        let (mut stream, _) = ssh_target.accept().await.expect("ssh target accept");
        let mut request = [0_u8; 3];
        stream.read_exact(&mut request).await.expect("ssh target read");
        assert_eq!(&request, b"ssh");
        stream.write_all(b"SSH").await.expect("ssh target write");
        stream.shutdown().await.expect("ssh target shutdown");
    });
    let web_target_task = tokio::spawn(async move {
        let (mut stream, _) = web_target.accept().await.expect("web target accept");
        let mut request = [0_u8; 3];
        stream.read_exact(&mut request).await.expect("web target read");
        assert_eq!(&request, b"web");
        stream.write_all(b"WEB").await.expect("web target write");
        stream.shutdown().await.expect("web target shutdown");
    });

    let ssh_client_task = tokio::spawn(async move {
        let mut client = connect_with_retry(ssh_offer_port).await;
        client.write_all(b"ssh").await.expect("ssh client write");
        let mut response = [0_u8; 3];
        client.read_exact(&mut response).await.expect("ssh client read");
        assert_eq!(&response, b"SSH");
        client.shutdown().await.expect("ssh client shutdown");
    });
    let web_client_task = tokio::spawn(async move {
        let mut client = connect_with_retry(web_offer_port).await;
        client.write_all(b"web").await.expect("web client write");
        let mut response = [0_u8; 3];
        client.read_exact(&mut response).await.expect("web client read");
        assert_eq!(&response, b"WEB");
        client.shutdown().await.expect("web client shutdown");
    });

    timeout(Duration::from_secs(15), ssh_client_task)
        .await
        .expect("ssh client should finish")
        .expect("ssh client should succeed");
    timeout(Duration::from_secs(15), web_client_task)
        .await
        .expect("web client should finish")
        .expect("web client should succeed");
    timeout(Duration::from_secs(15), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target should succeed");
    timeout(Duration::from_secs(15), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target should succeed");

    let offer_status = wait_for_status(&offer_status_path, "tunnel_open").await;
    let forwards = offer_status["configured_forwards"].as_array().expect("configured forwards");
    assert!(forwards.iter().any(|forward| forward == "ssh"));
    assert!(forwards.iter().any(|forward| forward == "web-ui"));
    let _ = wait_for_status(&answer_status_path, "serving").await;

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}

#[tokio::test]
async fn answer_daemon_serves_two_offer_peers_concurrently() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-status.json");
    let offer_desktop_status = unique_path("offer-desktop-status.json");
    let answer_status = unique_path("answer-v03-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let target_listener =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let target_port = target_listener.local_addr().expect("target addr").port();

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let target_task = tokio::spawn(async move {
        for _ in 0..2 {
            let (mut stream, _) = target_listener.accept().await.expect("target accept");
            tokio::spawn(async move {
                let mut request = [0_u8; 4];
                stream.read_exact(&mut request).await.expect("target read");
                stream.write_all(&request).await.expect("target write");
                stream.shutdown().await.expect("target shutdown");
            });
        }
    });

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let home_client = tokio::spawn(async move {
        let mut client = connect_with_retry(offer_home_port).await;
        client.write_all(b"home").await.expect("home client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("home client read");
        assert_eq!(&response, b"home");
    });
    let desktop_client = tokio::spawn(async move {
        let mut client = connect_with_retry(offer_desktop_port).await;
        client.write_all(b"desk").await.expect("desktop client write");
        let mut response = [0_u8; 4];
        client.read_exact(&mut response).await.expect("desktop client read");
        assert_eq!(&response, b"desk");
    });

    timeout(Duration::from_secs(20), home_client)
        .await
        .expect("home client should finish")
        .expect("home client should succeed");
    timeout(Duration::from_secs(20), desktop_client)
        .await
        .expect("desktop client should finish")
        .expect("desktop client should succeed");

    let status = wait_for_session_count(&answer_status, 2).await;
    let sessions = status["sessions"].as_array().expect("sessions array");
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-home"));
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-desktop"));

    target_task.abort();
    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = target_task.await;
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn delayed_and_duplicate_delivery_do_not_cross_mutate_active_sessions() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-delay-status.json");
    let offer_desktop_status = unique_path("offer-desktop-dup-status.json");
    let answer_status = unique_path("answer-delay-dup-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    control.delay_next_delivery("answer-office", "offer-home", 250);
    control.drop_next_delivery("answer-office", "offer-home", 1);
    control.duplicate_next_delivery("answer-office", "offer-desktop", 1);
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let home_client = tokio::spawn(assert_client_round_trip(offer_home_port, b"h001", b"h001"));
    let desktop_client =
        tokio::spawn(assert_client_round_trip(offer_desktop_port, b"d001", b"d001"));
    timeout(Duration::from_secs(20), home_client)
        .await
        .expect("home client should finish")
        .expect("home client should succeed");
    timeout(Duration::from_secs(20), desktop_client)
        .await
        .expect("desktop client should finish")
        .expect("desktop client should succeed");
    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    let status = wait_for_status_matching(&answer_status, "two active sessions", |status| {
        session_count_is(2)(status)
            && has_remote_peer("offer-home")(status)
            && has_remote_peer("offer-desktop")(status)
    })
    .await;
    let sessions = status["sessions"].as_array().expect("sessions array");
    assert_eq!(sessions.len(), 2);

    for attempt in mesh.trace().attempts() {
        assert!(
            !attempt.payload.starts_with(b"{"),
            "signaling payloads must remain encrypted binary envelopes"
        );
    }

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn route_scoped_drop_duplicate_stress_is_peer_isolated() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let offer_home_status = unique_path("offer-home-retransmit-status.json");
    let offer_desktop_status = unique_path("offer-desktop-retransmit-status.json");
    let answer_status = unique_path("answer-retransmit-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    control.drop_next_delivery("offer-home", "answer-office", 1);
    control.duplicate_next_delivery("answer-office", "offer-desktop", 1);
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    let home_client = tokio::spawn(assert_client_round_trip(offer_home_port, b"rt01", b"rt01"));
    let desktop_client =
        tokio::spawn(assert_client_round_trip(offer_desktop_port, b"rt02", b"rt02"));
    timeout(Duration::from_secs(20), home_client)
        .await
        .expect("home client should finish")
        .expect("home client should succeed");
    timeout(Duration::from_secs(20), desktop_client)
        .await
        .expect("desktop client should finish")
        .expect("desktop client should succeed");
    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    let status =
        wait_for_status_matching(&answer_status, "two active sessions after retry", |status| {
            session_count_is(2)(status)
                && has_remote_peer("offer-home")(status)
                && has_remote_peer("offer-desktop")(status)
        })
        .await;
    assert_status_schema_is_consistent(&status);
    let offer_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert!(
        count_records_from(&offer_records, "offer-home", MessageType::Offer) >= 1,
        "home route should publish at least one offer"
    );
    assert_eq!(
        count_records_from(&offer_records, "offer-desktop", MessageType::Offer),
        1,
        "desktop duplicate handling must not create another offer-side session"
    );

    let attempts = mesh.trace().attempts();
    let _dropped_home_payload = attempts
        .iter()
        .find(|attempt| {
            attempt.from_peer_id == "offer-home"
                && attempt.to_peer_id == "answer-office"
                && !attempt.delivered
        })
        .expect("home route should record a dropped offer-side publish");
    assert!(
        attempts.iter().any(|attempt| {
            attempt.from_peer_id == "offer-home"
                && attempt.to_peer_id == "answer-office"
                && attempt.delivered
        }),
        "home route should recover with a later delivered publish"
    );
    assert!(
        attempts.iter().any(|attempt| {
            attempt.from_peer_id == "answer-office"
                && attempt.to_peer_id == "offer-desktop"
                && attempt.delivered
        }),
        "desktop route should keep delivering while home route retries"
    );

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn route_scoped_publish_failure_does_not_break_other_active_peer() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);
    let answer_recipient = offer_home_keys
        .get_by_peer_id(&"answer-office".parse().expect("answer peer id"))
        .expect("answer key should be authorized")
        .clone();

    let offer_home_status = unique_path("offer-home-publish-failure-status.json");
    let offer_desktop_status = unique_path("offer-desktop-publish-ok-status.json");
    let answer_status = unique_path("answer-publish-failure-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(3).await;

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    assert_client_round_trip(offer_home_port, b"h100", b"h100").await;
    assert_client_round_trip(offer_desktop_port, b"d100", b"d100").await;
    wait_for_status_matching(
        &answer_status,
        "two active sessions before failure",
        session_count_is(2),
    )
    .await;

    let home_session_id =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec)
            .into_iter()
            .find(|record| {
                record.sender_peer_id.as_str() == "offer-home"
                    && record.message_type == MessageType::Offer
            })
            .expect("home offer should be recorded")
            .session_id;
    let close = InnerMessageBuilder::new(
        home_session_id,
        "offer-home".parse().expect("home peer id"),
        "answer-office".parse().expect("answer peer id"),
    )
    .build(MessageBody::Close(CloseBody {
        reason_code: "test_route_scoped_failure".to_owned(),
        message: None,
    }));
    let (_envelope, payload) =
        home_codec.encode_for_peer(&answer_recipient, &close, false).expect("close should encode");

    control.fail_next_publish("answer-office", "offer-home", 1);
    control.inject_payload("answer-office", payload);
    wait_for_failed_publish_attempt(&mesh.trace(), "answer-office", "offer-home").await;
    assert!(
        mesh.trace().attempts().iter().any(|attempt| {
            attempt.from_peer_id == "answer-office"
                && attempt.to_peer_id == "offer-home"
                && !attempt.delivered
        }),
        "failed publish attempt should be route-scoped and recorded"
    );

    control.inject_payload("answer-office", vec![0_u8]);
    let status =
        wait_for_status_matching(&answer_status, "transport recovered", mqtt_connected_is(true))
            .await;
    assert_status_schema_is_consistent(&status);
    assert_client_round_trip(offer_desktop_port, b"d101", b"d101").await;

    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 3);

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn same_peer_connection_pressure_during_negotiation_does_not_affect_other_peer() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-pressure-status.json");
    let offer_desktop_status = unique_path("offer-desktop-pressure-status.json");
    let answer_status = unique_path("answer-pressure-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_counting_echo_target().await;

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    let answer_transport = mesh.add_transport("answer-office");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));

    assert_client_round_trip(offer_desktop_port, b"pb00", b"pb00").await;
    wait_for_status_matching(&answer_status, "peer B active", has_remote_peer("offer-desktop"))
        .await;

    control.delay_next_delivery("answer-office", "offer-home", 500);
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        offer_home_transport,
        None,
    ));

    let pressured_clients = [
        tokio::spawn(assert_client_round_trip_owned(offer_home_port, *b"pa01", *b"pa01")),
        tokio::spawn(assert_client_round_trip_owned(offer_home_port, *b"pa02", *b"pa02")),
        tokio::spawn(assert_client_round_trip_owned(offer_home_port, *b"pa03", *b"pa03")),
    ];
    assert_client_round_trip(offer_desktop_port, b"pb01", b"pb01").await;
    let mut completed_pressure_clients = 0_usize;
    for client in pressured_clients {
        if timeout(Duration::from_secs(15), client).await.is_ok_and(|result| result.is_ok()) {
            completed_pressure_clients += 1;
        }
    }
    assert!(
        completed_pressure_clients >= 1,
        "at least one same-peer client should complete after pending negotiation"
    );
    assert_client_round_trip(offer_desktop_port, b"pb02", b"pb02").await;
    assert_client_round_trip(offer_home_port, b"pa04", b"pa04").await;
    let status =
        wait_for_status_matching(&answer_status, "both peers active after pressure", |status| {
            has_remote_peer("offer-home")(status) && has_remote_peer("offer-desktop")(status)
        })
        .await;
    assert_status_schema_is_consistent(&status);
    assert!(accepted.load(Ordering::SeqCst) >= 5);

    target_task.abort();
    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = target_task.await;
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn status_file_remains_consistent_during_churn_and_session_failure() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-status-churn.json");
    let offer_desktop_status = unique_path("offer-desktop-status-churn.json");
    let answer_status = unique_path("answer-status-churn.json");
    let bad_offer_port = unused_local_port();
    let good_offer_port = unused_local_port();
    let bad_target_port = unused_local_port();
    let (good_target_port, good_target_task, accepted) = spawn_counting_echo_target().await;

    let mut bad_offer_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        bad_offer_port,
        bad_target_port,
        "offer-home",
        vec!["offer-home"],
    );
    bad_offer_config.forwards[0].id = "bad".to_owned();
    let mut good_offer_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        good_offer_port,
        good_target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    good_offer_config.forwards[0].id = "good".to_owned();
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        bad_offer_port,
        bad_target_port,
        "answer-office",
        vec!["offer-home"],
    );
    answer_config.forwards[0].id = "bad".to_owned();
    answer_config.forwards.push(ForwardRule {
        id: "good".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: good_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: good_target_port,
            allow_remote_peers: vec!["offer-desktop".parse().expect("desktop peer id")],
        }),
    });

    let mesh = InMemoryTransportMesh::new();
    let bad_offer_transport = mesh.add_transport("offer-home");
    let good_offer_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let stop_reader = Arc::new(AtomicBool::new(false));
    let parsed_statuses = Arc::new(Mutex::new(Vec::new()));
    let reader_stop = Arc::clone(&stop_reader);
    let reader_statuses = Arc::clone(&parsed_statuses);
    let reader_path = answer_status.clone();
    let status_reader = tokio::spawn(async move {
        while !reader_stop.load(Ordering::SeqCst) {
            match tokio::fs::read_to_string(&reader_path).await {
                Ok(content) => match serde_json::from_str::<serde_json::Value>(&content) {
                    Ok(status) => {
                        assert_status_schema_is_consistent(&status);
                        reader_statuses.lock().expect("status mutex should lock").push(status);
                    }
                    Err(_) if content.is_empty() => {}
                    Err(error) => panic!("visible status file should parse as JSON: {error}"),
                },
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => panic!("status read should not fail unexpectedly: {error}"),
            }
            sleep(Duration::from_millis(20)).await;
        }
    });

    let bad_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        bad_offer_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        bad_offer_transport,
        None,
    ));
    let good_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        good_offer_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        good_offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    for index in 0..3_u8 {
        let payload = [b'g', b'o', b'0' + index, b'd'];
        assert_client_round_trip_owned(good_offer_port, payload, payload).await;
    }
    let mut bad_client = connect_with_retry(bad_offer_port).await;
    bad_client.write_all(b"fail").await.expect("bad client write");
    let mut bad_response = [0_u8; 4];
    let bad_error = timeout(Duration::from_secs(15), bad_client.read_exact(&mut bad_response))
        .await
        .expect("bad client should fail in time")
        .expect_err("bad client should not receive bytes");
    assert_eq!(bad_error.kind(), std::io::ErrorKind::ConnectionReset);
    assert_client_round_trip(good_offer_port, b"live", b"live").await;
    let final_status = wait_for_status_matching(
        &answer_status,
        "surviving session status",
        has_remote_peer("offer-desktop"),
    )
    .await;
    assert_status_schema_is_consistent(&final_status);

    stop_reader.store(true, Ordering::SeqCst);
    timeout(Duration::from_secs(5), status_reader)
        .await
        .expect("status reader should stop")
        .expect("status reader should succeed");
    assert!(
        !parsed_statuses.lock().expect("status mutex should lock").is_empty(),
        "status reader should capture parseable snapshots"
    );
    assert!(accepted.load(Ordering::SeqCst) >= 4);

    good_target_task.abort();
    bad_offer_task.abort();
    good_offer_task.abort();
    answer_task.abort();
    let _ = good_target_task.await;
    let _ = bad_offer_task.await;
    let _ = good_offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn long_lived_multi_peer_multi_forward_stream_churn_keeps_sessions_usable() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-soak-status.json");
    let offer_desktop_status = unique_path("offer-desktop-soak-status.json");
    let answer_status = unique_path("answer-soak-status.json");
    let home_ssh_port = unused_local_port();
    let home_web_port = unused_local_port();
    let desktop_ssh_port = unused_local_port();
    let desktop_web_port = unused_local_port();
    let cycles = 5_usize;
    let (ssh_target_port, ssh_target_task, ssh_accepts) = spawn_echo_target(cycles * 2).await;
    let (web_target_port, web_target_task, web_accepts) = spawn_echo_target(cycles * 2).await;

    let mut offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        home_ssh_port,
        ssh_target_port,
        "offer-home",
        vec!["offer-home"],
    );
    add_offer_forward(&mut offer_home_config, "web-ui", home_web_port, web_target_port);
    let mut offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        desktop_ssh_port,
        ssh_target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    add_offer_forward(&mut offer_desktop_config, "web-ui", desktop_web_port, web_target_port);
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        home_ssh_port,
        ssh_target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );
    answer_config.forwards.push(ForwardRule {
        id: "web-ui".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: home_web_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: web_target_port,
            allow_remote_peers: vec![
                "offer-home".parse().expect("home peer id"),
                "offer-desktop".parse().expect("desktop peer id"),
            ],
        }),
    });

    let mesh = InMemoryTransportMesh::new();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    for cycle in 0..cycles {
        let home_ssh = [b'h', b's', b'0' + cycle as u8, b'a'];
        let home_web = [b'h', b'w', b'0' + cycle as u8, b'b'];
        let desktop_ssh = [b'd', b's', b'0' + cycle as u8, b'c'];
        let desktop_web = [b'd', b'w', b'0' + cycle as u8, b'd'];
        if cycle == 2 {
            let tasks = [
                tokio::spawn(assert_client_round_trip_owned(home_ssh_port, home_ssh, home_ssh)),
                tokio::spawn(assert_client_round_trip_owned(home_web_port, home_web, home_web)),
                tokio::spawn(assert_client_round_trip_owned(
                    desktop_ssh_port,
                    desktop_ssh,
                    desktop_ssh,
                )),
                tokio::spawn(assert_client_round_trip_owned(
                    desktop_web_port,
                    desktop_web,
                    desktop_web,
                )),
            ];
            for task in tasks {
                timeout(Duration::from_secs(15), task)
                    .await
                    .expect("concurrent stream should finish")
                    .expect("concurrent stream should succeed");
            }
        } else {
            assert_client_round_trip_owned(home_ssh_port, home_ssh, home_ssh).await;
            assert_client_round_trip_owned(home_web_port, home_web, home_web).await;
            assert_client_round_trip_owned(desktop_ssh_port, desktop_ssh, desktop_ssh).await;
            assert_client_round_trip_owned(desktop_web_port, desktop_web, desktop_web).await;
        }
        let status = wait_for_status_matching(&answer_status, "soak sessions active", |status| {
            session_count_is(2)(status)
                && configured_forwards_include("ssh")(status)
                && configured_forwards_include("web-ui")(status)
        })
        .await;
        assert_status_schema_is_consistent(&status);
    }

    timeout(Duration::from_secs(15), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target should succeed");
    timeout(Duration::from_secs(15), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target should succeed");
    assert_eq!(ssh_accepts.load(Ordering::SeqCst), cycles * 2);
    assert_eq!(web_accepts.load(Ordering::SeqCst), cycles * 2);

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn malformed_authenticated_signaling_is_rejected_without_cross_session_damage() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);
    let answer_recipient = offer_home_keys
        .get_by_peer_id(&"answer-office".parse().expect("answer peer id"))
        .expect("answer key should be authorized")
        .clone();

    let offer_home_status = unique_path("offer-home-malformed-status.json");
    let offer_desktop_status = unique_path("offer-desktop-malformed-status.json");
    let answer_status = unique_path("answer-malformed-status.json");
    let offer_home_port = unused_local_port();
    let offer_desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(8).await;

    let offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        offer_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        offer_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        offer_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let control = mesh.control();
    let offer_home_transport = mesh.add_transport("offer-home");
    let offer_desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    assert_client_round_trip(offer_home_port, b"mh00", b"mh00").await;
    assert_client_round_trip(offer_desktop_port, b"md00", b"md00").await;
    wait_for_status_matching(&answer_status, "both sessions active", session_count_is(2)).await;

    let records = decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    let home_session_id = records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
        })
        .expect("home offer should be recorded")
        .session_id;
    let desktop_session_id = records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
        })
        .expect("desktop offer should be recorded")
        .session_id;

    let malformed_messages = [
        InnerMessageBuilder::new(
            p2p_core::SessionId::random(),
            "offer-home".parse().expect("home peer id"),
            "answer-office".parse().expect("answer peer id"),
        )
        .build(MessageBody::Ping(PingBody { seq: 1 })),
        InnerMessageBuilder::new(
            desktop_session_id,
            "offer-home".parse().expect("home peer id"),
            "answer-office".parse().expect("answer peer id"),
        )
        .build(MessageBody::Ping(PingBody { seq: 2 })),
        InnerMessageBuilder::new(
            home_session_id,
            "offer-home".parse().expect("home peer id"),
            "answer-office".parse().expect("answer peer id"),
        )
        .build(MessageBody::Answer(AnswerBody { sdp: "not-valid-for-answer-state".to_owned() })),
    ];

    for (index, message) in malformed_messages.iter().enumerate() {
        let (_envelope, payload) = home_codec
            .encode_for_peer(&answer_recipient, message, false)
            .expect("malformed authenticated signal should encode");
        control.inject_payload("answer-office", payload);
        assert_client_round_trip_owned(
            offer_home_port,
            [b'm', b'h', b'1' + index as u8, b'x'],
            [b'm', b'h', b'1' + index as u8, b'x'],
        )
        .await;
        assert_client_round_trip_owned(
            offer_desktop_port,
            [b'm', b'd', b'1' + index as u8, b'y'],
            [b'm', b'd', b'1' + index as u8, b'y'],
        )
        .await;
        let status = wait_for_status_matching(
            &answer_status,
            "sessions survive malformed signal",
            |status| {
                session_count_is(2)(status)
                    && has_remote_peer("offer-home")(status)
                    && has_remote_peer("offer-desktop")(status)
                    && lacks_remote_peer("offer-unknown")(status)
            },
        )
        .await;
        assert_status_schema_is_consistent(&status);
    }

    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 8);

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn answer_daemon_restart_with_same_identity_accepts_fresh_offer_side_session() {
    let offer_identity = generate_identity("offer-home").expect("offer identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");
    let offer_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for(&offer_identity);
    let offer_codec = SignalCodec::new(&offer_identity.identity, &offer_keys, 120, 300);

    let offer_status = unique_path("offer-restart-status.json");
    let answer_status = unique_path("answer-restart-status.json");
    let offer_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(2).await;
    let offer_config =
        sample_config(NodeRole::Offer, offer_status.clone(), offer_port, target_port);
    let answer_config =
        sample_config(NodeRole::Answer, answer_status.clone(), offer_port, target_port);

    let mesh = InMemoryTransportMesh::new();
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_config.clone(),
        clone_identity(&offer_identity.identity),
        offer_keys.clone(),
        offer_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config.clone(),
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));
    assert_client_round_trip(offer_port, b"r001", b"r001").await;
    wait_for_status_matching(&answer_status, "first restarted-session status", session_count_is(1))
        .await;

    answer_task.abort();
    offer_task.abort();
    let _ = answer_task.await;
    let _ = offer_task.await;

    let restarted_offer_port = unused_local_port();
    let restarted_offer_config =
        sample_config(NodeRole::Offer, offer_status.clone(), restarted_offer_port, target_port);
    let restarted_answer_config =
        sample_config(NodeRole::Answer, answer_status.clone(), restarted_offer_port, target_port);
    let offer_transport = mesh.add_transport("offer-home");
    let answer_transport = mesh.add_transport("answer-office");
    let restarted_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        restarted_offer_config,
        clone_identity(&offer_identity.identity),
        offer_keys.clone(),
        offer_transport,
        None,
    ));
    let restarted_answer_task = tokio::spawn(run_answer_daemon_with_transport(
        restarted_answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));
    assert_client_round_trip(restarted_offer_port, b"r002", b"r002").await;
    let status = wait_for_status_matching(
        &answer_status,
        "post-restart session status",
        session_count_is(1),
    )
    .await;
    assert_status_schema_is_consistent(&status);

    let answer_to_offer =
        decode_signal_records(&mesh.trace().payloads_for("offer-home"), &offer_codec);
    assert!(
        !answer_to_offer.iter().any(|record| matches!(
            record.message_type,
            MessageType::Offer | MessageType::IceRestartRequest | MessageType::RenegotiateRequest
        )),
        "answer side must not initiate reconnect or fresh-session signaling"
    );
    for attempt in mesh.trace().attempts() {
        assert!(!attempt.payload.starts_with(b"{"));
    }

    timeout(Duration::from_secs(10), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 2);

    restarted_offer_task.abort();
    restarted_answer_task.abort();
    let _ = restarted_offer_task.await;
    let _ = restarted_answer_task.await;
    let _ = tokio::fs::remove_file(offer_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn multi_peer_answer_restart_accepts_fresh_offer_side_sessions() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);
    let home_codec = SignalCodec::new(&offer_home.identity, &offer_home_keys, 120, 300);
    let desktop_codec = SignalCodec::new(&offer_desktop.identity, &offer_desktop_keys, 120, 300);
    let answer_codec = SignalCodec::new(&answer_identity.identity, &answer_keys, 120, 300);

    let home_status = unique_path("offer-home-multi-restart-status.json");
    let desktop_status = unique_path("offer-desktop-multi-restart-status.json");
    let answer_status = unique_path("answer-multi-restart-status.json");
    let home_port = unused_local_port();
    let desktop_port = unused_local_port();
    let (target_port, target_task, accepted) = spawn_echo_target(4).await;

    let home_config = sample_config_for(
        NodeRole::Offer,
        home_status.clone(),
        home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let desktop_config = sample_config_for(
        NodeRole::Offer,
        desktop_status.clone(),
        desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );

    let mesh = InMemoryTransportMesh::new();
    let home_transport = mesh.add_transport("offer-home");
    let desktop_transport = mesh.add_transport("offer-desktop");
    let answer_transport = mesh.add_transport("answer-office");
    let home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        home_config.clone(),
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        home_transport,
        None,
    ));
    let desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        desktop_config.clone(),
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys.clone(),
        desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config.clone(),
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        answer_transport,
    ));

    assert_client_round_trip(home_port, b"hr01", b"hr01").await;
    assert_client_round_trip(desktop_port, b"dr01", b"dr01").await;
    wait_for_session_count(&answer_status, 2).await;
    let first_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    let first_home_session = first_records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
        })
        .expect("initial home offer should be recorded")
        .session_id;
    let first_desktop_session = first_records
        .iter()
        .find(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
        })
        .expect("initial desktop offer should be recorded")
        .session_id;

    answer_task.abort();
    home_task.abort();
    desktop_task.abort();
    let _ = answer_task.await;
    let _ = home_task.await;
    let _ = desktop_task.await;

    let restarted_home_port = unused_local_port();
    let restarted_desktop_port = unused_local_port();
    let restarted_home_config = sample_config_for(
        NodeRole::Offer,
        home_status.clone(),
        restarted_home_port,
        target_port,
        "offer-home",
        vec!["offer-home"],
    );
    let restarted_desktop_config = sample_config_for(
        NodeRole::Offer,
        desktop_status.clone(),
        restarted_desktop_port,
        target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    let restarted_answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        restarted_home_port,
        target_port,
        "answer-office",
        vec!["offer-home", "offer-desktop"],
    );
    let restarted_home_transport = mesh.add_transport("offer-home");
    let restarted_desktop_transport = mesh.add_transport("offer-desktop");
    let restarted_answer_transport = mesh.add_transport("answer-office");
    let restarted_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        restarted_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys.clone(),
        restarted_home_transport,
        None,
    ));
    let restarted_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        restarted_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys.clone(),
        restarted_desktop_transport,
        None,
    ));
    let restarted_answer_task = tokio::spawn(run_answer_daemon_with_transport(
        restarted_answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys.clone(),
        restarted_answer_transport,
    ));

    assert_client_round_trip(restarted_home_port, b"hr02", b"hr02").await;
    assert_client_round_trip(restarted_desktop_port, b"dr02", b"dr02").await;
    let status = wait_for_session_count(&answer_status, 2).await;
    assert_status_schema_is_consistent(&status);

    let all_records =
        decode_signal_records(&mesh.trace().payloads_for("answer-office"), &answer_codec);
    assert!(
        all_records.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-home"
                && record.message_type == MessageType::Offer
                && record.session_id != first_home_session
        }),
        "home peer should establish a fresh post-restart session"
    );
    assert!(
        all_records.iter().any(|record| {
            record.sender_peer_id.as_str() == "offer-desktop"
                && record.message_type == MessageType::Offer
                && record.session_id != first_desktop_session
        }),
        "desktop peer should establish a fresh post-restart session"
    );
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-home"),
        &home_codec,
    ));
    assert_answer_trace_is_passive(&decode_signal_records(
        &mesh.trace().payloads_for("offer-desktop"),
        &desktop_codec,
    ));
    for attempt in mesh.trace().attempts() {
        assert!(!attempt.payload.starts_with(b"{"));
    }

    timeout(Duration::from_secs(15), target_task)
        .await
        .expect("target should finish")
        .expect("target should succeed");
    assert_eq!(accepted.load(Ordering::SeqCst), 4);

    restarted_home_task.abort();
    restarted_desktop_task.abort();
    restarted_answer_task.abort();
    let _ = restarted_home_task.await;
    let _ = restarted_desktop_task.await;
    let _ = restarted_answer_task.await;
    let _ = tokio::fs::remove_file(home_status).await;
    let _ = tokio::fs::remove_file(desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn target_connect_failure_for_one_peer_does_not_break_another_peer() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-fail-status.json");
    let offer_desktop_status = unique_path("offer-desktop-ok-status.json");
    let answer_status = unique_path("answer-failure-isolation-status.json");
    let bad_offer_port = unused_local_port();
    let good_offer_port = unused_local_port();
    let bad_target_port = unused_local_port();
    let good_target =
        TcpListener::bind(("127.0.0.1", 0)).await.expect("good target listener should bind");
    let good_target_port = good_target.local_addr().expect("good target addr").port();

    let mut bad_offer_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        bad_offer_port,
        bad_target_port,
        "offer-home",
        vec!["offer-home"],
    );
    bad_offer_config.forwards[0].id = "bad".to_owned();
    let mut good_offer_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        good_offer_port,
        good_target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    good_offer_config.forwards[0].id = "good".to_owned();
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        bad_offer_port,
        bad_target_port,
        "answer-office",
        vec!["offer-home"],
    );
    answer_config.forwards[0].id = "bad".to_owned();
    answer_config.forwards.push(ForwardRule {
        id: "good".to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: good_offer_port,
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: good_target_port,
            allow_remote_peers: vec!["offer-desktop".parse().expect("desktop peer id")],
        }),
    });

    let mut transports = transport_mesh(&["offer-home", "offer-desktop", "answer-office"]);
    let offer_home_transport = transports.remove("offer-home").expect("offer-home transport");
    let offer_desktop_transport =
        transports.remove("offer-desktop").expect("offer-desktop transport");
    let answer_transport = transports.remove("answer-office").expect("answer transport");

    let good_target_task = tokio::spawn(async move {
        let (mut stream, _) = good_target.accept().await.expect("good target accept");
        let mut request = [0_u8; 4];
        stream.read_exact(&mut request).await.expect("good target read");
        assert_eq!(&request, b"good");
        stream.write_all(b"GOOD").await.expect("good target write");
        stream.shutdown().await.expect("good target shutdown");
    });

    let bad_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        bad_offer_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        offer_home_transport,
        None,
    ));
    let good_offer_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        good_offer_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    let mut bad_client = connect_with_retry(bad_offer_port).await;
    bad_client.write_all(b"fail").await.expect("bad client write");
    let mut bad_response = [0_u8; 4];
    let bad_error = timeout(Duration::from_secs(15), bad_client.read_exact(&mut bad_response))
        .await
        .expect("bad client should fail in time")
        .expect_err("bad client should not receive bytes");
    assert_eq!(bad_error.kind(), std::io::ErrorKind::ConnectionReset);

    let mut good_client = connect_with_retry(good_offer_port).await;
    good_client.write_all(b"good").await.expect("good client write");
    let mut good_response = [0_u8; 4];
    timeout(Duration::from_secs(15), good_client.read_exact(&mut good_response))
        .await
        .expect("good client should receive response in time")
        .expect("good client should read response");
    assert_eq!(&good_response, b"GOOD");

    timeout(Duration::from_secs(15), good_target_task)
        .await
        .expect("good target should finish")
        .expect("good target should succeed");

    let status = {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            if let Ok(content) = tokio::fs::read_to_string(&answer_status).await
                && let Ok(status) = serde_json::from_str::<serde_json::Value>(&content)
            {
                let sessions = status["sessions"].as_array().expect("sessions array");
                if sessions.iter().any(|session| session["remote_peer_id"] == "offer-desktop") {
                    break status;
                }
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "answer status did not retain the surviving peer"
            );
            sleep(Duration::from_millis(50)).await;
        }
    };
    assert_eq!(status["current_state"], "serving");

    bad_offer_task.abort();
    good_offer_task.abort();
    answer_task.abort();
    let _ = bad_offer_task.await;
    let _ = good_offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}

#[tokio::test]
async fn per_forward_allowlists_are_isolated_across_simultaneous_sessions() {
    let offer_home = generate_identity("offer-home").expect("offer-home identity should build");
    let offer_desktop =
        generate_identity("offer-desktop").expect("offer-desktop identity should build");
    let answer_identity = generate_identity("answer-office").expect("answer identity should build");

    let offer_home_keys = authorized_keys_for(&answer_identity);
    let offer_desktop_keys = authorized_keys_for(&answer_identity);
    let answer_keys = authorized_keys_for_many(&[&offer_home, &offer_desktop]);

    let offer_home_status = unique_path("offer-home-allowlist-status.json");
    let offer_desktop_status = unique_path("offer-desktop-allowlist-status.json");
    let answer_status = unique_path("answer-allowlist-status.json");
    let home_ssh_port = unused_local_port();
    let home_web_port = unused_local_port();
    let desktop_ssh_port = unused_local_port();
    let desktop_web_port = unused_local_port();

    let ssh_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh target should bind");
    let web_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("web target should bind");
    let ssh_target_port = ssh_target.local_addr().expect("ssh target addr").port();
    let web_target_port = web_target.local_addr().expect("web target addr").port();

    let ssh_target_task = tokio::spawn(async move {
        for expected in [b"ha01", b"ha02"] {
            let (mut stream, _) = ssh_target.accept().await.expect("ssh target accept");
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).await.expect("ssh target read");
            assert_eq!(&request, expected);
            stream.write_all(b"SSH!").await.expect("ssh target write");
            stream.shutdown().await.expect("ssh target shutdown");
        }
    });
    let web_target_task = tokio::spawn(async move {
        for expected in [b"db01", b"db02"] {
            let (mut stream, _) = web_target.accept().await.expect("web target accept");
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).await.expect("web target read");
            assert_eq!(&request, expected);
            stream.write_all(b"WEB!").await.expect("web target write");
            stream.shutdown().await.expect("web target shutdown");
        }
    });

    let mut offer_home_config = sample_config_for(
        NodeRole::Offer,
        offer_home_status.clone(),
        home_ssh_port,
        ssh_target_port,
        "offer-home",
        vec!["offer-home"],
    );
    add_offer_forward(&mut offer_home_config, "web-ui", home_web_port, web_target_port);
    let mut offer_desktop_config = sample_config_for(
        NodeRole::Offer,
        offer_desktop_status.clone(),
        desktop_ssh_port,
        ssh_target_port,
        "offer-desktop",
        vec!["offer-desktop"],
    );
    add_offer_forward(&mut offer_desktop_config, "web-ui", desktop_web_port, web_target_port);
    let mut answer_config = sample_config_for(
        NodeRole::Answer,
        answer_status.clone(),
        home_ssh_port,
        ssh_target_port,
        "answer-office",
        vec!["offer-home"],
    );
    add_answer_forward(&mut answer_config, "web-ui", web_target_port, "offer-desktop");

    let mut transports = transport_mesh(&["offer-home", "offer-desktop", "answer-office"]);
    let offer_home_transport = transports.remove("offer-home").expect("offer-home transport");
    let offer_desktop_transport =
        transports.remove("offer-desktop").expect("offer-desktop transport");
    let answer_transport = transports.remove("answer-office").expect("answer transport");

    let offer_home_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_home_config,
        clone_identity(&offer_home.identity),
        offer_home_keys,
        offer_home_transport,
        None,
    ));
    let offer_desktop_task = tokio::spawn(run_offer_daemon_with_transport_and_test_hook(
        offer_desktop_config,
        clone_identity(&offer_desktop.identity),
        offer_desktop_keys,
        offer_desktop_transport,
        None,
    ));
    let answer_task = tokio::spawn(run_answer_daemon_with_transport(
        answer_config,
        clone_identity(&answer_identity.identity),
        answer_keys,
        answer_transport,
    ));

    assert_client_round_trip(home_ssh_port, b"ha01", b"SSH!").await;
    assert_client_stream_fails(home_web_port, b"deny").await;
    assert_client_round_trip(desktop_web_port, b"db01", b"WEB!").await;
    assert_client_stream_fails(desktop_ssh_port, b"nope").await;
    assert_client_round_trip(home_ssh_port, b"ha02", b"SSH!").await;
    assert_client_round_trip(desktop_web_port, b"db02", b"WEB!").await;

    timeout(Duration::from_secs(15), ssh_target_task)
        .await
        .expect("ssh target should finish")
        .expect("ssh target task should succeed");
    timeout(Duration::from_secs(15), web_target_task)
        .await
        .expect("web target should finish")
        .expect("web target task should succeed");

    let status = wait_for_session_count(&answer_status, 2).await;
    let sessions = status["sessions"].as_array().expect("sessions array");
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-home"));
    assert!(sessions.iter().any(|session| session["remote_peer_id"] == "offer-desktop"));

    offer_home_task.abort();
    offer_desktop_task.abort();
    answer_task.abort();
    let _ = offer_home_task.await;
    let _ = offer_desktop_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_home_status).await;
    let _ = tokio::fs::remove_file(offer_desktop_status).await;
    let _ = tokio::fs::remove_file(answer_status).await;
}
