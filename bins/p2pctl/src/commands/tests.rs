#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use p2p_core::SessionId;
use p2p_core::{DaemonState, NodeRole};
use p2p_crypto::{generate_identity, kid_from_signing_key};
use p2p_daemon::{DaemonStatus, SessionStatus};

use super::{
    append_authorized_key, check_config, fingerprint, render_fingerprint, render_status,
    resolve_config_path, write_identity_files,
};

#[test]
fn keygen_refuses_to_overwrite_without_force() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let generated = generate_identity("offer-home").expect("identity");
    write_identity_files(temp_dir.path(), &generated, false).expect("first write");

    let error = write_identity_files(temp_dir.path(), &generated, false).expect_err("refuse");
    assert!(error.to_string().contains("use --force"));
}

#[test]
fn keygen_force_replaces_existing_files() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let first = generate_identity("offer-home").expect("identity");
    let second = generate_identity("offer-home").expect("identity");
    write_identity_files(temp_dir.path(), &first, false).expect("first write");

    let (identity_path, identity_pub_path, replaced) =
        write_identity_files(temp_dir.path(), &second, true).expect("force write");
    assert!(replaced);
    assert!(
        std::fs::read_to_string(identity_path).expect("identity content").contains("offer-home")
    );
    assert!(
        std::fs::read_to_string(identity_pub_path)
            .expect("public identity content")
            .contains("offer-home")
    );
}

fn sample_status(sessions: Vec<SessionStatus>, session_capacity: usize) -> DaemonStatus {
    DaemonStatus::with_sessions(
        "answer-office".parse().expect("peer id"),
        NodeRole::Answer,
        true,
        DaemonState::Serving,
        vec!["ssh".to_owned()],
        session_capacity,
        sessions,
    )
}

#[test]
fn status_rendering_handles_zero_sessions() {
    let output = render_status(&sample_status(Vec::new(), 16));

    assert!(output.contains("peer_id=answer-office role=answer mqtt_connected=true state=serving"));
    assert!(output.contains("sessions=0/16"));
    assert!(output.contains("sessions: none"));
}

#[test]
fn status_rendering_handles_one_session() {
    let session = SessionStatus::new(
        SessionId::new([0xaa; 16]),
        "offer-home".parse().expect("peer id"),
        DaemonState::TunnelOpen,
        true,
        vec!["ssh".to_owned(), "web-ui".to_owned()],
    );
    let output = render_status(&sample_status(vec![session], 16));

    assert!(output.contains("state=serving"));
    assert!(output.contains("sessions=1/16"));
    assert!(output.contains("peer=offer-home"));
    assert!(output.contains("state=tunnel_open"));
    assert!(output.contains("data_channel_open=true"));
    assert!(output.contains("configured_forwards=ssh,web-ui"));
}

#[test]
fn status_rendering_handles_multiple_sessions() {
    let sessions = vec![
        SessionStatus::new(
            SessionId::new([0xbb; 16]),
            "offer-desktop".parse().expect("peer id"),
            DaemonState::TunnelOpen,
            true,
            vec!["web-ui".to_owned()],
        ),
        SessionStatus::new(
            SessionId::new([0xaa; 16]),
            "offer-home".parse().expect("peer id"),
            DaemonState::ConnectingDataChannel,
            false,
            vec!["ssh".to_owned()],
        ),
    ];
    let output = render_status(&sample_status(sessions, 16));

    assert!(output.contains("sessions=2/16"));
    assert!(output.contains("peer=offer-desktop"));
    assert!(output.contains("peer=offer-home"));
    assert!(output.contains("configured_forwards=web-ui"));
    assert!(output.contains("configured_forwards=ssh"));
}

#[test]
fn status_rendering_handles_session_missing_configured_forwards() {
    let session = SessionStatus::new(
        SessionId::new([0xaa; 16]),
        "offer-home".parse().expect("peer id"),
        DaemonState::TunnelOpen,
        true,
        Vec::new(),
    );
    let output = render_status(&sample_status(vec![session], 16));

    assert!(output.contains("peer=offer-home"));
    assert!(output.contains("configured_forwards=\n"));
}

#[test]
fn status_parse_fails_clearly_for_malformed_json() {
    let error = serde_json::from_str::<DaemonStatus>("not json").expect_err("malformed json");
    assert!(!error.to_string().is_empty());
}

#[test]
fn status_parse_fails_clearly_for_a_missing_required_field() {
    // `session_capacity` is a required field on the real schema; a status file
    // missing it (e.g. written by a stale/mismatched daemon version) must be a
    // parse error, not silently rendered as "unknown".
    let json = serde_json::json!({
        "peer_id": "answer-office",
        "role": "answer",
        "mqtt_connected": true,
        "active_session_id": null,
        "current_state": "serving",
        "active_session_count": 0,
        "sessions": [],
        "configured_forwards": [],
        "forwards": []
    });

    let error = serde_json::from_str::<DaemonStatus>(&json.to_string())
        .expect_err("missing required field must fail, not default");
    assert!(error.to_string().contains("session_capacity"), "got: {error}");
}

