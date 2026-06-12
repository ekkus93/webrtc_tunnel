
use std::fs;
use std::path::Path;

use super::{AppConfig, expand_home};

fn sample_config(config_dir: &Path, state_dir: &Path) -> String {
    format!(
        r#"
format = "p2ptunnel-config-v3"

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

fn offer_config(config_dir: &Path, state_dir: &Path) -> String {
    sample_config(config_dir, state_dir)
            .replace("peer_id = \"answer-office\"\nrole = \"answer\"", "peer_id = \"offer-home\"\nrole = \"offer\"")
            .replace("[paths]", "[peer]\nremote_peer_id = \"answer-office\"\n\n[paths]")
            .replace(
                "[forwards.answer]\ntarget_host = \"127.0.0.1\"\ntarget_port = 22\nallow_remote_peers = [\"offer-home\"]",
                "[forwards.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = 2223",
            )
}

fn append_answer_forward(config: String, id: &str, target_port: u16) -> String {
    config.replace(
            "[reconnect]",
            &format!(
                "[[forwards]]\nid = \"{id}\"\n\n[forwards.answer]\ntarget_host = \"127.0.0.1\"\ntarget_port = {target_port}\nallow_remote_peers = [\"offer-home\"]\n\n[reconnect]"
            ),
        )
}

fn append_offer_forward(config: String, id: &str, listen_port: u16) -> String {
    config.replace(
            "[reconnect]",
            &format!(
                "[[forwards]]\nid = \"{id}\"\n\n[forwards.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = {listen_port}\n\n[reconnect]"
            ),
        )
}

fn write_required_files(config_dir: &Path) {
    fs::write(config_dir.join("identity"), "peer_id = \"answer-office\"\n").expect("identity");
    fs::write(config_dir.join("authorized_keys"), "").expect("write auth keys");
    fs::write(config_dir.join("mqtt_password"), "secret\n").expect("password");
    fs::write(
        config_dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
}

fn render_documented_sample(sample: &str, config_dir: &Path, state_dir: &Path) -> String {
    sample
        .replace("__IDENTITY__", &config_dir.join("identity").display().to_string())
        .replace("__AUTHORIZED_KEYS__", &config_dir.join("authorized_keys").display().to_string())
        .replace("__STATE_DIR__", &state_dir.display().to_string())
        .replace("__LOG_DIR__", &state_dir.join("log").display().to_string())
        .replace("__CA_FILE__", &config_dir.join("ca.crt").display().to_string())
        .replace("__LOG_FILE__", &state_dir.join("log/p2ptunnel.log").display().to_string())
        .replace("__STATUS_FILE__", &state_dir.join("status.json").display().to_string())
}

#[test]
fn config_loads_and_parses() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    assert_eq!(config.paths.identity, config_dir.join("identity"));
}

#[test]
fn documented_sample_configs_parse_and_validate() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    for sample in [
        include_str!("../../../../docs/examples/offer-config.toml"),
        include_str!("../../../../docs/examples/answer-config.toml"),
    ] {
        let content = render_documented_sample(sample, &config_dir, &state_dir);
        let mut config: AppConfig = toml::from_str(&content).expect("sample should parse");
        config.expand_paths().expect("sample paths should expand");
        config.validate().expect("sample should validate");
    }
}

#[test]
fn config_loads_when_runtime_dirs_are_missing() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    assert!(!config.paths.state_dir.exists());
    assert!(!config.paths.log_dir.exists());
}

#[test]
fn ensure_runtime_dirs_creates_missing_directories() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    write_required_files(&config_dir);

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

    let config = AppConfig::load_from_file(&config_path).expect("config should load");
    config.ensure_runtime_dirs().expect("runtime dirs should be created");

    assert!(config.paths.state_dir.is_dir());
    assert!(config.paths.log_dir.is_dir());
    assert!(config.logging.log_file.parent().expect("log parent").is_dir());
    assert!(config.health.status_file.parent().expect("status parent").is_dir());
}

#[test]
fn config_rejects_empty_answer_allowlist() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("allow_remote_peers = [\"offer-home\"]", "allow_remote_peers = []");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_accepts_answer_config_with_two_forwards() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_answer_forward(sample_config(&config_dir, &state_dir), "web-ui", 8080);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");

    let loaded = AppConfig::load_from_file(&config_path).expect("config should load");
    assert_eq!(loaded.forwards.len(), 2);
}

#[test]
fn config_accepts_offer_config_with_two_forwards() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_offer_forward(offer_config(&config_dir, &state_dir), "web-ui", 8080);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");

    let loaded = AppConfig::load_from_file(&config_path).expect("config should load");
    assert_eq!(loaded.forwards.len(), 2);
}

