//! The service's command line: which role it hosts, and where that role's
//! config lives.

use std::path::PathBuf;

use clap::{Parser, ValueEnum};
use p2p_core::NodeRole;
use p2p_platform::PlatformError;

#[derive(Clone, Copy, Debug, Eq, PartialEq, ValueEnum)]
pub(crate) enum Role {
    Answer,
    Offer,
}

impl Role {
    /// The role's name as it appears in `--role` and in its config directory.
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Role::Answer => "answer",
            Role::Offer => "offer",
        }
    }

    pub(crate) fn node_role(self) -> NodeRole {
        match self {
            Role::Answer => NodeRole::Answer,
            Role::Offer => NodeRole::Offer,
        }
    }
}

/// The arguments in the service's `binPath`. The installer passes only
/// `--role`, fixing which daemon this machine's single `p2ptunnel` service runs.
#[derive(Debug, Parser)]
#[command(name = "p2p-service-windows")]
#[command(about = "Run the offer or answer daemon as the Windows service 'p2ptunnel'")]
pub(crate) struct Invocation {
    /// Which daemon this service hosts.
    #[arg(long, value_enum)]
    pub(crate) role: Role,
    /// Override the config file. By default it is `config.toml` in the role's
    /// service config directory, the same path the installer seeds.
    #[arg(long)]
    pub(crate) config: Option<PathBuf>,
}

impl Invocation {
    pub(crate) fn config_path(&self) -> Result<PathBuf, PlatformError> {
        match &self.config {
            Some(path) => Ok(path.clone()),
            None => Ok(p2p_platform::service_config_dir(self.role.as_str())?.join("config.toml")),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};

    use clap::Parser;

    use super::{Invocation, Role};

    fn parse(args: &[&str]) -> Result<Invocation, clap::Error> {
        Invocation::try_parse_from(
            std::iter::once("p2p-service-windows").chain(args.iter().copied()),
        )
    }

    #[test]
    fn role_is_required() {
        assert!(parse(&[]).is_err());
    }

    #[test]
    fn only_the_two_daemon_roles_are_accepted() {
        assert_eq!(parse(&["--role", "answer"]).expect("answer").role, Role::Answer);
        assert_eq!(parse(&["--role", "offer"]).expect("offer").role, Role::Offer);
        assert!(parse(&["--role", "both"]).is_err());
    }

    #[test]
    fn default_config_is_in_the_roles_service_config_dir() {
        let invocation = parse(&["--role", "answer"]).expect("parse");
        let path = invocation.config_path().expect("service config dir resolves");
        let expected = p2p_platform::service_config_dir("answer")
            .expect("service config dir resolves")
            .join("config.toml");
        assert_eq!(path, expected);
    }

    #[test]
    fn explicit_config_overrides_the_default() {
        let invocation =
            parse(&["--role", "offer", "--config", "C:/elsewhere/config.toml"]).expect("parse");
        assert_eq!(
            invocation.config_path().expect("explicit path"),
            PathBuf::from("C:/elsewhere/config.toml")
        );
    }

    #[test]
    fn roles_map_to_their_node_role_and_directory_name() {
        // `service` uses both of these to check the config matches the
        // installed role; on non-Windows hosts this test is their only caller.
        assert_eq!(Role::Answer.node_role(), p2p_core::NodeRole::Answer);
        assert_eq!(Role::Offer.node_role(), p2p_core::NodeRole::Offer);
        assert_eq!(Path::new(Role::Offer.as_str()), Path::new("offer"));
    }
}