#[test]
fn add_authorized_key_creates_missing_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let authorized_keys_path = temp_dir.path().join("authorized_keys");
    let identity = generate_identity("offer-home").expect("identity");

    append_authorized_key(&authorized_keys_path, &identity.public_identity)
        .expect("append to missing file");

    let content = std::fs::read_to_string(&authorized_keys_path).expect("read file");
    assert!(content.contains("offer-home"));
    assert_eq!(content.lines().count(), 1);
}

#[test]
fn add_authorized_key_appends_to_existing_file_leaving_prior_entries_untouched() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let authorized_keys_path = temp_dir.path().join("authorized_keys");
    let first = generate_identity("offer-home").expect("identity");
    let second = generate_identity("offer-office").expect("identity");

    append_authorized_key(&authorized_keys_path, &first.public_identity).expect("append first");
    append_authorized_key(&authorized_keys_path, &second.public_identity).expect("append second");

    let content = std::fs::read_to_string(&authorized_keys_path).expect("read file");
    assert_eq!(content.lines().count(), 2);
    assert!(content.contains("offer-home"));
    assert!(content.contains("offer-office"));
}

#[test]
fn add_authorized_key_rejects_duplicate_peer_id_without_modifying_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let authorized_keys_path = temp_dir.path().join("authorized_keys");
    let first = generate_identity("offer-home").expect("identity");
    // A second identity for the *same* peer_id but with different keys, so we're
    // exercising peer_id collision specifically, not an exact-duplicate-line case.
    let second = generate_identity("offer-home").expect("identity");

    append_authorized_key(&authorized_keys_path, &first.public_identity).expect("append first");
    let before = std::fs::read_to_string(&authorized_keys_path).expect("read file");

    let error = append_authorized_key(&authorized_keys_path, &second.public_identity)
        .expect_err("duplicate peer_id should be rejected");
    assert!(error.to_string().contains("already exists"));
    assert!(error.to_string().contains("offer-home"));

    let after = std::fs::read_to_string(&authorized_keys_path).expect("read file");
    assert_eq!(before, after, "rejected append must not modify the file");
    assert_eq!(after.lines().count(), 1);
}

#[test]
fn add_authorized_key_rejects_malformed_existing_file_without_modifying_it() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let authorized_keys_path = temp_dir.path().join("authorized_keys");
    std::fs::write(&authorized_keys_path, "not a valid authorized_keys line\n")
        .expect("write malformed file");
    let identity = generate_identity("offer-home").expect("identity");

    let before = std::fs::read_to_string(&authorized_keys_path).expect("read file");
    let error = append_authorized_key(&authorized_keys_path, &identity.public_identity)
        .expect_err("malformed existing file should be rejected");
    assert!(!error.to_string().is_empty());

    let after = std::fs::read_to_string(&authorized_keys_path).expect("read file");
    assert_eq!(before, after, "rejected append must not modify the file");
}

#[test]
fn render_fingerprint_produces_expected_format() {
    let identity = generate_identity("offer-home").expect("identity");
    let output = render_fingerprint(&identity.public_identity);
    let expected_kid = kid_from_signing_key(&identity.public_identity.sign_public);
    assert_eq!(output, format!("peer_id=offer-home\nfingerprint={expected_kid}\n"));
}

#[test]
fn fingerprint_fails_clearly_for_a_missing_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let missing = temp_dir.path().join("does-not-exist.pub");

    assert!(fingerprint(&missing).is_err());
}

/// A minimal but fully valid on-disk config + identity, mirroring the fixture shape
/// used by `crates/p2p-core/tests/config_parsing.rs` (not importable across crates,
/// so reproduced here at the minimum size `check_config` actually exercises).
fn write_valid_config_fixture(
    dir: &std::path::Path,
    authorize_remote_peer: bool,
) -> std::path::PathBuf {
    std::fs::create_dir_all(dir.join("state/log")).expect("state dir");
    let generated = generate_identity("answer-office").expect("identity");
    let identity_path = dir.join("identity");
    std::fs::write(&identity_path, generated.identity.render_toml()).expect("identity");
    #[cfg(unix)]
    std::fs::set_permissions(&identity_path, std::fs::Permissions::from_mode(0o600))
        .expect("identity perms");
    let authorized_keys_content = if authorize_remote_peer {
        let offer_home = generate_identity("offer-home").expect("identity");
        format!("{}\n", offer_home.public_identity.render())
    } else {
        String::new()
    };
    std::fs::write(dir.join("authorized_keys"), authorized_keys_content).expect("authorized_keys");
    std::fs::write(dir.join("mqtt_password"), "secret").expect("password");
    std::fs::write(
        dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca cert");

    let config_path = dir.join("config.toml");
    let config = format!(
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
        identity = identity_path.display(),
        authorized_keys = dir.join("authorized_keys").display(),
        state_dir = dir.join("state").display(),
        log_dir = dir.join("state/log").display(),
        password_file = dir.join("mqtt_password").display(),
        ca_file = dir.join("ca.crt").display(),
        log_file = dir.join("state/log/p2ptunnel.log").display(),
        status_file = dir.join("state/status.json").display(),
    );
    std::fs::write(&config_path, config).expect("write config");
    config_path
}

#[test]
fn check_config_succeeds_for_a_valid_config() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_path = write_valid_config_fixture(temp_dir.path(), true);

    check_config(Some(&config_path)).expect("valid config should check out");
}

