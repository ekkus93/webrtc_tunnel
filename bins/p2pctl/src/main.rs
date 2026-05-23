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
    let kid = kid_from_signing_key(&public_identity.sign_public);
    println!("peer_id={}", public_identity.peer_id);
    println!("fingerprint={kid}");
    Ok(())
}

fn add_authorized_key(path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let public_identity = load_public_identity(path)?;
    let authorized_keys_path = default_config_dir()?.join("authorized_keys");
    if authorized_keys_path.exists() {
        let existing = AuthorizedKeys::from_file(&authorized_keys_path)?;
        if existing.get_by_peer_id(&public_identity.peer_id).is_some() {
            return Err(format!(
                "peer '{}' already exists in {}",
                public_identity.peer_id,
                authorized_keys_path.display()
            )
            .into());
        }
    }

    let mut file = OpenOptions::new().create(true).append(true).open(&authorized_keys_path)?;
    use std::io::Write;
    writeln!(file, "{}", public_identity.render())?;
    println!("updated {}", authorized_keys_path.display());
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
    use p2p_crypto::generate_identity;
    use serde_json::json;

    use super::{render_status, write_identity_files};

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
}
