use std::fs::{self, OpenOptions};
#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use clap::{Parser, Subcommand};
use p2p_core::AppConfig;
use p2p_crypto::{
    AuthorizedKeys, IdentityFile, PublicIdentity, generate_identity, kid_from_signing_key,
};
use tracing_subscriber::EnvFilter;

#[derive(Debug, Parser)]
#[command(name = "p2pctl")]
#[command(about = "Manage p2ptunnel identities and configuration")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    Keygen {
        peer_id: String,
        #[arg(long)]
        force: bool,
    },
    Fingerprint {
        identity_pub: PathBuf,
    },
    AddAuthorizedKey {
        identity_pub: PathBuf,
    },
    CheckConfig {
        #[arg(long)]
        config: Option<PathBuf>,
    },
    Status {
        #[arg(long)]
        config: Option<PathBuf>,
    },
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt().with_env_filter(EnvFilter::from_default_env()).without_time().init();

    match Cli::parse().command {
        Command::Keygen { peer_id, force } => keygen(&peer_id, force)?,
        Command::Fingerprint { identity_pub } => fingerprint(&identity_pub)?,
        Command::AddAuthorizedKey { identity_pub } => add_authorized_key(&identity_pub)?,
        Command::CheckConfig { config } => check_config(config.as_deref())?,
        Command::Status { config } => status(config.as_deref())?,
    }

    Ok(())
}

fn keygen(peer_id: &str, force: bool) -> Result<(), Box<dyn std::error::Error>> {
    let generated = generate_identity(peer_id)?;
    let config_dir = default_config_dir()?;
    fs::create_dir_all(&config_dir)?;

    let (identity_path, identity_pub_path, replaced) =
        write_identity_files(&config_dir, &generated, force)?;
    let action = if replaced { "replaced" } else { "wrote" };
    println!("{action} {}", identity_path.display());
    println!("{action} {}", identity_pub_path.display());
    Ok(())
}

fn write_identity_files(
    config_dir: &Path,
    generated: &p2p_crypto::GeneratedIdentity,
    force: bool,
) -> Result<(PathBuf, PathBuf, bool), Box<dyn std::error::Error>> {
    let identity_path = config_dir.join("identity");
    let identity_pub_path = config_dir.join("identity.pub");
    let identity_exists = identity_path.exists();
    let identity_pub_exists = identity_pub_path.exists();
    if (identity_exists || identity_pub_exists) && !force {
        return Err(format!(
            "refusing to overwrite existing identity files in {} (use --force to replace them)",
            config_dir.display()
        )
        .into());
    }

    fs::write(&identity_path, generated.identity.render_toml())?;
    #[cfg(unix)]
    fs::set_permissions(&identity_path, fs::Permissions::from_mode(0o600))?;
    fs::write(&identity_pub_path, format!("{}\n", generated.public_identity.render()))?;
    Ok((identity_path, identity_pub_path, identity_exists || identity_pub_exists))
}

fn fingerprint(path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let public_identity = load_public_identity(path)?;
    print!("{}", render_fingerprint(&public_identity));
    Ok(())
}

fn render_fingerprint(public_identity: &PublicIdentity) -> String {
    let kid = kid_from_signing_key(&public_identity.sign_public);
    format!("peer_id={}\nfingerprint={kid}\n", public_identity.peer_id)
}

fn add_authorized_key(path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let public_identity = load_public_identity(path)?;
    let authorized_keys_path = default_config_dir()?.join("authorized_keys");
    append_authorized_key(&authorized_keys_path, &public_identity)?;
    println!("updated {}", authorized_keys_path.display());
    Ok(())
}

/// Core of [`add_authorized_key`], parameterized on the authorized_keys path so it's
/// testable without touching the real `$HOME`. Rejects (without writing anything) if
/// `public_identity`'s peer_id is already present in the file; otherwise appends one
/// line, creating the file if it doesn't exist yet.
fn append_authorized_key(
    authorized_keys_path: &Path,
    public_identity: &PublicIdentity,
) -> Result<(), Box<dyn std::error::Error>> {
    if authorized_keys_path.exists() {
        let existing = AuthorizedKeys::from_file(authorized_keys_path)?;
        if existing.get_by_peer_id(&public_identity.peer_id).is_some() {
            return Err(format!(
                "peer '{}' already exists in {}",
                public_identity.peer_id,
                authorized_keys_path.display()
            )
            .into());
        }
    }

    let mut file = OpenOptions::new().create(true).append(true).open(authorized_keys_path)?;
    use std::io::Write;
    writeln!(file, "{}", public_identity.render())?;
    Ok(())
}

fn check_config(path: Option<&Path>) -> Result<(), Box<dyn std::error::Error>> {
    let config = load_config(path)?;
    let identity = IdentityFile::from_file(&config.paths.identity)?;
    config.validate_identity_peer(&identity.peer_id)?;
    println!("config ok for peer_id={}", config.node.peer_id);
    Ok(())
}