#[test]
fn check_config_fails_when_answer_role_is_missing_an_allowed_peer_key() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_path = write_valid_config_fixture(temp_dir.path(), false);

    let error = check_config(Some(&config_path))
        .expect_err("missing allowed peer's authorized key must fail check-config");
    assert!(error.to_string().contains("offer-home"), "got: {error}");
}

#[test]
fn check_config_fails_when_offer_role_is_missing_the_remote_peers_key() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_path = write_valid_offer_config_fixture(temp_dir.path(), false);

    let error = check_config(Some(&config_path))
        .expect_err("missing remote peer's authorized key must fail check-config");
    assert!(error.to_string().contains("answer-office"), "got: {error}");
}

#[test]
fn check_config_succeeds_for_a_valid_offer_config() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let config_path = write_valid_offer_config_fixture(temp_dir.path(), true);

    check_config(Some(&config_path)).expect("valid offer config should check out");
}

/// Mirrors [`write_valid_config_fixture`] but for the offer role, so the offer-side
/// `[peer].remote_peer_id` authorization requirement can be tested independently of
/// the answer role's `allow_remote_peers`.
fn write_valid_offer_config_fixture(
    dir: &std::path::Path,
    authorize_remote_peer: bool,
) -> std::path::PathBuf {
    std::fs::create_dir_all(dir.join("state/log")).expect("state dir");
    let generated = generate_identity("offer-home").expect("identity");
    let identity_path = dir.join("identity");
    std::fs::write(&identity_path, generated.identity.render_toml()).expect("identity");
    #[cfg(unix)]
    std::fs::set_permissions(&identity_path, std::fs::Permissions::from_mode(0o600))
        .expect("identity perms");
    let authorized_keys_content = if authorize_remote_peer {
        let answer_office = generate_identity("answer-office").expect("identity");
        format!("{}\n", answer_office.public_identity.render())
    } else {
        String::new()
    };
    std::fs::write(dir.join("authorized_keys"), authorized_keys_content).expect("authorized_keys");
    std::fs::write(dir.join("mqtt_password"), "secret").expect("password");
    std::fs::write(
        dir.join("ca.crt"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca cert");

    let config_path = dir.join("config.toml");
    let config = format!(
        r#"format = "p2ptunnel-config-v3"

[node]
peer_id = "offer-home"
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
client_id = "offer-home"
topic_prefix = "p2ptunnel"
username = "offer-home"
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

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 2222

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
        identity = identity_path.display(),
        authorized_keys = dir.join("authorized_keys").display(),
        state_dir = dir.join("state").display(),
        log_dir = dir.join("state/log").display(),
        password_file = dir.join("mqtt_password").display(),
        ca_file = dir.join("ca.crt").display(),
        log_file = dir.join("state/log/p2ptunnel.log").display(),
        status_file = dir.join("state/status.json").display(),
    );
    std::fs::write(&config_path, config).expect("write config");
    config_path
}

#[test]
fn check_config_fails_clearly_for_a_missing_config_file() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    let missing = temp_dir.path().join("does-not-exist.toml");

    assert!(check_config(Some(&missing)).is_err());
}

#[test]
fn explicit_config_path_is_used_as_is() {
    let explicit = Path::new("/etc/p2ptunnel/config.toml");
    let resolved = resolve_config_path(Some(explicit), None).expect("explicit path resolves");
    assert_eq!(resolved, explicit);
}

#[test]
fn missing_config_flag_falls_back_to_home_config_dir() {
    let home = PathBuf::from("/home/ctl-user");
    let resolved = resolve_config_path(None, Some(home.clone())).expect("home fallback resolves");
    assert_eq!(resolved, home.join(".config/p2ptunnel/config.toml"));
}

#[test]
fn missing_config_flag_without_home_yields_a_normal_error_not_a_panic() {
    let error = resolve_config_path(None, None).expect_err("missing HOME must error, not panic");
    assert!(error.to_string().contains("HOME is not set"));
}
