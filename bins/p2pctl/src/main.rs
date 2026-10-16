use std::path::PathBuf;

use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;

mod commands;

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
        /// Write the identity into this directory instead of the default config
        /// directory, e.g. a service's config directory, so the key is created
        /// there under that directory's permissions rather than copied in later.
        #[arg(long)]
        dir: Option<PathBuf>,
    },
    Fingerprint {
        identity_pub: PathBuf,
    },
    AddAuthorizedKey {
        identity_pub: PathBuf,
        /// Append to this authorized_keys file instead of the one in the default
        /// config directory.
        #[arg(long)]
        authorized_keys: Option<PathBuf>,
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
        Command::Keygen { peer_id, force, dir } => {
            commands::keygen(&peer_id, force, dir.as_deref())?
        }
        Command::Fingerprint { identity_pub } => commands::fingerprint(&identity_pub)?,
        Command::AddAuthorizedKey { identity_pub, authorized_keys } => {
            commands::add_authorized_key(&identity_pub, authorized_keys.as_deref())?
        }
        Command::CheckConfig { config } => commands::check_config(config.as_deref())?,
        Command::Status { config } => commands::status(config.as_deref())?,
    }

    Ok(())
}
