use std::path::{Path, PathBuf};

use clap::{Parser, Subcommand};
use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::{
    ShutdownToken, apply_answer_overrides, apply_env_overrides, notify_ready, notify_stopping,
    run_answer_daemon_with_shutdown, setup_logging, wait_for_process_shutdown_signal,
};

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

    let shutdown = ShutdownToken::new();
    let daemon =
        run_answer_daemon_with_shutdown(config, local_identity, authorized_keys, shutdown.clone());
    tokio::pin!(daemon);

    // No-op unless built with --features sd-notify and running under a systemd
    // Type=notify unit; see crates/p2p-daemon/src/notify.rs.
    notify_ready();

    let result = tokio::select! {
        result = &mut daemon => result,
        signal = wait_for_process_shutdown_signal() => {
            let signal = signal?;
            tracing::info!(signal, "process shutdown requested");
            notify_stopping();
            shutdown.request_shutdown();
            daemon.await
        }
    };

    result?;
    Ok(())
}

fn load_config(path: Option<&Path>) -> Result<AppConfig, Box<dyn std::error::Error>> {
    let resolved = resolve_config_path(path, std::env::var_os("HOME").map(PathBuf::from))?;
    Ok(AppConfig::load_from_file(&resolved)?)
}

/// Config-path resolution, parameterized on `home` so it's testable without touching
/// the real `$HOME`: an explicit path is used as-is (ignoring `home` entirely), otherwise
/// falls back to `$HOME/.config/p2ptunnel/config.toml`.
fn resolve_config_path(
    path: Option<&Path>,
    home: Option<PathBuf>,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    match path {
        Some(path) => Ok(path.to_path_buf()),
        None => Ok(default_config_dir(home)?.join("config.toml")),
    }
}

fn default_config_dir(home: Option<PathBuf>) -> Result<PathBuf, Box<dyn std::error::Error>> {
    let home = home.ok_or("HOME is not set")?;
    Ok(home.join(".config/p2ptunnel"))
}

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};

    use super::resolve_config_path;

    #[test]
    fn explicit_config_path_is_used_as_is() {
        let explicit = Path::new("/etc/p2ptunnel/config.toml");
        let resolved = resolve_config_path(Some(explicit), None).expect("explicit path resolves");
        assert_eq!(resolved, explicit);
    }

    #[test]
    fn missing_config_flag_falls_back_to_home_config_dir() {
        let home = PathBuf::from("/home/answer-user");
        let resolved =
            resolve_config_path(None, Some(home.clone())).expect("home fallback resolves");
        assert_eq!(resolved, home.join(".config/p2ptunnel/config.toml"));
    }

    #[test]
    fn missing_home_produces_a_clear_error_not_a_panic() {
        let error =
            resolve_config_path(None, None).expect_err("missing HOME must error, not panic");
        assert!(error.to_string().contains("HOME is not set"));
    }
}
