//! Real Service Control Manager shutdown coverage for `p2p-service-windows`: the
//! Windows counterpart of `crates/p2p-daemon/tests/process_signal_shutdown.rs`.
//!
//! Registers the compiled service binary as a throwaway service, starts it in the
//! offer role, sends it a real `SERVICE_CONTROL_STOP`, and asserts the stop was
//! truthful: the SCM reports a clean exit code, the status file's terminal state is
//! `closed` with every forward `stopped`, and the forward's local port is released.
//! That exercises the layer the daemon's own tests cannot reach:
//! `SERVICE_CONTROL_STOP -> control handler -> ShutdownToken -> drain -> SERVICE_STOPPED`.
//!
//! The service is registered as `p2ptunnel-test-<pid>`, never `p2ptunnel`, so an
//! installed service is never touched. The binary hardcodes `p2ptunnel` as the name
//! it passes to the dispatcher and control-handler registration; Windows ignores that
//! name for a `SERVICE_WIN32_OWN_PROCESS` service, and the service reaching `RUNNING`
//! under another registered name is the empirical proof.
//!
//! The offer daemon only binds its listeners after its first broker subscribe
//! succeeds, and gives up after a few failed attempts, so pointing it at an
//! unreachable broker would make it exit before there is a listener to check. The
//! test therefore hosts a minimal in-process MQTT 3.1.1 broker over TLS on loopback
//! (CONNACK, SUBACK, PINGRESP, publish acks; it routes nothing), which is enough to
//! hold the daemon in its `waiting_for_local_client` steady state with no network.
//!
//! Creating a service needs Administrator rights. A non-elevated run prints a skip
//! notice and passes, unless `P2P_REQUIRE_SCM_TEST` is set, in which case the skip
//! is a hard failure, as with `P2P_REQUIRE_SIGNAL_TEST` in the unix test.

#![cfg(windows)]

use std::ffi::OsString;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;

use p2p_crypto::generate_identity;
use rcgen::{BasicConstraints, CertificateParams, DnType, IsCa, Issuer, KeyPair};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tokio::time::{Instant, sleep};
use tokio_rustls::TlsAcceptor;
use tokio_rustls::rustls::ServerConfig;
use tokio_rustls::rustls::pki_types::PrivatePkcs8KeyDer;
use windows_service::service::{
    Service, ServiceAccess, ServiceErrorControl, ServiceExitCode, ServiceInfo, ServiceStartType,
    ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

const OFFER_PEER: &str = "scm-offer-peer";
const ANSWER_PEER: &str = "scm-answer-peer";
const FORWARD_ID: &str = "web";

/// `ERROR_ACCESS_DENIED`: opening the SCM for `SC_MANAGER_CREATE_SERVICE` without an
/// elevated token.
const ERROR_ACCESS_DENIED: i32 = 5;

/// Covers config and identity loading plus the first broker subscribe.
const START_TIMEOUT: Duration = Duration::from_secs(60);
/// The service caps draining at 30 s and then reports a failed stop, so anything
/// past this margin means the service never reported `STOPPED` at all.
const STOP_TIMEOUT: Duration = Duration::from_secs(45);

/// When set, a non-elevated run fails instead of skipping.
fn required_scm_test() -> bool {
    std::env::var_os("P2P_REQUIRE_SCM_TEST").is_some()
}

/// Opens the SCM with exactly the right this test needs. A non-elevated token is
/// refused with `ERROR_ACCESS_DENIED`, which is the elevation check: it asks the
/// question the test depends on rather than inferring it from group membership.
fn open_scm_for_create() -> Option<ServiceManager> {
    match ServiceManager::local_computer(
        None::<&str>,
        ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE,
    ) {
        Ok(manager) => Some(manager),
        Err(windows_service::Error::Winapi(error))
            if error.raw_os_error() == Some(ERROR_ACCESS_DENIED) =>
        {
            None
        }
        Err(error) => panic!("could not open the Service Control Manager: {error}"),
    }
}

fn free_port() -> u16 {
    std::net::TcpListener::bind(("127.0.0.1", 0))
        .expect("bind ephemeral")
        .local_addr()
        .expect("local addr")
        .port()
}

/// A loopback MQTT 3.1.1 broker over TLS that acknowledges everything and routes
/// nothing. The daemon only needs its connect and subscribe to succeed and its
/// keepalives answered to stay in its steady state.
struct FakeBroker {
    port: u16,
    accept_task: JoinHandle<()>,
}

impl FakeBroker {
    /// Generates a throwaway CA and a `localhost` / `127.0.0.1` server cert signed by
    /// it, writes the CA to `ca_path` for the daemon's `broker.tls.ca_file`, and
    /// starts accepting.
    async fn start(ca_path: &Path) -> Self {
        let ca_key = KeyPair::generate().expect("ca key");
        let mut ca_params = CertificateParams::new(Vec::<String>::new()).expect("ca params");
        ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
        ca_params.distinguished_name.push(DnType::CommonName, "p2p-scm-test-ca");
        let ca_cert = ca_params.self_signed(&ca_key).expect("ca self-sign");
        let server_key = KeyPair::generate().expect("server key");
        let server_params =
            CertificateParams::new(vec!["localhost".to_string(), "127.0.0.1".to_string()])
                .expect("server params");
        let issuer = Issuer::from_params(&ca_params, &ca_key);
        let server_cert = server_params.signed_by(&server_key, &issuer).expect("server sign");
        std::fs::write(ca_path, ca_cert.pem()).expect("write broker CA");

        let tls = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![server_cert.der().clone()],
                PrivatePkcs8KeyDer::from(server_key.serialize_der()).into(),
            )
            .expect("broker TLS config");
        let acceptor = TlsAcceptor::from(Arc::new(tls));

        // Loopback only, so the host firewall never prompts or interferes.
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("bind broker");
        let port = listener.local_addr().expect("broker addr").port();
        let accept_task = tokio::spawn(async move {
            loop {
                let Ok((tcp, _)) = listener.accept().await else {
                    continue;
                };
                let acceptor = acceptor.clone();
                tokio::spawn(async move {
                    if let Ok(stream) = acceptor.accept(tcp).await {
                        // A client going away mid-packet is the normal end of a
                        // connection here, not a test failure.
                        let _ = serve_mqtt_client(stream).await;
                    }
                });
            }
        });
        FakeBroker { port, accept_task }
    }
}

