//! Real-process SIGTERM/SIGINT integration coverage: launches the actual compiled
//! `p2p-offer` and `p2p-answer` binaries as child processes against a real TLS MQTT
//! broker, sends a real OS signal to each, and asserts they exit 0 with a final
//! `Closed` status — exercising the layer unit/direct-token tests cannot:
//! `OS signal -> process_signal adapter -> ShutdownToken -> cleanup`.
//!
//! The broker prefers a native `mosquitto` process (installed via `apt`/`brew` in
//! CI — see `.github/workflows/ci.yml`) and falls back to a Dockerized one only if
//! no native binary is found, so this does not silently depend on Docker being
//! available. Requires either broker backend plus pre-built debug binaries (`cargo
//! build --workspace` / `cargo test --workspace`, which the project's own
//! regression gate already runs before this test). When `P2P_REQUIRE_SIGNAL_TEST=1`
//! is set (as the required CI job does), a missing prerequisite is a hard failure
//! rather than a skip — see `required_signal_test()`.

use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::ExitStatusExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

use p2p_crypto::generate_identity;
use rcgen::{BasicConstraints, CertificateParams, DnType, IsCa, Issuer, KeyPair};
use tokio::net::TcpStream;
use tokio::time::{Instant, sleep, timeout};

const MOSQUITTO_IMAGE: &str = "eclipse-mosquitto:2";
const OFFER_PEER: &str = "sig-offer-peer";
const ANSWER_PEER: &str = "sig-answer-peer";

/// When set (as the CI job's required signal-lifecycle step does), a missing
/// broker backend or missing debug binaries must fail the test loudly instead of
/// silently skipping — see the non-negotiable rule against a required test
/// self-skipping because its prerequisite was never provisioned.
fn required_signal_test() -> bool {
    std::env::var_os("P2P_REQUIRE_SIGNAL_TEST").is_some()
}

/// Fails loudly if `required_signal_test()`, otherwise prints an explicit skip
/// notice and returns `true` (meaning: the caller should return/skip).
fn skip_or_fail(reason: &str) -> bool {
    assert!(!required_signal_test(), "required signal lifecycle test cannot skip: {reason}");
    eprintln!("SKIP: {reason}");
    true
}

fn native_mosquitto_available() -> bool {
    Command::new("mosquitto")
        .arg("--help")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok()
}

fn docker_available() -> bool {
    Command::new("docker").arg("version").output().map(|out| out.status.success()).unwrap_or(false)
}

fn workspace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("p2p-daemon should be two levels under the workspace root")
        .to_path_buf()
}

/// Locates a debug binary. Prefers an explicit `P2P_<NAME>_BIN` env var (set by
/// the required CI job right after it builds these exact binaries) over guessing
/// the conventional `target/debug/<name>` path, so CI failures point at a path
/// the job itself chose rather than one this test inferred. Returns `None`
/// (rather than building it here) if neither resolves to a file, so this test
/// can skip/fail explicitly instead of silently building/side-effecting or
/// hanging.
fn debug_bin(name: &str) -> Option<PathBuf> {
    let suffix = name.strip_prefix("p2p-").or_else(|| name.strip_prefix("p2p")).unwrap_or(name);
    let env_key = format!("P2P_{}_BIN", suffix.to_uppercase());
    if let Some(from_env) = std::env::var_os(&env_key) {
        let path = PathBuf::from(from_env);
        return path.is_file().then_some(path);
    }
    let path = workspace_root().join("target").join("debug").join(name);
    path.is_file().then_some(path)
}

