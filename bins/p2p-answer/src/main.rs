use std::path::{Path, PathBuf};

use clap::{Parser, Subcommand};
use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::{
    ShutdownToken, apply_answer_overrides, apply_env_overrides, report_unenforced_platform_checks,
    run_answer_daemon_with_shutdown, setup_logging, wait_for_process_shutdown_signal,
};
use p2p_platform::PlatformError;

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
    report_unenforced_platform_checks();

    let local_identity = IdentityFile::from_file(&config.paths.identity)?;
    config.validate_identity_peer(&local_identity.peer_id)?;
    let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)?;

    let shutdown = ShutdownToken::new();
    let daemon =
        run_answer_daemon_with_shutdown(config, local_identity, authorized_keys, shutdown.clone());
    tokio::pin!(daemon);

    let result = tokio::select! {
        result = &mut daemon => result,
        signal = wait_for_process_shutdown_signal() => {
            let signal = signal?;
            tracing::info!(signal, "process shutdown requested");
            shutdown.request_shutdown();
            daemon.await
        }
    };

    result?;
    Ok(())
}

fn load_config(path: Option<&Path>) -> Result<AppConfig, Box<dyn std::error::Error>> {
    let resolved = resolve_config_path(path, p2p_platform::default_config_dir)?;
    Ok(AppConfig::load_from_file(&resolved)?)
}

/// Config-path resolution, parameterized on the default directory so it's testable
/// without touching the real environment: an explicit path is used as-is (the default
/// is never evaluated), otherwise falls back to `<default_config_dir>/config.toml`.
fn resolve_config_path(
    path: Option<&Path>,
    default_config_dir: impl FnOnce() -> Result<PathBuf, PlatformError>,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    match path {
        Some(path) => Ok(path.to_path_buf()),
        None => Ok(default_config_dir()?.join("config.toml")),
    }
}

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};

    use p2p_platform::PlatformError;

    use super::resolve_config_path;

    #[test]
    fn explicit_config_path_is_used_as_is() {
        let explicit = Path::new("/etc/p2ptunnel/config.toml");
        let resolved = resolve_config_path(Some(explicit), || {
            unreachable!("an explicit --config must not consult the default directory")
        })
        .expect("explicit path resolves");
        assert_eq!(resolved, explicit);
    }

    #[test]
    fn missing_config_flag_falls_back_to_the_default_config_dir() {
        let config_dir = PathBuf::from("/home/answer-user/.config/p2ptunnel");
        let resolved = resolve_config_path(None, || Ok(config_dir.clone()))
            .expect("default dir fallback resolves");
        assert_eq!(resolved, config_dir.join("config.toml"));
    }

    #[test]
    fn unresolvable_default_dir_produces_a_clear_error_not_a_panic() {
        let error = resolve_config_path(None, || {
            Err(PlatformError::Unresolved("HOME is not set".to_owned()))
        })
        .expect_err("an unresolvable default must error, not panic");
        assert!(error.to_string().contains("HOME is not set"));
    }
}