impl Drop for FakeBroker {
    fn drop(&mut self) {
        self.accept_task.abort();
    }
}

async fn read_remaining_length<S: AsyncRead + Unpin>(stream: &mut S) -> std::io::Result<usize> {
    let mut value = 0usize;
    for shift in [0, 7, 14, 21] {
        let byte = stream.read_u8().await?;
        value |= usize::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(std::io::Error::new(ErrorKind::InvalidData, "MQTT remaining length too long"))
}

fn packet_id(body: &[u8], offset: usize) -> std::io::Result<[u8; 2]> {
    body.get(offset..offset + 2)
        .and_then(|bytes| bytes.try_into().ok())
        .ok_or_else(|| std::io::Error::new(ErrorKind::InvalidData, "truncated MQTT packet"))
}

/// SUBACK granting each requested filter the QoS it asked for.
fn suback(body: &[u8]) -> std::io::Result<Vec<u8>> {
    let id = packet_id(body, 0)?;
    let mut granted = Vec::new();
    let mut offset = 2;
    while offset < body.len() {
        let [high, low] = packet_id(body, offset)?;
        offset += 2 + usize::from(u16::from_be_bytes([high, low]));
        let qos = *body
            .get(offset)
            .ok_or_else(|| std::io::Error::new(ErrorKind::InvalidData, "truncated SUBSCRIBE"))?;
        granted.push(qos);
        offset += 1;
    }
    let remaining = u8::try_from(2 + granted.len())
        .map_err(|_| std::io::Error::new(ErrorKind::InvalidData, "too many filters"))?;
    let mut reply = vec![0x90, remaining, id[0], id[1]];
    reply.extend(granted);
    Ok(reply)
}

/// A two-byte acknowledgement (`packet_type` plus packet id) for the packet whose
/// id starts at `offset` in `body`.
fn ack(packet_type: u8, body: &[u8], offset: usize) -> std::io::Result<Vec<u8>> {
    let [high, low] = packet_id(body, offset)?;
    Ok(vec![packet_type, 0x02, high, low])
}

/// PUBACK for QoS 1, PUBREC for QoS 2, nothing for QoS 0.
fn publish_ack(header: u8, body: &[u8]) -> std::io::Result<Option<Vec<u8>>> {
    let packet_type = match (header >> 1) & 0x03 {
        0 => return Ok(None),
        1 => 0x40,
        _ => 0x50,
    };
    // The packet id follows the length-prefixed topic name.
    let [high, low] = packet_id(body, 0)?;
    ack(packet_type, body, 2 + usize::from(u16::from_be_bytes([high, low]))).map(Some)
}

async fn serve_mqtt_client<S: AsyncRead + AsyncWrite + Unpin>(
    mut stream: S,
) -> std::io::Result<()> {
    loop {
        let header = stream.read_u8().await?;
        let length = read_remaining_length(&mut stream).await?;
        let mut body = vec![0; length];
        stream.read_exact(&mut body).await?;
        let reply = match header >> 4 {
            // CONNECT -> CONNACK, accepted, no session present.
            1 => Some(vec![0x20, 0x02, 0x00, 0x00]),
            3 => publish_ack(header, &body)?,
            // PUBREL -> PUBCOMP.
            6 => Some(ack(0x70, &body, 0)?),
            8 => Some(suback(&body)?),
            // UNSUBSCRIBE -> UNSUBACK.
            10 => Some(ack(0xB0, &body, 0)?),
            // PINGREQ -> PINGRESP.
            12 => Some(vec![0xD0, 0x00]),
            // DISCONNECT.
            14 => return Ok(()),
            _ => None,
        };
        if let Some(reply) = reply {
            stream.write_all(&reply).await?;
            stream.flush().await?;
        }
    }
}

/// Limits `dir` (and everything created in it afterwards) to SYSTEM and Administrators, the
/// way the installer limits `%ProgramData%\p2ptunnel`. The service runs as LocalSystem, and
/// its runtime DACL check refuses an identity that another ordinary account, such as the
/// user whose `%TEMP%` the fixture would otherwise inherit from, can read.
fn restrict_to_system_and_administrators(dir: &Path) {
    let output = Command::new("icacls")
        .arg(dir)
        .args(["/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F"])
        .output()
        .expect("run icacls");
    assert!(
        output.status.success(),
        "icacls failed on {}: {}",
        dir.display(),
        String::from_utf8_lossy(&output.stdout)
    );
}

/// Resets every entry directly inside `dir` to the ACL it inherits from `dir`, dropping any
/// explicit ACE the creating process added. On a hosted runner the new files carry a
/// write ACE for the runner's own account (the built-in Administrator, not the
/// Administrators group), which the service's permission check rightly refuses when it
/// runs as LocalSystem. Nothing here grants anything: the files end up with exactly the
/// SYSTEM and Administrators entries `restrict_to_system_and_administrators` set.
fn reset_children_to_inherited(dir: &Path) {
    for entry in std::fs::read_dir(dir).expect("read fixture dir") {
        let path = entry.expect("fixture dir entry").path();
        let output = Command::new("icacls").arg(&path).arg("/reset").output().expect("run icacls");
        assert!(
            output.status.success(),
            "icacls /reset failed on {}: {}",
            path.display(),
            String::from_utf8_lossy(&output.stdout)
        );
    }
}

/// What `icacls` prints for `path`, for failure messages.
fn acl_listing(path: &Path) -> String {
    match Command::new("icacls").arg(path).output() {
        Ok(output) => String::from_utf8_lossy(&output.stdout).into_owned(),
        Err(error) => format!("<icacls failed: {error}>"),
    }
}

/// A self-contained offer-role config directory: identity, authorized_keys, and a
/// config.toml whose paths all point inside it.
struct OfferFixture {
    // Kept alive so the directory outlives the service that reads from it.
    _dir: tempfile::TempDir,
    config_path: PathBuf,
    status_path: PathBuf,
    log_path: PathBuf,
    listen_port: u16,
}

impl OfferFixture {
    /// Writes the fixture into `dir`, trusting the broker CA already at `ca_path`.
    fn write(dir: tempfile::TempDir, broker_port: u16, ca_path: &Path) -> Self {
        let root = dir.path();
        restrict_to_system_and_administrators(root);
        let identity = generate_identity(OFFER_PEER).expect("offer identity");
        let remote = generate_identity(ANSWER_PEER).expect("answer identity");

        let identity_path = root.join("identity");
        let authorized_keys_path = root.join("authorized_keys");
        let state_dir = root.join("state");
        let log_dir = root.join("log");
        std::fs::create_dir_all(&state_dir).expect("state dir");
        std::fs::create_dir_all(&log_dir).expect("log dir");
        std::fs::write(&identity_path, identity.identity.render_toml()).expect("write identity");
        std::fs::write(&authorized_keys_path, remote.public_identity.render())
            .expect("write authorized_keys");

        let status_path = root.join("status.json");
        let log_path = log_dir.join("p2ptunnel.log");
        let config_path = root.join("config.toml");
        let listen_port = free_port();

        // Derived from docs/examples/offer-config.toml. Paths are TOML literal
        // strings so Windows backslashes need no escaping. A service has no
        // console, so logging goes to a file only (spec §6.3); the test also
        // reads that file back for its failure diagnostics.
        let config = format!(
            r#"format = "p2ptunnel-config-v3"

[node]
peer_id = "{OFFER_PEER}"
role = "offer"

[peer]
remote_peer_id = "{ANSWER_PEER}"

[paths]
identity = '{identity}'
authorized_keys = '{authorized_keys}'
state_dir = '{state_dir}'
log_dir = '{log_dir}'

[broker]
url = "mqtts://localhost:{broker_port}"
client_id = "{OFFER_PEER}"
topic_prefix = "p2ptunnel-scm-test"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = '{ca_file}'
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
id = "{FORWARD_ID}"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = {listen_port}

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
file_logging = true
stdout_logging = false
log_file = '{log_file}'
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = '{status_file}'
"#,
            identity = identity_path.display(),
            authorized_keys = authorized_keys_path.display(),
            state_dir = state_dir.display(),
            log_dir = log_dir.display(),
            ca_file = ca_path.display(),
            log_file = log_path.display(),
            status_file = status_path.display(),
        );
        std::fs::write(&config_path, config).expect("write config.toml");
        reset_children_to_inherited(root);

        OfferFixture { _dir: dir, config_path, status_path, log_path, listen_port }
    }

    fn status(&self) -> Option<serde_json::Value> {
        let content = std::fs::read_to_string(&self.status_path).ok()?;
        serde_json::from_str(&content).ok()
    }

    /// The status file and the service's log, for failure messages: once the
    /// service is running, the log is the only place its errors go.
    fn diagnostics(&self) -> String {
        let status = std::fs::read_to_string(&self.status_path)
            .unwrap_or_else(|error| format!("<no status file: {error}>"));
        let log = std::fs::read_to_string(&self.log_path)
            .unwrap_or_else(|error| format!("<no log file: {error}>"));
        let tail: Vec<&str> = log.lines().rev().take(40).collect();
        let tail: Vec<&str> = tail.into_iter().rev().collect();
        // Config and logging failures (exit codes 1 and 2) happen before the log file
        // exists; the wrapper records them here instead.
        let startup_error_path = self
            .config_path
            .parent()
            .map(|root| root.join("state").join("startup-error.log"))
            .unwrap_or_default();
        let startup_error = std::fs::read_to_string(&startup_error_path)
            .unwrap_or_else(|error| format!("<no startup-error.log: {error}>"));
        // The service's permission check reads these, so a rejection is easiest to
        // understand next to the ACLs it saw.
        let acls = self.config_path.parent().map_or_else(String::new, |root| {
            format!("{}{}", acl_listing(root), acl_listing(&root.join("identity")))
        });
        format!(
            "\n--- status file ({}) ---\n{status}\n--- service log tail ({}) ---\n{}\n--- startup errors ({}) ---\n{startup_error}\n--- fixture ACLs ---\n{acls}",
            self.status_path.display(),
            self.log_path.display(),
            tail.join("\n"),
            startup_error_path.display(),
        )
    }
}

/// A registered throwaway service. Drop stops it if it is still running and then
/// deletes it, so a failed assertion never leaves a service behind.
struct TestService {
    name: String,
    service: Service,
}

impl TestService {
    fn create(manager: &ServiceManager, name: &str, config_path: &Path) -> Self {
        let info = ServiceInfo {
            name: OsString::from(name),
            display_name: OsString::from(format!("{name} (p2p-service-windows SCM test)")),
            service_type: ServiceType::OWN_PROCESS,
            start_type: ServiceStartType::OnDemand,
            error_control: ServiceErrorControl::Normal,
            executable_path: PathBuf::from(env!("CARGO_BIN_EXE_p2p-service-windows")),
            launch_arguments: vec![
                OsString::from("--role"),
                OsString::from("offer"),
                OsString::from("--config"),
                config_path.as_os_str().to_owned(),
            ],
            dependencies: vec![],
            // LocalSystem. The virtual `NT SERVICE\<name>` account and its DACL
            // are the installer's concern (P0-008), not the stop path's.
            account_name: None,
            account_password: None,
        };
        let access = ServiceAccess::QUERY_STATUS
            | ServiceAccess::START
            | ServiceAccess::STOP
            | ServiceAccess::DELETE;
        let service = manager.create_service(&info, access).unwrap_or_else(|error| {
            panic!(
                "could not create test service {name}: {error}. If a previous run left it \
                 behind, remove it with `sc.exe delete {name}`"
            )
        });
        TestService { name: name.to_owned(), service }
    }

    fn query(&self) -> ServiceStatus {
        self.service.query_status().expect("query test service status")
    }

    /// Polls until the service reaches `target`, failing early if it stops instead.
    async fn wait_for_state(
        &self,
        target: ServiceState,
        limit: Duration,
        fixture: &OfferFixture,
    ) -> ServiceStatus {
        let deadline = Instant::now() + limit;
        loop {
            let status = self.query();
            if status.current_state == target {
                return status;
            }
            assert!(
                status.current_state != ServiceState::Stopped,
                "{} stopped while waiting for {target:?} (exit code {:?}){}",
                self.name,
                status.exit_code,
                fixture.diagnostics(),
            );
            assert!(
                Instant::now() < deadline,
                "{} did not reach {target:?} within {limit:?}; last state {:?}{}",
                self.name,
                status.current_state,
                fixture.diagnostics(),
            );
            sleep(Duration::from_millis(200)).await;
        }
    }
}

impl Drop for TestService {
    fn drop(&mut self) {
        if self
            .service
            .query_status()
            .is_ok_and(|status| status.current_state != ServiceState::Stopped)
        {
            let _ = self.service.stop();
            let deadline = std::time::Instant::now() + STOP_TIMEOUT;
            while std::time::Instant::now() < deadline
                && self
                    .service
                    .query_status()
                    .is_ok_and(|status| status.current_state != ServiceState::Stopped)
            {
                std::thread::sleep(Duration::from_millis(200));
            }
        }
        if let Err(error) = self.service.delete() {
            eprintln!(
                "WARNING: could not delete test service {}: {error}; remove it with \
                 `sc.exe delete {}`",
                self.name, self.name
            );
        }
    }
}

/// Whether some socket holds `127.0.0.1:port`. Windows refuses a second bind to the
/// same address unless it opts into `SO_REUSEADDR`, which `std` does not. This
/// checks the listener without connecting to it: a connection would be accepted as
/// a local client and start a peer session, moving the daemon out of the steady
/// state the stop is meant to begin from.
fn port_is_held(port: u16) -> bool {
    match std::net::TcpListener::bind(("127.0.0.1", port)) {
        Ok(_) => false,
        Err(error) if error.kind() == ErrorKind::AddrInUse => true,
        Err(error) => panic!("unexpected error probing 127.0.0.1:{port}: {error}"),
    }
}

fn forward_states(status: &serde_json::Value) -> Vec<String> {
    status["forwards"]
        .as_array()
        .map(|forwards| {
            forwards.iter().map(|forward| forward["listen_state"].to_string()).collect()
        })
        .unwrap_or_default()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn service_control_stop_shuts_the_offer_daemon_down_truthfully() {
    let Some(manager) = open_scm_for_create() else {
        assert!(
            !required_scm_test(),
            "P2P_REQUIRE_SCM_TEST is set but this process is not elevated, so it cannot \
             create a service"
        );
        eprintln!(
            "SKIP: creating a service needs Administrator rights; rerun from an elevated \
             shell to exercise SERVICE_CONTROL_STOP"
        );
        return;
    };

    let dir = tempfile::tempdir().expect("fixture tempdir");
    let ca_path = dir.path().join("broker-ca.crt");
    let broker = FakeBroker::start(&ca_path).await;
    let fixture = OfferFixture::write(dir, broker.port, &ca_path);

    let name = format!("p2ptunnel-test-{}", std::process::id());
    let service = TestService::create(&manager, &name, &fixture.config_path);

    service.service.start(&[] as &[OsString]).expect("start test service");
    let running = service.wait_for_state(ServiceState::Running, START_TIMEOUT, &fixture).await;
    eprintln!("{name} is RUNNING (pid {:?})", running.process_id);

    // RUNNING only means config and identity loaded. The daemon is in its steady
    // state once it has subscribed and bound its listener.
    let deadline = Instant::now() + START_TIMEOUT;
    loop {
        if let Some(status) = fixture.status()
            && status["current_state"] == "waiting_for_local_client"
            && forward_states(&status) == ["\"listening\""]
        {
            break;
        }
        let state = service.query();
        assert!(
            state.current_state == ServiceState::Running,
            "{name} left RUNNING before reaching its steady state: {:?} (exit code {:?}){}",
            state.current_state,
            state.exit_code,
            fixture.diagnostics(),
        );
        assert!(
            Instant::now() < deadline,
            "{name} never reported waiting_for_local_client with its forward listening{}",
            fixture.diagnostics(),
        );
        sleep(Duration::from_millis(200)).await;
    }
    assert!(
        port_is_held(fixture.listen_port),
        "status says forward {FORWARD_ID} is listening, but nothing holds 127.0.0.1:{}{}",
        fixture.listen_port,
        fixture.diagnostics(),
    );

    // The real control: what `sc.exe stop` sends.
    let stop_started = Instant::now();
    service.service.stop().expect("send SERVICE_CONTROL_STOP");
    let stopped = service.wait_for_state(ServiceState::Stopped, STOP_TIMEOUT, &fixture).await;
    eprintln!("{name} STOPPED {:?} after the stop request", stop_started.elapsed());

    // 1. Exit code: what `sc.exe queryex` shows as WIN32_EXIT_CODE 0 and
    //    SERVICE_EXIT_CODE 0. A failure phase would be ServiceSpecific(1..=5).
    assert_eq!(
        stopped.exit_code,
        ServiceExitCode::NO_ERROR,
        "{name} should report a clean exit after SERVICE_CONTROL_STOP{}",
        fixture.diagnostics(),
    );

    // 2. Terminal status: `closed`, with no forward still claiming to listen.
    let final_status = fixture.status().unwrap_or_else(|| {
        panic!("final status file should be valid json{}", fixture.diagnostics())
    });
    assert_eq!(
        final_status["current_state"],
        "closed",
        "final state should be closed{}",
        fixture.diagnostics(),
    );
    assert_eq!(
        forward_states(&final_status),
        ["\"stopped\""],
        "final status should report forward {FORWARD_ID} stopped{}",
        fixture.diagnostics(),
    );

    // 3. No lingering listener, checked the moment the SCM reports STOPPED: the
    //    port refuses connections and can be bound again. Windows retransmits the SYN
    //    to a closed loopback port for about two seconds before it reports the refusal,
    //    so the timeout has to be well beyond that or a refusal reads as `TimedOut`.
    match std::net::TcpStream::connect_timeout(
        &([127, 0, 0, 1], fixture.listen_port).into(),
        Duration::from_secs(10),
    ) {
        Err(error) if error.kind() == ErrorKind::ConnectionRefused => {}
        other => panic!(
            "127.0.0.1:{} should refuse connections once {name} is STOPPED, got {other:?}{}",
            fixture.listen_port,
            fixture.diagnostics(),
        ),
    }
    assert!(
        !port_is_held(fixture.listen_port),
        "127.0.0.1:{} is still bound after {name} reported STOPPED",
        fixture.listen_port,
    );

    drop(service);
    drop(broker);
}
