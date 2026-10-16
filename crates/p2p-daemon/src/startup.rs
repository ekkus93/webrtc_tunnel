//! The startup sequence every daemon host runs before handing control to a daemon:
//! `p2p-answer`, `p2p-offer` and the Windows service wrapper. It is split in two so a
//! host can put `setup_logging` between the halves -- logging needs the validated
//! config, and a failure in the first half cannot reach the log file -- and so the
//! service can tell the phases apart in its exit codes.

use std::error::Error;
use std::path::Path;

use p2p_core::{AppConfig, ConfigError, NodeRole};
use p2p_crypto::{AuthorizedKeys, IdentityFile};

use crate::{apply_answer_overrides, apply_env_overrides, apply_offer_overrides};

/// What [`load_runtime_config`] needs beyond the path.
pub struct RuntimeConfigRequest<'a> {
    pub path: &'a Path,
    /// The daemon this host will run. Selects which `--broker-url` override applies.
    pub role: NodeRole,
    pub broker_url: Option<String>,
    /// Refuse a config whose `[node].role` is not `role`, before anything is created.
    /// The foreground binaries leave this off and behave exactly as they always did;
    /// the service sets it, because its role was fixed at install time.
    pub require_matching_role: bool,
}

/// Loads, overrides and validates the config, then creates its runtime directories.
pub fn load_runtime_config(request: RuntimeConfigRequest<'_>) -> Result<AppConfig, ConfigError> {
    let mut config = AppConfig::load_from_file(request.path)?;
    apply_env_overrides(&mut config)?;
    match request.role {
        NodeRole::Answer => apply_answer_overrides(&mut config, request.broker_url),
        NodeRole::Offer => apply_offer_overrides(&mut config, request.broker_url),
    }
    check_role(request.path, &config.node.role, &request.role, request.require_matching_role)?;
    config.validate()?;
    config.ensure_runtime_dirs()?;
    Ok(config)
}

fn check_role(
    path: &Path,
    configured: &NodeRole,
    running: &NodeRole,
    require_match: bool,
) -> Result<(), ConfigError> {
    if require_match && configured != running {
        return Err(ConfigError::InvalidConfig(format!(
            "{} is a {configured:?} config, but this host runs the {running:?} role",
            path.display(),
        )));
    }
    Ok(())
}

/// Loads the local identity (refusing loose permissions), checks it matches the
/// config's `peer_id`, and loads the authorized keys.
pub fn load_identity_and_keys(
    config: &AppConfig,
) -> Result<(IdentityFile, AuthorizedKeys), Box<dyn Error>> {
    let identity = IdentityFile::from_file(&config.paths.identity)?;
    config.validate_identity_peer(&identity.peer_id)?;
    let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)?;
    Ok((identity, authorized_keys))
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use p2p_core::NodeRole;

    use super::check_role;

    #[test]
    fn a_mismatched_role_is_refused_when_the_host_requires_a_match() {
        let error = check_role(Path::new("c.toml"), &NodeRole::Offer, &NodeRole::Answer, true)
            .expect_err("an offer config must not run as the answer role");
        let message = error.to_string();
        assert!(message.contains("c.toml") && message.contains("Offer"), "got: {message}");
    }

    #[test]
    fn a_matching_role_is_accepted_when_a_match_is_required() {
        check_role(Path::new("c.toml"), &NodeRole::Answer, &NodeRole::Answer, true)
            .expect("same role");
    }

    #[test]
    fn the_foreground_binaries_keep_not_checking_the_role() {
        // They never did; only the service, whose role is fixed at install time, asks.
        check_role(Path::new("c.toml"), &NodeRole::Offer, &NodeRole::Answer, false)
            .expect("no role check unless the host asks for one");
    }
}
