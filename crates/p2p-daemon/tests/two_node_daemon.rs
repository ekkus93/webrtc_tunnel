use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
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
use p2p_signaling::{ReplayCache, SignalCodec};
use p2p_webrtc::IceConnectionState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::time::{sleep, timeout};

#[derive(Clone, Default)]
struct TransportTrace {
    payloads_by_recipient: Arc<Mutex<HashMap<String, Vec<Vec<u8>>>>>,
}

impl TransportTrace {
    fn record(&self, peer_id: &p2p_core::PeerId, payload: &[u8]) {
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
}

struct InMemoryTransport {
    inbox: mpsc::UnboundedReceiver<Vec<u8>>,
    routes: HashMap<String, mpsc::UnboundedSender<Vec<u8>>>,
    duplicate_first: HashMap<String, usize>,
    delay_first_ms: HashMap<String, u64>,
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
        let route = self.routes.get(peer_id.as_str()).cloned().ok_or_else(|| {
            p2p_signaling::SignalingError::Protocol(format!(
                "missing in-memory route for {}",
                peer_id
            ))
        })?;
        self.trace.record(peer_id, &payload);
        if let Some(delay_ms) = self.delay_first_ms.get_mut(peer_id.as_str()) {
            if *delay_ms > 0 {
                let sleep_ms = *delay_ms;
                *delay_ms = 0;
                sleep(Duration::from_millis(sleep_ms)).await;
                let _ = route.send(payload.clone()).map_err(|_| {
                    p2p_signaling::SignalingError::Protocol(format!(
                        "in-memory delayed route for {} is closed",
                        peer_id
                    ))
                });
            } else {
                route.send(payload.clone()).map_err(|_| {
                    p2p_signaling::SignalingError::Protocol(format!(
                        "in-memory route for {} is closed",
                        peer_id
                    ))
                })?;
            }
        } else {
            route.send(payload.clone()).map_err(|_| {
                p2p_signaling::SignalingError::Protocol(format!(
                    "in-memory route for {} is closed",
                    peer_id
                ))
            })?;
        }
        if let Some(remaining) = self.duplicate_first.get_mut(peer_id.as_str()) {
            if *remaining > 0 {
                *remaining -= 1;
                route.send(payload).map_err(|_| {
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
        Ok(self.inbox.recv().await)
    }
}

fn transport_pair(
    duplicate_answer_to_offer_payloads: usize,
    delay_first_answer_to_offer_ms: u64,
) -> (InMemoryTransport, InMemoryTransport, TransportTrace) {
    let (offer_tx, offer_rx) = mpsc::unbounded_channel();
    let (answer_tx, answer_rx) = mpsc::unbounded_channel();
    let trace = TransportTrace::default();

    let offer_transport = InMemoryTransport {
        inbox: offer_rx,
        routes: HashMap::from([("answer-office".to_owned(), answer_tx)]),
        duplicate_first: HashMap::new(),
        delay_first_ms: HashMap::new(),
        trace: trace.clone(),
    };
    let answer_transport = InMemoryTransport {
        inbox: answer_rx,
        routes: HashMap::from([("offer-home".to_owned(), offer_tx)]),
        duplicate_first: HashMap::from([(
            "offer-home".to_owned(),
            duplicate_answer_to_offer_payloads,
        )]),
        delay_first_ms: HashMap::from([("offer-home".to_owned(), delay_first_answer_to_offer_ms)]),
        trace: trace.clone(),
    };

    (offer_transport, answer_transport, trace)
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
        NodeRole::Offer => "offer-home".parse().expect("offer peer id"),
        NodeRole::Answer => "answer-office".parse().expect("answer peer id"),
    };
    let client_id = match role {
        NodeRole::Offer => "offer-home".to_owned(),
        NodeRole::Answer => "answer-office".to_owned(),
    };

    AppConfig {
        format: "p2ptunnel-config-v2".to_owned(),
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
                allow_remote_peers: vec!["offer-home".parse().expect("offer peer id")],
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

fn unused_local_port() -> u16 {
    std::net::TcpListener::bind(("127.0.0.1", 0))
        .expect("port probe should bind")
        .local_addr()
        .expect("port probe local addr")
        .port()
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

#[derive(Clone, Copy)]
struct DecodedSignalRecord {
    session_id: p2p_core::SessionId,
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
                message_type: message.message_type,
            }
        })
        .collect()
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

    let offer_status = wait_for_status(&offer_status_path, "waiting_for_local_client").await;
    let answer_status = wait_for_status(&answer_status_path, "idle").await;
    assert_eq!(offer_status["current_state"], "waiting_for_local_client");
    assert_eq!(offer_status["role"], "offer");
    assert_eq!(offer_status["mqtt_connected"], true);
    assert_eq!(answer_status["current_state"], "idle");
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

    let offer_status = wait_for_status(&offer_status_path, "waiting_for_local_client").await;
    let forwards = offer_status["configured_forwards"].as_array().expect("configured forwards");
    assert!(forwards.iter().any(|forward| forward == "ssh"));
    assert!(forwards.iter().any(|forward| forward == "web-ui"));
    let _ = wait_for_status(&answer_status_path, "idle").await;

    offer_task.abort();
    answer_task.abort();
    let _ = offer_task.await;
    let _ = answer_task.await;
    let _ = tokio::fs::remove_file(offer_status_path).await;
    let _ = tokio::fs::remove_file(answer_status_path).await;
}
