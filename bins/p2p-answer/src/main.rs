use std::path::{Path, PathBuf};

use clap::{Parser, Subcommand};
use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::{apply_answer_overrides, apply_env_overrides, run_answer_daemon, setup_logging};

#[derive(Debug, Parser)]
#[command(name = "p2p-answer")]
#[command(about = "Run the answer-side daemon")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    Run {
        #[arg(long)]
        config: Option<PathBuf>,
        #[arg(long)]
        broker_url: Option<String>,
    },
}

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("Error: {error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error>> {
    let Command::Run { config, broker_url } = Cli::parse().command;
    let mut config = load_config(config.as_deref())?;
    apply_env_overrides(&mut config)?;
    apply_answer_overrides(&mut config, broker_url);
    config.validate()?;
    config.ensure_runtime_dirs()?;

    setup_logging(&config.logging)?;

    let local_identity = IdentityFile::from_file(&config.paths.identity)?;
    config.validate_identity_peer(&local_identity.peer_id)?;
    let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)?;
    run_answer_daemon(config, local_identity, authorized_keys).await?;
    Ok(())
}

fn load_config(path: Option<&Path>) -> Result<AppConfig, Box<dyn std::error::Error>> {
    let path = path
        .map(Path::to_path_buf)
        .unwrap_or_else(|| default_config_dir().expect("default config dir").join("config.toml"));
    Ok(AppConfig::load_from_file(&path)?)
}

fn default_config_dir() -> Result<PathBuf, Box<dyn std::error::Error>> {
    let home = std::env::var_os("HOME").ok_or("HOME is not set")?;
    Ok(PathBuf::from(home).join(".config/p2ptunnel"))
}