fn status(path: Option<&Path>) -> Result<(), Box<dyn std::error::Error>> {
    let config = load_config(path)?;
    let status_file = &config.health.status_file;
    if !status_file.exists() {
        return Err(format!("status file '{}' does not exist", status_file.display()).into());
    }

    let content = fs::read_to_string(status_file)?;
    let status: serde_json::Value = serde_json::from_str(&content)?;
    print!("{}", render_status(&status));
    Ok(())
}

fn render_status(status: &serde_json::Value) -> String {
    let mut output = String::new();
    output.push_str(&format!(
        "peer_id={} role={} mqtt_connected={} state={}\n",
        status["peer_id"].as_str().unwrap_or("unknown"),
        status["role"].as_str().unwrap_or("unknown"),
        status["mqtt_connected"].as_bool().unwrap_or(false),
        status["current_state"].as_str().unwrap_or("unknown")
    ));
    if let Some(count) = status["active_session_count"].as_u64() {
        let capacity = status["session_capacity"]
            .as_u64()
            .map(|value| value.to_string())
            .unwrap_or_else(|| "unknown".to_owned());
        output.push_str(&format!("sessions={count}/{capacity}\n"));
    }
    match status["sessions"].as_array() {
        Some(sessions) if sessions.is_empty() => output.push_str("sessions: none\n"),
        None => output.push_str("sessions: none\n"),
        Some(sessions) => {
            output.push_str("sessions:\n");
            for session in sessions {
                let forwards = session["configured_forward_ids"]
                    .as_array()
                    .map(|values| {
                        values
                            .iter()
                            .filter_map(serde_json::Value::as_str)
                            .collect::<Vec<_>>()
                            .join(",")
                    })
                    .unwrap_or_default();
                output.push_str(&format!(
                    "  {} peer={} state={} data_channel_open={} configured_forwards={}\n",
                    session["session_id"].as_str().unwrap_or("unknown"),
                    session["remote_peer_id"].as_str().unwrap_or("unknown"),
                    session["state"].as_str().unwrap_or("unknown"),
                    session["data_channel_open"].as_bool().unwrap_or(false),
                    forwards
                ));
            }
        }
    }
    output
}

fn load_config(path: Option<&Path>) -> Result<AppConfig, Box<dyn std::error::Error>> {
    let path = path
        .map(Path::to_path_buf)
        .unwrap_or_else(|| default_config_dir().expect("default config dir").join("config.toml"));
    Ok(AppConfig::load_from_file(&path)?)
}

fn load_public_identity(path: &Path) -> Result<PublicIdentity, Box<dyn std::error::Error>> {
    let content = fs::read_to_string(path)?;
    Ok(PublicIdentity::parse(content.trim())?)
}

fn default_config_dir() -> Result<PathBuf, Box<dyn std::error::Error>> {
    let home = std::env::var_os("HOME").ok_or("HOME is not set")?;
    Ok(PathBuf::from(home).join(".config/p2ptunnel"))
}

#[cfg(test)]
mod tests {
    #[cfg(unix)]
    use std::os::unix::fs::PermissionsExt;

    use p2p_crypto::{generate_identity, kid_from_signing_key};
    use serde_json::json;