#[test]
fn config_rejects_duplicate_forward_ids() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_answer_forward(sample_config(&config_dir, &state_dir), "ssh", 8080);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_duplicate_offer_listen_sockets() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = append_offer_forward(offer_config(&config_dir, &state_dir), "web-ui", 2223);
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_invalid_forward_ids() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for invalid in ["", "bad/id", "bad:id", "bad id", "bad\\id"] {
        let config = sample_config(&config_dir, &state_dir)
            .replace("id = \"ssh\"", &format!("id = \"{invalid}\""));
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err(), "{invalid}");
    }
}

#[test]
fn config_rejects_missing_role_specific_forward_ports() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for config in [
        offer_config(&config_dir, &state_dir).replace("listen_port = 2223\n", ""),
        sample_config(&config_dir, &state_dir).replace("target_port = 22\n", ""),
    ] {
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }
}

#[test]
fn old_single_forward_config_is_rejected() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir).replace(
            "[tunnel]\nread_chunk_size = 16384",
            "[tunnel]\nread_chunk_size = 16384\n\n[tunnel.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = 2223\nremote_peer_id = \"answer-office\"",
        );
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_unsupported_session_expiry() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("session_expiry_secs = 0", "session_expiry_secs = 60");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_partial_broker_client_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("client_key_file = \"\"", "client_key_file = \"/tmp/client.key\"");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_unsupported_connect_timeout() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("connect_timeout_secs = 5", "connect_timeout_secs = 10");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn expand_home_uses_current_home_directory() {
    let home = std::env::var_os("HOME").expect("HOME should be set for tests");
    let expanded = expand_home(Path::new("~/example")).expect("path should expand");
    assert_eq!(expanded, std::path::PathBuf::from(home).join("example"));
}

#[test]
fn config_allows_anonymous_broker_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("username = \"answer-office\"", "username = \"\"")
        .replace(
            &format!("password_file = \"{}\"", config_dir.join("mqtt_password").display()),
            "password_file = \"\"",
        );
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).expect("anonymous config");
}

#[test]
fn config_allows_username_only_broker_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir).replace(
        &format!("password_file = \"{}\"", config_dir.join("mqtt_password").display()),
        "password_file = \"\"",
    );
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).expect("username-only config");
}

#[test]
fn config_allows_mqtts_without_explicit_ca_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace(&format!("ca_file = \"{}\"", config_dir.join("ca.crt").display()), "");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).expect("default-root TLS config");
}

#[test]
fn load_with_identity_override_does_not_require_identity_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);
    fs::remove_file(config_dir.join("identity")).expect("remove identity");

    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, offer_config(&config_dir, &state_dir)).expect("write config");

    assert!(AppConfig::load_from_file(&config_path).is_err());
    AppConfig::load_from_file_with_identity_override(&config_path)
        .expect("identity override should allow loading without paths.identity");
}

#[test]
fn config_rejects_password_without_username() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    let config = sample_config(&config_dir, &state_dir)
        .replace("username = \"answer-office\"", "username = \"\"");
    let config_path = temp_dir.path().join("config.toml");
    fs::write(&config_path, config).expect("write config");
    assert!(AppConfig::load_from_file(&config_path).is_err());
}

#[test]
fn config_rejects_dead_knobs() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for (from, to) in [
        ("log_rotation = \"none\"", "log_rotation = \"daily\""),
        ("status_socket = \"\"", "status_socket = \"/tmp/p2ptunnel.sock\""),
        ("hold_local_client_during_reconnect = false", "hold_local_client_during_reconnect = true"),
        ("local_client_hold_secs = 0", "local_client_hold_secs = 5"),
    ] {
        let config = sample_config(&config_dir, &state_dir).replace(from, to);
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err(), "{to}");
    }
}

#[test]
fn config_rejects_removed_v1_knobs() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_dir = temp_dir.path().join("config");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(&config_dir);

    for (anchor, extra) in [
        ("stun_urls = [\"stun:stun.l.google.com:19302\"]", "\nice_gather_timeout_secs = 15"),
        ("enable_trickle_ice = true", "\nice_connection_timeout_secs = 20"),
        ("enable_ice_restart = true", "\nmax_message_size = 262144"),
        ("remote_eof_grace_ms = 250", "\nframe_version = 1"),
        ("read_chunk_size = 16384", "\nwrite_buffer_limit = 262144"),
        ("target_port = 22", "\nauto_open = true"),
        ("[health]", "\nheartbeat_interval_secs = 10\nping_timeout_secs = 30"),
    ] {
        let config =
            sample_config(&config_dir, &state_dir).replace(anchor, &format!("{anchor}{extra}"));
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err(), "{extra}");
    }
}

#[test]
fn missing_config_file_error_names_path() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let path = temp_dir.path().join("missing-config.toml");

    let error = AppConfig::load_from_file(&path).expect_err("config load should fail");

    assert!(error.to_string().contains(path.to_string_lossy().as_ref()));
}
