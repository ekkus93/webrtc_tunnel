//! End-to-end tunnel test over a **real MQTT broker** (mosquitto in Docker, TLS).
//!
//! Unlike `two_node_daemon.rs` (in-memory signaling transport), this drives the real
//! `run_offer_daemon` / `run_answer_daemon` entry points through `MqttSignalingTransport`
//! and a real `mqtts://` broker, then proves application data flows through the tunnel:
//!
//!   client -> offer local listener -> WebRTC -> answer -> echo target -> back
//!
//! The broker is a `eclipse-mosquitto` container with a TLS listener whose cert is
//! signed by a throwaway CA generated at test time (rcgen). The offer/answer daemons
//! trust that CA via `broker.tls.ca_file`.
//!
//! Requires Docker. If Docker is unavailable the test logs a skip and passes, so
//! `cargo test` stays green in environments without Docker. CI must provide Docker
//! for this to actually exercise the broker path.

use std::net::TcpListener as StdTcpListener;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

use p2p_core::{
    AppConfig, BrokerConfig, BrokerTlsConfig, ForwardAnswerConfig, ForwardOfferConfig, ForwardRule,
    HealthConfig, LoggingConfig, NodeConfig, NodeRole, PathConfig, PeerConfig, ReconnectConfig,
    SecurityConfig, TunnelConfig, WebRtcConfig,
};
use p2p_crypto::{AuthorizedKeys, GeneratedIdentity, generate_identity};
use p2p_daemon::{run_answer_daemon, run_offer_daemon};
use rcgen::{BasicConstraints, CertificateParams, DnType, IsCa, Issuer, KeyPair};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{Instant, sleep, timeout};

const MOSQUITTO_IMAGE: &str = "eclipse-mosquitto:2";
const OFFER_PEER: &str = "offer-peer";
const ANSWER_PEER: &str = "answer-peer";
const FORWARD_ID: &str = "web";

fn docker_available() -> bool {
    Command::new("docker").arg("version").output().map(|out| out.status.success()).unwrap_or(false)
}

/// Reserve an ephemeral localhost port and return it (the listener is dropped so the
/// port is free for the real consumer — small TOCTOU window, acceptable for tests).
fn free_port() -> u16 {
    StdTcpListener::bind(("127.0.0.1", 0))
        .expect("bind ephemeral")
        .local_addr()
        .expect("local addr")
        .port()
}

fn write_world_readable(path: &Path, contents: &str) {
    std::fs::write(path, contents).expect("write file");
    let mut perms = std::fs::metadata(path).expect("metadata").permissions();
    perms.set_mode(0o644);
    std::fs::set_permissions(path, perms).expect("chmod file");
}

/// Generate a throwaway CA + a server cert (SAN localhost / 127.0.0.1) signed by it.
/// Returns the paths to `ca.crt`, `server.crt`, `server.key` (all world-readable so
/// the in-container mosquitto user can read the mounted files).
fn gen_broker_certs(dir: &Path) -> (PathBuf, PathBuf, PathBuf) {
    let ca_key = KeyPair::generate().expect("ca key");
    let mut ca_params = CertificateParams::new(Vec::<String>::new()).expect("ca params");
    ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    ca_params.distinguished_name.push(DnType::CommonName, "p2p-e2e-test-ca");
    let ca_cert = ca_params.self_signed(&ca_key).expect("ca self-sign");

    let server_key = KeyPair::generate().expect("server key");
    let server_params =
        CertificateParams::new(vec!["localhost".to_string(), "127.0.0.1".to_string()])
            .expect("server params");
    let issuer = Issuer::from_params(&ca_params, &ca_key);
    let server_cert = server_params.signed_by(&server_key, &issuer).expect("server sign");

    let ca_path = dir.join("ca.crt");
    let cert_path = dir.join("server.crt");
    let key_path = dir.join("server.key");
    write_world_readable(&ca_path, &ca_cert.pem());
    write_world_readable(&cert_path, &server_cert.pem());
    write_world_readable(&key_path, &server_key.serialize_pem());
    (ca_path, cert_path, key_path)
}

/// A mosquitto container with a TLS listener; removed on drop.
struct MosquittoContainer {
    name: String,
}

