//! Verifies that `start()` fails loudly instead of silently losing the diagnostics
//! surface when the process-global `tracing` subscriber is already taken by someone
//! else. Runs as its own process (a separate integration-test binary) because the
//! failure requires a real, unshared global subscriber conflict — running it inside
//! the crate's own unit-test binary would make the outcome depend on test ordering
//! and could poison the shared `OnceLock` for every other test in that binary.

use std::fs;

use p2p_crypto::generate_identity;
use p2p_mobile::AndroidTunnelController;
use tracing_subscriber::prelude::*;

#[test]
fn start_fails_when_tracing_bridge_install_loses_to_an_existing_global_subscriber() {
    // Take the process-global default subscriber ourselves first, so the crate's
    // own `install_tracing_once` is guaranteed to lose the race.
    tracing_subscriber::registry().try_init().expect("this process's first subscriber install");

    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    fs::write(config_dir.join("authorized_keys"), "").expect("auth keys");
    fs::write(
        config_dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(
        &config_path,
        format!(
            r#"
format = "p2ptunnel-config-v3"

[node]
peer_id = "android-test"
role = "offer"

[peer]
remote_peer_id = "answer-office"

[paths]
identity = "{identity}"
authorized_keys = "{authorized_keys}"
state_dir = "{state_dir}"
log_dir = "{log_dir}"

[broker]
url = "mqtts://mqtt.example.com:8883"
client_id = "android-test"
topic_prefix = "p2ptunnel"
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
stun_urls = ["stun:stun.l.google.com:19302"]
enable_trickle_ice = true
enable_ice_restart = true

[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250

[[forwards]]
id = "llama"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 8080

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
stdout_logging = true
log_file = "{log_file}"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "{status_file}"
"#,
            identity = config_dir.join("missing_identity.toml").display(),
            authorized_keys = config_dir.join("authorized_keys").display(),
            state_dir = state_dir.display(),
            log_dir = state_dir.join("log").display(),
            ca_file = config_dir.join("ca.crt").display(),
            log_file = state_dir.join("log/p2ptunnel.log").display(),
            status_file = state_dir.join("status.json").display(),
        ),
    )
    .expect("config");

    let generated = generate_identity("android-test").expect("generate identity");
    let controller = AndroidTunnelController::new();
    let result = controller.start_offer_with_identity(
        &config_path.display().to_string(),
        &generated.identity.render_toml(),
    );

    let error = result.expect_err("start must fail rather than silently lose diagnostics");
    assert!(
        error.contains("failed to install Android tracing bridge"),
        "expected a tracing-bridge install error, got: {error}"
    );
    let status = controller.status();
    assert!(
        format!("{:?}", status.state).eq_ignore_ascii_case("error"),
        "expected Error state, got {:?}",
        status.state
    );
    assert!(
        status
            .last_error
            .as_deref()
            .is_some_and(|message| message.contains("failed to install Android tracing bridge")),
        "last_error should explain the tracing install failure, got {:?}",
        status.last_error
    );
}
