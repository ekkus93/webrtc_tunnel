//! p2pctl's subcommand implementations: keygen, fingerprint, add-authorized-key,
//! check-config, and status. Split out of `main.rs` (which now holds only CLI parsing
//! and dispatch) to stay under the repo's 800-line file-size guidance.

use std::fs::{self, OpenOptions};
use std::path::{Path, PathBuf};

use p2p_core::AppConfig;
use p2p_crypto::{
    AuthorizedKeys, IdentityFile, PublicIdentity, generate_identity, kid_from_signing_key,
};
use p2p_daemon::DaemonStatus;
use p2p_platform::{PlatformError, create_private_file, default_config_dir};

#[cfg(test)]
mod tests;

pub(crate) fn keygen(
    peer_id: &str,
    force: bool,
    dir: Option<&Path>,
) -> Result<(), Box<dyn std::error::Error>> {
    let generated = generate_identity(peer_id)?;
    let config_dir = explicit_or_default(dir, default_config_dir)?;
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

    create_private_file(&identity_path, generated.identity.render_toml().as_bytes())?;
    fs::write(&identity_pub_path, format!("{}\n", generated.public_identity.render()))?;
    Ok((identity_path, identity_pub_path, identity_exists || identity_pub_exists))
}

pub(crate) fn fingerprint(path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let public_identity = load_public_identity(path)?;
    print!("{}", render_fingerprint(&public_identity));
    Ok(())
}

fn render_fingerprint(public_identity: &PublicIdentity) -> String {
    let kid = kid_from_signing_key(&public_identity.sign_public);
    format!("peer_id={}\nfingerprint={kid}\n", public_identity.peer_id)
}

pub(crate) fn add_authorized_key(
    path: &Path,
    authorized_keys: Option<&Path>,
) -> Result<(), Box<dyn std::error::Error>> {
    let public_identity = load_public_identity(path)?;
    let authorized_keys_path =
        explicit_or_default(authorized_keys, || Ok(default_config_dir()?.join("authorized_keys")))?;
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

pub(crate) fn check_config(path: Option<&Path>) -> Result<(), Box<dyn std::error::Error>> {
    let config = load_config(path)?;
    let identity = IdentityFile::from_file(&config.paths.identity)?;
    config.validate_identity_peer(&identity.peer_id)?;

    // Match the daemon's own authorization preflight (`validate_config_authorized_peers`)
    // instead of only checking the identity: a config can be well-formed and still fail
    // to actually start if a required remote peer has no `authorized_keys` entry.
    let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)?;
    for peer_id in config.required_authorized_peer_ids()? {
        if authorized_keys.get_by_peer_id(peer_id).is_none() {
            return Err(format!(
                "required peer '{}' is missing from {}",
                peer_id,
                config.paths.authorized_keys.display(),
            )
            .into());
        }
    }

    println!("config ok for peer_id={}", config.node.peer_id);
    Ok(())
}

pub(crate) fn status(path: Option<&Path>) -> Result<(), Box<dyn std::error::Error>> {
    let config = load_config(path)?;
    let status_file = &config.health.status_file;
    if !status_file.exists() {
        return Err(format!("status file '{}' does not exist", status_file.display()).into());
    }

    let content = fs::read_to_string(status_file)?;
    let status: DaemonStatus = serde_json::from_str(&content)?;
    print!("{}", render_status(&status));
    Ok(())
}

/// Renders an enum that serializes to a plain JSON string (all of this crate's status
/// enums use `#[serde(rename_all = "snake_case"/"lowercase")]` with no data) as that bare
/// string, e.g. `DaemonState::TunnelOpen` -> `"tunnel_open"`.
fn render_enum_as_str<T: serde::Serialize>(value: &T) -> String {
    match serde_json::to_value(value) {
        Ok(serde_json::Value::String(rendered)) => rendered,
        _ => unreachable!("status enums always serialize to a JSON string"),
    }
}

fn render_status(status: &DaemonStatus) -> String {
    let mut output = String::new();
    output.push_str(&format!(
        "peer_id={} role={} mqtt_connected={} state={}\n",
        status.peer_id,
        render_enum_as_str(&status.role),
        status.mqtt_connected,
        render_enum_as_str(&status.current_state),
    ));
    output.push_str(&format!(
        "sessions={}/{}\n",
        status.active_session_count, status.session_capacity
    ));
    if status.sessions.is_empty() {
        output.push_str("sessions: none\n");
    } else {
        output.push_str("sessions:\n");
        for session in &status.sessions {
            output.push_str(&format!(
                "  {} peer={} state={} data_channel_open={} configured_forwards={}\n",
                session.session_id,
                session.remote_peer_id,
                render_enum_as_str(&session.state),
                session.data_channel_open,
                session.configured_forward_ids.join(","),
            ));
        }
    }
    output
}

fn load_config(path: Option<&Path>) -> Result<AppConfig, Box<dyn std::error::Error>> {
    let resolved = resolve_config_path(path, default_config_dir)?;
    Ok(AppConfig::load_from_file(&resolved)?)
}

fn load_public_identity(path: &Path) -> Result<PublicIdentity, Box<dyn std::error::Error>> {
    let content = fs::read_to_string(path)?;
    Ok(PublicIdentity::parse(content.trim())?)
}

/// Config-path resolution, parameterized on the default directory so it's testable
/// without touching the real environment: an explicit path is used as-is (the default
/// is never evaluated), otherwise falls back to `<default_config_dir>/config.toml`.
/// Mirrors the pattern used by `p2p-offer`/`p2p-answer`.
fn resolve_config_path(
    path: Option<&Path>,
    default_config_dir: impl FnOnce() -> Result<PathBuf, PlatformError>,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    match path {
        Some(path) => Ok(path.to_path_buf()),
        None => Ok(default_config_dir()?.join("config.toml")),
    }
}

/// An explicitly given path as-is, otherwise the lazily computed default, so an
/// explicit flag never consults the environment.
fn explicit_or_default(
    explicit: Option<&Path>,
    default: impl FnOnce() -> Result<PathBuf, PlatformError>,
) -> Result<PathBuf, PlatformError> {
    match explicit {
        Some(path) => Ok(path.to_path_buf()),
        None => default(),
    }
}