impl MosquittoContainer {
    fn start(cert_dir: &Path, host_port: u16) -> Self {
        // Mosquitto 2.x defaults to allow_anonymous=false; enable it explicitly.
        let conf = "\
listener 8883
allow_anonymous true
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
require_certificate false
";
        let conf_path = cert_dir.join("mosquitto.conf");
        write_world_readable(&conf_path, conf);

        // The mounted dir must be traversable + readable by the in-container user.
        let mut dir_perms = std::fs::metadata(cert_dir).expect("dir metadata").permissions();
        dir_perms.set_mode(0o755);
        std::fs::set_permissions(cert_dir, dir_perms).expect("chmod dir");

        let name = format!("p2p-e2e-mosq-{}-{host_port}", std::process::id());
        // Best-effort cleanup of any stale container with the same name.
        let _ = Command::new("docker").args(["rm", "-f", &name]).output();

        let status = Command::new("docker")
            .args([
                "run",
                "-d",
                "--name",
                &name,
                "-p",
                &format!("127.0.0.1:{host_port}:8883"),
                "-v",
                &format!("{}:/mosquitto/certs:ro", cert_dir.display()),
                "-v",
                &format!("{}:/mosquitto/config/mosquitto.conf:ro", conf_path.display()),
                MOSQUITTO_IMAGE,
            ])
            .status()
            .expect("docker run");
        assert!(status.success(), "failed to start mosquitto container");
        MosquittoContainer { name }
    }

    fn logs(&self) -> String {
        Command::new("docker")
            .args(["logs", &self.name])
            .output()
            .map(|o| {
                format!(
                    "{}{}",
                    String::from_utf8_lossy(&o.stdout),
                    String::from_utf8_lossy(&o.stderr)
                )
            })
            .unwrap_or_default()
    }
}

impl Drop for MosquittoContainer {
    fn drop(&mut self) {
        let _ = Command::new("docker").args(["rm", "-f", &self.name]).output();
    }
}

async fn wait_for_tcp(port: u16, label: &str) {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        if TcpStream::connect(("127.0.0.1", port)).await.is_ok() {
            return;
        }
        assert!(Instant::now() < deadline, "{label} never became reachable on port {port}");
        sleep(Duration::from_millis(200)).await;
    }
}

/// Poll a daemon status file until `current_state` matches `expected`.
async fn wait_for_status(path: &Path, expected: &str, label: &str) {
    let deadline = Instant::now() + Duration::from_secs(40);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                if json["current_state"] == expected {
                    return;
                }
            }
        }
        assert!(
            Instant::now() < deadline,
            "{label} status never reached '{expected}' (path: {})",
            path.display()
        );
        sleep(Duration::from_millis(200)).await;
    }
}

/// Echo TCP server (the answer's tunnel target). Echoes whatever it receives back.
async fn spawn_echo_target(port: u16) {
    let listener = TcpListener::bind(("127.0.0.1", port)).await.expect("echo target should bind");
    tokio::spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            tokio::spawn(async move {
                let mut buf = vec![0_u8; 4096];
                loop {
                    match stream.read(&mut buf).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => {
                            if stream.write_all(&buf[..n]).await.is_err() {
                                return;
                            }
                        }
                    }
                }
            });
        }
    });
}

struct PeerParams<'a> {
    role: NodeRole,
    peer_id: &'a str,
    remote_peer_id: &'a str,
    broker_url: &'a str,
    ca_file: &'a Path,
    listen_port: u16,
    target_port: u16,
    state_dir: &'a Path,
    status_file: PathBuf,
}