    use super::{
        append_authorized_key, check_config, fingerprint, render_fingerprint, render_status,
        write_identity_files,
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
            std::fs::read_to_string(identity_path)
                .expect("identity content")
                .contains("offer-home")
        );
        assert!(
            std::fs::read_to_string(identity_pub_path)
                .expect("public identity content")
                .contains("offer-home")
        );
    }

    #[test]
    fn status_rendering_handles_zero_sessions() {
        let output = render_status(&json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_count": 0,
            "session_capacity": 16,
            "sessions": [],
            "configured_forwards": ["ssh"]
        }));

        assert!(
            output.contains("peer_id=answer-office role=answer mqtt_connected=true state=serving")
        );
        assert!(output.contains("sessions=0/16"));
        assert!(output.contains("sessions: none"));
        assert!(!output.contains("active_stream_count"));
        assert!(!output.contains("open_forward_ids"));
    }

    #[test]
    fn status_rendering_handles_one_session() {
        let output = render_status(&json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_count": 1,
            "session_capacity": 16,
            "sessions": [{
                "session_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "remote_peer_id": "offer-home",
                "state": "tunnel_open",
                "data_channel_open": true,
                "configured_forward_ids": ["ssh", "web-ui"]
            }]
        }));

        assert!(output.contains("state=serving"));
        assert!(output.contains("sessions=1/16"));
        assert!(output.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assert!(output.contains("peer=offer-home"));
        assert!(output.contains("state=tunnel_open"));
        assert!(output.contains("data_channel_open=true"));
        assert!(output.contains("configured_forwards=ssh,web-ui"));
    }

    #[test]
    fn status_rendering_handles_multiple_sessions() {
        let output = render_status(&json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_count": 2,
            "session_capacity": 16,
            "sessions": [
                {
                    "session_id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "remote_peer_id": "offer-desktop",
                    "state": "tunnel_open",
                    "data_channel_open": true,
                    "configured_forward_ids": ["web-ui"]
                },
                {
                    "session_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "remote_peer_id": "offer-home",
                    "state": "connecting_data_channel",
                    "data_channel_open": false,
                    "configured_forward_ids": ["ssh"]
                }
            ]
        }));

        assert!(output.contains("sessions=2/16"));
        assert!(output.contains("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb peer=offer-desktop"));
        assert!(output.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa peer=offer-home"));
        assert!(output.contains("configured_forwards=web-ui"));
        assert!(output.contains("configured_forwards=ssh"));
        assert!(!output.contains("active_stream_count"));
        assert!(!output.contains("open_forward_ids"));
    }

    #[test]
    fn status_rendering_handles_missing_top_level_fields() {
        let output = render_status(&json!({}));

        assert_eq!(
            output,
            "peer_id=unknown role=unknown mqtt_connected=false state=unknown\nsessions: none\n"
        );
        assert!(!output.contains("active_stream_count"));
        assert!(!output.contains("open_forward_ids"));
    }

    #[test]
    fn status_rendering_handles_each_missing_current_field() {
        let base = json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_count": 0,
            "session_capacity": 16,
            "sessions": []
        });

        for key in [
            "peer_id",
            "role",
            "mqtt_connected",
            "current_state",
            "active_session_count",
            "session_capacity",
            "sessions",
        ] {
            let mut fixture = base.clone();
            fixture.as_object_mut().expect("fixture object").remove(key);

            let output = render_status(&fixture);

            assert!(output.starts_with("peer_id="), "{key}: output remains human-readable");
            assert!(output.contains("role="), "{key}: role field remains rendered");
            assert!(output.contains("state="), "{key}: state field remains rendered");
            assert!(output.contains("sessions:"), "{key}: sessions section remains rendered");
            assert!(!output.contains("active_stream_count"), "{key}: removed fields not invented");
            assert!(!output.contains("open_forward_ids"), "{key}: removed fields not invented");
        }
    }

    #[test]
    fn status_rendering_handles_non_array_sessions() {
        let output = render_status(&json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_count": 1,
            "session_capacity": 16,
            "sessions": {"unexpected": "object"}
        }));

        assert!(output.contains("sessions=1/16"));
        assert!(output.contains("sessions: none"));
    }

    #[test]
    fn status_rendering_handles_session_missing_configured_forwards() {
        let output = render_status(&json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_count": 1,
            "session_capacity": 16,
            "sessions": [{
                "session_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "remote_peer_id": "offer-home",
                "state": "tunnel_open",
                "data_channel_open": true
            }]
        }));

        assert!(output.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa peer=offer-home"));
        assert!(output.contains("configured_forwards=\n"));
        assert!(!output.contains("open_forward_ids"));
    }

    #[test]
    fn status_rendering_handles_old_status_without_session_capacity() {
        let output = render_status(&json!({
            "peer_id": "answer-office",
            "role": "answer",
            "mqtt_connected": true,
            "current_state": "serving",
            "active_session_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "active_session_count": 1,
            "sessions": [{
                "session_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "remote_peer_id": "offer-home",
                "state": "tunnel_open",
                "data_channel_open": true,
                "configured_forward_ids": ["ssh"]
            }]
        }));

        assert!(output.contains("sessions=1/unknown"));
        assert!(output.contains("configured_forwards=ssh"));
        assert!(!output.contains("active_stream_count"));
        assert!(!output.contains("open_forward_ids"));
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
        append_authorized_key(&authorized_keys_path, &second.public_identity)
            .expect("append second");

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
    fn write_valid_config_fixture(dir: &std::path::Path) -> std::path::PathBuf {
        std::fs::create_dir_all(dir.join("state/log")).expect("state dir");
        let generated = generate_identity("answer-office").expect("identity");
        let identity_path = dir.join("identity");
        std::fs::write(&identity_path, generated.identity.render_toml()).expect("identity");
        #[cfg(unix)]
        std::fs::set_permissions(&identity_path, std::fs::Permissions::from_mode(0o600))
            .expect("identity perms");
        std::fs::write(dir.join("authorized_keys"), "").expect("authorized_keys");
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
        let config_path = write_valid_config_fixture(temp_dir.path());

        check_config(Some(&config_path)).expect("valid config should check out");
    }

    #[test]
    fn check_config_fails_clearly_for_a_missing_config_file() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let missing = temp_dir.path().join("does-not-exist.toml");

        assert!(check_config(Some(&missing)).is_err());
    }
}
