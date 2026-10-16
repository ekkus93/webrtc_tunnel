//! End-to-end tunnel test over a **real MQTT broker** run as a **native `mosquitto`
//! process** (TLS) — no Docker.
//!
//! Same coverage as `real_broker_tunnel.rs`: drives the real `run_offer_daemon` /
//! `run_answer_daemon` entry points through `MqttSignalingTransport` and a real
//! `mqtts://` broker, then proves application data flows through the tunnel:
//!
//!   client -> offer local listener -> WebRTC -> answer -> echo target -> back
//!
//! The difference is only how the broker is hosted. `real_broker_tunnel.rs` needs a
//! Linux container (and POSIX permission bits for its bind mounts), so it is
//! unix-only; this test starts the `mosquitto` executable directly, so it runs on any
//! host that has one — in particular `windows-latest`, which cannot reliably host
//! Linux containers. The broker's cert is signed by a throwaway CA generated at test
//! time (rcgen), and the daemons trust that CA via `broker.tls.ca_file`.
//!
//! The broker binary is `P2P_MOSQUITTO_BIN` if set, otherwise `mosquitto` on `PATH`,
//! otherwise (Windows only) the Chocolatey / installer default
//! `%ProgramFiles%\mosquitto\mosquitto.exe`. If none is found the test logs a skip and
//! passes, so `cargo test` stays green on hosts without mosquitto. When
//! `P2P_REQUIRE_BROKER_TEST=1` is set (as CI sets it), a missing broker is a hard
//! failure instead, so a CI job that never provisioned mosquitto cannot pass silently.

use std::ffi::OsString;
use std::fs::File;
use std::net::TcpListener as StdTcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::Duration;

use p2p_core::{
    AppConfig, BrokerConfig, BrokerTlsConfig, DEFAULT_ICE_CHECKING_TIMEOUT_MS, ForwardAnswerConfig,
    ForwardOfferConfig, ForwardRule, HealthConfig, LoggingConfig, NodeConfig, NodeRole, PathConfig,
    PeerConfig, ReconnectConfig, SecurityConfig, TunnelConfig, WebRtcConfig,
};
use p2p_crypto::{AuthorizedKeys, GeneratedIdentity, generate_identity};
use p2p_daemon::{run_answer_daemon, run_offer_daemon};
use rcgen::{BasicConstraints, CertificateParams, DnType, IsCa, Issuer, KeyPair};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{Instant, sleep, timeout};

const OFFER_PEER: &str = "offer-peer";
const ANSWER_PEER: &str = "answer-peer";
const FORWARD_ID: &str = "web";

/// When set, a missing broker binary fails the test instead of skipping it.
fn required_broker_test() -> bool {
    std::env::var_os("P2P_REQUIRE_BROKER_TEST").is_some()
}

/// Candidate broker binaries, most explicit first.
fn mosquitto_candidates() -> Vec<OsString> {
    if let Some(explicit) = std::env::var_os("P2P_MOSQUITTO_BIN").filter(|bin| !bin.is_empty()) {
        // An explicit override is the only candidate: silently falling back to a
        // different broker than the one asked for would hide a misconfiguration.
        return vec![explicit];
    }
    let mut candidates = vec![OsString::from("mosquitto")];
    if cfg!(windows) {
        // The Chocolatey package and the upstream installer both install here and
        // neither adds the directory to PATH.
        if let Some(program_files) = std::env::var_os("ProgramFiles") {
            candidates
                .push(PathBuf::from(program_files).join("mosquitto").join("mosquitto.exe").into());
        }
    }
    candidates
}

/// The first candidate that can actually be executed. `mosquitto --help` exits
/// non-zero on some versions, so only the spawn itself is checked.
fn find_mosquitto() -> Option<OsString> {
    mosquitto_candidates().into_iter().find(|bin| {
        Command::new(bin)
            .arg("--help")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok()
    })
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

/// Generate a throwaway CA + a server cert (SAN localhost / 127.0.0.1) signed by it.
/// Returns the paths to `ca.crt`, `server.crt`, `server.key`.
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
    std::fs::write(&ca_path, ca_cert.pem()).expect("write ca.crt");
    std::fs::write(&cert_path, server_cert.pem()).expect("write server.crt");
    std::fs::write(&key_path, server_key.serialize_pem()).expect("write server.key");
    (ca_path, cert_path, key_path)
}

