//! Shared fixtures for the config test suite: sample TOML renderers, required-file setup,
//! and the load helpers the grouped test modules build on.

use std::fs;
use std::path::Path;

pub(super) use crate::config::{AndroidIceMode, AppConfig, expand_home};

pub(super) fn sample_config(config_dir: &Path, state_dir: &Path) -> String {
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

pub(super) fn offer_config(config_dir: &Path, state_dir: &Path) -> String {
    sample_config(config_dir, state_dir)
            .replace("peer_id = \"answer-office\"\nrole = \"answer\"", "peer_id = \"offer-home\"\nrole = \"offer\"")
            .replace("[paths]", "[peer]\nremote_peer_id = \"answer-office\"\n\n[paths]")
            .replace(
                "[forwards.answer]\ntarget_host = \"127.0.0.1\"\ntarget_port = 22\nallow_remote_peers = [\"offer-home\"]",
                "[forwards.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = 2223",
            )
}

pub(super) fn append_answer_forward(config: String, id: &str, target_port: u16) -> String {
    config.replace(
            "[reconnect]",
            &format!(
                "[[forwards]]\nid = \"{id}\"\n\n[forwards.answer]\ntarget_host = \"127.0.0.1\"\ntarget_port = {target_port}\nallow_remote_peers = [\"offer-home\"]\n\n[reconnect]"
            ),
        )
}

pub(super) fn append_offer_forward(config: String, id: &str, listen_port: u16) -> String {
    config.replace(
            "[reconnect]",
            &format!(
                "[[forwards]]\nid = \"{id}\"\n\n[forwards.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = {listen_port}\n\n[reconnect]"
            ),
        )
}

pub(super) fn write_required_files(config_dir: &Path) {
    fs::write(config_dir.join("identity"), "peer_id = \"answer-office\"\n").expect("identity");
    fs::write(config_dir.join("authorized_keys"), "").expect("write auth keys");
    fs::write(config_dir.join("mqtt_password"), "secret\n").expect("password");
    fs::write(
        config_dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
}

pub(super) fn render_documented_sample(
    sample: &str,
    config_dir: &Path,
    state_dir: &Path,
) -> String {
    sample
        .replace("__IDENTITY__", &config_dir.join("identity").display().to_string())
        .replace("__AUTHORIZED_KEYS__", &config_dir.join("authorized_keys").display().to_string())
        .replace("__STATE_DIR__", &state_dir.display().to_string())
        .replace("__LOG_DIR__", &state_dir.join("log").display().to_string())
        .replace("__CA_FILE__", &config_dir.join("ca.crt").display().to_string())
        .replace("__LOG_FILE__", &state_dir.join("log/p2ptunnel.log").display().to_string())
        .replace("__STATUS_FILE__", &state_dir.join("status.json").display().to_string())
}

/// Write required files + load `config`, returning the parse/validate result.
pub(super) fn load_config(
    config: &str,
    config_dir: &Path,
    state_dir: &Path,
) -> Result<AppConfig, String> {
    fs::create_dir_all(config_dir).expect("create config dir");
    fs::create_dir_all(state_dir.join("log")).expect("create state dir");
    write_required_files(config_dir);
    let config_path = config_dir.join("config.toml");
    fs::write(&config_path, config).expect("write config");
    AppConfig::load_from_file(&config_path).map_err(|error| error.to_string())
}