fn peer_config(params: PeerParams) -> AppConfig {
    let PeerParams {
        role,
        peer_id,
        remote_peer_id,
        broker_url,
        ca_file,
        listen_port,
        target_port,
        state_dir,
        status_file,
    } = params;
    let peer_id: p2p_core::PeerId = peer_id.parse().expect("peer id");
    let client_id = peer_id.to_string();
    AppConfig {
        format: "p2ptunnel-config-v3".to_owned(),
        node: NodeConfig { peer_id, role },
        peer: Some(PeerConfig { remote_peer_id: remote_peer_id.parse().expect("remote peer id") }),
        paths: PathConfig {
            identity: state_dir.join("identity"),
            authorized_keys: state_dir.join("authorized_keys"),
            state_dir: state_dir.to_path_buf(),
            log_dir: state_dir.to_path_buf(),
        },
        broker: BrokerConfig {
            url: broker_url.to_owned(),
            client_id,
            topic_prefix: "p2ptunnel-e2e".to_owned(),
            username: String::new(),
            password_file: PathBuf::new(),
            qos: 1,
            keepalive_secs: 30,
            clean_session: false,
            connect_timeout_secs: 5,
            session_expiry_secs: 0,
            tls: BrokerTlsConfig {
                ca_file: ca_file.to_path_buf(),
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
            id: FORWARD_ID.to_owned(),
            offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port,
                allow_remote_peers: vec![OFFER_PEER.parse().expect("allowed peer id")],
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
            log_file: state_dir.join("p2ptunnel.log"),
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
    AuthorizedKeys::parse(&remote.public_identity.render()).expect("authorized keys parse")
}

async fn tunnel_echo_roundtrip(listen_port: u16, payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut client = TcpStream::connect(("127.0.0.1", listen_port))
        .await
        .map_err(|e| format!("connect to offer listener: {e}"))?;
    client.write_all(payload).await.map_err(|e| format!("write: {e}"))?;
    let mut received = vec![0_u8; payload.len()];
    timeout(Duration::from_secs(20), client.read_exact(&mut received))
        .await
        .map_err(|_| "read timed out".to_owned())?
        .map_err(|e| format!("read: {e}"))?;
    Ok(received)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn full_tunnel_over_real_tls_broker() {
    if !docker_available() {
        eprintln!("SKIP: docker not available; skipping real-broker tunnel E2E");
        return;
    }

    let cert_dir = tempfile::tempdir().expect("cert tempdir");
    let (ca_path, _cert, _key) = gen_broker_certs(cert_dir.path());

    let broker_port = free_port();
    let broker = MosquittoContainer::start(cert_dir.path(), broker_port);
    wait_for_tcp(broker_port, "mosquitto broker").await;
    // Give the TLS listener a moment to finish initializing after the port opens.
    sleep(Duration::from_millis(500)).await;

    let broker_url = format!("mqtts://localhost:{broker_port}");

    // Identities + cross-authorization.
    let offer_id = generate_identity(OFFER_PEER).expect("offer identity");
    let answer_id = generate_identity(ANSWER_PEER).expect("answer identity");

    let listen_port = free_port();
    let target_port = free_port();
    spawn_echo_target(target_port).await;

    let offer_state = tempfile::tempdir().expect("offer state");
    let answer_state = tempfile::tempdir().expect("answer state");
    let offer_status = offer_state.path().join("status.json");
    let answer_status = answer_state.path().join("status.json");

    let offer_config = peer_config(PeerParams {
        role: NodeRole::Offer,
        peer_id: OFFER_PEER,
        remote_peer_id: ANSWER_PEER,
        broker_url: &broker_url,
        ca_file: &ca_path,
        listen_port,
        target_port,
        state_dir: offer_state.path(),
        status_file: offer_status.clone(),
    });
    let answer_config = peer_config(PeerParams {
        role: NodeRole::Answer,
        peer_id: ANSWER_PEER,
        remote_peer_id: OFFER_PEER,
        broker_url: &broker_url,
        ca_file: &ca_path,
        listen_port,
        target_port,
        state_dir: answer_state.path(),
        status_file: answer_status.clone(),
    });

    let answer_keys = authorized_keys_for(&offer_id);
    let offer_keys = authorized_keys_for(&answer_id);

    let answer_task =
        tokio::spawn(run_answer_daemon(answer_config, answer_id.identity, answer_keys));
    let offer_task = tokio::spawn(run_offer_daemon(offer_config, offer_id.identity, offer_keys));

    // Both peers should connect to the broker and reach steady state.
    let startup = async {
        wait_for_status(&answer_status, "serving", "answer").await;
        wait_for_status(&offer_status, "waiting_for_local_client", "offer").await;
    };
    if timeout(Duration::from_secs(40), startup).await.is_err() {
        eprintln!("broker logs:\n{}", broker.logs());
        panic!("daemons did not reach steady state over the real broker");
    }

    // Drive real application data through the tunnel.
    let payload = b"GET /health HTTP/1.0\r\n\r\nhello-through-the-tunnel";
    let mut last_err = String::new();
    let mut echoed = None;
    let deadline = Instant::now() + Duration::from_secs(40);
    while Instant::now() < deadline {
        match tunnel_echo_roundtrip(listen_port, payload).await {
            Ok(received) => {
                echoed = Some(received);
                break;
            }
            Err(e) => {
                last_err = e;
                sleep(Duration::from_millis(500)).await;
            }
        }
    }

    offer_task.abort();
    answer_task.abort();

    let echoed =
        echoed.unwrap_or_else(|| panic!("tunnel echo never succeeded; last error: {last_err}"));
    assert_eq!(echoed, payload, "data must round-trip unchanged through the tunnel");
}