fn free_port() -> u16 {
    std::net::TcpListener::bind(("127.0.0.1", 0))
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

fn write_private_identity(path: &Path, contents: &str) {
    std::fs::write(path, contents).expect("write identity file");
    let mut perms = std::fs::metadata(path).expect("metadata").permissions();
    perms.set_mode(0o600);
    std::fs::set_permissions(path, perms).expect("chmod identity file");
}

fn gen_broker_certs(dir: &Path) -> PathBuf {
    let ca_key = KeyPair::generate().expect("ca key");
    let mut ca_params = CertificateParams::new(Vec::<String>::new()).expect("ca params");
    ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    ca_params.distinguished_name.push(DnType::CommonName, "p2p-signal-test-ca");
    let ca_cert = ca_params.self_signed(&ca_key).expect("ca self-sign");

    let server_key = KeyPair::generate().expect("server key");
    let server_params =
        CertificateParams::new(vec!["localhost".to_string(), "127.0.0.1".to_string()])
            .expect("server params");
    let issuer = Issuer::from_params(&ca_params, &ca_key);
    let server_cert = server_params.signed_by(&server_key, &issuer).expect("server sign");

    let ca_path = dir.join("ca.crt");
    write_world_readable(&ca_path, &ca_cert.pem());
    write_world_readable(&dir.join("server.crt"), &server_cert.pem());
    write_world_readable(&dir.join("server.key"), &server_key.serialize_pem());
    ca_path
}

enum BrokerBackend {
    /// Preferred: a real `mosquitto` process installed on the runner (`apt`/
    /// `brew`), so this test does not depend on a container runtime being
    /// available — Docker is not preinstalled on GitHub's macOS runners.
    Native(std::process::Child),
    /// Fallback for environments with Docker but no native `mosquitto` binary.
    Docker { container_name: String },
}

struct MosquittoBroker {
    backend: BrokerBackend,
}

impl MosquittoBroker {
    fn start(cert_dir: &Path, host_port: u16) -> Self {
        if native_mosquitto_available() {
            Self::start_native(cert_dir, host_port)
        } else {
            Self::start_docker(cert_dir, host_port)
        }
    }

    fn start_native(cert_dir: &Path, host_port: u16) -> Self {
        let conf = format!(
            "listener {host_port}\n\
             allow_anonymous true\n\
             cafile {}\n\
             certfile {}\n\
             keyfile {}\n\
             require_certificate false\n",
            cert_dir.join("ca.crt").display(),
            cert_dir.join("server.crt").display(),
            cert_dir.join("server.key").display(),
        );
        let conf_path = cert_dir.join("mosquitto-native.conf");
        write_world_readable(&conf_path, &conf);

        let child = Command::new("mosquitto")
            .arg("-c")
            .arg(&conf_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn native mosquitto process");
        MosquittoBroker { backend: BrokerBackend::Native(child) }
    }

    fn start_docker(cert_dir: &Path, host_port: u16) -> Self {
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
        let mut dir_perms = std::fs::metadata(cert_dir).expect("dir metadata").permissions();
        dir_perms.set_mode(0o755);
        std::fs::set_permissions(cert_dir, dir_perms).expect("chmod dir");

        let container_name = format!("p2p-signal-test-mosq-{}-{host_port}", std::process::id());
        let _ = Command::new("docker").args(["rm", "-f", &container_name]).output();
        let status = Command::new("docker")
            .args([
                "run",
                "-d",
                "--name",
                &container_name,
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
        MosquittoBroker { backend: BrokerBackend::Docker { container_name } }
    }
}

impl Drop for MosquittoBroker {
    fn drop(&mut self) {
        match &mut self.backend {
            BrokerBackend::Native(child) => {
                let _ = child.kill();
                let _ = child.wait();
            }
            BrokerBackend::Docker { container_name } => {
                let _ = Command::new("docker").args(["rm", "-f", container_name]).output();
            }
        }
    }
}

/// Kills and reaps the wrapped real `p2p-offer`/`p2p-answer` child process on
/// drop unless [`Self::take`] has already handed it off. `std::process::Child`
/// does not kill its process on drop, so without this, a ready-marker timeout or
/// a failed signal-delivery assertion between spawn and the final wait would
/// panic and leave a full daemon process (bound port, live broker connection)
/// running indefinitely instead of being cleaned up.
struct ChildGuard {
    child: Option<std::process::Child>,
}

impl ChildGuard {
    fn new(child: std::process::Child) -> Self {
        Self { child: Some(child) }
    }

    /// Takes ownership of the child for the real, final wait(), disarming the
    /// guard so Drop does not also try to kill/reap it.
    fn take(&mut self) -> std::process::Child {
        self.child.take().expect("child already taken")
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
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

async fn wait_for_status_state(path: &Path, expected: &str, label: &str) {
    let deadline = Instant::now() + Duration::from_secs(40);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await
            && let Ok(json) = serde_json::from_str::<serde_json::Value>(&content)
            && json["current_state"] == expected
        {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "{label} status never reached '{expected}' (path: {})",
            path.display()
        );
        sleep(Duration::from_millis(200)).await;
    }
}

struct Peer {
    // Never read directly; kept alive so the backing temp directory (config,
    // identity, authorized_keys) is not removed while the child process runs.
    _dir: tempfile::TempDir,
    config_path: PathBuf,
    status_path: PathBuf,
}

/// Writes a fully self-contained peer directory (identity, authorized_keys, and a
/// config.toml pointing at all of it) for the given role, and returns paths a real
/// `p2p-offer`/`p2p-answer run --config ...` invocation can use directly.
fn write_peer(
    role: &str,
    peer_id: &str,
    remote_peer_id: &str,
    broker_url: &str,
    ca_file: &Path,
    listen_port: u16,
    target_port: u16,
) -> Peer {
    let dir = tempfile::tempdir().expect("peer tempdir");
    let identity = generate_identity(peer_id).expect("identity should generate");
    let remote = generate_identity(remote_peer_id).expect("remote identity should generate");

    let identity_path = dir.path().join("identity");
    let authorized_keys_path = dir.path().join("authorized_keys");
    let state_dir = dir.path().join("state");
    let log_dir = dir.path().join("log");
    std::fs::create_dir_all(&state_dir).expect("state dir");
    std::fs::create_dir_all(&log_dir).expect("log dir");
    write_private_identity(&identity_path, &identity.identity.render_toml());
    write_world_readable(&authorized_keys_path, &remote.public_identity.render());

    let status_path = dir.path().join("status.json");
    let config_path = dir.path().join("config.toml");
    let config = format!(
        r#"
format = "p2ptunnel-config-v3"

[node]
peer_id = "{peer_id}"
role = "{role}"

[peer]
remote_peer_id = "{remote_peer_id}"

[paths]
identity = "{identity}"
authorized_keys = "{authorized_keys}"
state_dir = "{state_dir}"
log_dir = "{log_dir}"

[broker]
url = "{broker_url}"
client_id = "{peer_id}"
topic_prefix = "p2ptunnel-signal-test"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "{ca_file}"
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false

[webrtc]
stun_urls = []
enable_trickle_ice = false
enable_ice_restart = true
android_ice_mode = "auto"

[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250
data_plane_probe_timeout_ms = 5000
data_plane_heartbeat_interval_ms = 5000
data_plane_heartbeat_max_misses = 3

[[forwards]]
id = "web"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = {listen_port}

[forwards.answer]
target_host = "127.0.0.1"
target_port = {target_port}
allow_remote_peers = ["{allow_remote_peer}"]

[reconnect]
enable_auto_reconnect = true
strategy = "ice_then_renegotiate"
ice_restart_timeout_secs = 8
renegotiate_timeout_secs = 20
backoff_initial_ms = 1000
backoff_max_ms = 30000
backoff_multiplier = 2.0
jitter_ratio = 0.20
max_attempts = 0
hold_local_client_during_reconnect = false
local_client_hold_secs = 0

[security]
require_mqtt_tls = true
require_message_encryption = true
require_message_signatures = true
require_authorized_keys = true
max_clock_skew_secs = 120
max_message_age_secs = 300
replay_cache_size = 10000
reject_unknown_config_keys = true
refuse_world_readable_identity = true
refuse_world_writable_paths = true

[logging]
level = "info"
format = "text"
file_logging = false
stdout_logging = true
log_file = "{log_dir}/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "{status_path}"
"#,
        peer_id = peer_id,
        role = role,
        remote_peer_id = remote_peer_id,
        identity = identity_path.display(),
        authorized_keys = authorized_keys_path.display(),
        state_dir = state_dir.display(),
        log_dir = log_dir.display(),
        broker_url = broker_url,
        ca_file = ca_file.display(),
        listen_port = listen_port,
        target_port = target_port,
        allow_remote_peer = OFFER_PEER,
        status_path = status_path.display(),
    );
    std::fs::write(&config_path, config).expect("write config.toml");

    Peer { _dir: dir, config_path, status_path }
}

/// Runs `bin run --config <path>` as a real child process, sends it `signal_flag`
/// (e.g. `-TERM`/`-INT`) once it reaches `expected_steady_state`, and asserts it
/// exits 0 with a final `Closed` status within a bounded timeout.
async fn assert_process_graceful_shutdown(
    bin: &Path,
    config_path: &Path,
    status_path: &Path,
    expected_steady_state: &str,
    signal_flag: &str,
    label: &str,
) {
    let child = Command::new(bin)
        .args(["run", "--config"])
        .arg(config_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap_or_else(|error| panic!("{label} should spawn: {error}"));
    let pid = child.id();
    let mut guard = ChildGuard::new(child);

    wait_for_status_state(status_path, expected_steady_state, label).await;

    let status = Command::new("kill")
        .arg(signal_flag)
        .arg(pid.to_string())
        .status()
        .expect("kill command should run");
    assert!(status.success(), "{label}: kill {signal_flag} {pid} should succeed");

    // Disarm the guard now: ownership moves into the blocking wait below, which
    // is the real, final reap. If that hangs, the timeout branch force-kills by
    // pid directly (the guard can no longer reach the child once moved).
    let mut child = guard.take();
    let exit =
        match timeout(Duration::from_secs(10), tokio::task::spawn_blocking(move || child.wait()))
            .await
        {
            Ok(join_result) => {
                join_result.expect("wait task should not panic").expect("wait should succeed")
            }
            Err(_) => {
                let _ = Command::new("kill").arg("-KILL").arg(pid.to_string()).status();
                panic!("{label} should exit before the test timeout");
            }
        };

    assert_eq!(
        exit.code(),
        Some(0),
        "{label} should exit 0 on graceful {signal_flag} shutdown, got {:?} (signal: {:?})",
        exit.code(),
        exit.signal(),
    );

    let final_status = serde_json::from_str::<serde_json::Value>(
        &tokio::fs::read_to_string(status_path)
            .await
            .unwrap_or_else(|error| panic!("{label} final status file should exist: {error}")),
    )
    .expect("final status should be valid json");
    assert_eq!(final_status["current_state"], "closed", "{label} final state should be closed");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn real_process_sigterm_and_sigint_shut_down_gracefully() {
    if !native_mosquitto_available()
        && !docker_available()
        && skip_or_fail(
            "neither a native `mosquitto` binary nor Docker is available for the broker",
        )
    {
        return;
    }
    let (Some(offer_bin), Some(answer_bin)) = (debug_bin("p2p-offer"), debug_bin("p2p-answer"))
    else {
        if skip_or_fail(
            "debug binaries not found (checked P2P_OFFER_BIN/P2P_ANSWER_BIN and target/debug); \
             run `cargo build --workspace` (or `cargo test --workspace`) first, or set those \
             env vars to explicit binary paths",
        ) {
            return;
        }
        unreachable!("skip_or_fail returns true or panics");
    };

    let cert_dir = tempfile::tempdir().expect("cert tempdir");
    let ca_path = gen_broker_certs(cert_dir.path());
    let broker_port = free_port();
    let broker = MosquittoBroker::start(cert_dir.path(), broker_port);
    wait_for_tcp(broker_port, "mosquitto broker").await;
    sleep(Duration::from_millis(500)).await;
    let broker_url = format!("mqtts://localhost:{broker_port}");

    // --- SIGTERM: answer role ---
    let listen_port_a = free_port();
    let target_port_a = free_port();
    let answer_peer_a = write_peer(
        "answer",
        ANSWER_PEER,
        OFFER_PEER,
        &broker_url,
        &ca_path,
        listen_port_a,
        target_port_a,
    );
    assert_process_graceful_shutdown(
        &answer_bin,
        &answer_peer_a.config_path,
        &answer_peer_a.status_path,
        "serving",
        "-TERM",
        "answer (SIGTERM)",
    )
    .await;

    // --- SIGTERM: offer role ---
    let listen_port_b = free_port();
    let target_port_b = free_port();
    let offer_peer_b = write_peer(
        "offer",
        OFFER_PEER,
        ANSWER_PEER,
        &broker_url,
        &ca_path,
        listen_port_b,
        target_port_b,
    );
    assert_process_graceful_shutdown(
        &offer_bin,
        &offer_peer_b.config_path,
        &offer_peer_b.status_path,
        "waiting_for_local_client",
        "-TERM",
        "offer (SIGTERM)",
    )
    .await;

    // --- SIGINT: offer role (at least one role must cover SIGINT too) ---
    let listen_port_c = free_port();
    let target_port_c = free_port();
    let offer_peer_c = write_peer(
        "offer",
        OFFER_PEER,
        ANSWER_PEER,
        &broker_url,
        &ca_path,
        listen_port_c,
        target_port_c,
    );
    assert_process_graceful_shutdown(
        &offer_bin,
        &offer_peer_c.config_path,
        &offer_peer_c.status_path,
        "waiting_for_local_client",
        "-INT",
        "offer (SIGINT)",
    )
    .await;

    drop(broker);
}
