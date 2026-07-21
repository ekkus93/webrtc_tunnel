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
        Command::Keygen { peer_id, force } => commands::keygen(&peer_id, force)?,
        Command::Fingerprint { identity_pub } => commands::fingerprint(&identity_pub)?,
        Command::AddAuthorizedKey { identity_pub } => commands::add_authorized_key(&identity_pub)?,
        Command::CheckConfig { config } => commands::check_config(config.as_deref())?,
        Command::Status { config } => commands::status(config.as_deref())?,
    }

    Ok(())
}