/// A native mosquitto process with a TLS listener; killed on drop.
struct NativeMosquitto {
    child: Child,
    log_path: PathBuf,
}

impl NativeMosquitto {
    fn start(bin: &OsString, dir: &Path, port: u16, cert: &Path, key: &Path, ca: &Path) -> Self {
        // Bound to 127.0.0.1 only, so a Windows host does not raise a firewall
        // prompt for a test broker. Mosquitto 2.x defaults to allow_anonymous=false;
        // enable it explicitly. Forward slashes keep Windows paths unambiguous in
        // mosquitto.conf, which Windows' file APIs accept.
        let conf = format!(
            "listener {port} 127.0.0.1\n\
             allow_anonymous true\n\
             persistence false\n\
             cafile {}\n\
             certfile {}\n\
             keyfile {}\n\
             require_certificate false\n",
            conf_path(ca),
            conf_path(cert),
            conf_path(key),
        );
        let conf_file = dir.join("mosquitto.conf");
        std::fs::write(&conf_file, conf).expect("write mosquitto.conf");

        // Broker output goes to a file rather than a pipe, so a chatty broker can
        // never block on a full pipe buffer, and it is still there to dump on failure.
        let log_path = dir.join("mosquitto.log");
        let log = File::create(&log_path).expect("create mosquitto log");
        let child = Command::new(bin)
            .arg("-c")
            .arg(&conf_file)
            .arg("-v")
            .stdin(Stdio::null())
            .stdout(log.try_clone().expect("clone mosquitto log handle"))
            .stderr(log)
            .spawn()
            .expect("spawn native mosquitto process");
        NativeMosquitto { child, log_path }
    }

    fn logs(&self) -> String {
        std::fs::read_to_string(&self.log_path).unwrap_or_default()
    }

    /// Panics with the broker log if the process has already exited.
    fn assert_running(&mut self) {
        if let Ok(Some(status)) = self.child.try_wait() {
            panic!("mosquitto exited early ({status}); log:\n{}", self.logs());
        }
    }
}

impl Drop for NativeMosquitto {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn conf_path(path: &Path) -> String {
    path.display().to_string().replace('\\', "/")
}

async fn wait_for_broker(broker: &mut NativeMosquitto, port: u16) {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        broker.assert_running();
        if TcpStream::connect(("127.0.0.1", port)).await.is_ok() {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "mosquitto never became reachable on port {port}; log:\n{}",
            broker.logs()
        );
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
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
            ice_checking_timeout_ms: DEFAULT_ICE_CHECKING_TIMEOUT_MS,
        },
        tunnel: TunnelConfig {
            read_chunk_size: 16_384,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
            data_plane_probe_timeout_ms: 5000,
            data_plane_heartbeat_interval_ms: 5000,
            data_plane_heartbeat_max_misses: 3,
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
async fn full_tunnel_over_native_tls_broker() {
    let Some(mosquitto_bin) = find_mosquitto() else {
        let reason = "no mosquitto executable found (set P2P_MOSQUITTO_BIN or put mosquitto on \
                      PATH)";
        assert!(
            !required_broker_test(),
            "P2P_REQUIRE_BROKER_TEST is set, so this cannot skip: {reason}"
        );
        eprintln!("SKIP: {reason}; skipping native-broker tunnel E2E");
        return;
    };

    let broker_dir = tempfile::tempdir().expect("broker tempdir");
    let (ca_path, cert_path, key_path) = gen_broker_certs(broker_dir.path());

    let broker_port = free_port();
    let mut broker = NativeMosquitto::start(
        &mosquitto_bin,
        broker_dir.path(),
        broker_port,
        &cert_path,
        &key_path,
        &ca_path,
    );
    wait_for_broker(&mut broker, broker_port).await;
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
        offer_task.abort();
        answer_task.abort();
        panic!(
            "daemons did not reach steady state over the native broker; log:\n{}",
            broker.logs()
        );
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

    let echoed = echoed.unwrap_or_else(|| {
        panic!("tunnel echo never succeeded; last error: {last_err}; log:\n{}", broker.logs())
    });
    assert_eq!(echoed, payload, "data must round-trip unchanged through the tunnel");
}
