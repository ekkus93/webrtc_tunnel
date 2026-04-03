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
    let _: serde_json::Value = serde_json::from_str(&content)?;
    println!("{content}");
    Ok(())
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

    use super::write_identity_files;

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
}
