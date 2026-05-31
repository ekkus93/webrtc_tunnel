use std::fs;
use std::path::Path;

use p2p_core::{AppConfig, ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, ForwardTable};

// ── Fixtures ──────────────────────────────────────────────────────────────────

fn write_required_files(config_dir: &Path) {
    fs::write(config_dir.join("identity"), "peer_id = \"answer-office\"\n").expect("identity");
    fs::write(config_dir.join("authorized_keys"), "").expect("authorized keys");
    fs::write(config_dir.join("mqtt_password"), "secret").expect("password");
    fs::write(
        config_dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca cert");
}

fn sample_answer_config(config_dir: &Path, state_dir: &Path) -> String {
    format!(
        r#"format = "p2ptunnel-config-v3"

[node]
peer_id = "answer-office"
role = "answer"

[paths]
identity = "{identity}"
authorized_keys = "{authorized_keys}"
state_dir = "{state_dir}"
log_dir = "{log_dir}"

[broker]
url = "mqtts://mqtt.example.com:8883"
client_id = "answer-office"
topic_prefix = "p2ptunnel"
username = "answer-office"
password_file = "{password_file}"
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
id = "ssh"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]

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
        identity = config_dir.join("identity").display(),
        authorized_keys = config_dir.join("authorized_keys").display(),
        state_dir = state_dir.display(),
        log_dir = state_dir.join("log").display(),
        password_file = config_dir.join("mqtt_password").display(),
        ca_file = config_dir.join("ca.crt").display(),
        log_file = state_dir.join("log/p2ptunnel.log").display(),
        status_file = state_dir.join("status.json").display(),
    )
}

fn setup_dirs() -> (tempfile::TempDir, std::path::PathBuf, std::path::PathBuf) {
    let tmp = tempfile::tempdir().expect("temp dir");
    let config_dir = tmp.path().join("config");
    let state_dir = tmp.path().join("state");
    fs::create_dir_all(&config_dir).expect("config dir");
    fs::create_dir_all(state_dir.join("log")).expect("state log dir");
    write_required_files(&config_dir);
    (tmp, config_dir, state_dir)
}

fn write_and_load(
    tmp: &tempfile::TempDir,
    content: &str,
) -> Result<AppConfig, p2p_core::ConfigError> {
    let config_path = tmp.path().join("config.toml");
    fs::write(&config_path, content).expect("write config");
    AppConfig::load_from_file(&config_path)
}

// ── Phase 4.1: Unknown keys are rejected ─────────────────────────────────────

#[test]
fn unknown_top_level_key_is_rejected() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("[health]", "completely_unknown_key = \"oops\"\n\n[health]");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn unknown_security_key_is_rejected() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("replay_cache_size = 10000", "replay_cache_size = 10000\nnew_mystery_flag = true");
    assert!(write_and_load(&tmp, &config).is_err());
}

// ── Phase 4.2: Security toggle fail-closed tests ──────────────────────────────

#[test]
fn config_rejects_require_mqtt_tls_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("require_mqtt_tls = true", "require_mqtt_tls = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_require_message_encryption_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("require_message_encryption = true", "require_message_encryption = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_require_message_signatures_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("require_message_signatures = true", "require_message_signatures = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_require_authorized_keys_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("require_authorized_keys = true", "require_authorized_keys = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_reject_unknown_config_keys_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("reject_unknown_config_keys = true", "reject_unknown_config_keys = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_refuse_world_readable_identity_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("refuse_world_readable_identity = true", "refuse_world_readable_identity = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_refuse_world_writable_paths_disabled() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("refuse_world_writable_paths = true", "refuse_world_writable_paths = false");
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_zero_replay_cache_size() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("replay_cache_size = 10000", "replay_cache_size = 0");
    assert!(write_and_load(&tmp, &config).is_err());
}

// ── Phase 4.3: Broker URL / TLS validation ────────────────────────────────────

#[test]
fn config_rejects_non_tls_broker_url() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir).replace(
        "url = \"mqtts://mqtt.example.com:8883\"",
        "url = \"mqtt://mqtt.example.com:1883\"",
    );
    assert!(write_and_load(&tmp, &config).is_err());
}

#[test]
fn config_rejects_insecure_skip_verify() {
    let (tmp, config_dir, state_dir) = setup_dirs();
    let config = sample_answer_config(&config_dir, &state_dir)
        .replace("insecure_skip_verify = false", "insecure_skip_verify = true");
    assert!(write_and_load(&tmp, &config).is_err());
}

// ── Phase 4.4: ForwardTable::target_for authorization ────────────────────────

fn make_forward_table() -> ForwardTable {
    ForwardTable::new(&[ForwardRule {
        id: "ssh".to_owned(),
        offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port: 2223 }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: 22,
            allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
        }),
    }])
}

#[test]
fn forward_table_returns_target_for_authorized_peer() {
    let table = make_forward_table();
    let peer = "offer-home".parse().expect("peer id");
    let target = table.target_for("ssh", &peer).expect("authorized peer must get a target");
    assert_eq!(target.host, "127.0.0.1");
    assert_eq!(target.port, 22);
}

#[test]
fn forward_table_rejects_unknown_forward_id() {
    let table = make_forward_table();
    let peer = "offer-home".parse().expect("peer id");
    assert!(
        matches!(
            table.target_for("web-ui", &peer),
            Err(p2p_core::ForwardLookupError::UnknownForward)
        ),
        "unknown forward_id must return UnknownForward"
    );
}

#[test]
fn forward_table_rejects_unauthorized_peer() {
    let table = make_forward_table();
    let rogue = "rogue-peer".parse().expect("peer id");
    assert!(
        matches!(
            table.target_for("ssh", &rogue),
            Err(p2p_core::ForwardLookupError::ForbiddenForward)
        ),
        "unauthorized peer must return ForbiddenForward"
    );
}

#[test]
fn forward_table_isolates_authorization_per_forward_rule() {
    let table = ForwardTable::new(&[
        ForwardRule {
            id: "ssh".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 2223,
            }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 22,
                allow_remote_peers: vec!["alice".parse().expect("peer id")],
            }),
        },
        ForwardRule {
            id: "web".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 8080,
            }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 80,
                allow_remote_peers: vec!["bob".parse().expect("peer id")],
            }),
        },
    ]);

    let alice: p2p_core::PeerId = "alice".parse().expect("alice");
    let bob: p2p_core::PeerId = "bob".parse().expect("bob");

    // Alice can access ssh but not web
    assert!(table.target_for("ssh", &alice).is_ok());
    assert!(matches!(
        table.target_for("web", &alice),
        Err(p2p_core::ForwardLookupError::ForbiddenForward)
    ));

    // Bob can access web but not ssh
    assert!(table.target_for("web", &bob).is_ok());
    assert!(matches!(
        table.target_for("ssh", &bob),
        Err(p2p_core::ForwardLookupError::ForbiddenForward)
    ));
}
