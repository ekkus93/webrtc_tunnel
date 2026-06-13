//! Stateless config/identity validation entry points on [`AndroidTunnelController`].
//! These never touch the live runtime; they load and check on-disk (or supplied)
//! config and identity material and report the result to the UI.

use std::path::Path;

use p2p_core::AppConfig;
use p2p_crypto::IdentityFile;

use super::{AndroidTunnelController, AndroidValidationResult};

impl AndroidTunnelController {
    pub fn validate_config(config_path: impl AsRef<Path>) -> AndroidValidationResult {
        match AppConfig::load_from_file(config_path.as_ref()) {
            Ok(_) => AndroidValidationResult { valid: true, message: None },
            Err(error) => {
                AndroidValidationResult { valid: false, message: Some(error.to_string()) }
            }
        }
    }

    pub fn validate_config_with_identity(
        config_path: impl AsRef<Path>,
        identity_toml: &str,
    ) -> AndroidValidationResult {
        let config = match AppConfig::load_from_file_with_identity_override(config_path.as_ref()) {
            Ok(config) => config,
            Err(error) => {
                return AndroidValidationResult { valid: false, message: Some(error.to_string()) };
            }
        };
        let identity = match IdentityFile::from_toml(identity_toml) {
            Ok(identity) => identity,
            Err(error) => {
                return AndroidValidationResult { valid: false, message: Some(error.to_string()) };
            }
        };
        match config.validate_identity_peer(&identity.peer_id) {
            Ok(_) => AndroidValidationResult { valid: true, message: None },
            Err(error) => {
                AndroidValidationResult { valid: false, message: Some(error.to_string()) }
            }
        }
    }
}
